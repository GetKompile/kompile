package ai.kompile.app.web.controllers;

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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/index-browser")
public class IndexBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(IndexBrowserController.class);

    private  IndexerService indexerService;
    private  DocumentRetriever documentRetriever;

    @Autowired
    public IndexBrowserController(List<IndexerService> indexerService,
                                  List<DocumentRetriever> documentRetriever) {
        if(indexerService.size() > 1) {
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


        if(documentRetriever.size() > 1) {
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

    }

    public record UpdateDocRequest(String content) {}
    public record SearchRequest(String query, Integer maxResults) {}

    @GetMapping("/status")
    public ResponseEntity<?> getIndexBrowserStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            
            // Get implementation details
            status.put("indexerImplementation", indexerService.getClass().getSimpleName());
            status.put("indexerFullClassName", indexerService.getClass().getName());
            status.put("retrieverImplementation", documentRetriever.getClass().getSimpleName());
            status.put("retrieverFullClassName", documentRetriever.getClass().getName());
            
            // Check if index is available
            boolean indexAvailable = indexerService.isIndexAvailable();
            status.put("indexAvailable", indexAvailable);
            
            // Get document count if possible
            try {
                long docCount = indexerService.getApproxTotalDocCount(null);
                status.put("approximateDocumentCount", docCount);
            } catch (Exception e) {
                status.put("approximateDocumentCount", "Error: " + e.getMessage());
            }
            
            // Check if implementations are NoOp
            boolean isNoOpIndexer = indexerService.getClass().getSimpleName().contains("NoOp");
            boolean isNoOpRetriever = documentRetriever.getClass().getSimpleName().contains("NoOp");
            status.put("isNoOpIndexer", isNoOpIndexer);
            status.put("isNoOpRetriever", isNoOpRetriever);
            
            if (isNoOpIndexer || isNoOpRetriever) {
                status.put("warning", "One or more components are using NoOp implementations which will not provide actual functionality");
            }
            
            logger.info("Index Browser Status - Indexer: {}, Retriever: {}, Index Available: {}", 
                       indexerService.getClass().getSimpleName(), 
                       documentRetriever.getClass().getSimpleName(), 
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
            @RequestParam(defaultValue = "0",name = "offset") int offset,
            @RequestParam(defaultValue = "10",name = "limit") int limit) {
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
        }  catch (Exception e) {
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
                return ResponseEntity.ok(Map.of("message", "Document '" + docId + "' updated successfully in the index."));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to update document '" + docId + "' in the index. Check server logs for details."));
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
                        result.put("content", doc.getContent());
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
                        String content = doc.getContent();
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

            logger.debug("Search completed: found {} results for query '{}'", searchResults.size(), request.query());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error during search for query '{}': {}", request.query(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }
}
