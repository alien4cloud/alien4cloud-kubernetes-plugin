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

/**
 * Transform a matched K8S topology containing <code>Container</code>s, <code>Deployment</code>s, <code>Service</code>s and replace them with <code>DeploymentResource</code>s and <code>ServiceResource</code>s.
 */
@Log
@Component(value = "kubernetes-final-modifier")
public class KubernetesFinalTopologyModifier extends AbstractKubernetesTopologyModifier {

    private static final String A4C_KUBERNETES_MODIFIER_TAG = "a4c_kubernetes-final-modifier";

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        // just a map that store the node name as key and the replacement node as value
        Map<String, NodeTemplate> nodeReplacementMap = Maps.newHashMap();

        // for each Service create a node of type ServiceResource
        Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures = Maps.newHashMap();
        Set<NodeTemplate> serviceNodes = getNodesOfType(topology, K8S_TYPES_SERVICE, false);
        for (NodeTemplate serviceNode : serviceNodes) {
            NodeTemplate serviceResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_Resource", K8S_TYPES_SERVICE_RESOURCE, K8S_CSAR_VERSION);
            nodeReplacementMap.put(serviceNode.getName(), serviceResourceNode);
            setNodeTagValue(serviceResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", serviceNode.getName());
            Map<String, AbstractPropertyValue> serviceResourceNodeProperties = Maps.newHashMap();
            resourceNodeYamlStructures.put(serviceResourceNode.getName(), serviceResourceNodeProperties);

            copyProperty(csar, topology, serviceNode, "apiVersion", serviceResourceNodeProperties, "resource_def.apiVersion");
            copyProperty(csar, topology, serviceNode, "kind", serviceResourceNodeProperties, "resource_def.kind");
            copyProperty(csar, topology, serviceNode, "metadata", serviceResourceNodeProperties, "resource_def.metadata");

            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(serviceNode.getProperties()), "spec");
            // rename entry service_type to type
            renameProperty(propertyValue, "service_type", "type");
            feedPropertyValue(serviceResourceNodeProperties, "resource_def.spec", propertyValue, false);
//            copyProperty(csar, topology, serviceNode, "spec", serviceResourceNodeProperties, "resource_def.spec");
        }

        // for each Deployment create a node of type DeploymentResource
        Set<NodeTemplate> deploymentNodes = getNodesOfType(topology, K8S_TYPES_DEPLOYMENT, false);
        for (NodeTemplate deploymentNode : deploymentNodes) {
            NodeTemplate deploymentResourceNode = addNodeTemplate(csar, topology, deploymentNode.getName() + "_Resource", K8S_TYPES_DEPLOYMENT_RESOURCE, K8S_CSAR_VERSION);
            nodeReplacementMap.put(deploymentNode.getName(), deploymentResourceNode);
            setNodeTagValue(deploymentResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", deploymentNode.getName());
            Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = Maps.newHashMap();
            resourceNodeYamlStructures.put(deploymentResourceNode.getName(), deploymentResourceNodeProperties);

            copyProperty(csar, topology, deploymentNode, "apiVersion", deploymentResourceNodeProperties, "resource_def.apiVersion");
            copyProperty(csar, topology, deploymentNode, "kind", deploymentResourceNodeProperties, "resource_def.kind");
            copyProperty(csar, topology, deploymentNode, "metadata", deploymentResourceNodeProperties, "resource_def.metadata");
            copyProperty(csar, topology, deploymentNode, "spec", deploymentResourceNodeProperties, "resource_def.spec");

            // find each node of type Service that targets this deployment
            Set<NodeTemplate> sourceCandidates = getSourceNodes(topology, deploymentNode, "feature");
            for (NodeTemplate nodeTemplate : sourceCandidates) {
                // TODO: manage inheritance ?
                if (nodeTemplate.getType().equals(K8S_TYPES_SERVICE)) {
                    // find the replacer
                    NodeTemplate serviceResource = nodeReplacementMap.get(nodeTemplate.getName());
                    if (!hasRelationship(serviceResource, deploymentResourceNode.getName(), "dependency", "feature")) {
                        RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, serviceResource, deploymentResourceNode.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                        setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", nodeTemplate.getName() + " -> " + deploymentNode.getName());
                    }
                }
            }
            // find each node of type service this deployment depends on
            Set<NodeTemplate> targetCandidates = getTargetNodes(topology, deploymentNode, "dependency");
            for (NodeTemplate nodeTemplate : sourceCandidates) {
                // TODO: manage inheritance ?
                if (nodeTemplate.getType().equals(K8S_TYPES_SERVICE)) {
                    // find the replacer
                    NodeTemplate serviceResource = nodeReplacementMap.get(nodeTemplate.getName());
                    if (!hasRelationship(deploymentResourceNode, serviceResource.getName(), "dependency", "feature")) {
                        RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, deploymentResourceNode, serviceResource.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                        setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", deploymentNode.getName() + " -> " + nodeTemplate.getName());
                    }
                }
            }
        }

        // for each container, add an entry in the deployment resource
        Set<NodeTemplate> containerNodes = getNodesOfType(topology, K8S_TYPES_CONTAINER, false);
        for (NodeTemplate containerNode : containerNodes) {
            // get the hosting node
            NodeTemplate deploymentNode = getHostNode(topology, containerNode);
            // find the replacer
            NodeTemplate deploymentResource = nodeReplacementMap.get(deploymentNode.getName());
            Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = resourceNodeYamlStructures.get(deploymentResource.getName());

            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(containerNode.getProperties()), "container");
            transformMapToList(propertyValue, "ports");
            feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec.template.spec.containers", propertyValue, true);
//            appendProperty(csar, topology, containerNode, "container", deploymentResourceNodeProperties, "resource_def.spec.template.spec.containers");

            // TODO: in the interface, create operation, search for ENV_, resolve all get_property ...
            Set<NodeTemplate> hostedContainers = getSourceNodes(topology, containerNode, "host");
            for (NodeTemplate dockerContainerNode : hostedContainers) {
                // we should have a single hosted docker container
                NodeType nodeType = ToscaContext.get(NodeType.class, dockerContainerNode.getType());
                nodeType.getInterfaces();
            }

        }

