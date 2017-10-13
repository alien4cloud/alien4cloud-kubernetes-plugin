package org.alien4cloud.plugin.kubernetes.policies;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.ITopologyModifier;
import org.alien4cloud.tosca.model.templates.Topology;
import org.springframework.stereotype.Component;

/**
 * This topology modifiers is associated with the kubernetes anti-affinity policy.
 */
@Component("kubernetes-anti-affinity-modifier")
public class AntiAffinityTopologyModifier implements ITopologyModifier {
    @Override
    public void process(Topology topology, FlowExecutionContext context) {

    }
}