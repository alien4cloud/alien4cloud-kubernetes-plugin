Feature: Topology modifier

  Background:
    Given I am authenticated with "ADMIN" role
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/tosca-normative-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0   |         |
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/alien4cloud-extended-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0 | alien-base-types |
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/docker-tosca-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0-alt | docker-types |
      | tests/2.0.0-alt | docker-draft-2.0.0/sandbox/samples |
    Given I upload unzipped CSAR from path "src/main/resources/csar"

  Scenario: Apply each modifiers on a simple topology containing 1 apache
    Given I upload unzipped CSAR from path "src/test/resources/data/01-one-apache/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I execute the modifier "kubernetes-final-modifier" on the current topology

  Scenario: Apply each modifiers on a simple topology containing 2 apache with an anti-affinity policy
    Given I upload unzipped CSAR from path "src/test/resources/data/02-two-apache/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    # TODO: match the policy
#    And I match the policy named "Placement" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.AntiAffinityLabel" version "2.0.0-SNAPSHOT"
    # TODO: apply the policy modifier
    And I execute the modifier "kubernetes-anti-affinity-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I execute the modifier "kubernetes-final-modifier" on the current topology

  Scenario: Apply each modifiers on a topology containing 1 nodecellar connected to 1 mongo
    Given I upload unzipped CSAR from path "src/test/resources/data/03-1nodecellar-1mongo/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I execute the modifier "kubernetes-final-modifier" on the current topology

  Scenario: Apply each modifiers on a topology containing 2 nodecellar connected to 2 mongo with an anti-affinity policy
    Given I upload unzipped CSAR from path "src/test/resources/data/04-2nodecellar-2mongo/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    # TODO: match the policy
#    And I match the policy named "AntiAffinity" to the concrete policy of type "org.alien4cloud.kubernetes.api.policies.AntiAffinityLabel" version "2.0.0-SNAPSHOT"
    # TODO: apply the policy modifier
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I execute the modifier "kubernetes-final-modifier" on the current topology