#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

NAMESPACE_OPTION=""
if [ ! -z "$NAMESPACE" ]; then
   NAMESPACE_OPTION="-n $NAMESPACE "
fi

command="kubectl --kubeconfig ${KUBE_ADMIN_CONFIG_PATH} ${NAMESPACE_OPTION}create secret generic ${SECRET_NAME}"

json="$INPUT_VARIABLES"
# Iterate over files in value of $resources (the resources folder artifact)
for file in $( ls $resources/* )
do
    filename=$(basename -- "$file")
    filename="${filename%.*}"
    # build the config map command end
    command="${command} --from-file=${filename}=${file}"
done

echo "Creating secret using command: $command"

cmd_output=$(echo $command | sh)
cmd_code=$?
if [ "${cmd_code}" -ne 0 ]; then
    echo "Failed to create config map: $cmd_output"
    exit "${cmd_code}"
fi
echo "Config map successfully created : $cmd_output"
