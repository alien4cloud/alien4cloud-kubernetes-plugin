#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# provided variables:
# KUBE_DEPLOYMENT_ID: contains the k8s deployment id to undeploy

echo "Will delete configMap ${CONFIGMAP_NAME}"

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
    NAMESPACE_OPTION="-n ${NAMESPACE} "
fi

command="kubectl --kubeconfig ${KUBE_ADMIN_CONFIG_PATH} ${NAMESPACE_OPTION}delete secret ${SECRET_NAME}"
echo "Deleting secret using command: $command"

cmd_output=$(echo $command | sh)
cmd_code=$?
if [ "${cmd_code}" -ne 0 ]; then
    echo "Failed to delete secret: $cmd_output"
    exit "${cmd_code}"
fi
echo "Config map successfully deleted : $cmd_output"