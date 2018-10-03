#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_SERVICE_NAME: name of the service to start

function undeploy_service(){
    # undeploy service

    NAMESPACE_OPTION=""
    if [ -z "$NAMESPACE" ]; then
        NAMESPACE_OPTION="-n $NAMESPACE"
    fi

    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete services "${KUBE_SERVICE_NAME}" $NAMESPACE_OPTION
    SERVICE_UNDEPLOY_STATUS=$?

    if [ "$?" -ne 0 ]
    then
        echo "Failed to undeploy service"
        exit "${SERVICE_UNDEPLOY_STATUS}"
    fi

}

undeploy_service
