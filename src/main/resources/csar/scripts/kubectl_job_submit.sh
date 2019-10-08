#!/bin/bash

set -e

# configuration
source $commons

function string_replace {
  echo "$1" | sed -e "s/$2/$3/g"
}

function resolve_service_dependencies_variables(){
	echo "resolving dependencies variables..."

	for service_dependency in $(echo ${KUBE_SERVICE_DEPENDENCIES} | tr ',' ' ')
	do
        var_name=$(echo $service_dependency | cut -d ':' -f 1)
        var_service_name=$(echo $service_dependency | cut -d ':' -f 2)
        var_value=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}get services "${var_service_name}" -o=jsonpath={.spec.clusterIP})
		echo "${var_name} : ${var_value}"
        eval "${var_name}=${var_value}"
        # Update the configuration to inject the service dependency variable
        KUBE_RESOURCE_JOB_CONFIG=$(string_replace "$KUBE_RESOURCE_JOB_CONFIG" "\${$var_name}" "$var_value")
	done
}

resolve_service_dependencies_variables

JOB_TMP_FILE=$(mktemp)

# create resource deployment definition
echo "${KUBE_RESOURCE_JOB_CONFIG}" > "${JOB_TMP_FILE}"

# deploy
TOSCA_JOB_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${JOB_TMP_FILE}" -o 'name')
export TOSCA_JOB_ID

# cleanup
rm "${JOB_TMP_FILE}"
clear_resources
