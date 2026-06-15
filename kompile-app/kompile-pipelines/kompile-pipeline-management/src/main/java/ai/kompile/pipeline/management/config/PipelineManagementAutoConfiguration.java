package ai.kompile.pipeline.management.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "ai.kompile.pipeline.management")
@ConditionalOnClass(name = "ai.kompile.pipeline.management.config.PipelineManagementAutoConfiguration")
@ConditionalOnProperty(name = "kompile.pipelines.management.enabled", havingValue = "true", matchIfMissing = true)
public class PipelineManagementAutoConfiguration {
}
