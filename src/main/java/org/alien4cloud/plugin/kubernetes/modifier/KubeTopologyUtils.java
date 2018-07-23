package org.alien4cloud.plugin.kubernetes.modifier;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.tosca.utils.ToscaTypeUtils.isOfType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.FunctionPropertyValue;
import org.alien4cloud.tosca.model.definitions.IValue;
import org.alien4cloud.tosca.model.definitions.Operation;
import org.alien4cloud.tosca.model.definitions.PropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.ToscaFunctionConstants;
import org.alien4cloud.tosca.utils.InterfaceUtils;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.collect.Maps;

import alien4cloud.model.common.Tag;
import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.utils.PropertyUtil;

/**
 * A utility to browse Kube topologies (enhanced by Kube modifiers).
 */
public class KubeTopologyUtils {

    // A4C & normatives types
    public static final String A4C_TYPES_CONTAINER_RUNTIME = "org.alien4cloud.extended.container.types.ContainerRuntime";
    public static final String A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT = "org.alien4cloud.extended.container.types.ContainerDeploymentUnit";
    public static final String A4C_TYPES_CONTAINER_JOB_UNIT = "org.alien4cloud.extended.container.types.ContainerJobUnit";
    public static final String A4C_TYPES_APPLICATION_DOCKER_CONTAINER = "tosca.nodes.Container.Application.DockerContainer";
    public static final String A4C_TYPES_DOCKER_VOLUME = "org.alien4cloud.nodes.DockerExtVolume";
    // K8S abstract types
    public static final String K8S_TYPES_ABSTRACT_CONTAINER = "org.alien4cloud.kubernetes.api.types.AbstractContainer";
    public static final String K8S_TYPES_ABSTRACT_DEPLOYMENT = "org.alien4cloud.kubernetes.api.types.AbstractDeployment";
    public static final String K8S_TYPES_ABSTRACT_JOB = "org.alien4cloud.kubernetes.api.types.AbstractJob";
    public static final String K8S_TYPES_ABSTRACT_SERVICE = "org.alien4cloud.kubernetes.api.types.AbstractService";
    public static final String K8S_TYPES_ABSTRACT_VOLUME_BASE = "org.alien4cloud.kubernetes.api.types.volume.AbstractVolumeBase";
    public static final String K8S_TYPES_VOLUME_BASE = "org.alien4cloud.kubernetes.api.types.volume.VolumeBase";
    // K8S concrete types
    public static final String K8S_TYPES_CONTAINER = "org.alien4cloud.kubernetes.api.types.Container";
    public static final String K8S_TYPES_DEPLOYMENT = "org.alien4cloud.kubernetes.api.types.Deployment";
    public static final String K8S_TYPES_JOB = "org.alien4cloud.kubernetes.api.types.Job";
    public static final String K8S_TYPES_SERVICE = "org.alien4cloud.kubernetes.api.types.Service";
    // K8S volume types
    public static final String K8S_TYPES_VOLUMES_CLAIM = "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimSource";
    public static final String K8S_TYPES_VOLUMES_CLAIM_SC = "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimStorageClassSource";
    // K8S resource types
    public static final String K8S_TYPES_DEPLOYMENT_RESOURCE = "org.alien4cloud.kubernetes.api.types.DeploymentResource";
    public static final String K8S_TYPES_JOB_RESOURCE = "org.alien4cloud.kubernetes.api.types.JobResource";
    public static final String K8S_TYPES_BASE_JOB_RESOURCE = "org.alien4cloud.kubernetes.api.types.BaseJobResource";
    public static final String K8S_TYPES_BASE_RESOURCE = "org.alien4cloud.kubernetes.api.types.BaseResource";
    public static final String K8S_TYPES_SERVICE_RESOURCE = "org.alien4cloud.kubernetes.api.types.ServiceResource";
    public static final String K8S_TYPES_SIMPLE_RESOURCE = "org.alien4cloud.kubernetes.api.types.SimpleResource";
    public static final String K8S_TYPES_ENDPOINT_RESOURCE = "org.alien4cloud.kubernetes.api.types.EndpointResource";
    // K8S relationships
    public static final String K8S_TYPES_RSENDPOINT = "org.alien4cloud.kubernetes.api.relationships.K8SEndpointConnectToEndpoint";

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

