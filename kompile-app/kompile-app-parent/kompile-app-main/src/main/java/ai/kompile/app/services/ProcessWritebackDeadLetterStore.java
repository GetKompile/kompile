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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only JSONL dead-letter store for process writeback failures.
 *
 * <p>Each entry is one JSON line in {@code <dataDir>/data/process/writeback-dead-letter.jsonl}.
 * Atomic temp+move rewrites are used when the file is compacted (survivors written back after
 * a replay pass). Plain append is used for new failures.</p>
 *
 * <p>Thread-safety: all public methods are {@code synchronized} on this instance.
 * The in-memory list shadows the file to avoid re-reading on every GET request.</p>
 *
 * <p>If {@code dataDir} is {@code null} the store is disabled: {@link #isEnabled()} returns
 * {@code false}, appends are silently dropped, reads return an empty list. In-memory retry
 * still works; only durability is off.</p>
 */
public class ProcessWritebackDeadLetterStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessWritebackDeadLetterStore.class);
    static final String DEAD_LETTER_SUBPATH = "data/process/writeback-dead-letter.jsonl";

    private final Path journalPath;   // null when disabled
    private final ObjectMapper mapper;

    /** In-memory mirror of what is on disk; kept in sync to avoid file reads on GETs. */
    private final List<DeadLetterEntry> entries = new ArrayList<>();
    private boolean loaded = false;

    ProcessWritebackDeadLetterStore(Path dataDir, ObjectMapper mapper) {
        this.mapper = mapper;
        if (dataDir == null) {
            this.journalPath = null;
        } else {
            this.journalPath = dataDir.resolve(DEAD_LETTER_SUBPATH);
        }
    }

    /** Returns {@code true} when a data directory is configured (durability is active). */
    public boolean isEnabled() {
        return journalPath != null;
    }

    /**
     * Append one failed payload to the dead-letter file and in-memory list.
     * If disabled, logs a WARN and returns without writing.
     */
    public synchronized void append(DeadLetterEntry entry) {
        if (!isEnabled()) {
            log.warn("ProcessWriteback dead-letter disabled (no dataDir): permanently losing payload type={} runId={} stepId={}",
                    entry.callbackType, entry.runId, entry.stepId);
            return;
        }
        ensureLoaded();
        try {
            Files.createDirectories(journalPath.getParent());
            String line = mapper.writeValueAsString(entry) + "\n";
            Files.writeString(journalPath, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            entries.add(entry);
            log.warn("ProcessWriteback dead-letter: wrote entry id={} type={} runId={} stepId={} failure={}",
                    entry.id, entry.callbackType, entry.runId, entry.stepId, entry.failureSummary);
        } catch (IOException ex) {
            log.error("ProcessWriteback: could not write dead-letter entry — payload is lost: {}", ex.getMessage());
        }
    }

    /**
     * Returns an immutable snapshot of all current entries.
     * Loads from disk on first call (lazy, idempotent).
     */
    public synchronized List<DeadLetterEntry> readAll() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Remove all entries whose {@code id} is in {@code succeededIds} and rewrite the file
     * atomically. Returns the number of entries removed.
     */
    public synchronized int removeSucceeded(List<String> succeededIds) {
        if (succeededIds.isEmpty()) return 0;
        ensureLoaded();
        int before = entries.size();
        entries.removeIf(e -> succeededIds.contains(e.id));
        int removed = before - entries.size();
        if (removed > 0 && isEnabled()) {
            rewriteAtomic();
        }
        return removed;
    }

    /**
     * Remove the single entry with the given {@code id}.
     * Returns {@code true} if found and removed.
     */
    public synchronized boolean removeById(String id) {
        ensureLoaded();
        boolean removed = entries.removeIf(e -> id.equals(e.id));
        if (removed && isEnabled()) {
            rewriteAtomic();
        }
        return removed;
    }

    /** Returns the current entry count without triggering a file load. */
    public synchronized int size() {
        ensureLoaded();
        return entries.size();
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!isEnabled()) return;
        if (!Files.exists(journalPath)) return;
        try {
            List<String> lines = Files.readAllLines(journalPath);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    DeadLetterEntry e = mapper.readValue(trimmed, DeadLetterEntry.class);
                    entries.add(e);
                } catch (Exception ex) {
                    log.warn("ProcessWriteback dead-letter: skipping unparseable line: {}", ex.getMessage());
                }
            }
            log.info("ProcessWriteback dead-letter: loaded {} entries from {}", entries.size(), journalPath);
        } catch (IOException ex) {
            log.warn("ProcessWriteback dead-letter: could not read journal — starting empty: {}", ex.getMessage());
        }
    }

    private void rewriteAtomic() {
        if (journalPath == null) return;
        Path tmp = journalPath.resolveSibling(journalPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(journalPath.getParent());
            StringBuilder sb = new StringBuilder();
            for (DeadLetterEntry e : entries) {
                sb.append(mapper.writeValueAsString(e)).append("\n");
            }
            Files.writeString(tmp, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, journalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, journalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("ProcessWriteback dead-letter: atomic rewrite failed: {}", ex.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    // ── Dead-letter entry ────────────────────────────────────────────────────────

    /**
     * One serializable dead-letter record capturing everything needed to replay a single
     * writeback batch (one step callback or one run callback).
     *
     * <p>JSON line format example (STEP_COMPLETED):
     * <pre>
     * {"id":"a1b2c3d4...","callbackType":"STEP_COMPLETED","runId":"run-42","stepId":"step-A",
     *  "processDefinitionId":"invoice-proc","ts":"2026-07-09T10:00:00Z",
     *  "stepName":"Analyze Invoice","stepStatus":"COMPLETED","graphNodeIds":["node-1","node-2"],
     *  "executedBy":"tool:ragQuery","outputKeys":"result,count","outputHash":"abc123",
     *  "error":null,"failureSummary":"KG service unavailable"}
     * </pre>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeadLetterEntry {

        /** Unique ID for this dead-letter entry (used to address DELETE). */
        public String id;

        /** Either {@code "STEP_COMPLETED"} or {@code "RUN_COMPLETED"}. */
        public String callbackType;

        /** WorkflowRun.getId() */
        public String runId;

        /** StepExecution.getStepId() — null for RUN_COMPLETED entries. */
        public String stepId;

        public String processDefinitionId;

        /** ISO-8601 timestamp when this entry was written. */
        public String ts;

        // ── Step-level fields (null for RUN_COMPLETED) ────────────────────
        public String stepName;
        public String stepStatus;
        public List<String> graphNodeIds;
        public String executedBy;
        public String outputKeys;
        public String outputHash;
        public String inputHash;
        public String error;

        // ── Run-level fields (null for STEP_COMPLETED) ────────────────────
        public String runStatus;
        public String startedAt;
        public String completedAt;

        /** Human-readable summary of the terminal failure that caused dead-lettering. */
        public String failureSummary;

        /** Attempt number when this was dead-lettered (1-based). */
        public int attemptNumber;

        /** Serialized step outputs summary (for STEP_COMPLETED entries with outputs). */
        public String outputSummary;

        /** Optional bag for any extra replay context. */
        public Map<String, Object> extra;

        /** Jackson no-arg constructor. */
        public DeadLetterEntry() {}

        public static DeadLetterEntry forStep(String runId, String stepId,
                                              String processDefinitionId,
                                              String stepName, String stepStatus,
                                              List<String> graphNodeIds,
                                              String executedBy,
                                              String outputKeys, String outputHash,
                                              String inputHash, String error,
                                              String outputSummary,
                                              String failureSummary, int attemptNumber) {
            DeadLetterEntry e = new DeadLetterEntry();
            e.id = UUID.randomUUID().toString();
            e.callbackType = "STEP_COMPLETED";
            e.runId = runId;
            e.stepId = stepId;
            e.processDefinitionId = processDefinitionId;
            e.ts = Instant.now().toString();
            e.stepName = stepName;
            e.stepStatus = stepStatus;
            e.graphNodeIds = graphNodeIds == null ? null : List.copyOf(graphNodeIds);
            e.executedBy = executedBy;
            e.outputKeys = outputKeys;
            e.outputHash = outputHash;
            e.inputHash = inputHash;
            e.error = error;
            e.outputSummary = outputSummary;
            e.failureSummary = failureSummary;
            e.attemptNumber = attemptNumber;
            return e;
        }

        public static DeadLetterEntry forRun(String runId, String processDefinitionId,
                                             String runStatus, String startedAt, String completedAt,
                                             List<String> graphNodeIds,
                                             String failureSummary, int attemptNumber) {
            DeadLetterEntry e = new DeadLetterEntry();
            e.id = UUID.randomUUID().toString();
            e.callbackType = "RUN_COMPLETED";
            e.runId = runId;
            e.processDefinitionId = processDefinitionId;
            e.ts = Instant.now().toString();
            e.runStatus = runStatus;
            e.startedAt = startedAt;
            e.completedAt = completedAt;
            e.graphNodeIds = graphNodeIds == null ? null : List.copyOf(graphNodeIds);
            e.failureSummary = failureSummary;
            e.attemptNumber = attemptNumber;
            return e;
        }
    }
}
