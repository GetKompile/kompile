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

package ai.kompile.app.rag.retrieval;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.rag.retrieval.OptimizedRetriever;
import ai.kompile.core.rag.retrieval.RetrievalMetrics;
import ai.kompile.core.rag.retrieval.RetrievalOptions;
import ai.kompile.core.rag.retrieval.RetrievalResult;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Hybrid document retriever that combines semantic (vector) and keyword (BM25) search.
 * <p>
 * This implementation is optimized for INDArray operations to avoid float[] conversion
 * overhead in the hot path. Embeddings stay as INDArray until the final conversion
 * at the Lucene storage boundary.
 *
 * <h2>Architecture</h2>
 * <pre>
 * Query String
 *     │
 *     ├─────────────────────────────────────────────────────┐
 *     │                                                     │
 *     ▼                                                     ▼
 * EmbeddingModel.embed()                           DocumentRetriever (BM25)
 *     │ (returns INDArray)                                  │
 *     ▼                                                     ▼
 * VectorStore.similaritySearchWithScores()         KeywordResults
 *     │ (INDArray → float[] only at Lucene)               │
 *     ▼                                                     │
 * SemanticResults                                           │
 *     │                                                     │
 *     └──────────────────┬──────────────────────────────────┘
 *                        │
 *                        ▼
 *                 Deduplicate + Merge
 *                        │
 *                        ▼
 *                 RetrievalResult
 * </pre>
 */
@Slf4j
@Service("hybridOptimizedRetriever")
public class HybridOptimizedRetriever implements OptimizedRetriever {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final DocumentRetriever keywordRetriever;

