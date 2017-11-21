Feature: Kubernetes anti affinity location topology modifier
  # test the kubernetes-anti-affinity-modifier

  Background:
    Given I am authenticated with "ADMIN" role
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/tosca-normative-types.git" usr "" pwd "" stored "false" and locations
      | branchId  | subPath |
      | 2.0.0-SM3 |         |
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/alien4cloud-extended-types.git" usr "" pwd "" stored "false" and locations
      | branchId    | subPath          |
      | tests/2.0.0 | alien-base-types |
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/docker-tosca-types.git" usr "" pwd "" stored "false" and locations
      | branchId        | subPath      |
      | tests/2.0.0-alt | docker-types |
#      | 2.0.0-SM3 | docker-draft-2.0.0/sandbox/samples |
    Given I upload unzipped CSAR from path "src/test/resources/csar/docker-samples-types.yml"
    Given I upload unzipped CSAR from path "src/main/resources/csar"

  Scenario: Apply policy modifier on a simple topology containing 2 apache
    Given I upload unzipped CSAR from path "src/test/resources/data/02-two-apache/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the policy named "Placement" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.AntiAffinityLabel"
    And I set the policy "Placement" property "level" to "host"
    And I execute the modifier "kubernetes-anti-affinity-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 8
    And register the SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['metadata']['labels']['placement'].value" result as "Apache1Deployment_placement_label"
    And register the SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['metadata']['labels']['placement'].value" result as "Apache2Deployment_placement_label"
    # check that the Apache1Deployment has the anti affinity to the other
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['topologyKey']" should return "kubernetes.io/hostname"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['key']" should return "placement"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['operator']" should return "In"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['values'].get(0)" result should equals the registered object "Apache2Deployment_placement_label"
    # check that the Apache2ContainerDeployment has the anti affinity to the other
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['topologyKey']" should return "kubernetes.io/hostname"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['key']" should return "placement"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['operator']" should return "In"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['values'].get(0)" result should equals the registered object "Apache1Deployment_placement_label"


