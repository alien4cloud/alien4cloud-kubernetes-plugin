#!/bin/bash

# configuration
PREFIX=K8S_
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

function resolve_service_dependencies_variables(){
	echo "resolving dependencies variables..."

	for service_dependency in $(echo ${KUBE_SERVICE_DEPENDENCIES} | tr ',' ' ')
	do
        	var_name=$(echo $service_dependency | cut -d ':' -f 1)
        	var_service_name=$(echo $service_dependency | cut -d ':' -f 2)
        	var_value=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get services "${var_service_name}" -o=jsonpath={.spec.clusterIP})
		echo "${var_name} : ${var_value}"
        	eval "${var_name}=${var_value}"
	done
}

function deploy_resource(){
    DEPLOYMENT_TMP_FILE=$(mktemp)

    # create resource deployment definition
    echo "${KUBE_RESOURCE_DEPLOYMENT_CONFIG}" > "${DEPLOYMENT_TMP_FILE}"

    # deploy
    DEPLOYMENT_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${DEPLOYMENT_TMP_FILE}" | sed -r 's/deployment "([a-zA-Z0-9\-]*)" created/\1/')
    export DEPLOYMENT_STATUS=$?

    # cleanup
    rm "${DEPLOYMENT_TMP_FILE}"

    exit_if_error

    export DEPLOYMENT_ID

    command="kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get deployment ${DEPLOYMENT_ID} -o=jsonpath={.status.conditions[*].status}"

    wait_until_done_or_exit "$command" 60
}

function wait_until_done_or_exit {
  command=$1
  max_retries=$2

  retries=0
  cmd_output=$(echo $command | sh)
  cmd_code=$?
  while [ "${cmd_code}" -eq "0" ] && [ "${retries}" -lt "${max_retries}" ] ; do
    case "${cmd_output}" in
    *False*)
      # At least one condition is not fulfil - keep going
      ;;
    *)
      # All conditions are fufilled
      echo "Success"
      break
      ;;
    esac

    echo "Waiting for deployment to be completed ... (${retries}/${max_retries})"
    sleep 5
    retries=$((${retries}+1))
    cmd_output=$(echo $command | sh)
    cmd_code=$?
  done

  if [ "${retries}" -eq "${max_retries}" ] ; then
    echo "Giving up waiting for deployment to complete. Reached max retries (=$max_retries)"
    exit 1
  fi
  echo $cmd_output
}

function exit_if_error(){
    if [ "${DEPLOYMENT_STATUS}" -ne 0 ]
    then
        echo "Failed to deploy"
        exit "${DEPLOYMENT_STATUS}"
    fi
}

resolve_service_dependencies_variables
deploy_resource
exit 0

