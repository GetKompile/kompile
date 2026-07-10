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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.FileBackedAuditLog;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRow;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-fact corroboration counts and StrengthBand transitions across successive
 * grounding cascade runs.
 *
 * <h3>Promotion logic</h3>
 * <p>On each {@link #checkPromotion} call the tracker:
 * <ol>
 *   <li>Determines the old band from the prior observation (or SPECULATIVE if none).</li>
 *   <li>Determines the new band from the new value via {@link StrengthBand#fromScalar(double)}.</li>
 *   <li>Increments the per-atom corroboration count.</li>
 *   <li>If {@code newBand.ordinal() > oldBand.ordinal()} (higher confidence tier): publishes a
 *       {@link FactPromotedEvent} and appends a PROMOTED {@link FactAuditEvent} to the audit log.</li>
 * </ol>
 *
 * <h3>Facts-by-tier query</h3>
 * <p>{@link #factsByTier(long, InferredFactStore, StrengthBand)} filters the store's latest facts
 * by band so callers can page/export facts at a given confidence tier.</p>
 *
 * <h3>Spring wiring</h3>
 * <p>This is an optional Spring {@code @Component}. It is null-safely injected into
 * {@link IncrementalReasoningOrchestrator} via an {@code @Autowired} field so existing
 * plain-Java tests that don't bootstrap Spring are unaffected.</p>
 */
@Component
@Slf4j
public class FactPromotionTracker {

    // ── Inner state ──────────────────────────────────────────────────────────────

    /**
     * In-memory promotion state for a single (factSheetId, atomKey) pair.
     * Fields are set by {@link #checkPromotion} and optionally hydrated from the DB
     * by {@link #ensureHydrated(long)} on first access per fact sheet.
     */
    private static final class PromotionState {
        volatile StrengthBand lastBand = StrengthBand.SPECULATIVE;
        volatile String promotionStatus = "NONE";
        final AtomicInteger corroborationCount = new AtomicInteger(0);
        /** Cumulative positive evidence weight (sum of sourceTrust across corroborations). */
        volatile double evidencePos = 0.0;
        /** Cumulative negative evidence weight (contradiction-sourced). */
        volatile double evidenceNeg = 0.0;
    }

    /**
     * Two-level map: factSheetId → (atomKey → PromotionState).
     * ConcurrentHashMap at both levels for lock-free reads.
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, PromotionState>> stateMap =
            new ConcurrentHashMap<>();

    // ── Spring collaborators ─────────────────────────────────────────────────────

    @Nullable
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional JPA repository injected via Spring field injection.
     *
     * <p>When non-null (production JPA path), {@link #checkPromotion} durably persists the
     * updated band, promotionStatus, and corroborationCount on the latest fact row, and
     * {@link #hydrateFromDb(long)} loads prior corroboration state from the DB on first access
     * so counts survive a restart.</p>
     *
     * <p>When null (plain-Java / no-Spring test contexts), the tracker operates in purely
     * in-memory mode: existing behaviour is preserved and no DB calls are made.</p>
     */
    @Nullable
    @Autowired(required = false)
    InferredFactRowRepository factRepo;

    /**
     * Track which fact sheets have already been hydrated from the DB to avoid repeated queries.
     */
    private final ConcurrentHashMap<Long, Boolean> hydratedSheets = new ConcurrentHashMap<>();

    /**
     * Per-factSheet audit logs (keyed by factSheetId).
     * Lazily created using dataDir (if available) or in-memory fallback.
     */
    private final ConcurrentHashMap<Long, FileBackedAuditLog> auditLogs = new ConcurrentHashMap<>();

    @Nullable
    @Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    /**
     * Kompile-managed KB config — supplies the Beta prior strength W ({@code evidencePriorStrength}).
     * Null in plain-Java contexts → falls back to {@link KbConfig#defaults()}.
     */
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    /**
     * Primary Spring constructor.
     */
    @Autowired
    public FactPromotionTracker(@Nullable ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * No-arg constructor for plain-Java test contexts (no Spring, no event publisher).
     * The {@code factRepo} field remains null and the tracker operates in-memory only.
     */
    public FactPromotionTracker() {
        this.eventPublisher = null;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Check whether this observation promotes the fact to a higher StrengthBand,
     * and if so emit the promotion event and audit record.
     *
     * <p>This overload uses a default sourceTrust of 0.6 (LLM_EXTRACTION default).
     * Prefer {@link #checkPromotion(long, String, double, double, String, double)} when the
     * source trust is known.</p>
     *
     * @param factSheetId the fact sheet owning the atom
     * @param atomKey     the atom key
     * @param oldValue    the previous soft-truth value (NaN if no prior observation)
     * @param newValue    the newly observed soft-truth value
     * @param runId       the cascade run ID (for traceability)
     */
    public void checkPromotion(long factSheetId, String atomKey,
                                double oldValue, double newValue, String runId) {
        // Scalar-band path: derive the band directly from the newValue scalar (zero-uncertainty
        // projection) rather than via Beta-distribution evidence accumulation.  This mirrors the
        // original intent documented in the class javadoc ("Determines the new band from the new
        // value via StrengthBand.fromScalar(double)") and is what the plain-Java durability tests
        // verify.  The 6-arg overload (explicit sourceTrust) is the Beta-evidence path.
        ensureHydrated(factSheetId);

        PromotionState state = getOrCreateState(factSheetId, atomKey);

        StrengthBand oldBand = Double.isNaN(oldValue)
                ? StrengthBand.SPECULATIVE
                : state.lastBand;

        StrengthBand newBand = Double.isNaN(newValue)
                ? StrengthBand.SPECULATIVE
                : StrengthBand.fromScalar(newValue);

        int count = state.corroborationCount.incrementAndGet();

        if (newBand.ordinal() < oldBand.ordinal()) {
            log.debug("FactPromotionTracker: fact '{}' in sheet {} promoted {} → {} (scalar path, count={})",
                    atomKey, factSheetId, oldBand, newBand, count);

            state.lastBand = newBand;
            state.promotionStatus = "PROMOTED";

            if (eventPublisher != null) {
                try {
                    eventPublisher.publishEvent(
                            new FactPromotedEvent(this, factSheetId, atomKey, oldBand, newBand, runId, count));
                } catch (Exception e) {
                    log.warn("FactPromotionTracker: failed to publish FactPromotedEvent — {}", e.getMessage());
                }
            }

            try {
                FactAuditEvent auditEvent = FactAuditEvent.promoted(
                        atomKey, oldValue, newValue, oldBand.name(), newBand.name(), runId, null);
                getAuditLog(factSheetId).append(auditEvent);
            } catch (Exception e) {
                log.warn("FactPromotionTracker: failed to append PROMOTED audit event — {}", e.getMessage());
            }
        } else {
            if (newBand.ordinal() < state.lastBand.ordinal()) {
                state.lastBand = newBand;
            }
        }

        if (factRepo != null) {
            try {
                factRepo.updateBandAndPromotion(
                        factSheetId, atomKey,
                        state.lastBand.name(),
                        state.promotionStatus,
                        count);
            } catch (Exception e) {
                log.warn("FactPromotionTracker: could not persist band/promotion for '{}' sheet={} — {}",
                        atomKey, factSheetId, e.getMessage());
            }
        }
    }

    /**
     * Check whether this observation promotes the fact to a higher StrengthBand.
     *
     * <p>In addition to the legacy scalar-based band comparison, this method accumulates
     * Beta-distribution evidence: each call adds {@code sourceTrust} to
     * {@code state.evidencePos}, then computes
     * {@code Opinion.fromBetaEvidence(evidencePos, evidenceNeg, 0.5, priorStrength)}
     * and derives the band from the full Opinion (which respects the uncertainty gate —
     * a single observation with high u can NEVER be ESTABLISHED regardless of expectation).</p>
     *
     * @param factSheetId the fact sheet owning the atom
     * @param atomKey     the atom key
     * @param oldValue    the previous soft-truth value (NaN if no prior observation)
     * @param newValue    the newly observed soft-truth value
     * @param runId       the cascade run ID (for traceability)
     * @param sourceTrust the trust scalar of the source providing this observation (0..1)
     */
    public void checkPromotion(long factSheetId, String atomKey,
                                double oldValue, double newValue, String runId,
                                double sourceTrust) {
        // Hydrate corroboration state from DB on first access for this fact sheet
        // (makes counts durable across restarts when a JPA repo is available).
        ensureHydrated(factSheetId);

        PromotionState state = getOrCreateState(factSheetId, atomKey);

        StrengthBand oldBand = Double.isNaN(oldValue)
                ? StrengthBand.SPECULATIVE
                : state.lastBand;  // use accumulated band from Beta evidence, not raw scalar

        // Accumulate positive evidence — each corroboration adds sourceTrust (not 1.0)
        state.evidencePos += sourceTrust;
        int count = state.corroborationCount.incrementAndGet();

        // Compute band from Beta-distribution Opinion (respects uncertainty gate)
        Opinion opinion = Opinion.fromBetaEvidence(state.evidencePos, state.evidenceNeg, 0.5,
                kbCfg().getEvidencePriorStrength());
        StrengthBand newBand = StrengthBand.from(opinion);

        // Promotion: new band is strictly higher tier (lower ordinal = higher tier in the enum)
        if (newBand.ordinal() < oldBand.ordinal()) {
            log.debug("FactPromotionTracker: fact '{}' in sheet {} promoted {} → {} (corroboration={}, evidencePos={})",
                    atomKey, factSheetId, oldBand, newBand, count, state.evidencePos);

            state.lastBand = newBand;
            state.promotionStatus = "PROMOTED";

            // Publish Spring event
            if (eventPublisher != null) {
                try {
                    eventPublisher.publishEvent(
                            new FactPromotedEvent(this, factSheetId, atomKey, oldBand, newBand, runId, count));
                } catch (Exception e) {
                    log.warn("FactPromotionTracker: failed to publish FactPromotedEvent — {}", e.getMessage());
                }
            }

            // Append PROMOTED audit event
            try {
                FactAuditEvent auditEvent = FactAuditEvent.promoted(
                        atomKey, oldValue, newValue, oldBand.name(), newBand.name(), runId, null);
                getAuditLog(factSheetId).append(auditEvent);
            } catch (Exception e) {
                log.warn("FactPromotionTracker: failed to append PROMOTED audit event — {}", e.getMessage());
            }
        } else {
            // No promotion but still update last-known band if it improved
            if (newBand.ordinal() < state.lastBand.ordinal()) {
                state.lastBand = newBand;
            }
        }

        // Persist the updated band, promotionStatus, corroborationCount, and evidence accumulators
        // to the DB so they survive a restart (L2 durability gap closed).
        if (factRepo != null) {
            try {
                // Prefer the evidence-aware update (slice 1); fall back to legacy update if method unavailable
                try {
                    factRepo.updateBandPromotionAndEvidence(
                            factSheetId, atomKey,
                            state.lastBand.name(),
                            state.promotionStatus,
                            count,
                            state.evidencePos,
                            state.evidenceNeg);
                } catch (Exception ex) {
                    // Fall back to legacy update without evidence columns
                    factRepo.updateBandAndPromotion(
                            factSheetId, atomKey,
                            state.lastBand.name(),
                            state.promotionStatus,
                            count);
                }
            } catch (Exception e) {
                log.warn("FactPromotionTracker: could not persist band/promotion for '{}' sheet={} — {}",
                        atomKey, factSheetId, e.getMessage());
            }
        }
    }

    /**
     * Return all latest inferred facts from {@code store} that map to the given
     * {@code band} via {@link StrengthBand#fromScalar(double)} on their confidence value.
     *
     * <p>This overload operates against the in-memory store (read-time band computation).
     * For a durable DB-backed query against the persisted {@code band} column, use
     * {@link #factsByTierDurable(long, StrengthBand)} when a repo is available.</p>
     *
     * @param factSheetId the fact sheet (used for logging only; the store is already scoped)
     * @param store       the inferred fact store to query
     * @param band        the target tier
     * @return facts at the given tier, unordered
     */
    public List<InferredFact> factsByTier(long factSheetId, InferredFactStore store, StrengthBand band) {
        return store.allLatest().stream()
                .filter(f -> StrengthBand.fromScalar(f.confidence()) == band)
                .toList();
    }

    /**
     * Durable variant of {@link #factsByTier}: queries the persisted {@code band} column in the
     * DB for the given fact sheet rather than recomputing the band from confidence at read time.
     *
     * <p>Returns an empty list if no JPA repository is wired (plain-Java test contexts).</p>
     *
     * @param factSheetId the fact sheet to query
     * @param band        the target tier
     * @return facts persisted at that tier, unordered
     */
    public List<InferredFact> factsByTierDurable(long factSheetId, StrengthBand band) {
        if (factRepo == null) {
            return List.of();
        }
        try {
            return factRepo.findLatestByFactSheetIdAndBand(factSheetId, band.name())
                    .stream()
                    .map(r -> InferredFact.fromJson(r.getProvenanceJson()))
                    .toList();
        } catch (Exception e) {
            log.warn("FactPromotionTracker: factsByTierDurable query failed for sheet={} band={} — {}",
                    factSheetId, band, e.getMessage());
            return List.of();
        }
    }

    /**
     * Return the promotion status for a specific (factSheetId, atomKey) pair.
     * Returns "NONE" if the atom has never been promoted.
     */
    public String getPromotionStatus(long factSheetId, String atomKey) {
        ConcurrentHashMap<String, PromotionState> sheetMap = stateMap.get(factSheetId);
        if (sheetMap == null) return "NONE";
        PromotionState state = sheetMap.get(atomKey);
        return state == null ? "NONE" : state.promotionStatus;
    }

    /**
     * Return the corroboration count for a specific (factSheetId, atomKey) pair.
     * Returns 0 if the atom has never been seen.
     */
    public int getCorroborationCount(long factSheetId, String atomKey) {
        ConcurrentHashMap<String, PromotionState> sheetMap = stateMap.get(factSheetId);
        if (sheetMap == null) return 0;
        PromotionState state = sheetMap.get(atomKey);
        return state == null ? 0 : state.corroborationCount.get();
    }

    /**
     * Return the last-known StrengthBand for the atom, or SPECULATIVE if unknown.
     */
    public StrengthBand getLastBand(long factSheetId, String atomKey) {
        ConcurrentHashMap<String, PromotionState> sheetMap = stateMap.get(factSheetId);
        if (sheetMap == null) return StrengthBand.SPECULATIVE;
        PromotionState state = sheetMap.get(atomKey);
        return state == null ? StrengthBand.SPECULATIVE : state.lastBand;
    }

    /**
     * Return the count of atoms at each {@link StrengthBand} for the given fact sheet,
     * based on the in-memory {@code lastBand} field (no DB call).
     *
     * <p>Used by {@link ai.kompile.crawl.graph.GraphHydrationOrchestrator} to populate
     * the per-band breakdown in {@code LearningMetrics} after a derivation run. The map
     * always contains an entry for every band; unknown (null) atoms count toward
     * {@link StrengthBand#SPECULATIVE}.</p>
     *
     * <p>If no promotion state exists yet for the fact sheet (e.g. first cascade), an empty
     * map with zero counts for every band is returned. Thread-safe — iterates a snapshot of
     * the inner map's values.</p>
     *
     * @param factSheetId the fact sheet to aggregate
     * @return immutable map of band → atom count; all five {@link StrengthBand} values present
     */
    public Map<StrengthBand, Integer> bandCounts(long factSheetId) {
        Map<StrengthBand, Integer> counts = new EnumMap<>(StrengthBand.class);
        for (StrengthBand b : StrengthBand.values()) counts.put(b, 0);
        ConcurrentHashMap<String, PromotionState> sheetMap = stateMap.get(factSheetId);
        if (sheetMap != null) {
            for (PromotionState state : sheetMap.values()) {
                counts.merge(state.lastBand, 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Return the total number of corroboration events accumulated across all atoms in the
     * given fact sheet.  Each call to {@link #checkPromotion} increments the count for
     * the affected atom; this method sums across the sheet.
     *
     * <p>Used by {@code LearningMetrics} to surface a coarse "evidence accumulated" counter
     * without requiring per-atom iteration by the monitoring layer.</p>
     *
     * @param factSheetId the fact sheet to aggregate
     * @return total corroboration events seen in this session (since last restart or hydration)
     */
    public int totalCorroboration(long factSheetId) {
        ConcurrentHashMap<String, PromotionState> sheetMap = stateMap.get(factSheetId);
        if (sheetMap == null) return 0;
        int total = 0;
        for (PromotionState state : sheetMap.values()) {
            total += state.corroborationCount.get();
        }
        return total;
    }

    /**
     * Return the count of atoms currently marked as {@code "PROMOTED"} for the given fact sheet.
     *
     * <p>An atom is PROMOTED when at least one {@link #checkPromotion} call caused its band
     * to advance to a higher tier (lower ordinal) than it held previously.</p>
     *
     * @param factSheetId the fact sheet to aggregate
     * @return number of promoted atoms tracked in-memory for this fact sheet
     */
    public int promotedAtomCount(long factSheetId) {
        ConcurrentHashMap<String, PromotionState> sheetMap = stateMap.get(factSheetId);
        if (sheetMap == null) return 0;
        int count = 0;
        for (PromotionState state : sheetMap.values()) {
            if ("PROMOTED".equals(state.promotionStatus)) count++;
        }
        return count;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    private PromotionState getOrCreateState(long factSheetId, String atomKey) {
        ConcurrentHashMap<String, PromotionState> sheetMap =
                stateMap.computeIfAbsent(factSheetId, id -> new ConcurrentHashMap<>());
        return sheetMap.computeIfAbsent(atomKey, k -> new PromotionState());
    }

    /**
     * Hydrate corroboration counts, bands, and promotionStatus from the DB for the given
     * fact sheet on first access.  Subsequent calls for the same fact sheet are no-ops
     * (guarded by {@link #hydratedSheets}).
     *
     * <p>This is what makes corroboration counts durable across restarts: when the tracker
     * is re-constructed (e.g. after a JVM restart), the first {@link #checkPromotion} call
     * for a given fact sheet triggers a DB read that seeds the in-memory map with the
     * counts that were persisted in the previous session.</p>
     */
    private void ensureHydrated(long factSheetId) {
        if (factRepo == null) return;
        // computeIfAbsent returns null if key was absent → we only call hydrateFromDb once
        hydratedSheets.computeIfAbsent(factSheetId, id -> {
            hydrateFromDb(id);
            return Boolean.TRUE;
        });
    }

    /**
     * Load corroboration count, band, and promotionStatus for all atom keys in the given
     * fact sheet from the DB into the in-memory stateMap.  Only called once per fact sheet
     * (guarded by {@link #hydratedSheets}).
     */
    private void hydrateFromDb(long factSheetId) {
        try {
            List<InferredFactRow> rows = factRepo.findLatestWithCorroborationByFactSheetId(factSheetId);
            for (InferredFactRow row : rows) {
                PromotionState state = getOrCreateState(factSheetId, row.getAtomKey());
                state.corroborationCount.set(row.getCorroborationCount());
                if (row.getBand() != null) {
                    try {
                        state.lastBand = StrengthBand.valueOf(row.getBand());
                    } catch (IllegalArgumentException ignored) {
                        // Unknown band name in DB — keep default SPECULATIVE
                    }
                }
                if (row.getPromotionStatus() != null) {
                    state.promotionStatus = row.getPromotionStatus();
                }
                // Restore Beta-distribution accumulators (added in slice 1)
                if (row.getEvidencePos() != null) {
                    state.evidencePos = row.getEvidencePos();
                }
                if (row.getEvidenceNeg() != null) {
                    state.evidenceNeg = row.getEvidenceNeg();
                }
            }
            if (!rows.isEmpty()) {
                log.debug("FactPromotionTracker: hydrated corroboration state for {} atoms in factSheet={}",
                        rows.size(), factSheetId);
            }
        } catch (Exception e) {
            log.warn("FactPromotionTracker: DB hydration failed for factSheet={} — in-memory only: {}",
                    factSheetId, e.getMessage());
        }
    }

    FileBackedAuditLog getAuditLog(long factSheetId) {
        return auditLogs.computeIfAbsent(factSheetId, id -> {
            if (dataDir != null && !dataDir.isBlank()) {
                return new FileBackedAuditLog(Path.of(dataDir), id);
            }
            return new FileBackedAuditLog(id);
        });
    }
}
