package ai.kompile.compute.graph.n8n.config;

import ai.kompile.compute.graph.n8n.N8nNodeExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the n8n compute graph backend.
 * Always registers — runtime enable/disable is managed via the UI config service.
 */
@AutoConfiguration
public class N8nComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(N8nNodeExecutor.class)
    public N8nNodeExecutor n8nNodeExecutor() {
        return new N8nNodeExecutor();
    }
}
