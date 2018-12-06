#!/bin/bash

set -e 

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

JOB_TMP_FILE=$(mktemp)

# create resource deployment definition
echo "${KUBE_RESOURCE_JOB_CONFIG}" > "${JOB_TMP_FILE}"

# deploy
TOSCA_JOB_ID=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${JOB_TMP_FILE}" -o 'name')
export TOSCA_JOB_ID

# cleanup
rm "${JOB_TMP_FILE}"
