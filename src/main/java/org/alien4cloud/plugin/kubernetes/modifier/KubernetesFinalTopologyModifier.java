package org.alien4cloud.plugin.kubernetes.modifier;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.*;
import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.exceptions.InvalidPropertyValueException;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.Interface;
import org.alien4cloud.tosca.model.definitions.Operation;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.alien4cloud.tosca.model.definitions.PropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.DataType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.AlienCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.normative.primitives.Size;
import org.alien4cloud.tosca.normative.primitives.SizeUnit;
import org.alien4cloud.tosca.normative.types.SizeType;
import org.alien4cloud.tosca.normative.types.ToscaTypes;
import org.alien4cloud.tosca.utils.FunctionEvaluator;
import org.alien4cloud.tosca.utils.FunctionEvaluatorContext;
import org.alien4cloud.tosca.utils.NodeTemplateUtils;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.alien4cloud.tosca.utils.ToscaTypeUtils;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.CloneUtil;
import alien4cloud.utils.PropertyUtil;
import lombok.extern.java.Log;

/**
 * Transform a matched K8S topology containing <code>Container</code>s, <code>Deployment</code>s, <code>Service</code>s
 * and replace them with <code>DeploymentResource</code>s and <code>ServiceResource</code>s.
 * <p>
 * TODO: add logs using FlowExecutionContext
 */
@Log
@Component(value = "kubernetes-final-modifier")
public class KubernetesFinalTopologyModifier extends TopologyModifierSupport {

    public static final String A4C_KUBERNETES_MODIFIER_TAG = "a4c_kubernetes-final-modifier";

    private static Map<String, Parser> k8sParsers = Maps.newHashMap();

