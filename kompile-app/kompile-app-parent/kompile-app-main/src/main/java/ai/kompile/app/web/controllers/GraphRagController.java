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
package ai.kompile.app.web.controllers;

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for Graph RAG search operations.
 * Provides the {@code /api/graph-rag/search} endpoint used by the CLI
 * {@code GraphRagSearchTool} and the {@code /api/graph-rag/info} endpoint
 * for service discovery.
 */
@RestController
@RequestMapping("/api/graph-rag")
@Slf4j
public class GraphRagController {

    private final GraphRagService graphRagService;

    @Autowired
    public GraphRagController(@Autowired(required = false) GraphRagService graphRagService) {
        this.graphRagService = graphRagService;
    }

    /**
     * Search the knowledge graph using RAG.
     * <p>
     * Request body: {@code { query, searchType, maxResults, conversationId }}
     * <p>
     * Response: {@code { answer, context, entities[], relationships[], communities[], sourceChunks[] }}
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> request) {
        if (graphRagService == null) {
            return ResponseEntity.ok(Map.of(
                    "error", "Graph RAG service is not available. Ensure knowledge graph module is configured.",
                    "available", false
            ));
        }

        String query = (String) request.getOrDefault("query", "");
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }

        String searchTypeStr = (String) request.getOrDefault("searchType", "LOCAL");
        int maxResults = request.containsKey("maxResults")
                ? ((Number) request.get("maxResults")).intValue() : 5;
        String conversationId = (String) request.getOrDefault("conversationId", "default");

        SearchType searchType;
        try {
            searchType = SearchType.valueOf(searchTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            searchType = SearchType.LOCAL;
        }

        try {
            GraphRagQuery graphQuery = GraphRagQuery.builder()
                    .query(query)
                    .searchType(searchType)
                    .k(maxResults)
                    .conversationId(conversationId)
                    .build();

            long startTime = System.currentTimeMillis();
            GraphRagResult result = graphRagService.answerQuery(graphQuery);
            long durationMs = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("answer", result.getAnswer());
            response.put("context", result.getFormattedContext());
            response.put("durationMs", durationMs);

            // Structured results — these are what the CLI GraphRagSearchTool parses
            response.put("entities", result.getEntities() != null ? result.getEntities() : List.of());
            response.put("relationships", result.getRelationships() != null ? result.getRelationships() : List.of());
            response.put("communities", result.getCommunities() != null ? result.getCommunities() : List.of());
            response.put("sourceChunks", result.getSourceChunks() != null ? result.getSourceChunks() : List.of());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in graph RAG search", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Graph RAG search failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Get information about the Graph RAG service.
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", graphRagService != null);

        if (graphRagService != null) {
            result.put("serviceClass", graphRagService.getClass().getSimpleName());

            String serviceType;
            if (graphRagService.getClass().getName().contains("Neo4j")) {
                serviceType = "neo4j";
            } else if (graphRagService.getClass().getName().contains("Matrix")) {
                serviceType = "matrix";
            } else {
                serviceType = "unknown";
            }
            result.put("type", serviceType);

            result.put("searchTypes", List.of(
                    Map.of("id", "LOCAL", "name", "Local Search",
                            "description", "Vector similarity search for query-relevant nodes, expands with neighbors and KG link prediction"),
                    Map.of("id", "GLOBAL", "name", "Global Search",
                            "description", "PageRank-based importance scoring with community detection")
            ));
        } else {
            result.put("type", "none");
            result.put("description", "Graph RAG service not available. Ensure kompile-knowledge-graph module is included.");
        }

        return ResponseEntity.ok(result);
    }
}
