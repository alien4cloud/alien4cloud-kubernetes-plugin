#!/bin/bash

# configuration
source $commons

# provided variables:
# KUBE_DEPLOYMENT_ID: contains the k8s deployment id to undeploy

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
   NAMESPACE_OPTION="-n $NAMESPACE "
fi

function undeploy_resource(){
    # undeploy

    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}delete deployment "${KUBE_DEPLOYMENT_ID}"
    UNDEPLOY_STATUS=$?
    clear_resources

    if [ "${UNDEPLOY_STATUS}" -ne 0 ]
    then
        echo "Failed to undeploy"
        exit "${UNDEPLOY_STATUS}"
    fi
}

undeploy_resource

