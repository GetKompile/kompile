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

package ai.kompile.vectorstore.anserini;

import ai.kompile.core.freshness.DocumentFreshnessScorer;
import ai.kompile.core.reranking.RerankerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.kompile.core.embeddings.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Auto-configuration for Anserini VectorStore.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl")
@ConditionalOnProperty(name = "kompile.vectorstore.anserini.enabled", havingValue = "true", matchIfMissing = true)
public class AnseriniVectorStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AnseriniVectorStoreAutoConfiguration.class);

    /**
     * Self-contained fallback: auto-configurations activate in EVERY Spring context
     * (subprocess contexts included) regardless of component scanning, but
     * {@link AnseriniVectorStoreProperties} is a plain {@code @Component} in this
     * package — contexts that don't scan it (e.g. the graph-matrix subprocess,
     * which scans knowledgegraph/core only) failed with NoSuchBeanDefinition.
     * The main context's scanned bean wins via {@code @ConditionalOnMissingBean}.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(AnseriniVectorStoreProperties.class)
    public AnseriniVectorStoreProperties anseriniVectorStoreProperties(
            org.springframework.core.env.Environment environment) {
        return new AnseriniVectorStoreProperties(environment.getProperty("kompile.data.dir"));
    }

    @Bean
    public AnseriniVectorStoreImpl anseriniVectorStore(
            AnseriniVectorStoreProperties properties,
            @Lazy EmbeddingModel embeddingModel,
            @Autowired(required = false) RerankerService rerankerService,
            @Autowired(required = false) DocumentFreshnessScorer freshnessScorer) {
        log.info("=== AnseriniVectorStoreAutoConfiguration ACTIVATED ===");
        log.info("Creating AnseriniVectorStoreImpl bean:");
        log.info("  - index path: {}", properties.getIndexPath());
        log.info("  - persistence enabled: {}", properties.isPersistenceEnabled());
        log.info("  - embedding model: {} (lazy proxy - will initialize on first use)",
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL");
        log.info("  - reranker service: {}", rerankerService != null ? rerankerService.getClass().getSimpleName() : "NULL");
        log.info("  - freshness scorer: {}", freshnessScorer != null ? "enabled" : "disabled");
        AnseriniVectorStoreImpl vectorStore = new AnseriniVectorStoreImpl(properties, embeddingModel, rerankerService);
        if (freshnessScorer != null) {
            vectorStore.setFreshnessScorer(freshnessScorer);
        }
        return vectorStore;
    }
}
