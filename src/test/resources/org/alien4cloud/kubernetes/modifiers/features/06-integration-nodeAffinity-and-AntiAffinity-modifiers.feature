Feature: Kubernetes label placement policy topology modifier
  # test integration of the kubernetes-node-affinity-modifier and the kubernetes-anti-affinity-modifier togetehr on the same node

  Background:
    Given I am authenticated with "ADMIN" role

    Given I add and import a GIT repository with url "https://github.com/alien4cloud/tosca-normative-types.git" usr "" pwd "" stored "false" and locations
      | branchId  | subPath |
      | tests/2.0.0 |         |
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/alien4cloud-extended-types.git" usr "" pwd "" stored "false" and locations
      | branchId    | subPath          |
      | tests/2.0.0 | alien-base-types |
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/docker-tosca-types.git" usr "" pwd "" stored "false" and locations
      | branchId        | subPath      |
      | tests/2.0.0-alt | docker-types |

    Given I upload unzipped CSAR from path "src/test/resources/csar/docker-samples-types.yml"
    Given I upload unzipped CSAR from path "src/main/resources/csar"

  Scenario: Apply node affinity placement policy modifier on a simple topology containing 2 apache
    Given I upload unzipped CSAR from path "src/test/resources/data/07-two-apache-placement-and-antiaffinity-policies/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the policy named "Placement1" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.NodeAffinityLabel"
    And I match the policy named "Placement2" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.NodeAffinityLabel"
    And I match the policy named "AntiAffinity" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.AntiAffinityLabel"
    And I execute the modifier "kubernetes-anti-affinity-modifier" on the current topology
    And I execute the modifier "kubernetes-node-affinity-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 8

    ####check that antiAffinity part is OK
    And register the SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['metadata']['labels']['antiaffinity'].value" result as "Apache1Deployment_placement_label"
    And register the SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['metadata']['labels']['antiaffinity'].value" result as "Apache2Deployment_placement_label"
    # check that the Apache1Deployment has the anti affinity to the other
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['topologyKey']" should return "kubernetes.io/hostname"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['key']" should return "antiaffinity"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['operator']" should return "In"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['values'].get(0)" result should equals the registered object "Apache2Deployment_placement_label"
    # check that the Apache2ContainerDeployment has the anti affinity to the other
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['topologyKey']" should return "kubernetes.io/hostname"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['key']" should return "antiaffinity"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['operator']" should return "In"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['podAffinityTerm']['labelSelector']['matchExpressions'].get(0)['values'].get(0)" result should equals the registered object "Apache1Deployment_placement_label"

    ####check that nodeAffinity part is OK
    # check that the Apache1Deployment has the node affinity with the proper label
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['key']" should return "flavor"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['operator']" should return "In"
    And The SPEL expression "nodeTemplates['Apache1Deployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['values'].get(0)" should return "large"
    # check that the Apache2ContainerDeployment has the node affinity with the proper label
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['key']" should return "flavor"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['operator']" should return "In"
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['values'].size()" should return 1
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].properties['spec'].value['template']['spec']['affinity']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'].value.get(0).value['preference']['matchExpressions'].get(0)['values'].?[#this == 'small'].size()" should return 1
