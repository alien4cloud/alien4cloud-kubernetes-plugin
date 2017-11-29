Feature: Kubernetes label placement policy topology modifier
  # test integration of the kubernetes-node-affinity-modifier and the kubernetes-anti-affinity-modifier togetehr on the same node

  Background:
    Given I am authenticated with "ADMIN" role

    Given I add and import a GIT repository with url "https://github.com/alien4cloud/tosca-normative-types.git" usr "" pwd "" stored "false" and locations
      | branchId    | subPath |
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
    Given I upload unzipped CSAR from path "src/test/resources/data/08-one-apache-auto-scaling/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the policy named "Scaling" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.AutoscalingPolicy"
    Given I set the policy "Scaling" "complex"'s property "spec" to "{"minReplicas": "1", "maxReplicas": "10", "metrics": [{"type": "Resource", "resource": {"name":"cpu", "targetAverageUtilization":"50"}}]}"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 3

    Then register the SPEL expression "nodeTemplates['Apache1Deployment_Resource'].properties['resource_spec'].value" result as a map "Apache1Deployment_Resource_spec"
    Then register the SPEL expression "nodeTemplates['Apache1Deployment_Scaling_Resource'].properties['resource_spec'].value" result as "Apache1Deployment_Scaling_Resource_spec"

  # now let's explore the env variables of the nocellar deployment
    When I Parse as JSON the content of the registered object "Apache1Deployment_Scaling_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['apiVersion']" should return "autoscaling/v2beta1"
    Then The SPEL expression "#this['kind']" should return "HorizontalPodAutoscaler"
    Then The SPEL expression "#this['spec']['scaleTargetRef']['name']" result should equals the registered "Apache1Deployment_Resource_spec"'s SPEL expression "#this['metadata']['name']" result
