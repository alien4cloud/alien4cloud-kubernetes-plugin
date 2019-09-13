#!/bin/bash

# configuration
source $commons

# Provided variables:
# KUBE_SERVICE_DEPENDENCIES: contains a list of key values VARIABLE_WHERE_TO_STORE_SERVICE_IP:service-name,VAR2:service-name2

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
   NAMESPACE_OPTION="-n $NAMESPACE "
fi

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
        KUBE_RESOURCE_DEPLOYMENT_CONFIG=$(string_replace "$KUBE_RESOURCE_DEPLOYMENT_CONFIG" "\${$var_name}" "$var_value")
	done
}

function deploy_resource(){
    DEPLOYMENT_TMP_FILE=$(mktemp)

    # create resource deployment definition
    echo "${KUBE_RESOURCE_DEPLOYMENT_CONFIG}" > "${DEPLOYMENT_TMP_FILE}"

    # deploy
    export KUBE_DEPLOYMENT_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${DEPLOYMENT_TMP_FILE}" -o jsonpath="{.metadata.name}")
    export DEPLOYMENT_STATUS=$?

    # cleanup
    rm "${DEPLOYMENT_TMP_FILE}"

    exit_if_error

    command="kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}get deployment ${KUBE_DEPLOYMENT_ID} -o=jsonpath='{.status.conditions[?(@.type==\"Available\")].status}'"

    wait_until_done_or_exit "$command" 60
}

function scale_resource() {
  echo "scale resource $KUBE_DEPLOYMENT_ID"
}

function wait_until_done_or_exit {
  command=$1
  max_retries=$2

  retries=0
  cmd_output=$(echo $command | sh)
  cmd_code=$?
  while [ "${cmd_code}" -eq "0" ] && [ "${retries}" -lt "${max_retries}" ] ; do
    if [ "${cmd_output}" == "True" ] ; then
      # Deployment is available
      echo "Success"
      break
    fi

    echo "Waiting for deployment to be available ... (${retries}/${max_retries})"
    sleep 5
    retries=$((${retries}+1))
    cmd_output=$(echo $command | sh)
    cmd_code=$?
  done

  if [ "${retries}" -eq "${max_retries}" ] ; then
    echo "Giving up waiting for deployment to available. Reached max retries (=$max_retries)"
    exit 1
  fi
  echo $cmd_output
}

function exit_if_error(){
    if [ "${DEPLOYMENT_STATUS}" -ne 0 ]
    then
        echo "Failed to deploy"
        clear_resources
        exit "${DEPLOYMENT_STATUS}"
    fi
}

resolve_service_dependencies_variables
deploy_resource
clear_resources