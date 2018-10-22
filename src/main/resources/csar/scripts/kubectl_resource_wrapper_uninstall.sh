#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_RESOURCE_ID: name of the service to start
# NAMESPACE: optional namespace

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
    NAMESPACE_OPTION="-n $NAMESPACE "
fi

function delete_resource(){

    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}delete ${KUBE_RESOURCE_TYPE} -l "a4c_id=${KUBE_RESOURCE_ID}"
    RESOURCE_DELETE_STATUS=$?

    if [ "$?" -ne 0 ]
    then
        echo "Failed to delete resource"
        exit "${RESOURCE_DELETE_STATUS}"
    fi

}

delete_resource
