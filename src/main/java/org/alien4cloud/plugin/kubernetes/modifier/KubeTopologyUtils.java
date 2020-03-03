package org.alien4cloud.plugin.kubernetes.modifier;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.tosca.utils.ToscaTypeUtils.isOfType;

import java.util.*;
import java.math.BigInteger;
import java.security.MessageDigest;

import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.*;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.ToscaFunctionConstants;
import org.alien4cloud.tosca.utils.InterfaceUtils;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.alien4cloud.tosca.utils.ToscaTypeUtils;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.collect.Maps;

import alien4cloud.model.common.Tag;
import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.utils.PropertyUtil;
import lombok.extern.java.Log;

/**
 * A utility to browse Kube topologies (enhanced by Kube modifiers).
 */
@Log
public class KubeTopologyUtils {

    // A4C & normatives types
    public static final String A4C_TYPES_CONTAINER_RUNTIME = "org.alien4cloud.extended.container.types.ContainerRuntime";
    public static final String A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT = "org.alien4cloud.extended.container.types.ContainerDeploymentUnit";
    public static final String A4C_TYPES_CONTAINER_JOB_UNIT = "org.alien4cloud.extended.container.types.ContainerJobUnit";
    public static final String A4C_TYPES_APPLICATION_DOCKER_CONTAINER = "tosca.nodes.Container.Application.DockerContainer";
    public static final String A4C_TYPES_APPLICATION_CONFIGURABLE_DOCKER_CONTAINER = "tosca.nodes.Container.Application.ConfigurableDockerContainer";
    public static final String A4C_TYPES_DOCKER_VOLUME = "org.alien4cloud.nodes.DockerExtVolume";
    public static final String A4C_TYPES_DOCKER_ARTIFACT_VOLUME = "org.alien4cloud.nodes.DockerArtifactVolume";
    // K8S abstract types
    public static final String K8S_TYPES_ABSTRACT_CONTAINER = "org.alien4cloud.kubernetes.api.types.AbstractContainer";
    public static final String K8S_TYPES_ABSTRACT_DEPLOYMENT = "org.alien4cloud.kubernetes.api.types.AbstractDeployment";
    public static final String K8S_TYPES_ABSTRACT_STATEFULSET = "org.alien4cloud.kubernetes.api.types.AbstractStatefulSet";
    public static final String K8S_TYPES_ABSTRACT_CONTROLLER = "org.alien4cloud.kubernetes.api.types.AbstractController";
    public static final String K8S_TYPES_ABSTRACT_JOB = "org.alien4cloud.kubernetes.api.types.AbstractJob";
    public static final String K8S_TYPES_ABSTRACT_SERVICE = "org.alien4cloud.kubernetes.api.types.AbstractService";
    public static final String K8S_TYPES_ABSTRACT_VOLUME_BASE = "org.alien4cloud.kubernetes.api.types.volume.AbstractVolumeBase";
    public static final String K8S_TYPES_ABSTRACT_ARTIFACT_VOLUME_BASE = "org.alien4cloud.kubernetes.api.types.volume.AbstractArtifactVolumeBase";
    public static final String K8S_TYPES_VOLUME_BASE = "org.alien4cloud.kubernetes.api.types.volume.AbstractVolumeBase";
    // K8S concrete types
    public static final String K8S_TYPES_CONTAINER = "org.alien4cloud.kubernetes.api.types.Container";
    public static final String K8S_TYPES_DEPLOYMENT = "org.alien4cloud.kubernetes.api.types.Deployment";
    public static final String K8S_TYPES_STATEFULSET = "org.alien4cloud.kubernetes.api.types.StatefulSet";
    public static final String K8S_TYPES_JOB = "org.alien4cloud.kubernetes.api.types.Job";
    public static final String K8S_TYPES_SERVICE = "org.alien4cloud.kubernetes.api.types.Service";
    public static final String K8S_TYPES_SERVICE_INGRESS = "org.alien4cloud.kubernetes.api.types.IngressService";
    // K8S volume types
    public static final String K8S_TYPES_VOLUMES_CLAIM = "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimSource";
    public static final String K8S_TYPES_VOLUMES_CLAIM_SC = "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimStorageClassSource";
    public static final String K8S_TYPES_SECRET_VOLUME = "org.alien4cloud.kubernetes.api.types.volume.SecretSource";
    // K8S resource types
    public static final String K8S_TYPES_DEPLOYMENT_RESOURCE = "org.alien4cloud.kubernetes.api.types.DeploymentResource";
    public static final String K8S_TYPES_STATEFULSET_RESOURCE = "org.alien4cloud.kubernetes.api.types.StatefulSetResource";
    public static final String K8S_TYPES_JOB_RESOURCE = "org.alien4cloud.kubernetes.api.types.JobResource";
    public static final String K8S_TYPES_BASE_JOB_RESOURCE = "org.alien4cloud.kubernetes.api.types.BaseJobResource";
    public static final String K8S_TYPES_BASE_RESOURCE = "org.alien4cloud.kubernetes.api.types.BaseResource";
    public static final String K8S_TYPES_SERVICE_RESOURCE = "org.alien4cloud.kubernetes.api.types.ServiceResource";
    public static final String K8S_TYPES_SIMPLE_RESOURCE = "org.alien4cloud.kubernetes.api.types.SimpleResource";
    public static final String K8S_TYPES_ENDPOINT_RESOURCE = "org.alien4cloud.kubernetes.api.types.EndpointResource";
    public static final String K8S_TYPES_CONFIG_MAP_FACTORY = "org.alien4cloud.kubernetes.api.types.ConfigMapFactory";
    public static final String K8S_TYPES_SECRET_FACTORY = "org.alien4cloud.kubernetes.api.types.SecretFactory";
    // K8S relationships
    public static final String K8S_TYPES_RSENDPOINT = "org.alien4cloud.kubernetes.api.relationships.K8SEndpointConnectToEndpoint";

