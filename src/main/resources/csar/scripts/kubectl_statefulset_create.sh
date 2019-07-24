#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

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
        KUBE_RESOURCE_STATEFULSET_CONFIG=$(string_replace "$KUBE_RESOURCE_STATEFULSET_CONFIG" "\${$var_name}" "$var_value")
	done
}

function deploy_resource(){
    STATEFULSET_TMP_FILE=$(mktemp)

    # create resource statefulset definition
    echo "${KUBE_RESOURCE_STATEFULSET_CONFIG}" > "${STATEFULSET_TMP_FILE}"

    # deploy
    export KUBE_STATEFULSET_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${STATEFULSET_TMP_FILE}" | sed -r 's/statefulset "([a-zA-Z0-9\-]*)" created/\1/')
    export STATEFULSET_STATUS=$?

    # cleanup
    rm "${STATEFULSET_TMP_FILE}"

    exit_if_error

    command="kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}get statefulset ${KUBE_STATEFULSET_ID} -o=jsonpath='{.status.conditions[?(@.type==\"Available\")].status}'"

    wait_until_done_or_exit "$command" 60
}

function scale_resource() {
  echo "scale resource $KUBE_STATEFULSET_ID"
}

function wait_until_done_or_exit {
  command=$1
  max_retries=$2

  retries=0
  cmd_output=$(echo $command | sh)
  cmd_code=$?
  while [ "${cmd_code}" -eq "0" ] && [ "${retries}" -lt "${max_retries}" ] ; do
    if [ "${cmd_output}" == "True" ] ; then
      # STATEFULSET is available
      echo "Success"
      break
    fi

    echo "Waiting for statefulset to be available ... (${retries}/${max_retries})"
    sleep 5
    retries=$((${retries}+1))
    cmd_output=$(echo $command | sh)
    cmd_code=$?
  done

  if [ "${retries}" -eq "${max_retries}" ] ; then
    echo "Giving up waiting for statefulset to available. Reached max retries (=$max_retries)"
    exit 1
  fi
  echo $cmd_output
}

function exit_if_error(){
    if [ "${STATEFULSET_STATUS}" -ne 0 ]
    then
        echo "Failed to deploy"
        exit "${STATEFULSET_STATUS}"
    fi
}

resolve_service_dependencies_variables
deploy_resource