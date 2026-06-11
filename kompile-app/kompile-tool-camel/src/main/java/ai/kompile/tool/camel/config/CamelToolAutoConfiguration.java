package ai.kompile.tool.camel.config;

import ai.kompile.compute.graph.camel.CamelContextManager;
import ai.kompile.compute.graph.camel.CamelNodeExecutor;
import ai.kompile.compute.graph.camel.CamelRouteParser;
import ai.kompile.compute.graph.camel.CamelRouteRegistry;
import ai.kompile.tool.camel.BusinessRulesTool;
import ai.kompile.tool.camel.CamelRouteTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Camel and Business Rules MCP tools.
 * Registers tool beans when the Camel compute graph module is available.
 */
@AutoConfiguration
@ConditionalOnBean(CamelNodeExecutor.class)
public class CamelToolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CamelRouteTool.class)
    public CamelRouteTool camelRouteTool(CamelRouteRegistry routeRegistry,
                                          CamelNodeExecutor camelExecutor,
                                          CamelRouteParser routeParser,
                                          CamelContextManager contextManager) {
        return new CamelRouteTool(routeRegistry, camelExecutor, routeParser, contextManager);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessRulesTool.class)
    public BusinessRulesTool businessRulesTool(
            @Autowired(required = false) @Qualifier("droolsNodeExecutor") Object droolsNodeExecutor,
            @Autowired(required = false) @Qualifier("droolsDecisionTableCompiler") Object droolsDecisionTableCompiler) {
        return new BusinessRulesTool(droolsNodeExecutor, droolsDecisionTableCompiler);
    }
}
