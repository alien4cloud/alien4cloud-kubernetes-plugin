package org.alien4cloud;

import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.AlienUtils;
import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.java.Log;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.plugin.kubernetes.modifier.AbstractKubernetesTopologyModifier;
import org.alien4cloud.plugin.kubernetes.modifier.KubeAttributeDetector;
import org.alien4cloud.tosca.exceptions.InvalidPropertyValueException;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.DataType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.normative.primitives.Size;
import org.alien4cloud.tosca.normative.primitives.SizeUnit;
import org.alien4cloud.tosca.normative.types.SizeType;
import org.alien4cloud.tosca.normative.types.ToscaTypes;
import org.alien4cloud.tosca.utils.FunctionEvaluator;
import org.alien4cloud.tosca.utils.FunctionEvaluatorContext;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Just for tests : a modifier that replace all K8S abstract nodes by it's Concrete implem.
 */
@Log
@Component(value = "kubernetes-automatching-modifier")
public class KubernetesAutomatchingTopologyModifier extends AbstractKubernetesTopologyModifier {

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        // replace each AbstractContainer by Container
        Set<NodeTemplate> containerNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_ABSTRACT_CONTAINER, false);
        containerNodes.forEach(nodeTemplate -> {
            replaceNode(csar, topology, nodeTemplate, K8S_TYPES_CONTAINER, K8S_CSAR_VERSION);
        });
        // replace each AbstractService by Service
        Set<NodeTemplate> serviceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_ABSTRACT_SERVICE, false);
        serviceNodes.forEach(nodeTemplate -> {
            replaceNode(csar, topology, nodeTemplate, K8S_TYPES_SERVICE, K8S_CSAR_VERSION);
        });
        // replace each AbstractDeployment by Deployment
        Set<NodeTemplate> deploymentNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_ABSTRACT_DEPLOYMENT, false);
        deploymentNodes.forEach(nodeTemplate -> {
            replaceNode(csar, topology, nodeTemplate, K8S_TYPES_DEPLOYMENT, K8S_CSAR_VERSION);
        });

    }


}
