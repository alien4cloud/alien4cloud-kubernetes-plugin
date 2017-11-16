package org.alien4cloud.plugin.kubernetes.policies;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ListPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import alien4cloud.tosca.context.ToscaContextual;
import lombok.extern.slf4j.Slf4j;

/**
 * This topology modifier is associated with the kubernetes node affinity label placement policy.
 */
@Component("kubernetes-node-affinity-modifier")
@Slf4j
public class NodeAffinityLabelTopologyModifier extends TopologyModifierSupport {

    private static final Map<String, String> LEVEL_TO_TOPOLOGY_KEY = Maps.newHashMap();

    static {
        LEVEL_TO_TOPOLOGY_KEY.put("host", "kubernetes.io/hostname");
        LEVEL_TO_TOPOLOGY_KEY.put("zone", "failure-domain.beta.kubernetes.io/zone");
        LEVEL_TO_TOPOLOGY_KEY.put("region", "failure-domain.beta.kubernetes.io/regon");
    }

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing node affinity label placement policy for topology " + topology.getId());
        List<PolicyTemplate> policies = safe(topology.getPolicies()).values().stream()
                .filter(policyTemplate -> Objects.equals(KubePoliciesConstants.K8S_POLICIES_NODE_AFFINITY_LABEL, policyTemplate.getType()))
                .collect(Collectors.toList());

        safe(policies).forEach(policyTemplate -> apply(policyTemplate, topology, context));
    }

    /**
     * Add affinity data to the targeted nodes<br/>
     *
     * template.metadata.label, template.spec.affinity section
     *
     * @param policy
     * @param topology
     * @param context
     */
    private void apply(PolicyTemplate policy, Topology topology, FlowExecutionContext context) {
        if (safe(policy.getTargets()).isEmpty()) {
            context.log().warn("Placement policy <{}> is not correctly configured, at least 1 targets is required. It will be ignored.", policy.getName());
            return;
        }

        Map<String, AbstractPropertyValue> properties = safe(policy.getProperties());

        // prefer matchExpressions property
        if (properties.get("matchExpressions") != null) {
            apply(policy, topology, context, () -> ((ListPropertyValue) properties.get("matchExpressions")).getValue());
        } else if (properties.get("labels") != null) {
            // if not, then consider labels property
            apply(policy, topology, context, () -> {
                List<Object> matchExpressions = Lists.newArrayList();
                // add every label / value couple match expression
                ((ComplexPropertyValue) properties.get("labels")).getValue().forEach((s, o) -> addMatchExpression(matchExpressions, s, (String) o));
                return matchExpressions;
            });
        } else {
            context.log().warn(
                    "Node label Placement policy <{}> is not correctly configured, either \"labels\" or \"matchExpressions\" property is required. It will be ignored.",
                    policy.getName());
        }
    }

    private void apply(PolicyTemplate policy, Topology topology, FlowExecutionContext context, Supplier<List<Object>> matchExpressionsSupplier) {
        Set<NodeTemplate> validTargets = getValidTargets(policy, topology, context);
        safe(validTargets).forEach(nodeTemplate -> {
            // add nodeAffinity.preferredDuringSchedulingIgnoredDuringExecution section
            addTemplateSpecAffinitySection(topology, nodeTemplate, matchExpressionsSupplier.get());

            context.log().info("Node label placement policy <{}>: configured for node {}", policy.getName(), nodeTemplate.getName());
            log.debug("Node label placement policy <{}>: configured for node {}", policy.getName(), nodeTemplate.getName());
        });
    }

    private void addTemplateSpecAffinitySection(Topology topology, NodeTemplate nodeTemplate, List<Object> matcExpressions) {
        Map<String, Object> mainEntry = Maps.newLinkedHashMap();
        mainEntry.put("weight", "100");
        Map<String, Object> preference = (Map<String, Object>) mainEntry.compute("preference", (s, o) -> Maps.<String, Object> newLinkedHashMap());
        preference.put("matchExpressions", matcExpressions);

        // TODO strategy (preferredDuringSchedulingIgnoredDuringExecution) should be configurable by the user as a policy property
        appendNodePropertyPathValue(new Csar(topology.getArchiveName(), topology.getArchiveVersion()), topology, nodeTemplate,
                KubePoliciesConstants.NODE_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH, new ComplexPropertyValue(mainEntry));
    }

    private void addMatchExpression(List<Object> matchExpressions, String label, String... labelSelectorValues) {
        Map<String, Object> matchExpression = Maps.newLinkedHashMap();
        matchExpressions.add(matchExpression);
        matchExpression.put("key", generateKubeName(label));
        matchExpression.put("operator", "In");
        List<String> matchExpressionValues = (List<String>) matchExpression.compute("values", (s, o) -> Lists.<String> newArrayList());
        matchExpressionValues.addAll(Sets.newHashSet(labelSelectorValues));
    }

    private Set<NodeTemplate> getValidTargets(PolicyTemplate policyTemplate, Topology topology, FlowExecutionContext context) {
        Set<NodeTemplate> targetedMembers = TopologyNavigationUtil.getTargetedMembers(topology, policyTemplate);
        Iterator<NodeTemplate> iter = safe(targetedMembers).iterator();
        while (iter.hasNext()) {
            NodeTemplate nodeTemplate = iter.next();
            // TODO ALIEN-2583 ALIEN-2592 maybe better to consider type hierarchy and check if the node is from
            // org.alien4cloud.kubernetes.api.types.AbstractDeployment
            if (!Objects.equals(K8S_TYPES_DEPLOYMENT, nodeTemplate.getType())) {
                context.log().warn("Placement policy <{}>: will ignore target <{}> as it IS NOT an instance of <{}>.", policyTemplate.getName(),
                        nodeTemplate.getName(), K8S_TYPES_DEPLOYMENT);
                iter.remove();
            }
        }
        return targetedMembers;
    }

    private String levelToTopologyKey(String level) {
        return LEVEL_TO_TOPOLOGY_KEY.getOrDefault(level, level);
    }

}