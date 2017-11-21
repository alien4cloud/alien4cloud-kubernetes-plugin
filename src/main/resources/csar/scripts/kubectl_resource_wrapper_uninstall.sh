#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_SERVICE_NAME: name of the service to start

function delete_resource(){
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete ${KUBE_RESOURCE_TYPE} -l "a4c_id=${KUBE_RESOURCE_ID}"
    RESOURCE_DELETE_STATUS=$?

    if [ "$?" -ne 0 ]
    then
        echo "Failed to delete resource"
        exit "${RESOURCE_DELETE_STATUS}"
    fi

}

delete_resource
