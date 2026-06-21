/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.grounding;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.learning.FileWeightStore;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.FileBackedAuditLog;
import ai.kompile.knowledgegraph.audit.PinGuard;
import ai.kompile.knowledgegraph.audit.PinRecord;
import ai.kompile.knowledgegraph.persistence.FileBackedWeightStore;
import ai.kompile.knowledgegraph.persistence.dual.DualStoreGroundingFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correction and PIN control surface for the grounding KB.
 *
 * <p>Implements the correction flow from the design (§4.1):
 * <ol>
 *   <li>Read current InferredFact for atomKey</li>
 *   <li>Emit CORRECTED audit event</li>
 *   <li>Write PinRecord (atom locked)</li>
 *   <li>Write new InferredFact version with corrected value</li>
 *   <li>Publish FactCorrectedEvent (triggers cascade re-ground via existing hook)</li>
 *   <li>Feed correction as training signal to PslWeightLearningService.updateOnBatch</li>
 * </ol>
 *
 * <p>Per-fact-sheet audit logs and pin stores are lazily created and held in maps keyed by
 * factSheetId, following the same pattern as {@link KbGroundingService}.</p>
 */
@Service
@Slf4j
public class KbCorrectionService {

    private final KbGroundingService kbGroundingService;

    @Nullable
    private final ApplicationEventPublisher eventPublisher;

    @Nullable
    @Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    /**
     * When true (default), PSL weight training is run on each human correction.
     * Set {@code kompile.kb.learning.enabled=false} to disable.
     */
    @Value("${kompile.kb.learning.enabled:true}")
    private boolean learningEnabled;

    private final PinGuard pinGuard;
    private final PslWeightLearningService weightLearner;

    /**
     * Spring-injected file-backed weight store (project-scoped, durable across restarts).
     * Null in plain-Java test contexts — falls back to per-fact-sheet in-memory stores.
     */
    @Nullable
    private final FileBackedWeightStore fileBackedWeightStore;

    /**
     * Optional dual-store factory. When non-null, {@link #getWeightStore(long)} returns a
     * {@link ai.kompile.knowledgegraph.persistence.dual.DualStoreWeightStore} (JPA-backed)
     * instead of a {@link FileWeightStore}, providing durable persistence via JPA.
     * Null in plain-Java test contexts.
     */
    @Nullable
    private final DualStoreGroundingFactory dualStoreFactory;

    /** Per-factSheet audit logs (lazy). */
    private final ConcurrentHashMap<Long, FileBackedAuditLog> auditLogs = new ConcurrentHashMap<>();

    /**
     * Per-factSheet weight stores (lazy).
     * In production (Spring), each entry is a fact-sheet-scoped weight store obtained from
     * {@link DualStoreGroundingFactory#weightStoreFor(Long)} when the factory is wired,
     * or from {@link FileBackedWeightStore#fileWeightStoreFor} otherwise.
     * In plain-Java test contexts (no stores), entries remain null and weight persistence is skipped.
     */
    private final ConcurrentHashMap<Long, WeightStore> weightStores = new ConcurrentHashMap<>();

    /** Latest PSL program snapshot per factSheet (used for mini-batch weight update). */
    private final ConcurrentHashMap<Long, PslProgram> programSnapshots = new ConcurrentHashMap<>();

    /**
     * Full Spring constructor (FileBackedWeightStore and optional DualStoreGroundingFactory injected).
     * {@code @Autowired} marks this as the primary injection point when Spring
     * sees multiple constructors.
     */
    @Autowired
    public KbCorrectionService(KbGroundingService kbGroundingService,
                                PinGuard pinGuard,
                                @Nullable ApplicationEventPublisher eventPublisher,
                                @Nullable FileBackedWeightStore fileBackedWeightStore,
                                @Nullable DualStoreGroundingFactory dualStoreFactory) {
        this.kbGroundingService = kbGroundingService;
        this.pinGuard = pinGuard;
        this.eventPublisher = eventPublisher;
        this.fileBackedWeightStore = fileBackedWeightStore;
        this.dualStoreFactory = dualStoreFactory;
        this.weightLearner = new PslWeightLearningService();
    }

    /**
     * Backward-compatible 4-arg constructor (no DualStoreGroundingFactory).
     * Weight persistence uses the file-backed store in this mode.
     */
    public KbCorrectionService(KbGroundingService kbGroundingService,
                                PinGuard pinGuard,
                                @Nullable ApplicationEventPublisher eventPublisher,
                                @Nullable FileBackedWeightStore fileBackedWeightStore) {
        this(kbGroundingService, pinGuard, eventPublisher, fileBackedWeightStore, null);
    }

