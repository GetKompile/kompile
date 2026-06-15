package ai.kompile.compute.graph.config;

import ai.kompile.compute.graph.engine.ComputeGraphEngine;
import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.engine.PassthroughNodeExecutor;
import ai.kompile.compute.graph.store.ArtifactStore;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import ai.kompile.compute.graph.store.WorkflowFileStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Auto-configuration for the compute graph core engine.
 * Always registers — runtime enable/disable is managed via the UI config service.
 */
@AutoConfiguration
public class ComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ComputeGraphConfigService computeGraphConfigService() {
        return new ComputeGraphConfigService();
    }

    @Bean
    @ConditionalOnMissingBean(ArtifactStore.class)
    public ArtifactStore inMemoryArtifactStore() {
        return new InMemoryArtifactStore();
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowFileStore.class)
    public WorkflowFileStore workflowFileStore() {
        return new WorkflowFileStore();
    }

    @Bean
    public PassthroughNodeExecutor passthroughNodeExecutor() {
        return new PassthroughNodeExecutor();
    }

    @Bean
    @Primary
    public ComputeGraphEngine computeGraphEngine(List<NodeExecutor> executors, ArtifactStore artifactStore) {
        return new DefaultGraphExecutor(executors, artifactStore);
    }
}
