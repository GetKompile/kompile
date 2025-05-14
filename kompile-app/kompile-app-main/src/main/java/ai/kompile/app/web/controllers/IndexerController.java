/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.core.indexers.IndexerService; // From core-abstractions
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/indexer")
public class IndexerController {

    private static final Logger logger = LoggerFactory.getLogger(IndexerController.class);
    private final IndexerService indexerService;

    @Autowired
    public IndexerController(IndexerService indexerService) {
        // We inject the specific IndexerService implementation if multiple exist,
        // or just IndexerService if AnseriniIndexerServiceImpl is the only one or @Primary
        this.indexerService = indexerService;
        logger.info("IndexerController initialized with IndexerService: {}", indexerService.getClass().getSimpleName());
    }

    @PostMapping("/rebuild-all-sources")
    public ResponseEntity<?> rebuildAllSourcesIndex() {
        try {
            logger.info("Received REST request to rebuild index from all configured sources.");
            indexerService.reprocessAndIndexAllSources(); // This method should exist in IndexerService
            logger.info("Index rebuild process from all sources initiated successfully via REST.");
            return ResponseEntity.ok(Map.of("message", "Index rebuild from all sources initiated successfully."));
        } catch (Exception e) {
            logger.error("REST call to rebuild all indexes failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to initiate index rebuild: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getIndexStatus() {
        try {
            boolean isAvailable = indexerService.isIndexAvailable(); // This method should exist
            String statusMessage = isAvailable ?
                    "The index is currently available and appears valid." :
                    "The index is NOT available or is currently invalid. Indexing may be needed or might have failed.";
            logger.info("Reporting index status via REST: {}", statusMessage);
            return ResponseEntity.ok(Map.of(
                    "index_status", isAvailable ? "AVAILABLE" : "NOT_AVAILABLE_OR_INVALID",
                    "message", statusMessage
            ));
        } catch (Exception e) {
            logger.error("REST call to check index status failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to check index status: " + e.getMessage()));
        }
    }
}