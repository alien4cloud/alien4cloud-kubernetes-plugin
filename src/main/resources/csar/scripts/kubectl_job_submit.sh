#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

JOB_TMP_FILE=$(mktemp)

# create resource deployment definition
echo "${KUBE_RESOURCE_JOB_CONFIG}" > "${JOB_TMP_FILE}"

# deploy
export KUBE_JOB_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${JOB_TMP_FILE}" -o 'name')

# cleanup
rm "${JOB_TMP_FILE}"
