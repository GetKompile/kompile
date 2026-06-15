package ai.kompile.graphchangetracking.config;

import ai.kompile.graphchangetracking.hook.ConfigDrivenGraphUpdateHook;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

@AutoConfiguration
@ComponentScan(basePackages = "ai.kompile.graphchangetracking")
@EnableAsync
public class GraphChangeTrackingAutoConfiguration {

    @Bean
    public GraphUpdateHookRegistry graphUpdateHookRegistry(ConfigDrivenGraphUpdateHook configDrivenHook) {
        GraphUpdateHookRegistry registry = new GraphUpdateHookRegistry();
        registry.register(configDrivenHook);
        return registry;
    }
}
