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

  Scenario: Test the EmptyDirVolumeSource
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.EmptyDirVolumeSource" version "2.0.0-SNAPSHOT"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.containsKey('ContainerDeploymentUnit_Resource')" should return true
    When register the SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].properties['resource_spec'].value" result as "ContainerDeploymentUnit_Resource_Resource_spec"
    And I Parse as JSON the content of the registered object "ContainerDeploymentUnit_Resource_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['template']['spec']['volumes'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0].containsKey('emptyDir')" should return true
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['name']" should return "volume"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['mountPath']" should return "/var/www/html"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['name']" should return "volume"

  Scenario: Test the HostPathVolumeSource
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.HostPathVolumeSource" version "2.0.0-SNAPSHOT"
    And I set the node "Volume" property "spec.path" to "/home/ec2-user"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.containsKey('ContainerDeploymentUnit_Resource')" should return true
    When register the SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].properties['resource_spec'].value" result as "ContainerDeploymentUnit_Resource_Resource_spec"
    And I Parse as JSON the content of the registered object "ContainerDeploymentUnit_Resource_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['template']['spec']['volumes'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['name']" should return "volume"
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0].containsKey('hostPath')" should return true
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['hostPath']['path']" should return "/home/ec2-user"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['mountPath']" should return "/var/www/html"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['name']" should return "volume"

  Scenario: Test the AWSElasticBlockStoreVolumeSource
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.AWSElasticBlockStoreVolumeSource" version "2.0.0-SNAPSHOT"
    And I set the node "Volume" property "spec.volumeID" to "#1234"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.containsKey('ContainerDeploymentUnit_Resource')" should return true
    When register the SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].properties['resource_spec'].value" result as "ContainerDeploymentUnit_Resource_Resource_spec"
    And I Parse as JSON the content of the registered object "ContainerDeploymentUnit_Resource_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['template']['spec']['volumes'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['name']" should return "volume"
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0].containsKey('awsElasticBlockStore')" should return true
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['awsElasticBlockStore']['volumeID']" should return "#1234"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['mountPath']" should return "/var/www/html"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['name']" should return "volume"

  Scenario: Test the PersistentVolumeClaimSource with claim generation
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimSource" version "2.0.0-SNAPSHOT"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.containsKey('ContainerDeploymentUnit_Resource')" should return true
    # we should find a node for the PersistentVolumeClaim
    And The SPEL expression "nodeTemplates.containsKey('Volume_PVC')" should return true
    And The SPEL expression "nodeTemplates['Volume_PVC'].type" should return "org.alien4cloud.kubernetes.api.types.SimpleResource"
    # the deployment should depends_on the PVC
    And The SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].relationships.values().iterator().next().target" should return "Volume_PVC"
    And The SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].relationships.values().iterator().next().type" should return "tosca.relationships.DependsOn"
    And register the SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].properties['resource_spec'].value" result as "ContainerDeploymentUnit_Resource_Resource_spec"
    And register the SPEL expression "nodeTemplates['Volume_PVC'].properties['resource_spec'].value" result as "Volume_PVC_Resource_spec"
    # check the PVC spec content
    When I Parse as JSON the content of the registered object "Volume_PVC_Resource_spec" and put it in the SPEL context
    And register the SPEL expression "#this['metadata']['name']" result as "pvc_name"
    Then The SPEL expression "#this['apiVersion']" should return "v1"
    And The SPEL expression "#this['kind']" should return "PersistentVolumeClaim"
    And The SPEL expression "#this['spec']['resources']['requests']['storage']" should return 200000000
    # check the deployment resource and it's container
    When I Parse as JSON the content of the registered object "ContainerDeploymentUnit_Resource_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['template']['spec']['volumes'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['name']" should return "volume"
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0].containsKey('persistentVolumeClaim')" should return true
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['persistentVolumeClaim']['claimName']" result should equals the registered object "pvc_name"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['mountPath']" should return "/var/www/html"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['name']" should return "volume"

  Scenario: Test the PersistentVolumeClaimSource without claim generation
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimSource" version "2.0.0-SNAPSHOT"
    And I set the node "Volume" property "spec.claimName" to "my_already_exist_claim"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 2
    And The SPEL expression "nodeTemplates.containsKey('ContainerDeploymentUnit_Resource')" should return true
    And register the SPEL expression "nodeTemplates['ContainerDeploymentUnit_Resource'].properties['resource_spec'].value" result as "ContainerDeploymentUnit_Resource_Resource_spec"
    # we shouldn't find a node for the PersistentVolumeClaim
    And The SPEL expression "nodeTemplates.containsKey('Volume_PVC')" should return false
    # check the deployment resource and it's container
    When I Parse as JSON the content of the registered object "ContainerDeploymentUnit_Resource_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['template']['spec']['volumes'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['name']" should return "volume"
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0].containsKey('persistentVolumeClaim')" should return true
    And The SPEL expression "#this['spec']['template']['spec']['volumes'][0]['persistentVolumeClaim']['claimName']" should return "my_already_exist_claim"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'].size()" should return 1
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['mountPath']" should return "/var/www/html"
    And The SPEL expression "#this['spec']['template']['spec']['containers'][0]['volumeMounts'][0]['name']" should return "volume"

  Scenario: Test the PersistentVolumeClaimStorageClassSource with 'default' storageClass
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimStorageClassSource" version "2.0.0-SNAPSHOT"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    # we should find a node for the PersistentVolumeClaim
    And The SPEL expression "nodeTemplates.containsKey('Volume_PVC')" should return true
    And The SPEL expression "nodeTemplates['Volume_PVC'].type" should return "org.alien4cloud.kubernetes.api.types.SimpleResource"
    And register the SPEL expression "nodeTemplates['Volume_PVC'].properties['resource_spec'].value" result as "Volume_PVC_Resource_spec"
    # check the PVC spec content
    When I Parse as JSON the content of the registered object "Volume_PVC_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['apiVersion']" should return "v1"
    And The SPEL expression "#this['kind']" should return "PersistentVolumeClaim"
    And The SPEL expression "#this['spec']['resources']['requests']['storage']" should return 200000000
    And The SPEL expression "#this['spec']['storageClassName']" should return "default"

  Scenario: Test the PersistentVolumeClaimStorageClassSource with custom storageClass
    Given I upload unzipped CSAR from path "src/test/resources/data/05-1apache-1volume/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I match the node named "Volume" to a node of type "org.alien4cloud.kubernetes.api.types.volume.PersistentVolumeClaimStorageClassSource" version "2.0.0-SNAPSHOT"
    And I set the node "Volume" property "storageClassName" to "my_custom_storage_class"
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    # we should find a node for the PersistentVolumeClaim
    And The SPEL expression "nodeTemplates.containsKey('Volume_PVC')" should return true
    And register the SPEL expression "nodeTemplates['Volume_PVC'].properties['resource_spec'].value" result as "Volume_PVC_Resource_spec"
    # check the PVC spec content
    When I Parse as JSON the content of the registered object "Volume_PVC_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['storageClassName']" should return "my_custom_storage_class"
