package org.alien4cloud.plugin.kubernetes;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;

import java.util.*;
import java.util.function.Consumer;

import alien4cloud.common.MetaPropertiesService;
import alien4cloud.model.common.MetaPropertyTarget;
import alien4cloud.model.orchestrators.locations.Location;
import alien4cloud.tosca.context.ToscaContext;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.alien4cloud.alm.deployment.configuration.flow.EnvironmentContext;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils;
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
import org.apache.commons.lang.StringUtils;

import javax.annotation.Resource;

public abstract class AbstractKubernetesModifier extends TopologyModifierSupport {

    public static final String A4C_KUBERNETES_MODIFIER_TAG = "a4c_kubernetes-modifier";
    public static final String A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT = A4C_KUBERNETES_MODIFIER_TAG + "_service_endpoint";
    protected static final String A4C_KUBERNETES_MODIFIER_TAG_EXPOSED_AS_CAPA = AbstractKubernetesModifier.A4C_KUBERNETES_MODIFIER_TAG + "_exposedAs";
    protected static final String A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT_PORT = A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT + "_port";
    protected static final String A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT_PORT_NAME = A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT + "_portName";
    private static Map<String, AbstractKubernetesModifier.Parser> k8sParsers = Maps.newHashMap();
    protected static final String K8S_NAMESPACE_METAPROP_NAME = "K8S_NAMESPACE";
    protected static final String K8S_PREFIX_METAPROP_NAME = "K8S_PREFIX";

    private static String FLOW_CACHE_KEY_K8S_PREFIX = AbstractKubernetesModifier.class.getName() + "K8S_PREFIX";

    @Resource
    protected MetaPropertiesService metaPropertiesService;

    static {
        k8sParsers.put(ToscaTypes.SIZE, new AbstractKubernetesModifier.SizeParser(ToscaTypes.SIZE));
    }

    /**
     * Search for a K8S_NAMESPACE meta-properties value in application or location. If a value is found in both, the location takes the precedence.
     *
     * @param context Execution context that allows modifiers to access some useful contextual information
     * @return the value of a meta-property corresponding to a namespace specification ("K8S_NAMESPACE").
     */
    protected String getProvidedMetaproperty(FlowExecutionContext context, String metaPropertyName) {
        Optional<EnvironmentContext> ec = context.getEnvironmentContext();
        String applicationNamespaceMetaPropertyKey = this.metaPropertiesService.getMetapropertykeyByName(metaPropertyName, MetaPropertyTarget.APPLICATION);
        String locationNamespaceMetaPropertyKey = this.metaPropertiesService.getMetapropertykeyByName(metaPropertyName, MetaPropertyTarget.LOCATION);

        // first, get the namespace using the value of a meta property on application
        String providedNamespace = null;
        if (ec.isPresent() && applicationNamespaceMetaPropertyKey != null) {
            EnvironmentContext env = ec.get();
            Map<String, String> metaProperties = safe(env.getApplication().getMetaProperties());
            String applicationProvidedNamespace = metaProperties.get(applicationNamespaceMetaPropertyKey);
            if (StringUtils.isNotEmpty(applicationProvidedNamespace)) {
                providedNamespace = applicationProvidedNamespace;
            }
        }
        // if defined, use the the value of a meta property of the targeted location
        if (locationNamespaceMetaPropertyKey != null) {
            Object deploymentLocation = context.getExecutionCache().get(FlowExecutionContext.DEPLOYMENT_LOCATIONS_MAP_CACHE_KEY);
            if (deploymentLocation != null && deploymentLocation instanceof Map) {
                Map<String, Location> locations = (Map<String, Location>)deploymentLocation;
                if (locations != null) {
                    Optional<Location> location = locations.values().stream().findFirst();
                    if (location.isPresent()) {
                        String locationProvidedNamespace = safe(location.get().getMetaProperties()).get(locationNamespaceMetaPropertyKey);
                        if (StringUtils.isNotEmpty(locationProvidedNamespace)) {
                            providedNamespace = locationProvidedNamespace;
                        }
                    }
                }
            }
        }
        return providedNamespace;
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

    protected String generateUniqueKubeName(FlowExecutionContext ctx, String prefix) {
        // if a metaprop is defined at application or location level, use it as a prefix.
        String k8sPrefix = "";
        Object o = ctx.getExecutionCache().get(FLOW_CACHE_KEY_K8S_PREFIX);
        if (o == null) {
            k8sPrefix = getProvidedMetaproperty(ctx, K8S_PREFIX_METAPROP_NAME);
            ctx.getExecutionCache().put(FLOW_CACHE_KEY_K8S_PREFIX, k8sPrefix);
        } else {
            k8sPrefix = o.toString();
        }
        String kubeName = KubeTopologyUtils.generateKubeName(k8sPrefix + prefix + "-" + UUID.randomUUID().toString().hashCode());
        // length should be < 63 (Kube rule)
        org.apache.commons.lang3.StringUtils.abbreviateMiddle(kubeName, "-", 63);
        // TODO: better unique generation
        // we hashCode the UUID, we know that we have some collision risk, but for the moment we accept
        return KubeTopologyUtils.generateKubeName(k8sPrefix + prefix + "-" + UUID.randomUUID().toString().hashCode());
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
