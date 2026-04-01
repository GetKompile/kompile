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

package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedDocument.IndexStatus;
import ai.kompile.app.ingest.domain.IndexedDocument.OverallIndexStatus;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.app.ingest.repository.IndexedDocumentRepository;
import ai.kompile.app.ingest.repository.IndexedPassageRepository;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for tracking document and passage indexing status across multiple indexes.
 * Provides methods to register, update, and query cross-index status.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CrossIndexTrackingService {

    private final IndexedDocumentRepository documentRepository;
    private final IndexedPassageRepository passageRepository;

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Register a new document for tracking.
     */
    public IndexedDocument registerDocument(String sourceId, String fileName,
                                             String checksum, Long factId, Long factSheetId) {
        // Check if document already exists
        Optional<IndexedDocument> existing = documentRepository.findBySourceIdAndFactSheetId(sourceId, factSheetId);
        if (existing.isPresent()) {
            IndexedDocument doc = existing.get();
            // Check if checksum changed (document modified)
            if (checksum != null && !checksum.equals(doc.getChecksum())) {
                log.info("Document {} has changed, marking as stale", sourceId);
                doc.setChecksum(checksum);
                doc.markAsStale();
                return documentRepository.save(doc);
            }
            return doc;
        }

        // Create new tracking record
        IndexedDocument doc = IndexedDocument.create(sourceId, fileName, checksum, factId, factSheetId);
        return documentRepository.save(doc);
    }

    /**
     * Get or create a tracked document by source ID.
     */
    public IndexedDocument getOrCreateDocument(String sourceId, Long factSheetId) {
        return documentRepository.findBySourceIdAndFactSheetId(sourceId, factSheetId)
                .orElseGet(() -> registerDocument(sourceId, null, null, null, factSheetId));
    }

    /**
     * Find a document by ID.
     */
    @Transactional(readOnly = true)
    public Optional<IndexedDocument> findDocument(Long documentId) {
        return documentRepository.findById(documentId);
    }

    /**
     * Find a document by source ID.
     */
    @Transactional(readOnly = true)
    public Optional<IndexedDocument> findDocumentBySourceId(String sourceId, Long factSheetId) {
        return documentRepository.findBySourceIdAndFactSheetId(sourceId, factSheetId);
    }

    /**
     * Update document's keyword index status.
     */
    public void updateKeywordIndexStatus(Long documentId, IndexStatus status,
                                          String indexPath, int passageCount) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.updateKeywordStatus(status, indexPath, passageCount);
            documentRepository.save(doc);
            log.debug("Updated keyword index status for document {}: {}", documentId, status);
        });
    }

    /**
     * Update document's vector store status.
     */
    public void updateVectorStoreStatus(Long documentId, IndexStatus status,
                                         String vectorStorePath, int passageCount) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.updateVectorStatus(status, vectorStorePath, passageCount);
            documentRepository.save(doc);
            log.debug("Updated vector store status for document {}: {}", documentId, status);
        });
    }

    /**
     * Update document's knowledge graph status.
     */
    public void updateGraphStatus(Long documentId, IndexStatus status, int nodeCount) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.updateGraphStatus(status, nodeCount);
            documentRepository.save(doc);
            log.debug("Updated graph status for document {}: {}", documentId, status);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PASSAGE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Register a new passage for tracking.
     */
    public IndexedPassage registerPassage(IndexedDocument document, String chunkId,
                                           int chunkIndex, String content) {
        // Check if passage already exists
        Optional<IndexedPassage> existing = passageRepository.findByChunkId(chunkId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String contentHash = computeHash(content);
        String contentPreview = content != null && content.length() > 500 ?
                content.substring(0, 500) : content;

        IndexedPassage passage = IndexedPassage.create(document, chunkId, chunkIndex,
                contentHash, contentPreview);
        return passageRepository.save(passage);
    }

    /**
     * Bulk register passages from indexing pipeline output.
     */
    public List<IndexedPassage> registerPassages(IndexedDocument document,
                                                  List<RetrievedDoc> chunks) {
        List<IndexedPassage> registered = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedDoc chunk = chunks.get(i);
            String chunkId = chunk.getId();
            String content = chunk.getText();

            // Get chunk index from metadata if available
            Integer chunkIndex = null;
            if (chunk.getMetadata() != null) {
                Object idx = chunk.getMetadata().get("chunk_index");
                if (idx instanceof Number) {
                    chunkIndex = ((Number) idx).intValue();
                }
            }
            if (chunkIndex == null) {
                chunkIndex = i;
            }

            IndexedPassage passage = registerPassage(document, chunkId, chunkIndex, content);
            registered.add(passage);
        }

        // Update document passage count
        document.setKeywordPassageCount(registered.size());
        documentRepository.save(document);

        return registered;
    }

    /**
     * Find a passage by chunk ID.
     */
    @Transactional(readOnly = true)
    public Optional<IndexedPassage> findPassage(String chunkId) {
        return passageRepository.findByChunkId(chunkId);
    }

    /**
     * Mark a passage as indexed in the keyword index.
     */
    public void markPassageKeywordIndexed(String chunkId, Integer luceneDocId) {
        passageRepository.markKeywordIndexed(chunkId, luceneDocId, Instant.now());
    }

    /**
     * Mark a passage as indexed in the vector store.
     */
    public void markPassageVectorIndexed(String chunkId, String vectorId) {
        passageRepository.markVectorIndexed(chunkId, vectorId, Instant.now());
    }

    /**
     * Mark a passage as indexed in the knowledge graph.
     */
    public void markPassageGraphIndexed(String chunkId, String graphNodeId) {
        passageRepository.markGraphIndexed(chunkId, graphNodeId, Instant.now());
    }

    /**
     * Batch mark passages as vector indexed.
     */
    public void markPassagesVectorIndexed(Collection<String> chunkIds) {
        passageRepository.updateVectorStatusBatch(chunkIds, IndexStatus.INDEXED, Instant.now());
    }

    /**
     * Batch mark passages as graph indexed.
     */
    public void markPassagesGraphIndexed(Collection<String> chunkIds) {
        passageRepository.updateGraphStatusBatch(chunkIds, IndexStatus.INDEXED, Instant.now());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CROSS-INDEX RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find all documents needing synchronization to any index.
     */
    @Transactional(readOnly = true)
    public List<IndexedDocument> findDocumentsNeedingSync(Long factSheetId) {
        return documentRepository.findNeedingAnyIndexing(factSheetId);
    }

    /**
     * Find documents needing synchronization with pagination.
     */
    @Transactional(readOnly = true)
    public Page<IndexedDocument> findDocumentsNeedingSync(Long factSheetId, Pageable pageable) {
        return documentRepository.findNeedingAnyIndexing(factSheetId, pageable);
    }

    /**
     * Find passages that exist in keyword index but not vector store.
     */
    @Transactional(readOnly = true)
    public List<IndexedPassage> findPassagesMissingFromVector(Long factSheetId) {
        return passageRepository.findInKeywordNotInVector(factSheetId);
    }

    /**
     * Find passages that exist in vector store but not knowledge graph.
     */
    @Transactional(readOnly = true)
    public List<IndexedPassage> findPassagesMissingFromGraph(Long factSheetId) {
        return passageRepository.findInVectorNotInGraph(factSheetId);
    }

    /**
     * Find passages needing vector indexing with pagination.
     */
    @Transactional(readOnly = true)
    public Page<IndexedPassage> findPassagesNeedingVectorIndexing(Long factSheetId, Pageable pageable) {
        return passageRepository.findNeedingVectorIndexing(factSheetId, pageable);
    }

    /**
     * Check if a set of chunk IDs are all indexed in the vector store.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> checkVectorIndexStatus(Collection<String> chunkIds) {
        List<IndexedPassage> passages = passageRepository.findByChunkIds(chunkIds);
        Map<String, Boolean> result = new HashMap<>();

        // Initialize all as false
        for (String chunkId : chunkIds) {
            result.put(chunkId, false);
        }

        // Mark indexed ones as true
        for (IndexedPassage passage : passages) {
            result.put(passage.getChunkId(), passage.isInVectorStore());
        }

        return result;
    }

    /**
     * Resolve cross-index status for a query result set.
     * Used during search to detect missing entries for auto-sync.
     */
    @Transactional(readOnly = true)
    public CrossIndexResolutionResult resolveQueryResults(List<String> chunkIds, Long factSheetId) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return new CrossIndexResolutionResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    false
            );
        }

        Set<String> chunkIdSet = new HashSet<>(chunkIds);

        // Find passages that exist but aren't in vector store
        Set<String> missingFromVector = passageRepository.findChunkIdsNotInVectorStore(chunkIdSet);

        // Find passages that exist but aren't in graph
        Set<String> missingFromGraph = passageRepository.findChunkIdsNotInGraph(chunkIdSet);

        // All indexed = those not in the missing sets
        List<String> allIndexed = chunkIds.stream()
                .filter(id -> !missingFromVector.contains(id) && !missingFromGraph.contains(id))
                .collect(Collectors.toList());

        boolean needsSync = !missingFromVector.isEmpty() || !missingFromGraph.isEmpty();

        return new CrossIndexResolutionResult(
                allIndexed,
                new ArrayList<>(missingFromVector),
                new ArrayList<>(missingFromGraph),
                needsSync
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LISTING AND SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * List all documents for a fact sheet with pagination.
     */
    @Transactional(readOnly = true)
    public Page<IndexedDocument> listDocuments(Long factSheetId, Pageable pageable) {
        return documentRepository.findByFactSheetId(factSheetId, pageable);
    }

    /**
     * List documents by status with pagination.
     */
    @Transactional(readOnly = true)
    public Page<IndexedDocument> listDocumentsByStatus(Long factSheetId,
                                                        OverallIndexStatus status,
                                                        Pageable pageable) {
        return documentRepository.findByFactSheetIdAndOverallStatus(factSheetId, status, pageable);
    }

    /**
     * Search documents by filename.
     */
    @Transactional(readOnly = true)
    public Page<IndexedDocument> searchDocuments(Long factSheetId, String pattern, Pageable pageable) {
        return documentRepository.searchByFileName(factSheetId, pattern, pageable);
    }

    /**
     * Search documents by filename and status.
     */
    @Transactional(readOnly = true)
    public Page<IndexedDocument> searchDocumentsByStatus(Long factSheetId, String pattern,
                                                          OverallIndexStatus status, Pageable pageable) {
        return documentRepository.searchByFileNameAndStatus(factSheetId, pattern, status, pageable);
    }

    /**
     * List passages for a document with pagination.
     */
    @Transactional(readOnly = true)
    public Page<IndexedPassage> listPassages(Long documentId, Pageable pageable) {
        return passageRepository.findByDocumentId(documentId, pageable);
    }

    /**
     * List all passages for a document ordered by index.
     */
    @Transactional(readOnly = true)
    public List<IndexedPassage> listPassagesOrdered(Long documentId) {
        return passageRepository.findByDocumentIdOrderByChunkIndex(documentId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get comprehensive cross-index statistics for a fact sheet.
     */
    @Transactional(readOnly = true)
    public CrossIndexStatistics getStatistics(Long factSheetId) {
        long totalDocuments = documentRepository.countByFactSheetId(factSheetId);
        long fullyIndexedDocuments = documentRepository.countByFactSheetIdAndOverallStatus(
                factSheetId, OverallIndexStatus.FULLY_INDEXED);
        long partiallyIndexedDocuments = documentRepository.countByFactSheetIdAndOverallStatus(
                factSheetId, OverallIndexStatus.PARTIAL);
        long notIndexedDocuments = documentRepository.countByFactSheetIdAndOverallStatus(
                factSheetId, OverallIndexStatus.NOT_INDEXED);
        long failedDocuments = documentRepository.countByFactSheetIdAndOverallStatus(
                factSheetId, OverallIndexStatus.FAILED);

        long totalPassages = passageRepository.countByFactSheetId(factSheetId);
        long vectorIndexedPassages = passageRepository.countByFactSheetIdAndVectorStoreStatus(
                factSheetId, IndexStatus.INDEXED);
        long keywordIndexedPassages = passageRepository.countByFactSheetIdAndKeywordIndexStatus(
                factSheetId, IndexStatus.INDEXED);
        long graphIndexedPassages = passageRepository.countByFactSheetIdAndGraphStatus(
                factSheetId, IndexStatus.INDEXED);

        return new CrossIndexStatistics(
                totalDocuments,
                fullyIndexedDocuments,
                partiallyIndexedDocuments,
                notIndexedDocuments,
                failedDocuments,
                totalPassages,
                vectorIndexedPassages,
                keywordIndexedPassages,
                graphIndexedPassages
        );
    }

    /**
     * Get cross-index status summary for dashboard.
     */
    @Transactional(readOnly = true)
    public CrossIndexSummary getSummary(Long factSheetId, String factSheetName) {
        int documentsNeedingSync = documentRepository.findNeedingAnyIndexing(factSheetId).size();
        int passagesMissingFromVector = passageRepository.findInKeywordNotInVector(factSheetId).size();
        int passagesMissingFromGraph = passageRepository.findInVectorNotInGraph(factSheetId).size();

        return new CrossIndexSummary(
                factSheetId,
                factSheetName,
                documentsNeedingSync,
                passagesMissingFromVector,
                passagesMissingFromGraph,
                Instant.now(),
                true // TODO: Get from configuration
        );
    }

    /**
     * Get status distribution for a fact sheet.
     */
    @Transactional(readOnly = true)
    public Map<OverallIndexStatus, Long> getStatusDistribution(Long factSheetId) {
        List<Object[]> counts = documentRepository.getStatusCountsByFactSheet(factSheetId);
        Map<OverallIndexStatus, Long> distribution = new EnumMap<>(OverallIndexStatus.class);

        // Initialize all statuses to 0
        for (OverallIndexStatus status : OverallIndexStatus.values()) {
            distribution.put(status, 0L);
        }

        // Fill in actual counts
        for (Object[] row : counts) {
            OverallIndexStatus status = (OverallIndexStatus) row[0];
            Long count = (Long) row[1];
            distribution.put(status, count);
        }

        return distribution;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete all tracking data for a fact sheet.
     */
    public void deleteByFactSheet(Long factSheetId) {
        passageRepository.deleteByFactSheetId(factSheetId);
        documentRepository.deleteByFactSheetId(factSheetId);
        log.info("Deleted all cross-index tracking data for fact sheet {}", factSheetId);
    }

    /**
     * Delete tracking data for a specific document.
     */
    public void deleteDocument(Long documentId) {
        passageRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        log.info("Deleted cross-index tracking data for document {}", documentId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute SHA-256 hash of content.
     */
    private String computeHash(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available, skipping content hash");
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INNER CLASSES - RESULT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of cross-index resolution for a set of chunk IDs.
     */
    public record CrossIndexResolutionResult(
            List<String> allIndexedChunkIds,
            List<String> missingFromVectorChunkIds,
            List<String> missingFromGraphChunkIds,
            boolean needsSync
    ) {}

    /**
     * Cross-index statistics for a fact sheet.
     */
    public record CrossIndexStatistics(
            long totalDocuments,
            long fullyIndexedDocuments,
            long partiallyIndexedDocuments,
            long notIndexedDocuments,
            long failedDocuments,
            long totalPassages,
            long vectorIndexedPassages,
            long keywordIndexedPassages,
            long graphIndexedPassages
    ) {}

    /**
     * Cross-index summary for dashboard display.
     */
    public record CrossIndexSummary(
            Long factSheetId,
            String factSheetName,
            int documentsNeedingSync,
            int passagesMissingFromVector,
            int passagesMissingFromGraph,
            Instant lastSyncCheck,
            boolean autoSyncEnabled
    ) {}
}
