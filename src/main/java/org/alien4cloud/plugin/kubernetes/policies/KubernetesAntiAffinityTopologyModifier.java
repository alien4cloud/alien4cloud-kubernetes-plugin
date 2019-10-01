package org.alien4cloud.plugin.kubernetes.policies;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.K8S_POLICIES_ANTI_AFFINITY_LABEL;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.POD_ANTI_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.PropertyUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * This topology modifiers is associated with the kubernetes anti-affinity policy.
 */
@Component("kubernetes-anti-affinity-modifier")
@Slf4j
public class KubernetesAntiAffinityTopologyModifier extends AbstractKubernetesModifier {

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
       // DO NOTHING
    }
    
}