Feature: Topology modifier

  Background:
    Given I am authenticated with "ADMIN" role
    Given I add a GIT repository with url "https://github.com/alien4cloud/tosca-normative-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0   |         |
    And I get the GIT repo with url "https://github.com/alien4cloud/tosca-normative-types.git"
    And I import the GIT repository
    Given I add a GIT repository with url "https://github.com/alien4cloud/alien4cloud-extended-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0 | alien-base-types |
    And I get the GIT repo with url "https://github.com/alien4cloud/alien4cloud-extended-types.git"
    And I import the GIT repository
    Given I add a GIT repository with url "https://github.com/alien4cloud/docker-tosca-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0-alt | docker-types |
      | tests/2.0.0-alt | docker-draft-2.0.0/sandbox/kubernetes |
      | tests/2.0.0-alt | docker-draft-2.0.0/sandbox/samples |
    And I get the GIT repo with url "https://github.com/alien4cloud/docker-tosca-types.git"
    And I import the GIT repository
    # TODO: remove docker-draft-2.0.0/sandbox/kubernetes (will be embeded by this project)

  Scenario: Transform a base topology
    Given I successfully upload the local archive "data/00-simple-topology/10-topology-containerunit.yaml"
    And I should be able to retrieve a topology with name "docker-topo-sample-nodecellar" version "2.0.0-SNAPSHOT" and store it as a SPEL context
    When I execute the modifier "org.alien4cloud.kubernetes.modifiers.KubernetesLocationModifier" on the current topology
