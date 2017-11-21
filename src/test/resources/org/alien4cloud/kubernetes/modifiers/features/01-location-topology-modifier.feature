Feature: Kubernetes location topology modifier
  # test the kubernetes-modifier (the modifier that apply just after the location matching)

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

  Scenario: Apply topology modifier on a simple topology containing 1 apache
    # test the basics of the K8S topology modifier : replacement of nodes and creation of services
    Given I upload unzipped CSAR from path "src/test/resources/data/01-one-apache/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 4
    # ensure the container runtime node has been replaced by an abstract k8s container node
    And The SPEL expression "nodeTemplates['ApacheContainer'].type" should return "org.alien4cloud.kubernetes.api.types.AbstractContainer"
    # ensure properties are transfered from the container node
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['image'].value" should return "httpd:latest"
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['resources']['requests']['memory'].value" should return "128 MB"
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['resources']['requests']['cpu'].value" should return "0.2"
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['resources']['limits']['memory'].value" should return "128 MB"
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['resources']['limits']['cpu'].value" should return "0.2"
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['ports'].value[0].value['name'].value" should return "http-endpoint"
    And The SPEL expression "nodeTemplates['ApacheContainer'].properties['container'].value['ports'].value[0].value['containerPort'].value" should return "80"
    # ensure the deployment unit node has been replaced by an abstract k8s deployment node
    And The SPEL expression "nodeTemplates['ApacheDeployment'].type" should return "org.alien4cloud.kubernetes.api.types.AbstractDeployment"
    # the number of replicas comes from the default_instances property of the initial node
    And The SPEL expression "nodeTemplates['ApacheDeployment'].properties['spec'].value['replicas'].value" should return "2"
    And The SPEL expression "nodeTemplates['ApacheDeployment'].capabilities['scalable'].properties['min_instances'].value" should return "1"
    And The SPEL expression "nodeTemplates['ApacheDeployment'].capabilities['scalable'].properties['default_instances'].value" should return "2"
    And The SPEL expression "nodeTemplates['ApacheDeployment'].capabilities['scalable'].properties['max_instances'].value" should return "2"
    # get the app label (used by the service to reference the deployment)
    And register the SPEL expression "nodeTemplates['ApacheDeployment'].properties['spec'].value['template']['metadata']['labels']['app'].value" result as "ApacheDeployment_app_label"
    # a service has been added to target the endpoint of the container image node
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].type" should return "org.alien4cloud.kubernetes.api.types.AbstractService"
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].properties['spec'].value['selector']['app'].value" result should equals the registered object "ApacheDeployment_app_label"
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].properties['spec'].value['service_type'].value" should return "NodePort"
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].properties['spec'].value['ports'].value[0].value['name'].value" should return "http-endpoint"
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].properties['spec'].value['ports'].value[0].value['port'].value" should return "80"
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].properties['spec'].value['ports'].value[0].value['targetPort'].value" should return "http-endpoint"
    # we may have a depends_on between the service and the deployment
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].relationships.values().iterator().next().target" should return "ApacheDeployment"
    And The SPEL expression "nodeTemplates['Apache_http_endpoint_Service'].relationships.values().iterator().next().type" should return "tosca.relationships.DependsOn"

  Scenario: Apply topology modifier on a simple topology containing 2 apache
    # here we just check that the orphan container runtime is wrapped by a generated deployment
    Given I upload unzipped CSAR from path "src/test/resources/data/02-two-apache/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 8
    And The SPEL expression "nodeTemplates['Apache2ContainerDeployment'].type" should return "org.alien4cloud.kubernetes.api.types.AbstractDeployment"
    # the Apache2Container may be hosted_on the generated deployment
    And The SPEL expression "nodeTemplates['Apache2Container'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['Apache2Container'].relationships.values().iterator().next().target" should return "Apache2ContainerDeployment"
    And The SPEL expression "nodeTemplates['Apache2Container'].relationships.values().iterator().next().type" should return "tosca.relationships.HostedOn"
    # the policy may now target this created node
    And The SPEL expression "policies['Placement'].targets.size()" should return 2
    And The SPEL expression "policies['Placement'].targets.contains('Apache1Deployment')" should return true
    And The SPEL expression "policies['Placement'].targets.contains('Apache2ContainerDeployment')" should return true

  Scenario: Apply topology modifier on a topology that connects 2 containers : nodecelar depends_on mongo
    Given I upload unzipped CSAR from path "src/test/resources/data/03-1nodecellar-1mongo/1-initial.yaml"
    And I get the topology related to the CSAR with name "initial" and version "2.0.0-SNAPSHOT"
    When I execute the modifier "kubernetes-modifier" on the current topology
    And I store the current topology in the SPEL context
    Then The SPEL expression "nodeTemplates.size()" should return 8
    # the command property of the NocellarContainer should be filled
    And The SPEL expression "nodeTemplates['NocellarContainer'].properties['container'].value['command'].value.size()" should return 3
    And The SPEL expression "nodeTemplates['NocellarContainer'].properties['container'].value['command'].value.get(0)" should return "/bin/bash"
    And The SPEL expression "nodeTemplates['NocellarContainer'].properties['container'].value['command'].value.get(1)" should return "-c"
    And The SPEL expression "nodeTemplates['NocellarContainer'].properties['container'].value['command'].value.get(2)" should return "cd /nodecellar && nodejs server.js"
    # here we check that the source deployment is connected to the target service
    And The SPEL expression "nodeTemplates['NodecellarDeployment'].relationships.size()" should return 1
    And The SPEL expression "nodeTemplates['NodecellarDeployment'].relationships.values().iterator().next().target" should return "Mongo_mongo_db_Service"
    And The SPEL expression "nodeTemplates['NodecellarDeployment'].relationships.values().iterator().next().type" should return "tosca.relationships.DependsOn"
