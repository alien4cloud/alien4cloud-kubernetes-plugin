#!/bin/bash
set -e
# configuration
KUBE_ADMIN_CONFIG_PATH=/etc/kubernetes/admin.conf

# Get active logs
activeJobs=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}"  get "${KUBE_JOB_ID}" -o=jsonpath={.status.active})

# Get failed jobs
failedJobs=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}"  get "${KUBE_JOB_ID}" -o=jsonpath={.status.failed})
if [[ -z "${failedJobs}" ]] ; then 
    failedJobs=0
fi

# Get succeeded jobs
succeededJobs=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}"  get "${KUBE_JOB_ID}" -o=jsonpath={.status.succeeded})
if [[ -z "${succeededJobs}" ]] ; then 
    succeededJobs=0
fi

# Get expected completions
expectedCompletions=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}"  get "${KUBE_JOB_ID}" -o=jsonpath={.spec.completions})

if [[ -n "${activeJobs}" ]] || ( [[ "${succeededJobs}" == "0" ]] && [[ "${failedJobs}" == "0" ]] ); then
    # Some jobs are still running
    echo "${succeededJobs}/${expectedCompletions} succeeded completions (${activeJobs} still running, ${failedJobs} failed)"
    export TOSCA_JOB_STATUS="RUNNING"
    exit 0
fi

# We are done retrieve logs
pods=$(kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}"  get pods --selector=job-name=${KUBE_JOB_ID##*/} --output=jsonpath={.items..metadata.name})
for pod in ${pods} ; do
    echo "logs from pod: ${pod}"
    kubectl logs ${pod}
done

echo
echo

if [[ ${succeededJobs} -ge ${expectedCompletions} ]] ; then 
    # We reached the minimum number of completions
    echo "Job succeeded"
    echo "${succeededJobs}/${expectedCompletions} succeeded completions, ${failedJobs} failed"
    export TOSCA_JOB_STATUS="COMPLETED"

    kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete "${KUBE_JOB_ID}"

    exit 0
fi

# Not enougth successful completions
echo "Job failed"
echo "${succeededJobs}/${expectedCompletions} succeeded completions, ${failedJobs} failed"
export TOSCA_JOB_STATUS="FAILED"
kubectl --kubeconfig "${KUBE_ADMIN_CONFIG_PATH}" delete "${KUBE_JOB_ID}"
