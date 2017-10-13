package alien4cloud.it;

import alien4cloud.application.ApplicationService;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.FacetedSearchResult;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.exception.NotFoundException;
import alien4cloud.it.common.CommonStepDefinitions;
import alien4cloud.model.application.Application;
import alien4cloud.model.application.ApplicationEnvironment;
import alien4cloud.model.application.ApplicationVersion;
import alien4cloud.model.components.CSARSource;
import alien4cloud.rest.utils.JsonUtil;
import alien4cloud.security.model.User;
import alien4cloud.topology.TopologyDTO;
import alien4cloud.tosca.parser.ParserTestUtil;
import alien4cloud.tosca.parser.ParsingErrorLevel;
import alien4cloud.tosca.parser.ParsingResult;
import alien4cloud.utils.AlienConstants;
import alien4cloud.utils.FileUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import cucumber.api.DataTable;
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
import org.alien4cloud.tosca.catalog.ArchiveUploadService;
import org.alien4cloud.tosca.catalog.index.CsarService;
import org.alien4cloud.tosca.catalog.index.ITopologyCatalogService;
import org.alien4cloud.tosca.editor.EditionContextManager;
import org.alien4cloud.tosca.editor.EditorService;
import org.alien4cloud.tosca.editor.operations.AbstractEditorOperation;
import org.alien4cloud.tosca.editor.operations.UpdateFileOperation;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.*;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.Assert;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
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

@ContextConfiguration("classpath:org/alien4cloud/kubernetes/modifiers/application-context-test.xml")
@Slf4j
public class ModifierTestStepDefs {

    CommonStepDefinitions commonStepDefinitions = new CommonStepDefinitions();

    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;
    @Inject
    private ArchiveUploadService csarUploadService;
    @Inject
    private EditorService editorService;
    @Inject
    private EditionContextManager editionContextManager;
    @Inject
    private CsarService csarService;
    @Inject
    private ITopologyCatalogService catalogService;
    @Inject
    private ApplicationService applicationService;

    private LinkedList<String> topologyIds = new LinkedList();

    private EvaluationContext topologyEvaluationContext;
    private EvaluationContext dtoEvaluationContext;
    private EvaluationContext exceptionEvaluationContext;
    private EvaluationContext csarEvaluationContext;

    private Exception thrownException;

    private Map<String, String> topologyIdToLastOperationId = new HashMap<>();

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

        topologyIds.clear();
        editionContextManager.clearCache();
    }

    @When("^I execute the modifier \"(.*?)\" on the current topology$")
    public void i_execute_the_modifier_on_the_current_topology(String modifierClass) throws Throwable {
        String topologyId = Context.getInstance().getTopologyId();

        // Write code here that turns the phrase above into concrete actions
        Context.getInstance().registerRestResponse(Context.getRestClientInstance().get("/rest/v1/catalog/topologies/" + topologyId));
        commonStepDefinitions.I_should_receive_a_RestResponse_with_no_error();

        // register the retrieved topology as SPEL context
        Topology topology = JsonUtil.read(Context.getInstance().getRestResponse(), Topology.class, Context.getJsonMapper()).getData();

        Class clazz = Class.forName(modifierClass);
        ITopologyModifier modifier = (ITopologyModifier)BeanUtils.instantiate(clazz);
        FlowExecutionContext executionContext = new FlowExecutionContext(alienDAO, topology, new EnvironmentContext(null, null));
        modifier.process(topology, executionContext);
    }

}