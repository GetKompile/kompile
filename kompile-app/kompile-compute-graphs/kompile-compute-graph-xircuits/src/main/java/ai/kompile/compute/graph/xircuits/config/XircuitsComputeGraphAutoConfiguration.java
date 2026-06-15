package ai.kompile.compute.graph.xircuits.config;

import ai.kompile.compute.graph.xircuits.XircuitsNodeExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Xircuits compute graph backend.
 * Always registers — runtime enable/disable is managed via the UI config service.
 */
@AutoConfiguration
public class XircuitsComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(XircuitsNodeExecutor.class)
    public XircuitsNodeExecutor xircuitsNodeExecutor() {
        return new XircuitsNodeExecutor();
    }
}
