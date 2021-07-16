#!/bin/bash

# configuration
source $commons

function detach_pv(){
   echo label: ${LABEL_NAME}=${LABEL_VALUE}
   if [ -z "$LABEL_NAME" ]; then
     echo Can not select PV: no label name
     clear_resources
     exit 1
   fi
   if [ -z "$LABEL_VALUE" ]; then
     echo Can not select PV: no label value
     clear_resources
     exit 1
   fi
   command="kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get pv -l ${LABEL_NAME}=${LABEL_VALUE} -o jsonpath={..status.phase}"
   KUBE_JSON_PATH_VALUE=Released
   wait_until_done_or_exit "$command" 60
   PV_NAME=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get pv -l ${LABEL_NAME}=${LABEL_VALUE} -o jsonpath={..metadata.name})
   echo PV: $PV_NAME
   if [ -z "$PV_NAME" ]; then
     echo Can not find PV
     clear_resources
     exit 1
   fi
   kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" patch pv ${PV_NAME} --type json -p '[{"op": "remove", "path": "/spec/claimRef"}]'
   RC=$?
}

function wait_until_done_or_exit {
  command=$1
  max_retries=$2

  retries=0
  cmd_output=$(echo $command | sh)
  cmd_code=$?
  while [ "${cmd_code}" -eq "0" ] && [ "${retries}" -lt "${max_retries}" ] && [ "${cmd_output}" != "${KUBE_JSON_PATH_VALUE}" ] ; do
    echo "Waiting for resource to be in the expected status ... (${retries}/${max_retries})"
    sleep 5
    retries=$((${retries}+1))
    cmd_output=$(echo $command | sh)
    cmd_code=$?
  done

  if [ "${retries}" -eq "${max_retries}" ] ; then
    echo "Giving up waiting for resource to be in the expected status. Reached max retries (=$max_retries)"
    clear_resources
    exit 1
  fi
  echo $cmd_output
}

detach_pv
clear_resources
exit $RC
