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

import ai.kompile.app.services.diffindex.DiffIndexService;
import ai.kompile.app.services.diffindex.DiffIndexEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the diff index browser UI.
 * Exposes search, project listing, agent listing, and reindex endpoints.
 */
@RestController
@RequestMapping("/api/diff-index")
public class DiffIndexController {

    private static final Logger log = LoggerFactory.getLogger(DiffIndexController.class);

    private final DiffIndexService diffIndexService;

    @Autowired
    public DiffIndexController(@Autowired(required = false) DiffIndexService diffIndexService) {
        this.diffIndexService = diffIndexService;
    }

    @GetMapping("/search")
    public ResponseEntity<List<DiffIndexEntry>> search(
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String projectDirectory,
            @RequestParam(required = false) String filePath,
            @RequestParam(required = false) String contentQuery,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer limit) {
        if (diffIndexService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(diffIndexService.search(
                agent, projectDirectory, filePath, contentQuery, source, limit));
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<DiffIndexEntry> getEntry(@PathVariable String id) {
        if (diffIndexService == null) {
            return ResponseEntity.notFound().build();
        }
        DiffIndexEntry entry = diffIndexService.get(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(entry);
    }

    @GetMapping("/projects")
    public ResponseEntity<List<Map<String, Object>>> listProjects() {
        if (diffIndexService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(diffIndexService.listProjects());
    }

    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, Object>>> listAgents() {
        if (diffIndexService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(diffIndexService.listAgents());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        if (diffIndexService == null) {
            return ResponseEntity.ok(Map.of("totalEntries", 0));
        }
        return ResponseEntity.ok(diffIndexService.getStats());
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        if (diffIndexService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "error", "error", "Service not available"));
        }
        if (diffIndexService.isIndexing()) {
            return ResponseEntity.ok(Map.of("status", "info", "message", "Already indexing"));
        }
        diffIndexService.reindexAll();
        return ResponseEntity.ok(Map.of("status", "success", "message", "Reindex started"));
    }
}
