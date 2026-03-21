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

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService; // From core-abstractions
import ai.kompile.core.indexers.NoOpIndexerService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/indexer")
public class IndexerController {

    private static final Logger logger = LoggerFactory.getLogger(IndexerController.class);
    private IndexerService indexerService;
    private VectorStore vectorStore;

    @Autowired
    public IndexerController(List<IndexerService> indexerService,
                             @Autowired(required = false) List<VectorStore> vectorStores) {
        // yes this looks weird. Graalvm doesn't seem to like spring's
        // conditional injection though qualifiers are also very brittle
        if (indexerService.size() > 1) {
            for (IndexerService indexerService1 : indexerService) {
                if (indexerService1 instanceof NoOpIndexerService) {
                    continue;
                } else {
                    this.indexerService = indexerService1;
                }
            }
        } else {
            this.indexerService = indexerService.get(0);
        }

        // Select non-NoOp vector store
        if (vectorStores != null && !vectorStores.isEmpty()) {
            for (VectorStore vs : vectorStores) {
                if (!vs.getClass().getSimpleName().contains("NoOp")) {
                    this.vectorStore = vs;
                    break;
                }
            }
            if (this.vectorStore == null) {
                this.vectorStore = vectorStores.get(0);
            }
        }

        logger.info("IndexerController initialized with IndexerService: {}, VectorStore: {}",
                this.indexerService != null ? this.indexerService.getClass().getSimpleName() : "none",
                this.vectorStore != null ? this.vectorStore.getClass().getSimpleName() : "none");
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

    @PostMapping("/vector-index/start")
    public ResponseEntity<?> startVectorIndexCreation() {
        try {
            logger.info("Received REST request to start async vector index creation from Lucene.");
            boolean started = indexerService.startVectorIndexCreationAsync();
            if (started) {
                return ResponseEntity.ok(Map.of("message", "Vector index creation job started."));
            } else {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "A vector index creation job is already running."));
            }
        } catch (Exception e) {
            logger.error("Failed to start vector index creation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start job: " + e.getMessage()));
        }
    }

    @PostMapping("/vector-index/cancel")
    public ResponseEntity<?> cancelVectorIndexCreation() {
        try {
            indexerService.cancelCurrentJob();
            return ResponseEntity.ok(Map.of("message", "Cancellation requested."));
        } catch (Exception e) {
            logger.error("Failed to cancel vector index creation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to cancel job: " + e.getMessage()));
        }
    }

    @GetMapping("/vector-index/status")
    public ResponseEntity<?> getVectorIndexJobStatus() {
        return ResponseEntity.ok(indexerService.getJobStatus());
    }

    // Deprecated alias for backward compatibility or direct calls
    @PostMapping("/index-from-lucene")
    public ResponseEntity<?> indexFromLucene() {
        return startVectorIndexCreation();
    }

    @GetMapping("/status")
    public ResponseEntity<?> getIndexStatus() {
        try {
            Map<String, Object> status = new HashMap<>();

            // Keyword index status
            boolean isAvailable = indexerService.isIndexAvailable();
            String statusMessage = isAvailable ? "The index is currently available and appears valid."
                    : "The index is NOT available or is currently invalid. Indexing may be needed or might have failed.";
            status.put("index_status", isAvailable ? "AVAILABLE" : "NOT_AVAILABLE_OR_INVALID");
            status.put("indexAvailable", isAvailable);
            status.put("message", statusMessage);
            status.put("indexerImplementation", indexerService.getClass().getSimpleName());
            status.put("isNoOpIndexer", indexerService instanceof NoOpIndexerService);

            // Get keyword index path
            try {
                status.put("indexPath", indexerService.getIndexPath());
                status.put("keywordIndexPath", indexerService.getIndexPath());
            } catch (Exception e) {
                logger.debug("Could not get keyword index details: {}", e.getMessage());
            }

            // Vector store status
            if (vectorStore != null) {
                String vectorPath = vectorStore.getVectorStorePath();
                boolean isAvailableVector = vectorStore.isVectorStoreAvailable();
                status.put("vectorStoreImplementation", vectorStore.getClass().getSimpleName());
                status.put("vectorStoreAvailable", isAvailableVector);
                status.put("vectorStorePath", vectorPath);
                status.put("isNoOpVectorStore", vectorStore.getClass().getSimpleName().contains("NoOp"));
                status.put("isUsingFallbackIndex", vectorStore.isUsingFallbackIndex());

                // Log diagnostic info
                logger.info("Vector store status check: path={}, available={}", vectorPath, isAvailableVector);

                try {
                    long vectorCount = vectorStore.getApproxVectorCount();
                    status.put("approximateVectorCount", vectorCount);
                    status.put("vectorDocumentCount", vectorCount);
                    logger.info("Vector store count: {} at path {}", vectorCount, vectorPath);
                } catch (Exception e) {
                    status.put("approximateVectorCount", 0);
                    status.put("vectorCountError", e.getMessage());
                    logger.warn("Could not get vector count from path {}: {}", vectorPath, e.getMessage());
                }
            } else {
                status.put("vectorStoreImplementation", "N/A");
                status.put("vectorStoreAvailable", false);
                status.put("isNoOpVectorStore", true);
                status.put("approximateVectorCount", 0);
            }

            logger.info("Reporting index status via REST: {}", statusMessage);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("REST call to check index status failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to check index status: " + e.getMessage()));
        }
    }
}