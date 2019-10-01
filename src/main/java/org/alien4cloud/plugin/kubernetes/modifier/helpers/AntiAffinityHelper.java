package org.alien4cloud.plugin.kubernetes.modifier.helpers;

import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.alien4cloud.plugin.kubernetes.modifier.KubernetesModifierContext;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.Topology;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.POD_ANTI_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH;

public class AntiAffinityHelper {

    private static final Map<String, String> LEVEL_TO_TOPOLOGY_KEY = Maps.newHashMap();

    static {
        LEVEL_TO_TOPOLOGY_KEY.put("host", "kubernetes.io/hostname");
        LEVEL_TO_TOPOLOGY_KEY.put("zone", "failure-domain.beta.kubernetes.io/zone");
        LEVEL_TO_TOPOLOGY_KEY.put("region", "failure-domain.beta.kubernetes.io/regon");
    }

    private AntiAffinityHelper() {
    }

    public static Map<String,Object> buildAntiAffinitySection(KubernetesModifierContext context, String level, NodeTemplate node,Set<NodeTemplate> allTargets) {
        Set<String> nodeNames = allTargets.stream()
                .filter(t -> !t.getName().equals(node.getName()))
                .map(AntiAffinityHelper::getDeploymentNodeName)
                .collect(Collectors.toSet());

        Map<String,Object> antiAffinitySection = Maps.newLinkedHashMap();
        antiAffinitySection.put("weight",new Integer(100));

        Map<String, Object> podAffinityTerm = (Map<String, Object>) antiAffinitySection.compute("podAffinityTerm", (s, o) -> Maps.<String, Object> newLinkedHashMap());
        Map<String, Object> labelSelector = (Map<String, Object>) podAffinityTerm.compute("labelSelector", (s, o) -> Maps.<String, Object> newLinkedHashMap());
        List<Object> matchExpressions = (List<Object>) labelSelector.compute("matchExpressions", (s, o) -> Lists.<Object> newArrayList());

        Map<String, Object> matchExpression = Maps.newLinkedHashMap();
        matchExpressions.add(matchExpression);
        matchExpression.put("key", "app");
        matchExpression.put("operator", "In");
        List<String> matchExpressionValues = (List<String>) matchExpression.compute("values", (s, o) -> Lists.<String> newArrayList());
        matchExpressionValues.addAll(nodeNames);

        podAffinityTerm.put("topologyKey", levelToTopologyKey(level));

        return antiAffinitySection;
    }

    private static String getDeploymentNodeName(NodeTemplate nodeTemplate) {
        return PropertyUtil.getScalarValue(PropertyUtil.getPropertyValueFromPath(nodeTemplate.getProperties(), "metadata.name"));
    }

    private static String levelToTopologyKey(String level) {
        return LEVEL_TO_TOPOLOGY_KEY.getOrDefault(level, level);
    }

}
