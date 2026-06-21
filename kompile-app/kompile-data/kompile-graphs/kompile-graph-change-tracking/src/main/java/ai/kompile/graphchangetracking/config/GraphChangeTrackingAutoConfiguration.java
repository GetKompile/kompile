package ai.kompile.graphchangetracking.config;

import ai.kompile.graphchangetracking.hook.ConfigDrivenGraphUpdateHook;
import ai.kompile.graphchangetracking.hook.GraphRuleHook;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Auto-configuration for graph change tracking.
 *
 * <p>The {@code @ComponentScan} picks up {@code ai.kompile.graphchangetracking} (all hooks,
 * services, controllers, etc.) and {@code ai.kompile.knowledgegraph} which contributes
 * {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator} and
 * {@link ai.kompile.knowledgegraph.grounding.KbGroundingService} — required by the L3
 * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeHook} (also picked up via scan
 * as a {@code @Component}).</p>
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "ai.kompile.graphchangetracking",
        "ai.kompile.knowledgegraph"  // brings in IncrementalReasoningOrchestrator + KbGroundingService
})
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
