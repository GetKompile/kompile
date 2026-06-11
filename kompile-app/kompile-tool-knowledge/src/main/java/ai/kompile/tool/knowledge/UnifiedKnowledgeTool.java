/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.tool.knowledge;

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Unified knowledge tool that gives agents a single, simple entry point
 * for searching all knowledge backends (vector store, keyword index,
 * knowledge graph) simultaneously.
 *
 * <p>Designed for simplicity: agents only need to provide a query.
 * All backend fan-out, merging, deduplication, and formatting is
 * handled server-side. A small model on Telegram can use this tool
 * effectively without understanding the underlying architecture.</p>
 */
@Component
public class UnifiedKnowledgeTool {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedKnowledgeTool.class);
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_CONTENT_CHARS = 2000;
    private static final int GRAPH_ENTITY_LIMIT = 5;
    private static final int GRAPH_RELATIONSHIP_LIMIT = 3;
    private static final long SEARCH_TIMEOUT_SECONDS = 30;

    private final List<DocumentRetriever> allRetrievers;
    private final GraphRagService graphRagService;
    private final McpOptimizationConfigProvider optimizationProvider;
    private final ObjectMapper objectMapper;

    private GraphNodeRepository graphNodeRepository;

    public record SearchInput(String query, String topic) {}

    public record StatusInput() {}

    @Autowired
    public UnifiedKnowledgeTool(
            @Autowired(required = false) List<DocumentRetriever> documentRetrievers,
            @Autowired(required = false) GraphRagService graphRagService,
            @Autowired(required = false) McpOptimizationConfigProvider optimizationProvider,
            ObjectMapper objectMapper) {
        this.allRetrievers = documentRetrievers != null ? documentRetrievers : List.of();
        this.graphRagService = graphRagService;
        this.optimizationProvider = optimizationProvider != null
                ? optimizationProvider
                : McpOptimizationConfigProvider.ofDefaults();
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setGraphNodeRepository(GraphNodeRepository graphNodeRepository) {
        this.graphNodeRepository = graphNodeRepository;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL 1: knowledge_search
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "knowledge_search",
            description = "Search all knowledge sources with a natural language question. " +
                    "Automatically searches indexed documents and knowledge graph in parallel. " +
                    "Returns relevant content with source attribution. " +
                    "Optionally use topic to narrow results to a specific subject area.")
    public Map<String, Object> knowledgeSearch(SearchInput input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            return Map.of("error", "query is required");
        }

        String query = input.query().trim();
        String topic = input.topic();
        logger.info("knowledge_search: query='{}', topic='{}'", query, topic);

        McpOptimizationConfig cfg = optimizationProvider.getConfiguration();
        int maxDocs = resolveMaxDocs(cfg);
        int maxChars = resolveMaxChars(cfg);

        List<Map<String, Object>> docResults = new ArrayList<>();
        List<String> backendsUsed = new ArrayList<>();

        // ── Fan-out: document retrievers (parallel) ──────────────────────────
        List<DocumentRetriever> active = activeRetrievers();
        if (!active.isEmpty()) {
            List<CompletableFuture<List<RetrievedDoc>>> futures = new ArrayList<>();
            for (DocumentRetriever retriever : active) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return retriever.retrieveWithDetails(query, maxDocs);
                    } catch (Exception e) {
                        logger.warn("Retriever {} failed: {}",
                                retriever.getClass().getSimpleName(), e.getMessage());
                        return List.<RetrievedDoc>of();
                    }
                }));
            }

            // Wait for all retriever results
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Some retrievers timed out: {}", e.getMessage());
            }

            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<List<RetrievedDoc>> f = futures.get(i);
                String retrieverName = active.get(i).getClass().getSimpleName();
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    try {
                        List<RetrievedDoc> docs = f.get();
                        for (RetrievedDoc doc : docs) {
                            docResults.add(buildDocEntry(doc, retrieverName, maxChars));
                        }
                        if (!docs.isEmpty()) {
                            backendsUsed.add(retrieverName);
                        }
                    } catch (Exception ignored) {
                        // already logged
                    }
                }
            }
        }

        // ── Fan-out: graph RAG search (best-effort) ─────────────────────────
        String graphContext = "";
        if (graphRagService != null) {
            try {
                GraphRagQuery gq = GraphRagQuery.builder()
                        .query(query)
                        .searchType(SearchType.LOCAL)
                        .k(Math.min(maxDocs, 5))
                        .build();
                GraphRagResult gr = graphRagService.answerQuery(gq);
                graphContext = formatGraphContext(gr);
                if (!graphContext.isEmpty()) {
                    backendsUsed.add("GraphRAG");
                }
            } catch (Exception e) {
                logger.debug("GraphRAG search failed (non-fatal): {}", e.getMessage());
            }
        }

        // ── Merge and deduplicate ───────────────────────────────────────────
        List<Map<String, Object>> merged = mergeAndDedup(docResults, maxDocs);

        // ── Topic filter ────────────────────────────────────────────────────
        if (topic != null && !topic.isBlank()) {
            String topicLower = topic.toLowerCase();
            merged = merged.stream()
                    .filter(r -> {
                        String source = String.valueOf(r.getOrDefault("source", "")).toLowerCase();
                        return source.contains(topicLower);
                    })
                    .collect(Collectors.toList());
        }

        // ── Build response ──────────────────────────────────────────────────
        int resultCount = merged.size();
        Set<String> uniqueSources = merged.stream()
                .map(r -> String.valueOf(r.getOrDefault("source", "unknown")))
                .collect(Collectors.toSet());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", merged);
        if (!graphContext.isEmpty()) {
            response.put("graph_context", graphContext);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Found ").append(resultCount).append(" result")
                .append(resultCount != 1 ? "s" : "");
        if (!uniqueSources.isEmpty()) {
            summary.append(" across ").append(uniqueSources.size()).append(" source")
                    .append(uniqueSources.size() != 1 ? "s" : "");
        }
        if (!graphContext.isEmpty()) {
            summary.append(". Graph context available");
        }
        summary.append(".");
        response.put("summary", summary.toString());

        if (merged.isEmpty()) {
            response.put("hint", "No results found. The knowledge base may be empty or the query may not match any indexed content.");
        }

        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL 2: knowledge_status
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "knowledge_status",
            description = "Check what knowledge backends are available and how much data is indexed. " +
                    "Call this to verify knowledge search will work before using knowledge_search.")
    public Map<String, Object> knowledgeStatus(StatusInput input) {
        List<String> backends = new ArrayList<>();
        for (DocumentRetriever r : activeRetrievers()) {
            backends.add(r.getClass().getSimpleName());
        }
        if (graphRagService != null) {
            backends.add("GraphRAG");
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", !backends.isEmpty());
        status.put("backends", backends);

        // Source and entity counts from graph repository (if available)
        long sourcesCount = countByNodeType("SOURCE");
        long entitiesCount = countByNodeType("ENTITY");
        long documentsCount = countByNodeType("DOCUMENT");

        status.put("sources_count", sourcesCount);
        status.put("documents_indexed", documentsCount);
        status.put("graph_entities", entitiesCount);

        return status;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<DocumentRetriever> activeRetrievers() {
        return allRetrievers.stream()
                .filter(r -> !(r instanceof NoOpDocumentRetrieverImpl))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildDocEntry(RetrievedDoc doc, String backend, int maxChars) {
        Map<String, Object> entry = new LinkedHashMap<>();

        String content = doc.getText();
        if (content != null && content.length() > maxChars) {
            content = content.substring(0, maxChars) + "...";
        }
        entry.put("content", content != null ? content : "");

        String source = doc.getSourceFilename().orElse(
                doc.getMetadata() != null
                        ? String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"))
                        : "unknown");
        entry.put("source", source);

        if (doc.getScore() != null) {
            entry.put("relevance", Math.round(doc.getScore() * 100.0) / 100.0);
        }

        // Internal fields for dedup
        entry.put("_id", doc.getId());
        entry.put("_score", doc.getScore() != null ? doc.getScore() : 0.0);
        entry.put("_backend", backend);

        return entry;
    }

    private List<Map<String, Object>> mergeAndDedup(List<Map<String, Object>> raw, int maxDocs) {
        // Deduplicate by chunk ID, keeping the entry with the highest score
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> entry : raw) {
            String id = String.valueOf(entry.getOrDefault("_id", UUID.randomUUID().toString()));
            Map<String, Object> existing = byId.get(id);
            if (existing == null) {
                byId.put(id, entry);
            } else {
                double existingScore = ((Number) existing.getOrDefault("_score", 0.0)).doubleValue();
                double newScore = ((Number) entry.getOrDefault("_score", 0.0)).doubleValue();
                if (newScore > existingScore) {
                    byId.put(id, entry);
                }
            }
        }

        // Sort by score descending, take top N, strip internal fields
        return byId.values().stream()
                .sorted((a, b) -> Double.compare(
                        ((Number) b.getOrDefault("_score", 0.0)).doubleValue(),
                        ((Number) a.getOrDefault("_score", 0.0)).doubleValue()))
                .limit(maxDocs)
                .map(entry -> {
                    Map<String, Object> clean = new LinkedHashMap<>(entry);
                    clean.remove("_id");
                    clean.remove("_score");
                    clean.remove("_backend");
                    return clean;
                })
                .collect(Collectors.toList());
    }

    private String formatGraphContext(GraphRagResult gr) {
        if (gr == null) return "";

        StringBuilder sb = new StringBuilder();

        // Include graph answer if available
        if (gr.getAnswer() != null && !gr.getAnswer().isBlank()) {
            String answer = gr.getAnswer();
            if (answer.length() > 300) {
                answer = answer.substring(0, 300) + "...";
            }
            sb.append(answer);
        }

        // Entities
        if (gr.getEntities() != null && !gr.getEntities().isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Related entities: ");
            sb.append(gr.getEntities().stream()
                    .limit(GRAPH_ENTITY_LIMIT)
                    .map(e -> e.getTitle() + " (" + e.getType() + ")")
                    .collect(Collectors.joining(", ")));
            sb.append(".");
        }

        // Relationships
        if (gr.getRelationships() != null && !gr.getRelationships().isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Relationships: ");
            sb.append(gr.getRelationships().stream()
                    .limit(GRAPH_RELATIONSHIP_LIMIT)
                    .map(r -> r.getSource() + " --" + r.getType() + "--> " + r.getTarget())
                    .collect(Collectors.joining("; ")));
            sb.append(".");
        }

        return sb.toString();
    }

    private long countByNodeType(String typeName) {
        if (graphNodeRepository == null) return -1;
        try {
            return graphNodeRepository.countByNodeType(NodeLevel.valueOf(typeName));
        } catch (Exception e) {
            logger.debug("Could not count {} nodes: {}", typeName, e.getMessage());
            return -1;
        }
    }

    private int resolveMaxDocs(McpOptimizationConfig cfg) {
        if (cfg != null && Boolean.TRUE.equals(cfg.getEnabled())
                && cfg.getRagMaxDocs() != null && cfg.getRagMaxDocs() > 0) {
            return Math.min(cfg.getRagMaxDocs(), 10);
        }
        return DEFAULT_MAX_RESULTS;
    }

    private int resolveMaxChars(McpOptimizationConfig cfg) {
        if (cfg != null && Boolean.TRUE.equals(cfg.getEnabled())
                && cfg.getRagMaxContentChars() != null && cfg.getRagMaxContentChars() > 0) {
            return cfg.getRagMaxContentChars();
        }
        return MAX_CONTENT_CHARS;
    }
}