    public static String generateUniqueKubeName(String prefix) {
        // TODO: length should be < 63 ??
        // TODO: better unique generation
        // we hashCode the UUID, we know that we have some collision risk, but for the moment we accept
        return generateKubeName(prefix + "-" + UUID.randomUUID().toString().hashCode());
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
     * For a given node template, returns true if the function if of type get_attribute(TARGET, requirement, property)
     * and the target is a docker container and the capability has an "ip_address" attribute (endpoint).
     */
    public static boolean isServiceIpAddress(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue) {
        return isTargetServiceAttribute(topology, sourceNodeTemplate, inputParameterValue, "ip_address");
    }

    /**
     * For a given node template, returns true if the function if of type get_attribute(TARGET, requirement, property)
     * and the target is a docker container's endpoint.
     */
    private static boolean isTargetServiceAttribute(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue, String attributeName) {
        // a get_attribute that searchs an ip_address on a requirement that targets a Docker Container should return true
        if (inputParameterValue instanceof FunctionPropertyValue) {
            FunctionPropertyValue evaluatedFunction = (FunctionPropertyValue) inputParameterValue;
            if (evaluatedFunction.getFunction().equals(ToscaFunctionConstants.GET_ATTRIBUTE)) {
                if (evaluatedFunction.getTemplateName().equals(ToscaFunctionConstants.R_TARGET)) {
                    String requirement = evaluatedFunction.getCapabilityOrRequirementName();
                    if (requirement != null) {
                        Set<NodeTemplate> targetNodes = TopologyNavigationUtil.getTargetNodes(topology, sourceNodeTemplate, requirement);
                        for (NodeTemplate targetNode : targetNodes) {
                            // is this node a container ?
                            NodeType targetNodeType = ToscaContext.get(NodeType.class, targetNode.getType());

                            // if (isOfType(targetNodeType, A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {
                            // ok the
                            if (evaluatedFunction.getElementNameToFetch().equals(attributeName)) {
                                return true;
                            }
                            // }
                        }
                    }
                }
            }
        }
        return false;
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
            if (evaluatedFunction.getFunction().equals(ToscaFunctionConstants.GET_ATTRIBUTE)) {
                if (evaluatedFunction.getTemplateName().equals(ToscaFunctionConstants.R_TARGET)) {
                    String requirement = evaluatedFunction.getCapabilityOrRequirementName();
                    if (requirement != null) {
                        Set<RelationshipTemplate> targetRelationships = TopologyNavigationUtil.getTargetRelationships(sourceNodeTemplate, requirement);
                        for (RelationshipTemplate targetRelationship : targetRelationships) {
                            // is this node a container ?
                            NodeTemplate targetNode = topology.getNodeTemplates().get(targetRelationship.getTarget());
                            NodeType targetNodeType = ToscaContext.get(NodeType.class, targetNode.getType());
                            // if (isOfType(targetNodeType, A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {

                            Capability endpoint = targetNode.getCapabilities().get(targetRelationship.getTargetedCapabilityName());
                            AbstractPropertyValue targetPropertyValue = PropertyUtil.getPropertyValueFromPath(endpoint.getProperties(),
                                    evaluatedFunction.getElementNameToFetch());
                            if (targetPropertyValue != null) {
                                return targetPropertyValue;
                            }
                            // }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * For a given deployment node template, returns the service node it depends on regarding a given input parameter of type get_attribute(TARGET, requirement,
     * property).
     */
    public static NodeTemplate getServiceDependency(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue) {
        // a get_attribute that searchs an ip_address on a requirement that targets a Docker Container should return true
        if (inputParameterValue instanceof FunctionPropertyValue) {
            FunctionPropertyValue evaluatedFunction = (FunctionPropertyValue) inputParameterValue;
            if (evaluatedFunction.getFunction().equals(ToscaFunctionConstants.GET_ATTRIBUTE)) {
                if (evaluatedFunction.getTemplateName().equals(ToscaFunctionConstants.R_TARGET)) {
                    String requirement = evaluatedFunction.getCapabilityOrRequirementName();
                    if (requirement != null) {
                        Set<NodeTemplate> targetNodes = TopologyNavigationUtil.getTargetNodes(topology, sourceNodeTemplate, requirement);
                        for (NodeTemplate targetNode : targetNodes) {
                            // is this node a container ?
                            NodeType targetNodeType = ToscaContext.get(NodeType.class, targetNode.getType());
                            if (isOfType(targetNodeType, A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {
                                // find the deployment that host this container
                                NodeTemplate deploymentNode = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, targetNode,
                                        K8S_TYPES_DEPLOYMENT);
                                if (deploymentNode != null) {
                                    return getServiceRelatedToDeployment(topology, deploymentNode, requirement);
                                }
                            } else {
                                // the target is not a container, so we should find a service that proxy the endpoint
                                Set<NodeTemplate> endpoints = TopologyNavigationUtil.getSourceNodesByRelationshipType(topology, targetNode,
                                        K8S_TYPES_RSENDPOINT);
                                NodeTemplate endpointNode = endpoints.iterator().next();
                                Set<NodeTemplate> services = TopologyNavigationUtil.getSourceNodes(topology, endpointNode, "feature");
                                return services.stream().filter(nodeTemplate -> nodeTemplate.getType().equals(K8S_TYPES_SERVICE)).findFirst().get();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * For a given deployment node, returns the service that depends on this deployment considering a given endpoint name.
     */
    public static NodeTemplate getServiceRelatedToDeployment(Topology topology, NodeTemplate deploymentNodeTemplate, String endpointName) {
        Set<NodeTemplate> sourceNodes = TopologyNavigationUtil.getSourceNodes(topology, deploymentNodeTemplate, "feature");
        for (NodeTemplate sourceNode : sourceNodes) {
            Collection<Tag> sourceNodeTags = safe(sourceNode.getTags());
            for (Tag tag : sourceNodeTags) {
                if (tag.getName().equals(KubernetesLocationTopologyModifier.A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT)) {
                    if (tag.getValue().equals(endpointName)) {
                        return sourceNode;
                    }
                }
            }
        }
        return null;
    }

}
