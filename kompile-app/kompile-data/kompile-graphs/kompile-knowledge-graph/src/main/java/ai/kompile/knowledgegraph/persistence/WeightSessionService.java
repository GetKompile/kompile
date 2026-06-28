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

import ai.kompile.graph.reasoning.learning.WeightBackupInfo;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service layer for weight-model session management: backup, reset (reinit), restore, and
 * "new training session" (backup + reinit of both PSL weights and MEBN edge strengths).
 *
 * <h3>Scope</h3>
 * <p>Wraps the durable {@link FileBackedWeightStore} for PSL rule weights, and the
 * {@link MebnWeightPersistenceAdapter} for MEBN noisy-OR edge strengths. Both are backed
 * by files under {@code <dataDir>/data/graph/reasoning/}.</p>
 *
 * <h3>Initial / default weight values</h3>
 * <p>On reset, PSL rule weights are all set to {@link KbConfig#getPslDefaultRuleWeight()}
 * (default 0.8 — the cold-start value written by {@link KbConfig}).  MEBN edge strengths
 * have no stored prior mean so they are reset to the MEBN-learning initial value 0.5
 * (uniform Bernoulli prior for a noisy-OR gate with no evidence).  Both choices mirror the
 * first values the online learner would assign in a cold-start run.</p>
 *
 * <h3>Concurrency</h3>
 * <p>Not thread-safe — the {@link FileBackedWeightStore} is not synchronized.  The REST
 * controller must not issue concurrent reset/restore calls.  This is enforced by the REST
 * layer returning 409 if a reset is already in progress (future work; currently single-
 * threaded).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeightSessionService {

    /** Uniform noisy-OR prior used for MEBN edge strengths on cold-start / reset. */
    private static final double MEBN_DEFAULT_STRENGTH = 0.5;

    private static final DateTimeFormatter MEBN_BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final FileBackedWeightStore weightStore;
    private final MebnWeightPersistenceAdapter mebnAdapter;
    private final KbConfigManager kbConfigManager;
    private final TrainingCheckpointStore trainingCheckpointStore;

    // ── PSL weight operations ─────────────────────────────────────────────────

    /**
     * Back up the current PSL weights for {@code programId}.
     *
     * @param programId the program/ruleset to back up
     * @return the backupId (UTC timestamp string), or {@code null} if no weights exist yet
     */
    public String backupPsl(String programId) {
        String id = weightStore.backup(programId);
        if (id != null) {
            log.info("WeightSessionService: backed up PSL weights for '{}' → backupId={}", programId, id);
        }
        return id;
    }

    /**
     * List all available PSL weight backups for {@code programId}, newest first.
     */
    public List<WeightBackupInfo> listPslBackups(String programId) {
        return weightStore.listBackups(programId);
    }

    /**
     * Restore PSL weights for {@code programId} from the given {@code backupId}.
     *
     * @return {@code true} if the backup was found and restored
     */
    public boolean restorePsl(String programId, String backupId) {
        boolean ok = weightStore.restoreBackup(programId, backupId);
        if (ok) {
            log.info("WeightSessionService: restored PSL weights for '{}' from backupId={}", programId, backupId);
        } else {
            log.warn("WeightSessionService: PSL backup not found for '{}', backupId={}", programId, backupId);
        }
        return ok;
    }

    /**
     * Reset PSL weights for {@code programId} to the cold-start default.
     * Auto-backs up the current weights first (if any).
     *
     * @return the backupId of the auto-backup, or {@code null} if there were no weights to back up
     */
    public String resetPsl(String programId) {
        KbConfig cfg = safeConfig();
        double defaultWeight = cfg.getPslDefaultRuleWeight();
        String backupId = weightStore.reset(programId, defaultWeight);
        log.info("WeightSessionService: reset PSL weights for '{}' to default={}, backupId={}",
                programId, defaultWeight, backupId);
        return backupId;
    }

    // ── MEBN weight operations ────────────────────────────────────────────────

    /**
     * Back up the current MEBN edge strengths for {@code factSheetId}.
     * The backup is written to {@code <reasoningDir>/<factSheetId>/mebn-weights.bak.<timestamp>.json}.
     *
     * @return the backupId (timestamp string), or {@code null} if no MEBN weights file exists
     */
    public String backupMebn(long factSheetId) {
        Path source = mebnAdapter.mebnArtifactPath(factSheetId);
        if (!Files.exists(source)) {
            return null;
        }
        String ts = MEBN_BACKUP_TS.format(Instant.now());
        Path backupDir = source.getParent().resolve("mebn-backups");
        Path dest = backupDir.resolve("mebn-weights.bak." + ts + ".json");
        try {
            Files.createDirectories(backupDir);
            Files.copy(source, dest);
            pruneMebnBackups(backupDir, factSheetId);
            log.info("WeightSessionService: backed up MEBN weights for factSheet {} → {}", factSheetId, dest);
            return ts;
        } catch (IOException e) {
            throw new UncheckedIOException("WeightSessionService: cannot backup MEBN weights for factSheet " + factSheetId, e);
        }
    }

    /**
     * List all MEBN weight backups for {@code factSheetId}, newest first.
     */
    public List<WeightBackupInfo> listMebnBackups(long factSheetId) {
        Path source = mebnAdapter.mebnArtifactPath(factSheetId);
        Path backupDir = source.getParent().resolve("mebn-backups");
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (var stream = Files.list(backupDir)) {
            List<WeightBackupInfo> result = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("mebn-weights.bak."))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        // mebn-weights.bak.<ts>.json
                        String ts = name.replace("mebn-weights.bak.", "").replace(".json", "");
                        int count = 0;
                        try {
                            String json = Files.readString(p, StandardCharsets.UTF_8);
                            count = ai.kompile.graph.reasoning.learning.MebnWeightSerializer
                                    .parseStrengths(json).size();
                        } catch (IOException ignored) {
                            // keep count=0
                        }
                        return new WeightBackupInfo(ts, "mebn:" + factSheetId, ts, 0, count);
                    })
                    .sorted(java.util.Comparator.comparing(WeightBackupInfo::backupId).reversed())
                    .toList();
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("WeightSessionService: cannot list MEBN backups for factSheet " + factSheetId, e);
        }
    }

    /**
     * Restore MEBN weights for {@code factSheetId} from the given {@code backupId} (timestamp).
     *
     * @return {@code true} if the backup was found and restored
     */
    public boolean restoreMebn(long factSheetId, String backupId) {
        Path source = mebnAdapter.mebnArtifactPath(factSheetId);
        Path backupDir = source.getParent().resolve("mebn-backups");
        Path backupFile = backupDir.resolve("mebn-weights.bak." + backupId + ".json");
        if (!Files.exists(backupFile)) {
            log.warn("WeightSessionService: MEBN backup not found for factSheet {}, backupId={}", factSheetId, backupId);
            return false;
        }
        try {
            Files.createDirectories(source.getParent());
            Files.copy(backupFile, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("WeightSessionService: restored MEBN weights for factSheet {} from backupId={}", factSheetId, backupId);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("WeightSessionService: cannot restore MEBN weights for factSheet " + factSheetId, e);
        }
    }

    /**
     * Reset MEBN edge strengths for {@code factSheetId} to the cold-start default (0.5).
     * Auto-backs up the current MEBN weights first (if any).
     *
     * @return the backupId of the auto-backup, or {@code null} if there were no MEBN weights to back up
     */
    public String resetMebn(long factSheetId) {
        String backupId = backupMebn(factSheetId);
        // Read current strengths and re-write with reset values
        Map<String, Double> existing;
        try {
            existing = mebnAdapter.readRawStrengths(factSheetId);
        } catch (IOException e) {
            throw new UncheckedIOException("WeightSessionService: cannot read MEBN weights for reset", e);
        }
        if (!existing.isEmpty()) {
            // Build reset map — same keys, all set to MEBN_DEFAULT_STRENGTH
            Map<String, Double> reset = new java.util.LinkedHashMap<>();
            for (String key : existing.keySet()) {
                reset.put(key, MEBN_DEFAULT_STRENGTH);
            }
            // Write as raw JSON directly to the mebn-weights.json file
            Path target = mebnAdapter.mebnArtifactPath(factSheetId);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, toStrengthJson(reset), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("WeightSessionService: cannot write reset MEBN weights", e);
            }
            log.info("WeightSessionService: reset MEBN weights for factSheet {} to default={}, backupId={}",
                    factSheetId, MEBN_DEFAULT_STRENGTH, backupId);
        }
        return backupId;
    }

    // ── Combined session reset ────────────────────────────────────────────────

    /**
     * Start a new weight-training session: backs up and reinitialises BOTH PSL weights
     * (for the given {@code programId}) and MEBN edge strengths (for {@code factSheetId}).
     *
     * <p>This is the "New Session" action exposed via the REST endpoint.  It is equivalent to
     * calling {@link #resetPsl(String)} followed by {@link #resetMebn(long)} in sequence.
     * The result captures both backup ids so the caller can surface them in the UI.</p>
     *
     * @param programId   the PSL program/ruleset to reset
     * @param factSheetId the fact sheet whose MEBN weights to reset
     * @return a {@link SessionResetResult} containing both backup IDs
     */
    public SessionResetResult startNewSession(String programId, long factSheetId) {
        String pslBackupId = resetPsl(programId);
        String mebnBackupId = resetMebn(factSheetId);
        // Clear the durable training-position checkpoint so the next DERIVATION starts
        // from epoch 0 (cold-start), not from the prior run's cascade position.
        trainingCheckpointStore.clear(factSheetId);
        log.info("WeightSessionService: new training session started for programId='{}', factSheetId={}; " +
                "pslBackup={}, mebnBackup={}", programId, factSheetId, pslBackupId, mebnBackupId);
        return new SessionResetResult(pslBackupId, mebnBackupId);
    }

    /**
     * Result of {@link #startNewSession}: backup IDs for the PSL and MEBN weight stores.
     *
     * @param pslBackupId  backupId for the PSL weights backup (null if none existed)
     * @param mebnBackupId backupId for the MEBN weights backup (null if none existed)
     */
    public record SessionResetResult(String pslBackupId, String mebnBackupId) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private KbConfig safeConfig() {
        try {
            KbConfig cfg = kbConfigManager.current();
            return (cfg != null) ? cfg : KbConfig.defaults();
        } catch (Exception e) {
            log.debug("WeightSessionService: could not load KbConfig, using defaults", e);
            return KbConfig.defaults();
        }
    }

    /** Prune MEBN backups, keeping at most 10. */
    private void pruneMebnBackups(Path backupDir, long factSheetId) throws IOException {
        try (var stream = Files.list(backupDir)) {
            List<Path> backups = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("mebn-weights.bak."))
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
            while (backups.size() > 10) {
                Files.deleteIfExists(backups.remove(0));
            }
        }
    }

    /** Serialize a strength map as JSON (mirrors MebnWeightSerializer format). */
    private static String toStrengthJson(Map<String, Double> strengths) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> e : strengths.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":");
            sb.append(String.format(Locale.ROOT, "%s", e.getValue()));
        }
        return sb.append('}').toString();
    }
}
