#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_SERVICE_NAME: name of the service to start

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
    NAMESPACE_OPTION="-n $NAMESPACE "
fi

function undeploy_service(){
    # undeploy service

    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}delete services "${KUBE_SERVICE_NAME}"
    SERVICE_UNDEPLOY_STATUS=$?

    if [ "$?" -ne 0 ]
    then
        echo "Failed to undeploy service"
        exit "${SERVICE_UNDEPLOY_STATUS}"
    fi

}

undeploy_service
