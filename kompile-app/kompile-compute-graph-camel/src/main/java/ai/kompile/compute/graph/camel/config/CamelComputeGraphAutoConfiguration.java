package ai.kompile.compute.graph.camel.config;

import ai.kompile.compute.graph.camel.*;
import ai.kompile.compute.graph.config.ComputeGraphConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Apache Camel compute graph backend.
 * Registers when Camel is on the classpath.
 * Runtime enable/disable is managed via the UI config service.
 * <p>
 * On startup, deploys all enabled routes from the persistent registry
 * (stored at {@code ~/.kompile/camel-routes/}) into the shared CamelContext.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.apache.camel.CamelContext")
public class CamelComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CamelRouteParser camelRouteParser() {
        return new CamelRouteParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public CamelRouteRegistry camelRouteRegistry() {
        return new CamelRouteRegistry();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CamelContextManager camelContextManager(CamelRouteParser routeParser,
                                                    CamelRouteRegistry routeRegistry) {
        CamelContextManager manager = new CamelContextManager(routeParser);
        // Deploy all enabled routes from the persistent registry on startup
        manager.deployAllFromRegistry(routeRegistry);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean(CamelNodeExecutor.class)
    public CamelNodeExecutor camelNodeExecutor(CamelContextManager contextManager,
                                                CamelRouteParser routeParser,
                                                ComputeGraphConfigService configService) {
        return new CamelNodeExecutor(contextManager, routeParser, configService.getCamelRouteTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean(GraphExtractorProcessor.class)
    public GraphExtractorProcessor graphExtractorProcessor() {
        // Pass null — the processor handles null gracefully.
        // Graph extractors are discovered at runtime via the extraction service.
        return new GraphExtractorProcessor(null);
    }

    @Bean
    @ConditionalOnMissingBean(DroolsCamelProcessor.class)
    @ConditionalOnClass(name = "org.kie.api.KieServices")
    public DroolsCamelProcessor droolsCamelProcessor(
            @Autowired(required = false) @Qualifier("droolsNodeExecutor") Object droolsNodeExecutor) {
        return new DroolsCamelProcessor(droolsNodeExecutor, null, null);
    }
}
