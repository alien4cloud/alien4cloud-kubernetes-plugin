#!/bin/bash

# configuration
source $commons

kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" create -f "${KUBE_SPEC_PATH}"

clear_resources