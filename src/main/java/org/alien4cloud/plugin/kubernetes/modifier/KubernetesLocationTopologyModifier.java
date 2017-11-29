package org.alien4cloud.plugin.kubernetes.modifier;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_APPLICATION_DOCKER_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_CONTAINER_RUNTIME;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_DOCKER_VOLUME;
import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_SERVICE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_VOLUME_BASE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateUniqueKubeName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.getContainerImageName;
import static org.alien4cloud.tosca.utils.ToscaTypeUtils.isOfType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ListPropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.normative.constants.NormativeCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.utils.NodeTemplateUtils;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.PropertyUtil;
import lombok.extern.java.Log;

/**
 * Transform an abstract topology containing <code>DockerContainer</code>s, <code>ContainerRuntime</code>s and <code>ContainerDeploymentUnit</code>s to an
 * abstract K8S topology.
 *
 * <p>
 * This modifier:
 * </p>
 * <ul>
 * <li>Replace all occurences of <code>org.alien4cloud.extended.container.types.ContainerRuntime</code> by
 * <code>org.alien4cloud.kubernetes.api.types.AbstractContainer</code> and fill k8s properties.</li>
 * <li>Replace all occurences of <code>org.alien4cloud.extended.container.types.ContainerDeploymentUnit</code> by
 * <code>org.alien4cloud.kubernetes.api.types.AbstractDeployment</code> and fill k8s properties.</li>
 * <li>Wrap all orphan <code>org.alien4cloud.kubernetes.api.types.AbstractContainer</code> inside a
 * <code>org.alien4cloud.kubernetes.api.types.AbstractDeployment</code>.</li>
 * <li>For each container endpoint, create a node of type <code>org.alien4cloud.kubernetes.api.types.AbstractService</code> that depends on the coresponding
 * deployment.</li>
 * </ul>
 * TODO: add logs using FlowExecutionContext
 *
 * alien4cloud-kubernetes-plugin:kubernetes-modifier:post-location-match
 */
@Log
@Component(value = "kubernetes-modifier")
public class KubernetesLocationTopologyModifier extends AbstractKubernetesModifier {

    public static final String A4C_KUBERNETES_MODIFIER_TAG = "a4c_kubernetes-modifier";
    public static final String A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT = A4C_KUBERNETES_MODIFIER_TAG + "_service_endpoint";

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

