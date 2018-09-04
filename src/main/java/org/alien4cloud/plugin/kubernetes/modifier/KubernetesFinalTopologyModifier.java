package org.alien4cloud.plugin.kubernetes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.CloneUtil;
import alien4cloud.utils.MapUtil;
import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.java.Log;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.plugin.kubernetes.AbstractKubernetesModifier;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.*;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.model.types.PolicyType;
import org.alien4cloud.tosca.normative.constants.AlienCapabilityTypes;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.utils.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import static alien4cloud.utils.AlienUtils.safe;
import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.A4C_TYPES_APPLICATION_DOCKER_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_CONTAINER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_ENDPOINT_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE_INGRESS;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SIMPLE_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_VOLUME_BASE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.generateUniqueKubeName;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.getValue;
import static org.alien4cloud.plugin.kubernetes.policies.KubePoliciesConstants.K8S_POLICIES_AUTO_SCALING;

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
        serviceNodes.forEach(nodeTemplate -> createServiceResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // for each Deployment create a node of type DeploymentResource
        Set<NodeTemplate> deploymentNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_DEPLOYMENT, false);
        deploymentNodes.forEach(nodeTemplate -> createDeploymentResource(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

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
        volumeNodes.forEach(nodeTemplate -> manageVolume(csar, topology, nodeTemplate, nodeReplacementMap, resourceNodeYamlStructures));

        // check auto-scaling policies and build the equiv node
        Set<PolicyTemplate> policies = TopologyNavigationUtil.getPoliciesOfType(topology, K8S_POLICIES_AUTO_SCALING, true);
        policies.forEach(policyTemplate -> manageAutoScaling(csar, topology, policyTemplate, nodeReplacementMap, resourceNodeYamlStructures, context));

        // remove useless nodes
        // TODO bug on node matching view since these nodes are the real matched ones
        // TODO then find a way to delete servicesNodes and deloymentNodes as they are not used
        serviceNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        deploymentNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        Set<NodeTemplate> volumes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_VOLUME_BASE, true);
        volumes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        Set<NodeTemplate> containers = TopologyNavigationUtil.getNodesOfType(topology, A4C_TYPES_APPLICATION_DOCKER_CONTAINER, true, false);
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

    private void managePersistentVolumeClaim(Csar csar, Topology topology, NodeTemplate volumeNode,
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

        Set<NodeTemplate> validTargets = getValidTargets(policyTemplate, topology, invalidName -> context.log()
                .warn("Auto-scaling policy <{}>: will ignore target <{}> as it IS NOT an instance of <{}>.", policyTemplate.getName(), invalidName,
                        K8S_TYPES_DEPLOYMENT));

        // for each target, add a SimpleResource for HorizontaPodAutoScaler, targeting the related DeploymentResource
        validTargets.forEach(nodeTemplate -> addHorizontalPodAutoScalingResource(csar, topology, policyTemplate, nodeTemplate, nodeReplacementMap,
                resourceNodeYamlStructures));

    }

    private void addHorizontalPodAutoScalingResource(Csar csar, Topology topology, PolicyTemplate policyTemplate, NodeTemplate target,
            Map<String, NodeTemplate> nodeReplacementMap, Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        String resourceBaseName = target.getName() + "_" + policyTemplate.getName();
        NodeTemplate podAutoScalerResourceNode = addNodeTemplate(csar, topology, resourceBaseName + "_Resource", K8S_TYPES_SIMPLE_RESOURCE, K8S_CSAR_VERSION);

        Map<String, AbstractPropertyValue> podAutoScalerResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(podAutoScalerResourceNode.getName(), podAutoScalerResourceNodeProperties);

        NodeTemplate targetDeploymentResourceNode = nodeReplacementMap.get(target.getName());
        Map<String, AbstractPropertyValue> targetDeploymentResourceNodeProps = resourceNodeYamlStructures.get(targetDeploymentResourceNode.getName());
        String podAutoScalerName = generateUniqueKubeName(resourceBaseName);

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
            List<String>> serviceIpAddressesPerDeploymentResource,
            AbstractPropertyValue iValue) {
        if (iValue instanceof ConcatPropertyValue) {
            ConcatPropertyValue cpv = (ConcatPropertyValue) iValue;
            StringBuilder sb = new StringBuilder();
            for (AbstractPropertyValue param : cpv.getParameters()) {
                AbstractPropertyValue v = resolveContainerInput(topology, deploymentResource, nodeTemplate, functionEvaluatorContext,
                        serviceIpAddressesPerDeploymentResource, param);
                if (v instanceof ScalarPropertyValue) {
                    sb.append(PropertyUtil.getScalarValue(v));
                } else {
                    log.severe("in concat operation '" + iValue.toString() + "' parameter '" + param.toString() +
                            "' resolved to a complex result. Let's ignore it.");
                }
            }
            return new ScalarPropertyValue(sb.toString());
        } else if (KubeTopologyUtils.isTargetedEndpointProperty(topology, nodeTemplate, iValue)) {
            return KubeTopologyUtils.getTargetedEndpointProperty(topology, nodeTemplate, iValue);
        } else if (KubeTopologyUtils.isServiceIpAddress(topology, nodeTemplate, iValue)) {
            NodeTemplate serviceTemplate = KubeTopologyUtils.getServiceDependency(topology, nodeTemplate, iValue);
            if (serviceTemplate != null) {
                AbstractPropertyValue serviceNameValue = PropertyUtil
                        .getPropertyValueFromPath(serviceTemplate.getProperties(), "metadata.name");
                String serviceName = PropertyUtil.getScalarValue(serviceNameValue);

                List<String> serviceIpAddresses = serviceIpAddressesPerDeploymentResource.get(deploymentResource.getName());
                if (serviceIpAddresses == null) {
                    serviceIpAddresses = Lists.newArrayList();
                    serviceIpAddressesPerDeploymentResource.put(deploymentResource.getName(), serviceIpAddresses);
                }
                serviceIpAddresses.add(serviceName);

                return new ScalarPropertyValue("${SERVICE_IP_LOOKUP" + (serviceIpAddresses.size() - 1) + "}");
            }
        } else {
            try {
                AbstractPropertyValue propertyValue =
                        FunctionEvaluator.tryResolveValue(functionEvaluatorContext, nodeTemplate, nodeTemplate.getProperties(), iValue);
                if (propertyValue != null) {
                    if (propertyValue instanceof PropertyValue) {
                        return propertyValue;
                    } else {
                        log.severe("Property is not PropertyValue but " + propertyValue.getClass());
                    }
                }
            } catch (IllegalArgumentException iae) {
                log.severe(iae.getMessage());
            }
        }
        return null;
    }

    private void manageContainer(Csar csar, Topology topology, NodeTemplate containerNode, Map<String, NodeTemplate> nodeReplacementMap,
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures, FunctionEvaluatorContext functionEvaluatorContext,
            Map<String, List<String>> serviceIpAddressesPerDeploymentResource, FlowExecutionContext context) {
        {
            // get the hosting node
            NodeTemplate deploymentNode = TopologyNavigationUtil.getImmediateHostTemplate(topology, containerNode);
            // find the replacer
            NodeTemplate deploymentResource = nodeReplacementMap.get(deploymentNode.getName());
            Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = resourceNodeYamlStructures.get(deploymentResource.getName());

            // if the container if of type ConfigurableDockerContainer we must create a ConfigMapFactory per config_settings entry
            // a map of input_prefix -> NodeTemplate (where NodeTemplate is an instance of ConfigMapFactory)
            Map<String, NodeTemplate> configMapFactories = Maps.newHashMap();

            // resolve env variables
            Set<NodeTemplate> hostedContainers = TopologyNavigationUtil.getSourceNodes(topology, containerNode, "host");
            for (NodeTemplate nodeTemplate : hostedContainers) {
                // we should have a single hosted docker container

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
                                String input_prefix = config_setting_map.get("input_prefix");
                                String config_path = config_setting_map.get("config_path");

                                NodeTemplate configMapFactoryNode = addNodeTemplate(csar, topology, nodeTemplate.getName() + "_ConfigMap_" + input_prefix, KubeTopologyUtils.K8S_TYPES_CONFIG_MAP_FACTORY,
                                        K8S_CSAR_VERSION);
                                AbstractPropertyValue containerNameAPV = PropertyUtil.getPropertyValueFromPath(safe(containerNode.getProperties()), "container.name");
                                String containerName = ((ScalarPropertyValue)containerNameAPV).getValue();
                                String configMapName = KubeTopologyUtils.generateKubeName(containerName + "_ConfigMap_" + input_prefix);
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
                                configMapFactories.put(input_prefix, configMapFactoryNode);

                                // add the configMap to the deployment
                                Map<String, Object> volumeEntry = Maps.newHashMap();
                                Map<String, Object> volumeSpec = Maps.newHashMap();
                                volumeSpec.put("name", configMapName);
                                volumeEntry.put("name", configMapName);
                                volumeEntry.put("configMap", volumeSpec);
                                feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec.template.spec.volumes", volumeEntry, true);

                                // add the volume to the container
                                Map<String, Object> containerVolumeEntry = Maps.newHashMap();
                                containerVolumeEntry.put("name", configMapName);
                                containerVolumeEntry.put("mountPath", mount_path);
                                appendNodePropertyPathValue(csar, topology, containerNode, "container.volumeMounts", new ComplexPropertyValue(containerVolumeEntry));

                                // add all a dependsOn relationship between the configMapFactory and each dependsOn target of deploymentResource
                                Set<RelationshipTemplate> dependsOnRelationships = TopologyNavigationUtil.getTargetRelationships(deploymentResource, "dependency");
                                dependsOnRelationships.forEach(dependsOnRelationship -> {
                                    addRelationshipTemplate(csar, topology, configMapFactoryNode, dependsOnRelationship.getTarget(),
                                            NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                                    removeRelationship(csar, topology, deploymentResource.getName(), dependsOnRelationship.getName());
                                });
                                // and finally add a dependsOn between the deploymentResource and the configMapFactory
                                addRelationshipTemplate(csar, topology, deploymentResource, configMapFactoryNode.getName(),
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
                                    resolveContainerInput(topology, deploymentResource, nodeTemplate, functionEvaluatorContext,
                                            serviceIpAddressesPerDeploymentResource, (AbstractPropertyValue) iValue);
                            if (v != null) {
                                if (inputName.startsWith("ENV_")) {
                                    String envKey = inputName.substring(4);
                                    ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                    envEntry.setValue(Maps.newHashMap());
                                    envEntry.getValue().put("name", envKey);
                                    envEntry.getValue().put("value", v);
                                    appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                } else if (!configMapFactories.isEmpty()) {
                                    // maybe it's a config that should be associated with a configMap
                                    for (Map.Entry<String, NodeTemplate> configMapFactoryEntry : configMapFactories.entrySet()) {
                                        String inputPrefix = configMapFactoryEntry.getKey();
                                        if (inputName.startsWith(inputPrefix)) {
                                            // ok this input is related to this configMapFactory
                                            String varName = inputName.substring(inputPrefix.length());
                                            if (!(v instanceof ScalarPropertyValue)) {
                                                context.getLog().warn("Ignoring INPUT named <" + inputName + "> because the value is not a scalar: " + v.getClass().getSimpleName());
                                                return;
                                            }

                                            setNodePropertyPathValue(csar, topology, configMapFactoryEntry.getValue(), "input_variables." + varName, v);
                                            break;
                                        }
                                    }
                                }
                            } else {
                                context.log().warn("Not able to define value for input : " + inputName);
                            }
                        }
                        // here we should manage container of type tosca.nodes.Container.Application.ConfigurableDockerContainer
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
                    setNodePropertyPathValue(csar, topology, configMapFactoryNodeTemplate, "service_dependency_lookups",
                            new ScalarPropertyValue(serviceDependencyDefinitionsValue.toString()));
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
            Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
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
            createIngress(csar, topology, serviceNode, serviceResourceNode, portName, namePropertyValue, resourceNodeYamlStructures);
        }
    }

    private void createIngress(Csar csar, Topology topology, NodeTemplate serviceNode, NodeTemplate serviceResourcesNode, String portName, AbstractPropertyValue serviceName, Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures) {
        AbstractPropertyValue ingressHost = PropertyUtil.getPropertyValueFromPath(safe(serviceNode.getProperties()), "host");

        NodeTemplate ingressResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_Ingress", K8S_TYPES_SIMPLE_RESOURCE, K8S_CSAR_VERSION);

        Map<String, AbstractPropertyValue> ingressResourceNodeProperties = Maps.newHashMap();
        resourceNodeYamlStructures.put(ingressResourceNode.getName(), ingressResourceNodeProperties);

        String ingressName = generateUniqueKubeName(ingressResourceNode.getName());

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
