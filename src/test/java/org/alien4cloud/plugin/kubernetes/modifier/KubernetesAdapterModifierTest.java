package org.alien4cloud.plugin.kubernetes.modifier;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ConcatPropertyValue;
import org.alien4cloud.tosca.model.definitions.FunctionPropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.normative.constants.ToscaFunctionConstants;
import org.alien4cloud.tosca.utils.FunctionEvaluatorContext;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;

public class KubernetesAdapterModifierTest {

    @Test
    public void testGetSourceProperty() {
        Topology topology = new Topology();
        NodeTemplate source = new NodeTemplate();
        source.setName("source");
        NodeTemplate target = new NodeTemplate();
        target.setName("target");
        topology.setNodeTemplates(Maps.newHashMap());
        topology.getNodeTemplates().put("source", source);
        topology.getNodeTemplates().put("target", target);
        Map<String, AbstractPropertyValue> sourceProperties = Maps.newHashMap();
        sourceProperties.put("prop_source", new ScalarPropertyValue("myValue"));
        source.setProperties(sourceProperties);
        RelationshipTemplate relationshipTemplate = new RelationshipTemplate();
        relationshipTemplate.setTarget("target");
        relationshipTemplate.setTargetedCapabilityName("capa");
        source.setRelationships(Maps.newHashMap());
        source.getRelationships().put("rel", relationshipTemplate);

        FunctionPropertyValue fpv = new FunctionPropertyValue();
        fpv.setFunction(ToscaFunctionConstants.GET_PROPERTY);
        fpv.setParameters(Lists.newArrayList());
        fpv.getParameters().add(ToscaFunctionConstants.SOURCE);
        fpv.getParameters().add("capa");
        fpv.getParameters().add("prop_source");

        FunctionEvaluatorContext functionEvaluatorContext = new FunctionEvaluatorContext(topology, Maps.newHashMap());
        Optional<AbstractPropertyValue> v =  KubernetesAdapterModifier.resolveSourceFunction(topology, target, functionEvaluatorContext, fpv, null);

        Assert.assertTrue(v.isPresent());
        Assert.assertEquals(new ScalarPropertyValue("myValue"), v.get());

    }

    @Test
    public void testGetSourceCapabilityProperty() {
        Topology topology = new Topology();
        NodeTemplate source = new NodeTemplate();
        source.setName("source");
        source.setCapabilities(Maps.newHashMap());
        Capability capability = new Capability();
        Map<String, AbstractPropertyValue> sourceProperties = Maps.newHashMap();
        sourceProperties.put("prop_capa_source", new ScalarPropertyValue("myCapaValue"));
        capability.setProperties(sourceProperties);
        source.getCapabilities().put("source_capa", capability);

        NodeTemplate target = new NodeTemplate();
        target.setName("target");
        topology.setNodeTemplates(Maps.newHashMap());
        topology.getNodeTemplates().put("source", source);
        topology.getNodeTemplates().put("target", target);

        RelationshipTemplate relationshipTemplate = new RelationshipTemplate();
        relationshipTemplate.setTarget("target");
        relationshipTemplate.setTargetedCapabilityName("capa");
        source.setRelationships(Maps.newHashMap());
        source.getRelationships().put("rel", relationshipTemplate);

        FunctionPropertyValue fpv = new FunctionPropertyValue();
        fpv.setFunction(ToscaFunctionConstants.GET_PROPERTY);
        fpv.setParameters(Lists.newArrayList());
        fpv.getParameters().add(ToscaFunctionConstants.SOURCE);
        fpv.getParameters().add("capa");
        fpv.getParameters().add("source_capa");
        fpv.getParameters().add("prop_capa_source");

        FunctionEvaluatorContext functionEvaluatorContext = new FunctionEvaluatorContext(topology, Maps.newHashMap());
        Optional<AbstractPropertyValue> v =  KubernetesAdapterModifier.resolveSourceFunction(topology, target, functionEvaluatorContext, fpv, null);

        Assert.assertTrue(v.isPresent());
        Assert.assertEquals(new ScalarPropertyValue("myCapaValue"), v.get());

    }


    @Test
    public void testConcatGetSourceProperty() {
        Topology topology = new Topology();
        NodeTemplate source = new NodeTemplate();
        source.setName("source");
        source.setCapabilities(Maps.newHashMap());
        Capability capability = new Capability();
        Map<String, AbstractPropertyValue> capaSourceProperties = Maps.newHashMap();
        capaSourceProperties.put("prop_capa_source", new ScalarPropertyValue("myCapaValue"));
        capability.setProperties(capaSourceProperties);
        source.getCapabilities().put("source_capa", capability);

        Map<String, AbstractPropertyValue> sourceProperties = Maps.newHashMap();
        sourceProperties.put("prop_source", new ScalarPropertyValue("mySrcValue"));
        source.setProperties(sourceProperties);

        NodeTemplate target = new NodeTemplate();
        target.setName("target");
        topology.setNodeTemplates(Maps.newHashMap());
        topology.getNodeTemplates().put("source", source);
        topology.getNodeTemplates().put("target", target);

        RelationshipTemplate relationshipTemplate = new RelationshipTemplate();
        relationshipTemplate.setTarget("target");
        relationshipTemplate.setTargetedCapabilityName("capa");
        source.setRelationships(Maps.newHashMap());
        source.getRelationships().put("rel", relationshipTemplate);

        ConcatPropertyValue concatPropertyValue = new ConcatPropertyValue();
        concatPropertyValue.setParameters(Lists.newArrayList());

        FunctionPropertyValue fpv = new FunctionPropertyValue();
        fpv.setFunction(ToscaFunctionConstants.GET_PROPERTY);
        fpv.setParameters(Lists.newArrayList());
        fpv.getParameters().add(ToscaFunctionConstants.SOURCE);
        fpv.getParameters().add("capa");
        fpv.getParameters().add("prop_source");
        concatPropertyValue.getParameters().add(fpv);

        concatPropertyValue.getParameters().add(new ScalarPropertyValue("-"));

        fpv = new FunctionPropertyValue();
        fpv.setFunction(ToscaFunctionConstants.GET_PROPERTY);
        fpv.setParameters(Lists.newArrayList());
        fpv.getParameters().add(ToscaFunctionConstants.SOURCE);
        fpv.getParameters().add("capa");
        fpv.getParameters().add("source_capa");
        fpv.getParameters().add("prop_capa_source");
        concatPropertyValue.getParameters().add(fpv);

        FunctionEvaluatorContext functionEvaluatorContext = new FunctionEvaluatorContext(topology, Maps.newHashMap());

        AbstractPropertyValue v = KubernetesAdapterModifier.resolveContainerInput(topology, target, target, functionEvaluatorContext, Maps.newHashMap(), "input", concatPropertyValue, null);

        Assert.assertEquals(new ScalarPropertyValue("mySrcValue-myCapaValue"), v);

    }
}
