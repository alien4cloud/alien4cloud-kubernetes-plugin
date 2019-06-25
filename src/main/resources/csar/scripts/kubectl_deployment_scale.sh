#!/bin/bash

# configuration
source $commons

# provided variables:
# KUBE_DEPLOYMENT_ID: contains the k8s deployment id to undeploy
# EXPECTED_INSTANCES: contains the replicas count for the deployment

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
   NAMESPACE_OPTION="-n $NAMESPACE "
fi

function scale_resource(){
    # scale to new expected instances count
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}scale deployment "${KUBE_DEPLOYMENT_ID}" --replicas="${EXPECTED_INSTANCES}"
    SCALE_STATUS=$?

    clear_resources

    if [ "${SCALE_STATUS}" -ne 0 ]
    then
        echo "Failed to scale"
        exit "${SCALE_STATUS}"
    fi
}

scale_resource
