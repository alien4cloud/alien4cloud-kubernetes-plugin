#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
    NAMESPACE_OPTION="-n $NAMESPACE "
fi

function string_replace {
  echo "$1" | sed -e "s/$2/$3/g"
}

function deploy_resource(){
    DEPLOYMENT_TMP_FILE=$(mktemp)

    # create resource deployment definition
    echo "${KUBE_RESOURCE_CONFIG}" > "${DEPLOYMENT_TMP_FILE}"

    # deploy
    export KUBE_RESOURCE_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${DEPLOYMENT_TMP_FILE}" | sed -r 's/.+ "([a-zA-Z0-9\-]*)" created/\1/')
    export DEPLOYMENT_STATUS=$?

    # cleanup
    rm "${DEPLOYMENT_TMP_FILE}"

    exit_if_error

    if [ -n "$KUBE_JSON_PATH_EXPR" ]; then
        command="kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}get ${KUBE_RESOURCE_TYPE} -l a4c_id=${KUBE_RESOURCE_ID} -o=jsonpath={${KUBE_JSON_PATH_EXPR}}"
        wait_until_done_or_exit "$command" 60
    fi

}

function wait_until_done_or_exit {
  command=$1
  max_retries=$2

  retries=0
  cmd_output=$(echo $command | sh)
  cmd_code=$?
  while [ "${cmd_code}" -eq "0" ] && [ "${retries}" -lt "${max_retries}" ] && [ "${cmd_output}" != "${KUBE_JSON_PATH_VALUE}" ] ; do
    echo "Waiting for resource to be in the expected status ... (${retries}/${max_retries})"
    sleep 5
    retries=$((${retries}+1))
    cmd_output=$(echo $command | sh)
    cmd_code=$?
  done

  if [ "${retries}" -eq "${max_retries}" ] ; then
    echo "Giving up waiting for resource to be in the expected status. Reached max retries (=$max_retries)"
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
    if [ -z "${KUBE_RESOURCE_ID}" ]
    then
        echo "Not able to retrieve resource ID"
        exit "1"
    fi
    echo "Resource ID: ${KUBE_RESOURCE_ID}"
}

deploy_resource

