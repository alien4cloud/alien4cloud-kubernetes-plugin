package org.alien4cloud.plugin.kubernetes.modifier;

import alien4cloud.model.common.Tag;
import alien4cloud.paas.wf.util.WorkflowUtils;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.utils.AlienUtils;
import alien4cloud.utils.PropertyUtil;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.FunctionPropertyValue;
import org.alien4cloud.tosca.model.definitions.IValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.ToscaFunctionConstants;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A utility to browse Kube topologies (enhanced by Kube modifiers).
 */
public class KubeTopologyUtils {

    /**
     * For a given node template, returns true if the function if of type get_attribute(TARGET, requirement, property)
     * and the target is a docker container and the capability has an "ip_address" attribute (endpoint).
     */
    public static boolean isServiceIpAddress(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
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

                            if (WorkflowUtils.isOfType(targetNodeType, AbstractKubernetesTopologyModifier.A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {
                                // ok the
                                if (evaluatedFunction.getElementNameToFetch().equals(attributeName)) {
                                    return true;
                                }
                            }
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
    public static boolean isTargetedEndpointProperty(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
        AbstractPropertyValue abstractPropertyValue = getTargetedEndpointProperty(topology, sourceNodeTemplate, inputParameterValue);
        return abstractPropertyValue != null;
    }

    /**
     * For a given node template, if the inputParameterValue value is a function if of type get_attribute(TARGET, requirement, property)
     * and the target is a docker container, return the value of the property.
     */
    public static AbstractPropertyValue getTargetedEndpointProperty(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
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
                            if (WorkflowUtils.isOfType(targetNodeType, AbstractKubernetesTopologyModifier.A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {

                                Capability endpoint = targetNode.getCapabilities().get(targetRelationship.getTargetedCapabilityName());
                                AbstractPropertyValue targetPropertyValue = PropertyUtil.getPropertyValueFromPath(endpoint.getProperties(), evaluatedFunction.getElementNameToFetch());
                                if (targetPropertyValue != null) {
                                    return targetPropertyValue;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * For a given deployment node template, returns the service node it depends on regarding a given input parameter of type get_attribute(TARGET, requirement, property).
     */
    public static NodeTemplate getServiceDependency(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
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
                            if (WorkflowUtils.isOfType(targetNodeType, AbstractKubernetesTopologyModifier.A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {
                                // find the deployment that host this container
                                NodeTemplate deploymentNode = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology,targetNode, AbstractKubernetesTopologyModifier.K8S_TYPES_DEPLOYMENT);
                                if (deploymentNode != null) {
                                    return getServiceRelatedToDeployment(topology, deploymentNode, requirement);
                                }
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
            Collection<Tag> sourceNodeTags = AlienUtils.safe(sourceNode.getTags());
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
