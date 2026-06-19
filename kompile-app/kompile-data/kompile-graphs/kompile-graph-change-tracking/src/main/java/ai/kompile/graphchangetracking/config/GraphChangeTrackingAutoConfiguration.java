package ai.kompile.graphchangetracking.config;

import ai.kompile.graphchangetracking.hook.ConfigDrivenGraphUpdateHook;
import ai.kompile.graphchangetracking.hook.GraphRuleHook;
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
    public GraphUpdateHookRegistry graphUpdateHookRegistry(ConfigDrivenGraphUpdateHook configDrivenHook,
                                                           GraphRuleHook graphRuleHook) {
        GraphUpdateHookRegistry registry = new GraphUpdateHookRegistry();
        registry.register(configDrivenHook);
        registry.register(graphRuleHook);
        return registry;
    }
}
