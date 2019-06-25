#!/bin/bash

# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf
if [ ! -z "$KUBE_CONFIG" ]; then
   # the KUBE_CONFIG var contains the kube configuration
   KUBE_ADMIN_CONFIG_FILE=$(mktemp)
   echo "$KUBE_CONFIG" > "$KUBE_ADMIN_CONFIG_FILE"
   KUBE_ADMIN_CONFIG_PATH="$KUBE_ADMIN_CONFIG_FILE"
fi
echo "Using kube config in $KUBE_ADMIN_CONFIG_PATH"

function clear_resources(){
    if [ ! -z "$KUBE_ADMIN_CONFIG_FILE" ]; then
        echo "Removing kube config file in $KUBE_ADMIN_CONFIG_FILE"
	    rm -f "$KUBE_ADMIN_CONFIG_FILE"
	fi
}