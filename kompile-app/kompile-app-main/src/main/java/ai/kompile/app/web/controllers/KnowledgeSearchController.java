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

import ai.kompile.tool.knowledge.UnifiedKnowledgeTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the unified knowledge search tool.
 * Used by the CLI {@code KnowledgeSearchCliTool} to call into the server-side tool.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeSearchController {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSearchController.class);

    private final UnifiedKnowledgeTool unifiedKnowledgeTool;

    @Autowired
    public KnowledgeSearchController(
            @Autowired(required = false) UnifiedKnowledgeTool unifiedKnowledgeTool) {
        this.unifiedKnowledgeTool = unifiedKnowledgeTool;
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> request) {
        if (unifiedKnowledgeTool == null) {
            return ResponseEntity.ok(Map.of(
                    "error", "Unified knowledge tool not available",
                    "available", false));
        }

        String query = (String) request.getOrDefault("query", "");
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }

        String topic = (String) request.get("topic");

        try {
            Map<String, Object> result = unifiedKnowledgeTool.knowledgeSearch(
                    new UnifiedKnowledgeTool.SearchInput(query, topic));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Knowledge search failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (unifiedKnowledgeTool == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "backends", List.of(),
                    "sources_count", 0,
                    "documents_indexed", 0,
                    "graph_entities", 0));
        }
        return ResponseEntity.ok(unifiedKnowledgeTool.knowledgeStatus(
                new UnifiedKnowledgeTool.StatusInput()));
    }
}
