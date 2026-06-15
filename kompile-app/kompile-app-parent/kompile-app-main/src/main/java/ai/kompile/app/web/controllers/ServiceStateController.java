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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.IndexStatusService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.reranking.RerankerType;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service State Controller - provides comprehensive transparency into loaded services.
 * <p>
 * This controller exposes the internal state of all major RAG components including:
 * <ul>
 *   <li>Embedding Model - current model ID, type, dimensions, batch config, shutdown state</li>
 *   <li>Vector Store - index path, document count, encoder/reranker model IDs, fallback status</li>
 *   <li>Reranker Service - default config, supported types, availability</li>
 *   <li>Document Retriever - keyword search status</li>
 * </ul>
 * <p>
 * Use this endpoint to verify that services have properly loaded after archive loading
 * or model switching operations.
 */
@RestController
@RequestMapping("/api/services")
public class ServiceStateController {

    private static final Logger log = LoggerFactory.getLogger(ServiceStateController.class);

    private final List<EmbeddingModel> embeddingModels;
    private final List<VectorStore> vectorStores;
    private final List<DocumentRetriever> documentRetrievers;
    private final RerankerService rerankerService;
    private final IndexStatusService indexStatusService;

    @Autowired
    public ServiceStateController(
            @Autowired(required = false) List<EmbeddingModel> embeddingModels,
            @Autowired(required = false) List<VectorStore> vectorStores,
            @Autowired(required = false) List<DocumentRetriever> documentRetrievers,
            @Autowired(required = false) RerankerService rerankerService,
            @Autowired(required = false) IndexStatusService indexStatusService) {

        this.embeddingModels = embeddingModels != null ? embeddingModels : Collections.emptyList();
        this.vectorStores = vectorStores != null ? vectorStores : Collections.emptyList();
        this.documentRetrievers = documentRetrievers != null ? documentRetrievers : Collections.emptyList();
        this.rerankerService = rerankerService;
        this.indexStatusService = indexStatusService;

        log.info("ServiceStateController initialized - EmbeddingModels: {}, VectorStores: {}, DocumentRetrievers: {}, RerankerService: {}, IndexStatusService: {}",
                this.embeddingModels.size(),
                this.vectorStores.size(),
                this.documentRetrievers.size(),
                this.rerankerService != null ? "available" : "N/A",
                this.indexStatusService != null ? "available" : "N/A");
    }

    /**
     * Get comprehensive state of ALL loaded services.
     * This is the primary endpoint for verifying service state after archive loading.
     */
    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getServiceState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("timestamp", Instant.now().toString());

        // Embedding Model State
        state.put("embeddingModel", getEmbeddingModelState());

        // Vector Store State
        state.put("vectorStore", getVectorStoreState());

        // Reranker Service State
        state.put("reranker", getRerankerState());

        // Document Retriever State
        state.put("documentRetriever", getDocumentRetrieverState());

        // Summary - quick check for all services
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allServicesLoaded", isAllServicesLoaded());
        summary.put("embeddingModelLoaded", isEmbeddingModelLoaded());
        summary.put("vectorStoreLoaded", isVectorStoreLoaded());
        summary.put("rerankerLoaded", rerankerService != null);
        summary.put("documentRetrieverLoaded", isDocumentRetrieverLoaded());
        state.put("summary", summary);

