package ai.kompile.compute.graph.drools.config;

import ai.kompile.compute.graph.config.ComputeGraphConfigService;
import ai.kompile.compute.graph.drools.DroolsDecisionTableCompiler;
import ai.kompile.compute.graph.drools.DroolsInferenceEngine;
import ai.kompile.compute.graph.drools.DroolsNodeExecutor;
import ai.kompile.compute.graph.drools.DroolsRuleCompiler;
import ai.kompile.compute.graph.store.ArtifactStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Drools compute graph backend.
 * Always registers when Drools is on the classpath.
 * Runtime enable/disable is managed via the UI config service.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.kie.api.KieServices")
public class DroolsComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DroolsRuleCompiler droolsRuleCompiler() {
        return new DroolsRuleCompiler();
    }

    @Bean
    @ConditionalOnMissingBean
    public DroolsDecisionTableCompiler droolsDecisionTableCompiler() {
        return new DroolsDecisionTableCompiler();
    }

    @Bean
    @ConditionalOnMissingBean(DroolsNodeExecutor.class)
    public DroolsNodeExecutor droolsNodeExecutor(DroolsRuleCompiler ruleCompiler,
                                                  DroolsDecisionTableCompiler decisionTableCompiler,
                                                  ComputeGraphConfigService configService) {
        return new DroolsNodeExecutor(ruleCompiler, decisionTableCompiler, configService.getMaxRuleFiringsPerNode());
    }

    @Bean
    @ConditionalOnMissingBean(DroolsInferenceEngine.class)
    public DroolsInferenceEngine droolsInferenceEngine(DroolsRuleCompiler ruleCompiler,
                                                        ArtifactStore artifactStore,
                                                        ComputeGraphConfigService configService) {
        return new DroolsInferenceEngine(ruleCompiler, artifactStore, configService.getMaxRuleFiringsTotal());
    }
}