        // TODO: remove old useless nodes
        // TODO: we'll need to rename some entries in the maps

        Set<NodeTemplate> resourceNodes = getNodesOfType(topology, K8S_TYPES_RESOURCE, true);
        for (NodeTemplate resourceNode : resourceNodes) {
            Map<String, AbstractPropertyValue> resourceNodeProperties = resourceNodeYamlStructures.get(resourceNode.getName());
            if (resourceNodeProperties != null && resourceNodeProperties.containsKey("resource_def")) {
                Object propertyValue = getValue(resourceNodeProperties.get("resource_def"));
                String serializedPropertyValue = PropertyUtil.serializePropertyValue(propertyValue);
                setNodePropertyPathValue(csar, topology, resourceNode, "resource_yaml", new ScalarPropertyValue(serializedPropertyValue));
            }
        }

    }

    private void renameProperty(AbstractPropertyValue propertyValue, String propertyPath, String newName) {
        PropertyUtil.NestedPropertyWrapper nestedPropertyWrapper = PropertyUtil.getNestedProperty(propertyValue, propertyPath);
        if (nestedPropertyWrapper != null) {
            Object value = nestedPropertyWrapper.parent.remove(nestedPropertyWrapper.key);
            // value can't be null if nestedPropertyWrapper isn't null
            nestedPropertyWrapper.parent.put(newName, value);
        }
    }

    private void transformMapToList(AbstractPropertyValue propertyPath, String entryName) {
        PropertyUtil.NestedPropertyWrapper nestedPropertyWrapper = PropertyUtil.getNestedProperty(propertyPath, entryName);
        if (nestedPropertyWrapper != null) {
            Object value = nestedPropertyWrapper.parent.get(nestedPropertyWrapper.key);
            // value can't be null if nestedPropertyWrapper isn't null
            Map<String, Object> valueAsMap = PropertyUtil.getMapProperty(value);
            if (valueAsMap != null) {
                List<Object> list = Lists.newArrayList();
                for (Map.Entry<String, Object> entryMapEntry : valueAsMap.entrySet()) {
                    // for each map entry, create a map and add it in the list
                    Map<String, Object> listEntry = Maps.newHashMap();
                    listEntry.put(entryMapEntry.getKey(), entryMapEntry.getValue());
                    list.add(listEntry);
                }
                nestedPropertyWrapper.parent.put(entryName, list);
            }
        }
    }

    private void copyProperty(Csar csar, Topology topology, NodeTemplate sourceTemplate, String sourcePath, Map<String, AbstractPropertyValue> propertyValues, String targetPath) {
        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(sourceTemplate.getProperties()), sourcePath);
        feedPropertyValue(propertyValues, targetPath, propertyValue, false);
    }

    private void appendProperty(Csar csar, Topology topology, NodeTemplate sourceTemplate, String sourcePath, Map<String, AbstractPropertyValue> propertyValues, String targetPath) {
        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(sourceTemplate.getProperties()), sourcePath);
        feedPropertyValue(propertyValues, targetPath, propertyValue, true);
    }

    /**
     * Transform the object by replacing eventual PropertyValue found by it's value.
     */
    private Object getValue(Object value) {
        Object valueObject = value;
        if (value instanceof PropertyValue) {
            valueObject = getValue(((PropertyValue)value).getValue());
        } else if (value instanceof Map<?, ?>) {
            Map<String, Object> newMap = Maps.newHashMap();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>)valueObject).entrySet()) {
                newMap.put(entry.getKey(), getValue(entry.getValue()));
            }
            valueObject = newMap;
        } else if (value instanceof List<?>) {
            List<Object> newList = Lists.newArrayList();
            for (Object entry : (List<Object>)valueObject) {
                newList.add(getValue(entry));
            }
            valueObject = newList;
        }
        return valueObject;
    }

}
