package org.alien4cloud.plugin.kubernetes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.CloneUtil;
import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.java.Log;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ListPropertyValue;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.NormativeCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.utils.NodeTemplateUtils;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.alien4cloud.tosca.utils.ToscaTypeUtils;
import org.springframework.stereotype.Component;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_APPLICATION_DOCKER_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_CONTAINER_JOB_UNIT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_CONTAINER_RUNTIME;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_DOCKER_VOLUME;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_JOB;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_SERVICE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ABSTRACT_VOLUME_BASE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ENDPOINT_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_RSENDPOINT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateKubeName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateUniqueKubeName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.getContainerImageName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.getValue;
import static org.alien4cloud.tosca.utils.ToscaTypeUtils.isOfType;

/**
 * Transform an abstract topology containing <code>DockerContainer</code>s, <code>ContainerRuntime</code>s and <code>ContainerDeploymentUnit</code>s to an
 * abstract K8S topology.
 *
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
        } catch (Exception e) {
            context.getLog().error("Couldn't process " + A4C_KUBERNETES_MODIFIER_TAG);
            log.log(Level.WARNING, "Couldn't process " + A4C_KUBERNETES_MODIFIER_TAG, e);
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
        System.out.println(">>>>>>>>>>> Replace ContainerDeploymentUnit by AbstractDeployment");
        Set<NodeTemplate> containerDeploymentUnitNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_CONTAINER_DEPLOYMENT_UNIT, false);
        containerDeploymentUnitNodes.forEach(nodeTemplate -> transformContainerDeploymentUnit(csar, topology, nodeTemplate));

        // replace all ContainerJobUnit by org.alien4cloud.kubernetes.api.types.AbstractJob
        System.out.println(">>>>>>>>>>> Replace ContainerJobUnit by AbstractJob");
        Set<NodeTemplate> containerJobUnitNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_CONTAINER_JOB_UNIT, false);
        containerJobUnitNodes.forEach(nodeTemplate -> transformContainerJobUnit(csar, topology, nodeTemplate));

        // for each container capability of type endpoint
        // - add a org.alien4cloud.kubernetes.api.types.ServiceResource and weave depends_on relationships
        // - populate properties on the K8S AbstractContainer that host the container image
        Set<NodeTemplate> containerNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_APPLICATION_DOCKER_CONTAINER, true);
        containerNodes.forEach(nodeTemplate -> manageContainer(csar, topology, nodeTemplate, containerNodes, context));
        containerNodes.forEach(nodeTemplate -> manageContainerHybridConnections(context, csar, topology, nodeTemplate));

        // replace all occurences of org.alien4cloud.nodes.DockerExtVolume by k8s abstract volumes
        Set<NodeTemplate> volumeNodes = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_DOCKER_VOLUME, true);
        volumeNodes.forEach(nodeTemplate -> manageVolumes(csar, topology, nodeTemplate));
    }

    private void manageContainerHybridConnections(FlowExecutionContext context, Csar csar, Topology topology, NodeTemplate containerNodeTemplate) {

        // targetNode -> (capabilityName -> sourceNodes)
        Map<NodeTemplate, Map<String, List<NodeTemplate>>> hybridConnections = Maps.newHashMap();
        for (Map.Entry<String, RelationshipTemplate> e : safe(containerNodeTemplate.getRelationships()).entrySet()) {
            NodeTemplate targetNodeTemplate = topology.getNodeTemplates().get(e.getValue().getTarget());
            NodeType targetNodeType = ToscaContext.getOrFail(NodeType.class, targetNodeTemplate.getType());
            if (ToscaTypeUtils.isOfType(targetNodeType, A4C_TYPES_APPLICATION_DOCKER_CONTAINER)) {
                // only consider targets that are not containers
                continue;
            }
            String endpointName = e.getValue().getTargetedCapabilityName();
            Capability capability = targetNodeTemplate.getCapabilities().get(endpointName);
            CapabilityType capabilityType = ToscaContext.getOrFail(CapabilityType.class, capability.getType());
            if (ToscaTypeUtils.isOfType(capabilityType, NormativeCapabilityTypes.ENDPOINT)) {
                // TODO: add service and endpoint resource
                Map<String, List<NodeTemplate>> endpointsSources = hybridConnections.get(targetNodeTemplate);
                if (endpointsSources == null) {
                    endpointsSources = Maps.newHashMap();
                    hybridConnections.put(targetNodeTemplate, endpointsSources);
                }
                List<NodeTemplate> sources = endpointsSources.get(endpointName);
                if (sources == null) {
                    sources = Lists.newArrayList();
                    endpointsSources.put(endpointName, sources);
                }
                sources.add(containerNodeTemplate);
            }
        }
        for (Map.Entry<NodeTemplate, Map<String, List<NodeTemplate>>> e : hybridConnections.entrySet()) {
            for (Map.Entry<String, List<NodeTemplate>> f : e.getValue().entrySet()) {
                manageContainerHybridConnection(context, csar, topology, f.getValue(), e.getKey(), f.getKey());
            }
        }
    }

    private void manageContainerHybridConnection(FlowExecutionContext context, Csar csar, Topology topology, List<NodeTemplate> containerNodeTemplates,
            NodeTemplate targetNodeTemplate, String endpointName) {

        // add an abstract service node
        NodeTemplate serviceNode = addNodeTemplate(csar, topology, targetNodeTemplate.getName() + "_" + endpointName + "_Service", K8S_TYPES_ABSTRACT_SERVICE,
                K8S_CSAR_VERSION);
        setNodeTagValue(serviceNode, A4C_KUBERNETES_MODIFIER_TAG, "Proxy of node <" + targetNodeTemplate.getName() + "> capability <" + endpointName + ">");
        setNodeTagValue(serviceNode, A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT, endpointName);

        // fill properties of service
        String serviceName = generateUniqueKubeName(serviceNode.getName());
        setNodePropertyPathValue(csar, topology, serviceNode, "metadata.name", new ScalarPropertyValue(serviceName));
        setNodePropertyPathValue(csar, topology, serviceNode, "spec.service_type", new ScalarPropertyValue("ClusterIP"));

        // get the endpoint port
        AbstractPropertyValue port = targetNodeTemplate.getCapabilities().get(endpointName).getProperties().get("port");
        if (port == null) {
            context.log().error("Connecting container to an external service requires its endpoint port to be defined. Port of [" + targetNodeTemplate.getName()
                    + ".capabilities." + endpointName + "] is not defined.");
            return;
        }

        Map<String, Object> portEntry = Maps.newHashMap();
        portEntry.put("port", port);
        ComplexPropertyValue complexPropertyValue = new ComplexPropertyValue(portEntry);
        appendNodePropertyPathValue(csar, topology, serviceNode, "spec.ports", complexPropertyValue);

        for (NodeTemplate containerNodeTemplate : containerNodeTemplates) {
            // find the deployment node parent of the container
            NodeTemplate deploymentHost = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, containerNodeTemplate,
                    K8S_TYPES_ABSTRACT_DEPLOYMENT);
            // add a depends_on relationship between service and the deployment unit
            addRelationshipTemplate(csar, topology, deploymentHost, serviceNode.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
        }

        // create the resource endpoint
        NodeTemplate endpointNode = addNodeTemplate(csar, topology, targetNodeTemplate.getName() + "_" + endpointName + "_ServiceEndpoint",
                K8S_TYPES_ENDPOINT_RESOURCE, K8S_CSAR_VERSION);
        setNodePropertyPathValue(csar, topology, endpointNode, "resource_id", new ScalarPropertyValue(serviceName));
        // fill subsets property
        Map<String, Object> subsetEntry = Maps.newHashMap();
        Map<String, Object> addresses = Maps.newHashMap();
        addresses.put("ip", "#{TARGET_IP_ADDRESS}");
        subsetEntry.put("addresses", new ListPropertyValue(Lists.newArrayList(addresses)));
        Map<String, Object> ports = Maps.newHashMap();
        ports.put("port", port);
        subsetEntry.put("ports", new ListPropertyValue(Lists.newArrayList(ports)));
        ComplexPropertyValue subsetComplexPropertyValue = new ComplexPropertyValue(subsetEntry);
        ListPropertyValue subsets = new ListPropertyValue(Lists.newArrayList(subsetComplexPropertyValue));
        setNodePropertyPathValue(csar, topology, endpointNode, "subsets", subsets);

        // add a relationship between the endpoint and the target node
        addRelationshipTemplate(csar, topology, endpointNode, targetNodeTemplate.getName(), K8S_TYPES_RSENDPOINT, "endpoint", endpointName);
        // add a relationship between the service and the endpoint node
        addRelationshipTemplate(csar, topology, serviceNode, endpointNode.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");

        // now fill the JSON property
        Map<String, AbstractPropertyValue> resourceNodeProperties = Maps.newHashMap();
        feedPropertyValue(resourceNodeProperties, "resource_def.kind", "Endpoints", false);
        feedPropertyValue(resourceNodeProperties, "resource_def.apiVersion", "v1", false);
        feedPropertyValue(resourceNodeProperties, "resource_def.metadata.name", serviceName, false);
        feedPropertyValue(resourceNodeProperties, "resource_def.metadata.labels.a4c_id", serviceName, false);
        ListPropertyValue spec = CloneUtil.clone((ListPropertyValue) endpointNode.getProperties().get("subsets"));
        NodeType nodeType = ToscaContext.get(NodeType.class, endpointNode.getType());
        PropertyDefinition propertyDefinition = nodeType.getProperties().get("subsets");
        List<Object> transformed = (List<Object>) getTransformedValue(spec, propertyDefinition, "");
        feedPropertyValue(resourceNodeProperties, "resource_def.subsets", transformed, false);

        Object propertyValue = getValue(resourceNodeProperties.get("resource_def"));
        String serializedPropertyValue = PropertyUtil.serializePropertyValue(propertyValue);
        setNodePropertyPathValue(csar, topology, endpointNode, "resource_spec", new ScalarPropertyValue(serializedPropertyValue));

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
     * Replace this node of type ContainerJobUnit by a node of type AbstractJob.
     */
    private void transformContainerJobUnit(Csar csar, Topology topology, NodeTemplate nodeTemplate) {
        nodeTemplate = replaceNode(csar, topology, nodeTemplate, K8S_TYPES_ABSTRACT_JOB, K8S_CSAR_VERSION);
        setNodeTagValue(nodeTemplate, A4C_KUBERNETES_MODIFIER_TAG, "Replacement of a " + A4C_TYPES_CONTAINER_JOB_UNIT);

        ScalarPropertyValue jobName = new ScalarPropertyValue(generateUniqueKubeName(nodeTemplate.getName()));
        setNodePropertyPathValue(csar, topology, nodeTemplate, "metadata.name", jobName);
        setNodePropertyPathValue(csar, topology, nodeTemplate, "spec.template.metadata.labels.app", jobName);
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
    private void manageContainer(Csar csar, Topology topology, NodeTemplate containerNodeTemplate, Set<NodeTemplate> allContainerNodes,
            FlowExecutionContext context) {
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
        manageContainerEndpoints(csar, topology, containerNodeTemplate, containerRuntimeNodeTemplate, deploymentNodeTemplate, allContainerNodes, context);
    }

    /**
     * Each capability of type endpoint is considered for a given node of type container.
     */
    private void manageContainerEndpoints(Csar csar, Topology topology, NodeTemplate containerNodeTemplate, NodeTemplate containerRuntimeNodeTemplate,
            NodeTemplate deploymentNodeTemplate, Set<NodeTemplate> allContainerNodes, FlowExecutionContext context) {
        // find every endpoint
        Set<String> endpointNames = Sets.newHashSet();
        for (Map.Entry<String, Capability> e : safe(containerNodeTemplate.getCapabilities()).entrySet()) {
            CapabilityType capabilityType = ToscaContext.get(CapabilityType.class, e.getValue().getType());
            if (isOfType(capabilityType, NormativeCapabilityTypes.ENDPOINT)) {
                endpointNames.add(e.getKey());
            }
        }
        endpointNames.forEach(endpointName -> manageContainerEndpoint(csar, topology, containerNodeTemplate, endpointName, containerRuntimeNodeTemplate,
                deploymentNodeTemplate, allContainerNodes, context));
    }

    /**
     * For a given endpoint capability of a node of type container, we must create a Service node.
     */
    private void manageContainerEndpoint(Csar csar, Topology topology, NodeTemplate containerNodeTemplate, String endpointName,
            NodeTemplate containerRuntimeNodeTemplate, NodeTemplate deploymentNodeTemplate, Set<NodeTemplate> allContainerNodes, FlowExecutionContext context) {
        // fill the ports map of the hosting K8S AbstractContainer
        AbstractPropertyValue port = containerNodeTemplate.getCapabilities().get(endpointName).getProperties().get("port");
        if (port == null) {
            context.log().error("Connecting container to an external requires its endpoint port to be defined. Port of [" + containerNodeTemplate.getName()
                    + ".capabilities." + endpointName + "] is not defined.");
            return;
        }
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
