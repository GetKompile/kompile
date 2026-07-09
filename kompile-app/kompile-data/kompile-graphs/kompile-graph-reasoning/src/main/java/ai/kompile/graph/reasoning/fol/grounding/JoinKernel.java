/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.Term;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared backtracking conjunctive-join kernel used by both
 * {@link ConjunctiveQueryEngine} and {@link RecursiveQueryEngine}.
 *
 * <p>The kernel iterates a list of {@link ConjunctiveQueryEngine.AtomPattern}
 * conjuncts in order, extending a partial variable binding at each step.
 * For each conjunct it consults a caller-supplied predicate index
 * ({@code Map<String, List<InferredFact>>}) and attempts to unify each candidate
 * against the current binding.  When all conjuncts are satisfied the completed
 * binding is emitted as a {@link QueryBinding} with confidence equal to the
 * running <b>Gödel (minimum) T-norm</b> — the minimum soft-truth across matched atoms.</p>
 *
 * <h3>Tier-semantics note (Fix #10)</h3>
 * <p>This kernel uses <b>Gödel min</b> ({@code min(a,b)}) to combine conjunct
 * confidences, while {@link ai.kompile.graph.reasoning.psl.GroundRule} uses the
 * <b>Łukasiewicz T-norm</b> ({@code max(0, a+b−1)}) for PSL rule-body evaluation.
 * The two are intentionally different (see {@link ConjunctiveQueryEngine} for the
 * full rationale): Gödel min is the correct "weakest-link" semantics for retrieval
 * (avoids vanishing-conjunction pathology); Łukasiewicz is the correct relaxation
 * for HL-MRF optimization (smooth convex hinge loss).  Never collapse the two.</p>
 *
 * <p>This is a package-private utility.  Callers are
 * {@link ConjunctiveQueryEngine} (query against a materialized
 * {@link ai.kompile.graph.reasoning.fol.InferredFactStore}) and
 * {@link RecursiveQueryEngine} (delta-iteration over IDB relations).</p>
 */
final class JoinKernel {

    private JoinKernel() {}

    /**
     * Backtracking conjunctive join over a predicate-indexed fact set.
     *
     * @param conjuncts   ordered list of atom patterns to match
     * @param byPredicate predicate → fact-list index (source of candidate atoms)
     * @param idx         current conjunct index (0 on initial call)
     * @param binding     partial variable binding accumulated so far
     * @param minConf     running minimum confidence (1.0 on initial call)
     * @param out         accumulator for completed {@link QueryBinding} results
     * @param limit       maximum results before early termination
     */
    static void backtrack(List<ConjunctiveQueryEngine.AtomPattern> conjuncts,
                          Map<String, List<InferredFact>> byPredicate,
                          int idx,
                          Map<String, String> binding,
                          double minConf,
                          List<QueryBinding> out,
                          int limit) {
        if (out.size() >= limit) return;
        if (idx == conjuncts.size()) {
            out.add(new QueryBinding(new LinkedHashMap<>(binding), minConf));
            return;
        }
        ConjunctiveQueryEngine.AtomPattern pattern = conjuncts.get(idx);
        PslAtom template = pattern.toTemplate();
        // Case-insensitive lookup: the ConjunctiveQueryEngine index is keyed lower-case (atom keys
        // are stored lower-cased by the graph projector); query predicates may be camelCase / UPPER.
        List<InferredFact> candidates = pattern.predicate() == null
                ? null : byPredicate.get(pattern.predicate().toLowerCase(Locale.ROOT));
        if (candidates == null) return;

        for (InferredFact candidate : candidates) {
            PslAtom groundAtom = ConjunctiveQueryEngine.parseAtomKey(candidate.atomKey());
            if (groundAtom == null) continue;
            Map<String, String> extended = unify(template, groundAtom, binding);
            if (extended == null) continue;
            double newMin = Math.min(minConf, candidate.confidence());
            backtrack(conjuncts, byPredicate, idx + 1, extended, newMin, out, limit);
        }
    }

    /**
     * Unify a (possibly non-ground) template atom against a ground candidate atom.
     *
     * <p>Variables in the template are bound to the corresponding constants in the
     * candidate; a clash (variable already bound to a different constant) causes
     * failure.  This is the same semantics as
     * {@link ConjunctiveQueryEngine#unify(PslAtom, PslAtom, Map)}.</p>
     *
     * @param template  the pattern atom (may contain variables)
     * @param candidate the ground atom
     * @param binding   the current partial binding
     * @return the extended binding on success, or {@code null} on failure
     */
    static Map<String, String> unify(PslAtom template, PslAtom candidate,
                                     Map<String, String> binding) {
        // Predicate match is case-insensitive so a camelCase / UPPER_SNAKE query predicate unifies
        // with the lower-cased predicate the projector stored (RecursiveQueryEngine passes template
        // and candidate built from the same predicate string, so this never changes its behaviour).
        if (!template.predicate().equalsIgnoreCase(candidate.predicate())) return null;
        if (template.args().size() != candidate.args().size()) return null;
        Map<String, String> extended = null;
        for (int i = 0; i < template.args().size(); i++) {
            Term t = template.args().get(i);
            String c = candidate.args().get(i).name();
            if (t.variable()) {
                String current = (extended != null ? extended : binding).get(t.name());
                if (current != null) {
                    if (!current.equals(c)) return null;
                } else {
                    if (extended == null) extended = new LinkedHashMap<>(binding);
                    extended.put(t.name(), c);
                }
            } else if (!t.name().equals(c)) {
                return null;
            }
        }
        return extended != null ? extended : new LinkedHashMap<>(binding);
    }
}
