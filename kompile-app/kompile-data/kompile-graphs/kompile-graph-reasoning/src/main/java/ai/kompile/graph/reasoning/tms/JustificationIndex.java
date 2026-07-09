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
 *
 * <h3>Sole-dependency semantics</h3>
 * <p>An inferred atom Y is <em>solely dependent</em> on an observed fact F if every
 * contributing ground rule that places Y in its head also has F in its body. Removing F
 * therefore removes the body precondition of every supporting rule simultaneously, leaving
 * Y with no remaining support. This is the correct rule-level check: the union-of-facts
 * approach (requiring F to be the <em>only</em> observed fact across all rules) is wrong
 * when a rule needs multiple facts to fire — e.g. the body {@code State(alice) & Link(alice,bob)}
 * needs both facts, so removing either one breaks the rule and leaves {@code State(bob)}
 * unsupported.</p>
 */
public class JustificationIndex {

    private static final double CONTRIBUTION_THRESHOLD = 0.1;

    // atom key -> list of supporting rule display strings
    private final Map<String, List<String>> atomToRules = new LinkedHashMap<>();

    // atom key -> set of observed fact keys that appear in the rule body
    // (union across all contributing rules — used for supportingFacts() and weakened-atom detection)
    private final Map<String, Set<String>> atomToFacts = new LinkedHashMap<>();

    // atom key -> per-rule body-fact sets (one Set<String> per contributing rule that heads this atom)
    // Used by solelyDependentOn() for the correct rule-level check.
    private final Map<String, List<Set<String>>> atomToPerRuleBodyFacts = new LinkedHashMap<>();

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

            // Collect the set of observed body-fact keys for this specific rule.
            Set<String> ruleBodyFacts = new LinkedHashSet<>();
            for (GroundRule.Lit bodyLit : gr.body()) {
                String bodyKey = bodyLit.atomKey();
                if (factStore.factFor(bodyKey).isPresent()) {
                    ruleBodyFacts.add(bodyKey);
                }
            }

            // For each head atom, record this rule as supporting
            for (GroundRule.Lit headLit : gr.head()) {
                String atomKey = headLit.atomKey();

                index.atomToRules.computeIfAbsent(atomKey, k -> new ArrayList<>())
                        .add(gr.display());

                // Union of body facts across all rules (for supportingFacts() / weakened-atom logic)
                Set<String> factKeys = index.atomToFacts
                        .computeIfAbsent(atomKey, k -> new LinkedHashSet<>());
                factKeys.addAll(ruleBodyFacts);

                // Per-rule body-fact set (for solelyDependentOn() correct rule-level check)
                index.atomToPerRuleBodyFacts
                        .computeIfAbsent(atomKey, k -> new ArrayList<>())
                        .add(ruleBodyFacts);

                // Reverse mapping: fact → atoms that depend on it (via any rule)
                for (String bodyKey : ruleBodyFacts) {
                    index.factToAtoms.computeIfAbsent(bodyKey, k -> new LinkedHashSet<>())
                            .add(atomKey);
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
     * Get the observed fact keys that supported an atom (union across all contributing rules).
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
     * Atoms whose support is fully lost when the given fact is retracted.
     *
     * <p>An atom Y becomes fully unsupported after retracting fact F if and only if
     * <b>every</b> contributing ground rule that places Y in its head contains F in its
     * body.  Removing F neutralises all supporting rules simultaneously, so Y has no
     * remaining inferred support.</p>
     *
     * <p>Contrast with the simpler (but incorrect) "union check": that check would require
     * F to be the sole observed fact across all rules, which fails for atoms like
     * {@code State(bob)} that need {@code State(alice) & Link(alice,bob)} — both are needed,
     * so removing either one breaks the only supporting rule even though the union of body
     * facts has size 2.  The per-rule check below is correct: it passes whenever every rule
     * body includes F.</p>
     *
     * @param factAtomKey the atom key of the fact being retracted
     * @return set of atom keys that will become fully unsupported when this fact is retracted;
     *         atoms with at least one supporting rule whose body does NOT include the fact are
     *         excluded (they are weakened, not fully unsupported)
     */
    public Set<String> solelyDependentOn(String factAtomKey) {
        Set<String> dependent = atomsDependingOnFact(factAtomKey);
        Set<String> solely = new LinkedHashSet<>();
        for (String atomKey : dependent) {
            List<Set<String>> perRuleBodyFacts =
                    atomToPerRuleBodyFacts.getOrDefault(atomKey, List.of());
            if (perRuleBodyFacts.isEmpty()) continue;

            // Y is solely dependent on F iff EVERY supporting rule body contains F.
            // If any rule can support Y WITHOUT F, Y is weakened but not unsupported.
            boolean allRulesNeedF = true;
            for (Set<String> ruleBody : perRuleBodyFacts) {
                if (!ruleBody.contains(factAtomKey)) {
                    allRulesNeedF = false;
                    break;
                }
            }
            if (allRulesNeedF) {
                solely.add(atomKey);
            }
        }
        return solely;
    }
}
