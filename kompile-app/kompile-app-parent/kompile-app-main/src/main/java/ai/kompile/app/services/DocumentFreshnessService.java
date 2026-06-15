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

import ai.kompile.app.config.DocumentFreshnessProperties;
import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.repository.IndexedDocumentRepository;
import ai.kompile.core.freshness.DocumentFreshnessScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Service for managing document freshness and staleness detection.
 * Scans indexed documents for changes, applies TTL-based staleness,
 * and computes freshness scores for retrieval weighting.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kompile.freshness.enabled", havingValue = "true")
public class DocumentFreshnessService {

    private static final int BUFFER_SIZE = 8192; // SHA-256 read buffer

    private final IndexedDocumentRepository documentRepository;
    private final DocumentFreshnessProperties properties;

    /**
     * Scan all INDEXED documents for a fact sheet and mark any with changed checksums as STALE.
     *
     * @return number of documents marked stale
     */
    @Transactional
    public int scanForStaleDocuments(Long factSheetId) {
        log.info("Scanning for stale documents in fact sheet {}", factSheetId);
        List<IndexedDocument> documents = documentRepository.findByFactSheetId(factSheetId);
        int staleCount = 0;

        for (IndexedDocument doc : documents) {
            if (!doc.isFullyIndexed()) {
                continue;
            }

            try {
                String currentChecksum = computeChecksum(doc.getSourceId());
                if (currentChecksum != null && doc.getChecksum() != null
                        && !currentChecksum.equals(doc.getChecksum())) {
                    doc.markAsStale();
                    documentRepository.save(doc);
                    staleCount++;
                    log.debug("Document {} marked stale: checksum changed", doc.getSourceId());
                }
            } catch (Exception e) {
                log.debug("Could not verify checksum for {}: {}", doc.getSourceId(), e.getMessage());
            }
        }

        log.info("Staleness scan complete for fact sheet {}: {} documents marked stale", factSheetId, staleCount);
        return staleCount;
    }

    /**
     * Apply TTL-based staleness to documents in a fact sheet.
     * Documents older than the TTL for their extension are marked stale.
     *
     * @return number of documents marked stale
     */
    @Transactional
    public int markStaleByTtl(Long factSheetId) {
        log.info("Applying TTL-based staleness for fact sheet {}", factSheetId);
        List<IndexedDocument> documents = documentRepository.findByFactSheetId(factSheetId);
        int staleCount = 0;
        Instant now = Instant.now();

        for (IndexedDocument doc : documents) {
            if (!doc.isFullyIndexed()) {
                continue;
            }

            String extension = getExtension(doc.getFileName());
            int ttlDays = properties.getTtlByExtension()
                    .getOrDefault(extension, properties.getDefaultTtlDays());

            Instant indexedAt = doc.getKeywordIndexedAt();
            if (indexedAt == null) {
                indexedAt = doc.getCreatedAt();
            }

            if (indexedAt != null && ChronoUnit.DAYS.between(indexedAt, now) > ttlDays) {
                doc.markAsStale();
                documentRepository.save(doc);
                staleCount++;
                log.debug("Document {} marked stale: TTL exceeded ({} days for .{})",
                        doc.getFileName(), ttlDays, extension);
            }
        }

        log.info("TTL staleness check complete for fact sheet {}: {} documents marked stale",
                factSheetId, staleCount);
        return staleCount;
    }

    /**
     * Manually mark a single document as stale.
     */
    @Transactional
    public void markDocumentStale(Long documentId) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.markAsStale();
            documentRepository.save(doc);
            log.info("Document {} manually marked as stale", doc.getSourceId());
        });
    }

    /**
     * Calculate a freshness score for a document using exponential decay.
     * Score = exp(-0.693 * ageDays / halfLifeDays)
     * where 0.693 = ln(2)
     *
     * @return score between 0.0 (very stale) and 1.0 (freshly indexed)
     */
    public double calculateFreshnessScore(IndexedDocument document) {
        Instant indexedAt = document.getKeywordIndexedAt();
        if (indexedAt == null) {
            indexedAt = document.getCreatedAt();
        }
        if (indexedAt == null) {
            return 0.5; // Unknown age, neutral score
        }

        double ageDays = ChronoUnit.HOURS.between(indexedAt, Instant.now()) / 24.0;
        double halfLifeDays = properties.getFreshnessHalfLifeDays();
        return Math.exp(-0.693 * ageDays / halfLifeDays);
    }

    /**
     * Get all stale document IDs for a fact sheet.
     */
    public List<Long> getStaleDocumentIds(Long factSheetId) {
        List<IndexedDocument> staleDocs = documentRepository.findStaleDocuments(factSheetId);
        List<Long> ids = new ArrayList<>();
        for (IndexedDocument doc : staleDocs) {
            ids.add(doc.getId());
        }
        return ids;
    }

    private String computeChecksum(String sourceId) {
        if (sourceId == null) return null;

        try {
            Path filePath;
            if (sourceId.startsWith("file://")) {
                filePath = Paths.get(URI.create(sourceId));
            } else if (sourceId.startsWith("/")) {
                filePath = Paths.get(sourceId);
            } else {
                return null; // Non-file sources can't be checksummed locally
            }

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.debug("Failed to compute checksum for {}: {}", sourceId, e.getMessage());
            return null;
        }
    }

    /**
     * Create a DocumentFreshnessScorer bean that can be used by the vector store.
     * The scorer looks up documents by ID and calculates exponential decay freshness.
     */
    @Bean
    public DocumentFreshnessScorer documentFreshnessScorer() {
        return new DocumentFreshnessScorer() {
            @Override
            public double score(String documentId) {
                if (documentId == null || documentId.isBlank()) {
                    return 0.5;
                }
                try {
                    // Try to parse as numeric ID
                    Long id = Long.parseLong(documentId);
                    return documentRepository.findById(id)
                            .map(doc -> calculateFreshnessScore(doc))
                            .orElse(0.5);
                } catch (NumberFormatException e) {
                    // Not a numeric ID — neutral score
                    return 0.5;
                }
            }

            @Override
            public double getWeight() {
                return properties.getFreshnessWeight();
            }
        };
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= fileName.length() - 1) return "";
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}
