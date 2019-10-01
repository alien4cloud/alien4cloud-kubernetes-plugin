package org.alien4cloud.plugin.kubernetes.modifier.helpers;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.alien4cloud.plugin.kubernetes.modifier.KubernetesModifierContext;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ListPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;

import java.util.List;
import java.util.Map;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;

public class AffinitiyHelper {

    private AffinitiyHelper() {
    }

    public static List<Object> buildExpression(PolicyTemplate policy) {
        Map<String, AbstractPropertyValue> properties = safe(policy.getProperties());

        if (properties.get("matchExpressions") != null) {
            return ((ListPropertyValue) properties.get("matchExpressions")).getValue();
        } else if (properties.get("labels") != null) {
            List<Object> result = Lists.newArrayList();
            ((ComplexPropertyValue) properties.get("labels")).getValue().forEach((s, o) -> addMatchExpression(result, s, (String) o));
            return result;
        } else {
            return null;
        }
    }

    public static Map<String,Object> buildAffinitySection(KubernetesModifierContext context, NodeTemplate node, List<Object> expr) {
        Map<String, Object> section = Maps.newLinkedHashMap();
        Map<String, Object> preference = (Map<String, Object>) section.compute("preference", (s, o) -> Maps.<String, Object> newLinkedHashMap());

        section.put("weight",new Integer(100));
        preference.put("matchExpressions", expr);

        return section;
    }

    private static void addMatchExpression(List<Object> list, String label, String... labelSelectorValues) {
        Map<String, Object> me = Maps.newLinkedHashMap();
        me.put("key", generateKubeName(label));
        me.put("operator", "In");
        List<String> values = (List<String>) me.compute("values", (s, o) -> Lists.<String> newArrayList());
        values.addAll(Sets.newHashSet(labelSelectorValues));
        list.add(me);
    }
}
