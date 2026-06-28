/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Durable training-position checkpoint for the DERIVATION stage.
 *
 * <h3>Why this exists</h3>
 * <p>PSL minibatch weight learning and MEBN online learning are naturally
 * checkpointable at the cascade level: each cascade is one warm-started
 * online step.  When a DERIVATION is capped by the time budget, killed by
 * the JVM, or cancelled, the learner should restart from the last persisted
 * cascade position rather than from the prior weights at epoch 0.</p>
 *
 * <h3>What is persisted</h3>
 * <ul>
 *   <li>{@link TrainingCheckpoint#cascadesCompleted()} — the number of
 *       online gradient steps that successfully wrote their weights via
 *       the {@link FileBackedWeightStore}.  The PSL weight file (latest
 *       version in the store) is the weight-state checkpoint; this record
 *       tells the orchestrator that those weights were reached by
 *       {@code cascadesCompleted} warm-start steps, not a fresh cold-start.</li>
 *   <li>{@link TrainingCheckpoint#pslProgramKey()} — the programKey used
 *       in the weight store (e.g. {@code "42:cascade"}), so the orchestrator
 *       can reload from the correct key on resume.</li>
 *   <li>{@link TrainingCheckpoint#mebnBackupId()} — the timestamped backup
 *       ID created by {@link WeightSessionService#backupMebn} after the last
 *       MEBN step, enabling selective restore if the live MEBN file is clobbered
 *       between a timeout and a resume.</li>
 *   <li>{@link TrainingCheckpoint#checkpointedAt()} — wall-clock timestamp for
 *       diagnostics / staleness detection.</li>
 * </ul>
 *
 * <h3>File layout</h3>
 * <pre>
 *   &lt;dataDir&gt;/data/graph/reasoning/&lt;factSheetId&gt;/training-checkpoint.json
 * </pre>
 * Written atomically (temp → rename).  A missing file = no checkpoint = cold-start.
 *
 * <h3>Coordination with WeightSessionService</h3>
 * <p>{@link WeightSessionService#startNewSession} calls {@link #clear(long)} so a
 * deliberate "new session" reset always clears the checkpoint and forces a cold-start
 * on the next DERIVATION run.</p>
 *
 * <h3>Thread-safety</h3>
 * <p>Not synchronized — callers must ensure single-writer access per factSheetId
 * (the DERIVATION stage already runs in a single daemon thread per factSheet).</p>
 */
@Slf4j
@Component
public class TrainingCheckpointStore {

    private static final String CHECKPOINT_FILENAME = "training-checkpoint.json";

    @Value("${kompile.data.dir:}")
    private String dataDir;

    // ── Public record ─────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of training progress for one fact sheet.
     *
     * @param factSheetId       the fact sheet this checkpoint belongs to
     * @param cascadesCompleted number of online gradient steps (cascades) that have
     *                          successfully completed and been persisted to the weight store
     * @param pslProgramKey     the PSL program key in the weight store (e.g. {@code "42:cascade"})
     * @param pslWeightVersion  the latest version number in the FileWeightStore at the time of
     *                          checkpointing; the orchestrator can assert this on reload
     * @param mebnBackupId      the timestamped MEBN backup ID created after the last MEBN step
     *                          ({@code null} if MEBN has not run yet)
     * @param checkpointedAt    ISO-8601 wall-clock timestamp
     */
    public record TrainingCheckpoint(
            long factSheetId,
            long cascadesCompleted,
            String pslProgramKey,
            int pslWeightVersion,
            String mebnBackupId,
            String checkpointedAt
    ) {}

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist a training-progress checkpoint for {@code factSheetId}.
     *
     * @param checkpoint the checkpoint to write
     */
    public void save(TrainingCheckpoint checkpoint) {
        Path target = checkpointPath(checkpoint.factSheetId());
        try {
            Files.createDirectories(target.getParent());
            String json = toJson(checkpoint);
            // Atomic write: write to temp, rename
            Path tmp = target.resolveSibling(CHECKPOINT_FILENAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("TrainingCheckpointStore: saved checkpoint for factSheet={}: cascades={}, pslVer={}, mebnBackup={}",
                    checkpoint.factSheetId(), checkpoint.cascadesCompleted(),
                    checkpoint.pslWeightVersion(), checkpoint.mebnBackupId());
        } catch (IOException e) {
            // Non-fatal: checkpoint is a best-effort durability aid, not required for correctness.
            log.warn("TrainingCheckpointStore: could not write checkpoint for factSheet={}: {}",
                    checkpoint.factSheetId(), e.getMessage());
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Load the training checkpoint for {@code factSheetId}.
     *
     * @param factSheetId the fact sheet to load for
     * @return the checkpoint, or {@link Optional#empty()} if none exists or the file is corrupt
     */
    public Optional<TrainingCheckpoint> load(long factSheetId) {
        Path target = checkpointPath(factSheetId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            TrainingCheckpoint cp = fromJson(json);
            log.debug("TrainingCheckpointStore: loaded checkpoint for factSheet={}: cascades={}, pslVer={}",
                    factSheetId, cp.cascadesCompleted(), cp.pslWeightVersion());
            return Optional.of(cp);
        } catch (Exception e) {
            log.warn("TrainingCheckpointStore: could not read/parse checkpoint for factSheet={} (will cold-start): {}",
                    factSheetId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    /**
     * Delete the training checkpoint for {@code factSheetId}.
     * Called by {@link WeightSessionService#startNewSession} so a deliberate session
     * reset clears the position and forces a cold-start on the next DERIVATION.
     */
    public void clear(long factSheetId) {
        Path target = checkpointPath(factSheetId);
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("TrainingCheckpointStore: cleared training checkpoint for factSheet={}", factSheetId);
            }
        } catch (IOException e) {
            log.warn("TrainingCheckpointStore: could not clear checkpoint for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    /**
     * Build a checkpoint from the values available after a successful cascade step.
     */
    public static TrainingCheckpoint of(long factSheetId, long cascadesCompleted,
                                        String pslProgramKey, int pslWeightVersion,
                                        String mebnBackupId) {
        return new TrainingCheckpoint(
                factSheetId, cascadesCompleted, pslProgramKey, pslWeightVersion,
                mebnBackupId, Instant.now().toString());
    }

    // ── Path ──────────────────────────────────────────────────────────────────

    Path checkpointPath(long factSheetId) {
        Path base = (dataDir == null || dataDir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : Path.of(dataDir);
        return base.resolve("data").resolve("graph").resolve("reasoning")
                .resolve(String.valueOf(factSheetId))
                .resolve(CHECKPOINT_FILENAME);
    }

    // ── Minimal JSON serialiser (no Jackson dep needed) ───────────────────────

    private static String toJson(TrainingCheckpoint cp) {
        return String.format(Locale.ROOT,
                "{\"factSheetId\":%d,\"cascadesCompleted\":%d,\"pslProgramKey\":\"%s\","
                + "\"pslWeightVersion\":%d,\"mebnBackupId\":%s,\"checkpointedAt\":\"%s\"}",
                cp.factSheetId(),
                cp.cascadesCompleted(),
                escapeJson(cp.pslProgramKey()),
                cp.pslWeightVersion(),
                cp.mebnBackupId() != null ? "\"" + escapeJson(cp.mebnBackupId()) + "\"" : "null",
                escapeJson(cp.checkpointedAt()));
    }

    private static TrainingCheckpoint fromJson(String json) {
        long factSheetId       = longField(json, "factSheetId");
        long cascadesCompleted = longField(json, "cascadesCompleted");
        String pslProgramKey   = stringField(json, "pslProgramKey");
        int pslWeightVersion   = (int) longField(json, "pslWeightVersion");
        String mebnBackupId    = nullableStringField(json, "mebnBackupId");
        String checkpointedAt  = stringField(json, "checkpointedAt");
        return new TrainingCheckpoint(factSheetId, cascadesCompleted, pslProgramKey,
                pslWeightVersion, mebnBackupId, checkpointedAt);
    }

    private static long longField(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) throw new IllegalArgumentException("Missing field: " + key);
        int start = idx + key.length() + 3;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end).trim());
    }

    private static String stringField(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":\"");
        if (idx < 0) throw new IllegalArgumentException("Missing string field: " + key);
        int start = idx + key.length() + 4;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"') {
            if (json.charAt(end) == '\\') end++; // skip escaped char
            end++;
        }
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String nullableStringField(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return null;
        int valStart = idx + key.length() + 3;
        // Skip whitespace
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart < json.length() && json.charAt(valStart) == 'n') return null; // "null"
        // It's a quoted string
        if (valStart < json.length() && json.charAt(valStart) == '"') {
            int start = valStart + 1;
            int end = start;
            while (end < json.length() && json.charAt(end) != '"') {
                if (json.charAt(end) == '\\') end++;
                end++;
            }
            return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
