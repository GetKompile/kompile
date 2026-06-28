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

package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.learning.WeightBackupInfo;
import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import ai.kompile.knowledgegraph.persistence.TrainingCheckpointStore;
import ai.kompile.knowledgegraph.persistence.WeightSessionService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller exposing the learned PSL rule weights persisted in the {@link WeightStore},
 * and management endpoints for weight-model backups, reset, and new-session initialisation.
 *
 * <p>The WeightStore and {@link WeightSessionService} may not be registered as Spring beans in
 * all deployment configurations. When absent the controller degrades gracefully: GET /api/kb/weights
 * returns 503 with a descriptive message rather than a 500 error.</p>
 *
 * <p>Package is under {@code ai.kompile.app.web.controllers.grounding} which is covered by
 * the global {@link ai.kompile.app.web.GlobalExceptionHandler}.</p>
 *
 * <h3>New endpoints</h3>
 * <ul>
 *   <li>{@code POST /api/kb/weights/session/new?programId=X&factSheetId=Y} — backup + reinit both
 *       PSL and MEBN weights (start a new training session)</li>
 *   <li>{@code POST /api/kb/weights/reset?programId=X} — backup + reset PSL weights to defaults</li>
 *   <li>{@code POST /api/kb/weights/backup?programId=X} — explicit backup of PSL weights</li>
 *   <li>{@code GET  /api/kb/weights/backups?programId=X} — list PSL weight backups</li>
 *   <li>{@code POST /api/kb/weights/restore?programId=X&backupId=Y} — restore PSL weights from backup</li>
 *   <li>{@code POST /api/kb/weights/mebn/{factSheetId}/reset} — backup + reset MEBN edge strengths</li>
 *   <li>{@code POST /api/kb/weights/mebn/{factSheetId}/backup} — explicit MEBN backup</li>
 *   <li>{@code GET  /api/kb/weights/mebn/{factSheetId}/backups} — list MEBN backups</li>
 *   <li>{@code POST /api/kb/weights/mebn/{factSheetId}/restore?backupId=Y} — restore MEBN from backup</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/kb/weights")
public class KbWeightsController {

    @Nullable
    private final WeightStore weightStore;

    @Nullable
    private final MebnWeightPersistenceAdapter mebnWeightPersistenceAdapter;

    @Nullable
    private final WeightSessionService weightSessionService;

    @Nullable
    private final IncrementalReasoningOrchestrator reasoningOrchestrator;

    @Autowired
    public KbWeightsController(@Nullable WeightStore weightStore,
                               @Nullable MebnWeightPersistenceAdapter mebnWeightPersistenceAdapter,
                               @Nullable WeightSessionService weightSessionService,
                               @Nullable IncrementalReasoningOrchestrator reasoningOrchestrator) {
        this.weightStore = weightStore;
        this.mebnWeightPersistenceAdapter = mebnWeightPersistenceAdapter;
        this.weightSessionService = weightSessionService;
        this.reasoningOrchestrator = reasoningOrchestrator;
    }

    // ── Read endpoints ──────────────────────────────────────────────────────────

    /**
     * Retrieve the latest weight-set for the given programId.
     *
     * @param programId the PSL program/ruleset identifier; defaults to {@code "default"}
     * @return 200 with {@link WeightsResponse}; 503 if the WeightStore is not available
     */
    @GetMapping
    public ResponseEntity<WeightsResponse> getWeights(
            @RequestParam(defaultValue = "default") String programId) {

        if (weightStore == null) {
            return ResponseEntity.status(503).body(
                    new WeightsResponse(programId, 0, Map.of(), List.of(),
                            "WeightStore is not available in this configuration"));
        }

        Optional<Map<String, Double>> latest = weightStore.latest(programId);
        List<String> available = weightStore.programIds().stream().sorted().toList();

        if (latest.isEmpty()) {
            return ResponseEntity.ok(
                    new WeightsResponse(programId, 0, Map.of(), available, null));
        }

        return ResponseEntity.ok(
                new WeightsResponse(programId,
                        weightStore.latestVersion(programId),
                        latest.get(),
                        available,
                        null));
    }