        return ResponseEntity.ok(state);
    }

    /**
     * Get detailed embedding model state.
     */
    @GetMapping("/state/embedding")
    public ResponseEntity<Map<String, Object>> getEmbeddingState() {
        return ResponseEntity.ok(getEmbeddingModelState());
    }

    /**
     * Get detailed vector store state.
     */
    @GetMapping("/state/vector-store")
    public ResponseEntity<Map<String, Object>> getVectorStoreStateEndpoint() {
        return ResponseEntity.ok(getVectorStoreState());
    }

    /**
     * Get detailed reranker service state.
     */
    @GetMapping("/state/reranker")
    public ResponseEntity<Map<String, Object>> getRerankerStateEndpoint() {
        return ResponseEntity.ok(getRerankerState());
    }

    /**
     * Get detailed document retriever state.
     */
    @GetMapping("/state/document-retriever")
    public ResponseEntity<Map<String, Object>> getDocumentRetrieverStateEndpoint() {
        return ResponseEntity.ok(getDocumentRetrieverState());
    }

    /**
     * Get index status - whether indices are loaded and available.
     * This endpoint provides critical information about whether the system
     * can actually perform searches.
     */
    @GetMapping("/index-status")
    public ResponseEntity<IndexStatusService.IndexStatus> getIndexStatus() {
        if (indexStatusService == null) {
            return ResponseEntity.ok(IndexStatusService.IndexStatus.builder()
                    .vectorStorePath("N/A")
                    .vectorStoreAvailable(false)
                    .vectorStoreNoOp(true)
                    .vectorDocumentCount(0)
                    .vectorIndexLoaded(false)
                    .vectorIndexEmpty(true)
                    .keywordIndexPath("N/A")
                    .keywordIndexAvailable(false)
                    .indexerServiceNoOp(true)
                    .keywordDocumentCount(0)
                    .keywordIndexLoaded(false)
                    .keywordIndexEmpty(true)
                    .availableVectorIndices(Collections.emptyList())
                    .availableKarchFiles(Collections.emptyList())
                    .anyIndexLoaded(false)
                    .warningMessage("IndexStatusService not available")
                    .build());
        }
        return ResponseEntity.ok(indexStatusService.getStatus());
    }

    /**
     * Force refresh the index status cache and return fresh status.
     */
    @PostMapping("/index-status/refresh")
    public ResponseEntity<IndexStatusService.IndexStatus> refreshIndexStatus() {
        if (indexStatusService == null) {
            return ResponseEntity.ok(IndexStatusService.IndexStatus.builder()
                    .vectorStorePath("N/A")
                    .vectorStoreAvailable(false)
                    .vectorStoreNoOp(true)
                    .vectorDocumentCount(0)
                    .vectorIndexLoaded(false)
                    .vectorIndexEmpty(true)
                    .keywordIndexPath("N/A")
                    .keywordIndexAvailable(false)
                    .indexerServiceNoOp(true)
                    .keywordDocumentCount(0)
                    .keywordIndexLoaded(false)
                    .keywordIndexEmpty(true)
                    .availableVectorIndices(Collections.emptyList())
                    .availableKarchFiles(Collections.emptyList())
                    .anyIndexLoaded(false)
                    .warningMessage("IndexStatusService not available")
                    .build());
        }
        return ResponseEntity.ok(indexStatusService.refreshStatus());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING MODEL STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> getEmbeddingModelState() {
        Map<String, Object> state = new LinkedHashMap<>();

        if (embeddingModels.isEmpty()) {
            state.put("loaded", false);
            state.put("reason", "No embedding models configured");
            return state;
        }

        // Find primary (non-NoOp) embedding model
        EmbeddingModel primary = embeddingModels.stream()
                .filter(m -> !(m instanceof NoOpEmbeddingModelImpl))
                .findFirst()
                .orElse(embeddingModels.isEmpty() ? null : embeddingModels.get(0));

        if (primary == null || primary instanceof NoOpEmbeddingModelImpl) {
            state.put("loaded", false);
            state.put("reason", "Only NoOp embedding model available");
            state.put("totalModels", embeddingModels.size());
            return state;
        }

        state.put("loaded", true);
        state.put("totalModels", embeddingModels.size());

        // Primary model info
        Map<String, Object> primaryState = new LinkedHashMap<>();
        primaryState.put("class", primary.getClass().getSimpleName());
        primaryState.put("fullClass", primary.getClass().getName());
        primaryState.put("dimensions", primary.dimensions());
        primaryState.put("optimalBatchSize", primary.getOptimalBatchSize());
        primaryState.put("maxBatchSize", primary.getMaxBatchSize());

        // Anserini-specific details
        if (primary instanceof AnseriniEmbeddingModelImpl) {
            AnseriniEmbeddingModelImpl anserini = (AnseriniEmbeddingModelImpl) primary;
            primaryState.put("modelIdentifier", anserini.getModelIdentifier());
            primaryState.put("activeModelId", anserini.getActiveModelId());
            primaryState.put("encoderType", anserini.getEncoderType() != null ? anserini.getEncoderType() : "UNKNOWN");
            primaryState.put("modelType", anserini.getModelType());
            primaryState.put("usesAutoModelManagement", anserini.usesAutoModelManagement());
            primaryState.put("isShuttingDown", anserini.isShuttingDown());
            primaryState.put("modelInfo", anserini.getModelInfo());

            // Batch configuration
            Map<String, Object> batchConfig = new LinkedHashMap<>();
            batchConfig.put("optimal512Tokens", anserini.getOptimalBatchSize());
            batchConfig.put("max512Tokens", anserini.getMaxBatchSize());
            batchConfig.put("optimalFor256Tokens", anserini.getOptimalBatchSizeForSeqLength(256));
            batchConfig.put("optimalFor128Tokens", anserini.getOptimalBatchSizeForSeqLength(128));
            primaryState.put("batchConfig", batchConfig);

            // Current batch info if processing
            EmbeddingModel.BatchInfo batchInfo = anserini.getCurrentBatchInfo();
            if (batchInfo != null && batchInfo.numChunks() > 0) {
                Map<String, Object> currentBatch = new LinkedHashMap<>();
                currentBatch.put("numChunks", batchInfo.numChunks());
                currentBatch.put("maxSeqLength", batchInfo.maxSeqLength());
                currentBatch.put("embeddingDim", batchInfo.embeddingDim());
                currentBatch.put("totalTokens", batchInfo.totalTokens());
                currentBatch.put("inputShape", batchInfo.inputShape());
                currentBatch.put("outputShape", batchInfo.outputShape());
                currentBatch.put("step", batchInfo.step());
                currentBatch.put("stepStartTimeMs", batchInfo.stepStartTimeMs());
                primaryState.put("currentBatch", currentBatch);
            } else {
                primaryState.put("currentBatch", null);
            }
        }

        state.put("primary", primaryState);

        // List all models
        List<Map<String, Object>> allModels = new ArrayList<>();
        for (int i = 0; i < embeddingModels.size(); i++) {
            EmbeddingModel model = embeddingModels.get(i);
            Map<String, Object> modelInfo = new LinkedHashMap<>();
            modelInfo.put("index", i);
            modelInfo.put("class", model.getClass().getSimpleName());
            modelInfo.put("isNoOp", model instanceof NoOpEmbeddingModelImpl);
            modelInfo.put("dimensions", model.dimensions());

            if (model instanceof AnseriniEmbeddingModelImpl) {
                AnseriniEmbeddingModelImpl anserini = (AnseriniEmbeddingModelImpl) model;
                modelInfo.put("modelIdentifier", anserini.getModelIdentifier());
                modelInfo.put("encoderType", anserini.getEncoderType() != null ? anserini.getEncoderType() : "UNKNOWN");
            }

            allModels.add(modelInfo);
        }
        state.put("allModels", allModels);

        return state;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VECTOR STORE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> getVectorStoreState() {
        Map<String, Object> state = new LinkedHashMap<>();

        if (vectorStores.isEmpty()) {
            state.put("loaded", false);
            state.put("reason", "No vector stores configured");
            return state;
        }

        // Find primary (non-NoOp) vector store
        VectorStore primary = vectorStores.stream()
                .filter(v -> !(v instanceof NoOpVectorStoreImpl))
                .findFirst()
                .orElse(vectorStores.isEmpty() ? null : vectorStores.get(0));

        if (primary == null || primary instanceof NoOpVectorStoreImpl) {
            state.put("loaded", false);
            state.put("reason", "Only NoOp vector store available");
            state.put("totalStores", vectorStores.size());
            return state;
        }

        state.put("loaded", true);
        state.put("totalStores", vectorStores.size());

        // Primary store info
        Map<String, Object> primaryState = new LinkedHashMap<>();
        primaryState.put("class", primary.getClass().getSimpleName());
        primaryState.put("fullClass", primary.getClass().getName());
        primaryState.put("isAvailable", primary.isVectorStoreAvailable());
        primaryState.put("path", primary.getVectorStorePath());

        // Anserini-specific details
        if (primary instanceof AnseriniVectorStoreImpl) {
            AnseriniVectorStoreImpl anserini = (AnseriniVectorStoreImpl) primary;
            primaryState.put("indexPath", anserini.getIndexPath());
            primaryState.put("usingFallbackIndex", anserini.isUsingFallbackIndex());
            primaryState.put("isDestroyed", anserini.isDestroyed());
            primaryState.put("rerankingAvailable", anserini.isRerankingAvailable());

            // Model tracking
            Map<String, Object> modelTracking = new LinkedHashMap<>();
            modelTracking.put("encoderModelId", anserini.getEncoderModelId());
            modelTracking.put("rerankerModelId", anserini.getRerankerModelId());
            modelTracking.put("modelConfiguration", anserini.getModelConfiguration());
            primaryState.put("modelTracking", modelTracking);

            // Document count
            try {
                long docCount = anserini.getApproxVectorCount();
                primaryState.put("documentCount", docCount);
                primaryState.put("indexPopulated", docCount > 0);
            } catch (Exception e) {
                primaryState.put("documentCount", -1);
                primaryState.put("documentCountError", e.getMessage());
            }

            // Warnings
            List<String> warnings = new ArrayList<>();
            if (anserini.isUsingFallbackIndex()) {
                warnings.add("Using fallback temporary index - data will NOT be persisted!");
            }
            if (anserini.isDestroyed()) {
                warnings.add("Vector store has been destroyed and is no longer usable");
            }
            String encoderModelId = anserini.getEncoderModelId();
            if (encoderModelId == null || encoderModelId.isEmpty() || "not set".equals(encoderModelId)) {
                warnings.add("Encoder model ID not set - index may be empty or newly initialized");
            }
            if (!warnings.isEmpty()) {
                primaryState.put("warnings", warnings);
            }
        }

        state.put("primary", primaryState);

        // List all stores
        List<Map<String, Object>> allStores = new ArrayList<>();
        for (int i = 0; i < vectorStores.size(); i++) {
            VectorStore store = vectorStores.get(i);
            Map<String, Object> storeInfo = new LinkedHashMap<>();
            storeInfo.put("index", i);
            storeInfo.put("class", store.getClass().getSimpleName());
            storeInfo.put("isNoOp", store instanceof NoOpVectorStoreImpl);
            storeInfo.put("path", store.getVectorStorePath());

            if (store instanceof AnseriniVectorStoreImpl) {
                AnseriniVectorStoreImpl anserini = (AnseriniVectorStoreImpl) store;
                storeInfo.put("encoderModelId", anserini.getEncoderModelId());
                storeInfo.put("usingFallback", anserini.isUsingFallbackIndex());
            }

            allStores.add(storeInfo);
        }
        state.put("allStores", allStores);

        return state;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RERANKER SERVICE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> getRerankerState() {
        Map<String, Object> state = new LinkedHashMap<>();

        if (rerankerService == null) {
            state.put("loaded", false);
            state.put("reason", "RerankerService not configured");
            return state;
        }

        state.put("loaded", true);
        state.put("class", rerankerService.getClass().getSimpleName());
        state.put("fullClass", rerankerService.getClass().getName());

        // Supported types
        List<RerankerType> supportedTypes = rerankerService.getSupportedTypes();
        List<Map<String, Object>> typesList = new ArrayList<>();
        for (RerankerType type : supportedTypes) {
            Map<String, Object> typeInfo = new LinkedHashMap<>();
            typeInfo.put("id", type.getId());
            typeInfo.put("name", type.name());
            typeInfo.put("description", type.getDescription());
            typeInfo.put("supported", rerankerService.isSupported(type));
            typesList.add(typeInfo);
        }
        state.put("supportedTypes", typesList);
        state.put("supportedTypeCount", supportedTypes.size());

        // Default config
        RerankerConfig defaultConfig = rerankerService.getDefaultConfig();
        if (defaultConfig != null) {
            Map<String, Object> configInfo = new LinkedHashMap<>();
            configInfo.put("type", defaultConfig.getType() != null ? defaultConfig.getType().getId() : "none");
            configInfo.put("enabled", defaultConfig.isEnabled());
            configInfo.put("fbDocs", defaultConfig.getFbDocs());
            configInfo.put("fbTerms", defaultConfig.getFbTerms());
            configInfo.put("topK", defaultConfig.getTopK());
            state.put("defaultConfig", configInfo);
        }

        return state;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT RETRIEVER STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> getDocumentRetrieverState() {
        Map<String, Object> state = new LinkedHashMap<>();

        if (documentRetrievers.isEmpty()) {
            state.put("loaded", false);
            state.put("reason", "No document retrievers configured");
            return state;
        }

        // Find primary (non-NoOp) retriever
        DocumentRetriever primary = documentRetrievers.stream()
                .filter(r -> !(r instanceof NoOpDocumentRetrieverImpl))
                .findFirst()
                .orElse(documentRetrievers.isEmpty() ? null : documentRetrievers.get(0));

        if (primary == null || primary instanceof NoOpDocumentRetrieverImpl) {
            state.put("loaded", false);
            state.put("reason", "Only NoOp document retriever available");
            state.put("totalRetrievers", documentRetrievers.size());
            return state;
        }

        state.put("loaded", true);
        state.put("totalRetrievers", documentRetrievers.size());

        // Primary retriever info
        Map<String, Object> primaryState = new LinkedHashMap<>();
        primaryState.put("class", primary.getClass().getSimpleName());
        primaryState.put("fullClass", primary.getClass().getName());
        state.put("primary", primaryState);

        // List all retrievers
        List<Map<String, Object>> allRetrievers = new ArrayList<>();
        for (int i = 0; i < documentRetrievers.size(); i++) {
            DocumentRetriever retriever = documentRetrievers.get(i);
            Map<String, Object> retrieverInfo = new LinkedHashMap<>();
            retrieverInfo.put("index", i);
            retrieverInfo.put("class", retriever.getClass().getSimpleName());
            retrieverInfo.put("isNoOp", retriever instanceof NoOpDocumentRetrieverImpl);
            allRetrievers.add(retrieverInfo);
        }
        state.put("allRetrievers", allRetrievers);

        return state;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isEmbeddingModelLoaded() {
        return embeddingModels.stream()
                .anyMatch(m -> !(m instanceof NoOpEmbeddingModelImpl));
    }

    private boolean isVectorStoreLoaded() {
        return vectorStores.stream()
                .anyMatch(v -> !(v instanceof NoOpVectorStoreImpl));
    }

    private boolean isDocumentRetrieverLoaded() {
        return documentRetrievers.stream()
                .anyMatch(r -> !(r instanceof NoOpDocumentRetrieverImpl));
    }

    private boolean isAllServicesLoaded() {
        return isEmbeddingModelLoaded() && isVectorStoreLoaded();
    }
}
