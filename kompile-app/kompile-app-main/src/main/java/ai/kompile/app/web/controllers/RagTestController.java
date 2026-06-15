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

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.util.FieldNames;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.reranking.RerankerType;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG Testing Controller - provides endpoints to test RAG components.
 *
 * Endpoints:
 * - GET /api/rag/test/query - Run a test query against all RAG components
 * - GET /api/rag/test/status - Check status of RAG components
 * - GET /api/rag/test/embed - Test embedding generation
 * - GET /api/rag/test/rerankers - List available reranker types
 * - GET /api/rag/test/query-with-reranking - Run a query with reranking
 * - GET /api/rag/test/cross-encoder-models - List available cross-encoder models
 * - POST /api/rag/test/cross-encoder-models/{modelId}/download - Download a cross-encoder model
 */
@RestController
@RequestMapping("/api/rag/test")
public class RagTestController {

    private static final Logger log = LoggerFactory.getLogger(RagTestController.class);

    private final DocumentRetriever keywordRetriever;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final RerankerService rerankerService;
    private final KompileModelManager modelManager;
    private final GraphRagService graphRagService;

    @Autowired
    public RagTestController(
            List<DocumentRetriever> documentRetrievers,
            List<VectorStore> vectorStores,
            List<EmbeddingModel> embeddingModels,
            @Autowired(required = false) RerankerService rerankerService,
            @Autowired(required = false) KompileModelManager modelManager,
            @Autowired(required = false) GraphRagService graphRagService) {

        // Select non-NoOp implementations
        this.keywordRetriever = documentRetrievers.stream()
                .filter(r -> !(r instanceof NoOpDocumentRetrieverImpl))
                .findFirst()
                .orElse(documentRetrievers.isEmpty() ? new NoOpDocumentRetrieverImpl() : documentRetrievers.get(0));

        this.vectorStore = vectorStores.stream()
                .filter(v -> !(v instanceof NoOpVectorStoreImpl))
                .findFirst()
                .orElse(vectorStores.isEmpty() ? new NoOpVectorStoreImpl() : vectorStores.get(0));

        this.embeddingModel = embeddingModels.stream()
                .filter(e -> !(e instanceof NoOpEmbeddingModelImpl))
                .findFirst()
                .orElse(embeddingModels.isEmpty() ? new NoOpEmbeddingModelImpl() : embeddingModels.get(0));

        this.rerankerService = rerankerService;
        this.modelManager = modelManager != null ? modelManager : new KompileModelManager();
        this.graphRagService = graphRagService;

        log.info("RagTestController initialized - KeywordRetriever: {}, VectorStore: {}, EmbeddingModel: {}, RerankerService: {}, GraphRagService: {}",
                this.keywordRetriever.getClass().getSimpleName(),
                this.vectorStore.getClass().getSimpleName(),
                this.embeddingModel.getClass().getSimpleName(),
                this.rerankerService != null ? this.rerankerService.getClass().getSimpleName() : "N/A",
                this.graphRagService != null ? this.graphRagService.getClass().getSimpleName() : "N/A");
    }

