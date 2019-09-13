#!/bin/bash

# configuration
source $commons

# Provided variables:
# KUBE_SERVICE_CONFIG: k8s deployment configuration in JSON format
# KUBE_SERVICE_NAME: name of the service to start

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
    NAMESPACE_OPTION="-n $NAMESPACE "
fi

function deploy_service(){
    SERVICE_CONFIG_TMP_FILE=$(mktemp)

    # create kube service config file
    echo "${KUBE_SERVICE_CONFIG}" > "${SERVICE_CONFIG_TMP_FILE}"

    # deploy service
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${SERVICE_CONFIG_TMP_FILE}"
    SERVICE_DEPLOY_STATUS=$?

    # cleanup
    rm "${SERVICE_CONFIG_TMP_FILE}"

    if [ "${SERVICE_DEPLOY_STATUS}" -ne 0 ]
    then
        echo "Failed to deploy service"
        clear_resources
        exit "${SERVICE_DEPLOY_STATUS}"
    fi

    # get IP/PORT
    #export IP_ADDRESS=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get services "${KUBE_SERVICE_NAME}" -o=jsonpath={.spec.clusterIP})
    export IP_ADDRESS=$KUBE_SERVICE_NAME
    export PORT=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}get services "${KUBE_SERVICE_NAME}" -o=jsonpath={.spec.ports[0].port})
    export NODE_PORT=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}get services "${KUBE_SERVICE_NAME}" -o=jsonpath={.spec.ports[0].nodePort})
}

deploy_service
clear_resources
