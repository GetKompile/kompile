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
package ai.kompile.graph.reasoning.fol.materialization;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.EdbProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Bridges the {@link FactStore} / atom-key representation to the
 * {@link RecursiveQueryEngine} Datalog layer.
 *
 * <p>Atom keys use the canonical form {@code "Predicate(arg1, arg2)"}.  This adapter
 * wraps a {@link FactStore} as an {@link EdbProvider} and provides helpers to convert
 * {@link SimpleDatalogRule}s (a lightweight rule syntax defined here) to
 * {@link DatalogRule}s that the query engine understands.</p>
 *
 * <p>Infra-free — no Spring, no JPA.</p>
 */
public final class FolDatalogAdapter {

    private static final Logger log = LoggerFactory.getLogger(FolDatalogAdapter.class);

    /**
     * Default EDB binarization threshold.
     *
     * <p>Facts with value strictly below this threshold are treated as absent from the EDB
     * (closed-world assumption binarization).  A fact with value exactly at the threshold
     * is included.  The rationale: PSL soft-truth 0.5 corresponds to maximum uncertainty;
     * treating any soft-truth below 0.5 as absent avoids feeding unconfident atoms into
     * crisp Datalog inference, which does not handle soft truth.
     * Tune this threshold downward (e.g. 0.3) to admit weaker evidence into grounding.</p>
     */
    public static final double DEFAULT_EDB_BINARIZATION_THRESHOLD = 0.5;

    private FolDatalogAdapter() {}

    // ─── EDB adapter ────────────────────────────────────────────────────────────

    /**
     * Wrap a {@link FactStore} as a {@link EdbProvider} using the default binarization
     * threshold ({@value #DEFAULT_EDB_BINARIZATION_THRESHOLD}).
     *
     * <p>Each atom key of the form {@code "Pred(a, b, ...)"} contributes a ground tuple
     * {@code [a, b, ...]} to the predicate {@code Pred}.  Zero-arity predicates (no
     * parentheses) contribute an empty tuple {@code []} if the fact value is ≥ the threshold.
     * Facts with value below the threshold are excluded (binarized as absent) and a WARN
     * is logged if any facts are excluded.</p>
     *
     * @param store the fact store to expose as EDB
     * @return an {@link EdbProvider} backed by {@code store}
     */
    public static EdbProvider factStoreEdb(FactStore store) {
        return factStoreEdb(store, DEFAULT_EDB_BINARIZATION_THRESHOLD);
    }

