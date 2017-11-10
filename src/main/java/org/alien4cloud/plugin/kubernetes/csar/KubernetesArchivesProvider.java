package org.alien4cloud.plugin.kubernetes.csar;

import org.springframework.stereotype.Component;

import alien4cloud.plugin.archives.AbstractArchiveProviderPlugin;

//@Component("kubernete-archives-provider")
public class KubernetesArchivesProvider extends AbstractArchiveProviderPlugin {

    @Override
    protected String[] getArchivesPaths() {
        return new String[] { "csar" };
    }
}