    /**
     * Backward-compatible 3-arg constructor for plain-Java test contexts
     * that do not have a {@link FileBackedWeightStore} available.
     * Weight persistence is disabled in this mode (no file-backed store).
     */
    public KbCorrectionService(KbGroundingService kbGroundingService,
                                PinGuard pinGuard,
                                @Nullable ApplicationEventPublisher eventPublisher) {
        this(kbGroundingService, pinGuard, eventPublisher, null, null);
    }

    // ── Public API ─────────────────────────────────────────────────────────────────

    /**
     * Apply a human correction to a derived fact.
     *
     * @param factSheetId     fact sheet scope
     * @param atomKey         the atom to correct
     * @param newValue        the corrected value in [0,1]; use 0.0 to tombstone
     * @param actor           human user identifier (e.g. "HUMAN:adam")
     * @param sessionId       session for traceability
     * @param reason          human-supplied justification (may be null)
     * @param tombstone       if true, suppress the atom (set to SUPPRESSED layer)
     * @return the correction result
     */
    public CorrectionResult correct(long factSheetId, String atomKey, double newValue,
                                     String actor, String sessionId, String reason,
                                     boolean tombstone) {
        FactSheetKbState state = kbGroundingService.getState(factSheetId);
        state.lock().writeLock().lock();
        try {
            InferredFactStore inferredStore = state.inferredFactStore();
            Optional<InferredFact> existing = inferredStore.latest(atomKey);

            double valueBefore    = existing.map(InferredFact::value).orElse(Double.NaN);
            double confBefore     = existing.map(InferredFact::confidence).orElse(Double.NaN);
            double effectiveValue = tombstone ? 0.0 : newValue;

            // 1. Emit CORRECTED audit event
            FactAuditEvent auditEvent = FactAuditEvent.corrected(
                    atomKey, valueBefore, effectiveValue,
                    confBefore, effectiveValue,
                    actor, sessionId, true, reason);
            getAuditLog(factSheetId).append(auditEvent);

            // 2. Write PinRecord
            PinRecord pin = PinRecord.pin(atomKey, effectiveValue, actor, auditEvent.eventId());
            pinGuard.save(factSheetId, pin);

            // 3. Write new InferredFact version with corrected value
            long nextVersion = existing.map(f -> f.version() + 1L).orElse(1L);
            String runId = "correction-" + UUID.randomUUID();
            InferredFact correctedFact = new InferredFact(
                    atomKey, effectiveValue, effectiveValue,
                    existing.map(InferredFact::supportingFactKeys).orElse(List.of()),
                    existing.map(InferredFact::supportingRuleIds).orElse(List.of()),
                    runId, nextVersion, Instant.now());
            inferredStore.store(correctedFact);

            // 4. Feed training signal to weight learner (mini-batch, 3 steps)
            boolean trainingApplied = false;
            if (learningEnabled) {
                try {
                    PslProgram program = programSnapshots.get(factSheetId);
                    if (program != null && !program.rules().isEmpty()) {
                        PslProgram updated = weightLearner.updateOnBatch(
                                program, Map.of(atomKey, effectiveValue), 3);
                        programSnapshots.put(factSheetId, updated);
                        // Persist updated weights to the file-backed store (durable across restarts)
                        WeightStore ws = getWeightStore(factSheetId);
                        if (ws != null) {
                            ws.save(factSheetId + "-program", updated.rules());
                        }

                        // Emit WEIGHT_TUNED audit events for rules that changed
                        emitWeightTunedEvents(factSheetId, program, updated, actor, sessionId);
                        trainingApplied = true;
                    }
                } catch (Exception e) {
                    log.warn("KbCorrectionService: weight update failed for factSheet={} atomKey={} — {}",
                            factSheetId, atomKey, e.getMessage());
                }
            }

            log.info("KbCorrectionService: corrected atomKey='{}' in factSheet={} → value={}  pin=true  trainingApplied={}",
                    atomKey, factSheetId, effectiveValue, trainingApplied);

            // 5. Publish FactCorrectedEvent (triggers cascade — released after lock)
            CorrectionResult result = new CorrectionResult(
                    auditEvent.eventId(), correctedFact, pin, trainingApplied);

            publishCorrectedEvent(factSheetId, atomKey, effectiveValue, actor, auditEvent.eventId());
            return result;

        } finally {
            state.lock().writeLock().unlock();
        }
    }

