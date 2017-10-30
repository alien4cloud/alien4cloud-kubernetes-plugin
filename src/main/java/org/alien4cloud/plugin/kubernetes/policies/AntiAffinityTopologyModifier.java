package org.alien4cloud.plugin.kubernetes.policies;

import static alien4cloud.utils.AlienUtils.safe;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.plugin.kubernetes.modifier.AbstractKubernetesTopologyModifier;
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

/**
 * This topology modifiers is associated with the kubernetes anti-affinity policy.
 */
@Component("kubernetes-anti-affinity-modifier")
public class AntiAffinityTopologyModifier extends AbstractKubernetesTopologyModifier {

    private static final String PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH = "spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution";

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {

        Map<String, PolicyTemplate> policies = topology.getPolicies();

        safe(policies).values().forEach(policyTemplate -> addAffinityData(topology, policyTemplate));

    }

    /**
     * Add affinity data to the targeted nodes<br/>
     *
     * template.metadata.label, template.spec.affinity section
     *
     * @param topology
     * @param policyTemplate
     */
    private void addAffinityData(Topology topology, PolicyTemplate policyTemplate) {
        Set<NodeTemplate> targetedMembers = getTargetedMembers(topology, policyTemplate);
        safe(targetedMembers).forEach(nodeTemplate -> addAffinityData(topology, nodeTemplate, policyTemplate));
    }

    private void addAffinityData(Topology topology, NodeTemplate nodeTemplate, PolicyTemplate policyTemplate) {
        // TODO ALIEN-2583 ALIEN-2592 check f the member is Deployment Unit

        // template label is the policy name
        String templateLabel = generateKubeName(policyTemplate.getName());

        // template label value is the name of the node template
        String templateLabelValue = generateKubeName(nodeTemplate.getName());

        // label selector values are targets.
        // kubernetize them first
        Set<String> labelSelectorValues = policyTemplate.getTargets().stream().map(s -> generateKubeName(s)).collect(Collectors.toSet());
        // then remove the node being processing from the targets
        labelSelectorValues.remove(templateLabelValue);

        // add spec.template.metadata.label property
        addTemplateLabel(topology, nodeTemplate, templateLabel);

        // add podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution section
        addTemplateSpecAffinitySection(topology, nodeTemplate, templateLabel, labelSelectorValues);

    }

    private void addTemplateLabel(Topology topology, NodeTemplate nodeTemplate, String label) {
        // the label is the kubernetized name of the Deployment unit (the nodeTemplate)
        String labelPath = "spec.template.metadata.labels." + label;
        setNodePropertyPathValue(new Csar(topology.getArchiveName(), topology.getArchiveVersion()), topology, nodeTemplate, labelPath,
                new ScalarPropertyValue(generateKubeName(nodeTemplate.getName())));
    }

    private void addTemplateSpecAffinitySection(Topology topology, NodeTemplate nodeTemplate, String label, Set<String> labelSelectorValues) {
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
        matchExpressionValues.addAll(labelSelectorValues);
        // TODO topologyKey should take into account the value fed by the user on the policy template
        podAffinityTerm.put("topologyKey", "kubernetes.io/hostname");

        // TODO strategy (preferredDuringSchedulingIgnoredDuringExecution) should be configurable by the user as a policy property
        appendNodePropertyPathValue(new Csar(topology.getArchiveName(), topology.getArchiveVersion()), topology, nodeTemplate,
                PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH, new ComplexPropertyValue(antiAffinityEntry));
    }

}