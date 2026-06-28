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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent per-file SHA-256 content-hash store for incremental crawling.
 *
 * <p>For each (factSheetId, sourceDocumentId/path) pair, stores the SHA-256
 * hex digest of the file's content, the crawl run ID that last processed it,
 * and the processing timestamp.  The store is persisted as JSON under the
 * project data directory:
 * {@code <dataDir>/data/graph/<factSheetId>/document-hashes.json}
 * so it travels with the project on {@code git clone}.</p>
 *
 * <p>Writes are atomic (temp-file then {@link Files#move} with ATOMIC_MOVE /
 * REPLACE_EXISTING) to avoid corruption on crash mid-write.</p>
 *
 * <p>This class is intentionally free of Spring Data / JPA to remain
 * compatible with both the JPA and the live matrix/vector store paths.</p>
 *
 * <h2>Correctness contract</h2>
 * <ul>
 *   <li>A file is considered UNCHANGED if a stored entry exists AND its
 *       {@code contentHash} equals the freshly computed SHA-256.</li>
 *   <li>An UNCHANGED file's existing graph nodes (by provenance
 *       {@code _sourceDocumentId}) are kept as-is; all per-file pipeline steps
 *       (CONVERTING, CHUNKING, GRAPH_EXTRACTION, VECTOR_INDEXING) are skipped.</li>
 *   <li>A CHANGED file (hash mismatch) or NEW file (no entry) is (re-)processed;
 *       if CHANGED, the caller must first purge the prior nodes via the
 *       provenance-purge path before ingesting the new extraction.</li>
 *   <li>Global post-processing steps (ENTITY_RESOLUTION, EDGE_COMPUTATION,
 *       ENRICHMENT) always run over the full current graph — skipped files'
 *       kept nodes + newly extracted nodes — so graph-wide invariants hold.</li>
 *   <li>After successful processing, {@link #recordHash} persists the new entry.</li>
 * </ul>
 */
@Slf4j
@Component
class DocumentHashStore {

    /** Sub-directory under the project data-dir where per-factSheet hash files live. */
    private static final String DATA_GRAPH_SUBDIR = "data/graph";

    /** File name for the per-fact-sheet hash store. */
    private static final String HASH_FILE_NAME = "document-hashes.json";

    private static final TypeReference<Map<String, HashEntry>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Compute the SHA-256 hex digest of the given bytes.
     * Returns {@code null} if {@code bytes} is null or the algorithm is unavailable.
     */
    static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available — incremental hash check will be skipped: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Compute the SHA-256 hex digest of the given string (UTF-8 encoded).
     * Returns {@code null} if {@code content} is null or the algorithm is unavailable.
     */
    static String sha256Hex(String content) {
        if (content == null) {
            return null;
        }
        return sha256Hex(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Look up the stored hash entry for a given source document.
     *
     * @param factSheetId      the fact-sheet scope (may be null → global scope)
     * @param sourceDocumentId the canonical source path or URL
     * @return the stored {@link HashEntry}, or {@code null} if not present
     */
    HashEntry lookup(Long factSheetId, String sourceDocumentId) {
        if (sourceDocumentId == null) {
            return null;
        }
        try {
            Map<String, HashEntry> store = loadStore(factSheetId);
            return store.get(sourceDocumentId);
        } catch (Exception e) {
            log.debug("Hash-store lookup failed for factSheet={} doc={}: {}",
                    factSheetId, sourceDocumentId, e.getMessage());
            return null;
        }
    }

    /**
     * Persist a hash entry for a successfully processed source document.
     * Writes are atomic via temp-file rename.
     *
     * @param factSheetId      the fact-sheet scope (may be null → global scope)
     * @param sourceDocumentId the canonical source path or URL
     * @param contentHash      SHA-256 hex of the file bytes
     * @param crawlRunId       the job ID that produced this result
     */
    void recordHash(Long factSheetId, String sourceDocumentId, String contentHash, String crawlRunId) {
        if (sourceDocumentId == null || contentHash == null) {
            return;
        }
        try {
            Path file = hashFilePath(factSheetId);
            Files.createDirectories(file.getParent());
            Map<String, HashEntry> store = loadStore(factSheetId);
            store.put(sourceDocumentId, HashEntry.builder()
                    .contentHash(contentHash)
                    .lastCrawlRunId(crawlRunId)
                    .lastProcessedAt(Instant.now().toString())
                    .build());
            writeStoreAtomic(file, store);
        } catch (Exception e) {
            log.warn("Failed to persist hash entry for factSheet={} doc={}: {}",
                    factSheetId, sourceDocumentId, e.getMessage());
        }
    }

    /**
     * Determine whether a source document is UNCHANGED relative to its stored hash.
     *
     * @param factSheetId      the fact-sheet scope
     * @param sourceDocumentId the canonical source path or URL
     * @param freshHash        freshly computed SHA-256 hex (from {@link #sha256Hex})
     * @return {@code true} if a stored entry exists AND its hash matches {@code freshHash};
     *         {@code false} if the file is new (no entry) or has changed (hash mismatch)
     */
    boolean isUnchanged(Long factSheetId, String sourceDocumentId, String freshHash) {
        if (freshHash == null || sourceDocumentId == null) {
            return false; // can't confirm unchanged without a hash
        }
        HashEntry stored = lookup(factSheetId, sourceDocumentId);
        return stored != null && freshHash.equals(stored.getContentHash());
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Returns the path of the hash-store JSON file for the given fact-sheet.
     *
     * <p>Resolves under the effective kompile <em>project</em> data directory so
     * that the hash file is co-located with the graph JSON it describes and is
     * wiped atomically when {@code data/graph/} is cleaned.  Resolution order:
     * <ol>
     *   <li>{@code kompile.data.dir} JVM system property (set via
     *       {@code -Dkompile.data.dir=&lt;projectDir&gt;}).</li>
     *   <li>Walk-up from the JVM's current working directory looking for
     *       {@code kompile.project.json} — this covers the common launch script
     *       pattern where {@code --kompile.data.dir} is a Spring CLI arg (not a
     *       JVM property) and the script {@code cd}s into the project root.</li>
     *   <li>Falls back to {@code ~/.kompile} when no project context is found.</li>
     * </ol>
     */
    private Path hashFilePath(Long factSheetId) {
        String scope = (factSheetId != null) ? String.valueOf(factSheetId) : "global";
        return KompileHome.resolvedProjectDirectory().toPath()
                .resolve(DATA_GRAPH_SUBDIR)
                .resolve(scope)
                .resolve(HASH_FILE_NAME);
    }

    private Map<String, HashEntry> loadStore(Long factSheetId) {
        Path file = hashFilePath(factSheetId);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(file.toFile(), MAP_TYPE);
        } catch (Exception e) {
            log.debug("Could not read hash store at {}: {} — treating as empty", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeStoreAtomic(Path target, Map<String, HashEntry> store) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent != null
                ? Files.createTempFile(parent, ".dhs-", ".tmp")
                : Files.createTempFile(".dhs-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), store);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    // ── Value types ─────────────────────────────────────────────────────────

    /**
     * One entry in the per-fact-sheet hash store.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HashEntry {
        /** SHA-256 hex digest of the file bytes at the time it was last processed. */
        private String contentHash;

        /** Job / crawl-run ID that produced this entry. */
        private String lastCrawlRunId;

        /** ISO-8601 timestamp of when this entry was recorded. */
        private String lastProcessedAt;
    }
}
