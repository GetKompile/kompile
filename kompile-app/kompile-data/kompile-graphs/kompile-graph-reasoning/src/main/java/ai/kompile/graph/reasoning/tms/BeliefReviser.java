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
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Implements belief revision: retracting a fact from the store and identifying atoms
 * whose support is weakened or lost.
 *
 * <p>The retract + re-run workflow:
 * <ol>
 *   <li>Remove the fact from the store.</li>
 *   <li>Consult the {@link JustificationIndex} to find atoms that depended on this fact.</li>
 *   <li>Identify atoms with no remaining support (sole dependents).</li>
 *   <li>Optionally: re-run inference on a fresh program snapshot to update beliefs.</li>
 * </ol>
 */
public final class BeliefReviser {

    private static final Logger log = LoggerFactory.getLogger(BeliefReviser.class);

    private BeliefReviser() {}

    /**
     * Retract a fact from the store and return the belief revision result.
     * Uses the JustificationIndex to identify what becomes unsupported.
     *
     * <p>This operation creates a copy of the FactStore with the fact removed.</p>
     *
     * @param factAtomKey  the atom key of the fact to retract
     * @param factStore    the current fact store (will be modified in place)
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

        return new BeliefRevisionResult(factAtomKey, unsupported, weakened, factStore);
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
     * that need the new posteriors should call HlMrfMapInference.solve on the revised program.</p>
     *
     * @param factAtomKey the atom key of the fact to retract
     * @param program     the PSL program template (atoms and rules, WITHOUT observations)
     * @param factStore   the current fact store (modified in place -- fact is removed)
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
}
