package org.alien4cloud.plugin.kubernetes.policies;

public class KubePoliciesConstants {
    public static final String POD_ANTI_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH = "spec.template.spec.affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution";
    public static final String NODE_AFFINITY_PREFERRED_DURING_SCHE_IGNORED_DURING_EXEC_PATH = "spec.template.spec.affinity.nodeAffinity.preferredDuringSchedulingIgnoredDuringExecution";

    // policies

    public static final String K8S_POLICIES_ANTI_AFFINITY_LABEL = "org.alien4cloud.kubernetes.api.policies.AntiAffinityLabel";
    public static final String K8S_POLICIES_NODE_AFFINITY_LABEL = "org.alien4cloud.kubernetes.api.policies.NodeAffinityLabel";
}
