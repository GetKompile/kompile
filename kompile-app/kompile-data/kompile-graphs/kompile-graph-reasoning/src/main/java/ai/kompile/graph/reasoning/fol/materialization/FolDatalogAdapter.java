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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private FolDatalogAdapter() {}

    // ─── EDB adapter ────────────────────────────────────────────────────────────

    /**
     * Wrap a {@link FactStore} as a {@link EdbProvider}.
     *
     * <p>Each atom key of the form {@code "Pred(a, b, ...)"} contributes a ground tuple
     * {@code [a, b, ...]} to the predicate {@code Pred}.  Zero-arity predicates (no
     * parentheses) contribute an empty tuple {@code []} if the fact value is ≥ 0.5.</p>
     *
     * @param store the fact store to expose as EDB
     * @return an {@link EdbProvider} backed by {@code store}
     */
    public static EdbProvider factStoreEdb(FactStore store) {
        // Build an index predicate → list-of-tuples from the current snapshot
        Map<String, List<List<String>>> index = new LinkedHashMap<>();
        for (Fact fact : store.allFacts()) {
            if (fact.value() < 0.5) continue; // treat sub-threshold facts as absent
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
        return pred -> index.getOrDefault(pred, List.of());
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
