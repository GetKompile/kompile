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
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.EdbProvider;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.FixpointResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Forward-chaining materializer: given a {@link FactStore} and a Datalog rule set,
 * derive <em>all</em> entailed facts to fixpoint and materialize them as
 * {@link InferredFact}s in an {@link InferredFactStore}.
 *
 * <h2>Design</h2>
 * <p>This class is the "materialization" half of the FOL-based materialization + reduction
 * capability.  The "reduction" half lives in {@link LogicBasedReducer}.</p>
 *
 * <p>Entailed facts are <em>deductive</em> (logically certain given the premises and rules).
 * They are assigned:</p>
 * <ul>
 *   <li>{@code value = 1.0} — fully true under the closed-world assumption of Datalog.</li>
 *   <li>{@code confidence = 1.0} — no uncertainty; the derivation is deterministic.</li>
 *   <li>{@code runId} — a fresh UUID per materialization call, so runs can be distinguished.</li>
 *   <li>{@code supportingRuleIds} — contains the marker {@code "FOL_ENTAILMENT/DEDUCTIVE"} so
 *       downstream consumers and the graph store can distinguish entailed facts from
 *       probabilistic PSL/MEBN inferred facts.</li>
 * </ul>
 *
 * <h2>Fixpoint + cycle safety</h2>
 * <p>The underlying {@link RecursiveQueryEngine} uses semi-naive bottom-up evaluation with
 * stratification (Tarjan SCC).  Cyclic rules terminate naturally at fixpoint: a cycle
 * {@code p(X,Y) :- p(X,Z), p(Z,Y)} on a finite domain converges to the transitive closure
 * of the base facts.  The {@link #maxIterations} parameter (default {@value #DEFAULT_MAX_ITERATIONS})
 * bounds fixpoint rounds; {@link #maxDerivedFacts} (default {@value #DEFAULT_MAX_DERIVED_FACTS})
 * bounds the total derived-fact count.  Neither limit is expected to trigger on a normal
 * finite fact store, but they prevent infinite loops on pathological inputs.</p>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, no JPA, no @Value.  All tunables are passed as constructor/method parameters
 * with documented defaults.</p>
 *
 * <h2>Example</h2>
 * <pre>
 *   FactStore store = new FactStore();
 *   store.assertFact(Fact.observed("ancestor(alice, bob)", "test"));
 *   store.assertFact(Fact.observed("ancestor(bob, carol)", "test"));
 *
 *   List&lt;DatalogRule&gt; rules = List.of(FolDatalogAdapter.transitivityRule("ancestor"));
 *   ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
 *   InMemoryInferredFactStore out = new InMemoryInferredFactStore();
 *   MaterializationResult result = mat.materialize(store, rules, out);
 *   // out now contains ancestor(alice, carol) as a deductive InferredFact
 * </pre>
 */
public final class ForwardChainingMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ForwardChainingMaterializer.class);

    /** Basis marker placed in every derived fact's supportingRuleIds. */
    public static final String DEDUCTIVE_BASIS_MARKER = "FOL_ENTAILMENT/DEDUCTIVE";

    /**
     * Default maximum number of fixpoint iterations.
     * Enough for practical transitive closures; override via constructor if needed.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /**
     * Default maximum number of derived facts before early termination.
     * Guards against combinatorial explosion on very large fact stores.
     */
    public static final int DEFAULT_MAX_DERIVED_FACTS = 1_000_000;

    private final int maxIterations;
    private final int maxDerivedFacts;

    /** Construct with default limits. */
    public ForwardChainingMaterializer() {
        this(DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_DERIVED_FACTS);
    }

    /**
     * Construct with explicit limits.
     *
     * @param maxIterations  maximum fixpoint rounds (default {@value #DEFAULT_MAX_ITERATIONS});
     *                       must be ≥ 1
     * @param maxDerivedFacts maximum total derived facts (default {@value #DEFAULT_MAX_DERIVED_FACTS});
     *                        must be ≥ 1
     */
    public ForwardChainingMaterializer(int maxIterations, int maxDerivedFacts) {
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be ≥ 1, got: " + maxIterations);
        if (maxDerivedFacts < 1) throw new IllegalArgumentException("maxDerivedFacts must be ≥ 1, got: " + maxDerivedFacts);
        this.maxIterations = maxIterations;
        this.maxDerivedFacts = maxDerivedFacts;
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Run forward-chaining to fixpoint, materialize all derived facts into {@code sink},
     * and return a summary of the materialization run.
     *
     * <p>Facts already present in {@code factStore} are the EDB (base facts).  Rules
     * derive IDB (intensional) facts; only the IDB is materialized into {@code sink}
     * (not the EDB, which the caller already has in the fact store).</p>
     *
     * @param factStore the base fact store (EDB)
     * @param rules     Datalog rules (may include recursive rules)
     * @param sink      where to store derived {@link InferredFact}s
     * @return materialization result with counts and metadata
     */
    public MaterializationResult materialize(FactStore factStore,
                                             List<DatalogRule> rules,
                                             InferredFactStore sink) {
        Objects.requireNonNull(factStore, "factStore must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(sink, "sink must not be null");

        if (rules.isEmpty()) {
            log.debug("ForwardChainingMaterializer: no rules supplied — nothing to derive");
            return new MaterializationResult(0, 0, true, "");
        }

        String runId = UUID.randomUUID().toString();
        log.info("ForwardChainingMaterializer run={}: {} rules, {} base facts",
                runId, rules.size(), factStore.size());

        EdbProvider edb = FolDatalogAdapter.factStoreEdb(factStore);
        FixpointResult fixpoint = RecursiveQueryEngine.evaluate(rules, edb, maxIterations, maxDerivedFacts);

        // Materialize derived facts into the sink
        int stored = 0;
        Instant now = Instant.now();
        long version = 0L;
        for (Map.Entry<String, Set<List<String>>> entry : fixpoint.derivedFacts().entrySet()) {
            String pred = entry.getKey();
            for (List<String> tuple : entry.getValue()) {
                String atomKey = buildAtomKey(pred, tuple);
                // Skip if already in the EDB (it's not a new derived fact from our perspective)
                if (factStore.factFor(atomKey).isPresent()) continue;
                InferredFact fact = new InferredFact(
                        atomKey,
                        1.0,   // deductively certain
                        1.0,   // confidence = 1.0 (no uncertainty)
                        List.of(),  // no specific supporting-fact keys (derives from full EDB)
                        List.of(DEDUCTIVE_BASIS_MARKER),
                        runId,
                        version++,
                        now
                );
                sink.store(fact);
                stored++;
            }
        }

        log.info("ForwardChainingMaterializer run={}: derived {} facts (fixpoint rounds={}, complete={})",
                runId, stored, fixpoint.roundsCompleted(), fixpoint.isComplete());

        return new MaterializationResult(stored, fixpoint.roundsCompleted(),
                fixpoint.isComplete(), fixpoint.terminationReason());
    }

    /**
     * Convenience: also assert all derived facts back into the {@code factStore} so that
     * subsequent materialization calls can build on them.  This is useful for incremental
     * multi-step derivation where each step adds new base facts.
     *
     * @param factStore the base fact store (EDB) — derived facts are also asserted here
     * @param rules     Datalog rules
     * @param sink      where to store derived {@link InferredFact}s
     * @return materialization result
     */
    public MaterializationResult materializeAndAssert(FactStore factStore,
                                                       List<DatalogRule> rules,
                                                       InferredFactStore sink) {
        MaterializationResult result = materialize(factStore, rules, sink);
        // Assert derived facts back into the EDB for follow-on use
        for (InferredFact fact : sink.allLatest()) {
            if (DEDUCTIVE_BASIS_MARKER.equals(firstRuleId(fact))) {
                factStore.assertFact(new Fact(fact.atomKey(), fact.value(),
                        fact.runId(), fact.inferredAt(), true));
            }
        }
        return result;
    }

    // ─── Result record ────────────────────────────────────────────────────────────

    /**
     * Summary of a forward-chaining materialization run.
     *
     * @param derivedFactCount  number of new facts stored in the sink
     * @param roundsCompleted   fixpoint rounds executed
     * @param reachedFixpoint   {@code true} if the natural fixpoint was reached (not a guard limit)
     * @param terminationReason empty if fixpoint was reached; otherwise the guard that triggered early exit
     */
    public record MaterializationResult(
            int derivedFactCount,
            int roundsCompleted,
            boolean reachedFixpoint,
            String terminationReason) {}

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static String buildAtomKey(String pred, List<String> args) {
        if (args.isEmpty()) return pred;
        return pred + "(" + String.join(", ", args) + ")";
    }

    private static String firstRuleId(InferredFact fact) {
        List<String> ids = fact.supportingRuleIds();
        return (ids == null || ids.isEmpty()) ? null : ids.get(0);
    }
}
