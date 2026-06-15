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

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/index-browser")
public class IndexBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(IndexBrowserController.class);

    private IndexerService indexerService;
    private DocumentRetriever documentRetriever;
    private VectorStore vectorStore;
    private final StagingClientService stagingClientService;
    private final StagingServiceConfigService stagingConfigService;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public IndexBrowserController(List<IndexerService> indexerService,
            List<DocumentRetriever> documentRetriever,
            List<VectorStore> vectorStores,
            @Autowired(required = false) StagingClientService stagingClientService,
            @Autowired(required = false) StagingServiceConfigService stagingConfigService,
            @Autowired(required = false) EmbeddingModel embeddingModel) {
        this.stagingClientService = stagingClientService;
        this.stagingConfigService = stagingConfigService;
        this.embeddingModel = embeddingModel;
        // Select non-NoOp indexer if available
        if (indexerService.size() > 1) {
            for (IndexerService indexerService1 : indexerService) {
                if (indexerService1 instanceof NoOpIndexerService) {
                    continue;
                } else {
                    this.indexerService = indexerService1;
                    break;
                }
            }
        } else {
            this.indexerService = indexerService.get(0);
        }

        // Select non-NoOp retriever if available
        if (documentRetriever.size() > 1) {
            for (DocumentRetriever retriever : documentRetriever) {
                if (retriever instanceof NoOpDocumentRetrieverImpl) {
                    continue;
                } else {
                    this.documentRetriever = retriever;
                    break;
                }
            }
        } else {
            this.documentRetriever = documentRetriever.get(0);
        }

        // Select non-NoOp vector store if available
        if (vectorStores != null && !vectorStores.isEmpty()) {
            if (vectorStores.size() > 1) {
                for (VectorStore store : vectorStores) {
                    if (store instanceof NoOpVectorStoreImpl) {
                        continue;
                    } else {
                        this.vectorStore = store;
                        break;
                    }
                }
            }
            if (this.vectorStore == null) {
                this.vectorStore = vectorStores.get(0);
            }
        }

        logger.info("IndexBrowserController initialized with indexer: {}, retriever: {}, vectorStore: {}",
                indexerService.getClass().getSimpleName(),
                documentRetriever.getClass().getSimpleName(),
                vectorStore != null ? vectorStore.getClass().getSimpleName() : "null");
    }

    public record UpdateDocRequest(String content) {
    }

    public record SearchRequest(String query, Integer maxResults, Double similarityThreshold) {
    }

    @GetMapping("/status")
    public ResponseEntity<?> getIndexBrowserStatus() {
        try {
            Map<String, Object> status = new HashMap<>();

            // Get implementation details for keyword indexer
            status.put("indexerImplementation", indexerService.getClass().getSimpleName());
            status.put("indexerFullClassName", indexerService.getClass().getName());
            status.put("retrieverImplementation", documentRetriever.getClass().getSimpleName());
            status.put("retrieverFullClassName", documentRetriever.getClass().getName());

            // Check if keyword index is available
            boolean indexAvailable = indexerService.isIndexAvailable();
            status.put("indexAvailable", indexAvailable);

            // Get document count for keyword index
            try {
                long docCount = indexerService.getApproxTotalDocCount(null);
                status.put("approximateDocumentCount", docCount);
            } catch (Exception e) {
                status.put("approximateDocumentCount", "Error: " + e.getMessage());
            }

            // Get Index Path for keyword index
            status.put("indexPath", indexerService.getIndexPath());

            // Check if implementations are NoOp
            boolean isNoOpIndexer = indexerService.getClass().getSimpleName().contains("NoOp");
            boolean isNoOpRetriever = documentRetriever.getClass().getSimpleName().contains("NoOp");
            status.put("isNoOpIndexer", isNoOpIndexer);
            status.put("isNoOpRetriever", isNoOpRetriever);

            // ═══════════════════════════════════════════════════════════════════════════
            // VECTOR STORE STATUS
            // ═══════════════════════════════════════════════════════════════════════════
            if (vectorStore != null) {
                status.put("vectorStoreImplementation", vectorStore.getClass().getSimpleName());
                status.put("vectorStoreFullClassName", vectorStore.getClass().getName());
                status.put("vectorStoreAvailable", vectorStore.isVectorStoreAvailable());
                status.put("vectorStorePath", vectorStore.getVectorStorePath());
                status.put("isUsingFallbackIndex", vectorStore.isUsingFallbackIndex());

                boolean isNoOpVectorStore = vectorStore.getClass().getSimpleName().contains("NoOp");
                status.put("isNoOpVectorStore", isNoOpVectorStore);

                // Get vector count
                try {
                    long vectorCount = vectorStore.getApproxVectorCount();
                    status.put("approximateVectorCount", vectorCount);
                } catch (Exception e) {
                    status.put("approximateVectorCount", "Error: " + e.getMessage());
                }
            } else {
                status.put("vectorStoreImplementation", "N/A");
                status.put("vectorStoreFullClassName", "N/A");
                status.put("vectorStoreAvailable", false);
                status.put("isNoOpVectorStore", true);
                status.put("approximateVectorCount", 0);
            }

            // Build warning messages
            StringBuilder warnings = new StringBuilder();
            if (isNoOpIndexer || isNoOpRetriever) {
                warnings.append(
                        "One or more components are using NoOp implementations which will not provide actual functionality. ");
            }
            if (vectorStore != null && vectorStore.getClass().getSimpleName().contains("NoOp")) {
                warnings.append("Vector Store is using NoOp implementation. ");
            }
            if (warnings.length() > 0) {
                status.put("warning", warnings.toString().trim());
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // ACTIVE MODEL STATUS (from Staging Manager)
            // ═══════════════════════════════════════════════════════════════════════════

            // Add local embedding model info if available
            if (embeddingModel != null) {
                status.put("activeEmbeddingModel", embeddingModel.getModelIdentifier());
                status.put("embeddingModelName", embeddingModel.getModelName());

                // Always include loading status - this does NOT trigger initialization
                status.put("embeddingModelLoading", embeddingModel.isLoading());
                status.put("embeddingModelLoadingPhase", embeddingModel.getLoadingPhase());
                status.put("embeddingModelLoadingMessage", embeddingModel.getLoadingMessage());

                // Get embedding model status - this triggers lazy initialization
                try {
                    int dimensions = embeddingModel.dimensions();
                    status.put("embeddingDimensions", dimensions);

                    // Check if the model is properly initialized
                    if (dimensions <= 0) {
                        status.put("embeddingModelInitialized", false);

                        // If still loading, show loading message instead of warning
                        if (embeddingModel.isLoading()) {
                            String loadingMsg = embeddingModel.getLoadingMessage();
                            status.put("embeddingModelWarning", loadingMsg != null ? loadingMsg : "Loading model...");
                        } else {
                            status.put("embeddingModelWarning",
                                    "Embedding model not initialized. Configure a staging service or import a model archive.");
                        }

                        // Try to get more specific error info if available
                        String initError = embeddingModel.getInitializationError();
                        if (initError != null && !initError.isBlank()) {
                            // Extract just the first line for the warning
                            int newlineIdx = initError.indexOf('\n');
                            String simpleError = newlineIdx > 0 ? initError.substring(0, newlineIdx) : initError;
                            status.put("embeddingModelError", simpleError);
                        }
                    } else {
                        status.put("embeddingModelInitialized", true);
                    }
                } catch (Exception e) {
                    // Handle any unexpected errors gracefully
                    logger.warn("Could not get embedding model dimensions: {}", e.getMessage());
                    status.put("embeddingDimensions", -1);
                    status.put("embeddingModelInitialized", false);
                    status.put("embeddingModelWarning",
                            "Failed to initialize embedding model: " + e.getMessage());
                }
            } else {
                status.put("embeddingModelInitialized", false);
                status.put("embeddingModelLoading", false);
                status.put("embeddingModelWarning", "No embedding model configured.");
            }

            // Add staging service connection status and active models
            if (stagingConfigService != null && stagingClientService != null) {
                Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
                if (activeConfig.isPresent()) {
                    StagingServiceConfig config = activeConfig.get();
                    status.put("stagingServiceConfigured", true);
                    status.put("stagingServiceName", config.getName());
                    status.put("stagingServiceUrl", config.getEndpointUrl());
                    status.put("stagingServiceVerified", config.isVerified());

                    // Get active models from the staging service
                    try {
                        Optional<Map<String, String>> activeModels = stagingClientService.getActiveModels();
                        if (activeModels.isPresent()) {
                            Map<String, String> models = activeModels.get();
                            status.put("stagingConnected", true);
                            status.put("activeModels", models);
                            // Extract specific model types for easier access
                            if (models.containsKey("encoder") || models.containsKey("dense_encoder")) {
                                status.put("activeEncoder", models.getOrDefault("encoder", models.get("dense_encoder")));
                            }
                            if (models.containsKey("cross_encoder") || models.containsKey("reranker")) {
                                status.put("activeCrossEncoder", models.getOrDefault("cross_encoder", models.get("reranker")));
                            }
                            if (models.containsKey("sparse_encoder")) {
                                status.put("activeSparseEncoder", models.get("sparse_encoder"));
                            }
                        } else {
                            status.put("stagingConnected", false);
                        }
                    } catch (Exception e) {
                        logger.debug("Could not fetch active models from staging service: {}", e.getMessage());
                        status.put("stagingConnected", false);
                    }
                } else {
                    status.put("stagingServiceConfigured", false);
                    status.put("stagingConnected", false);
                }
            } else {
                status.put("stagingServiceConfigured", false);
                status.put("stagingConnected", false);
            }

            logger.info("Index Browser Status - Indexer: {}, Retriever: {}, VectorStore: {}, Index Available: {}",
                    indexerService.getClass().getSimpleName(),
                    documentRetriever.getClass().getSimpleName(),
                    vectorStore != null ? vectorStore.getClass().getSimpleName() : "null",
                    indexAvailable);

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Error getting index browser status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get status: " + e.getMessage()));
        }
    }

    @GetMapping("/documents")
    public ResponseEntity<?> listIndexedDocuments(
            @RequestParam(defaultValue = "0", name = "offset") int offset,
            @RequestParam(defaultValue = "10", name = "limit") int limit) {
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(limit, 500));
        try {
            logger.debug("Received request to list indexed documents. Offset: {}, Limit: {}", offset, limit);
            logger.debug("Using IndexerService implementation: {}", indexerService.getClass().getName());

            List<Map<String, Object>> docs = indexerService.listIndexedDocuments(offset, limit);
            if (docs == null) { // Should not happen if service impl returns empty list on error
                docs = Collections.emptyList();
            }

            logger.debug("IndexerService returned {} documents", docs.size());
            return ResponseEntity.ok(docs);
        } catch (IOException e) {
            logger.error("Error listing indexed documents: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list indexed documents: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error listing indexed documents: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred: " + e.getMessage()));
        }
    }

    @GetMapping("/documents/{docId}")
    public ResponseEntity<?> getIndexedDocument(@PathVariable String docId) {
        try {
            logger.debug("Received request to get indexed document with ID: {}", docId);
            logger.debug("Using IndexerService implementation: {}", indexerService.getClass().getName());

            Map<String, Object> doc = indexerService.getIndexedDocument(docId);
            if (doc == null) {
                logger.debug("Document with ID {} not found", docId);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(doc);
        } catch (IOException e) {
            logger.error("Error retrieving indexed document {}: {}", docId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve indexed document: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error retrieving document {}: {}", docId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred: " + e.getMessage()));
        }
    }

    @PutMapping("/documents/{docId}")
    public ResponseEntity<?> updateIndexedDocument(
            @PathVariable String docId,
            @RequestBody UpdateDocRequest request) {
        if (request == null || request.content() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body must contain 'content'."));
        }
        try {
            logger.debug("Received request to update indexed document with ID: {}", docId);
            logger.debug("Using IndexerService implementation: {}", indexerService.getClass().getName());

            boolean success = indexerService.updateIndexedDocumentContent(docId, request.content());
            if (success) {
                return ResponseEntity
                        .ok(Map.of("message", "Document '" + docId + "' updated successfully in the index."));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to update document '" + docId
                                + "' in the index. Check server logs for details."));
            }
        } catch (IOException e) {
            logger.error("Error updating indexed document {}: {}", docId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update indexed document: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error updating document {}: {}", docId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred: " + e.getMessage()));
        }
    }

    @PostMapping("/search")
    public ResponseEntity<?> searchIndexedDocuments(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Search query cannot be empty."));
        }

        int maxResults = request.maxResults() != null ? request.maxResults() : 10;
        if (maxResults <= 0 || maxResults > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "maxResults must be between 1 and 100."));
        }

        try {
            logger.debug("Received search request: query='{}', maxResults={}", request.query(), maxResults);
            logger.debug("Using DocumentRetriever implementation: {}", documentRetriever.getClass().getName());

            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails(request.query(), maxResults);

            if (results == null) {
                results = Collections.emptyList();
            }

            logger.debug("DocumentRetriever returned {} results", results.size());

            // Convert RetrievedDoc objects to a format suitable for the frontend
            List<Map<String, Object>> searchResults = results.stream()
                    .map(doc -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", doc.getId());
                        result.put("content", doc.getText());
                        result.put("score", doc.getScore());
                        result.put("metadata", doc.getMetadata());

                        // Extract source document information from metadata
                        if (doc.getMetadata() != null) {
                            // Look for common source indicators in metadata
                            Object sourceId = doc.getMetadata().get("source_id");
                            Object fileName = doc.getMetadata().get("file_name");
                            Object originalFileName = doc.getMetadata().get("original_file_name");
                            Object filePath = doc.getMetadata().get("file_path");

                            // Determine original document source
                            String originalDocument = "Unknown";
                            if (sourceId != null) {
                                originalDocument = sourceId.toString();
                            } else if (originalFileName != null) {
                                originalDocument = originalFileName.toString();
                            } else if (fileName != null) {
                                originalDocument = fileName.toString();
                            } else if (filePath != null) {
                                originalDocument = filePath.toString();
                            }

                            result.put("originalDocument", originalDocument);
                        } else {
                            result.put("originalDocument", "Unknown");
                        }

                        // Create a preview of the content
                        String content = doc.getText();
                        if (content != null && content.length() > 200) {
                            result.put("preview", content.substring(0, 200) + "...");
                        } else {
                            result.put("preview", content != null ? content : "[No content]");
                        }

                        return result;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("query", request.query());
            response.put("maxResults", maxResults);
            response.put("totalResults", searchResults.size());
            response.put("results", searchResults);

            // Include total document count in the index for context
            try {
                long totalDocumentCount = indexerService.getApproxTotalDocCount(null);
                response.put("totalDocumentCount", totalDocumentCount);
            } catch (Exception e) {
                logger.warn("Could not get total document count: {}", e.getMessage());
                response.put("totalDocumentCount", -1);
            }

            logger.debug("Search completed: found {} results for query '{}'", searchResults.size(), request.query());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error during search for query '{}': {}", request.query(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VECTOR STORE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/vector-store/documents")
    public ResponseEntity<?> listVectorStoreDocuments(
            @RequestParam(defaultValue = "0", name = "offset") int offset,
            @RequestParam(defaultValue = "10", name = "limit") int limit) {
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(limit, 500));
        try {
            if (vectorStore == null) {
                return ResponseEntity.ok(Collections.emptyList());
            }

            logger.debug("Received request to list vector store documents. Offset: {}, Limit: {}", offset, limit);
            logger.debug("Using VectorStore implementation: {}", vectorStore.getClass().getName());

            List<Map<String, Object>> docs = vectorStore.listVectorDocuments(offset, limit);
            if (docs == null) {
                docs = Collections.emptyList();
            }

            logger.debug("VectorStore returned {} documents", docs.size());
            return ResponseEntity.ok(docs);
        } catch (Exception e) {
            logger.error("Error listing vector store documents: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list vector store documents: " + e.getMessage()));
        }
    }

    @PostMapping("/vector-store/search")
    public ResponseEntity<?> searchVectorStore(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Search query cannot be empty."));
        }

        if (vectorStore == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "VectorStore is not configured."));
        }

        int maxResults = request.maxResults() != null ? request.maxResults() : 10;
        if (maxResults <= 0 || maxResults > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "maxResults must be between 1 and 100."));
        }

        try {
            double threshold = request.similarityThreshold() != null ? request.similarityThreshold() : 0.0;
            logger.debug("Received vector store search request: query='{}', maxResults={}, threshold={}",
                    request.query(), maxResults, threshold);
            logger.debug("Using VectorStore implementation: {}", vectorStore.getClass().getName());

            List<org.springframework.ai.document.Document> results = vectorStore.similaritySearch(request.query(),
                    maxResults, threshold);

            if (results == null) {
                results = Collections.emptyList();
            }

            logger.debug("VectorStore returned {} results", results.size());

            // Convert Spring AI Documents to a format suitable for the frontend
            List<Map<String, Object>> searchResults = results.stream()
                    .map(doc -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", doc.getId());
                        result.put("content", doc.getText());

                        // Extract score from metadata if available
                        double score = 0.0;
                        if (doc.getMetadata() != null && doc.getMetadata().containsKey("score")) {
                            Object scoreObj = doc.getMetadata().get("score");
                            if (scoreObj instanceof Number) {
                                score = ((Number) scoreObj).doubleValue();
                            }
                        }
                        result.put("score", score);
                        result.put("metadata", doc.getMetadata());

                        // Extract source document information from metadata
                        if (doc.getMetadata() != null) {
                            Object sourceId = doc.getMetadata().get("source_id");
                            Object fileName = doc.getMetadata().get("file_name");
                            Object originalFileName = doc.getMetadata().get("original_file_name");
                            Object filePath = doc.getMetadata().get("file_path");

                            String originalDocument = "Unknown";
                            if (sourceId != null) {
                                originalDocument = sourceId.toString();
                            } else if (originalFileName != null) {
                                originalDocument = originalFileName.toString();
                            } else if (fileName != null) {
                                originalDocument = fileName.toString();
                            } else if (filePath != null) {
                                originalDocument = filePath.toString();
                            }

                            result.put("originalDocument", originalDocument);
                        } else {
                            result.put("originalDocument", "Unknown");
                        }

                        // Create a preview of the content
                        String content = doc.getText();
                        if (content != null && content.length() > 200) {
                            result.put("preview", content.substring(0, 200) + "...");
                        } else {
                            result.put("preview", content != null ? content : "[No content]");
                        }

                        return result;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("query", request.query());
            response.put("maxResults", maxResults);
            response.put("similarityThreshold", threshold);
            response.put("totalResults", searchResults.size());
            response.put("results", searchResults);
            response.put("searchType", "vector");

            // Include total vector count for context
            long vectorCount = vectorStore.getApproxVectorCount();
            response.put("totalVectorCount", vectorCount);

            // Add helpful message if no results found
            if (searchResults.isEmpty()) {
                // Check if vector store has any documents
                if (vectorCount <= 0) {
                    response.put("message", "Vector store is empty. Run 'Populate Vector Store' from the Index Browser to enable semantic search.");
                    response.put("vectorStoreEmpty", true);
                    logger.info("Semantic search returned no results because vector store is empty (path: {})",
                            vectorStore.getVectorStorePath());
                } else {
                    String msg = String.format("No semantic matches found for query '%s' with threshold %.2f. " +
                            "Vector store contains %d documents. Try lowering the similarity threshold.",
                            request.query(), threshold, vectorCount);
                    response.put("message", msg);
                    response.put("vectorStoreEmpty", false);
                    logger.info("Vector search returned 0 results: query='{}', threshold={}, vectorCount={}, path={}",
                            request.query(), threshold, vectorCount, vectorStore.getVectorStorePath());
                }
            }

            logger.info("Vector store search completed: found {} results for query '{}' with threshold {}",
                    searchResults.size(), request.query(), threshold);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error during vector store search for query '{}': {}", request.query(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Vector store search failed: " + e.getMessage()));
        }
    }
}
