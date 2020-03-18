package org.alien4cloud.plugin.kubernetes.modifier;

import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_SERVICE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE;

import java.util.Set;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.springframework.stereotype.Component;

import alien4cloud.tosca.context.ToscaContextual;
import lombok.extern.java.Log;

/**
 * Just for tests : simulate matching by replacing all K8S abstract nodes by it's Concrete implem.
 */
@Log
@Component(value = "kubernetes-automatching-modifier")
public class KubernetesAutomatchingTopologyModifier extends TopologyModifierSupport {

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        // replace each AbstractContainer by Container
        Set<NodeTemplate> containerNodes = this.getNodesOfType(context, topology, K8S_TYPES_ABSTRACT_CONTAINER, false);
        containerNodes.forEach(nodeTemplate -> {
            replaceNode(csar, topology, nodeTemplate, K8S_TYPES_CONTAINER, K8S_CSAR_VERSION);
        });
        // replace each AbstractService by Service
        Set<NodeTemplate> serviceNodes = this.getNodesOfType(context, topology, K8S_TYPES_ABSTRACT_SERVICE, false);
        serviceNodes.forEach(nodeTemplate -> {
            replaceNode(csar, topology, nodeTemplate, K8S_TYPES_SERVICE, K8S_CSAR_VERSION);
        });
        // replace each AbstractDeployment by Deployment
        Set<NodeTemplate> deploymentNodes = this.getNodesOfType(context, topology, K8S_TYPES_ABSTRACT_DEPLOYMENT, false);
        deploymentNodes.forEach(nodeTemplate -> {
            replaceNode(csar, topology, nodeTemplate, K8S_TYPES_DEPLOYMENT, K8S_CSAR_VERSION);
        });

    }


}
