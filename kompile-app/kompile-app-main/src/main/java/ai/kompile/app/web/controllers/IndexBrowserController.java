package ai.kompile.app.web.controllers;

import ai.kompile.core.indexers.IndexerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/index-browser")
public class IndexBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(IndexBrowserController.class);

    private final IndexerService indexerService;

    @Autowired
    public IndexBrowserController(IndexerService indexerService) {
        this.indexerService = indexerService;
    }

    public record UpdateDocRequest(String content) {}


    @GetMapping("/documents")
    public ResponseEntity<?> listIndexedDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            logger.debug("Received request to list indexed documents. Offset: {}, Limit: {}", offset, limit);
            List<Map<String, Object>> docs = indexerService.listIndexedDocuments(offset, limit);
            if (docs == null) { // Should not happen if service impl returns empty list on error
                docs = Collections.emptyList();
            }
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
            Map<String, Object> doc = indexerService.getIndexedDocument(docId);
            if (doc == null) {
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
}