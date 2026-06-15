/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.metrics;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.metrics.binder.ChatMetrics;
import ai.kompile.metrics.binder.CrawlMetrics;
import ai.kompile.metrics.binder.EmbeddingModelMetrics;
import ai.kompile.metrics.binder.GuardrailMetrics;
import ai.kompile.metrics.binder.IngestMetrics;
import ai.kompile.metrics.binder.JobHistoryMetrics;
import ai.kompile.metrics.binder.KnowledgeGraphMetrics;
import ai.kompile.metrics.binder.LlmMetrics;
import ai.kompile.metrics.binder.McpToolMetrics;
import ai.kompile.metrics.binder.Nd4jMemoryMetrics;
import ai.kompile.metrics.binder.RetrievalObservabilityMetrics;
import ai.kompile.metrics.binder.VectorStoreMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Kompile observability metrics.
 * Registers Micrometer meter binders for each component when present.
 * Enabled by default; disable with {@code kompile.metrics.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(name = "kompile.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class KompileMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public EmbeddingModelMetrics embeddingModelMetrics(MeterRegistry registry, EmbeddingModel embeddingModel) {
        return new EmbeddingModelMetrics(registry, embeddingModel);
    }

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public VectorStoreMetrics vectorStoreMetrics(MeterRegistry registry, VectorStore vectorStore) {
        return new VectorStoreMetrics(registry, vectorStore);
    }

    @Bean
    @ConditionalOnBean(DocumentRetriever.class)
    public RetrievalObservabilityMetrics retrievalObservabilityMetrics(MeterRegistry registry) {
        return new RetrievalObservabilityMetrics(registry);
    }

    @Bean
    @ConditionalOnBean(LanguageModel.class)
    public LlmMetrics llmMetrics(MeterRegistry registry) {
        return new LlmMetrics(registry);
    }

    @Bean
    public IngestMetrics ingestMetrics(MeterRegistry registry) {
        return new IngestMetrics(registry);
    }

    @Bean
    public Nd4jMemoryMetrics nd4jMemoryMetrics(MeterRegistry registry) {
        return new Nd4jMemoryMetrics(registry);
    }

    @Bean
    public CrawlMetrics crawlMetrics(MeterRegistry registry) {
        return new CrawlMetrics(registry);
    }

    @Bean
    public JobHistoryMetrics jobHistoryMetrics(MeterRegistry registry) {
        return new JobHistoryMetrics(registry);
    }

    @Bean
    public ChatMetrics chatMetrics(MeterRegistry registry) {
        return new ChatMetrics(registry);
    }

    @Bean
    public KnowledgeGraphMetrics knowledgeGraphMetrics(MeterRegistry registry) {
        return new KnowledgeGraphMetrics(registry);
    }

    @Bean
    public GuardrailMetrics guardrailMetrics(MeterRegistry registry) {
        return new GuardrailMetrics(registry);
    }

    @Bean
    public McpToolMetrics mcpToolMetrics(MeterRegistry registry) {
        return new McpToolMetrics(registry);
    }
}
