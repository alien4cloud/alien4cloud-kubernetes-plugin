package org.alien4cloud.plugin.kubernetes;

import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;

import java.util.*;
import java.util.function.Consumer;

import alien4cloud.tosca.context.ToscaContext;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.plugin.kubernetes.modifier.KubernetesFinalTopologyModifier;
import org.alien4cloud.tosca.exceptions.InvalidPropertyValueException;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.alien4cloud.tosca.model.definitions.PropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.DataType;
import org.alien4cloud.tosca.normative.primitives.Size;
import org.alien4cloud.tosca.normative.primitives.SizeUnit;
import org.alien4cloud.tosca.normative.types.SizeType;
import org.alien4cloud.tosca.normative.types.ToscaTypes;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;

public abstract class AbstractKubernetesModifier extends TopologyModifierSupport {

    private static Map<String, AbstractKubernetesModifier.Parser> k8sParsers = Maps.newHashMap();

    static {
        k8sParsers.put(ToscaTypes.SIZE, new AbstractKubernetesModifier.SizeParser(ToscaTypes.SIZE));
    }

    protected Set<NodeTemplate> getValidTargets(PolicyTemplate policyTemplate, Topology topology, Consumer<String> invalidTargetConsumer) {
        Set<NodeTemplate> targetedMembers = TopologyNavigationUtil.getTargetedMembers(topology, policyTemplate);
        Iterator<NodeTemplate> iter = targetedMembers.iterator();
        while (iter.hasNext()) {
            NodeTemplate nodeTemplate = iter.next();
            // TODO maybe better to consider type hierarchy and check if the node is from
            // org.alien4cloud.kubernetes.api.types.AbstractDeployment
            if (!Objects.equals(K8S_TYPES_DEPLOYMENT, nodeTemplate.getType())) {
                invalidTargetConsumer.accept(nodeTemplate.getName());
                iter.remove();
            }
        }
        return targetedMembers;
    }

    /**
     * Transform the object by replacing eventual PropertyValue found by it's value.
     */
    protected Object getTransformedValue(Object value, PropertyDefinition propertyDefinition, String path) {
        if (value == null) {
            return null;
        } else if (value instanceof PropertyValue) {
            return getTransformedValue(((PropertyValue) value).getValue(), propertyDefinition, path);
        } else if (value instanceof Map<?, ?>) {
            Map<String, Object> newMap = Maps.newHashMap();
            if (!ToscaTypes.isPrimitive(propertyDefinition.getType())) {
                DataType dataType = ToscaContext.get(DataType.class, propertyDefinition.getType());
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                    PropertyDefinition pd = dataType.getProperties().get(entry.getKey());
                    String innerPath = (path.equals("")) ? entry.getKey() : path + "." + entry.getKey();
                    Object entryValue = getTransformedValue(entry.getValue(), pd, innerPath);
                    newMap.put(entry.getKey(), entryValue);
                }
            } else if (ToscaTypes.MAP.equals(propertyDefinition.getType())) {
                PropertyDefinition pd = propertyDefinition.getEntrySchema();
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                    String innerPath = (path.equals("")) ? entry.getKey() : path + "." + entry.getKey();
                    Object entryValue = getTransformedValue(entry.getValue(), pd, innerPath);
                    newMap.put(entry.getKey(), entryValue);
                }
            }
            return newMap;
        } else if (value instanceof List<?>) {
            PropertyDefinition pd = propertyDefinition.getEntrySchema();
            List<Object> newList = Lists.newArrayList();
            for (Object entry : (List<Object>) value) {
                Object entryValue = getTransformedValue(entry, pd, path);
                newList.add(entryValue);
            }
            return newList;
        } else {
            if (ToscaTypes.isSimple(propertyDefinition.getType())) {
                String valueAsString = value.toString();
                if (k8sParsers.containsKey(propertyDefinition.getType())) {
                    return k8sParsers.get(propertyDefinition.getType()).parseValue(valueAsString);
                } else {
                    switch (propertyDefinition.getType()) {
                        case ToscaTypes.INTEGER:
                            return Integer.parseInt(valueAsString);
                        case ToscaTypes.FLOAT:
                            return Float.parseFloat(valueAsString);
                        case ToscaTypes.BOOLEAN:
                            return Boolean.parseBoolean(valueAsString);
                        default:
                            return valueAsString;
                    }
                }
            } else {
                return value;
            }
        }
    }


    private static abstract class Parser {
        private String type;

        public Parser(String type) {
            this.type = type;
        }

        public abstract Object parseValue(String value);
    }

    private static class SizeParser extends Parser {
        public SizeParser(String type) {
            super(type);
        }

        @Override
        public Object parseValue(String value) {
            SizeType sizeType = new SizeType();
            try {
                Size size = sizeType.parse(value);
                Double d = size.convert(SizeUnit.B.toString());
                return d.longValue();
            } catch (InvalidPropertyValueException e) {
                return value;
            }
        }
    }

}
