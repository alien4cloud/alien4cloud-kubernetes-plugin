#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# provided variables:
# KUBE_DEPLOYMENT_ID: contains the k8s deployment id to undeploy

function undeploy_resource(){
    # undeploy
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete deployment "${KUBE_DEPLOYMENT_ID}"
    UNDEPLOY_STATUS=$?

    if [ "${UNDEPLOY_STATUS}" -ne 0 ]
    then
        echo "Failed to undeploy"
        exit "${UNDEPLOY_STATUS}"
    fi
}

undeploy_resource

