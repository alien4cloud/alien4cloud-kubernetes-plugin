#!/bin/bash
set -e
# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

echo "Cancelling Job ${KUBE_JOB_ID}"

kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete "${KUBE_JOB_ID}"