    /**
     * Check the status of all RAG components.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Keyword retriever status
        Map<String, Object> keywordStatus = new LinkedHashMap<>();
        keywordStatus.put("class", keywordRetriever.getClass().getSimpleName());
        keywordStatus.put("available", !(keywordRetriever instanceof NoOpDocumentRetrieverImpl));
        status.put("keywordRetriever", keywordStatus);

        // Vector store status
        Map<String, Object> vectorStatus = new LinkedHashMap<>();
        vectorStatus.put("class", vectorStore.getClass().getSimpleName());
        vectorStatus.put("available", !(vectorStore instanceof NoOpVectorStoreImpl));
        status.put("vectorStore", vectorStatus);

        // Embedding model status
        Map<String, Object> embeddingStatus = new LinkedHashMap<>();
        embeddingStatus.put("class", embeddingModel.getClass().getSimpleName());
        embeddingStatus.put("available", !(embeddingModel instanceof NoOpEmbeddingModelImpl));
        status.put("embeddingModel", embeddingStatus);

        // Reranker service status
        Map<String, Object> rerankerStatus = new LinkedHashMap<>();
        rerankerStatus.put("available", rerankerService != null);
        if (rerankerService != null) {
            rerankerStatus.put("class", rerankerService.getClass().getSimpleName());
            rerankerStatus.put("supportedTypes", rerankerService.getSupportedTypes().stream()
                    .map(RerankerType::getId)
                    .collect(Collectors.toList()));
        } else {
            rerankerStatus.put("class", "N/A");
            rerankerStatus.put("supportedTypes", Collections.emptyList());
        }
        status.put("reranker", rerankerStatus);

        // Graph RAG service status
        Map<String, Object> graphRagStatus = new LinkedHashMap<>();
        graphRagStatus.put("available", graphRagService != null);
        if (graphRagService != null) {
            graphRagStatus.put("class", graphRagService.getClass().getSimpleName());
            graphRagStatus.put("searchTypes", Arrays.asList("LOCAL", "GLOBAL"));
        } else {
            graphRagStatus.put("class", "N/A");
            graphRagStatus.put("searchTypes", Collections.emptyList());
        }
        status.put("graphRag", graphRagStatus);

        return ResponseEntity.ok(status);
    }

    /**
     * Test embedding generation.
     * Returns detailed timing breakdown including subprocess overhead vs actual inference time.
     */
    @GetMapping("/embed")
    public ResponseEntity<Map<String, Object>> testEmbed(
            @RequestParam("text") String text) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text);
        result.put("embeddingModel", embeddingModel.getClass().getSimpleName());

        if (embeddingModel instanceof NoOpEmbeddingModelImpl) {
            result.put("error", "No embedding model available");
            return ResponseEntity.ok(result);
        }

        try {
            // Check if we can get detailed timing from AnseriniEmbeddingModelImpl
            if (embeddingModel instanceof ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl anseriniModel) {
                // Use timing-aware method for detailed breakdown
                var timingResult = anseriniModel.embedWithTiming(text);

                if (!timingResult.success()) {
                    result.put("error", timingResult.error());
                    result.put(FieldNames.DURATION_MS, timingResult.totalWallClockMs());
                    return ResponseEntity.ok(result);
                }

                INDArray embedding = timingResult.embedding();
                result.put("dimensions", embedding.length());
                result.put("shape", Arrays.toString(embedding.shape()));

                // Total wall-clock time (what the user experiences)
                result.put(FieldNames.DURATION_MS, timingResult.totalWallClockMs());

                // Detailed timing breakdown
                Map<String, Object> timing = new LinkedHashMap<>();
                timing.put("totalWallClockMs", timingResult.totalWallClockMs());
                timing.put("subprocessInferenceMs", timingResult.subprocessInferenceMs());
                timing.put("subprocessOverheadMs", timingResult.subprocessOverheadMs());
                if (timingResult.totalWallClockMs() > 0) {
                    timing.put("overheadPercent",
                            Math.round(timingResult.subprocessOverheadMs() * 100.0 / timingResult.totalWallClockMs()));
                }
                result.put("timing", timing);

                // Include first 10 values as preview
                double[] preview = new double[Math.min(10, (int) embedding.length())];
                for (int i = 0; i < preview.length; i++) {
                    preview[i] = embedding.getDouble(i);
                }
                result.put("preview", preview);

            } else {
                // Fallback for other embedding models - just wall-clock time
                long startTime = System.currentTimeMillis();
                INDArray embedding = embeddingModel.embed(text);
                long duration = System.currentTimeMillis() - startTime;

                result.put("dimensions", embedding.length());
                result.put("shape", Arrays.toString(embedding.shape()));
                result.put(FieldNames.DURATION_MS, duration);

                // Include first 10 values as preview
                double[] preview = new double[Math.min(10, (int) embedding.length())];
                for (int i = 0; i < preview.length; i++) {
                    preview[i] = embedding.getDouble(i);
                }
                result.put("preview", preview);
            }

        } catch (Exception e) {
            log.error("Error generating embedding", e);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Run a test query against all RAG components.
     */
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> testQuery(
            @RequestParam("q") String query,
            @RequestParam(name = "k", defaultValue = "5") int maxResults,
            @RequestParam(name = "threshold", defaultValue = "0.0") double threshold,
            @RequestParam(name = "keyword", defaultValue = "true") boolean includeKeyword,
            @RequestParam(name = "semantic", defaultValue = "true") boolean includeSemantic) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("maxResults", maxResults);
        result.put("threshold", threshold);

        List<Map<String, Object>> allResults = new ArrayList<>();

        // Keyword search
        if (includeKeyword && !(keywordRetriever instanceof NoOpDocumentRetrieverImpl)) {
            Map<String, Object> keywordResult = new LinkedHashMap<>();
            keywordResult.put("type", "keyword");
            keywordResult.put("retriever", keywordRetriever.getClass().getSimpleName());

            try {
                long startTime = System.currentTimeMillis();
                List<RetrievedDoc> docs = keywordRetriever.retrieveWithDetails(query, maxResults);
                long duration = System.currentTimeMillis() - startTime;

                keywordResult.put(FieldNames.DURATION_MS, duration);
                keywordResult.put("count", docs != null ? docs.size() : 0);

                if (docs != null && !docs.isEmpty()) {
                    List<Map<String, Object>> hits = docs.stream()
                            .filter(d -> d != null && d.getText() != null)
                            .filter(d -> !d.getText().startsWith("Error:"))
                            .map(this::formatRetrievedDoc)
                            .collect(Collectors.toList());
                    keywordResult.put("hits", hits);
                } else {
                    keywordResult.put("hits", Collections.emptyList());
                }

            } catch (Exception e) {
                log.error("Keyword search error", e);
                keywordResult.put("error", e.getMessage());
            }

            allResults.add(keywordResult);
        }

        // Semantic search
        if (includeSemantic && !(vectorStore instanceof NoOpVectorStoreImpl)) {
            Map<String, Object> semanticResult = new LinkedHashMap<>();
            semanticResult.put("type", "semantic");
            semanticResult.put("vectorStore", vectorStore.getClass().getSimpleName());

            try {
                long startTime = System.currentTimeMillis();
                List<Document> docs = vectorStore.similaritySearch(query, maxResults, threshold);
                long duration = System.currentTimeMillis() - startTime;

                semanticResult.put(FieldNames.DURATION_MS, duration);
                semanticResult.put("count", docs != null ? docs.size() : 0);

                if (docs != null && !docs.isEmpty()) {
                    List<Map<String, Object>> hits = docs.stream()
                            .filter(d -> d != null && d.getText() != null)
                            .map(this::formatDocument)
                            .collect(Collectors.toList());
                    semanticResult.put("hits", hits);
                } else {
                    semanticResult.put("hits", Collections.emptyList());
                }

            } catch (Exception e) {
                log.error("Semantic search error", e);
                semanticResult.put("error", e.getMessage());
            }

            allResults.add(semanticResult);
        }

        result.put("results", allResults);

        // Summary
        int totalHits = allResults.stream()
                .filter(r -> r.containsKey("hits"))
                .mapToInt(r -> ((List<?>) r.get("hits")).size())
                .sum();
        result.put("totalHits", totalHits);

        return ResponseEntity.ok(result);
    }

    /**
     * Run a hybrid search and merge results.
     */
    @GetMapping("/hybrid")
    public ResponseEntity<Map<String, Object>> hybridSearch(
            @RequestParam("q") String query,
            @RequestParam(name = "k", defaultValue = "10") int maxResults,
            @RequestParam(name = "threshold", defaultValue = "0.0") double threshold) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("maxResults", maxResults);

        // Collect all unique documents
        Map<String, Map<String, Object>> mergedDocs = new LinkedHashMap<>();

        // Keyword search
        if (!(keywordRetriever instanceof NoOpDocumentRetrieverImpl)) {
            try {
                List<RetrievedDoc> keywordDocs = keywordRetriever.retrieveWithDetails(query, maxResults);
                if (keywordDocs != null) {
                    for (RetrievedDoc doc : keywordDocs) {
                        if (doc != null && doc.getText() != null && !doc.getText().startsWith("Error:")) {
                            String key = doc.getId() != null ? doc.getId() : String.valueOf(doc.getText().hashCode());
                            Map<String, Object> existing = mergedDocs.get(key);
                            if (existing == null) {
                                Map<String, Object> formatted = formatRetrievedDoc(doc);
                                formatted.put("sources", new ArrayList<>(List.of("keyword")));
                                mergedDocs.put(key, formatted);
                            } else {
                                ((List<String>) existing.get("sources")).add("keyword");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Keyword search error in hybrid", e);
                result.put("keywordError", e.getMessage());
            }
        }

        // Semantic search
        if (!(vectorStore instanceof NoOpVectorStoreImpl)) {
            try {
                List<Document> semanticDocs = vectorStore.similaritySearch(query, maxResults, threshold);
                if (semanticDocs != null) {
                    for (Document doc : semanticDocs) {
                        if (doc != null && doc.getText() != null) {
                            String key = doc.getId() != null ? doc.getId() : String.valueOf(doc.getText().hashCode());
                            Map<String, Object> existing = mergedDocs.get(key);
                            if (existing == null) {
                                Map<String, Object> formatted = formatDocument(doc);
                                formatted.put("sources", new ArrayList<>(List.of("semantic")));
                                mergedDocs.put(key, formatted);
                            } else {
                                ((List<String>) existing.get("sources")).add("semantic");
                                // Update score if semantic score is higher
                                Object existingScore = existing.get(FieldNames.SCORE);
                                Object semanticScore = doc.getMetadata() != null ? doc.getMetadata().get(FieldNames.SCORE) : null;
                                if (semanticScore instanceof Number && existingScore instanceof Number) {
                                    if (((Number) semanticScore).doubleValue() > ((Number) existingScore).doubleValue()) {
                                        existing.put(FieldNames.SCORE, semanticScore);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Semantic search error in hybrid", e);
                result.put("semanticError", e.getMessage());
            }
        }

        // Sort by score descending, then by number of sources
        List<Map<String, Object>> sortedResults = mergedDocs.values().stream()
                .sorted((a, b) -> {
                    // First by number of sources (docs found in both searches rank higher)
                    int sourcesCompare = Integer.compare(
                            ((List<?>) b.get("sources")).size(),
                            ((List<?>) a.get("sources")).size()
                    );
                    if (sourcesCompare != 0) return sourcesCompare;

                    // Then by score
                    double scoreA = a.get(FieldNames.SCORE) instanceof Number ? ((Number) a.get(FieldNames.SCORE)).doubleValue() : 0.0;
                    double scoreB = b.get(FieldNames.SCORE) instanceof Number ? ((Number) b.get(FieldNames.SCORE)).doubleValue() : 0.0;
                    return Double.compare(scoreB, scoreA);
                })
                .limit(maxResults)
                .collect(Collectors.toList());

        result.put("hits", sortedResults);
        result.put("totalHits", sortedResults.size());

        return ResponseEntity.ok(result);
    }

    /**
     * Get available reranker types and their configuration options.
     */
    @GetMapping("/rerankers")
    public ResponseEntity<Map<String, Object>> getRerankers() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("available", rerankerService != null);

        List<Map<String, Object>> rerankerTypes = new ArrayList<>();

        // Add all supported reranker types with their descriptions
        for (RerankerType type : RerankerType.values()) {
            if (type != RerankerType.NONE) {
                Map<String, Object> typeInfo = new LinkedHashMap<>();
                typeInfo.put("id", type.getId());
                typeInfo.put("name", type.name());
                typeInfo.put("description", type.getDescription());

                // Add parameters for each type with complete metadata
                List<Map<String, Object>> params = new ArrayList<>();

                // Add common parameters for feedback-based rerankers
                switch (type) {
                    case RM3:
                        params.add(createParam("fbDocs", "Feedback Documents", "number", 10, 1, 50));
                        params.add(createParam("fbTerms", "Feedback Terms", "number", 10, 1, 100));
                        params.add(createParam("originalQueryWeight", "Original Query Weight", "number", 0.5, 0.0, 1.0));
                        params.add(createParam("filterTerms", "Filter Non-Alphanumeric Terms", "boolean", true, null, null));
                        params.add(createParam("outputQuery", "Log Expanded Queries", "boolean", false, null, null));
                        break;
                    case BM25_PRF:
                        params.add(createParam("fbDocs", "Feedback Documents", "number", 10, 1, 50));
                        params.add(createParam("fbTerms", "Feedback Terms", "number", 20, 1, 100));
                        params.add(createParam("k1", "BM25 k1", "number", 0.9, 0.0, 3.0));
                        params.add(createParam("b", "BM25 b", "number", 0.4, 0.0, 1.0));
                        params.add(createParam("newTermWeight", "New Term Weight Boost", "number", 0.2, 0.0, 1.0));
                        params.add(createParam("outputQuery", "Log Expanded Queries", "boolean", false, null, null));
                        break;
                    case ROCCHIO:
                        params.add(createParam("fbDocs", "Feedback Documents", "number", 10, 1, 50));
                        params.add(createParam("fbTerms", "Feedback Terms", "number", 10, 1, 100));
                        params.add(createParam("alpha", "Original Query Weight (alpha)", "number", 1.0, 0.0, 2.0));
                        params.add(createParam("beta", "Positive Feedback Weight (beta)", "number", 0.75, 0.0, 2.0));
                        params.add(createParam("gamma", "Negative Feedback Weight (gamma)", "number", 0.15, 0.0, 1.0));
                        params.add(createParam("useNegative", "Use Negative Feedback", "boolean", false, null, null));
                        params.add(createParam("outputQuery", "Log Expanded Queries", "boolean", false, null, null));
                        break;
                    case AXIOM:
                        params.add(createParam("fbDocs", "Feedback Documents", "number", 10, 1, 50));
                        params.add(createParam("fbTerms", "Feedback Terms", "number", 10, 1, 100));
                        params.add(createParam("r", "Axiom R (Relevance Model Terms)", "number", 20, 1, 100));
                        params.add(createParam("n", "Axiom N (Context Terms)", "number", 30, 1, 100));
                        params.add(createParam("axiomBeta", "Axiom Beta (Expansion Weight)", "number", 0.4, 0.0, 1.0));
                        params.add(createParam("deterministic", "Deterministic Results", "boolean", true, null, null));
                        params.add(createParam("seed", "Random Seed", "number", 42, 0, Long.MAX_VALUE));
                        break;
                    case SCORE_TIES_ADJUSTER:
                        // No parameters needed - just breaks ties deterministically
                        break;
                    case CROSS_ENCODER:
                        params.add(createParam("topK", "Documents to Rerank (-1 = all)", "number", -1, -1, 100));
                        // Add available cross-encoder models
                        Map<String, Object> modelParam = createParam("crossEncoderModel", "Cross-Encoder Model", "select",
                                ModelConstants.getDefaultCrossEncoderModelId(), null, null);
                        modelParam.put("options", ModelConstants.getAvailableCrossEncoderModelIds());
                        params.add(modelParam);
                        break;
                    default:
                        break;
                }
                typeInfo.put("parameters", params);

                // Check if this type is supported by the current service
                typeInfo.put("supported", rerankerService != null && rerankerService.isSupported(type));

                rerankerTypes.add(typeInfo);
            }
        }

        result.put("types", rerankerTypes);

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> createParam(String id, String label, String type, Object defaultValue, Object min, Object max) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("id", id);
        param.put("label", label);
        param.put("type", type);
        param.put("default", defaultValue);
        param.put("min", min);
        param.put("max", max);
        return param;
    }

    /**
     * Run a similarity search with optional reranking.
     */
    @GetMapping("/query-with-reranking")
    public ResponseEntity<Map<String, Object>> queryWithReranking(
            @RequestParam("q") String query,
            @RequestParam(name = "k", defaultValue = "10") int maxResults,
            @RequestParam(name = "threshold", defaultValue = "0.0") double threshold,
            @RequestParam(name = "rerankerType", defaultValue = "none") String rerankerType,
            // Common parameters
            @RequestParam(name = "fbDocs", defaultValue = "10") int fbDocs,
            @RequestParam(name = "fbTerms", defaultValue = "10") int fbTerms,
            @RequestParam(name = "topK", defaultValue = "-1") int topK,
            // RM3 parameters
            @RequestParam(name = "originalQueryWeight", defaultValue = "0.5") float originalQueryWeight,
            @RequestParam(name = "filterTerms", defaultValue = "true") boolean filterTerms,
            @RequestParam(name = "outputQuery", defaultValue = "false") boolean outputQuery,
            // BM25-PRF parameters
            @RequestParam(name = "k1", defaultValue = "0.9") float k1,
            @RequestParam(name = "b", defaultValue = "0.4") float b,
            @RequestParam(name = "newTermWeight", defaultValue = "0.2") float newTermWeight,
            // Rocchio parameters
            @RequestParam(name = "alpha", defaultValue = "1.0") float alpha,
            @RequestParam(name = "beta", defaultValue = "0.75") float beta,
            @RequestParam(name = "gamma", defaultValue = "0.15") float gamma,
            @RequestParam(name = "useNegative", defaultValue = "false") boolean useNegative,
            // Axiom parameters
            @RequestParam(name = "r", defaultValue = "20") int r,
            @RequestParam(name = "n", defaultValue = "30") int n,
            @RequestParam(name = "axiomBeta", defaultValue = "0.4") float axiomBeta,
            @RequestParam(name = "deterministic", defaultValue = "true") boolean deterministic,
            @RequestParam(name = "seed", defaultValue = "42") long seed,
            // Cross-encoder parameters
            @RequestParam(name = "crossEncoderModel", defaultValue = "") String crossEncoderModel) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("maxResults", maxResults);
        result.put("threshold", threshold);

        if (vectorStore instanceof NoOpVectorStoreImpl) {
            result.put("error", "No vector store available");
            return ResponseEntity.ok(result);
        }

        try {
            // First, get initial results without reranking for comparison
            long startInitial = System.currentTimeMillis();
            List<ScoredDocument> initialResults = vectorStore.similaritySearchWithScores(query, maxResults, threshold);
            long initialDuration = System.currentTimeMillis() - startInitial;

            result.put("initialDurationMs", initialDuration);
            result.put("initialCount", initialResults.size());

            // Format initial results
            List<Map<String, Object>> initialHits = initialResults.stream()
                    .map(this::formatScoredDocument)
                    .collect(Collectors.toList());
            result.put("initialHits", initialHits);

            // Check if reranking is requested
            RerankerType selectedType = RerankerType.fromId(rerankerType);
            result.put("rerankerType", selectedType.getId());
            result.put("rerankerDescription", selectedType.getDescription());

            if (selectedType == RerankerType.NONE) {
                result.put("reranked", false);
                result.put("rerankedHits", initialHits);
                result.put("rerankDurationMs", 0);
            } else {
                // Build reranker config with all parameters
                RerankerConfig config = new RerankerConfig()
                        .setType(selectedType)
                        .setEnabled(true)
                        // Common parameters
                        .setFbDocs(fbDocs)
                        .setFbTerms(fbTerms)
                        .setTopK(topK)
                        // RM3 parameters
                        .setOriginalQueryWeight(originalQueryWeight)
                        .setFilterTerms(filterTerms)
                        .setOutputQuery(outputQuery)
                        // BM25-PRF parameters
                        .setK1(k1)
                        .setB(b)
                        .setNewTermWeight(newTermWeight)
                        // Rocchio parameters
                        .setAlpha(alpha)
                        .setBeta(beta)
                        .setGamma(gamma)
                        .setUseNegative(useNegative)
                        // Axiom parameters
                        .setR(r)
                        .setN(n)
                        .setAxiomBeta(axiomBeta)
                        .setDeterministic(deterministic)
                        .setSeed(seed);

                // Build config map for response
                Map<String, Object> configMap = new LinkedHashMap<>();
                configMap.put("type", selectedType.getId());
                configMap.put("fbDocs", fbDocs);
                configMap.put("fbTerms", fbTerms);
                configMap.put("topK", topK);

                switch (selectedType) {
                    case RM3:
                        configMap.put("originalQueryWeight", originalQueryWeight);
                        configMap.put("filterTerms", filterTerms);
                        configMap.put("outputQuery", outputQuery);
                        break;
                    case BM25_PRF:
                        configMap.put("k1", k1);
                        configMap.put("b", b);
                        configMap.put("newTermWeight", newTermWeight);
                        break;
                    case ROCCHIO:
                        configMap.put("alpha", alpha);
                        configMap.put("beta", beta);
                        configMap.put("gamma", gamma);
                        configMap.put("useNegative", useNegative);
                        break;
                    case AXIOM:
                        configMap.put("r", r);
                        configMap.put("n", n);
                        configMap.put("axiomBeta", axiomBeta);
                        configMap.put("deterministic", deterministic);
                        configMap.put("seed", seed);
                        break;
                    case CROSS_ENCODER:
                        String modelToUse = crossEncoderModel != null && !crossEncoderModel.isEmpty()
                                ? crossEncoderModel
                                : ModelConstants.getDefaultCrossEncoderModelId();
                        configMap.put("crossEncoderModel", modelToUse);
                        break;
                    default:
                        break;
                }

                result.put("rerankerConfig", configMap);

                // Perform reranking
                long startRerank = System.currentTimeMillis();
                List<ScoredDocument> rerankedResults = vectorStore.similaritySearchWithReranking(query, maxResults, threshold, config);
                long rerankDuration = System.currentTimeMillis() - startRerank;

                result.put("reranked", true);
                result.put("rerankDurationMs", rerankDuration);
                result.put("rerankedCount", rerankedResults.size());

                // Format reranked results with rank change info
                List<Map<String, Object>> rerankedHits = new ArrayList<>();
                for (int i = 0; i < rerankedResults.size(); i++) {
                    ScoredDocument doc = rerankedResults.get(i);
                    Map<String, Object> formatted = formatScoredDocument(doc);
                    formatted.put("newRank", i + 1);

                    // Find original rank
                    int originalRank = -1;
                    for (int j = 0; j < initialResults.size(); j++) {
                        if (initialResults.get(j).getId().equals(doc.getId())) {
                            originalRank = j + 1;
                            break;
                        }
                    }
                    formatted.put("originalRank", originalRank);
                    formatted.put("rankChange", originalRank > 0 ? originalRank - (i + 1) : 0);

                    rerankedHits.add(formatted);
                }
                result.put("rerankedHits", rerankedHits);
            }

            result.put("totalHits", initialResults.size());

        } catch (Exception e) {
            log.error("Error in query with reranking", e);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> formatScoredDocument(ScoredDocument scoredDoc) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("id", scoredDoc.getId());
        formatted.put(FieldNames.SCORE, scoredDoc.score());

        String content = scoredDoc.getText();
        if (content != null) {
            formatted.put("contentLength", content.length());
            formatted.put("preview", content.length() > 300 ? content.substring(0, 300) + "..." : content);
            formatted.put("content", content);
        }

        Map<String, Object> metadata = scoredDoc.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            // Filter out large metadata values
            Map<String, Object> filteredMeta = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean) {
                    filteredMeta.put(entry.getKey(), entry.getValue());
                }
            }
            if (!filteredMeta.isEmpty()) {
                formatted.put("metadata", filteredMeta);
            }
        }

        return formatted;
    }

    private Map<String, Object> formatRetrievedDoc(RetrievedDoc doc) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("id", doc.getId());
        formatted.put(FieldNames.SCORE, doc.getScore());

        String content = doc.getText();
        if (content != null) {
            formatted.put("contentLength", content.length());
            formatted.put("preview", content.length() > 300 ? content.substring(0, 300) + "..." : content);
            formatted.put("content", content);
        }

        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            formatted.put("metadata", doc.getMetadata());
        }

        return formatted;
    }

    private Map<String, Object> formatDocument(Document doc) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("id", doc.getId());

        // Extract score from metadata if present
        if (doc.getMetadata() != null && doc.getMetadata().containsKey(FieldNames.SCORE)) {
            formatted.put(FieldNames.SCORE, doc.getMetadata().get(FieldNames.SCORE));
        }

        String content = doc.getText();
        if (content != null) {
            formatted.put("contentLength", content.length());
            formatted.put("preview", content.length() > 300 ? content.substring(0, 300) + "..." : content);
            formatted.put("content", content);
        }

        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            // Filter out large metadata values
            Map<String, Object> filteredMeta = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                if (entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean) {
                    filteredMeta.put(entry.getKey(), entry.getValue());
                }
            }
            if (!filteredMeta.isEmpty()) {
                formatted.put("metadata", filteredMeta);
            }
        }

        return formatted;
    }

    // ==================== Cross-Encoder Model Management ====================

    /**
     * Get a list of all available cross-encoder models with their cache status.
     */
    @GetMapping("/cross-encoder-models")
    public ResponseEntity<Map<String, Object>> getCrossEncoderModels() {
        Map<String, Object> result = new LinkedHashMap<>();

        Set<String> availableModelIds = ModelConstants.getAvailableCrossEncoderModelIds();
        result.put("totalModels", availableModelIds.size());
        result.put("defaultModel", ModelConstants.getDefaultCrossEncoderModelId());

        List<Map<String, Object>> models = new ArrayList<>();

        for (String modelId : availableModelIds) {
            ModelDescriptor descriptor = ModelConstants.getCrossEncoderModelDescriptor(modelId);
            if (descriptor != null) {
                Map<String, Object> modelInfo = new LinkedHashMap<>();
                modelInfo.put(FieldNames.MODEL_ID, modelId);
                modelInfo.put("cached", modelManager.isCrossEncoderModelCached(modelId));

                // Add metadata
                Map<String, Object> metadata = descriptor.getMetadata();
                if (metadata != null) {
                    modelInfo.put("description", metadata.get("description"));
                    modelInfo.put("hiddenSize", metadata.get("hidden_size"));
                    modelInfo.put("numLayers", metadata.get("num_layers"));
                    modelInfo.put("maxSequenceLength", metadata.get("max_sequence_length"));
                    modelInfo.put("framework", metadata.get("framework"));
                    modelInfo.put("trainingData", metadata.get("training_data"));
                }

                // Get cached path if available
                Path cachedPath = modelManager.getCrossEncoderModelPath(modelId);
                if (cachedPath != null) {
                    modelInfo.put("cachedPath", cachedPath.toString());
                }

                models.add(modelInfo);
            }
        }

        result.put("models", models);

        // Count cached models
        long cachedCount = models.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("cached")))
                .count();
        result.put("cachedCount", cachedCount);

        return ResponseEntity.ok(result);
    }

    /**
     * Download/ensure a cross-encoder model is available.
     */
    @PostMapping("/cross-encoder-models/{modelId}/download")
    public ResponseEntity<Map<String, Object>> downloadCrossEncoderModel(
            @PathVariable String modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FieldNames.MODEL_ID, modelId);

        // Check if model is valid
        if (!ModelConstants.isCrossEncoderModelAvailable(modelId)) {
            result.put("success", false);
            result.put("error", "Unknown cross-encoder model: " + modelId);
            result.put("availableModels", ModelConstants.getAvailableCrossEncoderModelIds());
            return ResponseEntity.badRequest().body(result);
        }

        try {
            long startTime = System.currentTimeMillis();
            boolean wasCached = modelManager.isCrossEncoderModelCached(modelId);

            var bundle = modelManager.ensureCrossEncoderModelAvailable(modelId);
            long duration = System.currentTimeMillis() - startTime;

            result.put("success", true);
            result.put("wasCached", wasCached);
            result.put("modelPath", bundle.getModelPath().toString());
            result.put(FieldNames.DURATION_MS, duration);

            // Add bundle info
            if (bundle.getDescription() != null) {
                result.put("description", bundle.getDescription());
            }
            if (bundle.getHiddenSize() != null) {
                result.put("hiddenSize", bundle.getHiddenSize());
            }
            if (bundle.getNumLayers() != null) {
                result.put("numLayers", bundle.getNumLayers());
            }
            if (bundle.getMaxSequenceLength() != null) {
                result.put("maxSequenceLength", bundle.getMaxSequenceLength());
            }
            if (bundle.getFramework() != null) {
                result.put("framework", bundle.getFramework());
            }

            if (wasCached) {
                result.put("message", "Model was already cached");
            } else {
                result.put("message", "Model downloaded successfully");
            }

            log.info("Cross-encoder model {} {} in {}ms",
                    modelId,
                    wasCached ? "loaded from cache" : "downloaded",
                    duration);

        } catch (Exception e) {
            log.error("Failed to download cross-encoder model {}: {}", modelId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get detailed info about a specific cross-encoder model.
     */
    @GetMapping("/cross-encoder-models/{modelId}")
    public ResponseEntity<Map<String, Object>> getCrossEncoderModelInfo(
            @PathVariable String modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FieldNames.MODEL_ID, modelId);

        // Check if model exists
        if (!ModelConstants.isCrossEncoderModelAvailable(modelId)) {
            result.put("found", false);
            result.put("error", "Unknown cross-encoder model: " + modelId);
            result.put("availableModels", ModelConstants.getAvailableCrossEncoderModelIds());
            return ResponseEntity.status(404).body(result);
        }

        result.put("found", true);

        ModelDescriptor descriptor = ModelConstants.getCrossEncoderModelDescriptor(modelId);
        if (descriptor != null) {
            result.put("downloadUrl", descriptor.getDownloadUrl());
            result.put("checksum", descriptor.getChecksum());
            result.put("expectedCacheSubpath", descriptor.getExpectedCacheSubpath());
            result.put("modelType", descriptor.getModelType().name());

            Map<String, Object> metadata = descriptor.getMetadata();
            if (metadata != null) {
                result.put("description", metadata.get("description"));
                result.put("hiddenSize", metadata.get("hidden_size"));
                result.put("numLayers", metadata.get("num_layers"));
                result.put("maxSequenceLength", metadata.get("max_sequence_length"));
                result.put("framework", metadata.get("framework"));
                result.put("trainingData", metadata.get("training_data"));
            }
        }

        // Check cache status
        result.put("cached", modelManager.isCrossEncoderModelCached(modelId));

        Path cachedPath = modelManager.getCrossEncoderModelPath(modelId);
        if (cachedPath != null) {
            result.put("cachedPath", cachedPath.toString());

            // Get file size if exists
            try {
                File file = cachedPath.toFile();
                if (file.exists()) {
                    if (file.isDirectory()) {
                        result.put("type", "directory");
                        long dirSize;
                        try (var walk = Files.walk(cachedPath)) {
                            dirSize = walk
                                    .filter(p -> p.toFile().isFile())
                                    .mapToLong(p -> p.toFile().length())
                                    .sum();
                        }
                        result.put("sizeBytes", dirSize);
                        result.put("sizeMB", String.format("%.2f", dirSize / (1024.0 * 1024.0)));
                    } else {
                        result.put("type", "file");
                        result.put("sizeBytes", file.length());
                        result.put("sizeMB", String.format("%.2f", file.length() / (1024.0 * 1024.0)));
                    }
                }
            } catch (Exception e) {
                result.put("sizeError", e.getMessage());
            }
        }

        // Is this the default model?
        result.put("isDefault", modelId.equals(ModelConstants.getDefaultCrossEncoderModelId()));

        return ResponseEntity.ok(result);
    }

    /**
     * Delete a cached cross-encoder model.
     */
    @DeleteMapping("/cross-encoder-models/{modelId}")
    public ResponseEntity<Map<String, Object>> deleteCrossEncoderModel(
            @PathVariable String modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FieldNames.MODEL_ID, modelId);

        // Check if model exists
        if (!ModelConstants.isCrossEncoderModelAvailable(modelId)) {
            result.put("success", false);
            result.put("error", "Unknown cross-encoder model: " + modelId);
            return ResponseEntity.status(404).body(result);
        }

        Path cachedPath = modelManager.getCrossEncoderModelPath(modelId);
        if (cachedPath == null) {
            result.put("success", true);
            result.put("message", "Model was not cached");
            return ResponseEntity.ok(result);
        }

        try {
            File file = cachedPath.toFile();
            if (file.exists()) {
                if (file.isDirectory()) {
                    // Delete directory recursively
                    try (var walk = Files.walk(cachedPath)) {
                        walk.sorted(Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(File::delete);
                    }
                } else {
                    Files.delete(cachedPath);
                }
                result.put("success", true);
                result.put("message", "Model cache deleted");
                result.put("deletedPath", cachedPath.toString());
                log.info("Deleted cached cross-encoder model: {}", modelId);
            } else {
                result.put("success", true);
                result.put("message", "Model was not cached");
            }
        } catch (Exception e) {
            log.error("Failed to delete cross-encoder model {}: {}", modelId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }

        return ResponseEntity.ok(result);
    }

    // ==================== Knowledge Graph RAG Testing ====================

    /**
     * Run a knowledge graph RAG query.
     */
    @GetMapping("/graph/query")
    public ResponseEntity<Map<String, Object>> testGraphRagQuery(
            @RequestParam("q") String query,
            @RequestParam(name = "searchType", defaultValue = "LOCAL") String searchTypeStr,
            @RequestParam(name = "k", defaultValue = "5") int maxResults,
            @RequestParam(name = "conversationId", defaultValue = "test") String conversationId) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("searchType", searchTypeStr);
        result.put("k", maxResults);
        result.put("conversationId", conversationId);

        if (graphRagService == null) {
            result.put("error", "No Graph RAG service available. Configure Neo4j (neo4j.uri) or Matrix graph (kompile.graph.type=matrix).");
            result.put("available", false);
            return ResponseEntity.ok(result);
        }

        result.put("available", true);
        result.put("serviceClass", graphRagService.getClass().getSimpleName());

        try {
            // Parse search type
            SearchType searchType;
            try {
                searchType = SearchType.valueOf(searchTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                searchType = SearchType.LOCAL;
                result.put("searchTypeWarning", "Invalid search type '" + searchTypeStr + "', defaulting to LOCAL");
            }

            // Build query
            GraphRagQuery graphQuery = GraphRagQuery.builder()
                    .query(query)
                    .searchType(searchType)
                    .k(maxResults)
                    .conversationId(conversationId)
                    .build();

            // Execute query
            long startTime = System.currentTimeMillis();
            GraphRagResult graphResult = graphRagService.answerQuery(graphQuery);
            long duration = System.currentTimeMillis() - startTime;

            result.put(FieldNames.DURATION_MS, duration);
            result.put("answer", graphResult.getAnswer());
            result.put("context", graphResult.getFormattedContext());

            // Add context preview (first 500 chars)
            String context = graphResult.getFormattedContext();
            if (context != null && context.length() > 500) {
                result.put("contextPreview", context.substring(0, 500) + "...");
                result.put("contextLength", context.length());
            } else {
                result.put("contextPreview", context);
                result.put("contextLength", context != null ? context.length() : 0);
            }

        } catch (Exception e) {
            log.error("Error in graph RAG query", e);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get detailed information about the Graph RAG service.
     */
    @GetMapping("/graph/info")
    public ResponseEntity<Map<String, Object>> getGraphRagInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("available", graphRagService != null);

        if (graphRagService != null) {
            result.put("class", graphRagService.getClass().getSimpleName());
            result.put("fullClass", graphRagService.getClass().getName());

            // Determine the type of graph RAG
            String serviceType;
            if (graphRagService.getClass().getName().contains("Neo4j")) {
                serviceType = "neo4j";
                result.put("description", "Neo4j-based Graph RAG with vector search and Cypher queries");
            } else if (graphRagService.getClass().getName().contains("Matrix")) {
                serviceType = "matrix";
                result.put("description", "Matrix-based Graph RAG with adjacency matrix storage and PageRank");
            } else {
                serviceType = "unknown";
                result.put("description", "Custom Graph RAG implementation");
            }
            result.put("type", serviceType);

            // Search types
            List<Map<String, String>> searchTypes = new ArrayList<>();
            Map<String, String> localType = new LinkedHashMap<>();
            localType.put("id", "LOCAL");
            localType.put("name", "Local Search");
            localType.put("description", "Vector similarity search for query-relevant nodes, expands with immediate neighbors");
            searchTypes.add(localType);

            Map<String, String> globalType = new LinkedHashMap<>();
            globalType.put("id", "GLOBAL");
            globalType.put("name", "Global Search");
            globalType.put("description", "PageRank-based importance scoring, provides graph overview with relationship context");
            searchTypes.add(globalType);

            result.put("searchTypes", searchTypes);
        } else {
            result.put("class", "N/A");
            result.put("type", "none");
            result.put("description", "Graph RAG service is initializing or not available. Ensure kompile-knowledge-graph module is included.");
            result.put("searchTypes", Collections.emptyList());
        }

        return ResponseEntity.ok(result);
    }
}