    /**
     * Revert a PIN, allowing subsequent cascade runs to re-derive the atom freely.
     *
     * @param factSheetId the fact sheet
     * @param atomKey     the atom to un-pin
     * @param actor       human who reverted the pin
     */
    public void revertPin(long factSheetId, String atomKey, String actor) {
        Optional<PinRecord> existing = pinGuard.get(factSheetId, atomKey);
        if (existing.isEmpty() || !existing.get().pinned()) {
            log.debug("KbCorrectionService.revertPin: no active pin for factSheet={} atomKey={}",
                    factSheetId, atomKey);
            return;
        }
        String revertEventId = UUID.randomUUID().toString();
        PinRecord reverted = existing.get().reverted(revertEventId);
        pinGuard.save(factSheetId, reverted);

        // Emit CORRECTED audit event showing the pin revert
        FactAuditEvent event = FactAuditEvent.corrected(
                atomKey, existing.get().pinnedValue(), existing.get().pinnedValue(),
                existing.get().pinnedValue(), existing.get().pinnedValue(),
                actor, null, false, "PIN reverted by " + actor);
        getAuditLog(factSheetId).append(event);

        log.info("KbCorrectionService.revertPin: reverted pin for factSheet={} atomKey={}", factSheetId, atomKey);

        // Trigger re-ground so the atom can be re-derived from the current KB
        publishCorrectedEvent(factSheetId, atomKey,
                existing.get().pinnedValue(), actor, revertEventId);
    }

    /**
     * Return all audit events for a fact sheet, with optional filters.
     *
     * @param factSheetId   the fact sheet
     * @param atomKeyFilter optional atom key to filter on (null = all)
     * @param eventType     optional event type to filter on (null = all)
     * @return matching audit events in append order
     */
    public List<FactAuditEvent> getAuditTrail(long factSheetId,
                                               @Nullable String atomKeyFilter,
                                               @Nullable String eventType) {
        return getAuditLog(factSheetId).load(atomKeyFilter, eventType);
    }

    /**
     * Return all active (pinned=true) pins for a fact sheet.
     */
    public List<PinRecord> getActivePins(long factSheetId) {
        return pinGuard.activePins(factSheetId);
    }

    /**
     * Register the current PSL program snapshot for a fact sheet.
     * Called by the orchestrator after each MAP solve so the correction service
     * has a fresh program to warm-start weight updates.
     */
    public void registerProgram(long factSheetId, PslProgram program) {
        if (program != null) {
            programSnapshots.put(factSheetId, program);
        }
    }

    /**
     * Append a DERIVED audit event for an atom that was materialized during a cascade re-ground.
     *
     * <p>Called by {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
     * after each {@code inferredStore.store(newFact)} call so that the audit trail records
     * every MAP-derived update. Thread-safe: the orchestrator holds the write lock when calling.</p>
     *
     * @param factSheetId      the fact sheet
     * @param atomKey          the atom that was re-derived
     * @param valueBefore      value before this cascade run (NaN if first materialization)
     * @param valueAfter       value after this cascade run
     * @param confidenceBefore confidence before (NaN if first materialization)
     * @param confidenceAfter  confidence after
     * @param runId            the MAP inference run id
     */
    public void appendDerivedAuditEvent(long factSheetId, String atomKey,
                                         double valueBefore, double valueAfter,
                                         double confidenceBefore, double confidenceAfter,
                                         String runId) {
        FactAuditEvent event = FactAuditEvent.derived(
                atomKey, valueBefore, valueAfter, confidenceBefore, confidenceAfter, runId, null);
        getAuditLog(factSheetId).append(event);
    }

    /**
     * Append a CONTRADICTION_RESOLVED audit event for a fact that was retracted by the TMS
     * because a higher-confidence contradicting fact was retained.
     *
     * <p>Called by {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
     * after STEP 7 TMS retraction to record which atom was dropped and which was retained.</p>
     *
     * @param factSheetId   the fact sheet where the contradiction was detected
     * @param retractedAtomKey the atom key that was retracted (lower confidence)
     * @param retainedValue    the value of the retained (higher confidence) side
     * @param retainedAtomKey  the atom key of the retained side
     * @param runId            the cascade run ID for traceability
     */
    public void appendContradictionResolvedEvent(long factSheetId, String retractedAtomKey,
                                                  double retainedValue, String retainedAtomKey,
                                                  String runId) {
        FactAuditEvent event = FactAuditEvent.contradictionResolved(
                retractedAtomKey, retainedValue, retainedAtomKey, retractedAtomKey, runId);
        getAuditLog(factSheetId).append(event);
    }

