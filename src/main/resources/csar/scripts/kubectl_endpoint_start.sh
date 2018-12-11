#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${KUBE_SPEC_PATH}"