package org.alien4cloud.plugin.kubernetes.modifier;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.ITopologyModifier;
import org.alien4cloud.tosca.model.templates.Topology;
import org.springframework.stereotype.Component;

/**
 * This topology modifiers convert a TOSCA topology into the equivalent Kubernetes specific topology.
 */
@Component(value = "kubernetes-modifier")
public class KubeTopologyModifier implements ITopologyModifier {

    @Override
    public void process(Topology topology, FlowExecutionContext context) {

    }
}
