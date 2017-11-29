package org.alien4cloud.plugin.kubernetes;

import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;

public abstract class AbstractKubernetesModifier extends TopologyModifierSupport {

    protected Set<NodeTemplate> getValidTargets(PolicyTemplate policyTemplate, Topology topology, Consumer<String> invalidTargetConsumer) {
        Set<NodeTemplate> targetedMembers = TopologyNavigationUtil.getTargetedMembers(topology, policyTemplate);
        Iterator<NodeTemplate> iter = targetedMembers.iterator();
        while (iter.hasNext()) {
            NodeTemplate nodeTemplate = iter.next();
            // TODO maybe better to consider type hierarchy and check if the node is from
            // org.alien4cloud.kubernetes.api.types.AbstractDeployment
            if (!Objects.equals(K8S_TYPES_DEPLOYMENT, nodeTemplate.getType())) {
                invalidTargetConsumer.accept(nodeTemplate.getName());
                iter.remove();
            }
        }
        return targetedMembers;
    }
}
