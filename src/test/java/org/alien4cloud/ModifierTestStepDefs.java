package org.alien4cloud;

import alien4cloud.application.ApplicationService;
import alien4cloud.csar.services.CsarGitRepositoryService;
import alien4cloud.csar.services.CsarGitService;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.FacetedSearchResult;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.exception.NotFoundException;
import alien4cloud.model.application.Application;
import alien4cloud.model.application.ApplicationEnvironment;
import alien4cloud.model.application.ApplicationVersion;
import alien4cloud.model.components.CSARSource;
import alien4cloud.model.git.CsarGitCheckoutLocation;
import alien4cloud.model.git.CsarGitRepository;
import alien4cloud.repository.services.RepositoryService;
import alien4cloud.rest.utils.JsonUtil;
import alien4cloud.security.model.User;
import alien4cloud.topology.TopologyDTO;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.parser.*;
import alien4cloud.tosca.topology.TemplateBuilder;
import alien4cloud.utils.AlienConstants;
import alien4cloud.utils.FileUtil;
import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import cucumber.api.DataTable;
import cucumber.api.PendingException;
import cucumber.api.java.Before;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import gherkin.formatter.model.DataTableRow;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.alm.deployment.configuration.flow.EnvironmentContext;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.ITopologyModifier;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.alm.deployment.configuration.services.DeploymentConfigurationDao;
import org.alien4cloud.tosca.catalog.ArchiveUploadService;
import org.alien4cloud.tosca.catalog.index.CsarService;
import org.alien4cloud.tosca.catalog.index.ITopologyCatalogService;
import org.alien4cloud.tosca.editor.EditionContextManager;
import org.alien4cloud.tosca.editor.EditorService;
import org.alien4cloud.tosca.editor.operations.AbstractEditorOperation;
import org.alien4cloud.tosca.editor.operations.UpdateFileOperation;
import org.alien4cloud.tosca.exporter.ArchiveExportService;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.*;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.Assert;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import javax.annotation.Resource;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.alien4cloud.test.util.SPELUtils.evaluateAndAssertExpression;
import static org.mockito.Mockito.*;

@ContextConfiguration("classpath:org/alien4cloud/kubernetes/modifiers/application-context-test.xml")
@Slf4j
public class ModifierTestStepDefs {

    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;
    @Inject
    private DeploymentConfigurationDao deploymentConfigurationDao;
    @Inject
    private ArchiveUploadService csarUploadService;
    @Inject
    private EditionContextManager editionContextManager;
    @Inject
    private CsarService csarService;
    @Inject
    private CsarGitRepositoryService csarGitRepositoryService;
    @Inject
    private CsarGitService csarGitService;
    @Inject
    private ArchiveExportService archiveExportService;

    @Inject
    private ITopologyCatalogService catalogService;
    @Inject
    private ApplicationService applicationService;
    @Inject
    private ApplicationContext applicationContext;

    private Exception thrownException;

    private Topology currentTopology;

    private List<Class> typesToClean = Lists.newArrayList();
    public static final Path CSAR_TARGET_PATH = Paths.get("target/csars");

    public ModifierTestStepDefs() {
        super();
        typesToClean.add(AbstractInstantiableToscaType.class);
        typesToClean.add(AbstractToscaType.class);
        typesToClean.add(CapabilityType.class);
        typesToClean.add(ArtifactType.class);
        typesToClean.add(RelationshipType.class);
        typesToClean.add(NodeType.class);
        typesToClean.add(DataType.class);
        typesToClean.add(PrimitiveDataType.class);
        typesToClean.add(Csar.class);
        typesToClean.add(Topology.class);
        typesToClean.add(Application.class);
        typesToClean.add(ApplicationEnvironment.class);
        typesToClean.add(ApplicationVersion.class);
        typesToClean.add(CsarGitRepository.class);
    }

    @Before
    public void init() throws IOException {
        thrownException = null;

        GetMultipleDataResult<Application> apps = alienDAO.search(Application.class, "", null, 100);
        for (Application application : apps.getData()) {
            applicationService.delete(application.getId());
        }

        FacetedSearchResult<Topology> searchResult = catalogService.search(Topology.class, "", 100, null);
        Topology[] topologies = searchResult.getData();
        for (Topology topology : topologies) {
            try {
                csarService.forceDeleteCsar(topology.getId());
            } catch (NotFoundException e) {
                // Some previous tests may create topology without creating any archive, if so catch the exception
                alienDAO.delete(Topology.class, topology.getId());
            }
        }

        editionContextManager.clearCache();

        for (Class<?> type : typesToClean) {
            alienDAO.delete(type, QueryBuilders.matchAllQuery());
        }
    }