    // A property name used by Service capability Endpoints
    public static final String K8S_SERVICE_NAME_PROPERTY = "service_name";
    /**
     * Get the image name from the type implementation artifact file.
     */
    public static String getContainerImageName(NodeTemplate nodeTemplate) {
        Operation imageOperation = getContainerImageOperation(nodeTemplate);
        if (imageOperation == null) {
            return null;
        }
        return imageOperation.getImplementationArtifact().getArtifactRef();
    }

    public static Operation getContainerImageOperation(NodeTemplate nodeTemplate) {
        Operation imageOperation = InterfaceUtils.getOperationIfArtifactDefined(nodeTemplate.getInterfaces(), ToscaNodeLifecycleConstants.STANDARD,
                ToscaNodeLifecycleConstants.CREATE);
        if (imageOperation != null) {
            return imageOperation;
        }
        // if not overriden in the template, fetch from the type.
        NodeType nodeType = ToscaContext.get(NodeType.class, nodeTemplate.getType());
        imageOperation = InterfaceUtils.getOperationIfArtifactDefined(nodeType.getInterfaces(), ToscaNodeLifecycleConstants.STANDARD,
                ToscaNodeLifecycleConstants.CREATE);
        return imageOperation;
    }

    /**
     * K8S names must be in lower case and can't contain _
     */
    public static String generateKubeName(String candidate) {
        return candidate.toLowerCase().replaceAll("_", "-");
    }

