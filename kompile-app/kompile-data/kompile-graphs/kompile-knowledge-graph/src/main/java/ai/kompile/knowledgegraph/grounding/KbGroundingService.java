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
package ai.kompile.knowledgegraph.grounding;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.claims.ClaimDossier;
import ai.kompile.graph.reasoning.claims.DossierBuilder;
import ai.kompile.graph.reasoning.fol.grounding.ConcurrentFactStore;
import ai.kompile.graph.reasoning.fol.grounding.DeepWhyNot;
import ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.fol.grounding.WhyNotExplainer;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.tms.BeliefReviser;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.knowledgegraph.persistence.dual.DualStoreGroundingFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

/**
 * L2 Spring service that surfaces the infra-free {@code kompile-graph-reasoning} grounding
 * primitives as a per-fact-sheet KB grounding service.
 *
 * <p>This is the sole Spring {@code @Service} in the grounding layer. It holds a
 * {@link ConcurrentHashMap} keyed by {@code factSheetId} containing a
 * {@link FactSheetKbState} per fact sheet, created lazily on first access.</p>
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li>{@link #verify}, {@link #query}, {@link #explain} — acquire the per-fact-sheet
 *       read lock (parallel reads).</li>
 *   <li>{@link #assertFact} — acquires the write lock for the
 *       {@link ConcurrentFactStore#assertFact} + contradiction-check window; releases
 *       the lock before any downstream cascade.</li>
 * </ul>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li>Does NOT modify {@code kompile-graph-reasoning} (infra-free lib).</li>
 *   <li>Does NOT use inline FQCNs or stub implementations.</li>
 *   <li>Uses the lib primitives directly: {@link DefaultKbVerifier},
 *       {@link ConjunctiveQueryEngine}, {@link DerivationTree},
 *       {@link ContradictionDetector}.</li>
 * </ul>
 */
@Service
@Slf4j
public class KbGroundingService {

    // ── Per-factSheet state map ──────────────────────────────────────────────────

    private final ConcurrentHashMap<Long, FactSheetKbState> stateMap = new ConcurrentHashMap<>();

    /**
     * Per-factSheet stale flag: true when a mutation has been observed but the re-ground
     * cascade has not yet completed. Set by {@link #markStale}, cleared by {@link #clearStale}.
     * Read by the REST controller to surface {@code stale=true} in verify/explain responses.
     */
    private final ConcurrentHashMap<Long, AtomicBoolean> staleFlags = new ConcurrentHashMap<>();

    /**
     * Per-factSheet epoch: the runId of the last completed grounding cascade.
     * Read by agent tools that want to know whether the KB has been updated since they last queried.
     */
    private final ConcurrentHashMap<Long, AtomicReference<String>> epochMap = new ConcurrentHashMap<>();

    // ── Spring event publisher (optional — null-safe if not wired in tests) ─────

    @Nullable
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional project data directory: when set, lazily-created {@link FactSheetKbState}
     * instances use a {@link FileBackedInferredFactStore} instead of the in-memory store,
     * giving durability across restarts (design spec §2.2).
     *
     * <p>Plain-Java test contexts omit this (null / blank) so the existing 17 tests are
     * unaffected — they continue to receive an in-memory store.</p>
     */
    @Nullable
    @Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    /**
     * Optional dual-store factory. When non-null, lazily-created {@link FactSheetKbState}
     * instances use a {@link ai.kompile.knowledgegraph.persistence.dual.DualStoreInferredFactStore}
     * (JPA-backed) as their inferred-fact store, taking precedence over the file-backed store.
     * Null in plain-Java test contexts.
     */
    @Nullable
    private final DualStoreGroundingFactory dualStoreFactory;

    /**
     * Full constructor: Spring injects the event publisher and optional dual-store factory.
     *
     * @param eventPublisher   the publisher to use; may be {@code null} in test contexts
     * @param dualStoreFactory the dual-store factory; may be {@code null} to fall back to
     *                         file-backed or in-memory storage
     */
    @Autowired
    public KbGroundingService(@Nullable ApplicationEventPublisher eventPublisher,
                               @Nullable DualStoreGroundingFactory dualStoreFactory) {
        this.eventPublisher = eventPublisher;
        this.dualStoreFactory = dualStoreFactory;
    }

    /**
     * Backward-compatible single-arg constructor for contexts that do not provide the
     * dual-store factory. Delegates to the full constructor with {@code dualStoreFactory=null}.
     *
     * @param eventPublisher the publisher to use; may be {@code null} in test contexts
     */
    public KbGroundingService(@Nullable ApplicationEventPublisher eventPublisher) {
        this(eventPublisher, null);
    }

