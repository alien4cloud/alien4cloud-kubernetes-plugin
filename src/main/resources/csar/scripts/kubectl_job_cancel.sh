#!/bin/bash
set -e
# configuration
source $commons

echo "Cancelling Job ${TOSCA_JOB_ID}"

kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete "${TOSCA_JOB_ID}"

clear_resources