    /**
     * Append a single WEIGHT_TUNED audit event for a PSL rule whose weight changed
     * during the cascade weight-learning step.
     *
     * <p>Called by {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
     * after each successful PSL mini-batch update in STEP 5b to close the gap where cascade
     * weight training never emitted audit events.</p>
     *
     * @param factSheetId the fact sheet whose program was updated
     * @param ruleId      the rule identifier (typically the rule's string representation)
     * @param weightBefore the rule weight before the update
     * @param weightAfter  the rule weight after the update
     * @param runId        the cascade run ID (used as sessionId for traceability)
     */
    public void appendWeightTunedEvent(long factSheetId, String ruleId,
                                        double weightBefore, double weightAfter, String runId) {
        FactAuditEvent event = FactAuditEvent.weightTuned(ruleId, weightBefore, weightAfter,
                "CASCADE", runId);
        getAuditLog(factSheetId).append(event);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    FileBackedAuditLog getAuditLog(long factSheetId) {
        return auditLogs.computeIfAbsent(factSheetId, id -> {
            if (dataDir != null && !dataDir.isBlank()) {
                return new FileBackedAuditLog(Path.of(dataDir), id);
            }
            return new FileBackedAuditLog(id);
        });
    }

    /**
     * Return a {@link WeightStore} for the given fact sheet.
     *
     * <p>Priority:
     * <ol>
     *   <li>If a {@link DualStoreGroundingFactory} is wired (JPA path), use it — returns a
     *       fact-sheet-scoped {@link ai.kompile.knowledgegraph.persistence.dual.DualStoreWeightStore}.</li>
     *   <li>Else if the Spring-injected {@link FileBackedWeightStore} is available, returns a
     *       fact-sheet-scoped {@link FileWeightStore} backed by
     *       {@code <dataDir>/data/graph/reasoning/<factSheetId>/psl-weights/}.</li>
     *   <li>Otherwise returns {@code null} — callers must null-check before calling save.</li>
     * </ol>
     */
    @Nullable
    WeightStore getWeightStore(long factSheetId) {
        if (dualStoreFactory != null) {
            return weightStores.computeIfAbsent(factSheetId,
                    id -> dualStoreFactory.weightStoreFor(id));
        }
        if (fileBackedWeightStore == null) {
            return null; // plain-Java test context — no store available
        }
        return weightStores.computeIfAbsent(factSheetId,
                id -> fileBackedWeightStore.fileWeightStoreFor(String.valueOf(id)));
    }

    private void publishCorrectedEvent(long factSheetId, String atomKey,
                                        double correctedValue, String actor, String auditEventId) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(
                        new FactCorrectedEvent(this, factSheetId, atomKey,
                                correctedValue, actor, auditEventId));
            } catch (Exception e) {
                log.warn("KbCorrectionService: failed to publish FactCorrectedEvent — {}", e.getMessage());
            }
        }
    }

    private void emitWeightTunedEvents(long factSheetId, PslProgram before, PslProgram after,
                                        String actor, String sessionId) {
        if (before.rules().size() != after.rules().size()) return;
        double epsilon = 0.001;
        for (int i = 0; i < before.rules().size(); i++) {
            double wb = before.rules().get(i).weight();
            double wa = after.rules().get(i).weight();
            if (Math.abs(wb - wa) > epsilon) {
                FactAuditEvent event = FactAuditEvent.weightTuned(
                        before.rules().get(i).toString(), wb, wa, actor, sessionId);
                getAuditLog(factSheetId).append(event);
            }
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────────

    /**
     * Result of a {@link #correct} operation.
     *
     * @param auditEventId         the ID of the CORRECTED audit event
     * @param newInferredFact      the new InferredFact version with the corrected value
     * @param pinRecord            the PinRecord that was written
     * @param trainingSignalApplied true if PslWeightLearningService.updateOnBatch was called
     */
    public record CorrectionResult(
            String auditEventId,
            InferredFact newInferredFact,
            PinRecord pinRecord,
            boolean trainingSignalApplied) {}
}
