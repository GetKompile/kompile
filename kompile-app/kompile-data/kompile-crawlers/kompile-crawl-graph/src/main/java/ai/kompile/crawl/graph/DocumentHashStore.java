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
import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ai.kompile.utils.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 *   <li>Production crawls stage mutations in memory and publish them only after a
 *       {@link GraphBuildCompletedEvent}. A failed/crashed crawl therefore cannot make
 *       a partially-built graph look current on the next run.</li>
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
    private final ConcurrentMap<String, PendingRun> pendingByRun = new ConcurrentHashMap<>();

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Compute the SHA-256 hex digest of the given bytes.
     * Returns {@code null} if {@code bytes} is null or the algorithm is unavailable.
     */
    static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return HashUtils.sha256Hex(bytes);
    }

    /**
     * Compute the SHA-256 hex digest of the given string (UTF-8 encoded).
     * Returns {@code null} if {@code content} is null.
     */
    static String sha256Hex(String content) {
        if (content == null) {
            return null;
        }
        return HashUtils.sha256Hex(content);
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
        recordHash(factSheetId, sourceDocumentId, contentHash, crawlRunId, null);
    }

    /**
     * Persist a hash immediately. Kept for direct callers and tests; production crawl
     * code should use {@link #stageHash} so publication follows graph persistence.
     */
    synchronized void recordHash(Long factSheetId,
                                 String sourceDocumentId,
                                 String contentHash,
                                 String crawlRunId,
                                 String sourceScopeId) {
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
                    .sourceScopeId(sourceScopeId)
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

    /**
     * Stage a successful source load for atomic publication after the graph build.
     * Upsert wins over a deletion for the same source in one run.
     */
    void stageHash(Long factSheetId,
                   String sourceDocumentId,
                   String contentHash,
                   String crawlRunId,
                   String sourceScopeId) {
        if (crawlRunId == null || sourceDocumentId == null || contentHash == null) {
            return;
        }
        PendingRun pending = pendingByRun.computeIfAbsent(crawlRunId, ignored -> new PendingRun());
        PendingKey key = new PendingKey(new FactSheetScope(factSheetId), sourceDocumentId);
        pending.deletions.remove(key);
        pending.upserts.put(key, HashEntry.builder()
                .contentHash(contentHash)
                .lastCrawlRunId(crawlRunId)
                .lastProcessedAt(Instant.now().toString())
                .sourceScopeId(sourceScopeId)
                .build());
    }

    /** Stage a source tombstone; it becomes durable only after graph-build completion. */
    void stageDeletion(Long factSheetId, String sourceDocumentId, String crawlRunId) {
        if (crawlRunId == null || sourceDocumentId == null) {
            return;
        }
        PendingRun pending = pendingByRun.computeIfAbsent(crawlRunId, ignored -> new PendingRun());
        PendingKey key = new PendingKey(new FactSheetScope(factSheetId), sourceDocumentId);
        if (!pending.upserts.containsKey(key)) {
            pending.deletions.add(key);
        }
    }

    /**
     * Return manifest sources owned by {@code sourceScopeId} that were not present in
     * the latest successful discovery. Callers decide whether absence is a verified
     * deletion (local files) or merely an unavailable/excluded remote source.
     */
    List<String> findMissingSources(Long factSheetId,
                                    String sourceScopeId,
                                    Set<String> liveSourceDocumentIds) {
        if (sourceScopeId == null) {
            return List.of();
        }
        Set<String> live = liveSourceDocumentIds != null
                ? new HashSet<>(liveSourceDocumentIds)
                : Set.of();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, HashEntry> entry : loadStore(factSheetId).entrySet()) {
            HashEntry value = entry.getValue();
            if (value != null
                    && sourceScopeId.equals(value.getSourceScopeId())
                    && !live.contains(entry.getKey())) {
                missing.add(entry.getKey());
            }
        }
        missing.sort(String::compareTo);
        return missing;
    }

    /** Publish all staged mutations for one crawl run using per-fact-sheet atomic writes. */
    synchronized CommitSummary commitStaged(String crawlRunId) {
        PendingRun pending = crawlRunId == null ? null : pendingByRun.get(crawlRunId);
        if (pending == null) {
            return new CommitSummary(0, 0);
        }

        Map<FactSheetScope, Map<String, HashEntry>> upsertsByScope = new LinkedHashMap<>();
        pending.upserts.forEach((key, value) -> upsertsByScope
                .computeIfAbsent(key.scope(), ignored -> new LinkedHashMap<>())
                .put(key.sourceDocumentId(), value));
        Map<FactSheetScope, Set<String>> deletionsByScope = new LinkedHashMap<>();
        pending.deletions.forEach(key -> deletionsByScope
                .computeIfAbsent(key.scope(), ignored -> new HashSet<>())
                .add(key.sourceDocumentId()));

        Set<FactSheetScope> scopes = new HashSet<>(upsertsByScope.keySet());
        scopes.addAll(deletionsByScope.keySet());
        int upserted = 0;
        int deleted = 0;
        try {
            for (FactSheetScope scope : scopes) {
                Map<String, HashEntry> store = loadStore(scope.factSheetId());
                for (String source : deletionsByScope.getOrDefault(scope, Set.of())) {
                    if (store.remove(source) != null) {
                        deleted++;
                    }
                }
                Map<String, HashEntry> upserts = upsertsByScope.getOrDefault(scope, Map.of());
                store.putAll(upserts);
                upserted += upserts.size();
                Path file = hashFilePath(scope.factSheetId());
                Files.createDirectories(file.getParent());
                writeStoreAtomic(file, store);
            }
            pendingByRun.remove(crawlRunId, pending);
            return new CommitSummary(upserted, deleted);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to publish incremental crawl manifest for run "
                    + crawlRunId, e);
        }
    }

    /** Forget uncommitted mutations for a failed or cancelled crawl. */
    void discardStaged(String crawlRunId) {
        if (crawlRunId != null) {
            pendingByRun.remove(crawlRunId);
        }
    }

    /** Graph completion is the commit barrier for the crawl manifest. */
    @EventListener
    void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        if (event == null || event.getJobId() == null) {
            return;
        }
        try {
            CommitSummary summary = commitStaged(event.getJobId());
            if (summary.upserted() > 0 || summary.deleted() > 0) {
                log.info("Published incremental crawl manifest for run {}: {} upserted, {} deleted",
                        event.getJobId(), summary.upserted(), summary.deleted());
            }
        } catch (Exception e) {
            // Leave the staged run in memory. More importantly, leave the durable manifest
            // unchanged so the next crawl re-processes rather than trusting partial state.
            log.warn("Graph completed but incremental crawl manifest publication failed for run {}: {}",
                    event.getJobId(), e.getMessage());
        }
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

        /** Stable crawl-root identity used to reconcile deletions within one source scope. */
        private String sourceScopeId;
    }

    record CommitSummary(int upserted, int deleted) {}

    private record FactSheetScope(Long factSheetId) {}

    private record PendingKey(FactSheetScope scope, String sourceDocumentId) {}

    private static final class PendingRun {
        private final ConcurrentMap<PendingKey, HashEntry> upserts = new ConcurrentHashMap<>();
        private final Set<PendingKey> deletions = ConcurrentHashMap.newKeySet();
    }
}
