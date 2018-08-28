#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Provided variables:
# KUBE_SERVICE_DEPENDENCIES: contains a list of key values VARIABLE_WHERE_TO_STORE_SERVICE_IP:service-name,VAR2:service-name2

function string_replace {
  echo "$1" | sed -e "s/$2/$3/g"
}

function resolve_service_dependencies_variables(){
	echo "resolving dependencies variables..."

	for service_dependency in $(echo ${KUBE_SERVICE_DEPENDENCIES} | tr ',' ' ')
	do
        var_name=$(echo $service_dependency | cut -d ':' -f 1)
        var_service_name=$(echo $service_dependency | cut -d ':' -f 2)
        var_value=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" get services "${var_service_name}" -o=jsonpath={.spec.clusterIP})
		echo "${var_name} : ${var_value}"
        eval "${var_name}=${var_value}"
        # Iterate over files in value of $config (the config folder artifact)
        for file in $( ls $configs/* )
        do
            echo "Replacing occurrences of \${$var_name} by '$var_value' in file ${file}"
            # we can not manage value containing / @ and ^ (probability near zero !)
            sed_expre="s/\${$var_name}/${var_value}/g";
            if [ -z "${var_value##*/*}" ] ;then
                sed_expre="s@\${$var_name}@${var_value}@g";
                if [ -z "${var_value##*@*}" ] ;then
                    sed_expre="s^\${$var_name}^${var_value}^g";
                fi
            fi
            sed -i "${sed_expre}" ${file}
        done
	done
}

echo $(ls $configs)

command="kubectl --kubeconfig ${KUBE_ADMIN_CONFIG_PATH} create configmap ${CONFIGMAP_NAME}"

json="$INPUT_VARIABLES"
# Iterate over files in value of $config (the config folder artifact)
for file in $( ls $configs/* )
do
    for key in $(echo $json | jq 'keys' | jq -r '.[]');
    do
        value=$(echo $json | jq -r ".${key}");
        echo "Replacing occurrences of \${$key} by '$value' in file ${file}"
        # we can not manage value containing / @ and ^ (probability near zero !)
        sed_expre="s/\${$key}/${value}/g";
        if [ -z "${value##*/*}" ] ;then
            sed_expre="s@\${$key}@${value}@g";
            if [ -z "${value##*@*}" ] ;then
                sed_expre="s^\${$key}^${value}^g";
            fi
        fi
        sed -i "${sed_expre}" ${file}
    done
    # build the config map command end
    command="$command --from-file=$file"
done

# lookup service dependencies
resolve_service_dependencies_variables

echo "Creating configMap using command: $command"

cmd_output=$(echo $command | sh)
cmd_code=$?
if [ "${cmd_code}" -ne 0 ]; then
    echo "Failed to create config map: $cmd_output"
    exit "${cmd_code}"
fi
echo "Config map successfully created : $cmd_output"