    /**
     * No-arg constructor for plain-Java unit tests that do not need event publishing.
     * Spring will not pick this over the single-arg constructor when
     * {@link ApplicationEventPublisher} is available in the context.
     */
    public KbGroundingService() {
        this(null, null);
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Verify an atom key against the fact sheet's KB.
     *
     * <p>Fast-path O(1) lookup in the materialized {@link InferredFactStore};
     * falls back to the directly-observed {@link FactStore} when the inferred store
     * has no entry. No MAP re-inference is triggered here.</p>
     *
     * @param factSheetId the fact sheet to verify against (must not be null)
     * @param atomKey     the canonical atom key, e.g. {@code "isEmployedBy(Alice, Acme)"}
     * @return the verification result with verdict, confidence, and evidence
     */
    public VerifyResult verify(long factSheetId, String atomKey) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.readLock().lock();
        try {
            DefaultKbVerifier verifier = new DefaultKbVerifier(
                    state.inferredFactStore(),
                    state.factStore());
            return verifier.verify(atomKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Verify an atom with a custom confidence threshold.
     *
     * @param factSheetId the fact sheet id
     * @param atomKey     the atom key to verify
     * @param threshold   minimum confidence in [0,1] to report SUPPORTED
     * @return the verification result
     */
    public VerifyResult verify(long factSheetId, String atomKey, double threshold) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.readLock().lock();
        try {
            DefaultKbVerifier verifier = new DefaultKbVerifier(
                    state.inferredFactStore(),
                    state.factStore(),
                    threshold);
            return verifier.verify(atomKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Sparse-graph open-world assessor (optional). Turns an absent ({@code UNKNOWN}) fact into a
     * vacuous, base-rate-aware Opinion when the fact sheet's graph is structurally sparse. Null in
     * plain-Java unit tests — {@link #verifyOpinion} then falls back to a plain vacuous Opinion.
     */
    @Autowired(required = false)
    private ai.kompile.knowledgegraph.reasoning.SparseGraphAssessor sparseGraphAssessor;

    /**
     * Opinion-valued verification. {@code SUPPORTED}/{@code REFUTED} map to belief/disbelief; an
     * absent ({@code UNKNOWN}) fact resolves — in a structurally SPARSE graph — to a vacuous,
     * base-rate-aware open-world Opinion rather than a bare UNKNOWN (closed-world disbelief when the
     * graph is dense). This is the read-path consumer of the sparse-evidence model.
     *
     * @param baseRate domain prior that the absent proposition is true, in [0, 1]
     */
    public Opinion verifyOpinion(long factSheetId, String atomKey, double baseRate) {
        VerifyResult r = verify(factSheetId, atomKey);
        switch (r.status()) {
            case SUPPORTED:
                return Opinion.fromObservedValue(r.confidence());
            case REFUTED:
                return Opinion.fromObservedValue(Math.max(0.0, 1.0 - r.confidence()));
            case UNKNOWN:
            default:
                return sparseGraphAssessor != null
                        ? sparseGraphAssessor.absentFactOpinion(factSheetId, baseRate)
                        : Opinion.vacuous(baseRate);
        }
    }

    /**
     * Return the soft-truth value (0..1) for the given atom key from the inferred fact store,
     * or empty if no inference has been run for this atom yet.
     *
     * @param factSheetId the fact sheet to query
     * @param atomKey     the canonical atom key
     * @return an OptionalDouble containing the latest inferred soft-truth value, or empty
     */
    public OptionalDouble latestValue(long factSheetId, String atomKey) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.readLock().lock();
        try {
            return state.inferredFactStore().latest(atomKey)
                    .map(fact -> OptionalDouble.of(fact.value()))
                    .orElse(OptionalDouble.empty());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Execute a conjunctive pattern query against the fact sheet's materialized KB.
     *
     * <p>All conjuncts must be satisfied simultaneously. Variables use the {@code "?"} prefix
     * convention. Per-row confidence = min across matched atoms (Łukasiewicz T-norm).</p>
     *
     * @param factSheetId the fact sheet id
     * @param conjuncts   the ordered list of atom patterns
     * @param maxResults  maximum number of binding rows returned (0 = use engine default)
     * @return unmodifiable list of variable bindings, each with a confidence score
     */
    public List<QueryBinding> query(long factSheetId,
                                    List<ConjunctiveQueryEngine.AtomPattern> conjuncts,
                                    int maxResults) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.readLock().lock();
        try {
            int limit = maxResults > 0 ? maxResults : ConjunctiveQueryEngine.DEFAULT_MAX_RESULTS;
            return ConjunctiveQueryEngine.query(conjuncts, state.inferredFactStore(), limit);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Explain a derived fact by building its derivation tree.
     *
     * <p>Builds the proof tree up to {@code maxDepth} hops, using the fact sheet's
     * {@link ai.kompile.graph.reasoning.tms.JustificationIndex} to follow rule-body links
     * and the {@link InferredFactStore} for confidence values at each node.</p>
     *
     * @param factSheetId the fact sheet id
     * @param atomKey     the atom key to explain
     * @param maxDepth    maximum derivation hops (capped at
     *                    {@link DerivationTree#DEFAULT_MAX_DEPTH}; 0 = use default)
     * @return the derivation tree (leaf with confidence=0.0 if the atom is unknown)
     */
    public DerivationTree explain(long factSheetId, String atomKey, int maxDepth) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.readLock().lock();
        try {
            int depth = (maxDepth > 0)
                    ? Math.min(maxDepth, DerivationTree.DEFAULT_MAX_DEPTH)
                    : DerivationTree.DEFAULT_MAX_DEPTH;
            return DerivationTree.build(atomKey, state.inferredFactStore(),
                    state.justificationIndex(), depth);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Assert a fact into the fact sheet's observed store (MVCC path).
     *
     * <p>The assertion sequence:
     * <ol>
     *   <li>Acquire write lock.</li>
     *   <li>Call {@link ConcurrentFactStore#assertFact(Fact)} and obtain the new version.</li>
     *   <li>Sync the fact into the backing {@link FactStore} so that
     *       {@link DefaultKbVerifier} sees it on subsequent reads.</li>
     *   <li>Run {@link ContradictionDetector#findFactContradictions} to detect immediate
     *       hard-fact contradictions.</li>
     *   <li>Release write lock.</li>
     * </ol>
     * Log a warning if contradictions are detected but do not roll back (callers can
     * inspect the returned {@link AssertResult} for {@code contradictions}).</p>
     *
     * @param factSheetId the fact sheet id (must not be null)
     * @param fact        the fact to assert
     * @return the assert result containing the new store version and any detected contradictions
     */
    public AssertResult assertFact(long factSheetId, Fact fact) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        AssertResult result;
        try {
            // Assert into the MVCC store and obtain the new version
            long newVersion = state.concurrentFactStore().assertFact(fact);

            // Sync into backing FactStore so DefaultKbVerifier can look it up
            state.factStore().assertFact(fact);

            // Run contradiction detection on the full FactStore
            List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                    ContradictionDetector.findFactContradictions(state.factStore());

            if (!contradictions.isEmpty()) {
                log.warn("KB contradiction detected after asserting '{}' into factSheet {}: {} pair(s)",
                        fact.atomKey(), factSheetId, contradictions.size());
            }

            List<String> contradictionKeys = contradictions.stream()
                    .map(p -> p.first().atomKey() + " vs " + p.second().atomKey())
                    .collect(Collectors.toList());

            result = new AssertResult(newVersion, contradictionKeys);
        } finally {
            lock.writeLock().unlock();
        }

        // Publish AgentFactAssertedEvent AFTER releasing the write lock so the cascade
        // executor can re-acquire it for the re-ground without deadlocking.
        publishAgentAssertEvent(factSheetId, fact);
        return result;
    }

    /**
     * Assert a batch under one write lock and emit a single cascade trigger after all facts are
     * visible. The returned version is the MVCC version after the final assertion.
     */
    public AssertResult assertFactsBatch(long factSheetId, List<Fact> facts) {
        List<Fact> batch = facts == null
                ? List.of()
                : facts.stream().filter(java.util.Objects::nonNull).toList();
        if (batch.isEmpty()) {
            return new AssertResult(getState(factSheetId).concurrentFactStore().version(), List.of());
        }

        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        AssertResult result;
        lock.writeLock().lock();
        try {
            long version = state.concurrentFactStore().version();
            for (Fact fact : batch) {
                version = state.concurrentFactStore().assertFact(fact);
                state.factStore().assertFact(fact);
            }
            List<String> contradictionKeys =
                    ContradictionDetector.findFactContradictions(state.factStore()).stream()
                            .map(pair -> pair.first().atomKey() + " vs " + pair.second().atomKey())
                            .collect(Collectors.toList());
            result = new AssertResult(version, contradictionKeys);
        } finally {
            lock.writeLock().unlock();
        }

        // One event is sufficient: all batch members are visible before the cascade starts.
        publishAgentAssertEvent(factSheetId, batch.get(batch.size() - 1));
        return result;
    }

    /**
     * Optimistic-locking variant: asserts the fact only if the MVCC store has not been
     * modified since {@code expectedVersion}.
     *
     * @param factSheetId     the fact sheet id
     * @param fact            the fact to assert
     * @param expectedVersion version the caller observed before deciding to assert;
     *                        use {@code state.concurrentFactStore().version()} to read it
     * @return the result; if {@code version() == CONFLICT}, the fact was NOT written
     */
    public AssertResult assertFact(long factSheetId, Fact fact, long expectedVersion) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        AssertResult result;
        try {
            long newVersion = state.concurrentFactStore().assertFact(fact, expectedVersion);
            if (newVersion == ConcurrentFactStore.CONFLICT) {
                return AssertResult.conflict();
            }

            // Sync into backing FactStore
            state.factStore().assertFact(fact);

            List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                    ContradictionDetector.findFactContradictions(state.factStore());

            List<String> contradictionKeys = contradictions.stream()
                    .map(p -> p.first().atomKey() + " vs " + p.second().atomKey())
                    .collect(Collectors.toList());

            result = new AssertResult(newVersion, contradictionKeys);
        } finally {
            lock.writeLock().unlock();
        }

        // Publish AgentFactAssertedEvent AFTER releasing the write lock
        publishAgentAssertEvent(factSheetId, fact);
        return result;
    }

    /**
     * Retract an observed fact and purge inferred atoms whose only justification was that fact.
     */
    public RetractResult retractFact(long factSheetId, String atomKey) {
        if (atomKey == null || atomKey.isBlank()) {
            throw new IllegalArgumentException("atomKey must not be blank");
        }

        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        try {
            boolean found = state.factStore().factFor(atomKey).isPresent()
                    || state.concurrentFactStore().factFor(atomKey).isPresent();
            state.concurrentFactStore().retract(atomKey);
            BeliefRevisionResult revision = BeliefReviser.retractAndPurge(
                    atomKey,
                    state.factStore(),
                    state.justificationIndex(),
                    state.inferredFactStore());
            return new RetractResult(found, revision);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Synchronous consistency variant. Unsupported inferred atoms are purged before returning;
     * the normal graph-change cascade performs the next full MAP solve.
     */
    public RetractResult retractAndRevise(long factSheetId, String atomKey) {
        return retractFact(factSheetId, atomKey);
    }

    // ── State management ─────────────────────────────────────────────────────────

    /**
     * Get or lazily create the {@link FactSheetKbState} for the given fact sheet.
     *
     * <p>When {@code kompile.data.dir} is configured (production path), the inferred-fact
     * store is a {@link FileBackedInferredFactStore} that persists to JSONL on each write
     * and reloads on startup — satisfying the durability requirement (design spec §2.2).
     * In plain-Java test contexts (no data dir), it falls back to
     * {@link InMemoryInferredFactStore} so the existing 17 tests are unaffected.</p>
     *
     * @param factSheetId the fact sheet id (must not be null)
     * @return the existing or newly created state
     */
    public FactSheetKbState getState(long factSheetId) {
        return stateMap.computeIfAbsent(factSheetId, id -> {
            InferredFactStore inferredStore = createInferredFactStore(id);
            return FactSheetKbState.fresh(id, inferredStore);
        });
    }

    /**
     * Create the appropriate {@link InferredFactStore} for the given fact sheet id.
     *
     * <p>Priority order:
     * <ol>
     *   <li>If a {@link DualStoreGroundingFactory} is wired (production JPA path), use it.</li>
     *   <li>Else if {@code kompile.data.dir} is configured, use {@link FileBackedInferredFactStore}.</li>
     *   <li>Otherwise fall back to {@link InMemoryInferredFactStore}.</li>
     * </ol>
     */
    private InferredFactStore createInferredFactStore(long factSheetId) {
        if (dualStoreFactory != null) {
            try {
                return dualStoreFactory.factStoreFor(factSheetId);
            } catch (Exception e) {
                log.warn("KbGroundingService: could not create DualStoreInferredFactStore for factSheet={} — " +
                         "falling back to file-backed or in-memory store. Cause: {}", factSheetId, e.getMessage());
            }
        }
        if (dataDir != null && !dataDir.isBlank()) {
            try {
                return new FileBackedInferredFactStore(Path.of(dataDir), factSheetId);
            } catch (Exception e) {
                log.warn("KbGroundingService: could not create FileBackedInferredFactStore for factSheet={} — " +
                         "falling back to in-memory store. Cause: {}", factSheetId, e.getMessage());
            }
        }
        return new InMemoryInferredFactStore();
    }

    /**
     * Seed facts into the inferred store for the given fact sheet. Used in tests and
     * in the Phase 2 initialization path (loading persisted inferred facts).
     *
     * @param factSheetId the fact sheet id
     * @param facts       the inferred facts to seed
     */
    public void seedInferredFacts(long factSheetId, List<InferredFact> facts) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        try {
            for (InferredFact fact : facts) {
                state.inferredFactStore().store(fact);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── L3 Cascade support ────────────────────────────────────────────────────────

    /**
     * Mark the completion of a grounding cascade for {@code factSheetId}.
     *
     * <p>Called by {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
     * at STEP 8 of the cascade (§3.2 of the design). Updates the epoch reference and
     * replaces the {@link FactSheetKbState}'s justification index with the freshly-rebuilt
     * index from the MAP result. This is thread-safe: the epoch map uses an
     * {@link AtomicReference} per fact sheet and the state swap is guarded by the write lock
     * already held by the orchestrator when it calls this method.</p>
     *
     * @param factSheetId  the fact sheet whose grounding was refreshed
     * @param runId        the runId of the MAP inference run that just completed
     * @param newIndex     the freshly-built {@link JustificationIndex} from the MAP result
     */
    /**
     * Rules used by the last grounding cascade, converted to why-not normal form — the chaining
     * vocabulary for deep why-not completion search on UNKNOWN verdicts.
     */
    private final ConcurrentHashMap<Long, List<WhyNotExplainer.RuleNf>> groundingRulesMap =
            new ConcurrentHashMap<>();

    /**
     * {@link #markEpoch(long, String, JustificationIndex)} plus retention of the grounding rule
     * set: the rules that produced the materialized state are converted to why-not normal form
     * and kept per fact sheet so {@link #verifyEnriched} can run deep completion-set search for
     * UNKNOWN verdicts. Rules that cannot be normalized are skipped individually.
     *
     * @param factSheetId    the fact sheet whose grounding was refreshed
     * @param runId          the runId of the MAP inference run that just completed
     * @param newIndex       the freshly-built justification index from the MAP result
     * @param groundingRules the PSL rules the cascade ran with (null/empty clears nothing)
     */
    public void markEpoch(long factSheetId, String runId, JustificationIndex newIndex,
                          List<PslRule> groundingRules) {
        if (groundingRules != null && !groundingRules.isEmpty()) {
            List<WhyNotExplainer.RuleNf> normalized = new java.util.ArrayList<>();
            for (PslRule rule : groundingRules) {
                try {
                    normalized.add(WhyNotExplainer.fromPslRule(rule));
                } catch (Exception skipped) {
                    log.debug("markEpoch: rule not normalizable for why-not chaining: {}",
                            skipped.getMessage());
                }
            }
            if (!normalized.isEmpty()) {
                groundingRulesMap.put(factSheetId, List.copyOf(normalized));
            }
        }
        markEpoch(factSheetId, runId, newIndex);
    }

    public void markEpoch(long factSheetId, String runId, JustificationIndex newIndex) {
        // Update epoch reference
        epochMap.computeIfAbsent(factSheetId, id -> new AtomicReference<>(""))
                .set(runId);

        // Swap in the new justification index by replacing the state record.
        // The write lock is already held by the orchestrator's doReground() caller.
        stateMap.compute(factSheetId, (id, existing) -> {
            if (existing == null) {
                return null; // should not happen — state was created before the cascade
            }
            return new FactSheetKbState(
                    existing.factSheetId(),
                    existing.inferredFactStore(),
                    existing.concurrentFactStore(),
                    existing.factStore(),
                    newIndex,
                    existing.lock()
            );
        });

        log.debug("Epoch updated for factSheet={}: runId={}", factSheetId, runId);
    }

    /**
     * Return the runId of the last completed grounding cascade for this fact sheet,
     * or an empty string if no cascade has completed yet.
     *
     * @param factSheetId the fact sheet id
     * @return the epoch runId (never null; empty string if not yet set)
     */
    public String currentEpoch(long factSheetId) {
        AtomicReference<String> ref = epochMap.get(factSheetId);
        return (ref == null) ? "" : ref.get();
    }

    // ── Stale-flag API (used by GroundingCascadeHook and KbGroundingController) ───

    /**
     * Mark the grounding state for {@code factSheetId} as stale — i.e., a graph mutation has
     * been observed but the re-ground cascade has not yet completed. Safe to call from any thread.
     *
     * @param factSheetId the affected fact sheet
     */
    public void markStale(long factSheetId) {
        staleFlags.computeIfAbsent(factSheetId, id -> new AtomicBoolean(false)).set(true);
    }

    /**
     * Clear the stale flag for {@code factSheetId} — called by the cascade hook when a full
     * re-ground has successfully completed. Safe to call from any thread.
     *
     * @param factSheetId the affected fact sheet
     */
    public void clearStale(long factSheetId) {
        AtomicBoolean flag = staleFlags.get(factSheetId);
        if (flag != null) {
            flag.set(false);
        }
    }

    /**
     * Return {@code true} if the KB for {@code factSheetId} has been mutated since the last
     * completed re-ground. Returns {@code false} if no flag has been set (i.e., state is fresh
     * or no grounding has ever been triggered).
     *
     * @param factSheetId the fact sheet id
     * @return whether the grounding state is stale
     */
    public boolean isStale(long factSheetId) {
        AtomicBoolean flag = staleFlags.get(factSheetId);
        return flag != null && flag.get();
    }

    /**
     * Drop the cached {@link FactSheetKbState} for {@code factSheetId} so the next call to
     * {@link #getState} creates a fresh state. Used after a snapshot restore where the entire
     * graph has been replaced and the in-memory fact stores are no longer valid.
     *
     * <p>The stale flag is also cleared because the new graph has not been grounded yet — the
     * caller is expected to schedule a cascade immediately after calling this method.</p>
     *
     * @param factSheetId the fact sheet whose state should be evicted
     */
    public void resetState(long factSheetId) {
        stateMap.remove(factSheetId);
        clearStale(factSheetId);
        log.info("KbGroundingService: evicted cached KB state for factSheet={} (snapshot restore)", factSheetId);
    }

    /**
     * Permanently destroy all KB reasoning state for a deleted fact sheet: the cached
     * {@link FactSheetKbState} (fact stores, justification index), the grounding epoch, and the
     * stale flag. Unlike {@link #resetState}, no re-ground is expected afterwards — the sheet is
     * gone. Safe to call for sheets that never had state.
     *
     * @param factSheetId the deleted fact sheet; null is a no-op
     */
    public void destroyFactSheet(Long factSheetId) {
        if (factSheetId == null) {
            return;
        }
        stateMap.remove(factSheetId);
        epochMap.remove(factSheetId);
        groundingRulesMap.remove(factSheetId);
        clearStale(factSheetId);
        log.info("KbGroundingService: destroyed KB state for deleted factSheet={}", factSheetId);
    }

    /**
     * Assess a subject-predicate-object claim against the fact sheet's KB plus a reasoning
     * graph, producing the full evidence dossier (direct edges, paths, verifier verdicts, and
     * optional KGE/mined-rule signals when configured). Runs under the sheet's read lock.
     *
     * @param factSheetId the fact sheet scope
     * @param graph       reasoning graph for structural evidence (never null; may be empty)
     * @param subject     subject entity id
     * @param predicate   relation type
     * @param object      object entity id
     * @return the assembled dossier with fused score and supporting/refuting items
     */
    public ClaimDossier assessClaim(long factSheetId, ReasoningGraph graph,
                                    String subject, String predicate, String object) {
        FactSheetKbState state = getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.readLock().lock();
        try {
            return new DossierBuilder().assess(graph, subject, predicate, object,
                    state.factStore(), state.inferredFactStore(), null, null);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Enriched verification result: the plain {@link VerifyResult} plus the derived context the
     * verify API surfaces — near-miss suggestions, contradicting evidence, an open-world Opinion
     * for UNKNOWN verdicts, a structural fragility estimate for SUPPORTED verdicts, and an
     * optional deep why-not report.
     */
    public record EnrichedVerifyResult(
            VerifyResult result,
            List<String> nearMissSuggestions,
            List<String> contradictions,
            Opinion opinion,
            boolean openWorld,
            int derivationDepth,
            int evidenceCount,
            List<String> sourceProvenance,
            Double fragilityRobustness,
            List<String> fragilityWouldFlipIf,
            Integer fragilityMinimalSupportSize,
            DeepWhyNot.DeepWhyNotReport deepWhyNotReport) {

        public EnrichedVerifyResult {
            nearMissSuggestions = nearMissSuggestions == null
                    ? List.of() : List.copyOf(nearMissSuggestions);
            contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
            sourceProvenance = sourceProvenance == null
                    ? List.of() : List.copyOf(sourceProvenance);
            fragilityWouldFlipIf = fragilityWouldFlipIf == null
                    ? List.of() : List.copyOf(fragilityWouldFlipIf);
        }
    }

    /**
     * Verify an atom and enrich the verdict in one pass:
     * <ul>
     *   <li>near-miss suggestions and counter-evidence come from the verifier itself;</li>
     *   <li>UNKNOWN verdicts additionally carry an open-world {@link Opinion} (sparse-graph
     *       aware via {@link #verifyOpinion}) and — when the last grounding cascade registered
     *       its rule set via {@link #markEpoch(long, String, JustificationIndex, List)} — a deep
     *       why-not report with multi-hop completion sets;</li>
     *   <li>SUPPORTED verdicts carry counterfactual fragility computed on the justification
     *       index: {@code wouldFlipIf} lists the facts whose individual retraction leaves the
     *       atom fully unsupported ({@link JustificationIndex#solelyDependentOn}, rule-level
     *       exact), {@code minimalSupportSize} is the smallest contributing rule body (1 for a
     *       direct observation), and robustness is the fraction of the support universe that is
     *       NOT a single point of failure.</li>
     * </ul>
     *
     * @param factSheetId the fact sheet scope
     * @param atomKey     the canonical atom key
     * @param threshold   minimum confidence to report SUPPORTED; {@code <= 0} uses the default
     * @param baseRate    open-world prior for the UNKNOWN-verdict Opinion, in [0,1]
     */
    public EnrichedVerifyResult verifyEnriched(long factSheetId, String atomKey,
                                               double threshold, double baseRate) {
        VerifyResult result = threshold > 0.0
                ? verify(factSheetId, atomKey, threshold)
                : verify(factSheetId, atomKey);

        Opinion opinion = result.status() == VerifyResult.Status.UNKNOWN
                ? verifyOpinion(factSheetId, atomKey, baseRate)
                : null;

        // Deep why-not for UNKNOWN verdicts: chain over the grounding rule set retained from the
        // last cascade to find multi-hop completion sets that would derive the claim.
        DeepWhyNot.DeepWhyNotReport whyNotReport = null;
        if (result.status() == VerifyResult.Status.UNKNOWN) {
            List<WhyNotExplainer.RuleNf> rules =
                    groundingRulesMap.getOrDefault(factSheetId, List.of());
            if (!rules.isEmpty()) {
                FactSheetKbState state = getState(factSheetId);
                ReadWriteLock lock = state.lock();
                lock.readLock().lock();
                try {
                    whyNotReport = new DeepWhyNot(rules, state.inferredFactStore(),
                            state.factStore()).explainDeep(atomKey, 3);
                } catch (Exception e) {
                    log.debug("verifyEnriched: deep why-not failed for {}: {}",
                            atomKey, e.getMessage());
                } finally {
                    lock.readLock().unlock();
                }
            }
        }

        // Derivation context + counterfactual fragility for supported verdicts, computed on the
        // justification index under one read lock.
        int derivationDepth = 0;
        java.util.LinkedHashSet<String> provenance = new java.util.LinkedHashSet<>();
        Double robustness = null;
        List<String> wouldFlipIf = List.of();
        Integer minimalSupportSize = null;
        if (result.status() == VerifyResult.Status.SUPPORTED) {
            try {
                DerivationTree tree = explain(factSheetId, atomKey, DerivationTree.DEFAULT_MAX_DEPTH);
                if (tree != null) {
                    derivationDepth = treeHeight(tree);
                    collectProvenance(tree, provenance);
                }
            } catch (Exception e) {
                log.debug("verifyEnriched: derivation explain failed for {}: {}",
                        atomKey, e.getMessage());
            }
            FactSheetKbState state = getState(factSheetId);
            ReadWriteLock lock = state.lock();
            lock.readLock().lock();
            try {
                for (String evidenceAtom : result.evidence()) {
                    // Direct observations arrive as "observed:<sourceId>" evidence strings.
                    if (evidenceAtom.startsWith("observed:")) {
                        String source = evidenceAtom.substring("observed:".length()).trim();
                        if (!source.isBlank()) {
                            provenance.add(source);
                        }
                        continue;
                    }
                    state.factStore().factFor(evidenceAtom)
                            .map(Fact::sourceId)
                            .filter(source -> source != null && !source.isBlank())
                            .ifPresent(provenance::add);
                }

                JustificationIndex index = state.justificationIndex();
                boolean directlyObserved = state.factStore().factFor(atomKey).isPresent();
                java.util.Set<String> supportingFacts = index.supportingFacts(atomKey);
                List<java.util.Set<String>> ruleBodies = index.perRuleBodyFacts(atomKey);

                java.util.LinkedHashSet<String> critical = new java.util.LinkedHashSet<>();
                if (directlyObserved && ruleBodies.isEmpty()) {
                    // The observation itself is the only support: retracting it flips.
                    critical.add(atomKey);
                } else if (!directlyObserved) {
                    // Exact rule-level counterfactual: a fact flips the verdict iff every
                    // contributing rule body contains it.
                    for (String fact : supportingFacts) {
                        if (index.solelyDependentOn(fact).contains(atomKey)) {
                            critical.add(fact);
                        }
                    }
                }
                // directlyObserved && derivations exist: no single retraction flips — the
                // observation and the derivations back each other up.

                int supportUniverse = (directlyObserved ? 1 : 0) + supportingFacts.size();
                minimalSupportSize = directlyObserved ? 1
                        : ruleBodies.stream().mapToInt(java.util.Set::size).min()
                                .orElse(Math.max(1, supportingFacts.size()));
                wouldFlipIf = List.copyOf(critical);
                robustness = supportUniverse == 0
                        ? 0.0 : 1.0 - (double) critical.size() / supportUniverse;
            } finally {
                lock.readLock().unlock();
            }
        }

        return new EnrichedVerifyResult(result, result.nearMissSuggestions(),
                result.counterEvidence(), opinion, opinion != null,
                derivationDepth, result.evidence().size(), List.copyOf(provenance),
                robustness, wouldFlipIf, minimalSupportSize, whyNotReport);
    }

    /** Height of a derivation tree: a leaf derivation counts as depth 1. */
    private static int treeHeight(DerivationTree tree) {
        int childMax = 0;
        for (DerivationTree child : tree.children()) {
            childMax = Math.max(childMax, treeHeight(child));
        }
        return 1 + childMax;
    }

    /** Collect distinct non-blank source provenance identifiers from a derivation tree. */
    private static void collectProvenance(DerivationTree tree, java.util.Set<String> out) {
        if (tree.sourceProvenance() != null && !tree.sourceProvenance().isBlank()) {
            out.add(tree.sourceProvenance());
        }
        for (DerivationTree child : tree.children()) {
            collectProvenance(child, out);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * Publish an {@link AgentFactAssertedEvent} if an event publisher is wired.
     * Called after the write lock has been released to avoid a deadlock where
     * the cascade listener tries to re-acquire the same lock.
     */
    private void publishAgentAssertEvent(long factSheetId, Fact fact) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(
                        new AgentFactAssertedEvent(this, factSheetId,
                                fact.atomKey(), fact.value(), fact.sourceId()));
            } catch (Exception e) {
                log.warn("Failed to publish AgentFactAssertedEvent for factSheet={} atomKey={}: {}",
                        factSheetId, fact.atomKey(), e.getMessage());
            }
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────────

    /**
     * Result of an {@link #assertFact} operation.
     *
     * @param version         the new store version after the write ({@link ConcurrentFactStore#CONFLICT}
     *                        if the write was rejected)
     * @param contradictions  list of detected contradiction descriptions (empty if none)
     */
    /** Result of TMS-aware fact retraction. */
    public record RetractResult(boolean found, BeliefRevisionResult revision) {
        public RetractResult {
            if (revision == null) {
                throw new IllegalArgumentException("revision must not be null");
            }
        }
    }

    public record AssertResult(long version, List<String> contradictions) {

        public AssertResult {
            contradictions = (contradictions == null)
                    ? Collections.emptyList()
                    : List.copyOf(contradictions);
        }

        /** True if the assert was rejected due to an optimistic-concurrency conflict. */
        public boolean isConflict() {
            return version == ConcurrentFactStore.CONFLICT;
        }

        /** Convenience factory for a conflict result. */
        public static AssertResult conflict() {
            return new AssertResult(ConcurrentFactStore.CONFLICT, List.of());
        }
    }
}
