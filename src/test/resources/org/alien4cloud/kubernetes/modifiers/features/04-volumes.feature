Feature: Kubernetes final location topology modifier
  # test the kubernetes-final-modifier

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
    Given I upload unzipped CSAR from path "src/test/resources/csar/docker-samples-types.yml"
    Given I upload unzipped CSAR from path "src/main/resources/csar"
#
#  Scenario: Apply final modifier on a topology containing 1 node cellar 1 mongo
#    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
#    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
#    When I execute the modifier "kubernetes-modifier" on the current topology
#    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
#    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.EmptyDirVolumeSource" version "2.0.0-SM3"
#    And I execute the modifier "kubernetes-final-modifier" on the current topology
#    And I store the current topology in the SPEL context

#  Scenario: Test the HostPathVolumeSource
#    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
#    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
#    When I execute the modifier "kubernetes-modifier" on the current topology
#    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
#    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.HostPathVolumeSource" version "2.0.0-SM3"
#    And I set the node "Volume" property "spec.path" to "/home/ec2-user"
#    And I execute the modifier "kubernetes-final-modifier" on the current topology
#    And I store the current topology in the SPEL context

#  Scenario: Test the AWSElasticBlockStoreVolumeSource
#    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
#    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
#    When I execute the modifier "kubernetes-modifier" on the current topology
#    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
#    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.AWSElasticBlockStoreVolumeSource" version "2.0.0-SM3"
#    And I set the node "Volume" property "spec.volumeID" to "#1234"
#    And I execute the modifier "kubernetes-final-modifier" on the current topology
#    And I store the current topology in the SPEL context

  Scenario: Test the VolumeClaim
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimSource" version "2.0.0-SNAPSHOT"
#    And I set the node "Volume" property "spec.volumeID" to "#1234"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context