        // replace all ContainerRuntime by org.alien4cloud.kubernetes.api.types.AbstractContainer
        Set<NodeTemplate> containerRuntimeNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_CONTAINER_RUNTIME, false);
        containerRuntimeNodes.forEach(nodeTemplate -> transformContainerRuntime(csar, topology, nodeTemplate));

        // replace all ContainerDeploymentUnit by org.alien4cloud.kubernetes.api.types.AbstractDeployment
        Set<NodeTemplate> containerDeploymentUnitNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT, false);
        containerDeploymentUnitNodes.forEach(nodeTemplate -> transformContainerDeploymentUnit(csar, topology, nodeTemplate));

        // for each container capability of type endpoint
        // - add a org.alien4cloud.kubernetes.api.types.ServiceResource and weave depends_on relationships
        // - populate properties on the K8S AbstractContainer that host the container image
        Set<NodeTemplate> containerNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_APPLICATION_DOCKER_CONTAINER, true);
        containerNodes.forEach(nodeTemplate -> manageContainer(csar, topology, nodeTemplate, containerNodes));

        // replace all occurences of org.alien4cloud.nodes.DockerExtVolume by k8s abstract volumes
        Set<NodeTemplate> volumeNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_DOCKER_VOLUME, true);
        volumeNodes.forEach(nodeTemplate -> manageVolumes(csar, topology, nodeTemplate));
    }

    /**
     * Replace a node of type <code>org.alien4cloud.nodes.DockerExtVolume</code> by a node if type
     * <code>org.alien4cloud.kubernetes.api.types.volume.AbstractVolumeBase</code>.
     */
    private void manageVolumes(Csar csar, Topology topology, NodeTemplate nodeTemplate) {
        NodeTemplate volumeNodeTemplate = replaceNode(csar, topology, nodeTemplate, K8S_TYPES_ABSTRACT_VOLUME_BASE, K8S_CSAR_VERSION);
        String name = generateKubeName(volumeNodeTemplate.getName());
        setNodePropertyPathValue(csar, topology, volumeNodeTemplate, "name", new ScalarPropertyValue(name));

        Set<RelationshipTemplate> relationships = TopologyNavigationUtil.getTargetRelationships(volumeNodeTemplate, "attachment");
        relationships.forEach(relationshipTemplate -> manageVolumeAttachment(csar, topology, volumeNodeTemplate, relationshipTemplate));
    }

    /**
     * For a given volume node template, and it's relationship to a target container, fill the property 'volumeMounts' of the corresponding container runtime.
     */
    private void manageVolumeAttachment(Csar csar, Topology topology, NodeTemplate volumeNodeTemplate, RelationshipTemplate relationshipTemplate) {
        NodeTemplate targetNode = topology.getNodeTemplates().get(relationshipTemplate.getTarget());
        // TODO: return if not a container
        // TODO: return if not a org.alien4cloud.relationships.MountDockerVolume ?
        NodeTemplate containerNode = TopologyNavigationUtil.getImmediateHostTemplate(topology, targetNode);
        // get the container_path property from the relationship
        AbstractPropertyValue mountPath = PropertyUtil.getPropertyValueFromPath(relationshipTemplate.getProperties(), "container_path");
        // get the volume name
        AbstractPropertyValue name = PropertyUtil.getPropertyValueFromPath(volumeNodeTemplate.getProperties(), "name");
        if (mountPath != null && name != null) {
            ComplexPropertyValue volumeMount = new ComplexPropertyValue();
            volumeMount.setValue(Maps.newHashMap());
            volumeMount.getValue().put("mountPath", mountPath);
            volumeMount.getValue().put("name", name);
            appendNodePropertyPathValue(csar, topology, containerNode, "container.volumeMounts", volumeMount);
        }
    }

    /**
     * Replace this node of type ContainerRuntime by a node of type AbstractContainer.
     */
    private void transformContainerRuntime(Csar csar, Topology topology, NodeTemplate nodeTemplate) {
        nodeTemplate = replaceNode(csar, topology, nodeTemplate, K8S_TYPES_ABSTRACT_CONTAINER, K8S_CSAR_VERSION);
        setNodeTagValue(nodeTemplate, A4C_KUBERNETES_MODIFIER_TAG, "Replacement of a " + A4C_TYPES_CONTAINER_RUNTIME);

        // if containerRuntime is orphan, wrap it into an AbstractDeployment
        if (!TopologyNavigationUtil.isHosted(topology, nodeTemplate)) {
            wrapInAbstractDeployment(csar, topology, nodeTemplate);
        }
    }

    /**
     * Replace this node of type ContainerDeploymentUnit by a node of type AbstractDeployment.
     */
    private void transformContainerDeploymentUnit(Csar csar, Topology topology, NodeTemplate nodeTemplate) {
        nodeTemplate = replaceNode(csar, topology, nodeTemplate, K8S_TYPES_ABSTRACT_DEPLOYMENT, K8S_CSAR_VERSION);
        setNodeTagValue(nodeTemplate, A4C_KUBERNETES_MODIFIER_TAG, "Replacement of a " + A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT);

        AbstractPropertyValue defaultInstances = TopologyNavigationUtil.getNodeCapabilityPropertyValue(nodeTemplate, "scalable", "default_instances");
        // feed the replica property
        setNodePropertyPathValue(csar, topology, nodeTemplate, "spec.replicas", defaultInstances);
        ScalarPropertyValue deploymentName = new ScalarPropertyValue(generateUniqueKubeName(nodeTemplate.getName()));
        setNodePropertyPathValue(csar, topology, nodeTemplate, "metadata.name", deploymentName);
        setNodePropertyPathValue(csar, topology, nodeTemplate, "spec.template.metadata.labels.app", deploymentName);
    }

    /**
     * Wrap this node of type AbstractContainer into a node of type AbstractDeployment.
     */
    private void wrapInAbstractDeployment(Csar csar, Topology topology, NodeTemplate nodeTemplate) {
        NodeTemplate hostNode = addNodeTemplate(csar, topology, nodeTemplate.getName() + "Deployment", K8S_TYPES_ABSTRACT_DEPLOYMENT, K8S_CSAR_VERSION);
        setNodeTagValue(hostNode, A4C_KUBERNETES_MODIFIER_TAG, "Created to host " + nodeTemplate.getName());

        // set a generated name to the K8S object
        ScalarPropertyValue deploymentName = new ScalarPropertyValue(generateUniqueKubeName(hostNode.getName()));
        setNodePropertyPathValue(csar, topology, hostNode, "metadata.name", deploymentName);
        setNodePropertyPathValue(csar, topology, hostNode, "spec.template.metadata.labels.app", deploymentName);
        Set<NodeTemplate> hostedContainers = TopologyNavigationUtil.getSourceNodes(topology, nodeTemplate, "host");
        // we may have a single hosted container
        for (NodeTemplate containerNode : hostedContainers) {
            Capability scalableCapability = safe(containerNode.getCapabilities()).get("scalable");
            if (scalableCapability != null) {
                NodeTemplateUtils.setCapability(hostNode, "scalable", scalableCapability);
                AbstractPropertyValue defaultInstances = PropertyUtil.getPropertyValueFromPath(safe(scalableCapability.getProperties()), "default_instances");
                // feed the replica property
                setNodePropertyPathValue(csar, topology, hostNode, "spec.replicas", defaultInstances);
            }
        }
        // find the policies that target this CR node and target them to the new CU
        changePolicyTarget(topology, nodeTemplate, hostNode);

        // add the hosted on relationship between the container runtime and the deployment
        addRelationshipTemplate(csar, topology, nodeTemplate, hostNode.getName(), NormativeRelationshipConstants.HOSTED_ON, "host", "host");
    }

    /**
     * For a given node of type container, feed the k8s container properties, manage the endpoints.
     */
    private void manageContainer(Csar csar, Topology topology, NodeTemplate containerNodeTemplate, Set<NodeTemplate> allContainerNodes) {
        // father or mother
        NodeTemplate containerRuntimeNodeTemplate = TopologyNavigationUtil.getImmediateHostTemplate(topology, containerNodeTemplate);
        // gran father or mother
        NodeTemplate deploymentNodeTemplate = TopologyNavigationUtil.getImmediateHostTemplate(topology, containerRuntimeNodeTemplate);

        // fill properties on the hosting K8S container
        Map<String, AbstractPropertyValue> properties = safe(containerNodeTemplate.getProperties());
        AbstractPropertyValue cpu_share = PropertyUtil.getPropertyValueFromPath(properties, "cpu_share");
        setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.resources.requests.cpu", cpu_share);
        setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.resources.limits.cpu", cpu_share);
        AbstractPropertyValue mem_share = PropertyUtil.getPropertyValueFromPath(properties, "mem_share");
        setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.resources.requests.memory", mem_share);
        setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.resources.limits.memory", mem_share);
        String imageName = getContainerImageName(containerNodeTemplate);
        setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.image", new ScalarPropertyValue(imageName));
        setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.name",
                new ScalarPropertyValue(generateUniqueKubeName(containerNodeTemplate.getName())));
        AbstractPropertyValue docker_run_cmd = PropertyUtil.getPropertyValueFromPath(properties, "docker_run_cmd");
        if (docker_run_cmd != null && docker_run_cmd instanceof ScalarPropertyValue) {
            List<Object> values = Lists.newArrayList("/bin/bash", "-c", ((ScalarPropertyValue) docker_run_cmd).getValue());
            ListPropertyValue listPropertyValue = new ListPropertyValue();
            listPropertyValue.setValue(values);
            setNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.command", listPropertyValue);
        }
        manageContainerEndpoints(csar, topology, containerNodeTemplate, containerRuntimeNodeTemplate, deploymentNodeTemplate, allContainerNodes);
    }

    /**
     * Each capability of type endpoint is considered for a given node of type container.
     */
    private void manageContainerEndpoints(Csar csar, Topology topology, NodeTemplate containerNodeTemplate, NodeTemplate containerRuntimeNodeTemplate,
            NodeTemplate deploymentNodeTemplate, Set<NodeTemplate> allContainerNodes) {
        // find every endpoint
        Set<String> endpointNames = Sets.newHashSet();
        for (Map.Entry<String, Capability> e : safe(containerNodeTemplate.getCapabilities()).entrySet()) {
            CapabilityType capabilityType = ToscaContext.get(CapabilityType.class, e.getValue().getType());
            if (isOfType(capabilityType, NormativeCapabilityTypes.ENDPOINT)) {
                endpointNames.add(e.getKey());
            }
        }
        endpointNames.forEach(endpointName -> manageContainerEndpoint(csar, topology, containerNodeTemplate, endpointName, containerRuntimeNodeTemplate,
                deploymentNodeTemplate, allContainerNodes));
    }

    /**
     * For a given endpoint capability of a node of type container, we must create a Service node.
     */
    private void manageContainerEndpoint(Csar csar, Topology topology, NodeTemplate containerNodeTemplate, String endpointName,
            NodeTemplate containerRuntimeNodeTemplate, NodeTemplate deploymentNodeTemplate, Set<NodeTemplate> allContainerNodes) {
        // fill the ports map of the hosting K8S AbstractContainer
        AbstractPropertyValue port = containerNodeTemplate.getCapabilities().get(endpointName).getProperties().get("port");
        ComplexPropertyValue portPropertyValue = new ComplexPropertyValue(Maps.newHashMap());
        portPropertyValue.getValue().put("containerPort", port);
        portPropertyValue.getValue().put("name", new ScalarPropertyValue(generateKubeName(endpointName)));
        appendNodePropertyPathValue(csar, topology, containerRuntimeNodeTemplate, "container.ports", portPropertyValue);

        // add an abstract service node
        NodeTemplate serviceNode = addNodeTemplate(csar, topology, containerNodeTemplate.getName() + "_" + endpointName + "_Service",
                K8S_TYPES_ABSTRACT_SERVICE, K8S_CSAR_VERSION);
        setNodeTagValue(serviceNode, A4C_KUBERNETES_MODIFIER_TAG, "Proxy of node <" + containerNodeTemplate.getName() + "> capability <" + endpointName + ">");
        setNodeTagValue(serviceNode, A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT, endpointName);

        // fill properties of service
        setNodePropertyPathValue(csar, topology, serviceNode, "metadata.name", new ScalarPropertyValue(generateUniqueKubeName(serviceNode.getName())));
        setNodePropertyPathValue(csar, topology, serviceNode, "spec.service_type", new ScalarPropertyValue("NodePort"));
        // get the "pod name"
        AbstractPropertyValue podName = PropertyUtil.getPropertyValueFromPath(safe(deploymentNodeTemplate.getProperties()), "metadata.name");
        setNodePropertyPathValue(csar, topology, serviceNode, "spec.selector.app", podName);

        // fill port list
        Map<String, Object> portEntry = Maps.newHashMap();
        String portName = generateKubeName(endpointName);
        portEntry.put("name", new ScalarPropertyValue(portName));
        portEntry.put("targetPort", new ScalarPropertyValue(portName));
        portEntry.put("port", port);
        ComplexPropertyValue complexPropertyValue = new ComplexPropertyValue(portEntry);
        appendNodePropertyPathValue(csar, topology, serviceNode, "spec.ports", complexPropertyValue);

        // find the deployment node parent of the container
        NodeTemplate deploymentHost = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, containerNodeTemplate, K8S_TYPES_ABSTRACT_DEPLOYMENT);
        // add a depends_on relationship between service and the deployment unit
        addRelationshipTemplate(csar, topology, serviceNode, deploymentHost.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");

        // we should find each relationship that targets this endpoint and add a dependency between both deployments
        for (NodeTemplate containerSourceCandidateNodeTemplate : allContainerNodes) {
            if (containerSourceCandidateNodeTemplate.getName().equals(containerNodeTemplate.getName())) {
                // don't consider the current container (owner of the endpoint)
                continue;
            }

            for (RelationshipTemplate relationship : safe(containerSourceCandidateNodeTemplate.getRelationships()).values()) {
                if (relationship.getTarget().equals(containerNodeTemplate.getName()) && relationship.getTargetedCapabilityName().equals(endpointName)) {
                    // we need to add a depends_on between the source deployment and the service (if not already exist)
                    NodeTemplate sourceDeploymentHost = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, containerSourceCandidateNodeTemplate,
                            K8S_TYPES_ABSTRACT_DEPLOYMENT);
                    if (!TopologyNavigationUtil.hasRelationship(sourceDeploymentHost, serviceNode.getName(), "dependency", "feature")) {
                        addRelationshipTemplate(csar, topology, sourceDeploymentHost, serviceNode.getName(), NormativeRelationshipConstants.DEPENDS_ON,
                                "dependency", "feature");
                    }
                }
            }
        }
    }

}
