package org.alien4cloud.plugin.kubernetes.modifier;

import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.paas.wf.util.WorkflowUtils;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.AlienUtils;
import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.java.Log;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.NormativeCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Log
public abstract class AbstractKubernetesTopologyModifier extends TopologyModifierSupport {

    protected static final String A4C_TYPES_CONTAINER_RUNTIME = "org.alien4cloud.extended.container.types.ContainerRuntime";
    protected static final String A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT = "org.alien4cloud.extended.container.types.ContainerDeploymentUnit";
    protected static final String A4C_TYPES_APPLICATION_DOCKER_CONTAINER = "tosca.nodes.Container.Application.DockerContainer";
    protected static final String K8S_TYPES_ABSTRACT_CONTAINER = "org.alien4cloud.kubernetes.api.types.AbstractContainer";
    protected static final String K8S_TYPES_ABSTRACT_DEPLOYMENT = "org.alien4cloud.kubernetes.api.types.AbstractDeployment";
    protected static final String K8S_TYPES_ABSTRACT_SERVICE = "org.alien4cloud.kubernetes.api.types.AbstractService";
    protected static final String K8S_TYPES_CONTAINER = "org.alien4cloud.kubernetes.api.types.Container";
    protected static final String K8S_TYPES_DEPLOYMENT = "org.alien4cloud.kubernetes.api.types.Deployment";
    protected static final String K8S_TYPES_DEPLOYMENT_RESOURCE = "org.alien4cloud.kubernetes.api.types.DeploymentResource";
    protected static final String K8S_TYPES_RESOURCE = "org.alien4cloud.kubernetes.api.types.BaseResource";
    protected static final String K8S_TYPES_SERVICE = "org.alien4cloud.kubernetes.api.types.Service";
    protected static final String K8S_TYPES_SERVICE_RESOURCE = "org.alien4cloud.kubernetes.api.types.ServiceResource";

    protected static final String K8S_CSAR_VERSION = "2.0.0-SNAPSHOT";

    /**
     * Get the image name from the type implementation artifact file.
     * TODO: make error prone
     */
    protected String getContainerImageName(NodeTemplate nodeTemplate) {
        NodeType nodeType = ToscaContext.get(NodeType.class, nodeTemplate.getType());
        Interface stdInterface = nodeType.getInterfaces().get(ToscaNodeLifecycleConstants.STANDARD);
        Operation createOperation = stdInterface.getOperations().get(ToscaNodeLifecycleConstants.CREATE);
        return createOperation.getImplementationArtifact().getArtifactRef();
    }

    /**
     * K8S names must be in lower case and can't contain _
     */
    protected String generateKubeName(String candidate) {
        return candidate.toLowerCase().replaceAll("_", "-");
    }

}