    @Autowired
    public HybridOptimizedRetriever(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) @Qualifier("anseriniDocumentRetriever") DocumentRetriever keywordRetriever) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.keywordRetriever = keywordRetriever;

        log.info("HybridOptimizedRetriever initialized - embeddingModel: {}, vectorStore: {}, keywordRetriever: {}",
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "null",
                vectorStore != null ? vectorStore.getClass().getSimpleName() : "null",
                keywordRetriever != null ? keywordRetriever.getClass().getSimpleName() : "null");
    }

    @Override
    public RetrievalResult retrieve(String query, RetrievalOptions options) {
        if (query == null || query.isBlank()) {
            log.warn("Empty query provided to retriever");
            return RetrievalResult.empty();
        }

        long startTime = System.nanoTime();
        RetrievalMetrics.Builder metricsBuilder = RetrievalMetrics.builder();

        // Generate embedding if semantic search is enabled
        INDArray queryEmbedding = null;
        if (options.enableSemanticSearch() && embeddingModel != null) {
            long embedStart = System.nanoTime();
            try {
                queryEmbedding = embeddingModel.embed(query);
                metricsBuilder.embeddingTimeNanos(System.nanoTime() - embedStart);
            } catch (Exception e) {
                log.warn("Error generating query embedding: {}", e.getMessage());
                metricsBuilder.embeddingTimeNanos(System.nanoTime() - embedStart);
            }
        }

        // Perform retrieval with the embedding
        RetrievalResult result = retrieveInternal(queryEmbedding, query, options, metricsBuilder);

        // Clean up embedding if we created it
        if (queryEmbedding != null && !queryEmbedding.wasClosed()) {
            try {
                queryEmbedding.close();
            } catch (Exception e) {
                log.trace("Error closing query embedding: {}", e.getMessage());
            }
        }

        metricsBuilder.totalTimeNanos(System.nanoTime() - startTime);
        return new RetrievalResult(result.documents(), metricsBuilder.build());
    }

    @Override
    public RetrievalResult retrieve(INDArray queryEmbedding, String originalQuery, RetrievalOptions options) {
        if (originalQuery == null || originalQuery.isBlank()) {
            log.warn("Empty query provided to retriever");
            return RetrievalResult.empty();
        }

        long startTime = System.nanoTime();
        RetrievalMetrics.Builder metricsBuilder = RetrievalMetrics.builder();

        RetrievalResult result = retrieveInternal(queryEmbedding, originalQuery, options, metricsBuilder);

        metricsBuilder.totalTimeNanos(System.nanoTime() - startTime);
        return new RetrievalResult(result.documents(), metricsBuilder.build());
    }

    private RetrievalResult retrieveInternal(INDArray queryEmbedding, String originalQuery,
                                              RetrievalOptions options, RetrievalMetrics.Builder metricsBuilder) {

        List<ScoredDocument> allResults = new ArrayList<>();
        int semanticHits = 0;
        int keywordHits = 0;

        // ══════════════════════════════════════════════════════════════════════════
        // SEMANTIC SEARCH (INDArray-native, minimal float[] conversion)
        // ══════════════════════════════════════════════════════════════════════════
        if (options.enableSemanticSearch() && options.semanticK() > 0 && vectorStore != null) {
            long semanticStart = System.nanoTime();
            try {
                List<ScoredDocument> semanticResults;

                if (queryEmbedding != null && !queryEmbedding.isEmpty() && !queryEmbedding.wasClosed()) {
                    // Use INDArray-native search
                    semanticResults = vectorStore.similaritySearchWithScores(
                        queryEmbedding,
                        options.semanticK(),
                        options.similarityThreshold()
                    );
                } else {
                    // Fall back to string-based search (will generate embedding internally)
                    semanticResults = vectorStore.similaritySearchWithScores(
                        originalQuery,
                        options.semanticK(),
                        options.similarityThreshold()
                    );
                }

                allResults.addAll(semanticResults);
                semanticHits = semanticResults.size();
                log.debug("Semantic search returned {} results", semanticHits);

            } catch (Exception e) {
                log.warn("Error during semantic search: {}", e.getMessage());
            }
            metricsBuilder.semanticSearchTimeNanos(System.nanoTime() - semanticStart);
        }
        metricsBuilder.semanticHits(semanticHits);

        // ══════════════════════════════════════════════════════════════════════════
        // KEYWORD SEARCH (BM25 via DocumentRetriever)
        // ══════════════════════════════════════════════════════════════════════════
        if (options.enableKeywordSearch() && options.keywordK() > 0 && keywordRetriever != null) {
            long keywordStart = System.nanoTime();
            try {
                List<RetrievedDoc> keywordResults = keywordRetriever.retrieveWithDetails(
                    originalQuery,
                    options.keywordK()
                );

                for (RetrievedDoc doc : keywordResults) {
                    if (doc != null && doc.getText() != null) {
                        Document springDoc = new Document(
                            doc.getId() != null ? doc.getId() : UUID.randomUUID().toString(),
                            doc.getText(),
                            doc.getMetadata() != null ? doc.getMetadata() : Map.of()
                        );
                        double score = doc.getScore() != null ? doc.getScore() : 0.0;
                        allResults.add(new ScoredDocument(springDoc, score));
                        keywordHits++;
                    }
                }
                log.debug("Keyword search returned {} results", keywordHits);

            } catch (Exception e) {
                log.warn("Error during keyword search: {}", e.getMessage());
            }
            metricsBuilder.keywordSearchTimeNanos(System.nanoTime() - keywordStart);
        }
        metricsBuilder.keywordHits(keywordHits);

        // ══════════════════════════════════════════════════════════════════════════
        // DEDUPLICATE (by document ID)
        // ══════════════════════════════════════════════════════════════════════════
        int beforeDedup = allResults.size();
        if (options.deduplicateResults() && allResults.size() > 1) {
            allResults = deduplicateByDocId(allResults);
        }
        metricsBuilder.duplicatesRemoved(beforeDedup - allResults.size());

        // Sort by score descending
        allResults.sort((a, b) -> Double.compare(b.score(), a.score()));

        log.debug("HybridRetriever: semantic={}, keyword={}, deduped={}, final={}",
                semanticHits, keywordHits, beforeDedup - allResults.size(), allResults.size());

        return RetrievalResult.of(allResults);
    }

    /**
     * Deduplicates documents by ID, keeping the highest scored version.
     */
    private List<ScoredDocument> deduplicateByDocId(List<ScoredDocument> docs) {
        Map<String, ScoredDocument> seen = new LinkedHashMap<>();
        for (ScoredDocument doc : docs) {
            String id = doc.getId();
            if (id == null || id.isBlank()) {
                // Keep documents without IDs
                seen.put(UUID.randomUUID().toString(), doc);
            } else {
                // Keep highest scored version
                seen.merge(id, doc, (existing, newDoc) ->
                    newDoc.score() > existing.score() ? newDoc : existing);
            }
        }
        return new ArrayList<>(seen.values());
    }

    @Override
    public boolean isAvailable() {
        // At minimum we need either semantic or keyword search capability
        boolean hasSemanticSearch = embeddingModel != null && vectorStore != null;
        boolean hasKeywordSearch = keywordRetriever != null;
        return hasSemanticSearch || hasKeywordSearch;
    }

    @Override
    public String getName() {
        return "HybridOptimizedRetriever";
    }
}
