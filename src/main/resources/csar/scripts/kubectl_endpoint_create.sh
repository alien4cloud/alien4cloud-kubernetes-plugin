#!/bin/bash

# should
# - copy the content of the KUBE_RESOURCE_CONFIG var into a tmp file
# - publish the file path in output KUBE_SPEC_PATH

CONFIG_TMP_FILE=$(mktemp)
echo "${KUBE_RESOURCE_CONFIG}" > "${CONFIG_TMP_FILE}"
export KUBE_SPEC_PATH="${CONFIG_TMP_FILE}"
