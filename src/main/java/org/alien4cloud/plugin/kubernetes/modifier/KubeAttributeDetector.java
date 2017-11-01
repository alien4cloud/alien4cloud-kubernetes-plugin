package org.alien4cloud.plugin.kubernetes.modifier;

import alien4cloud.model.common.Tag;
import alien4cloud.paas.wf.util.WorkflowUtils;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.utils.AlienUtils;
import org.alien4cloud.tosca.model.definitions.FunctionPropertyValue;
import org.alien4cloud.tosca.model.definitions.IValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.ToscaFunctionConstants;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Created by xdegenne on 31/10/2017.
 */
public class KubeAttributeDetector {

    public static boolean isServiceIpAddress(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
        return isTargetServiceAttribute(topology, sourceNodeTemplate, inputParameterValue, "ip_address");
    }

    public static boolean isServicePort(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
        return isTargetServiceAttribute(topology, sourceNodeTemplate, inputParameterValue, "port");
    }

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

    public static String getTargetedEndpointPort(Topology topology, NodeTemplate sourceNodeTemplate, IValue inputParameterValue ) {
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
// Each capabilities of type endpoint
                                //                                targetNode.getCapabilities().
//                                TODO: get the target port
//                                return targetNode;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

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
