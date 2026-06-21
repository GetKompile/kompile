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
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Links each inferred atom to the rules and facts that support it, enabling truth
 * maintenance (belief revision after fact retraction).
 *
 * <p>The index is built from an {@link HlMrfMapInference.Result} and {@link FactStore}.
 * A ground rule is considered "contributing" if its distance to satisfaction is below
 * a threshold (i.e., it is nearly satisfied by the MAP solution).</p>
 */
public class JustificationIndex {

    private static final double CONTRIBUTION_THRESHOLD = 0.1;

    // atom key -> list of supporting rule display strings
    private final Map<String, List<String>> atomToRules = new LinkedHashMap<>();

    // atom key -> set of observed fact keys that appear in the rule body
    private final Map<String, Set<String>> atomToFacts = new LinkedHashMap<>();

    // fact key -> set of atoms that depend on this fact
    private final Map<String, Set<String>> factToAtoms = new LinkedHashMap<>();

    private JustificationIndex() {}

    /**
     * Build a justification index from inference results and the fact store.
     *
     * @param result    the MAP inference result
     * @param factStore the fact store used during inference
     * @return the populated justification index
     */
    public static JustificationIndex build(HlMrfMapInference.Result result, FactStore factStore) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(factStore, "factStore must not be null");

        JustificationIndex index = new JustificationIndex();
        Map<String, Double> values = result.values();

        for (GroundRule gr : result.groundRules()) {
            double dist = gr.distanceToSatisfaction(values);
            if (dist >= CONTRIBUTION_THRESHOLD) continue; // rule is not contributing

            // For each head atom, record this rule as supporting
            for (GroundRule.Lit headLit : gr.head()) {
                String atomKey = headLit.atomKey();

                index.atomToRules.computeIfAbsent(atomKey, k -> new ArrayList<>())
                        .add(gr.display());

                // Collect observed fact keys from the body
                Set<String> factKeys = index.atomToFacts
                        .computeIfAbsent(atomKey, k -> new LinkedHashSet<>());
                for (GroundRule.Lit bodyLit : gr.body()) {
                    String bodyKey = bodyLit.atomKey();
                    if (factStore.factFor(bodyKey).isPresent()) {
                        factKeys.add(bodyKey);
                        // Also record the reverse mapping
                        index.factToAtoms.computeIfAbsent(bodyKey, k -> new LinkedHashSet<>())
                                .add(atomKey);
                    }
                }
            }
        }

        return index;
    }

    /**
     * Get the supporting rules (display strings) for an atom key.
     *
     * @param atomKey the atom to look up
     * @return unmodifiable list of supporting rule display strings
     */
    public List<String> supportingRules(String atomKey) {
        return Collections.unmodifiableList(atomToRules.getOrDefault(atomKey, List.of()));
    }

    /**
     * Get the observed fact keys that supported an atom.
     *
     * @param atomKey the atom to look up
     * @return unmodifiable set of supporting fact atom keys
     */
    public Set<String> supportingFacts(String atomKey) {
        return Collections.unmodifiableSet(atomToFacts.getOrDefault(atomKey, Set.of()));
    }

    /**
     * Get all atoms that depended on a given fact (for retraction propagation).
     *
     * @param factAtomKey the fact's atom key
     * @return unmodifiable set of dependent atom keys
     */
    public Set<String> atomsDependingOnFact(String factAtomKey) {
        return Collections.unmodifiableSet(factToAtoms.getOrDefault(factAtomKey, Set.of()));
    }

    /**
     * Atoms whose ONLY support was the given fact key (fully unsupported after retraction).
     *
     * <p>An atom is solely dependent on a fact if:
     * <ol>
     *   <li>It depends on the fact (appears in {@link #atomsDependingOnFact}), AND</li>
     *   <li>All of its supporting facts include ONLY this one fact (no alternative support).</li>
     * </ol>
     * </p>
     *
     * @param factAtomKey the fact's atom key
     * @return set of atom keys that will become fully unsupported when this fact is retracted
     */
    public Set<String> solelyDependentOn(String factAtomKey) {
        Set<String> dependent = atomsDependingOnFact(factAtomKey);
        Set<String> solely = new LinkedHashSet<>();
        for (String atomKey : dependent) {
            Set<String> allSupportFacts = atomToFacts.getOrDefault(atomKey, Set.of());
            if (allSupportFacts.size() == 1 && allSupportFacts.contains(factAtomKey)) {
                solely.add(atomKey);
            }
        }
        return solely;
    }
}
