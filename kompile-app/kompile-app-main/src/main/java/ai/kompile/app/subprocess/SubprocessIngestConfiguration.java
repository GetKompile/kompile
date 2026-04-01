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

package ai.kompile.app.subprocess;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;

import java.util.List;

/**
 * Minimal Spring configuration for the ingest subprocess.
 *
 * This configuration creates a lightweight context that only includes the beans
 * necessary for document ingestion:
 * - Document loaders
 * - Text chunkers
 * - Embedding model
 * - Indexer service
 * - Vector store
 *
 * ARCHITECTURE:
 * We use @Import to whitelist ONLY the auto-configurations we need (e.g., JacksonAutoConfiguration
 * for ObjectMapper). This is a whitelist approach - we explicitly import what we need rather than
 * using @EnableAutoConfiguration which imports everything and then tries to exclude.
 *
 * This prevents modules with JPA dependencies (OAuth, Orchestrator, etc.) from being loaded,
 * since their auto-configurations are never imported in the first place.
 */
@Configuration
@ConditionalOnProperty(name = "kompile.subprocess.mode", havingValue = "true", matchIfMissing = false)
@ComponentScan(
    basePackages = {
        // Core interfaces and implementations
        "ai.kompile.core",
        // Loaders
        "ai.kompile.loader",
        // Chunkers
        "ai.kompile.app.core.chunking",
        // Anserini embedding (if enabled)
        "ai.kompile.embedding.anserini",
        // Anserini indexer
        "ai.kompile.anserini",
        // Vector store
        "ai.kompile.vectorstore.anserini",
        // Model manager
        "ai.kompile.modelmanager"
    },
    excludeFilters = {
        // Exclude web-related components
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Controller"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WebSocket.*"),
        // Exclude scheduling-related components
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Scheduler.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Scheduled.*"),
        // Exclude services that depend on web/database
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*IngestEventService.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*IndexingJobHistoryService.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*MemoryWatchdogService.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*DocumentIngestService.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*BroadcasterService.*"),
        // Exclude beans that depend on DocumentLoadingService (not available in subprocess)
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*DocumentRetriever.*"),
        // Exclude Anserini components with complex dependencies
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*AnseriniSearchService.*")
    }
)
@EnableConfigurationProperties
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
// WHITELIST approach: Import ONLY the specific auto-configurations we need
// This replaces @EnableAutoConfiguration which would import everything
@Import({
    JacksonAutoConfiguration.class  // Provides ObjectMapper
    // Add other specific auto-configs here if needed in the future
})
public class SubprocessIngestConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessIngestConfiguration.class);

    /**
     * Creates the IngestPipelineRunner bean.
     */
    @Bean
    public IngestPipelineRunner ingestPipelineRunner(
            @Autowired List<DocumentLoader> documentLoaders,
            @Autowired List<TextChunker> textChunkers,
            @Autowired List<EmbeddingModel> embeddingModels,
            @Autowired List<IndexerService> indexerServices,
            @Autowired List<VectorStore> vectorStores
    ) {
        logger.info("Creating IngestPipelineRunner with {} loaders, {} chunkers, {} embedding models, {} indexers, {} vector stores",
                   documentLoaders.size(), textChunkers.size(), embeddingModels.size(),
                   indexerServices.size(), vectorStores.size());

        // Select non-NoOp embedding model if available
        EmbeddingModel embeddingModel = embeddingModels.stream()
            .filter(e -> !(e instanceof NoOpEmbeddingModelImpl))
            .filter(e -> !e.getClass().getSimpleName().contains("NoOp"))
            .findFirst()
            .orElse(embeddingModels.isEmpty() ? null : embeddingModels.get(0));

        if (embeddingModel != null) {
            logger.info("Using embedding model: {}", embeddingModel.getClass().getSimpleName());
        } else {
            logger.warn("No embedding model available");
        }

        // Select non-NoOp indexer service if available
        IndexerService indexerService = indexerServices.stream()
            .filter(s -> !(s instanceof NoOpIndexerService))
            .findFirst()
            .orElse(indexerServices.isEmpty() ? null : indexerServices.get(0));

        if (indexerService != null) {
            logger.info("Using indexer service: {}", indexerService.getClass().getSimpleName());
        } else {
            logger.warn("No indexer service available");
        }

        // Select non-NoOp vector store if available
        VectorStore vectorStore = vectorStores.stream()
            .filter(v -> !(v instanceof NoOpVectorStoreImpl))
            .findFirst()
            .orElse(vectorStores.isEmpty() ? null : vectorStores.get(0));

        if (vectorStore != null) {
            logger.info("Using vector store: {}", vectorStore.getClass().getSimpleName());
        } else {
            logger.warn("No vector store available");
        }

        return new IngestPipelineRunner(documentLoaders, textChunkers, embeddingModel, indexerService, vectorStore);
    }

    /**
     * Provides fallback NoOp embedding model only if no other embedding model is available.
     * This ensures the real Anserini embedding model is preferred when present.
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel fallbackEmbeddingModel() {
        logger.warn("No EmbeddingModel found in context, using NoOpEmbeddingModelImpl fallback");
        return new NoOpEmbeddingModelImpl();
    }

    /**
     * Provides fallback NoOp indexer service only if no other indexer is available.
     */
    @Bean
    @ConditionalOnMissingBean(IndexerService.class)
    public IndexerService fallbackIndexerService() {
        logger.warn("No IndexerService found in context, using NoOpIndexerService fallback");
        return new NoOpIndexerService();
    }

    /**
     * Provides fallback NoOp vector store only if no other vector store is available.
     */
    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore fallbackVectorStore() {
        logger.warn("No VectorStore found in context, using NoOpVectorStoreImpl fallback");
        return new NoOpVectorStoreImpl();
    }

    /**
     * Provides a minimal DocumentLoadingService for subprocess mode.
     * The subprocess doesn't need to load documents from configured sources -
     * documents are already loaded and passed via the pipeline.
     * This is just to satisfy AnseriniIndexerServiceImpl's dependency.
     */
    @Bean
    public ai.kompile.core.loaders.DocumentLoadingService subprocessDocumentLoadingService() {
        return new ai.kompile.core.loaders.DocumentLoadingService() {
            @Override
            public java.util.List<org.springframework.ai.document.Document> loadAllConfiguredDocuments() {
                return java.util.List.of(); // Subprocess doesn't use this
            }

            @Override
            public java.util.List<org.springframework.ai.document.Document> loadDocumentsFromSource(
                    ai.kompile.core.loaders.DocumentSourceDescriptor sourceDescriptor, String loaderName) {
                return java.util.List.of(); // Subprocess doesn't use this
            }

            @Override
            public java.util.Map<String, Object> loadDocumentsBatch(
                    java.util.List<BatchLoadRequestItem> sourceRequests, String defaultLoaderName) {
                return java.util.Map.of(); // Subprocess doesn't use this
            }
        };
    }
}
