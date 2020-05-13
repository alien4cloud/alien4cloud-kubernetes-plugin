package org.alien4cloud.plugin.kubernetes.modifier;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_APPLICATION_DOCKER_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_BASE_JOB_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_BASE_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ENDPOINT_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_JOB;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_JOB_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE_INGRESS;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SIMPLE_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_STATEFULSET;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_STATEFULSET_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_VOLUME_BASE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateConsistentKubeName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.getValue;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.K8S_POLICIES_AUTO_SCALING;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

import javax.annotation.Resource;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.ConcatPropertyValue;
import org.alien4cloud.tosca.model.definitions.DeploymentArtifact;
import org.alien4cloud.tosca.model.definitions.ListPropertyValue;
import org.alien4cloud.tosca.model.definitions.Operation;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.alien4cloud.tosca.model.definitions.PropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.ServiceNodeTemplate;
import org.alien4cloud.tosca.model.templates.SubstitutionTarget;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.AbstractToscaType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.model.types.PolicyType;
import org.alien4cloud.tosca.normative.constants.AlienCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.utils.FunctionEvaluator;
import org.alien4cloud.tosca.utils.FunctionEvaluatorContext;
import org.alien4cloud.tosca.utils.NodeTemplateUtils;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.alien4cloud.tosca.utils.ToscaTypeUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.paas.wf.TopologyContext;
import alien4cloud.paas.wf.WorkflowSimplifyService;
import alien4cloud.paas.wf.WorkflowsBuilderService;
import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.tosca.parser.ToscaParser;
import alien4cloud.tosca.serializer.ToscaPropertySerializerUtils;
import alien4cloud.utils.CloneUtil;
import alien4cloud.utils.MapUtil;
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
public class KubernetesFinalTopologyModifier extends AbstractKubernetesModifier {

    public static final String A4C_KUBERNETES_MODIFIER_TAG = "a4c_kubernetes-final-modifier";

    @Resource
    private WorkflowSimplifyService workflowSimplifyService;

