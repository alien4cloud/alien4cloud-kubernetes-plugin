#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# provided variables:
# KUBE_STATEFULSET_ID: contains the k8s statefulset id to undeploy
# EXPECTED_INSTANCES: contains the replicas count for the statefulset

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
   NAMESPACE_OPTION="-n $NAMESPACE "
fi

function scale_resource(){
    # scale to new expected instances count
    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" ${NAMESPACE_OPTION}scale statefulset "${KUBE_STATEFULSET_ID}" --replicas="${EXPECTED_INSTANCES}"
    SCALE_STATUS=$?

    if [ "${SCALE_STATUS}" -ne 0 ]
    then
        echo "Failed to scale"
        exit "${SCALE_STATUS}"
    fi
}

scale_resource
