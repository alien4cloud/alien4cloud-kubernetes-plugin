#!/bin/bash

# should change the TARGET_IP_ADDRESS in the file in KUBE_SPEC_PATH

sed -i -e "s/#{TARGET_IP_ADDRESS}/$TARGET_IP_ADDRESS/g" ${KUBE_SPEC_PATH}
