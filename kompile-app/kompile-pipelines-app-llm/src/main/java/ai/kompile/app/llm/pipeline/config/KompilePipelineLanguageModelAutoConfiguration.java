/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// Path: kompile-app-llm-pipeline/src/main/java/ai/kompile/app/llm/pipeline/config/KompilePipelineLanguageModelAutoConfiguration.java
package ai.kompile.app.llm.pipeline.config;

import ai.kompile.app.llm.pipeline.KompilePipelineLanguageModelImpl;
// Import the static inner class for bean definition
import ai.kompile.app.llm.pipeline.KompilePipelineLanguageModelImpl.KompilePipelineProviderService;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.pipelines.framework.api.PipelineExecutor; // Still needed for defaultPipelineExecutor bean
import ai.kompile.pipelines.framework.api.context.*;
import ai.kompile.pipelines.framework.api.data.DataFactory;
// Import SequencePipeline to instantiate it for the default executor
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline; // Assuming this path
import ai.kompile.pipelines.framework.api.StepConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Configuration
@ConditionalOnClass({
        LanguageModel.class,
        KompilePipelineLanguageModelImpl.class,
        // PipelineExecutor.class, // KompilePipelineLanguageModelImpl no longer takes it in constructor
        DataFactory.class,
        ObjectMapper.class,
        Metrics.class,
        Profiler.class,
        SequencePipeline.class // For defaultPipelineExecutor
})
// This property enables the entire configuration for the pipeline-based LLM
@ConditionalOnProperty(prefix = "kompile.langmodel.pipeline", name = "enabled", havingValue = "true", matchIfMissing = false)
public class KompilePipelineLanguageModelAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(KompilePipelineLanguageModelAutoConfiguration.class);

    // This bean provides a default PipelineExecutor if no other specific one is defined.
    // It's not directly injected into KompilePipelineLanguageModelImpl anymore,
    // but can be useful for other parts of the application or if KompilePipelineLanguageModelImpl
    // were to be refactored to use specific executors for its internal tool pipelines.
    @Bean
    @Qualifier("sequencePipelineExecutor") // Retain qualifier in case it's used elsewhere
    @ConditionalOnMissingBean(name = "sequencePipelineExecutor")
    public PipelineExecutor defaultPipelineExecutor(DataFactory dataFactory) { // Added DataFactory dependency
        try {
            logger.info("Creating default SequencePipelineExecutor bean with qualifier 'sequencePipelineExecutor'. This executor will be for a default empty pipeline.");
            List<StepConfig> emptySteps = Collections.emptyList();
            // Assuming SequencePipeline constructor takes (id, steps) or (id, steps, dataFactory)
            // If it needs dataFactory, it should be passed.
            // For now, assuming the basic constructor is sufficient if Data.Factory.get() is reliable,
            // otherwise, the SequencePipeline might need its own DataFactory.
            // Let's assume SequencePipeline can internally get DataFactory or is simple enough.
            SequencePipeline defaultEmptyPipeline = new SequencePipeline("default-empty-pipeline", emptySteps);
            // SequencePipelineExecutor constructor might also need DataFactory explicitly
            return new ai.kompile.pipelines.framework.runtime.pipeline.SequencePipelineExecutor(defaultEmptyPipeline, true, dataFactory, null, null);
        } catch (Exception e) {
            logger.error("Failed to create default SequencePipelineExecutor with qualifier 'sequencePipelineExecutor'", e);
            throw new RuntimeException("Failed to create default SequencePipelineExecutor for an empty pipeline", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public DataFactory kompileDataFactory() {
        // User must provide their concrete DataFactory implementation.
        // This is a placeholder that will throw an error if not replaced.
        // Example: return new ai.kompile.pipelines.framework.core.data.JDataFactory();
        logger.error("CRITICAL: No concrete DataFactory bean is defined. " +
                "The application will fail to start unless a bean returning a " +
                "concrete implementation (e.g., JDataFactory) is provided for DataFactory.");
        throw new IllegalStateException("A concrete and working DataFactory bean definition is required. " +
                "Please provide a @Bean method that returns your DataFactory implementation.");
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper kompileObjectMapper() {
        logger.info("Providing default ObjectMapper bean.");
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public Metrics kompileMetrics() {
        logger.info("Providing NoOpMetrics bean as a fallback. Replace with your actual metrics implementation if available.");
        // Using the NoOpMetrics class if it's defined in the core context package
        // If not, the inline definition can be used or ai.kompile.pipelines.framework.core.context.NoOpMetrics.INSTANCE
        try {
            // Attempt to use a standalone NoOpMetrics if available
            Class<?> noOpMetricsClass = Class.forName("ai.kompile.pipelines.framework.core.context.NoOpMetrics");
            java.lang.reflect.Field instanceField = noOpMetricsClass.getDeclaredField("INSTANCE");
            return (Metrics) instanceField.get(null);
        } catch (Exception e) {
            logger.warn("Standalone ai.kompile.pipelines.framework.core.context.NoOpMetrics not found, using inline NoOpMetricsProvider.", e);
            // Inline NoOpMetricsProvider as a fallback
            class NoOpMetricsProvider implements Metrics {
                private static class NoOpCounterImpl implements Counter { @Override public void increment() {} @Override public void increment(double amount) {} @Override public double count() { return 0; } @Override public String name() { return "noop"; } @Override public String help() { return ""; } @Override public String[] tags() { return new String[0];}}
                private static class NoOpTimerImpl implements Timer { private static class NoOpSampleImpl implements Timer.Sample { @Override public long stop() { return 0; } } private final Sample sample = new NoOpSampleImpl(); @Override public Sample start() { return sample; } @Override public void record(long amount, java.util.concurrent.TimeUnit unit) {} @Override public <T> T record(java.util.concurrent.Callable<T> f) throws Exception { return f.call(); } @Override public void record(Runnable f) { f.run(); } @Override public long totalTime(java.util.concurrent.TimeUnit unit) { return 0; } @Override public long count() { return 0; } @Override public String name() { return "noop"; } @Override public String help() { return ""; } @Override public String[] tags() { return new String[0];}}
                private static class NoOpGaugeImpl implements Gauge { private java.util.function.Supplier<Number> sup = () -> 0; @Override public void setSupplier(java.util.function.Supplier<Number> supplier) {if(supplier!=null)this.sup=supplier;} @Override public double value() { return sup.get().doubleValue(); } @Override public String name() { return "noop"; } @Override public String help() { return ""; } @Override public String[] tags() { return new String[0];}}
                private final Counter counter = new NoOpCounterImpl();
                private final Timer timer = new NoOpTimerImpl();
                private final Gauge gauge = new NoOpGaugeImpl();
                @Override public Counter counter(String name, String help, String... tags) { return counter; }
                @Override public Timer timer(String name, String help, String... tags) { return timer; }
                @Override public Gauge gauge(String name, String help, String... tags) { return gauge; }
            }
            return new NoOpMetricsProvider();
        }
    }

    @Bean
    @ConditionalOnMissingBean(KompilePipelineLanguageModelImpl.KompilePipelineProviderService.class)
    public KompilePipelineLanguageModelImpl.KompilePipelineProviderService kompilePipelineProviderService(
            DataFactory dataFactory
            // If KompilePipelineProviderService needs to be configured by pipelineDefinitionPath
            // or other @Value properties, inject them here and pass to its constructor.
            // For example, the @Value("${kompile.langmodel.pipeline.definitionPath}") String pipelineDefinitionPath
            // could be passed if the provider service needs it for loading pipelines.
    ) {
        logger.info("Creating KompilePipelineProviderService bean.");
        // The constructor for KompilePipelineProviderService in v7 takes DataFactory.
        return new KompilePipelineLanguageModelImpl.KompilePipelineProviderService(dataFactory);
    }

    @Bean
    @ConditionalOnMissingBean(LanguageModel.class)
    public KompilePipelineLanguageModelImpl kompilePipelineLanguageModel(
            // Dependencies for KompilePipelineLanguageModelImpl constructor (from v7):
            DataFactory dataFactory,
            KompilePipelineLanguageModelImpl.KompilePipelineProviderService kompilePipelineProviderService,
            ObjectMapper objectMapper,
            Metrics metrics,
            Optional<Profiler> profilerOpt
            // The @Value annotated properties are method parameters for this bean definition method,
            // but are NOT passed to the KompilePipelineLanguageModelImpl constructor directly in v7.
            // If these properties are needed by KompilePipelineLanguageModelImpl,
            // its constructor must be updated, and they should be passed here.
            // The @ConditionalOnProperty on the class uses "definitionPath", so it's relevant for enabling the bean.
            // @Value("${kompile.langmodel.pipeline.definitionPath}") String pipelineDefinitionPath, // Example if used
            // @Value("${kompile.langmodel.pipeline.promptInputName:prompt}") String promptInputName,
            // @Value("${kompile.langmodel.pipeline.contextDocsInputName:context_docs}") String contextDocsInputName,
            // @Value("${kompile.langmodel.pipeline.responseOutputName:response_text}") String responseOutputName
            // etc.
    ) {
        // The pipelineDefinitionPath is used by @ConditionalOnProperty at the class level.
        // If it or other @Value properties were needed by the KompilePipelineProviderService,
        // they would be injected into its bean method and passed to its constructor.
        // If KompilePipelineLanguageModelImpl itself needs them, its constructor would change.
        // For now, the constructor of v7 is simpler.
        logger.info("Creating KompilePipelineLanguageModelImpl bean (v7 constructor).");

        // This call matches the constructor of KompilePipelineLanguageModelImpl from v7.
        // It does not take PipelineExecutor or the @Value annotated config strings directly.
        return new KompilePipelineLanguageModelImpl(
                dataFactory,
                kompilePipelineProviderService,
                objectMapper,
                metrics,
                profilerOpt
        );
    }
}
