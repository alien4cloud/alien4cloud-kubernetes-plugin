package org.alien4cloud.plugin.kubernetes.modifier;

import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.paas.wf.WorkflowSimplifyService;
import alien4cloud.paas.wf.WorkflowsBuilderService;
import alien4cloud.paas.wf.util.WorkflowUtils;
import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.topology.TopologyService;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.tosca.serializer.ToscaPropertySerializerUtils;
import alien4cloud.utils.CloneUtil;
import alien4cloud.utils.MapUtil;
import alien4cloud.utils.PropertyUtil;
import alien4cloud.utils.YamlParserUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.plugin.kubernetes.modifier.helpers.AffinitiyHelper;
import org.alien4cloud.plugin.kubernetes.modifier.helpers.AntiAffinityHelper;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.*;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.model.types.PolicyType;
import org.alien4cloud.tosca.model.workflow.Workflow;
import org.alien4cloud.tosca.normative.constants.NormativeCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.normative.constants.NormativeWorkflowNameConstants;
import org.alien4cloud.tosca.normative.constants.ToscaFunctionConstants;
import org.alien4cloud.tosca.utils.FunctionEvaluator;
import org.alien4cloud.tosca.utils.FunctionEvaluatorContext;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.alien4cloud.tosca.utils.ToscaTypeUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.*;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.*;
import static org.alien4cloud.tosca.utils.ToscaTypeUtils.isOfType;

/**
 * Remove delete steps for nodes when a namespace resource is found.
 */
@Slf4j
@Component(value = "kubernetes-cleanuninstall-modifier")
public class KubernetesCleanUninstallModifier extends AbstractKubernetesModifier {

    @Resource
    private WorkflowSimplifyService workflowSimplifyService;

    @Resource
    private WorkflowsBuilderService workflowBuilderService;

    @Resource
    private TopologyService topologyService;

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(new KubernetesModifierContext(topology, context));
        } catch (Exception e) {
            context.getLog().error("KubernetesCleanUninstallModifier Can't process");
            log.warn("KubernetesCleanUninstallModifier Can't process ", e);
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
        }
    }


    private void removeDeleteOperations(Map<String, NodeTemplate> nodeTemplates, Topology topology) {
        Workflow uninstallWorkflow = topology.getWorkflow(NormativeWorkflowNameConstants.UNINSTALL);
        if (uninstallWorkflow != null) {
            Set<String> stepsToRemove = Sets.newHashSet();
            uninstallWorkflow.getSteps().forEach((s, workflowStep) -> {
                if (nodeTemplates.containsKey(workflowStep.getTarget())) {
                    stepsToRemove.add(s);
                }
            });
            for (String stepId : stepsToRemove) {
                WorkflowUtils.removeStep(uninstallWorkflow, stepId, true);
            }
        }
    }

    private void doProcess(KubernetesModifierContext context) {
        String providedNamespace = getProvidedMetaproperty(context.getFlowExecutionContext(), K8S_NAMESPACE_METAPROP_NAME);
        if (providedNamespace != null) {
            return;
        }

        Topology topology = context.getTopology();

        // Cache The Type Loader
        topologyService.prepareTypeLoaderCache(topology);

        Map<String, NodeTemplate> nodeTemplatesToBoost = Maps.newHashMap();
        boolean nameSpaceResourceFound = false;
        Set<NodeTemplate> resourcesNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_BASE_RESOURCE, true);
        for (NodeTemplate resourceNode : resourcesNodes) {

            boolean thisIsNameSpaceResource = false;
            AbstractPropertyValue apv = PropertyUtil.getPropertyValueFromPath(resourceNode.getProperties(), "resource_type");
            if (apv != null && apv instanceof ScalarPropertyValue) {
                String resourceType = ((ScalarPropertyValue)apv).getValue();
                if (resourceType.equals("namespaces")) {
                    nameSpaceResourceFound = true;
                    thisIsNameSpaceResource = true;
                }
            }
            if (!thisIsNameSpaceResource) {
                nodeTemplatesToBoost.put(resourceNode.getName(), resourceNode);
            }
        }

        if (!nameSpaceResourceFound) {
            // No namespace resource found, do nothing
            return;
        }
        if (!nodeTemplatesToBoost.isEmpty()) {
            removeDeleteOperations(nodeTemplatesToBoost, topology);
        }

    }

}