    @Resource
    private WorkflowsBuilderService workflowBuilderService;

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(topology, context);
            TopologyContext topologyContext = workflowBuilderService.buildCachedTopologyContext(new TopologyContext() {
                @Override
                public String getDSLVersion() {
                    return ToscaParser.LATEST_DSL;
                }

                @Override
                public Topology getTopology() {
                    return topology;
                }

                @Override
                public <T extends AbstractToscaType> T findElement(Class<T> clazz, String elementId) {
                    return ToscaContext.get(clazz, elementId);
                }
            });
            // TODO: should be done in the deployment flow instead of here
            workflowSimplifyService.reentrantSimplifyWorklow(topologyContext, topology.getWorkflows().keySet());
        } catch (Exception e) {
            context.getLog().error("Couldn't process " + A4C_KUBERNETES_MODIFIER_TAG);
            log.log(Level.WARNING, "Couldn't process " + A4C_KUBERNETES_MODIFIER_TAG, e);
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
        }
    }

    private void doProcess(Topology topology, FlowExecutionContext context) {
        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        Set<NodeTemplate> endpointNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_ENDPOINT_RESOURCE, false, true);
        endpointNodes.forEach(nodeTemplate -> manageEndpoints(context, csar, topology, nodeTemplate));

        // just a map that store the node name as key and the replacement node as value
        Map<String, NodeTemplate> nodeReplacementMap = Maps.newHashMap();

        // store the yaml structure for each resources
        // these yaml structure will become the JSON resource spec after JSON serialization
        // these yaml structures can not be stored in node since they can't respect any TOSCA contract
        Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures = Maps.newHashMap();

        // for each Service create a node of type ServiceResource
        Set<NodeTemplate> serviceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_SERVICE, true);
        serviceNodes = demultiplexServices(csar, topology, serviceNodes, context);
        serviceNodes.forEach(nodeTemplate -> createServiceResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures, context));

        // for each Deployment create a node of type DeploymentResource
        Set<NodeTemplate> deploymentNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_DEPLOYMENT, false);
        deploymentNodes.forEach(nodeTemplate -> createDeploymentResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // for each StatefulSet create a node of type StatefulSetResource
        Set<NodeTemplate> statefulSetNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_STATEFULSET, false);
        statefulSetNodes.forEach(nodeTemplate -> createStatefulSetResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures, context));

        // for each Job create a node of type JobResource
        Set<NodeTemplate> jobNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_JOB, false);
        jobNodes.forEach(nodeTemplate -> createJobResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // A function evaluator context will be usefull
        // FIXME: use topology inputs ?
        Map<String, AbstractPropertyValue> inputValues = Maps.newHashMap();
        FunctionEvaluatorContext functionEvaluatorContext = new FunctionEvaluatorContext(topology, inputValues);

        Map<String, List<String>> serviceIpAddressesPerDeploymentResource = Maps.newHashMap();

        // for each container,
        Set<NodeTemplate> containerNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_CONTAINER, false);
        containerNodes.forEach(
                nodeTemplate -> manageContainer(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures, functionEvaluatorContext,
                        serviceIpAddressesPerDeploymentResource, context));

        // for each volume node, populate the 'volumes' property of the corresponding deployment resource
        Set<NodeTemplate> volumeNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_VOLUME_BASE, true);
        volumeNodes.forEach(nodeTemplate -> manageVolume(context, csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // check auto-scaling policies and build the equiv node
        Set<PolicyTemplate> policies = TopologyNavigationUtil.getPoliciesOfType(topology, K8S_POLICIES_AUTO_SCALING, true);
        policies.forEach(policyTemplate -> manageAutoScaling(csar, topology, policyTemplate, nodeReplacementMap, resourceNodeYamlStructures, context));

        // remove useless nodes
        // TODO bug on node matching view since these nodes are the real matched ones
        // TODO then find a way to delete servicesNodes and deloymentNodes as they are not used
        serviceNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        deploymentNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        jobNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        statefulSetNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        Set<NodeTemplate> volumes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_VOLUME_BASE, true);
        volumes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        Set<NodeTemplate> containers = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_APPLICATION_DOCKER_CONTAINER, true, false);
        safe(containers).forEach(nodeTemplate -> removeNode(topology, nodeTemplate));

        String providedNamespace = getProvidedMetaproperty(context, K8S_NAMESPACE_METAPROP_NAME);
        if (providedNamespace != null) {
            context.getLog().info("All resources will be created into the namespace <" + providedNamespace + ">");
        }
        // finally set the 'resource_spec' property with the JSON content of the resource specification
        Set<NodeTemplate> resourceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_BASE_RESOURCE, true);
        // also treat job resources
        Set<NodeTemplate> jobResourceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_BASE_JOB_RESOURCE, true);
        for (NodeTemplate jobResourceNode : jobResourceNodes) {
            resourceNodes.add(jobResourceNode);
        }
        for (NodeTemplate resourceNode : resourceNodes) {
            Map<String, AbstractPropertyValue> resourceNodeProperties = resourceNodeYamlStructures.get(resourceNode.getName());
            if (resourceNodeProperties != null && resourceNodeProperties.containsKey("resource_def")) {
                if (providedNamespace != null) {
                    feedPropertyValue(resourceNodeProperties, "resource_def.metadata.namespace", providedNamespace, false);
                    setNodePropertyPathValue(csar, topology, resourceNode, "namespace", new ScalarPropertyValue(providedNamespace));
                }
                Object propertyValue = getValue(resourceNodeProperties.get("resource_def"));
                String serializedPropertyValue = PropertyUtil.serializePropertyValue(propertyValue);
                setNodePropertyPathValue(csar, topology, resourceNode, "resource_spec", new ScalarPropertyValue(serializedPropertyValue));
            } else {
                setNodePropertyPathValue(csar, topology, resourceNode, "resource_spec", new ScalarPropertyValue("N/A"));
            }
            if (providedNamespace != null) {
                setNodePropertyPathValue(csar, topology, resourceNode, "namespace", new ScalarPropertyValue(providedNamespace));
            }
        }

        // remove services that are no more target of relationships
        Set<NodeTemplate> servicesToRemove = Sets.newHashSet();
        for (NodeTemplate node : topology.getNodeTemplates().values()) {
            if (node instanceof ServiceNodeTemplate) {
                Set<NodeTemplate> sourceNodes = TopologyNavigationUtil.getSourceNodesByRelationshipType(topology, node, NormativeRelationshipConstants.ROOT);
                if (sourceNodes.isEmpty()) {
                    servicesToRemove.add(node);
                }
            }
        }
        servicesToRemove.stream().forEach(nodeTemplate -> removeNode(topology, nodeTemplate));

    }

    /**
     * When several abstract services have been matched to the same named service (service with a fixed service name), we only want one to be created.
     */
    private Set<NodeTemplate> demultiplexServices(Csar csar, Topology topology, Set<NodeTemplate> serviceNodes, FlowExecutionContext context) {

        // feed a map of service_name -> nodes
        Map<String, Set<NodeTemplate>> servicesNamesByName = Maps.newHashMap();
        for (NodeTemplate serviceNode : serviceNodes) {
            AbstractPropertyValue serviceNamePV = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "service_name");
            if (serviceNamePV != null && serviceNamePV instanceof ScalarPropertyValue) {
                String serviceName = ((ScalarPropertyValue)serviceNamePV).getValue();
                if (StringUtils.isNotEmpty(serviceName)) {
                    Set<NodeTemplate> namedNodes = servicesNamesByName.get(serviceName);
                    if (namedNodes == null) {
                        namedNodes = Sets.newHashSet();
                        servicesNamesByName.put(serviceName, namedNodes);
                    }
                    namedNodes.add(serviceNode);
                }
            }
        }
        // remove all entries that only contains 1 element
        Stream<Map.Entry<String, Set<NodeTemplate>>> servicesToDemultiplex = servicesNamesByName.entrySet().stream().filter(e -> e.getValue().size() > 1);

        servicesToDemultiplex.forEach(e -> {
            // if services for a same node target a different container this is wrong
            final Set<NodeTemplate> nodesToRemove = Sets.newHashSet();

            // we well know we have more than 1 node in this iterator
            NodeTemplate nodeToKeep = null;
            for (NodeTemplate nodeToKeepCandidate : e.getValue()) {
                // we prefer to keep a node that have consumer since we are not able to move relationship
                Set<NodeTemplate> consumers = TopologyNavigationUtil.getSourceNodesByRelationshipType(topology, nodeToKeepCandidate, NormativeRelationshipConstants.DEPENDS_ON);
                if (consumers != null && !consumers.isEmpty()) {
                    nodeToKeep = nodeToKeepCandidate;
                    break;
                }
            }
            if (nodeToKeep == null) {
                nodeToKeep = e.getValue().stream().findFirst().get();
            }

            String nodeToKeepServiceType = PropertyUtil.getScalarPropertyValueFromPath(safe(nodeToKeep.getProperties()), "spec.service_type");

            Set<RelationshipTemplate> relationships = TopologyNavigationUtil.getTargetRelationships(nodeToKeep, "dependency");
            // we know we have 1 and only 1 relationship (1 service per endpoint)
            RelationshipTemplate relationshipTemplate = relationships.stream().findFirst().get();
            String deploymentUnitNodeName = relationshipTemplate.getTarget();
            StringBuilder nodeToKeepServiceEndpointTag = new StringBuilder("");

            for (NodeTemplate nodeToRemoveCandidate : e.getValue()) {

                if (nodeToRemoveCandidate == nodeToKeep) {
                    continue;
                }

                NodeTemplate nodeToRemove = nodeToRemoveCandidate;
                // check that services with same service name target the same deployment container
                RelationshipTemplate relationshipToRemove = TopologyNavigationUtil.getTargetRelationships(nodeToKeep, "dependency").iterator().next();
                if (!relationshipToRemove.getTarget().equals(deploymentUnitNodeName)) {
                    String msg = String.format("Same service name (%s) used for services that target different deployment units (at least %s and %s), please review your matching !", e.getKey(), nodeToKeep.getName(), nodeToRemove.getName());
                    context.getLog().error(msg);
                    throw new UnsupportedOperationException();
                }
                // check that services with same service name are of the same type
                String nodeToRemoveServiceType = PropertyUtil.getScalarPropertyValueFromPath(safe(nodeToRemove.getProperties()), "spec.service_type");
                if (!StringUtils.equals(nodeToKeepServiceType, nodeToRemoveServiceType)) {
                    String msg = String.format("Same service name (%s) used for services that are different service types (%s (%s) != %s (%s)), please review your matching !", e.getKey(), nodeToKeep.getName(), nodeToKeepServiceType, nodeToRemove.getName(), nodeToRemoveServiceType);
                    context.getLog().error(msg);
                    throw new UnsupportedOperationException();
                }

                AbstractPropertyValue serviceNamePV = PropertyUtil.getPropertyValueFromPath(safe(nodeToRemove.getProperties()), "spec.ports");
                if (serviceNamePV instanceof ListPropertyValue) {
                    List<Object> ports = ((ListPropertyValue)serviceNamePV).getValue();
                    if (!ports.isEmpty()) {
                        Object port = ports.iterator().next();
                        feedPropertyValue(nodeToKeep.getProperties(), "spec.ports", port, true);
                        nodesToRemove.add(nodeToRemove);
                        // since this tag is used by modifier elsewhere, we use it's value to set a tag on the node we keep
                        String serviceEndpointTag = getNodeTagValueOrNull(nodeToRemove, A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT);
                        nodeToKeepServiceEndpointTag.append(",").append(serviceEndpointTag);

                    }
                }
            }
            nodesToRemove.stream().forEach(s -> {
                serviceNodes.remove(s);
                // if the node to remove is the target of dependsOn relationship (a consumer), let's move it to the node we keep
                removeNode(topology, s);
            });

            setNodeTagValue(nodeToKeep, A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINTS, nodeToKeepServiceEndpointTag.toString());
        });

        return serviceNodes;
    }

    protected RelationshipTemplate addRelationshipTemplate(Csar csar, Topology topology, NodeTemplate sourceNode, String targetNodeName,
                                                           String relationshipTypeName, String requirementName, String capabilityName) {

        Set<RelationshipTemplate> targetRelationships = TopologyNavigationUtil.getTargetRelationships(sourceNode, requirementName);
        for (RelationshipTemplate relationshipTemplate : targetRelationships) {
            if (relationshipTemplate.getTarget().equals(targetNodeName) && relationshipTemplate.getTargetedCapabilityName().equals(capabilityName)) {
                return relationshipTemplate;
            }
        }
        return super.addRelationshipTemplate(csar, topology, sourceNode, targetNodeName, relationshipTypeName, requirementName, capabilityName);
    }

    private void manageEndpoints(FlowExecutionContext context, Csar csar, Topology topology, NodeTemplate endpointNode) {
        // and enpoint is connected to a regular node (not a container), but maybe to a service that has been matched
        // we just ensure the port is up-to-date now (after matching)
        // so we set port property on Endpoint node and the service node that both proxy the a4c service node
        Set<RelationshipTemplate> targetRelationships = TopologyNavigationUtil.getTargetRelationships(endpointNode, "endpoint");
        if (targetRelationships == null || targetRelationships.size() > 1) {
            // should never occur
            return;
        }
        RelationshipTemplate targetRelationship = targetRelationships.iterator().next();
        NodeTemplate targetNodeTemplate = topology.getNodeTemplates().get(targetRelationship.getTarget());
        AbstractPropertyValue port = TopologyNavigationUtil.getNodeCapabilityPropertyValue(targetNodeTemplate, targetRelationship.getTargetedCapabilityName(), "port");
        if (port == null) {
            context.log().error("Connecting container to an external service requires its endpoint port to be defined. Port of [" + targetNodeTemplate.getName()
                    + ".capabilities." + targetRelationship.getTargetedCapabilityName() + "] is not defined.");
            return;
        }

        Set<NodeTemplate> targetEndpointNodes = TopologyNavigationUtil.getTargetNodes(topology, endpointNode, "endpoint");
        if (targetEndpointNodes == null || targetEndpointNodes.size() > 1) {
            // should never occur
            return;
        }
        // this is the K8S targeted service node (maybe an abstract service that has now been matched)
        NodeTemplate targetEndpointNode = targetEndpointNodes.iterator().next();
        // now search for the service that depends on this endpoint
        Set<NodeTemplate> targetServiceNodes = TopologyNavigationUtil.getSourceNodes(topology, endpointNode, "feature");
        if (targetServiceNodes == null || targetServiceNodes.size() > 1) {
            // should never occur
            return;
        }
        NodeTemplate targetServiceNode = targetServiceNodes.iterator().next();

        // fill service property
        Map<String, Object> portEntry = Maps.newHashMap();
        portEntry.put("port", port);
        ComplexPropertyValue complexPropertyValue = new ComplexPropertyValue(portEntry);
        appendNodePropertyPathValue(csar, topology, targetServiceNode, "spec.ports", complexPropertyValue);

        String ipAddress = "#{TARGET_IP_ADDRESS}";
        if (targetEndpointNode instanceof ServiceNodeTemplate) {
            ServiceNodeTemplate serviceNodeTemplate = (ServiceNodeTemplate)targetEndpointNode;
            ipAddress = safe(serviceNodeTemplate.getAttributeValues()).get("capabilities." + targetRelationship.getTargetedCapabilityName() + ".ip_address");
        }

        // fill endpoint subsets property
        Map<String, Object> subsetEntry = Maps.newHashMap();
        Map<String, Object> addresses = Maps.newHashMap();
        addresses.put("ip", ipAddress);
        subsetEntry.put("addresses", new ListPropertyValue(Lists.newArrayList(addresses)));
        Map<String, Object> ports = Maps.newHashMap();
        ports.put("port", port);
        subsetEntry.put("ports", new ListPropertyValue(Lists.newArrayList(ports)));
        ComplexPropertyValue subsetComplexPropertyValue = new ComplexPropertyValue(subsetEntry);
        ListPropertyValue subsets = new ListPropertyValue(Lists.newArrayList(subsetComplexPropertyValue));
        setNodePropertyPathValue(csar, topology, endpointNode, "subsets", subsets);
    }

    private void manageVolume(FlowExecutionContext ctx, Csar csar, Topology topology, NodeTemplate volumeNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {

        // FIXME : doesn't support many attachement (1 volume -> many containers) ?)
        Optional<RelationshipTemplate> relationshipTemplate = TopologyNavigationUtil.getTargetRelationships(volumeNode, "attachment").stream().findFirst();
        if (!relationshipTemplate.isPresent()) {
            return;
        }
        NodeTemplate targetContainer = topology.getNodeTemplates().get(relationshipTemplate.get().getTarget());
        // find the deployment that hosts this container
        NodeTemplate hostOfContainer = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, targetContainer, K8S_TYPES_DEPLOYMENT);
        if (hostOfContainer == null) {
            // find the job that hosts this container
            hostOfContainer = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, targetContainer, K8S_TYPES_JOB);
        }
        if(hostOfContainer == null){
            hostOfContainer = TopologyNavigationUtil.getHostOfTypeInHostingHierarchy(topology, targetContainer, K8S_TYPES_STATEFULSET);
        }
        if (hostOfContainer == null) {
            ctx.getLog().error("failed to get controller hosting volume <"+ volumeNode.getName() + ">");
            return;
        }
        // get the deployment resource corresponding to this deployment
        NodeTemplate controllerResourceNode = nodeReplacementMap.get(hostOfContainer.getName());

        NodeType volumeNodeType = ToscaContext.get(NodeType.class, volumeNode.getType());
        NodeType hostNodeType = ToscaContext.get(NodeType.class, hostOfContainer.getType());
        if(ToscaTypeUtils.isOfType(hostNodeType, K8S_TYPES_STATEFULSET)){
            // If statefulSet does not match a PVC, raise error
            if(!ToscaTypeUtils.isOfType(volumeNodeType, KubeTopologyUtils.K8S_TYPES_VOLUMES_CLAIM)){
                ctx.log().error("StatefulSet "+hostOfContainer.getName()+" should match a PersistentVolumeClaim resource !");
                return;
            }
            manageVolumeClaimTemplates(ctx, csar, topology, volumeNode, resourceNodeYamlStructures, controllerResourceNode);
        }else{
            managePersistentVolumeClaim(ctx, csar, topology, volumeNode, resourceNodeYamlStructures, controllerResourceNode);
        }

        Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = resourceNodeYamlStructures.get(controllerResourceNode.getName());
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

        if (ToscaTypeUtils.isOfType(volumeNodeType, KubeTopologyUtils.K8S_TYPES_SECRET_VOLUME)) {
            // we must create a secret, the deployment should depend on it
            NodeTemplate secretFactory = addNodeTemplate(csar, topology, volumeNode.getName() + "_Secret", KubeTopologyUtils.K8S_TYPES_SECRET_FACTORY,
                    K8S_CSAR_VERSION);
            String secretName = generateUniqueKubeName(ctx, volumeNode.getName());
            setNodePropertyPathValue(csar, topology, secretFactory, "name", new ScalarPropertyValue(secretName));
            // we must also define the secretName of the secret
            TopologyModifierSupport.feedMapOrComplexPropertyEntry(volumeSpecObject, "secretName", secretName);

            Map<String, DeploymentArtifact> artifacts = secretFactory.getArtifacts();
            if (artifacts == null) {
                artifacts = Maps.newHashMap();
                secretFactory.setArtifacts(artifacts);
            }
            artifacts.put("resources", volumeNode.getArtifacts().get("resources"));
            addRelationshipTemplate(csar, topology, controllerResourceNode, secretFactory.getName(), NormativeRelationshipConstants.DEPENDS_ON,
                    "dependency", "feature");
        }
    }


    private void manageVolumeClaimTemplates(FlowExecutionContext ctx, Csar csar, Topology topology, NodeTemplate volumeNode,
    Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, NodeTemplate statefulsetResourceNode) {
        AbstractPropertyValue size = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "size");
                if(size ==null){
                    ctx.log().error("Volume node "+volumeNode.getName()+" should have a size !");
                }

        NodeType nodeType = ToscaContext.get(NodeType.class, volumeNode.getType());
        String stsName = statefulsetResourceNode.getName();
        Map<String, AbstractPropertyValue> stsResourceNodeProperties = resourceNodeYamlStructures.get(stsName);

        //Create a list of claim templates if it doesn't exists
        AbstractPropertyValue vctpl = PropertyUtil.getPropertyValueFromPath(stsResourceNodeProperties, "resource_def.spec.volumeClaimTemplates");
        if(vctpl == null)
            feedPropertyValue(stsResourceNodeProperties, "resource_def.spec.volumeClaimTemplates", new ListPropertyValue(Lists.newArrayList()), false);

        //Feed volume infos
        Map<String, AbstractPropertyValue> volumeClaimResource = Maps.newHashMap();
        //String volumeName = generateKubeName(volumeNode.getName());
        AbstractPropertyValue volumeName = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "name");
        feedPropertyValue(volumeClaimResource, "metadata.name", volumeName, false);
        AbstractPropertyValue accessModesProperty = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "accessModes");
        feedPropertyValue(volumeClaimResource, "spec.accessModes", accessModesProperty, true);
        PropertyDefinition propertyDefinition = nodeType.getProperties().get("size");
        Object transformedSize = getTransformedValue(size, propertyDefinition, "");
        feedPropertyValue(volumeClaimResource, "spec.resources.requests.storage", transformedSize, false);
        NodeType volumeNodeType = ToscaContext.get(NodeType.class, volumeNode.getType());
        if (ToscaTypeUtils.isOfType(volumeNodeType, KubeTopologyUtils.K8S_TYPES_VOLUMES_CLAIM_SC)) {
            // add the storage class name to the claim
            AbstractPropertyValue storageClassNameProperty = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "storageClassName");
            feedPropertyValue(volumeClaimResource, "resource_def.spec.storageClassName", storageClassNameProperty, false);
        }

        //Add one corresponding to the pvc matched into volumeClaimTemplates
        feedPropertyValue(stsResourceNodeProperties, "resource_def.spec.volumeClaimTemplates", volumeClaimResource, true);


    }

    private void managePersistentVolumeClaim(FlowExecutionContext ctx, Csar csar, Topology topology, NodeTemplate volumeNode,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, NodeTemplate deploymentResourceNode) {
        // in case of empty claimName for a PersistentVolumeClaimSource then create a node of type PersistentVolumeClaim
        NodeType volumeNodeType = ToscaContext.get(NodeType.class, volumeNode.getType());
        if (ToscaTypeUtils.isOfType(volumeNodeType, KubeTopologyUtils.K8S_TYPES_VOLUMES_CLAIM)) {
            AbstractPropertyValue claimNamePV = PropertyUtil.getPropertyValueFromPath(volumeNode.getProperties(), "spec.claimName");
            if (claimNamePV == null) {
                NodeTemplate volumeClaimResource = addNodeTemplate(csar, topology, volumeNode.getName() + "_PVC", KubeTopologyUtils.K8S_TYPES_SIMPLE_RESOURCE,
                        K8S_CSAR_VERSION);

                Map<String, AbstractPropertyValue> volumeClaimResourceNodeProperties = Maps.newHashMap();
                resourceNodeYamlStructures.put(volumeClaimResource.getName(), volumeClaimResourceNodeProperties);

                String claimName = generateUniqueKubeName(ctx, volumeNode.getName());
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
                if(size ==null){
                    ctx.log().error("Volume node "+volumeNode.getName()+" should have a size !");
                }
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
                addRelationshipTemplate(csar, topology, deploymentResourceNode, volumeClaimResource.getName(), NormativeRelationshipConstants.DEPENDS_ON,
                        "dependency", "feature");
            }
        }
    }

    private void manageAutoScaling(Csar csar, Topology topology, PolicyTemplate policyTemplate, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, FlowExecutionContext context) {

        if (CollectionUtils.isEmpty(policyTemplate.getTargets())) {
            context.log().warn("Auto-scaling policy <{}> is not correctly configured, at least 1 targets is required. It will be ignored.",
                    policyTemplate.getName());
            return;
        }

        if (safe(policyTemplate.getProperties()).get("spec") == null) {
            context.log()
                    .warn("Auto-scaling policy <{}> is not correctly configured, property \"spec\" is required. It will be ignored.", policyTemplate.getName());

            return;
        }

        Set<NodeTemplate> validTargets = getValidTargets(policyTemplate, topology, K8S_TYPES_DEPLOYMENT, invalidName -> context.log()
                .warn("Auto-scaling policy <{}>: will ignore target <{}> as it IS NOT an instance of <{}>.", policyTemplate.getName(), invalidName,
                        K8S_TYPES_DEPLOYMENT));

        // for each target, add a SimpleResource for HorizontaPodAutoScaler, targeting the related DeploymentResource
        validTargets.forEach(nodeTemplate -> addHorizontalPodAutoScalingResource(context, csar, topology, policyTemplate, nodeTemplate, nodeReplacementMap,
                resourceNodeYamlStructures));

    }

    private void addHorizontalPodAutoScalingResource(FlowExecutionContext ctx, Csar csar, Topology topology, PolicyTemplate policyTemplate, NodeTemplate target,
            Map<String, NodeTemplate> nodeReplacementMap, Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        String resourceBaseName = target.getName() + "_" + policyTemplate.getName();
        NodeTemplate podAutoScalerResourceNode = addNodeTemplate(csar, topology, resourceBaseName + "_Resource", K8S_TYPES_SIMPLE_RESOURCE, K8S_CSAR_VERSION);

        Map<String, AbstractPropertyValue> podAutoScalerResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(podAutoScalerResourceNode.getName(), podAutoScalerResourceNodeProperties);

        NodeTemplate targetDeploymentResourceNode = nodeReplacementMap.get(target.getName());
        Map<String, AbstractPropertyValue> targetDeploymentResourceNodeProps = resourceNodeYamlStructures.get(targetDeploymentResourceNode.getName());
        String podAutoScalerName = generateUniqueKubeName(ctx, resourceBaseName);

        feedPropertyValue(podAutoScalerResourceNode.getProperties(), "resource_type", new ScalarPropertyValue("hpa"), false);
        feedPropertyValue(podAutoScalerResourceNode.getProperties(), "resource_id", new ScalarPropertyValue(podAutoScalerName), false);

        // fill the future JSON spec
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.kind", "HorizontalPodAutoscaler", false);
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.apiVersion", "autoscaling/v2beta1", false);
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.metadata.name", podAutoScalerName, false);
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.metadata.labels.a4c_id", podAutoScalerName, false);

        // Do this to make sure integer values are not serialized as string
        ComplexPropertyValue spec = CloneUtil.clone((ComplexPropertyValue) policyTemplate.getProperties().get("spec"));
        PolicyType policyType = ToscaContext.get(PolicyType.class, policyTemplate.getType());
        PropertyDefinition propertyDefinition = policyType.getProperties().get("spec");
        Map<String, Object> transformed = safe((Map<String, Object>) getTransformedValue(spec, propertyDefinition, ""));

        // get targeted resource identification properties
        AbstractPropertyValue apiVersionProperty = PropertyUtil.getPropertyValueFromPath(targetDeploymentResourceNodeProps, "resource_def.apiVersion");
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.spec.scaleTargetRef.apiVersion", apiVersionProperty, false);
        AbstractPropertyValue kindProperty = PropertyUtil.getPropertyValueFromPath(targetDeploymentResourceNodeProps, "resource_def.kind");
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.spec.scaleTargetRef.kind", kindProperty, false);
        AbstractPropertyValue nameProperty = PropertyUtil.getPropertyValueFromPath(targetDeploymentResourceNodeProps, "resource_def.metadata.name");
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.spec.scaleTargetRef.name", nameProperty, false);

        // min and max replicas from the policy template
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.spec.minReplicas", transformed.get("minReplicas"), false);
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.spec.maxReplicas", transformed.get("maxReplicas"), false);

        // clear metrics and add
        feedPropertyValue(podAutoScalerResourceNodeProperties, "resource_def.spec.metrics", cleanMetricsBaseOnType((List<Object>) transformed.get("metrics")),
                false);
    }

    private List<Object> cleanMetricsBaseOnType(List<Object> metrics) {
        if (metrics != null) {
            metrics.forEach(metric -> {
                String type = (String) MapUtil.get(metric, "type");
                // remove all entry that does not match the type defined.
                // see org.alien4cloud.kubernetes.api.datatypes.autoscaler.MetricSpec for details
                ((Map<String, Object>) metric).entrySet()
                        .removeIf(entry -> !"type".equals(entry.getKey()) && !StringUtils.equalsAnyIgnoreCase(entry.getKey(), type));
            });
        }
        return metrics;
    }

    private AbstractPropertyValue resolveContainerInput(Topology topology, NodeTemplate deploymentResource, NodeTemplate nodeTemplate,
            FunctionEvaluatorContext functionEvaluatorContext, Map<String,
            List<String>> serviceIpAddressesPerDeploymentResource, String inputName,
            AbstractPropertyValue iValue, FlowExecutionContext context) {
        if (iValue instanceof ConcatPropertyValue) {
            ConcatPropertyValue cpv = (ConcatPropertyValue) iValue;
            StringBuilder sb = new StringBuilder();
            for (AbstractPropertyValue param : cpv.getParameters()) {
                AbstractPropertyValue v = resolveContainerInput(topology, deploymentResource, nodeTemplate, functionEvaluatorContext,
                        serviceIpAddressesPerDeploymentResource, inputName, param, context);
                if (v instanceof ScalarPropertyValue) {
                    sb.append(PropertyUtil.getScalarValue(v));
                } else {
                    // TODO: we need a AbstractPropertyValue serializer
                    context.getLog().warn("Some element in concat operation for input <" + inputName + "> (" + serializePropertyValue(param)+ ") of container <" + nodeTemplate.getName() + "> resolved to a complex result. Let's ignore it.");
                }
            }
            return new ScalarPropertyValue(sb.toString());
        }
        if (KubeTopologyUtils.isTargetedEndpointProperty(topology, nodeTemplate, iValue)) {
            return KubeTopologyUtils.getTargetedEndpointProperty(topology, nodeTemplate, iValue);
        }
        Optional<String> dependencyIpAddress = KubeTopologyUtils.getDependencyIpAddress(topology, nodeTemplate, iValue, serviceIpAddressesPerDeploymentResource, deploymentResource.getName());
        if (dependencyIpAddress.isPresent()) {
            return new ScalarPropertyValue(dependencyIpAddress.get());
        }
        try {
            AbstractPropertyValue propertyValue =
                    FunctionEvaluator.tryResolveValue(functionEvaluatorContext, nodeTemplate, nodeTemplate.getProperties(), iValue);
            if (propertyValue != null) {
                if (propertyValue instanceof PropertyValue) {
                    return propertyValue;
                } else {
                    context.getLog().warn("Property is not PropertyValue but <" + propertyValue.getClass() + "> for input <" + inputName + "> (" + serializePropertyValue(propertyValue)+ ") of container <" + nodeTemplate.getName() + ">");
                }
            }
        } catch (IllegalArgumentException iae) {
            context.getLog().warn("Can't resolve value for input <" + inputName + "> (" + serializePropertyValue(iValue)+ ") of container <" + nodeTemplate.getName() + ">, error was : " + iae.getMessage());
        }
        return null;
    }

    private static String serializePropertyValue(AbstractPropertyValue value) {
        try {
            return ToscaPropertySerializerUtils.formatPropertyValue(0, value);
        } catch(Exception e) {
            return "Serialization not possible : " + e.getMessage();
        }
    }

    private void manageContainerDependencies(Csar csar, Topology topology, NodeTemplate containerNode, NodeTemplate controllerResource, Map<String, NodeTemplate> nodeReplacementMap,FlowExecutionContext context) {

        Set<NodeTemplate> dependencies = TopologyNavigationUtil.getSourceNodesByRelationshipType(topology, containerNode, NormativeRelationshipConstants.DEPENDS_ON);

        dependencies.forEach(sourceContainerNode -> {
            // get the hosting node
            NodeTemplate sourceRuntimeNode = TopologyNavigationUtil.getImmediateHostTemplate(topology,
                    sourceContainerNode);
            if (sourceRuntimeNode != null) {
                NodeTemplate sourceControllerNode = TopologyNavigationUtil.getImmediateHostTemplate(topology,
                        sourceRuntimeNode);
                if (sourceControllerNode != null) {
                    // find the replacer
                    NodeTemplate sourceControllerResource = nodeReplacementMap.get(sourceControllerNode.getName());
                    if (sourceControllerResource != null) {
                        RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology,
                                sourceControllerResource, controllerResource.getName(),
                                NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                        setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                                sourceContainerNode.getName() + " -> " + containerNode.getName());
                    }
                }
            }
        });

    }

    private void manageContainer(Csar csar, Topology topology, NodeTemplate containerNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, FunctionEvaluatorContext functionEvaluatorContext,
            Map<String, List<String>> serviceIpAddressesPerDeploymentResource, FlowExecutionContext context) {
        {
            // get the hosting node
            NodeTemplate controllerNode = TopologyNavigationUtil.getImmediateHostTemplate(topology, containerNode);
            // find the replacer
            NodeTemplate controllerResource = nodeReplacementMap.get(controllerNode.getName());
            Map<String, AbstractPropertyValue> controllerResourceNodeProperties = resourceNodeYamlStructures.get(controllerResource.getName());

            // if the container if of type ConfigurableDockerContainer we must create a ConfigMapFactory per config_settings entry
            // a map of input_prefix -> List<NodeTemplate> (where NodeTemplate is an instance of ConfigMapFactory)
            // we can have several configMapFactory using the same prefix
            Map<String, List<NodeTemplate>> configMapFactories = Maps.newHashMap();

            // resolve env variables
            Set<NodeTemplate> hostedContainers = TopologyNavigationUtil.getSourceNodes(topology, containerNode, "host");
            for (NodeTemplate nodeTemplate : hostedContainers) {
                // we should have a single hosted docker container
                manageContainerDependencies(csar, topology, nodeTemplate, controllerResource, nodeReplacementMap, context);

                AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(nodeTemplate.getProperties()), "docker_run_args");
                if (propertyValue != null) {
                    if (propertyValue instanceof ListPropertyValue) {
                        setNodePropertyPathValue(csar, topology, containerNode, "container.args", propertyValue);
                    } else {
                        context.getLog().warn("Ignoring args for container <" + nodeTemplate.getName() + ">, it should be a list but it is not");
                    }
                }

                NodeType containerType = ToscaContext.get(NodeType.class, nodeTemplate.getType());
                if (ToscaTypeUtils.isOfType(containerType, KubeTopologyUtils.A4C_TYPES_APPLICATION_CONFIGURABLE_DOCKER_CONTAINER)) {
                    AbstractPropertyValue config_settings = safe(nodeTemplate.getProperties()).get("config_settings");
                    if (config_settings != null && config_settings instanceof ListPropertyValue) {
                        ListPropertyValue config_settings_list = (ListPropertyValue)config_settings;
                        for (Object config_setting_obj : config_settings_list.getValue()) {
                            if (config_setting_obj instanceof Map) {
                                Map<String, String> config_setting_map = (Map<String, String>)config_setting_obj;
                                String mount_path = config_setting_map.get("mount_path");
                                String mount_subPath = config_setting_map.get("mount_subPath");
                                String input_prefix = config_setting_map.get("input_prefix");
                                String config_path = config_setting_map.get("config_path");

                                NodeTemplate configMapFactoryNode = addNodeTemplate(csar, topology, nodeTemplate.getName() + "_ConfigMap_" + input_prefix, KubeTopologyUtils.K8S_TYPES_CONFIG_MAP_FACTORY,
                                        K8S_CSAR_VERSION);
                                AbstractPropertyValue containerNameAPV = PropertyUtil.getPropertyValueFromPath(safe(containerNode.getProperties()), "container.name");
                                String containerName = ((ScalarPropertyValue)containerNameAPV).getValue();
                                String configMapName = generateUniqueKubeName(context, containerName + "_ConfigMap_" + input_prefix);
                                configMapName = configMapName.replaceAll("--", "-");
                                configMapName = configMapName.replaceAll("\\.", "-");
                                if (configMapName.endsWith("-")) {
                                    configMapName = configMapName.substring(0, configMapName.length() -1);
                                }
                                setNodePropertyPathValue(csar, topology, configMapFactoryNode, "name", new ScalarPropertyValue(configMapName));
                                DeploymentArtifact configsArtifact = configMapFactoryNode.getArtifacts().get("configs");
                                configsArtifact.setArchiveName(topology.getArchiveName());
                                configsArtifact.setArchiveVersion(topology.getArchiveVersion());
                                configsArtifact.setArtifactRepository(ArtifactRepositoryConstants.ALIEN_TOPOLOGY_REPOSITORY);
                                // the artifact ref using the value found in setting
                                configsArtifact.setArtifactRef(config_path);

                                List<NodeTemplate> configMapFactoryList = configMapFactories.get(input_prefix);
                                if (!configMapFactories.containsKey(input_prefix)) {
                                    configMapFactories.put(input_prefix, Lists.newArrayList());
                                }
                                configMapFactories.get(input_prefix).add(configMapFactoryNode);

                                // add the configMap to the deployment
                                Map<String, Object> volumeEntry = Maps.newHashMap();
                                Map<String, Object> volumeSpec = Maps.newHashMap();
                                volumeSpec.put("name", configMapName);
                                volumeEntry.put("name", configMapName);
                                volumeEntry.put("configMap", volumeSpec);
                                feedPropertyValue(controllerResourceNodeProperties, "resource_def.spec.template.spec.volumes", volumeEntry, true);

                                // add the volume to the container
                                Map<String, Object> containerVolumeEntry = Maps.newHashMap();
                                containerVolumeEntry.put("name", configMapName);
                                containerVolumeEntry.put("mountPath", mount_path);
                                if (StringUtils.isNotEmpty(mount_subPath)) {
                                    containerVolumeEntry.put("subPath", mount_subPath);
                                }
                                appendNodePropertyPathValue(csar, topology, containerNode, "container.volumeMounts", new ComplexPropertyValue(containerVolumeEntry));

                                // add all a dependsOn relationship between the configMapFactory and each dependsOn target of deploymentResource
                                Set<RelationshipTemplate> dependsOnRelationships = TopologyNavigationUtil.getTargetRelationships(controllerResource, "dependency");
                                dependsOnRelationships.forEach(dependsOnRelationship -> {
                                    addRelationshipTemplate(csar, topology, configMapFactoryNode, dependsOnRelationship.getTarget(),
                                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                                    removeRelationship(csar, topology, controllerResource.getName(), dependsOnRelationship.getName());
                                });
                                // and finally add a dependsOn between the deploymentResource and the configMapFactory
                                addRelationshipTemplate(csar, topology, controllerResource, configMapFactoryNode.getName(),
                                        NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                            }
                        }
                    }
                }

                Operation createOp = KubeTopologyUtils.getContainerImageOperation(nodeTemplate);
                if (createOp != null) {
                    safe(createOp.getInputParameters()).forEach((inputName, iValue) -> {
                        if (iValue instanceof AbstractPropertyValue) {
                            AbstractPropertyValue v =
                                    resolveContainerInput(topology, controllerResource, nodeTemplate, functionEvaluatorContext,
                                            serviceIpAddressesPerDeploymentResource, inputName, (AbstractPropertyValue) iValue, context);
                            if (v != null) {
                                if (inputName.startsWith("ENV_")) {
                                    String envKey = inputName.substring(4);
                                    ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                    envEntry.setValue(Maps.newHashMap());
                                    envEntry.getValue().put("name", envKey);
                                    envEntry.getValue().put("value", v);
                                    try {
                                        appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                        context.getLog().info("Env variable <" + envKey + "> for container <" + nodeTemplate.getName() + "> set to value <" + serializePropertyValue(v) + ">");
                                    } catch(Exception e) {
                                        context.getLog().warn("Not able to set env variable <" + envKey + "> to value <" + serializePropertyValue(v) + "> for container <" + nodeTemplate.getName() + ">, error was : " + e.getMessage());
                                    }
                                } else if (!configMapFactories.isEmpty()) {
                                    // maybe it's a config that should be associated with a configMap
                                    for (Map.Entry<String, List<NodeTemplate>> configMapFactoryEntry : configMapFactories.entrySet()) {
                                        String inputPrefix = configMapFactoryEntry.getKey();
                                        if (inputName.startsWith(inputPrefix)) {
                                            // ok this input is related to this configMapFactory
                                            String varName = inputName.substring(inputPrefix.length());
                                            if (!(v instanceof ScalarPropertyValue)) {
                                                context.getLog().warn("Ignoring INPUT named <" + inputName + "> for container <" + nodeTemplate.getName() + "> because the value is not a scalar (" + serializePropertyValue(v) + ") and cannot be added to a configMap");
                                            } else {
                                                for (NodeTemplate configMapFactory : configMapFactoryEntry.getValue()) {
                                                    try {
                                                        setNodePropertyPathValue(csar, topology, configMapFactory, "input_variables." + varName, v);
                                                        context.getLog().info("Successfully set INPUT named <" + inputName + "> with value <" + serializePropertyValue(v) + "> to configMap <" + configMapFactory.getName() + "> for container <" + nodeTemplate.getName() + ">");
                                                    } catch(Exception e) {
                                                        context.getLog().warn("Not able to set INPUT named <" + inputName + "> with value <" + serializePropertyValue(v) + "> to configMap, <" + configMapFactory.getName() + "> for container <" + nodeTemplate.getName() + ">, error was : " + e.getMessage());
                                                    }
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                            } else {
                                context.log().warn("Not able to define value for input <" + inputName + "> (" + serializePropertyValue((AbstractPropertyValue)iValue) + ") of container <" + nodeTemplate.getName() + ">");
                            }
                        } else {
                            context.log().warn("Input <" + inputName + "> of container <" + nodeTemplate.getName() + "> is ignored since it's not of type AbstractPropertyValue but " + iValue.getClass().getSimpleName());
                        }
                    });
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
                // we set the same property for each configMapFactory if any
                configMapFactories.forEach((input_prefix, configMapFactoryNodeTemplate) -> {
                    configMapFactoryNodeTemplate.iterator().forEachRemaining(nodeTemplate -> {
                        setNodePropertyPathValue(csar, topology, nodeTemplate, "service_dependency_lookups",
                                new ScalarPropertyValue(serviceDependencyDefinitionsValue.toString()));
                    });
                });
            });

            // add an entry in the deployment resource
            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(containerNode.getProperties()), "container");
            // if a repository is defined concat the repo to the image
            AbstractPropertyValue repositoryPropertyValue = PropertyUtil.getPropertyValueFromPath(safe(containerNode.getProperties()), "repository");
            if (repositoryPropertyValue instanceof ScalarPropertyValue && propertyValue instanceof ComplexPropertyValue) {
                ScalarPropertyValue imagePropValue = (ScalarPropertyValue) ((ComplexPropertyValue) propertyValue).getValue().get("image");
                String image = ((ScalarPropertyValue) repositoryPropertyValue).getValue() + "/" + imagePropValue.getValue();
                imagePropValue.setValue(image);
            }
            // transform data
            NodeType nodeType = ToscaContext.get(NodeType.class, containerNode.getType());
            PropertyDefinition propertyDefinition = nodeType.getProperties().get("container");
            Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
            feedPropertyValue(controllerResourceNodeProperties, "resource_def.spec.template.spec.containers", transformedValue, true);
        }
    }


    private void createStatefulSetResource(Csar csar, Topology topology, NodeTemplate statefulsetNode, Map<String, NodeTemplate> nodeReplacementMap,
    Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures , FlowExecutionContext context){
        NodeTemplate statefulsetResourceNode = addNodeTemplate(csar, topology, statefulsetNode.getName() + "_Resource", K8S_TYPES_STATEFULSET_RESOURCE,
        K8S_CSAR_VERSION);
        nodeReplacementMap.put(statefulsetNode.getName(), statefulsetResourceNode);
        setNodeTagValue(statefulsetResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", statefulsetNode.getName());

        Map<String, AbstractPropertyValue> statefulsetResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(statefulsetResourceNode.getName(), statefulsetResourceNodeProperties);

        copyProperty(csar, topology, statefulsetNode, "apiVersion", statefulsetResourceNodeProperties, "resource_def.apiVersion");
        copyProperty(csar, topology, statefulsetNode, "kind", statefulsetResourceNodeProperties, "resource_def.kind");
        copyProperty(csar, topology, statefulsetNode, "metadata", statefulsetResourceNodeProperties, "resource_def.metadata");
        AbstractPropertyValue volumeDeletable = PropertyUtil.getPropertyValueFromPath(safe(statefulsetNode.getProperties()), "volumeDeletable");
        if(volumeDeletable == null){
            context.log().error("Failed to get volumeDeletable property on "+ statefulsetNode.getName());
        }
        setNodePropertyPathValue(csar, topology, statefulsetResourceNode, "volumeDeletable", volumeDeletable);

        // For statefulset, generate an id and name that is consistent
        String stsName = generateConsistentKubeName(statefulsetNode.getName());
        feedPropertyValue(statefulsetResourceNodeProperties, "resource_def.metadata.name", stsName, false);
        feedPropertyValue(statefulsetNode.getProperties(), "spec.template.metadata.labels.app", stsName, false);
        log.info("Set spec.selector.matchLabels.app to "+stsName);
        feedPropertyValue(statefulsetNode.getProperties(), "spec.selector.matchLabels.app", stsName, false);
        feedPropertyValue(statefulsetNode.getProperties(), "metadata.name", stsName, false);
        AbstractPropertyValue resource_id = PropertyUtil.getPropertyValueFromPath(safe(statefulsetNode.getProperties()), "metadata.name");
        if(resource_id == null){
            context.log().error("Failed to get metadata.name property on "+ statefulsetNode.getName());
        }
        setNodePropertyPathValue(csar, topology, statefulsetResourceNode, "resource_id", resource_id);

        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(statefulsetNode.getProperties()), "spec");
        if(propertyValue == null){
            context.log().error("Failed to get spec property on "+ statefulsetNode.getName());
        }
        NodeType nodeType = ToscaContext.get(NodeType.class, statefulsetNode.getType());
        if(nodeType == null){
            context.log().error("Failed to get nodeType property on "+ statefulsetNode.getName());
        }
        PropertyDefinition propertyDefinition = nodeType.getProperties().get("spec");
        Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
        feedPropertyValue(statefulsetResourceNodeProperties, "resource_def.spec", transformedValue, false);

        Capability scalableCapability = safe(statefulsetNode.getCapabilities()).get("scalable");
        if (scalableCapability != null) {
            Capability clusterControllerCapability = new Capability(AlienCapabilityTypes.CLUSTER_CONTROLLER,
                    CloneUtil.clone(scalableCapability.getProperties()));
            NodeTemplateUtils.setCapability(statefulsetResourceNode, "scalable", clusterControllerCapability);
        }


        // find each node of type Service that targets this statefulset
        Set<NodeTemplate> sourceCandidates = TopologyNavigationUtil.getSourceNodes(topology, statefulsetNode, "feature");
        if(sourceCandidates == null){
            context.log().error("Failed to get sourceCandidates that target node "+ statefulsetNode.getName());
        }
        for (NodeTemplate sourceCandidate : sourceCandidates) {
            NodeType sourceCandidateType = ToscaContext.get(NodeType.class, sourceCandidate.getType());
            if (ToscaTypeUtils.isOfType(sourceCandidateType, K8S_TYPES_SERVICE)) {
                // find the replacer
                NodeTemplate serviceResource = nodeReplacementMap.get(sourceCandidate.getName());
                if (!TopologyNavigationUtil.hasRelationship(serviceResource, statefulsetResourceNode.getName(), "dependency", "feature")) {
                    RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, serviceResource, statefulsetResourceNode.getName(),
                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                    setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                            sourceCandidate.getName() + " -> " + statefulsetNode.getName());
                //Change selector to match the consistent name
                Map<String, AbstractPropertyValue> serviceResourceNodeProperties = resourceNodeYamlStructures.get(serviceResource.getName());
                feedPropertyValue(serviceResourceNodeProperties, "resource_def.spec.selector.app", stsName, false);
                }
            }
        }
        // find each node of type service this deployment depends on
        Set<NodeTemplate> targetCandidates = TopologyNavigationUtil.getTargetNodes(topology, statefulsetNode, "dependency");
        for (NodeTemplate targetCandidate : targetCandidates) {
            NodeType targetCandidateType = ToscaContext.get(NodeType.class, targetCandidate.getType());
            if (ToscaTypeUtils.isOfType(targetCandidateType, K8S_TYPES_SERVICE)) {
                // find the replacer
                NodeTemplate serviceResource = nodeReplacementMap.get(targetCandidate.getName());
                if (!TopologyNavigationUtil.hasRelationship(statefulsetResourceNode, serviceResource.getName(), "dependency", "feature")) {
                    RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, statefulsetResourceNode, serviceResource.getName(),
                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                    setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                    statefulsetNode.getName() + " -> " + targetCandidate.getName());
                }
            }
        }
    }


    private void createJobResource(Csar csar, Topology topology, NodeTemplate jobNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        NodeTemplate jobResourceNode = addNodeTemplate(csar, topology, jobNode.getName() + "_Resource", K8S_TYPES_JOB_RESOURCE,
                K8S_CSAR_VERSION);
        nodeReplacementMap.put(jobNode.getName(), jobResourceNode);
        setNodeTagValue(jobResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", jobNode.getName());

        Map<String, AbstractPropertyValue> jobResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(jobResourceNode.getName(), jobResourceNodeProperties);

        copyProperty(csar, topology, jobNode, "apiVersion", jobResourceNodeProperties, "resource_def.apiVersion");
        copyProperty(csar, topology, jobNode, "kind", jobResourceNodeProperties, "resource_def.kind");
        copyProperty(csar, topology, jobNode, "metadata", jobResourceNodeProperties, "resource_def.metadata");

        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(safe(jobNode.getProperties()), "spec");
        NodeType nodeType = ToscaContext.get(NodeType.class, jobNode.getType());
        PropertyDefinition propertyDefinition = nodeType.getProperties().get("spec");
        Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
        feedPropertyValue(jobResourceNodeProperties, "resource_def.spec", transformedValue, false);

    }

    private void createDeploymentResource(Csar csar, Topology topology, NodeTemplate deploymentNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        NodeTemplate deploymentResourceNode = addNodeTemplate(csar, topology, deploymentNode.getName() + "_Resource", K8S_TYPES_DEPLOYMENT_RESOURCE,
                K8S_CSAR_VERSION);
        nodeReplacementMap.put(deploymentNode.getName(), deploymentResourceNode);
        setNodeTagValue(deploymentResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", deploymentNode.getName());

        // ensure a policy that targets the deployment will now target the resource
        // not very necessary because the policy here doesn't mean nothing ...
        // changePolicyTarget(topology, deploymentNode, deploymentResourceNode);

        Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(deploymentResourceNode.getName(), deploymentResourceNodeProperties);

        copyProperty(csar, topology, deploymentNode, "apiVersion", deploymentResourceNodeProperties, "resource_def.apiVersion");
        copyProperty(csar, topology, deploymentNode, "kind", deploymentResourceNodeProperties, "resource_def.kind");
        copyProperty(csar, topology, deploymentNode, "metadata", deploymentResourceNodeProperties, "resource_def.metadata");

        AbstractPropertyValue resource_id = PropertyUtil.getPropertyValueFromPath(safe(deploymentNode.getProperties()), "metadata.name");
        setNodePropertyPathValue(csar, topology, deploymentResourceNode, "resource_id", resource_id);

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
        for (NodeTemplate sourceCandidate : sourceCandidates) {
            NodeType sourceCandidateType = ToscaContext.get(NodeType.class, sourceCandidate.getType());
            if (ToscaTypeUtils.isOfType(sourceCandidateType, K8S_TYPES_SERVICE)) {
                // find the replacer
                NodeTemplate serviceResource = nodeReplacementMap.get(sourceCandidate.getName());
                if (!TopologyNavigationUtil.hasRelationship(serviceResource, deploymentResourceNode.getName(), "dependency", "feature")) {
                    RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, serviceResource, deploymentResourceNode.getName(),
                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                    setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                            sourceCandidate.getName() + " -> " + deploymentNode.getName());
                }
            }
        }
        // find each node of type service this deployment depends on
        Set<NodeTemplate> targetCandidates = TopologyNavigationUtil.getTargetNodes(topology, deploymentNode, "dependency");
        for (NodeTemplate targetCandidate : targetCandidates) {
            NodeType targetCandidateType = ToscaContext.get(NodeType.class, targetCandidate.getType());
            if (ToscaTypeUtils.isOfType(targetCandidateType, K8S_TYPES_SERVICE)) {
                // find the replacer
                NodeTemplate serviceResource = nodeReplacementMap.get(targetCandidate.getName());
                if (!TopologyNavigationUtil.hasRelationship(deploymentResourceNode, serviceResource.getName(), "dependency", "feature")) {
                    RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, deploymentResourceNode, serviceResource.getName(),
                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                    setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from",
                            deploymentNode.getName() + " -> " + targetCandidate.getName());
                }
            }
        }
    }

    private void createServiceResource(Csar csar, Topology topology, NodeTemplate serviceNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, FlowExecutionContext context) {

        // if the matched service has a property service_name, then we replace the autogenerated name by it
        AbstractPropertyValue serviceNamePV = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "service_name");
        if (serviceNamePV != null && serviceNamePV instanceof ScalarPropertyValue) {
            String serviceName = ((ScalarPropertyValue)serviceNamePV).getValue();
            if (StringUtils.isNoneEmpty(serviceName)) {
                feedPropertyValue(safe(serviceNode.getProperties()), "metadata.name", serviceName, false);
            }
        }

        NodeTemplate serviceResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_Resource", K8S_TYPES_SERVICE_RESOURCE, K8S_CSAR_VERSION);

        // prepare attributes
        Map<String, Set<String>> topologyAttributes = topology.getOutputAttributes();
        if (topologyAttributes == null) {
            topologyAttributes = Maps.newHashMap();
            topology.setOutputAttributes(topologyAttributes);
        }
        Set<String> nodeAttributes = topology.getOutputAttributes().get(serviceResourceNode.getName());
        if (nodeAttributes == null) {
            nodeAttributes = Sets.newHashSet();
            topology.getOutputAttributes().put(serviceResourceNode.getName(), nodeAttributes);
        }
        // add an output attribute with the node_port
        nodeAttributes.add("node_port");

        // set the value for the 'cluster_endpoint' port property
        String port = getNodeTagValueOrNull(serviceNode, A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT_PORT);
        String portName = getNodeTagValueOrNull(serviceNode, A4C_KUBERNETES_MODIFIER_TAG_SERVICE_ENDPOINT_PORT_NAME);
        if (port != null) {
            setNodeCappabilityPropertyPathValue(csar, topology, serviceResourceNode, "cluster_endpoint", "port", new ScalarPropertyValue(port), false);
        }
        String exposedCapabilityName = getNodeTagValueOrNull(serviceNode, A4C_KUBERNETES_MODIFIER_TAG_EXPOSED_AS_CAPA);
        if (exposedCapabilityName != null) {
            // a container capability is exposed for substitution
            // we replace the substitutionTarget by this service's 'cluster_endpoint'
            // by this way we can expose the clusterIp:clusterPort of this service to proxy the container's endpoint
            SubstitutionTarget substitutionTarget = topology.getSubstitutionMapping().getCapabilities().get(exposedCapabilityName);
            substitutionTarget.setNodeTemplateName(serviceResourceNode.getName());
            substitutionTarget.setTargetId("cluster_endpoint");
            nodeAttributes.add("ip_address");
            // FIXME: this is a workarround to the fact that we can't :
            // - define attributes on capabilities
            // - define attributes at runtime
            // - substitute capability exposition ... and so on
            setNodeTagValue(serviceResourceNode, A4C_MODIFIER_TAG_EXPOSED_ATTRIBUTE_ALIAS, "ip_address:capabilities." + exposedCapabilityName + ".ip_address");
        }

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

        // copy all dependency relationships between services and endpoints (hybrid connection)
        Set<NodeTemplate> dependencyTargets = TopologyNavigationUtil.getTargetNodes(topology, serviceNode, "dependency");
        for (NodeTemplate dependencyTarget : dependencyTargets) {
            if (dependencyTarget.getType().equals(K8S_TYPES_ENDPOINT_RESOURCE)) {
                addRelationshipTemplate(csar, topology, serviceResourceNode, dependencyTarget.getName(), NormativeRelationshipConstants.DEPENDS_ON,
                        "dependency", "feature");
            }
        }

        if (serviceNode.getType().equals(K8S_TYPES_SERVICE_INGRESS)) {
            createIngress(context, csar, topology, serviceNode, serviceResourceNode, portName, namePropertyValue, resourceNodeYamlStructures, context);
        }
    }

    private void createIngress(FlowExecutionContext ctx, Csar csar, Topology topology, NodeTemplate serviceNode, NodeTemplate serviceResourcesNode, String portName, AbstractPropertyValue serviceName, Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, FlowExecutionContext context) {
        AbstractPropertyValue ingressHost = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "host");

        NodeTemplate ingressResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_Ingress", K8S_TYPES_SIMPLE_RESOURCE, K8S_CSAR_VERSION);

        Map<String, AbstractPropertyValue> ingressResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(ingressResourceNode.getName(), ingressResourceNodeProperties);

        String ingressName = generateUniqueKubeName(ctx, ingressResourceNode.getName());

        feedPropertyValue(ingressResourceNode.getProperties(), "resource_type", new ScalarPropertyValue("ing"), false);
        feedPropertyValue(ingressResourceNode.getProperties(), "resource_id", new ScalarPropertyValue(ingressName), false);

        /* here we are creating something like that:
        apiVersion: extensions/v1beta1
        kind: Ingress
        metadata:
          name: ingress
        spec:
          rules:
          - host: helloworld-test.artemis.public
            http:
              paths:
              - path: /
                backend:
                  serviceName: helloworld-ingress-svc
                  servicePort: 80
         */

        // fill the future JSON spec
        feedPropertyValue(ingressResourceNodeProperties, "resource_def.kind", "Ingress", false);
        feedPropertyValue(ingressResourceNodeProperties, "resource_def.apiVersion", "extensions/v1beta1", false);
        feedPropertyValue(ingressResourceNodeProperties, "resource_def.metadata.name", ingressName, false);
        feedPropertyValue(ingressResourceNodeProperties, "resource_def.metadata.labels.a4c_id", ingressName, false);

        Map<String, Object> rule = Maps.newHashMap();
        rule.put("host", ingressHost);
        Map<String, Object> http = Maps.newHashMap();
        rule.put("http", http);
        Map<String, Object> path = Maps.newHashMap();
        path.put("path", "/");
        Map<String, Object> backend = Maps.newHashMap();
        backend.put("serviceName", serviceName);
        backend.put("servicePort", portName);
        path.put("backend", backend);
        List<Object> paths = Lists.newArrayList();
        paths.add(path);
        http.put("paths", paths);
        feedPropertyValue(ingressResourceNodeProperties, "resource_def.spec.rules", rule, true);

        // add a dependency between the ingress and the service
        addRelationshipTemplate(csar, topology, ingressResourceNode, serviceResourcesNode.getName(), NormativeRelationshipConstants.DEPENDS_ON,
                "dependency", "feature");

        // if the Ingress service has both tls_crt and tls_key then we should create a secret and activate TLS on Ingress
        AbstractPropertyValue ingressCrtPV = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "tls_crt");
        String ingressCrt = null;
        if (ingressCrtPV != null && ingressCrtPV instanceof ScalarPropertyValue) {
            ingressCrt = ((ScalarPropertyValue)ingressCrtPV).getValue();
        }
        AbstractPropertyValue ingressKeyPV = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "tls_key");
        String ingressKey = null;
        if (ingressKeyPV != null && ingressKeyPV instanceof ScalarPropertyValue) {
            ingressKey = ((ScalarPropertyValue)ingressKeyPV).getValue();
        }
        if (StringUtils.isNoneEmpty(ingressCrt) && StringUtils.isNoneEmpty(ingressKey)) {
            // create the secret
            NodeTemplate secretResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_IngressSecret", K8S_TYPES_SIMPLE_RESOURCE, K8S_CSAR_VERSION);

            Map<String, AbstractPropertyValue> ingressSecretResourceNodeProperties = Maps.newHashMap();
            resourceNodeYamlStructures.put(secretResourceNode.getName(), ingressSecretResourceNodeProperties);

            String ingressSecretName = generateUniqueKubeName(ctx, secretResourceNode.getName());

            feedPropertyValue(secretResourceNode.getProperties(), "resource_type", new ScalarPropertyValue("secrets"), false);
            feedPropertyValue(secretResourceNode.getProperties(), "resource_id", new ScalarPropertyValue(ingressSecretName), false);

            /* here we are creating something like that:
                apiVersion: v1
                data:
                  tls.crt: base64 encoded cert
                  tls.key: base64 encoded key
                kind: Secret
                metadata:
                  name: testsecret
                  namespace: default
                type: Opaque
             */

            Map<String, String> data = Maps.newHashMap();
            data.put("tls.crt", ingressCrt);
            data.put("tls.key", ingressKey);

            // fill the future JSON spec
            feedPropertyValue(ingressSecretResourceNodeProperties, "resource_def.kind", "Secret", false);
            feedPropertyValue(ingressSecretResourceNodeProperties, "resource_def.apiVersion", "v1", false);
            feedPropertyValue(ingressSecretResourceNodeProperties, "resource_def.metadata.name", ingressSecretName, false);
            feedPropertyValue(ingressSecretResourceNodeProperties, "resource_def.metadata.labels.a4c_id", ingressSecretName, false);
            feedPropertyValue(ingressSecretResourceNodeProperties, "resource_def.type", "Opaque", false);
            feedPropertyValue(ingressSecretResourceNodeProperties, "resource_def.data", data, false);

            // add the TLS config to the Ingress
            Map<String, Object> tls = Maps.newHashMap();
            tls.put("secretName", ingressSecretName);
            feedPropertyValue(ingressResourceNodeProperties, "resource_def.spec.tls", tls, true);

            // add a relation between the Ingress and the secret
            addRelationshipTemplate(csar, topology, ingressResourceNode, secretResourceNode.getName(), NormativeRelationshipConstants.DEPENDS_ON,
                    "dependency", "feature");
        } else if (StringUtils.isNoneEmpty(ingressCrt) || StringUtils.isNoneEmpty(ingressKey)) {
            context.log().warn("tls_crt or tls_key is provided for service  <" + serviceNode + "> but both are needed in order to create a secured Ingress. A non secured Ingress is created !");
        }

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

}