    /**
     * Generate a consistent kubernetes name using sha1 algorithm from a string
     */
    public static String generateConsistentKubeName(String resourceName){
        try{
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(resourceName.getBytes());
            
            return generateKubeName(String.format("%s-%X", resourceName, new BigInteger(1, Arrays.copyOfRange(b, 0, 6))) );
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Recursively get the root Object value eventually hosted by a PropertyValue. If the value is a collection (ListPropertyValue, AbstractPropertyValue) then
     * returns a collection of Objects.
     */
    public static Object getValue(Object value) {
        Object valueObject = value;
        if (value instanceof PropertyValue) {
            valueObject = getValue(((PropertyValue) value).getValue());
        } else if (value instanceof Map<?, ?>) {
            Map<String, Object> newMap = Maps.newHashMap();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) valueObject).entrySet()) {
                newMap.put(entry.getKey(), getValue(entry.getValue()));
            }
            valueObject = newMap;
        } else if (value instanceof List<?>) {
            List<Object> newList = Lists.newArrayList();
            for (Object entry : (List<Object>) valueObject) {
                newList.add(getValue(entry));
            }
            valueObject = newList;
        }
        return valueObject;
    }

    /**
     * For a given node template, if the inputParameterValue value is a function if of type get_attribute(TARGET, requirement, property)
     * and the target is a docker container, return true if the targeted capability has this property.
     */
    public static boolean isTargetedEndpointProperty(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue) {
        AbstractPropertyValue abstractPropertyValue = getTargetedEndpointProperty(topology, sourceNodeTemplate, inputParameterValue);
        return abstractPropertyValue != null;
    }

    /**
     * For a given node template, if the inputParameterValue value is a function if of type get_attribute(TARGET, requirement, property)
     * and the target is a docker container, return the value of the property.
     */
    public static AbstractPropertyValue getTargetedEndpointProperty(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue) {
        // a get_attribute that searchs an ip_address on a requirement that targets a Docker Container should return true
        if (inputParameterValue instanceof FunctionPropertyValue) {
            FunctionPropertyValue evaluatedFunction = (FunctionPropertyValue) inputParameterValue;
            if (evaluatedFunction.getFunction().equals(ToscaFunctionConstants.GET_ATTRIBUTE) && evaluatedFunction.getTemplateName().equals(ToscaFunctionConstants.R_TARGET)) {
                String requirement = evaluatedFunction.getCapabilityOrRequirementName();
                if (requirement != null) {
                    Set<RelationshipTemplate> targetRelationships = TopologyNavigationUtil.getTargetRelationships(sourceNodeTemplate, requirement);
                    for (RelationshipTemplate targetRelationship : targetRelationships) {
                        // is this node a container ?
                        NodeTemplate targetNode = topology.getNodeTemplates().get(targetRelationship.getTarget());
                        NodeType targetNodeType = ToscaContext.get(NodeType.class, targetNode.getType());
                        // if (isOfType(targetNodeType, A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {

                        Capability endpoint = targetNode.getCapabilities().get(targetRelationship.getTargetedCapabilityName());
                        String attributeName = "capabilities." + targetRelationship.getTargetedCapabilityName() + "." + evaluatedFunction.getElementNameToFetch();
                        if (targetNode instanceof ServiceNodeTemplate && ((ServiceNodeTemplate) targetNode).getAttributeValues().containsKey(attributeName)) {
                            return new ScalarPropertyValue(((ServiceNodeTemplate) targetNode).getAttributeValues().get(attributeName));
                        } else {
                            AbstractPropertyValue targetPropertyValue = PropertyUtil.getPropertyValueFromPath(endpoint.getProperties(),
                                    evaluatedFunction.getElementNameToFetch());
                            if (targetPropertyValue != null) {
                                return targetPropertyValue;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public static Optional<String> getDependencyIpAddress(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue, Map<String, List<String>> serviceIpAddressesPerDeploymentResource, String deploymentResourceName) {
        String result = null;

        // a get_attribute that searchs an ip_address on a requirement that targets a Docker Container should return true
        if (inputParameterValue instanceof FunctionPropertyValue) {
            FunctionPropertyValue evaluatedFunction = (FunctionPropertyValue) inputParameterValue;
            if (evaluatedFunction.getFunction().equals(ToscaFunctionConstants.GET_ATTRIBUTE)
                    && evaluatedFunction.getTemplateName().equals(ToscaFunctionConstants.R_TARGET)
                    && evaluatedFunction.getElementNameToFetch().equals("ip_address")) {

                String requirement = evaluatedFunction.getCapabilityOrRequirementName();
                String capabilityName = getCapabilityName(evaluatedFunction);
                if (requirement != null) {
                    Set<NodeTemplate> targetNodes = TopologyNavigationUtil.getTargetNodes(topology, sourceNodeTemplate, requirement);
                    for (NodeTemplate targetNode : targetNodes) {
                        return Optional.ofNullable(resolveIpAddress(topology, sourceNodeTemplate, targetNode, capabilityName, serviceIpAddressesPerDeploymentResource, deploymentResourceName));
                    }
                }
            }
        }
        return Optional.ofNullable(result);
    }

    private static String resolveIpAddress(Topology topology, NodeTemplate sourceNode, NodeTemplate targetNode, String capabilityName, Map<String, List<String>> serviceIpAddressesPerDeploymentResource, String deploymentResourceName) {
        NodeType targetNodeType = ToscaContext.get(NodeType.class, targetNode.getType());
        if (isOfType(targetNodeType, A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {
            if (targetNode instanceof ServiceNodeTemplate) {
                // this a container exposed as a service
                return resolveDependency(targetNode, serviceIpAddressesPerDeploymentResource, deploymentResourceName);
            } else {
                // this a regular container, also deployed in this deployemnt
                // find the deployment that host this container
                NodeTemplate deploymentNode = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, targetNode,
                        K8S_TYPES_DEPLOYMENT);
                if (deploymentNode != null) {
                    // if the deployment host is the same than me then just return 'localhost'
                    NodeTemplate sourceDeploymentNode = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, sourceNode,
                            K8S_TYPES_DEPLOYMENT);
                    if (sourceDeploymentNode == deploymentNode) {
                        // the target and the source are on the same deploymentunit, 'localhost' can be used;
                        return "localhost";
                    } else {
                        // find the service that is behind the exposed container
                        NodeTemplate service = getServiceRelatedToDeployment(topology, deploymentNode, capabilityName);
                        return resolveDependency(service, serviceIpAddressesPerDeploymentResource, deploymentResourceName);
                    }
                }
            }
        } else {
            // the target is not a container, so we should find a service that proxy the endpoint
            Set<NodeTemplate> endpoints = TopologyNavigationUtil.getSourceNodesByRelationshipType(topology, targetNode,
                    K8S_TYPES_RSENDPOINT);
            if (endpoints.size() > 0) {
                NodeTemplate endpointNode = endpoints.iterator().next();
                Set<NodeTemplate> services = TopologyNavigationUtil.getSourceNodes(topology, endpointNode, "feature");
                NodeTemplate service = services.stream().filter(nodeTemplate -> {
                    NodeType nodeType = ToscaContext.get(NodeType.class, nodeTemplate.getType());
                    return ToscaTypeUtils.isOfType(nodeType, K8S_TYPES_SERVICE);
                }).findFirst().get();
                resolveDependency(service, serviceIpAddressesPerDeploymentResource, deploymentResourceName);
            }
        }
        return null;
    }

    public static String resolveDependency(NodeTemplate serviceTemplate, Map<String, List<String>> serviceIpAddressesPerDeploymentResource, String deploymentResourceName) {

        AbstractPropertyValue serviceNameValue = PropertyUtil
                .getPropertyValueFromPath(serviceTemplate.getProperties(), "metadata.name");
        String serviceName = PropertyUtil.getScalarValue(serviceNameValue);

        List<String> serviceIpAddresses = serviceIpAddressesPerDeploymentResource.get(deploymentResourceName);
        if (serviceIpAddresses == null) {
            serviceIpAddresses = com.google.common.collect.Lists.newArrayList();
            serviceIpAddressesPerDeploymentResource.put(deploymentResourceName, serviceIpAddresses);
        }
        serviceIpAddresses.add(serviceName);

        return "${SERVICE_IP_LOOKUP" + (serviceIpAddresses.size() - 1) + "}";

    }

    private static String getCapabilityName(FunctionPropertyValue evaluatedFunction) {
        return evaluatedFunction.getParameters().get(evaluatedFunction.getParameters().size() -2);
    }

    /**
     * For a given deployment node, returns the service that depends on this deployment considering a given endpoint name.
     */
    public static NodeTemplate getServiceRelatedToDeployment(Topology topology, NodeTemplate deploymentNodeTemplate, String endpointName) {
        Set<NodeTemplate> sourceNodes = TopologyNavigationUtil.getSourceNodes(topology, deploymentNodeTemplate, "feature");
        for (NodeTemplate sourceNode : sourceNodes) {
            Collection<Tag> sourceNodeTags = safe(sourceNode.getTags());
            for (Tag tag : sourceNodeTags) {
                if (tag.getName().equals(AbstractKubernetesModifier.A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT)) {
                    if (tag.getValue().equals(endpointName)) {
                        return sourceNode;
                    }
                } else if (tag.getName().equals(AbstractKubernetesModifier.A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINTS)) {
                    if (tag.getValue().contains(endpointName)) {
                        return sourceNode;
                    }
                }
            }
        }
        return null;
    }

    public static ScalarPropertyValue portNameFromService(NodeTemplate node) {
        ListPropertyValue ports = (ListPropertyValue) PropertyUtil.getPropertyValueFromPath(safe(node.getProperties()), "spec.ports");
        ComplexPropertyValue port = (ComplexPropertyValue) ports.getValue().get(0);
        return (ScalarPropertyValue) port.getValue().get("name");
    }

    public static void copyProperty(NodeTemplate sourceTemplate, String sourcePath, Map<String, AbstractPropertyValue> propertyValues, String targetPath) {
        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(sourceTemplate.getProperties()), sourcePath);
        TopologyModifierSupport.feedPropertyValue(propertyValues, targetPath, propertyValue, false);
    }

    public static void renameProperty(Object propertyValue, String propertyPath, String newName) {
        PropertyUtil.NestedPropertyWrapper nestedPropertyWrapper = PropertyUtil.getNestedProperty(propertyValue, propertyPath);
        if (nestedPropertyWrapper != null) {
            Object value = nestedPropertyWrapper.parent.remove(nestedPropertyWrapper.key);
            // value can't be null if nestedPropertyWrapper isn't null
            nestedPropertyWrapper.parent.put(newName, value);
        }
    }
}
