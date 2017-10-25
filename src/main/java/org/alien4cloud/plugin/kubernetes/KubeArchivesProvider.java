package org.alien4cloud.plugin.kubernetes;

import org.springframework.stereotype.Component;

import alien4cloud.plugin.archives.AbstractArchiveProviderPlugin;

@Component("kubernete-archives-provider")
public class KubeArchivesProvider extends AbstractArchiveProviderPlugin {

    @Override
    protected String[] getArchivesPaths() {
        return new String[] { "csar" };
    }
}
