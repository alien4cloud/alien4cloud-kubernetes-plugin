package org.alien4cloud.plugin.kubernetes.policies;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.K8S_POLICIES_ANTI_AFFINITY_LABEL;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.POD_ANTI_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
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
public class KubernetesAntiAffinityTopologyModifier extends TopologyModifierSupport {

    private static final Map<String, String> LEVEL_TO_TOPOLOGY_KEY = Maps.newHashMap();

    static {
        LEVEL_TO_TOPOLOGY_KEY.put("host", "kubernetes.io/hostname");
        LEVEL_TO_TOPOLOGY_KEY.put("zone", "failure-domain.beta.kubernetes.io/zone");
        LEVEL_TO_TOPOLOGY_KEY.put("region", "failure-domain.beta.kubernetes.io/regon");
    }

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology anti-affinity for " + topology.getId());
        List<PolicyTemplate> policies = safe(topology.getPolicies()).values().stream()
                .filter(policyTemplate -> Objects.equals(K8S_POLICIES_ANTI_AFFINITY_LABEL, policyTemplate.getType())).collect(Collectors.toList());

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
        if (safe(policy.getTargets()).size() < 2) {
            context.log().warn("Anti-affinity policy <{}> is not correctly configured, at least 2 targets are required. It will be ignored.", policy.getName());
            return;
        }
        Set<NodeTemplate> validTargets = getValidTargets(policy, topology, context);

        safe(validTargets).forEach(nodeTemplate -> apply(nodeTemplate, topology, validTargets, policy, context));
    }

    private void apply(NodeTemplate nodeTemplate, Topology topology, Set<NodeTemplate> targets, PolicyTemplate policyTemplate, FlowExecutionContext context) {

        // template label is the policy name
        String templateLabel = generateKubeName(policyTemplate.getName());

        // template label value is the Kube name of the deployment
        String templateLabelValue = getDeploymentNodeName(nodeTemplate);

        // TODO should we validate this? (null, value)
        // TODO see https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#an-example-of-a-pod-that-uses-pod-affinity
        String level = PropertyUtil.getScalarValue(safe(policyTemplate.getProperties()).get("level"));

        // label selector values are targets.
        // kubernetize them first
        Set<String> labelSelectorValues = targets.stream().map(target -> getDeploymentNodeName(target)).collect(Collectors.toSet());
        // then remove the node being processing from the targets
        labelSelectorValues.remove(templateLabelValue);

        // add spec.template.metadata.label property
        addTemplateLabel(topology, nodeTemplate, templateLabel, templateLabelValue);

        // add podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution section
        addTemplateSpecAffinitySection(topology, nodeTemplate, templateLabel, level, labelSelectorValues);

        context.log().info("Anti-affinity policy <{}>: configured for node {}", policyTemplate.getName(), nodeTemplate.getName());
        log.debug("Anti-affinity policy <{}>: configured for node {}", policyTemplate.getName(), nodeTemplate.getName());
    }

    private String getDeploymentNodeName(NodeTemplate nodeTemplate) {
        return PropertyUtil.getScalarValue(PropertyUtil.getPropertyValueFromPath(nodeTemplate.getProperties(), "metadata.name"));
    }

    private void addTemplateLabel(Topology topology, NodeTemplate nodeTemplate, String label, String labelValue) {
        // the label is the kubernetized name of the Deployment unit (the nodeTemplate)
        String labelPath = "spec.template.metadata.labels." + label;
        setNodePropertyPathValue(new Csar(topology.getArchiveName(), topology.getArchiveVersion()), topology, nodeTemplate, labelPath,
                new ScalarPropertyValue(labelValue));
    }

    private void addTemplateSpecAffinitySection(Topology topology, NodeTemplate nodeTemplate, String label, String level, Set<String> labelSelectorValues) {
        Map<String, Object> antiAffinityEntry = Maps.newLinkedHashMap();
        antiAffinityEntry.put("weight", "100");
        Map<String, Object> podAffinityTerm = (Map<String, Object>) antiAffinityEntry.compute("podAffinityTerm",
                (s, o) -> Maps.<String, Object> newLinkedHashMap());
        Map<String, Object> labelSelector = (Map<String, Object>) podAffinityTerm.compute("labelSelector", (s, o) -> Maps.<String, Object> newLinkedHashMap());
        List<Object> matchExpressions = (List<Object>) labelSelector.compute("matchExpressions", (s, o) -> Lists.<Object> newArrayList());
        Map<String, Object> matchExpression = Maps.newLinkedHashMap();
        matchExpressions.add(matchExpression);
        matchExpression.put("key", generateKubeName(label));
        matchExpression.put("operator", "In");
        List<String> matchExpressionValues = (List<String>) matchExpression.compute("values", (s, o) -> Lists.<String> newArrayList());
        // TODO merge with the policy template "labels" property values provided by the user
        matchExpressionValues.addAll(labelSelectorValues);

        podAffinityTerm.put("topologyKey", levelToTopologyKey(level));

        // TODO strategy (preferredDuringSchedulingIgnoredDuringExecution) should be configurable by the user as a policy property
        appendNodePropertyPathValue(new Csar(topology.getArchiveName(), topology.getArchiveVersion()), topology, nodeTemplate,
                POD_ANTI_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH, new ComplexPropertyValue(antiAffinityEntry));
    }

    private Set<NodeTemplate> getValidTargets(PolicyTemplate policyTemplate, Topology topology, FlowExecutionContext context) {
        Set<NodeTemplate> targetedMembers = TopologyNavigationUtil.getTargetedMembers(topology, policyTemplate);
        Iterator<NodeTemplate> iter = safe(targetedMembers).iterator();
        while (iter.hasNext()) {
            NodeTemplate nodeTemplate = iter.next();
            // TODO ALIEN-2583 ALIEN-2592 maybe better to consider type hierarchy and check if the node is from
            // org.alien4cloud.kubernetes.api.types.AbstractDeployment
            if (!Objects.equals(K8S_TYPES_DEPLOYMENT, nodeTemplate.getType())) {
                context.log().warn("Anti-affinity policy <{}>: will ignore target <{}> as it IS NOT an instance of <{}>.", policyTemplate.getName(),
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