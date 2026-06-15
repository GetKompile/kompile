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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Alias controller for indexing endpoints that handles alternative URL patterns.
 * Delegates to IndexerController for actual functionality.
 *
 * This handles the following alternative URL patterns:
 * - /api/indexing/status -> delegates to /api/indexer/status
 * - /api/indexing/rebuild-all-sources -> delegates to /api/indexer/rebuild-all-sources
 */
@RestController
@RequestMapping("/api/indexing")
@CrossOrigin(origins = "*")
public class IndexingAliasController {

    private static final Logger logger = LoggerFactory.getLogger(IndexingAliasController.class);

    private final IndexerController indexerController;

    @Autowired
    public IndexingAliasController(IndexerController indexerController) {
        this.indexerController = indexerController;
        logger.info("IndexingAliasController initialized - handling /api/indexing/* paths");
    }

    /**
     * Alias for /api/indexer/status - handles /api/indexing/status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getIndexStatusAlias() {
        logger.debug("Handling aliased endpoint /api/indexing/status -> /api/indexer/status");
        return indexerController.getIndexStatus();
    }

    /**
     * Alias for /api/indexer/rebuild-all-sources - handles /api/indexing/rebuild-all-sources
     */
    @PostMapping("/rebuild-all-sources")
    public ResponseEntity<?> rebuildAllSourcesAlias() {
        logger.debug("Handling aliased endpoint /api/indexing/rebuild-all-sources -> /api/indexer/rebuild-all-sources");
        return indexerController.rebuildAllSourcesIndex();
    }

    /**
     * Base path handler - returns API overview
     */
    @GetMapping
    public ResponseEntity<?> getIndexingApiInfo() {
        logger.debug("Received request to base /api/indexing endpoint");
        return ResponseEntity.ok(java.util.Map.of(
            "message", "Indexing API (alias for /api/indexer)",
            "endpoints", java.util.Map.of(
                "GET /api/indexing/status", "Get index status (alias for /api/indexer/status)",
                "POST /api/indexing/rebuild-all-sources", "Rebuild index from all sources (alias for /api/indexer/rebuild-all-sources)"
            ),
            "note", "This is an alias controller. The canonical endpoints are under /api/indexer"
        ));
    }
}
