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
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Logic-based reducer: removes FOL-<em>redundant</em> facts from a {@link FactStore}.
 *
 * <h2>What "redundant" means here</h2>
 * <p>A fact {@code F} is logically redundant with respect to a rule set {@code R} and the
 * remaining facts {@code S \ {F}} if {@code F} can be derived from {@code S \ {F}} by
 * applying {@code R} to fixpoint.  In other words, the explicit graph can safely drop
 * {@code F} because the rules will reconstruct it on demand.  The resulting reduced set is
 * a <em>core</em> (sometimes called a "kernel") of the original: no remaining fact is
 * derivable from the others.</p>
 *
 * <p>This is <strong>distinct from confidence pruning</strong> (removing low-confidence
 * facts): a redundant fact may have confidence 1.0.  The criterion is purely <em>logical</em>
 * redundancy.</p>
 *
 * <h2>Transitive reduction for transitive predicates</h2>
 * <p>For binary predicates declared transitive, a direct edge {@code (X, Z)} is redundant if
 * there exists an indirect path {@code X → Y → ... → Z} composed only of direct edges.  This
 * is precisely the <em>transitive reduction</em> of the relation, which is unique for DAGs
 * and well-defined (as the smallest relation with the same transitive closure) for general
 * digraphs.  The {@link #reduceTransitivePredicate} method implements this efficiently:
 * for each edge {@code (X, Z)}, it runs a DFS/BFS from X in the graph that <em>excludes</em>
 * that edge and checks whether Z is still reachable; if so, the edge is redundant.</p>
 *
 * <h2>Safety + reversibility</h2>
 * <p>The reducer never mutates the original {@link FactStore}.  It returns a
 * {@link ReductionResult} that contains:</p>
 * <ul>
 *   <li>The <em>core</em> fact set (non-redundant facts) as a new {@link FactStore}.</li>
 *   <li>The <em>removed</em> facts with provenance — each carries a
 *       {@code REDUNDANCY/TRANSITIVE} or {@code REDUNDANCY/RULE} basis marker so callers
 *       can audit why a fact was removed.</li>
 *   <li>The full set of removed facts so the caller can restore them if needed.</li>
 * </ul>
 *
 * <h2>Tunables (all passed as constructor/method parameters)</h2>
 * <ul>
 *   <li>{@link #maxReductionWork} — maximum number of reachability checks per predicate
 *       (default {@value #DEFAULT_MAX_REDUCTION_WORK}); bounds cost on very dense graphs.</li>
 *   <li>{@code transitivePredicates} — caller-supplied set of predicate names to apply
 *       transitive reduction to (empty = no transitive reduction, only rule-based redundancy).</li>
 * </ul>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, no JPA, no @Value.</p>
 */
public final class LogicBasedReducer {

    private static final Logger log = LoggerFactory.getLogger(LogicBasedReducer.class);

    /** Basis marker for a fact removed because it is transitively entailed. */
    public static final String REDUNDANCY_TRANSITIVE = "REDUNDANCY/TRANSITIVE";

    /** Basis marker for a fact removed because it is entailed by the remaining facts + rules. */
    public static final String REDUNDANCY_RULE = "REDUNDANCY/RULE";

    /**
     * Default maximum number of reachability checks per predicate during transitive reduction.
     * E(E-1) checks are needed in the worst case; this bound prevents O(N³) blowup on dense graphs.
     * Reduce if performance matters; increase if you need exact reduction on large graphs.
     */
    public static final int DEFAULT_MAX_REDUCTION_WORK = 10_000;

    /**
     * Default maximum fixpoint iterations for the rule-entailment check
     * (passed to {@link ForwardChainingMaterializer}).
     */
    public static final int DEFAULT_MAX_MATERIALIZER_ITERATIONS = 10;

    private final int maxReductionWork;
    private final int maxMaterializerIterations;

    /** Construct with default limits. */
    public LogicBasedReducer() {
        this(DEFAULT_MAX_REDUCTION_WORK, DEFAULT_MAX_MATERIALIZER_ITERATIONS);
    }

    /**
     * Construct with explicit limits.
     *
     * @param maxReductionWork           max reachability probes per predicate
     *                                   (default {@value #DEFAULT_MAX_REDUCTION_WORK}); must be ≥ 1
     * @param maxMaterializerIterations  max fixpoint iterations for the entailment check
     *                                   (default {@value #DEFAULT_MAX_MATERIALIZER_ITERATIONS}); must be ≥ 1
     */
    public LogicBasedReducer(int maxReductionWork, int maxMaterializerIterations) {
        if (maxReductionWork < 1)
            throw new IllegalArgumentException("maxReductionWork must be ≥ 1, got: " + maxReductionWork);
        if (maxMaterializerIterations < 1)
            throw new IllegalArgumentException("maxMaterializerIterations must be ≥ 1, got: " + maxMaterializerIterations);
        this.maxReductionWork = maxReductionWork;
        this.maxMaterializerIterations = maxMaterializerIterations;
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Reduce a fact store by removing transitively redundant edges for each predicate in
     * {@code transitivePredicates}, then removing facts that are rule-entailed by the remaining
     * facts + {@code rules}.
     *
     * <p>The original {@code factStore} is not mutated.  All changes are reflected in
     * {@link ReductionResult#coreStore()}.</p>
     *
     * @param factStore           the input fact store to reduce
     * @param rules               Datalog rules to use for rule-based entailment check
     * @param transitivePredicates predicates to apply transitive reduction to (may be empty)
     * @return result with the core store and the removed facts
     */
    public ReductionResult reduce(FactStore factStore,
                                  List<DatalogRule> rules,
                                  Set<String> transitivePredicates) {
        Objects.requireNonNull(factStore, "factStore must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(transitivePredicates, "transitivePredicates must not be null");

        log.info("LogicBasedReducer: {} base facts, {} rules, {} transitive predicates",
                factStore.size(), rules.size(), transitivePredicates.size());

        // Work on a mutable copy — the original is not touched
        FactStore working = copyFactStore(factStore);
        List<RemovedFact> removed = new ArrayList<>();

        // Phase 1: transitive reduction for declared transitive predicates
        for (String pred : transitivePredicates) {
            List<RemovedFact> transitivelyRemoved = reduceTransitivePredicate(working, pred);
            removed.addAll(transitivelyRemoved);
        }

        // Phase 2: rule-based redundancy — remove any fact entailed by remaining facts + rules
        if (!rules.isEmpty()) {
            List<RemovedFact> ruleRemoved = reduceByRules(working, rules);
            removed.addAll(ruleRemoved);
        }

        log.info("LogicBasedReducer: removed {} redundant facts ({} remain)",
                removed.size(), working.size());

        return new ReductionResult(working, List.copyOf(removed));
    }

    /**
     * Transitive-predicate-only reduction (no rules).
     *
     * @param factStore           the input fact store
     * @param transitivePredicates predicates to apply transitive reduction to
     * @return result
     */
    public ReductionResult reduceTransitive(FactStore factStore, Set<String> transitivePredicates) {
        return reduce(factStore, List.of(), transitivePredicates);
    }

    /**
     * Rule-based redundancy only (no transitive reduction).
     *
     * @param factStore the input fact store
     * @param rules     Datalog rules
     * @return result
     */
    public ReductionResult reduceByRulesOnly(FactStore factStore, List<DatalogRule> rules) {
        return reduce(factStore, rules, Set.of());
    }

    // ─── Phase 1: transitive reduction ───────────────────────────────────────────

    /**
     * Remove all transitively-entailed edges for {@code predicate} from {@code working}.
     *
     * <p>An edge {@code (X, Z)} is redundant if there exists another path from X to Z
     * in the graph restricted to {@code predicate}-edges, not using {@code (X, Z)} itself.
     * We find such paths by DFS from X in the edge set with {@code (X, Z)} removed.</p>
     *
     * @param working the mutable working copy of the fact store
     * @param predicate the transitive predicate to reduce
     * @return facts that were removed
     */
    private List<RemovedFact> reduceTransitivePredicate(FactStore working, String predicate) {
        // Collect all (subject, object) pairs for this predicate
        List<String[]> edges = new ArrayList<>();
        for (Fact fact : working.factsFor(predicate)) {
            String atomKey = fact.atomKey();
            String[] parsed = parseSubjectObject(atomKey);
            if (parsed != null) edges.add(parsed);
        }

        if (edges.isEmpty()) return List.of();

        // Build adjacency list: subject → set of objects
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (String[] edge : edges) {
            adj.computeIfAbsent(edge[0], k -> new LinkedHashSet<>()).add(edge[1]);
        }

        List<RemovedFact> removed = new ArrayList<>();
        int work = 0;

        for (String[] edge : edges) {
            String subject = edge[0];
            String object = edge[1];

            if (work >= maxReductionWork) {
                log.warn("LogicBasedReducer: maxReductionWork ({}) reached for predicate '{}' — "
                        + "partial transitive reduction returned", maxReductionWork, predicate);
                break;
            }
            work++;

            // Check if object is reachable from subject WITHOUT the direct edge (subject→object)
            if (reachableWithout(adj, subject, object, subject, object)) {
                // This edge is redundant — remove from working store and adjacency
                String atomKey = predicate + "(" + subject + ", " + object + ")";
                Fact retracted = working.retract(atomKey).orElse(null);
                if (retracted == null) {
                    // Try without spaces
                    atomKey = predicate + "(" + subject + "," + object + ")";
                    retracted = working.retract(atomKey).orElse(null);
                }
                if (retracted != null) {
                    adj.get(subject).remove(object);
                    removed.add(new RemovedFact(retracted, REDUNDANCY_TRANSITIVE,
                            "Transitively entailed via another path from " + subject + " to " + object));
                }
            }
        }

        log.debug("LogicBasedReducer: transitive reduction of '{}': removed {} edges", predicate, removed.size());
        return removed;
    }

    /**
     * BFS/DFS reachability from {@code start} to {@code target} in {@code adj},
     * ignoring the direct edge {@code (forbiddenSrc, forbiddenDst)}.
     *
     * <p>We need to find a path of length ≥ 2 (at least one intermediate node).</p>
     */
    private static boolean reachableWithout(Map<String, Set<String>> adj,
                                             String start,
                                             String target,
                                             String forbiddenSrc,
                                             String forbiddenDst) {
        // BFS from start's neighbors (skip the direct edge)
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        Set<String> directNeighbors = adj.getOrDefault(start, Set.of());
        for (String neighbor : directNeighbors) {
            // Skip the direct edge we're testing for removal
            if (start.equals(forbiddenSrc) && neighbor.equals(forbiddenDst)) continue;
            if (!visited.contains(neighbor)) {
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(target)) return true;
            for (String next : adj.getOrDefault(current, Set.of())) {
                // After leaving start, we are free to use any edge (including the original target)
                if (!visited.contains(next)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return false;
    }

    // ─── Phase 2: rule-based redundancy ──────────────────────────────────────────

    /**
     * Remove facts from {@code working} that are entailed by the remaining facts + rules.
     *
     * <p>Algorithm: for each candidate fact F in the working store:
     * <ol>
     *   <li>Build a temporary store with F removed.</li>
     *   <li>Run forward-chaining materialization on the temporary store + rules.</li>
     *   <li>If F's atom key appears in the derived facts → F is redundant; remove it from working.</li>
     * </ol>
     * This is O(|facts| × materializer-cost); skip if there are many facts and cost matters.</p>
     */
    private List<RemovedFact> reduceByRules(FactStore working, List<DatalogRule> rules) {
        ForwardChainingMaterializer materializer =
                new ForwardChainingMaterializer(maxMaterializerIterations,
                        ForwardChainingMaterializer.DEFAULT_MAX_DERIVED_FACTS);

        // Collect candidate facts once (snapshot the current key set)
        List<String> candidateKeys = new ArrayList<>();
        for (Fact f : working.allFacts()) candidateKeys.add(f.atomKey());

        List<RemovedFact> removed = new ArrayList<>();

        for (String candidateKey : candidateKeys) {
            // Skip if already removed by an earlier step
            if (working.factFor(candidateKey).isEmpty()) continue;

            // Build a temporary fact store without this candidate
            FactStore withoutCandidate = copyFactStore(working);
            Fact candidate = withoutCandidate.retract(candidateKey).orElse(null);
            if (candidate == null) continue;

            // Materialize from the reduced store
            InferredFactStore derivedSink = new InMemoryInferredFactStore();
            materializer.materialize(withoutCandidate, rules, derivedSink);

            // Check if the candidate is derivable
            if (derivedSink.latest(candidateKey).isPresent()) {
                // Redundant — remove from working store
                working.retract(candidateKey);
                removed.add(new RemovedFact(candidate, REDUNDANCY_RULE,
                        "Entailed by remaining facts + rules"));
            }
        }

        log.debug("LogicBasedReducer: rule-based reduction removed {} facts", removed.size());
        return removed;
    }

    // ─── Result types ─────────────────────────────────────────────────────────────

    /**
     * Result of a reduction run.
     *
     * @param coreStore   the minimal (non-redundant) fact store;
     *                    the original fact store is <em>not</em> mutated
     * @param removedFacts provenance-carrying list of removed facts,
     *                     suitable for auditing or round-trip restoration
     */
    public record ReductionResult(FactStore coreStore, List<RemovedFact> removedFacts) {

        /**
         * Restore a previously reduced fact store to its original state by re-asserting
         * all removed facts into {@code target}.
         *
         * <p>The restored store is logically equivalent to the original input (before reduction)
         * when the same removed facts are re-asserted.  This makes reduction reversible.</p>
         *
         * @param target the store to restore removed facts into (may be the coreStore itself)
         */
        public void restore(FactStore target) {
            for (RemovedFact rf : removedFacts) {
                target.assertFact(rf.fact());
            }
        }

        /** @return the number of redundant facts removed */
        public int removedCount() { return removedFacts.size(); }
    }

    /**
     * A fact that was removed by the reducer, with provenance about why it was removed.
     *
     * @param fact            the removed fact
     * @param basisMarker     {@link #REDUNDANCY_TRANSITIVE} or {@link #REDUNDANCY_RULE}
     * @param reason          human-readable explanation of why the fact is redundant
     */
    public record RemovedFact(Fact fact, String basisMarker, String reason) {}

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Parse a binary atom key {@code "Pred(X, Y)"} into {@code [X, Y]}.
     * Returns {@code null} if the key is not a binary atom.
     */
    static String[] parseSubjectObject(String atomKey) {
        int lp = atomKey.indexOf('(');
        int rp = atomKey.lastIndexOf(')');
        if (lp <= 0 || rp <= lp) return null;
        String inside = atomKey.substring(lp + 1, rp).trim();
        String[] parts = inside.split(",", 2);
        if (parts.length != 2) return null;
        String s = parts[0].trim();
        String o = parts[1].trim();
        if (s.isEmpty() || o.isEmpty()) return null;
        return new String[]{s, o};
    }

    /** Copy a fact store into a new independent instance. */
    private static FactStore copyFactStore(FactStore original) {
        FactStore copy = new FactStore();
        for (Fact f : original.allFacts()) {
            copy.assertFact(f);
        }
        return copy;
    }
}
