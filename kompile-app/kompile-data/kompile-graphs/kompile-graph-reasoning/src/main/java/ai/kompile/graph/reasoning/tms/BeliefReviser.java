/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Implements belief revision: retracting a fact from the store and identifying atoms
 * whose support is weakened or lost.
 *
 * <h3>Store mutation contract</h3>
 * <ul>
 *   <li>{@link #retract} mutates only the {@link FactStore} (removes the observed fact).
 *       It does NOT touch the {@link InferredFactStore}; callers that need stale inferred
 *       facts removed must use {@link #retractAndPurge} instead.</li>
 *   <li>{@link #retractAndPurge} additionally removes every {@link BeliefRevisionResult#unsupportedAtoms()}
 *       entry from the supplied {@link InferredFactStore}.  Weakened atoms (those that retain
 *       at least one alternative support) are intentionally NOT purged — their value is
 *       outdated but not necessarily zero, and re-inference (rather than deletion) is the
 *       correct update for them.  Purging them would turn a soft-truth downgrade into a
 *       spurious UNKNOWN verdict before re-inference runs.</li>
 *   <li>{@link #retractReviseAndPurge} additionally re-runs MAP inference after the purge
 *       and returns the new {@link HlMrfMapInference.Result} in a {@link RevisionOutcome},
 *       giving callers a consistent post-retraction belief state in one call.</li>
 * </ul>
 *
 * <h3>Trace integration note</h3>
 * <p>Revision events should eventually surface as REVISION trace steps once the
 * {@code explain/} package lands its new {@code StepKind} variants (a follow-up wave).
 * No {@code StepKind} reference is made here yet; the {@link BeliefRevisionResult#revisedAt}
 * timestamp records when each revision occurred so the trace wire-up can reconstruct ordering
 * after the fact.</p>
 *
 * <h3>Retract + re-run workflow</h3>
 * <ol>
 *   <li>Remove the fact from the {@link FactStore}.</li>
 *   <li>Consult the {@link JustificationIndex} to find atoms that depended on this fact.</li>
 *   <li>Identify atoms with no remaining support (sole dependents) — {@code unsupportedAtoms}.</li>
 *   <li>Identify atoms with reduced but non-zero support — {@code weakenedAtoms}.</li>
 *   <li>(Purge path) Remove every {@code unsupportedAtoms} key from the {@link InferredFactStore}.</li>
 *   <li>(Revise path) Re-apply remaining facts to the program and re-run MAP inference.</li>
 * </ol>
 */
public final class BeliefReviser {

    private static final Logger log = LoggerFactory.getLogger(BeliefReviser.class);

    private BeliefReviser() {}

    /**
     * The combined outcome of {@link #retractReviseAndPurge}: the full belief revision
     * analysis together with the freshly-computed MAP inference result.
     *
     * @param revision  the TMS dependency analysis (retracted key, unsupported/weakened atoms,
     *                  purged inferred facts, revised FactStore, revision timestamp)
     * @param newResult the MAP inference result produced after the retraction and purge;
     *                  reflects the updated soft-truth assignment over the remaining facts
     */
    public record RevisionOutcome(BeliefRevisionResult revision, HlMrfMapInference.Result newResult) {
        public RevisionOutcome {
            Objects.requireNonNull(revision, "revision must not be null");
            Objects.requireNonNull(newResult, "newResult must not be null");
        }
    }

    /**
     * Retract a fact from the store and return the belief revision result.
     * Uses the JustificationIndex to identify what becomes unsupported.
     *
     * <p>This is the <em>legacy</em> path: it mutates only the {@link FactStore}.
     * The {@link InferredFactStore} is left untouched, which means stale inferred
     * facts survive and {@link ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier}
     * may return {@code SUPPORTED} from them until re-inference runs.  If immediate
     * consistency is required, use {@link #retractAndPurge} instead.</p>
     *
     * <p>The returned {@link BeliefRevisionResult} has {@code purgedAtoms = List.of()}
     * (no inferred-fact removal happened).</p>
     *
     * @param factAtomKey  the atom key of the fact to retract
     * @param factStore    the current fact store (modified in place — fact is removed)
     * @param index        the justification index for the current inference result
     * @return the belief revision result describing what changed
     */
    public static BeliefRevisionResult retract(
            String factAtomKey, FactStore factStore, JustificationIndex index) {

        Objects.requireNonNull(factAtomKey, "factAtomKey must not be null");
        Objects.requireNonNull(factStore, "factStore must not be null");
        Objects.requireNonNull(index, "index must not be null");

        log.debug("BeliefReviser.retract: retracting '{}'", factAtomKey);

        // Remove the fact from the store
        factStore.retract(factAtomKey);

        // Identify unsupported atoms (only support was this fact)
        Set<String> unsupported = new LinkedHashSet<>(index.solelyDependentOn(factAtomKey));

        // Identify weakened atoms (one of multiple supports)
        Set<String> allDependent = new LinkedHashSet<>(index.atomsDependingOnFact(factAtomKey));
        Set<String> weakened = new LinkedHashSet<>(allDependent);
        weakened.removeAll(unsupported);

        log.debug("BeliefReviser.retract: unsupported={}, weakened={}", unsupported.size(), weakened.size());

        // Legacy path: no InferredFactStore involved — purgedAtoms is empty.
        return new BeliefRevisionResult(
                factAtomKey, unsupported, weakened, factStore, List.of(), Instant.now());
    }

    /**
     * Retract a fact and re-run inference to produce an updated state.
     *
     * <p>This operation:
     * <ol>
     *   <li>Retracts the fact from the store (via {@link #retract}).</li>
     *   <li>Re-applies the remaining facts to the program.</li>
     *   <li>Runs MAP inference on the updated program.</li>
     * </ol>
     * The returned {@link BeliefRevisionResult} uses the revised store (fact removed).
     * Note: the inference result itself is not captured in BeliefRevisionResult; callers
     * that need the new posteriors should call HlMrfMapInference.solve on the revised program.
     * The {@link InferredFactStore} is NOT purged; use {@link #retractReviseAndPurge} for that.</p>
     *
     * @param factAtomKey the atom key of the fact to retract
     * @param program     the PSL program template (atoms and rules, WITHOUT observations)
     * @param factStore   the current fact store (modified in place — fact is removed)
     * @param index       the justification index for the current inference result
     * @return the belief revision result with the revised store
     */
    public static BeliefRevisionResult retractAndRevise(
            String factAtomKey, PslProgram program, FactStore factStore, JustificationIndex index) {

        Objects.requireNonNull(program, "program must not be null");

        // Retract the fact (modifies factStore in place)
        BeliefRevisionResult result = retract(factAtomKey, factStore, index);

        // Re-apply remaining facts to the program and re-run inference
        try {
            result.revisedFactStore().applyToProgram(program);
            HlMrfMapInference.solve(program);
            log.debug("BeliefReviser.retractAndRevise: inference re-run complete after retracting '{}'",
                    factAtomKey);
        } catch (Exception e) {
            log.warn("BeliefReviser.retractAndRevise: inference failed after retraction: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * Retract a fact from the {@link FactStore} and purge its solely-dependent atoms from
     * the {@link InferredFactStore}, returning a complete revision result.
     *
     * <p>After this call:
     * <ul>
     *   <li>The retracted fact is absent from {@code factStore}.</li>
     *   <li>Every atom in {@link BeliefRevisionResult#unsupportedAtoms()} has been removed
     *       from {@code inferredStore} via {@link InferredFactStore#purge(String)}.  If the
     *       retracted fact's own key happens to be present in the inferred store it is also
     *       purged.</li>
     *   <li>Atoms in {@link BeliefRevisionResult#weakenedAtoms()} are intentionally left in
     *       place: they retain alternative support so their inferred value is stale but
     *       non-zero, and the correct update is a re-inference pass rather than deletion.
     *       Purging them would produce spurious UNKNOWN verdicts before re-inference runs.</li>
     *   <li>{@link BeliefRevisionResult#purgedAtoms()} lists every key that was actually
     *       removed from {@code inferredStore} during this call.</li>
     *   <li>A subsequent call to
     *       {@link ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier#verify(String)}
     *       for any purged atom will return {@code UNKNOWN} (not {@code SUPPORTED} from stale data).</li>
     * </ul>
     *
     * <p>Re-inference is NOT triggered here; to also refresh the belief state call
     * {@link #retractReviseAndPurge} instead.</p>
     *
     * @param factAtomKey   the atom key of the fact to retract
     * @param factStore     the current fact store (modified in place — fact is removed)
     * @param index         the justification index for the current inference result
     * @param inferredStore the inferred-fact store whose stale entries will be purged
     * @return the belief revision result, including the list of purged atom keys
     */
    public static BeliefRevisionResult retractAndPurge(
            String factAtomKey, FactStore factStore, JustificationIndex index,
            InferredFactStore inferredStore) {

        Objects.requireNonNull(inferredStore, "inferredStore must not be null");

        // Retract from the FactStore and compute unsupported/weakened sets.
        BeliefRevisionResult base = retract(factAtomKey, factStore, index);

        // Purge unsupported atoms from the InferredFactStore.
        // Weakened atoms are NOT purged — they retain other support; see class Javadoc.
        List<String> purged = new ArrayList<>(base.unsupportedAtoms().size() + 1);

        for (String atomKey : base.unsupportedAtoms()) {
            if (inferredStore.latest(atomKey).isPresent()) {
                inferredStore.purge(atomKey);
                purged.add(atomKey);
                log.debug("BeliefReviser.retractAndPurge: purged unsupported inferred atom '{}'", atomKey);
            }
        }
        // Also purge the retracted fact's own inferred entry if it was asserted as an inferred fact.
        if (!purged.contains(factAtomKey) && inferredStore.latest(factAtomKey).isPresent()) {
            inferredStore.purge(factAtomKey);
            purged.add(factAtomKey);
            log.debug("BeliefReviser.retractAndPurge: purged retracted atom's own inferred entry '{}'",
                    factAtomKey);
        }

        log.debug("BeliefReviser.retractAndPurge: purged {} inferred atoms after retracting '{}'",
                purged.size(), factAtomKey);

        return new BeliefRevisionResult(
                base.retractedFactKey(),
                base.unsupportedAtoms(),
                base.weakenedAtoms(),
                base.revisedFactStore(),
                List.copyOf(purged),
                base.revisedAt());
    }

    /**
     * Retract a fact, purge stale inferred atoms, and re-run MAP inference — returning both
     * the full revision analysis and the fresh inference result.
     *
     * <p>This is the highest-level belief-revision primitive:
     * <ol>
     *   <li>Delegates to {@link #retractAndPurge} (fact removed from {@link FactStore};
     *       solely-dependent atoms removed from {@link InferredFactStore}).</li>
     *   <li>Re-applies the remaining facts to {@code program} and runs
     *       {@link HlMrfMapInference#solve(PslProgram)}.</li>
     *   <li>Returns both results in a {@link RevisionOutcome} so callers have a fully
     *       consistent post-retraction belief state.</li>
     * </ol>
     *
     * <p>The caller does NOT need to issue any additional re-inference call; everything is
     * handled here.</p>
     *
     * @param factAtomKey   the atom key of the fact to retract
     * @param program       the PSL program template (atoms and rules, WITHOUT observations;
     *                      will be mutated by {@link FactStore#applyToProgram(PslProgram)})
     * @param factStore     the current fact store (modified in place — fact is removed)
     * @param index         the justification index for the current inference result
     * @param inferredStore the inferred-fact store whose stale entries will be purged
     * @return a {@link RevisionOutcome} containing the revision analysis and the new
     *         MAP inference result
     * @throws RuntimeException (wrapped) if inference fails after retraction; callers should
     *         handle this gracefully (the purge has already happened at that point)
     */
    public static RevisionOutcome retractReviseAndPurge(
            String factAtomKey, PslProgram program, FactStore factStore,
            JustificationIndex index, InferredFactStore inferredStore) {

        Objects.requireNonNull(program, "program must not be null");

        // Step 1: retract + purge stale inferred atoms.
        BeliefRevisionResult revision = retractAndPurge(factAtomKey, factStore, index, inferredStore);

        // Step 2: clear the retracted atom's observed registration so the solver treats it as
        // a target (unknown) rather than fixed evidence, then re-apply remaining facts and re-solve.
        // Without clearObserved(), applyToProgram only re-observes the remaining facts but the
        // stale observation for the retracted atom persists, causing the MAP solution to remain
        // anchored at the old value.
        program.clearObserved(factAtomKey);
        revision.revisedFactStore().applyToProgram(program);
        HlMrfMapInference.Result newResult = HlMrfMapInference.solve(program);

        log.debug("BeliefReviser.retractReviseAndPurge: inference re-run complete after retracting '{}'; "
                + "purged={}, converged={}", factAtomKey, revision.purgedAtoms().size(), newResult.converged());

        return new RevisionOutcome(revision, newResult);
    }
}