    static {
        k8sParsers.put(ToscaTypes.SIZE, new SizeParser(ToscaTypes.SIZE));
    }

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(topology, context);
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
        }
    }

    private void doProcess(Topology topology, FlowExecutionContext context) {
        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        // just a map that store the node name as key and the replacement node as value
        Map<String, NodeTemplate> nodeReplacementMap = Maps.newHashMap();

        // store the yaml structure for each resources
        // these yaml structure will become the JSON resource spec after JSON serialization
        // these yaml structures can not be stored in node since they can't respect any TOSCA contract
        Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures = Maps.newHashMap();

        // for each Service create a node of type ServiceResource
        Set<NodeTemplate> serviceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_SERVICE, false);
        serviceNodes.forEach(nodeTemplate -> createServiceResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // for each Deployment create a node of type DeploymentResource
        Set<NodeTemplate> deploymentNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_DEPLOYMENT, false);
        deploymentNodes.forEach(nodeTemplate -> createDeploymentResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // A function evaluator context will be usefull
        // FIXME: use topology inputs ?
        Map<String, PropertyValue> inputValues = Maps.newHashMap();
        FunctionEvaluatorContext functionEvaluatorContext = new FunctionEvaluatorContext(topology, inputValues);

        Map<String, List<String>> serviceIpAddressesPerDeploymentResource = Maps.newHashMap();

        // for each container,
        Set<NodeTemplate> containerNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_CONTAINER, false);
        containerNodes.forEach(nodeTemplate -> manageContainer(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures,
                functionEvaluatorContext, serviceIpAddressesPerDeploymentResource));

        // for each volume node, populate the 'volumes' property of the corresponding deployment resource
        Set<NodeTemplate> volumeNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_VOLUME_BASE, true);
        volumeNodes.forEach(nodeTemplate -> manageVolume(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // remove useless nodes
        // TODO bug on node matching view since these nodes are the real matched ones
        // TODO then find a way to delete servicesNodes and deloymentNodes as they are not used
        serviceNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        deploymentNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        Set<NodeTemplate> volumes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_VOLUME_BASE, true);
        volumes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        Set<NodeTemplate> containers = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_APPLICATION_DOCKER_CONTAINER, true);
        safe(containers).forEach(nodeTemplate -> removeNode(topology, nodeTemplate));

        // finally set the 'resource_spec' property with the JSON content of the resource specification
        Set<NodeTemplate> resourceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_RESOURCE, true);
        for (NodeTemplate resourceNode : resourceNodes) {
            Map<String, AbstractPropertyValue> resourceNodeProperties = resourceNodeYamlStructures.get(resourceNode.getName());
            if (resourceNodeProperties != null && resourceNodeProperties.containsKey("resource_def")) {
                Object propertyValue = getValue(resourceNodeProperties.get("resource_def"));
                String serializedPropertyValue = PropertyUtil.serializePropertyValue(propertyValue);
                setNodePropertyPathValue(csar, topology, resourceNode, "resource_spec", new ScalarPropertyValue(serializedPropertyValue));
            }
        }
    }

    private void manageVolume(Csar csar, Topology topology, NodeTemplate volumeNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        Optional<RelationshipTemplate> relationshipTemplate = TopologyNavigationUtil.getTargetRelationships(volumeNode, "attachment").stream().findFirst();
        if (!relationshipTemplate.isPresent()) {
            return;
        }
        NodeTemplate targetContainer = topology.getNodeTemplates().get(relationshipTemplate.get().getTarget());
        // find the deployment that hosts this container
        NodeTemplate deploymentContainer = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, targetContainer, K8S_TYPES_DEPLOYMENT);
        // get the deployment resource corresponding to this deployment
        NodeTemplate deploymentResourceNode = nodeReplacementMap.get(deploymentContainer.getName());

        managePersistentVolumeClaim(csar, topology, volumeNode, resourceNodeYamlStructures, deploymentResourceNode);

        Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = resourceNodeYamlStructures.get(deploymentResourceNode.getName());
        Map<String, Object> volumeEntry = Maps.newHashMap();
        AbstractPropertyValue name = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "name");
        AbstractPropertyValue volume_type = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "volume_type");
        AbstractPropertyValue volume_spec = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "spec");
        volumeEntry.put("name", name);
        Object volumeSpecObject = volume_spec;
        if (volume_spec == null) {
            // if the volume spec is null, we want an empty object (JSON : {}) in the map
            Map<String, Object> map = Maps.newHashMap();
            volumeSpecObject = map;
        }
        volumeEntry.put(PropertyUtil.getScalarValue(volume_type), volumeSpecObject);
        feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec.template.spec.volumes", volumeEntry, true);
    }

    private void managePersistentVolumeClaim(Csar csar, Topology topology, NodeTemplate volumeNode, Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, NodeTemplate deploymentResourceNode) {
        // in case of empty claimName for a PersistentVolumeClaimSource then create a node of type PersistentVolumeClaim
        NodeType volumeNodeType = ToscaContext.get(NodeType.class, volumeNode.getType());
        if (ToscaTypeUtils.isOfType(volumeNodeType, KubeTopologyUtils.K8S_TYPES_VOLUMES_CLAIM)) {
            AbstractPropertyValue claimNamePV = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "spec.claimName");
            if (claimNamePV == null) {
                NodeTemplate volumeClaimResource = addNodeTemplate(csar, topology, volumeNode.getName() + "_PVC", KubeTopologyUtils.K8S_TYPES_SIMPLE_RESOURCE, K8S_CSAR_VERSION);

                Map<String, AbstractPropertyValue> volumeClaimResourceNodeProperties = Maps.newHashMap();
                resourceNodeYamlStructures.put(volumeClaimResource.getName(), volumeClaimResourceNodeProperties);

                String claimName = generateUniqueKubeName(volumeNode.getName());
                // fill the node properties
                feedPropertyValue(volumeClaimResource.getProperties(), "resource_type", new ScalarPropertyValue("pvc"), false);
                feedPropertyValue(volumeClaimResource.getProperties(), "resource_id", new ScalarPropertyValue(claimName), false);
                feedPropertyValue(volumeClaimResource.getProperties(), "json_path_expr", new ScalarPropertyValue(".items[0].status.phase"), false);
                feedPropertyValue(volumeClaimResource.getProperties(), "json_path_value", new ScalarPropertyValue("Bound"), false);
                // fill the future JSON spec
                feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.kind", "PersistentVolumeClaim", false);
                feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.apiVersion", "v1", false);
                feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.metadata.name", claimName, false);
                feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.metadata.labels.a4c_id", claimName, false);
                AbstractPropertyValue accessModesProperty = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "accessModes");
                feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.spec.accessModes", accessModesProperty, true);
                // get the size of the volume to define claim storage size
                NodeType nodeType = ToscaContext.get(NodeType.class, volumeNode.getType());
                AbstractPropertyValue size = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "size");
                PropertyDefinition propertyDefinition = nodeType.getProperties().get("size");
                Object transformedSize = getTransformedValue(size, propertyDefinition, "");
                feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.spec.resources.requests.storage", transformedSize, false);
                if (ToscaTypeUtils.isOfType(volumeNodeType, KubeTopologyUtils.K8S_TYPES_VOLUMES_CLAIM_SC)) {
                    // add the storage class name to the claim
                    AbstractPropertyValue storageClassNameProperty = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "storageClassName");
                    feedPropertyValue(volumeClaimResourceNodeProperties, "resource_def.spec.storageClassName", storageClassNameProperty, false);
                }
                // finally set the claimName of the volume node
                feedPropertyValue(volumeNode.getProperties(), "spec.claimName", claimName, false);
                // add a relationship between the deployment and this claim
                addRelationshipTemplate(csar, topology, deploymentResourceNode, volumeClaimResource.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
            }
        }
    }

    private void manageContainer(Csar csar, Topology topology, NodeTemplate containerNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, FunctionEvaluatorContext functionEvaluatorContext,
            Map<String, List<String>> serviceIpAddressesPerDeploymentResource) {
        {
            // get the hosting node
            NodeTemplate deploymentNode = TopologyNavigationUtil.getImmediateHostTemplate(topology, containerNode);
            // find the replacer
            NodeTemplate deploymentResource = nodeReplacementMap.get(deploymentNode.getName());
            Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = resourceNodeYamlStructures.get(deploymentResource.getName());

            // resolve env variables
            Set<NodeTemplate> hostedContainers = TopologyNavigationUtil.getSourceNodes(topology, containerNode, "host");
            for (NodeTemplate nodeTemplate : hostedContainers) {
                // we should have a single hosted docker container
                NodeType nodeType = ToscaContext.get(NodeType.class, nodeTemplate.getType());
                if (nodeType.getInterfaces() != null && nodeType.getInterfaces().containsKey(ToscaNodeLifecycleConstants.STANDARD)) {
                    Interface standardInterface = nodeType.getInterfaces().get(ToscaNodeLifecycleConstants.STANDARD);
                    if (standardInterface.getOperations() != null && standardInterface.getOperations().containsKey(ToscaNodeLifecycleConstants.CREATE)) {
                        Operation createOp = standardInterface.getOperations().get(ToscaNodeLifecycleConstants.CREATE);
                        safe(createOp.getInputParameters()).forEach((k, iValue) -> {
                            if (iValue instanceof AbstractPropertyValue && k.startsWith("ENV_")) {
                                String envKey = k.substring(4);

                                if (KubeTopologyUtils.isServiceIpAddress(topology, nodeTemplate, iValue)) {
                                    NodeTemplate serviceTemplate = KubeTopologyUtils.getServiceDependency(topology, nodeTemplate, iValue);
                                    AbstractPropertyValue serviceNameValue = PropertyUtil.getPropertyValueFromPath(serviceTemplate.getProperties(),
                                            "metadata.name");
                                    String serviceName = PropertyUtil.getScalarValue(serviceNameValue);

                                    List<String> serviceIpAddresses = serviceIpAddressesPerDeploymentResource.get(deploymentResource.getName());
                                    if (serviceIpAddresses == null) {
                                        serviceIpAddresses = Lists.newArrayList();
                                        serviceIpAddressesPerDeploymentResource.put(deploymentResource.getName(), serviceIpAddresses);
                                    }
                                    serviceIpAddresses.add(serviceName);

                                    ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                    envEntry.setValue(Maps.newHashMap());
                                    envEntry.getValue().put("name", envKey);
                                    envEntry.getValue().put("value", "${SERVICE_IP_LOOKUP" + (serviceIpAddresses.size() - 1) + "}");
                                    appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                } else if (KubeTopologyUtils.isTargetedEndpointProperty(topology, nodeTemplate, iValue)) {
                                    AbstractPropertyValue apv = KubeTopologyUtils.getTargetedEndpointProperty(topology, nodeTemplate, iValue);
                                    if (apv != null) {
                                        ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                        envEntry.setValue(Maps.newHashMap());
                                        envEntry.getValue().put("name", envKey);
                                        envEntry.getValue().put("value", PropertyUtil.getScalarValue(apv));
                                        appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                    }
                                } else {
                                    try {
                                        PropertyValue propertyValue = FunctionEvaluator.resolveValue(functionEvaluatorContext, nodeTemplate,
                                                nodeTemplate.getProperties(), (AbstractPropertyValue) iValue);
                                        if (propertyValue != null) {
                                            ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                            envEntry.setValue(Maps.newHashMap());
                                            envEntry.getValue().put("name", envKey);
                                            envEntry.getValue().put("value", propertyValue);
                                            appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                        }
                                    } catch (IllegalArgumentException iae) {
                                        log.severe(iae.getMessage());
                                    }
                                }
                            }
                        });
                    }
                }
            }

            // populate the service_dependency_lookups property of the deployment resource nodes
            serviceIpAddressesPerDeploymentResource.forEach((deploymentResourceNodeName, ipAddressLookups) -> {
                NodeTemplate deploymentResourceNode = topology.getNodeTemplates().get(deploymentResourceNodeName);
                StringBuilder serviceDependencyDefinitionsValue = new StringBuilder();
                for (int i = 0; i < ipAddressLookups.size(); i++) {
                    if (i > 0) {
                        serviceDependencyDefinitionsValue.append(",");
                    }
                    serviceDependencyDefinitionsValue.append("SERVICE_IP_LOOKUP").append(i);
                    serviceDependencyDefinitionsValue.append(":").append(ipAddressLookups.get(i));
                }
                setNodePropertyPathValue(csar, topology, deploymentResourceNode, "service_dependency_lookups",
                        new ScalarPropertyValue(serviceDependencyDefinitionsValue.toString()));
            });

            // add an entry in the deployment resource
            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(containerNode.getProperties()), "container");
            // transform data
            NodeType nodeType = ToscaContext.get(NodeType.class, containerNode.getType());
            PropertyDefinition propertyDefinition = nodeType.getProperties().get("container");
            Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
            feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec.template.spec.containers", transformedValue, true);

        }
    }

    private void createDeploymentResource(Csar csar, Topology topology, NodeTemplate deploymentNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        NodeTemplate deploymentResourceNode = addNodeTemplate(csar, topology, deploymentNode.getName() + "_Resource", K8S_TYPES_DEPLOYMENT_RESOURCE,
                K8S_CSAR_VERSION);
        nodeReplacementMap.put(deploymentNode.getName(), deploymentResourceNode);
        setNodeTagValue(deploymentResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", deploymentNode.getName());

        // ensure a policy that targets the deployment will now target the resource
        // not very necessary because the policy here doesn't mean nothing ...
        changePolicyTarget(topology, deploymentNode, deploymentResourceNode);

        Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(deploymentResourceNode.getName(), deploymentResourceNodeProperties);

        copyProperty(csar, topology, deploymentNode, "apiVersion", deploymentResourceNodeProperties, "resource_def.apiVersion");
        copyProperty(csar, topology, deploymentNode, "kind", deploymentResourceNodeProperties, "resource_def.kind");
        copyProperty(csar, topology, deploymentNode, "metadata", deploymentResourceNodeProperties, "resource_def.metadata");

        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(deploymentNode.getProperties()), "spec");
        NodeType nodeType = ToscaContext.get(NodeType.class, deploymentNode.getType());
        PropertyDefinition propertyDefinition = nodeType.getProperties().get("spec");
        Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
        feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec", transformedValue, false);

        // Copy scalable property of the deployment node into the cluster controller capability of the deployment node.
        Capability scalableCapability = safe(deploymentNode.getCapabilities()).get("scalable");
        if (scalableCapability != null) {
            Capability clusterControllerCapability = new Capability(AlienCapabilityTypes.CLUSTER_CONTROLLER,
                    CloneUtil.clone(scalableCapability.getProperties()));
            NodeTemplateUtils.setCapability(deploymentResourceNode, "scalable", clusterControllerCapability);
        }

        // find each node of type Service that targets this deployment
        Set<NodeTemplate> sourceCandidates = TopologyNavigationUtil.getSourceNodes(topology, deploymentNode, "feature");
        for (NodeTemplate nodeTemplate : sourceCandidates) {
            // TODO: manage inheritance ?
            if (nodeTemplate.getType().equals(K8S_TYPES_SERVICE)) {
                // find the replacer
                NodeTemplate serviceResource = nodeReplacementMap.get(nodeTemplate.getName());
                if (!TopologyNavigationUtil.hasRelationship(serviceResource, deploymentResourceNode.getName(), "dependency", "feature")) {
                    RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, serviceResource, deploymentResourceNode.getName(),
                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                    setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                            nodeTemplate.getName() + " -> " + deploymentNode.getName());
                }
            }
        }
        // find each node of type service this deployment depends on
        Set<NodeTemplate> targetCandidates = TopologyNavigationUtil.getTargetNodes(topology, deploymentNode, "dependency");
        for (NodeTemplate nodeTemplate : targetCandidates) {
            // TODO: manage inheritance ?
            if (nodeTemplate.getType().equals(K8S_TYPES_SERVICE)) {
                // find the replacer
                NodeTemplate serviceResource = nodeReplacementMap.get(nodeTemplate.getName());
                if (!TopologyNavigationUtil.hasRelationship(deploymentResourceNode, serviceResource.getName(), "dependency", "feature")) {
                    RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, deploymentResourceNode, serviceResource.getName(),
                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                    setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                            deploymentNode.getName() + " -> " + nodeTemplate.getName());
                }
            }
        }
    }

    private void createServiceResource(Csar csar, Topology topology, NodeTemplate serviceNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        NodeTemplate serviceResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_Resource", K8S_TYPES_SERVICE_RESOURCE, K8S_CSAR_VERSION);

        nodeReplacementMap.put(serviceNode.getName(), serviceResourceNode);
        setNodeTagValue(serviceResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", serviceNode.getName());
        Map<String, AbstractPropertyValue> serviceResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(serviceResourceNode.getName(), serviceResourceNodeProperties);

        copyProperty(csar, topology, serviceNode, "apiVersion", serviceResourceNodeProperties, "resource_def.apiVersion");
        copyProperty(csar, topology, serviceNode, "kind", serviceResourceNodeProperties, "resource_def.kind");
        copyProperty(csar, topology, serviceNode, "metadata", serviceResourceNodeProperties, "resource_def.metadata");

        AbstractPropertyValue namePropertyValue = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "metadata.name");
        setNodePropertyPathValue(csar, topology, serviceResourceNode, "service_name", namePropertyValue);

        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "spec");
        NodeType nodeType = ToscaContext.get(NodeType.class, serviceNode.getType());
        PropertyDefinition propertyDefinition = nodeType.getProperties().get("spec");
        Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
        // rename entry service_type to type
        renameProperty(transformedValue, "service_type", "type");
        feedPropertyValue(serviceResourceNodeProperties, "resource_def.spec", transformedValue, false);
    }

    private void renameProperty(Object propertyValue, String propertyPath, String newName) {
        PropertyUtil.NestedPropertyWrapper nestedPropertyWrapper = PropertyUtil.getNestedProperty(propertyValue, propertyPath);
        if (nestedPropertyWrapper != null) {
            Object value = nestedPropertyWrapper.parent.remove(nestedPropertyWrapper.key);
            // value can't be null if nestedPropertyWrapper isn't null
            nestedPropertyWrapper.parent.put(newName, value);
        }
    }

    private void copyProperty(Csar csar, Topology topology, NodeTemplate sourceTemplate, String sourcePath, Map<String, AbstractPropertyValue> propertyValues,
            String targetPath) {
        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(sourceTemplate.getProperties()), sourcePath);
        feedPropertyValue(propertyValues, targetPath, propertyValue, false);
    }

    /**
     * Transform the object by replacing eventual PropertyValue found by it's value.
     */
    private Object getTransformedValue(Object value, PropertyDefinition propertyDefinition, String path) {
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