    @Given("^I am authenticated with \"(.*?)\" role$")
    public void i_am_authenticated_with_role(String role) throws Throwable {
        User user = new User();
        user.setUsername("Username");
        user.setFirstName("firstName");
        user.setLastName("lastname");
        user.setEmail("user@fastco");
        Authentication auth = new TestAuth(user, role);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static class TestAuth extends UsernamePasswordAuthenticationToken {
        Collection<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();

        public TestAuth(User user, String role) {
            super(user, null);
            authorities.add(new SimpleGrantedAuthority(role));
        }

        @Override
        public Collection<GrantedAuthority> getAuthorities() {
            return authorities;
        }
    }

    @Given("^I add and import a GIT repository with url \"(.*?)\" usr \"(.*?)\" pwd \"(.*?)\" stored \"(.*?)\" and locations$")
    public void i_add_a_GIT_repository_with_url_usr_pwd_stored_and_locations(String url, String usr, String pwd, boolean stored,
                                                                             List<CsarGitCheckoutLocation> locations) throws Throwable {

        String id = csarGitRepositoryService.create(url, usr, pwd, locations, stored);
        List<ParsingResult<Csar>> results = csarGitService.importFromGitRepository(id);
        for (ParsingResult<Csar> result : results) {
            if (result.hasError(ParsingErrorLevel.ERROR)) {
                for (ParsingError error : result.getContext().getParsingErrors()) {
                    log.error("Parsing error context: {}, {}", error.getContext(), error.getNote());
                }
//                throw new Exception("Parsing error while importing CSARs from GIT");
            }
        }

    }

    @When("^I upload unzipped CSAR from path \"(.*?)\"$")
    public void i_upload_unzipped_CSAR_From_path(String path) throws Throwable {
        Path source = Paths.get(path);
        Path csarTargetPath = CSAR_TARGET_PATH.resolve(source.getFileName() + ".csar");
        FileUtil.zip(source, csarTargetPath);
        uploadCsar(csarTargetPath);
    }

    private void uploadCsar(Path path) throws Throwable {
        ParsingResult<Csar> result = csarUploadService.upload(path, CSARSource.UPLOAD, AlienConstants.GLOBAL_WORKSPACE_ID);
        if (result.getContext().getParsingErrors().size() > 0) {
            ParserTestUtil.displayErrors(result);
        }
        Assert.assertFalse(result.hasError(ParsingErrorLevel.ERROR));
    }

    @And("^I get the topology related to the CSAR with name \"([^\"]*)\" and version \"([^\"]*)\"$")
    public void iGetTheTopologyRelatedToTheCSARWithName(String archiveName, String archiveVersion) throws Throwable {
        Topology topology = catalogService.get(archiveName + ":" + archiveVersion);
        if (topology != null) {
            currentTopology = topology;
        }
    }

    @When("^I execute the modifier \"(.*?)\" on the current topology$")
    public void i_execute_the_modifier_on_the_current_topology(String beanName) throws Throwable {
        Topology topology = currentTopology;

        ITopologyModifier modifier = (ITopologyModifier)applicationContext.getBean(beanName);
        FlowExecutionContext executionContext = new FlowExecutionContext(deploymentConfigurationDao, topology, new EnvironmentContext(null, null));
        modifier.process(topology, executionContext);
        log.debug("Topology processed");
        String yaml = archiveExportService.getYaml(new Csar(topology.getArchiveName(), topology.getArchiveVersion()), topology, false, ToscaParser.LATEST_DSL);
        log.info(yaml);
        System.out.println("yaml = " + yaml);
    }

    @When("^I match the policy named \"(.*?)\" to the concrete policy of type \"(.*?)\"$")
    public void i_match_the_policy_named_to_the_concrete_policy_of_type_version(String policyName, String newPpolicyType) throws Throwable {
        // TODO: uggly quick code to be refactored (use existing policy matching code ?)
        PolicyTemplate policy = currentTopology.getPolicies().get(policyName);
        ToscaContext.init(currentTopology.getDependencies());
        PolicyType policyType = ToscaContext.get(PolicyType.class, newPpolicyType);
        ToscaContext.destroy();
        PolicyTemplate tempObject = TemplateBuilder.buildPolicyTemplate(policyType, policy, false);
        tempObject.setName(policy.getName());
        tempObject.setTargets(policy.getTargets());
        currentTopology.getPolicies().put(policyName, tempObject);
    }

    @When("^I set the policy \"(.*?)\" property \"(.*?)\" to \"(.*?)\"$")
    public void i_set_the_policy_property_to(String policyName, String propertyName, String propertyValue) throws Throwable {
        PolicyTemplate policy = currentTopology.getPolicies().get(policyName);
        ScalarPropertyValue scalarPropertyValue = new ScalarPropertyValue(propertyValue);
        TopologyModifierSupport.feedPropertyValue(policy.getProperties(), propertyName, scalarPropertyValue, false);
    }

}