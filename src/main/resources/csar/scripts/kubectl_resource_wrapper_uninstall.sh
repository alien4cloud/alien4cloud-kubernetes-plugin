#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_RESOURCE_ID: name of the service to start
# NAMESPACE: optionnal namespace

function delete_resource(){

    NAMESPACE_OPTION=""
    if [ -z "$NAMESPACE" ]; then
        NAMESPACE_OPTION="-n $NAMESPACE"
    fi
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete ${KUBE_RESOURCE_TYPE} -l "a4c_id=${KUBE_RESOURCE_ID}" ${NAMESPACE_OPTION}
    RESOURCE_DELETE_STATUS=$?

    if [ "$?" -ne 0 ]
    then
        echo "Failed to delete resource"
        exit "${RESOURCE_DELETE_STATUS}"
    fi

}

delete_resource
