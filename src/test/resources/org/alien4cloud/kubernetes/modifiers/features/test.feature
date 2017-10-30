Feature: Topology modifier

  Background:
    Given I am authenticated with "ADMIN" role
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/tosca-normative-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0   |         |
#    And I get the GIT repo with url "https://github.com/alien4cloud/tosca-normative-types.git"
#    And I import the GIT repository
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/alien4cloud-extended-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0 | alien-base-types |
#    And I get the GIT repo with url "https://github.com/alien4cloud/alien4cloud-extended-types.git"
#    And I import the GIT repository
    Given I add and import a GIT repository with url "https://github.com/alien4cloud/docker-tosca-types.git" usr "" pwd "" stored "false" and locations
      | branchId | subPath |
      | tests/2.0.0-alt | docker-types |
      | tests/2.0.0-alt | docker-draft-2.0.0/sandbox/samples |
#      | tests/2.0.0-alt | docker-draft-2.0.0/sandbox/kubernetes |
#    And I get the GIT repo with url "https://github.com/alien4cloud/docker-tosca-types.git"
#    And I import the GIT repository
#    # TODO: remove docker-draft-2.0.0/sandbox/kubernetes (will be embeded by this project)
    Given I upload unzipped CSAR from path "src/main/resources/csar/tosca.yml"

  Scenario: Transform a base topology
#    Given I upload unzipped CSAR from path "src/test/resources/data/03-one-apache/1-initial.yaml"
#    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
#    When I execute the modifier "kubernetes-modifier" on the current topology

    Given I upload unzipped CSAR from path "src/test/resources/data/03-one-apache/3-post-node-matching.yaml"
    And I get the topology related to the CSAR with name "post-node-matching" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-final-modifier" on the current topology

#    Given I upload unzipped CSAR from path "src/test/resources/data/01-two-mongo/1-initial.yaml"
#    And I get the topology related to the CSAR with name "01-two-mongo-initial" and version "2.0.0-SNAPSHOT"
#    When I execute the modifier "kubernetes-modifier" on the current topology

#    Given I successfully upload the local archive "data/00-simple-topology/10-topology-containerunit.yaml"
#    And I should be able to retrieve a topology with name "docker-topo-sample-nodecellar" version "2.0.0-SNAPSHOT" and store it as a SPEL context
#    When I execute the modifier "org.alien4cloud.kubernetes.modifiers.KubernetesLocationModifier" on the current topology
