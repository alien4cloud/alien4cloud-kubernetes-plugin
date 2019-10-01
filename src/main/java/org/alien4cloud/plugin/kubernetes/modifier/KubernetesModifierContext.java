package org.alien4cloud.plugin.kubernetes.modifier;

import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.Setter;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionLog;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.Topology;

import java.util.Map;

@Getter
@Setter
public class KubernetesModifierContext {

    private final Topology topology;

    private final FlowExecutionContext flowExecutionContext;

    private final Csar csar;

    /**
     * Just a map that store the node name as key and the replacement node as value
     */
    private final Map<String, NodeTemplate> replacements = Maps.newHashMap();

    /**
     * Store the yaml structure for each resources.
     *
     * these yaml structure will become the JSON resource spec after JSON serialization
     * these yaml structures can not be stored in node since they can't respect any TOSCA contract
     */
    Map<String, Map<String, AbstractPropertyValue>> yamlResources = Maps.newHashMap();

    public KubernetesModifierContext(Topology toplogy, FlowExecutionContext flowExecutionContext) {
        this.topology = toplogy;
        this.flowExecutionContext = flowExecutionContext;

        this.csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());
    }

    public FlowExecutionLog log() {
        return flowExecutionContext.log();
    }
}