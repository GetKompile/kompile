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

import ai.kompile.app.services.ToolCallCatalogService;
import ai.kompile.app.services.TranscriptIndexingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for searching and browsing the tool call catalog.
 * Provides endpoints for the web UI's Tool Call Catalog component.
 */
@RestController
@RequestMapping("/api/tool-calls")
public class ToolCallCatalogController {

    private final ToolCallCatalogService catalogService;
    private final TranscriptIndexingService indexingService;

    public ToolCallCatalogController(ToolCallCatalogService catalogService,
                                     TranscriptIndexingService indexingService) {
        this.catalogService = catalogService;
        this.indexingService = indexingService;
    }

    /**
     * Search tool calls with optional filters, sorting, and pagination.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String session,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "timestamp") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok(
                catalogService.search(q, tool, session, category, agent, source,
                        project, sortBy, sortDir, page, pageSize));
    }

    /**
     * Get tool calls grouped by a field (category, project, agent, tool, source, session).
     */
    @GetMapping("/grouped")
    public ResponseEntity<Map<String, Object>> grouped(
            @RequestParam(defaultValue = "category") String groupBy,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String session,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "timestamp") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "50") int limitPerGroup) {
        return ResponseEntity.ok(
                catalogService.groupBy(groupBy, q, tool, session, category, agent, source,
                        project, sortBy, sortDir, limitPerGroup));
    }

    /**
     * Get tool call statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(catalogService.getStats());
    }

    /**
     * Get available filter options (distinct tool names, categories, agents, projects, etc.).
     */
    @GetMapping("/filters")
    public ResponseEntity<Map<String, Object>> filters() {
        return ResponseEntity.ok(catalogService.getFilterOptions());
    }

    /**
     * Get a single tool call by ID with full input details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        ToolCallCatalogService.ToolCallEntry entry = catalogService.getById(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(entry);
    }

    /**
     * Index tool calls from provider transcripts (Claude, Codex, Qwen, OpenCode, Gemini).
     */
    @PostMapping("/index")
    public ResponseEntity<Map<String, Object>> indexTranscripts(
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(defaultValue = "false") boolean reindex) {
        return ResponseEntity.ok(indexingService.indexTranscripts(source, reindex));
    }
}