    /**
     * List all programIds that have stored weights.
     *
     * @return sorted list of programIds
     */
    @GetMapping("/programs")
    public ResponseEntity<List<String>> getPrograms() {
        if (weightStore == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(weightStore.programIds().stream().sorted().toList());
    }

    /**
     * Retrieve the MEBN MFrag/theory edge-strength weights for the given fact sheet.
     *
     * <p>Data source: {@code <dataDir>/data/graph/reasoning/<factSheetId>/mebn-weights.json}
     * written by {@link ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter}.
     * Each entry in that file has the composite key {@code "<mfragName>|<parent>-><child>"}
     * mapping to a learned noisy-OR edge strength in [0,1].</p>
     *
     * <p>The returned {@link MebnWeightRow} list splits each composite key into
     * {@code mFragName} and {@code conditionDescription} ({@code "<parent>-><child>"})
     * and includes the {@code learnedStrength}.</p>
     *
     * @param factSheetId the fact-sheet scoping the MEBN theory
     * @return 200 with sorted list of {@link MebnWeightRow}; 503 if the adapter is unavailable;
     *         404 with empty list if no weights have been persisted for this fact sheet yet
     */
    @GetMapping("/mebn/{factSheetId}")
    public ResponseEntity<List<MebnWeightRow>> getMebnWeights(@PathVariable long factSheetId) {
        if (mebnWeightPersistenceAdapter == null) {
            return ResponseEntity.status(503).build();
        }
        try {
            Map<String, Double> raw = mebnWeightPersistenceAdapter.readRawStrengths(factSheetId);
            if (raw.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            List<MebnWeightRow> rows = new ArrayList<>(raw.size());
            for (Map.Entry<String, Double> entry : raw.entrySet()) {
                String compositeKey = entry.getKey();
                int pipe = compositeKey.lastIndexOf('|');
                String mFragName = pipe >= 0 ? compositeKey.substring(0, pipe) : compositeKey;
                String conditionDescription = pipe >= 0 ? compositeKey.substring(pipe + 1) : "";
                rows.add(new MebnWeightRow(mFragName, conditionDescription, entry.getValue()));
            }
            rows.sort((a, b) -> {
                int cmp = a.mFragName().compareTo(b.mFragName());
                return cmp != 0 ? cmp : a.conditionDescription().compareTo(b.conditionDescription());
            });
            return ResponseEntity.ok(rows);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    // ── PSL backup / reset / restore ─────────────────────────────────────────

    /**
     * Explicitly back up the current PSL weights for the given programId.
     *
     * @param programId the program/ruleset to back up
     * @return 200 with {@link BackupResult}; 503 if the session service is unavailable;
     *         404 if there are no weights to back up yet
     */
    @PostMapping("/backup")
    public ResponseEntity<BackupResult> backupPsl(
            @RequestParam(defaultValue = "default") String programId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(new BackupResult(null, "WeightSessionService not available"));
        }
        String backupId = weightSessionService.backupPsl(programId);
        if (backupId == null) {
            return ResponseEntity.status(404).body(new BackupResult(null, "No weights to back up for '" + programId + "'"));
        }
        return ResponseEntity.ok(new BackupResult(backupId, null));
    }

    /**
     * List PSL weight backups for the given programId, newest first.
     *
     * @param programId the program/ruleset
     * @return 200 with list of {@link WeightBackupInfo}; 503 if unavailable
     */
    @GetMapping("/backups")
    public ResponseEntity<List<WeightBackupInfo>> listPslBackups(
            @RequestParam(defaultValue = "default") String programId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(weightSessionService.listPslBackups(programId));
    }

    /**
     * Restore PSL weights from a backup snapshot.
     *
     * @param programId the program/ruleset
     * @param backupId  the backup identifier returned by a previous backup call
     * @return 200 on success; 404 if the backup was not found; 503 if unavailable
     */
    @PostMapping("/restore")
    public ResponseEntity<OperationResult> restorePsl(
            @RequestParam(defaultValue = "default") String programId,
            @RequestParam String backupId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(new OperationResult(false, "WeightSessionService not available"));
        }
        boolean ok = weightSessionService.restorePsl(programId, backupId);
        if (!ok) {
            return ResponseEntity.status(404).body(new OperationResult(false, "Backup not found: " + backupId));
        }
        return ResponseEntity.ok(new OperationResult(true, "Restored weights from backup " + backupId));
    }

    /**
     * Reset PSL weights for the given programId to their cold-start defaults.
     * The current weights are automatically backed up before the reset.
     *
     * @param programId the program/ruleset to reset
     * @return 200 with {@link ResetResult}; 503 if unavailable
     */
    @PostMapping("/reset")
    public ResponseEntity<ResetResult> resetPsl(
            @RequestParam(defaultValue = "default") String programId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(new ResetResult(null, null, "WeightSessionService not available"));
        }
        String backupId = weightSessionService.resetPsl(programId);
        return ResponseEntity.ok(new ResetResult(programId, backupId, null));
    }

    // ── MEBN backup / reset / restore ────────────────────────────────────────

    /**
     * Explicitly back up the MEBN edge-strength weights for the given fact sheet.
     *
     * @param factSheetId the fact-sheet scoping the MEBN theory
     * @return 200 with {@link BackupResult}; 503 if unavailable; 404 if no MEBN weights yet
     */
    @PostMapping("/mebn/{factSheetId}/backup")
    public ResponseEntity<BackupResult> backupMebn(@PathVariable long factSheetId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(new BackupResult(null, "WeightSessionService not available"));
        }
        String backupId = weightSessionService.backupMebn(factSheetId);
        if (backupId == null) {
            return ResponseEntity.status(404).body(new BackupResult(null, "No MEBN weights to back up for factSheet " + factSheetId));
        }
        return ResponseEntity.ok(new BackupResult(backupId, null));
    }