    /**
     * Wrap a {@link FactStore} as a {@link EdbProvider} using an explicit binarization threshold.
     *
     * <p>Facts with value strictly below {@code binarizationThreshold} are treated as absent
     * from the EDB.  This implements the soft→crisp binarization needed before Datalog
     * grounding: Datalog is a two-valued logic (present / absent), not a soft-truth system.</p>
     *
     * <p>Examples:
     * <ul>
     *   <li>threshold=0.5 (default): a fact with value 0.4 is excluded; value 0.5 is included.</li>
     *   <li>threshold=0.3: a fact with value 0.3 is included; value 0.29 is excluded.</li>
     *   <li>threshold=0.0: all facts are included regardless of soft-truth value.</li>
     * </ul>
     *
     * @param store                  the fact store to expose as EDB
     * @param binarizationThreshold  minimum fact value (inclusive) to include in the EDB;
     *                               must be in [0, 1]
     * @return an {@link EdbProvider} backed by {@code store}
     * @throws IllegalArgumentException if {@code binarizationThreshold} is outside [0, 1]
     */
    public static EdbProvider factStoreEdb(FactStore store, double binarizationThreshold) {
        if (binarizationThreshold < 0.0 || binarizationThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "binarizationThreshold must be in [0,1], got: " + binarizationThreshold);
        }
        // Build an index predicate → list-of-tuples from the current snapshot
        Map<String, List<List<String>>> index = new LinkedHashMap<>();
        int excluded = 0;
        for (Fact fact : store.allFacts()) {
            if (fact.value() < binarizationThreshold) {
                excluded++;
                continue; // treat sub-threshold facts as absent (EDB binarization)
            }
            String atomKey = fact.atomKey();
            int lp = atomKey.indexOf('(');
            if (lp < 0) {
                // 0-arity
                index.computeIfAbsent(atomKey, k -> new ArrayList<>()).add(List.of());
            } else {
                int rp = atomKey.lastIndexOf(')');
                String pred = atomKey.substring(0, lp).trim();
                String inside = (rp > lp) ? atomKey.substring(lp + 1, rp).trim() : "";
                List<String> args = new ArrayList<>();
                if (!inside.isEmpty()) {
                    for (String a : inside.split(",")) {
                        String t = a.trim();
                        if (!t.isEmpty()) args.add(t);
                    }
                }
                index.computeIfAbsent(pred, k -> new ArrayList<>()).add(List.copyOf(args));
            }
        }
        if (excluded > 0) {
            log.warn("FolDatalogAdapter: {} fact(s) excluded from EDB by binarization threshold {} "
                    + "(value < threshold → treated as absent in Datalog grounding). "
                    + "Lower the threshold to admit weaker evidence.",
                    excluded, binarizationThreshold);
        }
        final int finalExcluded = excluded;
        return new EdbProviderWithStats(index, finalExcluded);
    }

    /**
     * An {@link EdbProvider} that also exposes how many facts were excluded during
     * binarization (for diagnostics and testing).
     */
    public static final class EdbProviderWithStats implements EdbProvider {

        private final Map<String, List<List<String>>> index;
        private final int excludedFactCount;

        EdbProviderWithStats(Map<String, List<List<String>>> index, int excludedFactCount) {
            this.index = index;
            this.excludedFactCount = excludedFactCount;
        }

        @Override
        public List<List<String>> tuplesFor(String pred) {
            return index.getOrDefault(pred, List.of());
        }

        /**
         * Number of facts excluded from the EDB because their value was below the
         * binarization threshold.
         *
         * @return number of excluded facts (≥ 0)
         */
        public int excludedFactCount() {
            return excludedFactCount;
        }
    }

    // ─── Annotation helpers ──────────────────────────────────────────────────────

    /**
     * Build an in-memory snapshot of a {@link FactStore}'s fact values, keyed by atom key.
     *
     * <p>This is the value map used by the Viterbi EDB annotator: for an EDB atom key
     * (e.g. {@code "edge(a, b)"}), the annotator returns the fact's numeric truth value
     * in [0,1].  Facts with value below the binarization threshold are still included in
     * the value map (annotation pass uses raw values, not the binarized EDB); this allows
     * soft truth values to flow into the Viterbi product even when the crisp EDB excluded
     * them from the rule firing.</p>
     *
     * <p>Callers can use the returned function directly as the {@code edbAnnotator} argument
     * of {@link RecursiveQueryEngine#evaluateAnnotated}: for EDB atom keys that actually
     * appear as derivation parents (i.e. they passed binarization and were used by the
     * engine), this function returns their original soft-truth value.  For any atom key
     * not in the store, it returns {@code 1.0} (full weight — consistent with the
     * closed-world crisp assumption that fired facts have value 1.0).</p>
     *
     * @param store the fact store to snapshot
     * @return a function from atom key → Double value in [0,1]
     */
    public static Function<String, Double> factValueAnnotator(FactStore store) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Fact fact : store.allFacts()) {
            values.put(fact.atomKey(), fact.value());
        }
        // For atom keys not found in the store (e.g. atoms synthesized by the engine from
        // IDB predicates that also appear as EDB), default to 1.0 (full Viterbi weight).
        return atomKey -> values.getOrDefault(atomKey, 1.0);
    }

    /**
     * Build a Viterbi EDB annotator that returns {@code 1.0} for every atom key.
     *
     * <p>Use this when all EDB facts are treated as equally certain (value = 1.0), e.g.
     * when all base facts are hard-observed and the Viterbi product is trivially 1.0
     * for all derived facts (parity mode: annotation equals the crisp derivation weight).</p>
     *
     * @return function that always returns {@code 1.0}
     */
    public static Function<String, Double> uniformViterbiAnnotator() {
        return atomKey -> 1.0;
    }

    // ─── Rule syntax helpers ─────────────────────────────────────────────────────

    /**
     * Build a binary transitivity rule:
     * <pre>
     *   path(?X, ?Z) :- path(?X, ?Y), path(?Y, ?Z)
     * </pre>
     * using {@code predicate} as the predicate name.
     *
     * @param predicate the transitive predicate (e.g. "ancestor", "reachable")
     * @return the corresponding {@link DatalogRule}
     */
    public static DatalogRule transitivityRule(String predicate) {
        return new DatalogRule(
                predicate,
                List.of("?X", "?Z"),
                List.of(
                        RuleAtom.pos(predicate, "?X", "?Y"),
                        RuleAtom.pos(predicate, "?Y", "?Z")
                )
        );
    }

    /**
     * Build a rule that copies base-predicate facts into the derived predicate:
     * <pre>
     *   derived(?X, ?Y) :- base(?X, ?Y)
     * </pre>
     * This is used to seed the IDB for a transitive predicate from a different base predicate.
     *
     * @param derivedPredicate the head predicate (IDB)
     * @param basePredicate    the source predicate (EDB)
     * @return the copy rule
     */
    public static DatalogRule copyRule(String derivedPredicate, String basePredicate) {
        return new DatalogRule(
                derivedPredicate,
                List.of("?X", "?Y"),
                List.of(RuleAtom.pos(basePredicate, "?X", "?Y"))
        );
    }

    /**
     * Build a simple two-hop rule:
     * <pre>
     *   head(?X, ?Z) :- body1(?X, ?Y), body2(?Y, ?Z)
     * </pre>
     *
     * @param head  derived predicate name
     * @param body1 first body predicate
     * @param body2 second body predicate
     * @return the corresponding {@link DatalogRule}
     */
    public static DatalogRule twoHopRule(String head, String body1, String body2) {
        return new DatalogRule(
                head,
                List.of("?X", "?Z"),
                List.of(
                        RuleAtom.pos(body1, "?X", "?Y"),
                        RuleAtom.pos(body2, "?Y", "?Z")
                )
        );
    }
}
