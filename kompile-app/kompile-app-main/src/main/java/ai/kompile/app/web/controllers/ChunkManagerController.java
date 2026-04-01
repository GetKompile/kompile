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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.anserini.AnseriniIndexerServiceImpl;
import ai.kompile.app.services.ChunkDeduplicationService;
import ai.kompile.app.web.dto.ChunkManagerDtos;
import ai.kompile.app.web.dto.ChunkManagerDtos.*;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

/**
 * REST Controller for chunk management operations.
 * Provides endpoints for browsing, managing, exporting, and deduplicating chunks
 * stored in the vector store.
 */
@Slf4j
@RestController
@RequestMapping("/api/chunk-manager")
public class ChunkManagerController {

    private static final String CLEAR_CONFIRMATION_PREFIX = "CLEAR_ALL_";

    private final VectorStore vectorStore;
    private final ChunkDeduplicationService deduplicationService;
    private final AnseriniIndexerServiceImpl indexerService;

    // Store confirmation tokens with expiry (simple in-memory approach)
    private final Map<String, Long> confirmationTokens = new HashMap<>();

    @Autowired
    public ChunkManagerController(
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) ChunkDeduplicationService deduplicationService,
            @Autowired(required = false) @Qualifier("anseriniIndexerService") IndexerService indexerService) {
        this.vectorStore = vectorStore;
        this.deduplicationService = deduplicationService;
        // Cast to AnseriniIndexerServiceImpl to access keyword index specific methods
        this.indexerService = (indexerService instanceof AnseriniIndexerServiceImpl)
                ? (AnseriniIndexerServiceImpl) indexerService
                : null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LISTING & BROWSING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * List chunks with pagination from both keyword index and vector store.
     * Chunks are unified by ID, with location flags indicating where they exist.
     */
    @GetMapping("/chunks")
    public ResponseEntity<?> listChunks(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String sourceId) {

        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.ok(new ChunkListResponse(List.of(), offset, limit, 0, 0,
                    new IndexStats(0, 0)));
        }

        try {
            // Track which IDs exist in each index
            Set<String> vectorIds = new HashSet<>();
            Set<String> keywordIds = new HashSet<>();
            Map<String, Map<String, Object>> unifiedDocs = new LinkedHashMap<>();

            // Get stats for the response
            long vectorCount = vectorStore != null ? vectorStore.getApproxVectorCount() : 0;
            long keywordCount = indexerService != null ? indexerService.getApproxTotalDocCount(null) : 0;
            IndexStats stats = new IndexStats(keywordCount, vectorCount);

            // Collect from keyword index first (has more docs in this case)
            if (indexerService != null) {
                List<Map<String, Object>> keywordDocs;
                if (sourceId != null && !sourceId.isEmpty()) {
                    List<String> matchingIds = indexerService.getKeywordDocumentIdsBySourceId(sourceId);
                    keywordIds.addAll(matchingIds);
                    // Get full docs for pagination
                    keywordDocs = new ArrayList<>();
                    for (String id : matchingIds) {
                        try {
                            Map<String, Object> doc = indexerService.getIndexedDocument(id);
                            if (doc != null) {
                                keywordDocs.add(doc);
                            }
                        } catch (IOException e) {
                            log.debug("Could not get keyword doc {}: {}", id, e.getMessage());
                        }
                    }
                } else {
                    try {
                        // Get more than needed to account for unified dedup
                        keywordDocs = indexerService.listIndexedDocuments(0, offset + limit + 100);
                        for (Map<String, Object> doc : keywordDocs) {
                            String id = (String) doc.get("id");
                            if (id != null) keywordIds.add(id);
                        }
                    } catch (IOException e) {
                        log.error("Error listing keyword index documents: {}", e.getMessage());
                        keywordDocs = new ArrayList<>();
                    }
                }

                for (Map<String, Object> doc : keywordDocs) {
                    String id = (String) doc.get("id");
                    if (id != null) {
                        unifiedDocs.put(id, doc);
                    }
                }
            }

            // Collect from vector store
            if (vectorStore != null) {
                List<Map<String, Object>> vectorDocs;
                if (sourceId != null && !sourceId.isEmpty()) {
                    List<String> matchingIds = vectorStore.getDocumentIdsBySourceId(sourceId);
                    vectorIds.addAll(matchingIds);
                    vectorDocs = vectorStore.getAllVectorDocuments().stream()
                            .filter(doc -> matchingIds.contains(doc.get("id")))
                            .collect(Collectors.toList());
                } else {
                    vectorDocs = vectorStore.listVectorDocuments(0, offset + limit + 100);
                    for (Map<String, Object> doc : vectorDocs) {
                        String id = (String) doc.get("id");
                        if (id != null) vectorIds.add(id);
                    }
                }

                for (Map<String, Object> doc : vectorDocs) {
                    String id = (String) doc.get("id");
                    if (id != null) {
                        // Merge with existing or add new
                        if (!unifiedDocs.containsKey(id)) {
                            unifiedDocs.put(id, doc);
                        }
                        // Either way, mark as in vector store
                    }
                }
            }

            // Create summaries with location info
            List<ChunkSummary> allChunks = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : unifiedDocs.entrySet()) {
                String id = entry.getKey();
                boolean inKeyword = keywordIds.contains(id);
                boolean inVector = vectorIds.contains(id);
                ChunkSummary summary = ChunkSummary.fromDocument(entry.getValue(), inKeyword, inVector);
                allChunks.add(summary);
            }

            // Apply pagination
            long totalCount = allChunks.size();
            List<ChunkSummary> paginatedChunks = allChunks.stream()
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());

            int pageCount = (int) Math.ceil((double) totalCount / limit);

            return ResponseEntity.ok(new ChunkListResponse(paginatedChunks, offset, limit, totalCount, pageCount, stats));
        } catch (Exception e) {
            log.error("Error listing chunks: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list chunks: " + e.getMessage()));
        }
    }

    /**
     * Get detailed information about a single chunk from either index.
     * Checks both keyword index and vector store, returning location flags.
     */
    @GetMapping("/chunks/{id}")
    public ResponseEntity<?> getChunk(@PathVariable String id) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            Map<String, Object> doc = null;
            boolean inKeyword = false;
            boolean inVector = false;

            // Check vector store
            if (vectorStore != null) {
                Map<String, Object> vectorDoc = vectorStore.getVectorDocument(id);
                if (vectorDoc != null) {
                    doc = vectorDoc;
                    inVector = true;
                }
            }

            // Check keyword index
            if (indexerService != null) {
                try {
                    Map<String, Object> keywordDoc = indexerService.getIndexedDocument(id);
                    if (keywordDoc != null) {
                        if (doc == null) {
                            doc = keywordDoc;
                        }
                        inKeyword = true;
                    }
                } catch (IOException e) {
                    log.debug("Could not get chunk from keyword index: {}", e.getMessage());
                }
            }

            if (doc == null) {
                return ResponseEntity.notFound().build();
            }

            ChunkDetail detail = ChunkDetail.fromDocument(doc, inKeyword, inVector);
            return ResponseEntity.ok(detail);
        } catch (Exception e) {
            log.error("Error getting chunk {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get chunk: " + e.getMessage()));
        }
    }

    /**
     * List unique source documents from both keyword index and vector store.
     */
    @GetMapping("/sources")
    public ResponseEntity<?> listSources() {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.ok(new SourceListResponse(List.of(), 0));
        }

        try {
            // Collect source IDs and counts from both indexes
            Map<String, int[]> sourceCounts = new HashMap<>(); // [keywordCount, vectorCount]

            // Get sources from keyword index
            if (indexerService != null) {
                List<String> keywordSourceIds = indexerService.getKeywordUniqueSourceIds();
                for (String sourceId : keywordSourceIds) {
                    List<String> chunkIds = indexerService.getKeywordDocumentIdsBySourceId(sourceId);
                    int[] counts = sourceCounts.computeIfAbsent(sourceId, k -> new int[2]);
                    counts[0] = chunkIds.size(); // keyword count
                }
            }

            // Get sources from vector store
            if (vectorStore != null) {
                List<String> vectorSourceIds = vectorStore.getUniqueSourceIds();
                for (String sourceId : vectorSourceIds) {
                    List<String> chunkIds = vectorStore.getDocumentIdsBySourceId(sourceId);
                    int[] counts = sourceCounts.computeIfAbsent(sourceId, k -> new int[2]);
                    counts[1] = chunkIds.size(); // vector count
                }
            }

            // Build source info with combined counts
            List<SourceInfo> sources = sourceCounts.entrySet().stream()
                    .map(entry -> {
                        String sourceId = entry.getKey();
                        int[] counts = entry.getValue();
                        int keywordCount = counts[0];
                        int vectorCount = counts[1];
                        int totalCount = Math.max(keywordCount, vectorCount); // Use max as total
                        String filename = extractFilename(sourceId);
                        return new SourceInfo(sourceId, filename, totalCount, keywordCount, vectorCount);
                    })
                    .sorted(Comparator.comparing(SourceInfo::filename))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(new SourceListResponse(sources, sources.size()));
        } catch (Exception e) {
            log.error("Error listing sources: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list sources: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update a chunk's content and metadata.
     * Updates both vector store and keyword index.
     */
    @PutMapping("/chunks/{id}")
    public ResponseEntity<?> updateChunk(@PathVariable String id, @RequestBody ChunkUpdateRequest request) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.badRequest()
                    .body(new ChunkUpdateResponse(false, "Neither VectorStore nor IndexerService available", null));
        }

        try {
            // Get existing document to merge with updates
            Map<String, Object> existingDoc = null;
            boolean inKeyword = false;
            boolean inVector = false;

            if (vectorStore != null) {
                existingDoc = vectorStore.getVectorDocument(id);
                if (existingDoc != null) {
                    inVector = true;
                }
            }

            if (indexerService != null) {
                try {
                    Map<String, Object> keywordDoc = indexerService.getIndexedDocument(id);
                    if (keywordDoc != null) {
                        if (existingDoc == null) {
                            existingDoc = keywordDoc;
                        }
                        inKeyword = true;
                    }
                } catch (IOException e) {
                    log.debug("Could not get chunk from keyword index: {}", e.getMessage());
                }
            }

            if (existingDoc == null) {
                return ResponseEntity.notFound().build();
            }

            // Build updated document
            Map<String, Object> updatedDoc = new HashMap<>(existingDoc);

            // Update content if provided
            if (request.content() != null) {
                updatedDoc.put("content", request.content());
                // Update preview
                String content = request.content();
                String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                updatedDoc.put("preview", preview);
            }

            // Update metadata
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = new HashMap<>((Map<String, Object>) updatedDoc.getOrDefault("metadata", new HashMap<>()));

            if (request.semanticType() != null) {
                metadata.put("semantic_type", request.semanticType());
            }
            if (request.sourceTitle() != null) {
                metadata.put("source_title", request.sourceTitle());
            }
            if (request.sourceAuthor() != null) {
                metadata.put("source_author", request.sourceAuthor());
            }
            if (request.sourceDate() != null) {
                metadata.put("source_date", request.sourceDate());
            }
            if (request.sourceUrl() != null) {
                metadata.put("source_url", request.sourceUrl());
            }

            // Handle entities
            if (request.entities() != null) {
                metadata.put("entity_count", request.entities().size());
                // Convert entities to JSON string for storage
                StringBuilder entitiesJson = new StringBuilder("[");
                for (int i = 0; i < request.entities().size(); i++) {
                    ChunkEntityDto entity = request.entities().get(i);
                    if (i > 0) entitiesJson.append(",");
                    entitiesJson.append("{\"name\":\"").append(escapeJson(entity.name()))
                            .append("\",\"type\":\"").append(entity.type()).append("\"");
                    if (entity.confidence() != null) {
                        entitiesJson.append(",\"confidence\":").append(entity.confidence());
                    }
                    entitiesJson.append("}");
                }
                entitiesJson.append("]");
                metadata.put("entities_json", entitiesJson.toString());
            }

            // Merge additional metadata if provided
            if (request.metadata() != null) {
                metadata.putAll(request.metadata());
            }

            // Mark as updated
            metadata.put("updated_at", Instant.now().toString());

            updatedDoc.put("metadata", metadata);

            boolean updateSuccess = true;
            String content = (String) updatedDoc.get("content");

            // Update in vector store (delete and re-add with new embedding)
            if (inVector && vectorStore != null) {
                try {
                    // Delete old entry
                    vectorStore.delete(List.of(id));
                    // Create Spring AI Document with the updated content and metadata
                    Document springDoc = new Document(id, content, metadata);
                    // Re-add with new content (this will generate new embedding)
                    int added = vectorStore.add(List.of(springDoc));
                    if (added < 1) {
                        log.warn("Vector store returned 0 documents added for chunk update {}", id);
                        updateSuccess = false;
                    }
                } catch (Exception e) {
                    log.error("Error updating vector store: {}", e.getMessage());
                    updateSuccess = false;
                }
            }

            // Update in keyword index
            if (inKeyword && indexerService != null) {
                try {
                    // Delete and re-index
                    indexerService.deleteFromKeywordIndex(List.of(id));
                    // Create RetrievedDoc for keyword indexing
                    RetrievedDoc retrievedDoc = RetrievedDoc.builder()
                            .id(id)
                            .text(content)
                            .metadata(metadata)
                            .build();
                    // Index to keyword index only (don't re-embed for vector store)
                    indexerService.indexToKeywordIndexOnly(List.of(retrievedDoc));
                } catch (IOException e) {
                    log.error("Error updating keyword index: {}", e.getMessage());
                    updateSuccess = false;
                }
            }

            if (updateSuccess) {
                ChunkEditDetail updatedDetail = ChunkEditDetail.fromDocument(updatedDoc, inKeyword, inVector);
                return ResponseEntity.ok(new ChunkUpdateResponse(true, "Chunk updated successfully", updatedDetail));
            } else {
                return ResponseEntity.internalServerError()
                        .body(new ChunkUpdateResponse(false, "Failed to update chunk in some indexes", null));
            }

        } catch (Exception e) {
            log.error("Error updating chunk {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ChunkUpdateResponse(false, "Error: " + e.getMessage(), null));
        }
    }

    /**
     * Get available semantic types.
     */
    @GetMapping("/semantic-types")
    public ResponseEntity<?> getSemanticTypes() {
        return ResponseEntity.ok(Map.of("types", ChunkManagerDtos.SEMANTIC_TYPES));
    }

    /**
     * Get available entity types.
     */
    @GetMapping("/entity-types")
    public ResponseEntity<?> getEntityTypes() {
        return ResponseEntity.ok(Map.of("types", ChunkManagerDtos.ENTITY_TYPES));
    }

    /**
     * Get chunk for editing with full metadata.
     */
    @GetMapping("/chunks/{id}/edit")
    public ResponseEntity<?> getChunkForEdit(@PathVariable String id) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            Map<String, Object> doc = null;
            boolean inKeyword = false;
            boolean inVector = false;

            // Check vector store
            if (vectorStore != null) {
                Map<String, Object> vectorDoc = vectorStore.getVectorDocument(id);
                if (vectorDoc != null) {
                    doc = vectorDoc;
                    inVector = true;
                }
            }

            // Check keyword index
            if (indexerService != null) {
                try {
                    Map<String, Object> keywordDoc = indexerService.getIndexedDocument(id);
                    if (keywordDoc != null) {
                        if (doc == null) {
                            doc = keywordDoc;
                        }
                        inKeyword = true;
                    }
                } catch (IOException e) {
                    log.debug("Could not get chunk from keyword index: {}", e.getMessage());
                }
            }

            if (doc == null) {
                return ResponseEntity.notFound().build();
            }

            ChunkEditDetail detail = ChunkEditDetail.fromDocument(doc, inKeyword, inVector);
            return ResponseEntity.ok(detail);
        } catch (Exception e) {
            log.error("Error getting chunk for edit {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get chunk: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete a single chunk from both vector store and keyword index.
     */
    @DeleteMapping("/chunks/{id}")
    public ResponseEntity<?> deleteChunk(@PathVariable String id) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "Neither VectorStore nor IndexerService available", 0));
        }

        try {
            List<String> ids = List.of(id);
            boolean vectorSuccess = true;
            boolean keywordSuccess = true;

            // Delete from vector store
            if (vectorStore != null) {
                vectorSuccess = vectorStore.delete(ids);
            }

            // Delete from keyword index (synchronized)
            if (indexerService != null) {
                keywordSuccess = indexerService.deleteFromKeywordIndex(ids);
            }

            boolean success = vectorSuccess && keywordSuccess;
            String message = success ? "Chunk deleted from both indexes"
                    : String.format("Partial deletion: vector=%s, keyword=%s", vectorSuccess, keywordSuccess);

            return ResponseEntity.ok(new OperationResponse(success, message, success ? 1 : 0));
        } catch (Exception e) {
            log.error("Error deleting chunk {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new OperationResponse(false, "Error: " + e.getMessage(), 0));
        }
    }

    /**
     * Delete multiple chunks from both vector store and keyword index.
     */
    @DeleteMapping("/chunks")
    public ResponseEntity<?> deleteChunks(@RequestBody List<String> ids) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "Neither VectorStore nor IndexerService available", 0));
        }

        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "No chunk IDs provided", 0));
        }

        try {
            boolean vectorSuccess = true;
            boolean keywordSuccess = true;

            // Delete from vector store
            if (vectorStore != null) {
                vectorSuccess = vectorStore.delete(ids);
            }

            // Delete from keyword index (synchronized)
            if (indexerService != null) {
                keywordSuccess = indexerService.deleteFromKeywordIndex(ids);
            }

            boolean success = vectorSuccess && keywordSuccess;
            String message = success
                    ? String.format("Deleted %d chunks from both indexes", ids.size())
                    : String.format("Partial deletion: vector=%s, keyword=%s", vectorSuccess, keywordSuccess);

            return ResponseEntity.ok(new OperationResponse(success, message, success ? ids.size() : 0));
        } catch (Exception e) {
            log.error("Error deleting chunks: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new OperationResponse(false, "Error: " + e.getMessage(), 0));
        }
    }

    /**
     * Delete all chunks from a specific source from both vector store and keyword index.
     */
    @DeleteMapping("/chunks/by-source")
    public ResponseEntity<?> deleteBySource(@RequestBody DeleteBySourceRequest request) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "Neither VectorStore nor IndexerService available", 0));
        }

        if (request.sourceId() == null || request.sourceId().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "Source ID is required", 0));
        }

        try {
            // Collect IDs from both indexes to ensure complete deletion
            Set<String> allChunkIds = new HashSet<>();

            if (vectorStore != null) {
                List<String> vectorChunkIds = vectorStore.getDocumentIdsBySourceId(request.sourceId());
                allChunkIds.addAll(vectorChunkIds);
            }

            if (indexerService != null) {
                List<String> keywordChunkIds = indexerService.getKeywordDocumentIdsBySourceId(request.sourceId());
                allChunkIds.addAll(keywordChunkIds);
            }

            if (allChunkIds.isEmpty()) {
                return ResponseEntity.ok(new OperationResponse(true, "No chunks found for this source", 0));
            }

            List<String> chunkIdsList = new ArrayList<>(allChunkIds);
            boolean vectorSuccess = true;
            boolean keywordSuccess = true;

            // Delete from vector store
            if (vectorStore != null) {
                vectorSuccess = vectorStore.delete(chunkIdsList);
            }

            // Delete from keyword index
            if (indexerService != null) {
                keywordSuccess = indexerService.deleteFromKeywordIndex(chunkIdsList);
            }

            boolean success = vectorSuccess && keywordSuccess;
            String message = success
                    ? String.format("Deleted %d chunks from source in both indexes", chunkIdsList.size())
                    : String.format("Partial deletion: vector=%s, keyword=%s", vectorSuccess, keywordSuccess);

            return ResponseEntity.ok(new OperationResponse(success, message, success ? chunkIdsList.size() : 0));
        } catch (Exception e) {
            log.error("Error deleting chunks by source: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new OperationResponse(false, "Error: " + e.getMessage(), 0));
        }
    }

    /**
     * Generate a confirmation token for clearing all chunks.
     * Token is valid for 60 seconds.
     */
    @PostMapping("/clear-all/token")
    public ResponseEntity<?> generateClearToken() {
        String token = CLEAR_CONFIRMATION_PREFIX + UUID.randomUUID().toString();
        long expiry = Instant.now().plusSeconds(60).toEpochMilli();
        confirmationTokens.put(token, expiry);

        // Cleanup old tokens
        cleanupExpiredTokens();

        return ResponseEntity.ok(Map.of(
                "token", token,
                "expiresIn", 60,
                "message", "Use this token within 60 seconds to confirm clearing all chunks"
        ));
    }

    /**
     * Clear all chunks from both vector store and keyword index.
     * Requires a valid confirmation token.
     */
    @DeleteMapping("/clear-all")
    public ResponseEntity<?> clearAll(@RequestBody ClearAllRequest request) {
        if (vectorStore == null && indexerService == null) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "Neither VectorStore nor IndexerService available", 0));
        }

        // Validate token
        String token = request.confirmationToken();
        if (token == null || !token.startsWith(CLEAR_CONFIRMATION_PREFIX)) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false, "Invalid confirmation token", 0));
        }

        Long expiry = confirmationTokens.remove(token);
        if (expiry == null || expiry < Instant.now().toEpochMilli()) {
            return ResponseEntity.badRequest()
                    .body(new OperationResponse(false,
                            "Confirmation token expired or invalid. Please generate a new token.", 0));
        }

        try {
            long vectorCountBefore = vectorStore != null ? vectorStore.getApproxVectorCount() : 0;
            long keywordCountBefore = indexerService != null ? indexerService.getApproxTotalDocCount(null) : 0;
            long totalCountBefore = Math.max(vectorCountBefore, keywordCountBefore);

            boolean vectorSuccess = true;
            boolean keywordSuccess = true;

            // Clear vector store
            if (vectorStore != null) {
                vectorSuccess = vectorStore.deleteAll();
            }

            // Clear keyword index
            if (indexerService != null) {
                keywordSuccess = indexerService.deleteAllFromKeywordIndex();
            }

            boolean success = vectorSuccess && keywordSuccess;
            String message = success
                    ? String.format("Cleared all chunks from both indexes (vector: %d, keyword: %d)",
                            vectorCountBefore, keywordCountBefore)
                    : String.format("Partial clear: vector=%s, keyword=%s", vectorSuccess, keywordSuccess);

            return ResponseEntity.ok(new OperationResponse(success, message, success ? (int) totalCountBefore : 0));
        } catch (Exception e) {
            log.error("Error clearing all chunks: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new OperationResponse(false, "Error: " + e.getMessage(), 0));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEDUPLICATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Analyze duplicates without removing them.
     */
    @GetMapping("/duplicates")
    public ResponseEntity<?> analyzeDuplicates(
            @RequestParam(defaultValue = "content_hash") String strategy) {

        if (deduplicationService == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Deduplication service not available"));
        }

        try {
            DuplicateAnalysisResponse response = deduplicationService.analyzeDuplicates(strategy);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error analyzing duplicates: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to analyze duplicates: " + e.getMessage()));
        }
    }

    /**
     * Remove duplicate chunks.
     */
    @PostMapping("/deduplicate")
    public ResponseEntity<?> deduplicate(@RequestBody DeduplicationRequest request) {
        if (deduplicationService == null) {
            return ResponseEntity.badRequest()
                    .body(new DeduplicationResult(request.strategy(), 0, 0, 0, false,
                            "Deduplication service not available"));
        }

        try {
            DeduplicationResult result = deduplicationService.deduplicate(
                    request.strategy(),
                    request.keepPolicy(),
                    request.dryRun()
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error during deduplication: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new DeduplicationResult(request.strategy(), 0, 0, 0, false,
                            "Error: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPORT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Export chunks to markdown format.
     */
    @PostMapping("/export")
    public ResponseEntity<?> exportChunks(@RequestBody ExportRequest request) {
        if (vectorStore == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "VectorStore not available"));
        }

        try {
            List<Map<String, Object>> docsToExport;

            if (request.chunkIds() != null && !request.chunkIds().isEmpty()) {
                // Export specific chunks
                docsToExport = request.chunkIds().stream()
                        .map(vectorStore::getVectorDocument)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } else if (request.sourceId() != null && !request.sourceId().isEmpty()) {
                // Export all chunks from a source
                List<String> ids = vectorStore.getDocumentIdsBySourceId(request.sourceId());
                docsToExport = ids.stream()
                        .map(vectorStore::getVectorDocument)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } else {
                // Export all chunks (with reasonable limit)
                docsToExport = vectorStore.listVectorDocuments(0, 1000);
            }

            String content = generateMarkdownExport(docsToExport, request.includeMetadata());
            String filename = "chunks_export_" + Instant.now().getEpochSecond() + ".md";

            return ResponseEntity.ok(new ExportResponse("markdown", content, docsToExport.size(), filename));
        } catch (Exception e) {
            log.error("Error exporting chunks: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to export chunks: " + e.getMessage()));
        }
    }

    /**
     * Download chunks as a markdown file.
     */
    @PostMapping("/export/download")
    public ResponseEntity<?> downloadExport(@RequestBody ExportRequest request) {
        ResponseEntity<?> exportResponse = exportChunks(request);

        if (exportResponse.getBody() instanceof ExportResponse export) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + export.filename())
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .body(export.content());
        }

        return exportResponse;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private String generateMarkdownExport(List<Map<String, Object>> docs, boolean includeMetadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Chunk Export\n\n");
        sb.append("Exported: ").append(Instant.now().toString()).append("\n");
        sb.append("Total chunks: ").append(docs.size()).append("\n\n");
        sb.append("---\n\n");

        for (int i = 0; i < docs.size(); i++) {
            Map<String, Object> doc = docs.get(i);
            String id = (String) doc.get("id");
            String content = (String) doc.get("content");

            sb.append("## Chunk ").append(i + 1).append("\n\n");
            sb.append("**ID:** `").append(id).append("`\n\n");

            if (includeMetadata) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
                if (metadata != null && !metadata.isEmpty()) {
                    sb.append("**Metadata:**\n");
                    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                        sb.append("- ").append(entry.getKey()).append(": ")
                                .append(entry.getValue()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            sb.append("**Content:**\n\n");
            sb.append(content != null ? content : "[No content]");
            sb.append("\n\n---\n\n");
        }

        return sb.toString();
    }

    private String extractFilename(String sourceId) {
        if (sourceId == null) {
            return "Unknown";
        }
        int lastSlash = Math.max(sourceId.lastIndexOf('/'), sourceId.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < sourceId.length() - 1) {
            return sourceId.substring(lastSlash + 1);
        }
        return sourceId;
    }

    private void cleanupExpiredTokens() {
        long now = Instant.now().toEpochMilli();
        confirmationTokens.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