    /**
     * List MEBN weight backups for the given fact sheet, newest first.
     *
     * @param factSheetId the fact-sheet identifier
     * @return 200 with list of {@link WeightBackupInfo}; 503 if unavailable
     */
    @GetMapping("/mebn/{factSheetId}/backups")
    public ResponseEntity<List<WeightBackupInfo>> listMebnBackups(@PathVariable long factSheetId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(weightSessionService.listMebnBackups(factSheetId));
    }

    /**
     * Restore MEBN edge strengths from a backup snapshot.
     *
     * @param factSheetId the fact-sheet identifier
     * @param backupId    the backup identifier returned by a previous backup call
     * @return 200 on success; 404 if backup not found; 503 if unavailable
     */
    @PostMapping("/mebn/{factSheetId}/restore")
    public ResponseEntity<OperationResult> restoreMebn(
            @PathVariable long factSheetId,
            @RequestParam String backupId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(new OperationResult(false, "WeightSessionService not available"));
        }
        boolean ok = weightSessionService.restoreMebn(factSheetId, backupId);
        if (!ok) {
            return ResponseEntity.status(404).body(new OperationResult(false, "MEBN backup not found: " + backupId));
        }
        return ResponseEntity.ok(new OperationResult(true, "Restored MEBN weights from backup " + backupId));
    }

    /**
     * Reset MEBN edge strengths for the given fact sheet to their cold-start defaults (0.5).
     * The current MEBN weights are automatically backed up before the reset.
     *
     * @param factSheetId the fact-sheet identifier
     * @return 200 with {@link ResetResult}; 503 if unavailable
     */
    @PostMapping("/mebn/{factSheetId}/reset")
    public ResponseEntity<ResetResult> resetMebn(@PathVariable long factSheetId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(new ResetResult(null, null, "WeightSessionService not available"));
        }
        String backupId = weightSessionService.resetMebn(factSheetId);
        return ResponseEntity.ok(new ResetResult("mebn:" + factSheetId, backupId, null));
    }

    // ── Combined new-session endpoint ─────────────────────────────────────────

    /**
     * Start a new weight-training session: backs up and reinitialises BOTH the PSL weights
     * (for the given {@code programId}) and the MEBN edge strengths (for {@code factSheetId}).
     *
     * <p>This is the primary "New Session" action exposed by the UI.  It is equivalent to
     * calling the reset endpoints for both PSL and MEBN in sequence.</p>
     *
     * @param programId   the PSL program/ruleset to reset (default {@code "default"})
     * @param factSheetId the fact-sheet whose MEBN weights to reset
     * @return 200 with {@link NewSessionResult}; 503 if unavailable
     */
    @PostMapping("/session/new")
    public ResponseEntity<NewSessionResult> startNewSession(
            @RequestParam(defaultValue = "default") String programId,
            @RequestParam long factSheetId) {
        if (weightSessionService == null) {
            return ResponseEntity.status(503).body(
                    new NewSessionResult(programId, factSheetId, null, null, "WeightSessionService not available"));
        }
        WeightSessionService.SessionResetResult result =
                weightSessionService.startNewSession(programId, factSheetId);
        return ResponseEntity.ok(
                new NewSessionResult(programId, factSheetId, result.pslBackupId(), result.mebnBackupId(), null));
    }

    // ── Training checkpoint (resume) ──────────────────────────────────────────

    /**
     * Return the durable training checkpoint for {@code factSheetId}.
     *
     * <p>The checkpoint records how many cascade gradient steps have been persisted for the
     * given fact sheet, plus the PSL weight version and MEBN backup ID at the time of the
     * last successful step.  The DERIVATION stage reads this on first encounter and seeds
     * the cascade counter from {@code cascadesCompleted} so training continues from the last
     * saved position rather than restarting from zero.</p>
     *
     * <p>Returns {@code 404} if no checkpoint exists (cold-start / after {@code /session/new}).</p>
     *
     * @param factSheetId the fact-sheet identifier
     * @return 200 with {@link TrainingCheckpointResponse}; 404 if no checkpoint; 503 if unavailable
     */
    @GetMapping("/training-checkpoint/{factSheetId}")
    public ResponseEntity<TrainingCheckpointResponse> getTrainingCheckpoint(@PathVariable long factSheetId) {
        if (reasoningOrchestrator == null) {
            return ResponseEntity.status(503).build();
        }
        return reasoningOrchestrator.trainingCheckpoint(factSheetId)
                .map(cp -> ResponseEntity.ok(new TrainingCheckpointResponse(
                        cp.factSheetId(), cp.cascadesCompleted(),
                        cp.pslProgramKey(), cp.pslWeightVersion(),
                        cp.mebnBackupId(), cp.checkpointedAt(),
                        reasoningOrchestrator.currentCascadeCount(factSheetId),
                        null)))
                .orElse(ResponseEntity.status(404).body(
                        new TrainingCheckpointResponse(factSheetId, 0, null, 0, null, null,
                                reasoningOrchestrator.currentCascadeCount(factSheetId),
                                "No training checkpoint exists — fact sheet will cold-start on next DERIVATION")));
    }

    /**
     * Delete the training checkpoint for {@code factSheetId} and reset the in-memory cascade
     * counter.
     *
     * <p>Forces the next DERIVATION to cold-start from epoch 0 (equivalent to calling
     * {@code /session/new} but without touching the weight files).  Idempotent: safe to call
     * even when no checkpoint exists.</p>
     *
     * @param factSheetId the fact-sheet identifier
     * @return 200 always (idempotent)
     */
    @DeleteMapping("/training-checkpoint/{factSheetId}")
    public ResponseEntity<OperationResult> clearTrainingCheckpoint(@PathVariable long factSheetId) {
        if (reasoningOrchestrator == null) {
            return ResponseEntity.status(503).body(
                    new OperationResult(false, "IncrementalReasoningOrchestrator not available"));
        }
        reasoningOrchestrator.clearTrainingCheckpoint(factSheetId);
        return ResponseEntity.ok(new OperationResult(true,
                "Training checkpoint cleared for factSheet " + factSheetId + " — next DERIVATION will cold-start"));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /**
     * Response DTO for the weights endpoint.
     *
     * @param programId         the program identifier
     * @param version           the monotonic version of the returned weights (0 = none stored)
     * @param weights           rule-display-text → weight value map
     * @param availablePrograms all programIds that have stored weights
     * @param message           optional informational or error message
     */
    public record WeightsResponse(
            String programId,
            int version,
            Map<String, Double> weights,
            List<String> availablePrograms,
            String message
    ) {}

    /**
     * One MEBN edge-strength row returned by {@link #getMebnWeights(long)}.
     *
     * @param mFragName            the name of the MFrag that owns this edge
     * @param conditionDescription the edge description in {@code "<parent>-><child>"} format
     * @param learnedStrength      the learned noisy-OR edge strength in [0, 1]
     */
    public record MebnWeightRow(
            String mFragName,
            String conditionDescription,
            double learnedStrength
    ) {}

    /**
     * Result of a backup operation.
     *
     * @param backupId the opaque backup identifier (UTC timestamp); null if nothing was backed up
     * @param message  error or info message; null on success
     */
    public record BackupResult(String backupId, String message) {}

    /**
     * Result of a reset operation.
     *
     * @param programId  the program/ruleset that was reset
     * @param backupId   the auto-backup taken before reset; null if there were no weights to back up
     * @param message    error or info message; null on success
     */
    public record ResetResult(String programId, String backupId, String message) {}

    /**
     * Result of a restore operation.
     *
     * @param success  whether the restore succeeded
     * @param message  description of outcome
     */
    public record OperationResult(boolean success, String message) {}

    /**
     * Result of a new-session (combined PSL + MEBN reset) operation.
     *
     * @param programId     the PSL program that was reset
     * @param factSheetId   the fact sheet whose MEBN weights were reset
     * @param pslBackupId   backup id for PSL weights; null if none existed
     * @param mebnBackupId  backup id for MEBN weights; null if none existed
     * @param message       error message; null on success
     */
    public record NewSessionResult(
            String programId,
            long factSheetId,
            String pslBackupId,
            String mebnBackupId,
            String message
    ) {}

    /**
     * Training-position checkpoint for a fact sheet.
     *
     * @param factSheetId          the fact sheet this checkpoint belongs to
     * @param cascadesCompleted    number of online gradient steps that have been durably persisted
     * @param pslProgramKey        the PSL program key in the weight store (e.g. {@code "42:cascade"})
     * @param pslWeightVersion     the PSL weight file version at the time of checkpointing
     * @param mebnBackupId         the MEBN backup ID at the time of checkpointing; null if MEBN has not run
     * @param checkpointedAt       ISO-8601 timestamp of when the checkpoint was written
     * @param inMemoryCascadeCount the current in-memory cascade counter for this JVM session (may be 0 if the
     *                             JVM was restarted after the checkpoint was written)
     * @param message              informational or error message; null on success
     */
    public record TrainingCheckpointResponse(
            long factSheetId,
            long cascadesCompleted,
            String pslProgramKey,
            int pslWeightVersion,
            String mebnBackupId,
            String checkpointedAt,
            long inMemoryCascadeCount,
            String message
    ) {}
}
