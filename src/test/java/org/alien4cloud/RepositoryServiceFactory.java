package org.alien4cloud;

import alien4cloud.repository.services.RepositoryService;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.AbstractFactoryBean;

import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Mock <code>RepositoryService</code> to avoid issues with artifacts resolving during import of CSARs in test context.
 */
public class RepositoryServiceFactory extends AbstractFactoryBean<RepositoryService> {

    @Override
    public Class<?> getObjectType() {
        return RepositoryService.class;
    }

    @Override
    protected RepositoryService createInstance() throws Exception {
        RepositoryService repositoryService = Mockito.mock(RepositoryService.class);
        when(repositoryService.canResolveArtifact(anyString(), anyString(), anyString(), anyMapOf(String.class, Object.class))).thenReturn(true);
        when(repositoryService.resolveArtifact(anyString(), anyString(), anyString(), anyMapOf(String.class, Object.class))).thenReturn("");
        return repositoryService;
    }
}
