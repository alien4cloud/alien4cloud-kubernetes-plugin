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
#      | 2.0.0-SM3 | docker-draft-2.0.0/sandbox/samples |
    Given I upload unzipped CSAR from path "src/test/resources/csar/docker-samples-types.yml"
    Given I upload unzipped CSAR from path "src/main/resources/csar"

  Scenario: Apply final modifier on a topology containing 1 node cellar 1 mongo
    Given I upload unzipped CSAR from path "src/test/resources/data/03-1nodecellar-1mongo/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I execute the modifier "kubernetes-automatching-modifier" on the current topology
    And I execute the modifier "kubernetes-final-modifier" on the current topology
    And I store the current topology in the SPEL context
    # FIXME: will fail since the old nodes are not deleted cf. org/alien4cloud/plugin/kubernetes/modifier/KubernetesFinalTopologyModifier.java:244
    Then The SPEL expression "nodeTemplates.size()" should return 4
    # we may have 2 DeploymentResource (1 for mongo 1 for nodecellar)
    And The SPEL expression "nodeTemplates['MongoContainerDeployment_Resource'].type" should return "org.alien4cloud.kubernetes.api.types.DeploymentResource"
    And The SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].type" should return "org.alien4cloud.kubernetes.api.types.DeploymentResource"
    # we may have 2 ServiceResource (1 for mongo endpoint 1 for nodecellar endpoint)
    And The SPEL expression "nodeTemplates['Mongo_mongo_db_Service_Resource'].type" should return "org.alien4cloud.kubernetes.api.types.ServiceResource"
    And The SPEL expression "nodeTemplates['Nodecellar_nodecellar_app_Service_Resource'].type" should return "org.alien4cloud.kubernetes.api.types.ServiceResource"
    # the mongo service should depends_on the mongo deployment
    And The SPEL expression "nodeTemplates['Mongo_mongo_db_Service_Resource'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['Mongo_mongo_db_Service_Resource'].relationships.values().iterator().next().target" should return "MongoContainerDeployment_Resource"
    And The SPEL expression "nodeTemplates['Mongo_mongo_db_Service_Resource'].relationships.values().iterator().next().type" should return "tosca.relationships.DependsOn"
    # the nodecellar deployment should depends_on the mongo service
    And The SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].relationships.values().iterator().next().target" should return "Mongo_mongo_db_Service_Resource"
    And The SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].relationships.values().iterator().next().type" should return "tosca.relationships.DependsOn"
    # and finally the nodecellar service should depends_on the nodecellar deployment
    And The SPEL expression "nodeTemplates['Nodecellar_nodecellar_app_Service_Resource'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['Nodecellar_nodecellar_app_Service_Resource'].relationships.values().iterator().next().target" should return "NodecellarDeployment_Resource"
    And The SPEL expression "nodeTemplates['Nodecellar_nodecellar_app_Service_Resource'].relationships.values().iterator().next().type" should return "tosca.relationships.DependsOn"
    # now let's have a look to resource_spec property of a deployment resource
    When register the SPEL expression "nodeTemplates['MongoContainerDeployment_Resource'].properties['resource_spec'].value" result as "MongoContainerDeployment_Resource_spec"
    And register the SPEL expression "nodeTemplates['Mongo_mongo_db_Service_Resource'].properties['resource_spec'].value" result as "Mongo_mongo_db_Service_Resource_spec"
    And register the SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].properties['resource_spec'].value" result as "NodecellarDeployment_Resource_spec"
    And register the SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].properties['service_dependency_lookups'].value.split(':')[0]" result as "NodecellarDeployment_Resource_service_dependency_lookups_key"
    And register the SPEL expression "nodeTemplates['NodecellarDeployment_Resource'].properties['service_dependency_lookups'].value.split(':')[1]" result as "NodecellarDeployment_Resource_service_dependency_lookups_value"
    # check that the service_dependency_lookups targets the mongo service
    Then The SPEL expression "nodeTemplates['Mongo_mongo_db_Service_Resource'].properties['service_name'].value" result should equals the registered object "NodecellarDeployment_Resource_service_dependency_lookups_value"
    And I Parse as JSON the content of the registered object "MongoContainerDeployment_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['apiVersion']" should return "apps/v1beta1"
    Then The SPEL expression "#this['kind']" should return "Deployment"
    # the container should be added to this pod
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['image']" should return "mongo:latest"
    # here we test that the special parser for size unit is correctly applied
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['resources']['requests']['memory']" should return 128000000
    # the cpu should be a double
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['resources']['requests']['cpu']" should return 1.0
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['ports'][0]['name']" should return "mongo-db"
    # containerPort is an int
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['ports'][0]['containerPort']" should return 27017
    # now let's have a look to resource_spec property of a service resource
    When I Parse as JSON the content of the registered object "Mongo_mongo_db_Service_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['apiVersion']" should return "v1"
    Then The SPEL expression "#this['kind']" should return "Service"
    # the 'type' property has been renamed from the TOSCA property 'service_type'
    Then The SPEL expression "#this['spec']['type']" should return "NodePort"
    # now let's explore the env variables of the nocellar deployment
    When I Parse as JSON the content of the registered object "NodecellarDeployment_Resource_spec" and put it in the SPEL context
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['env'].?[#this['name'] == 'MONGO_PORT'][0]['value']" should return "27017"
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['env'].?[#this['name'] == 'NODECELLAR_PORT'][0]['value']" should return "3000"
    Then The SPEL expression "#this['spec']['template']['spec']['containers'][0]['env'].?[#this['name'] == 'MONGO_HOST'][0]['value'].replaceAll('\{', '').replaceAll('\}', '').replaceAll('\$', '')" result should equals the registered object "NodecellarDeployment_Resource_service_dependency_lookups_key"
