#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_SERVICE_NAME: name of the service to start

function undeploy_service(){
    # undeploy service
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete services "${KUBE_SERVICE_NAME}"
    SERVICE_UNDEPLOY_STATUS=$?

    if [ "$?" -ne 0 ]
    then
        echo "Failed to deploy service"
        exit "${SERVICE_UNDEPLOY_STATUS}"
    fi

}

undeploy_service
