#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

function create_resource(){
    DEPLOYMENT_TMP_FILE=$(mktemp)

    # create resource deployment definition
    echo "${KUBE_RESOURCE_JOB_CONFIG}" > "${JOB_TMP_FILE}"

    # deploy
    export KUBE_JOB_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${JOB_TMP_FILE}" | sed -r 's/job "([a-zA-Z0-9\-]*)" created/\1/')
    export JOB_STATUS=$?

    # cleanup
    rm "${JOB_TMP_FILE}"

    exit_if_error

    command="kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get job ${KUBE_JOB_ID} -o=jsonpath={.status.conditions[*].status}"

    #wait_until_done_or_exit "$command" 60
}

create_resource