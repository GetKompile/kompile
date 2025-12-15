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
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG Testing Controller - provides endpoints to test RAG components.
 *
 * Endpoints:
 * - GET /api/rag/test/query - Run a test query against all RAG components
 * - GET /api/rag/test/status - Check status of RAG components
 * - GET /api/rag/test/embed - Test embedding generation
 */
@RestController
@RequestMapping("/api/rag/test")
public class RagTestController {

    private static final Logger log = LoggerFactory.getLogger(RagTestController.class);

    private final DocumentRetriever keywordRetriever;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public RagTestController(
            List<DocumentRetriever> documentRetrievers,
            List<VectorStore> vectorStores,
            List<EmbeddingModel> embeddingModels) {

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

        log.info("RagTestController initialized - KeywordRetriever: {}, VectorStore: {}, EmbeddingModel: {}",
                this.keywordRetriever.getClass().getSimpleName(),
                this.vectorStore.getClass().getSimpleName(),
                this.embeddingModel.getClass().getSimpleName());
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

        return ResponseEntity.ok(status);
    }

    /**
     * Test embedding generation.
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
            long startTime = System.currentTimeMillis();
            INDArray embedding = embeddingModel.embed(text);
            long duration = System.currentTimeMillis() - startTime;

            result.put("dimensions", embedding.length());
            result.put("shape", Arrays.toString(embedding.shape()));
            result.put("durationMs", duration);

            // Include first 10 values as preview
            double[] preview = new double[Math.min(10, (int) embedding.length())];
            for (int i = 0; i < preview.length; i++) {
                preview[i] = embedding.getDouble(i);
            }
            result.put("preview", preview);

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

                keywordResult.put("durationMs", duration);
                keywordResult.put("count", docs != null ? docs.size() : 0);

                if (docs != null && !docs.isEmpty()) {
                    List<Map<String, Object>> hits = docs.stream()
                            .filter(d -> d != null && d.getContent() != null)
                            .filter(d -> !d.getContent().startsWith("Error:"))
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

                semanticResult.put("durationMs", duration);
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
                        if (doc != null && doc.getContent() != null && !doc.getContent().startsWith("Error:")) {
                            String key = doc.getId() != null ? doc.getId() : String.valueOf(doc.getContent().hashCode());
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
                                Object existingScore = existing.get("score");
                                Object semanticScore = doc.getMetadata() != null ? doc.getMetadata().get("score") : null;
                                if (semanticScore instanceof Number && existingScore instanceof Number) {
                                    if (((Number) semanticScore).doubleValue() > ((Number) existingScore).doubleValue()) {
                                        existing.put("score", semanticScore);
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
                    double scoreA = a.get("score") instanceof Number ? ((Number) a.get("score")).doubleValue() : 0.0;
                    double scoreB = b.get("score") instanceof Number ? ((Number) b.get("score")).doubleValue() : 0.0;
                    return Double.compare(scoreB, scoreA);
                })
                .limit(maxResults)
                .collect(Collectors.toList());

        result.put("hits", sortedResults);
        result.put("totalHits", sortedResults.size());

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> formatRetrievedDoc(RetrievedDoc doc) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("id", doc.getId());
        formatted.put("score", doc.getScore());

        String content = doc.getContent();
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
        if (doc.getMetadata() != null && doc.getMetadata().containsKey("score")) {
            formatted.put("score", doc.getMetadata().get("score"));
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
}
