package org.alien4cloud.kubernetes.modifiers;

import lombok.extern.java.Log;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.ITopologyModifier;
import org.alien4cloud.tosca.model.templates.Topology;

/**
 * Created by xdegenne on 13/10/2017.
 */
@Log
public class KubernetesLocationModifier implements ITopologyModifier {

    @Override
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());
    }

}
