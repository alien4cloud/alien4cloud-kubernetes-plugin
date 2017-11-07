package org.alien4cloud.plugin.kubernetes;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import alien4cloud.plugin.archives.AbstractPluginArchiveService;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class PluginArchiveService extends AbstractPluginArchiveService {

    @Override
    protected Logger getLogger() {
        return log;
    }
}
