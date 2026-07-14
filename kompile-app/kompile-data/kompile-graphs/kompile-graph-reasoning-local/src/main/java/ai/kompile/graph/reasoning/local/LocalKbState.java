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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.materialization.FolDatalogAdapter;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Infra-free analogue of {@code KbGroundingService.FactSheetKbState} for local (no-Spring) use.
 *
 * <p>Holds the grounding stores that make verify/query/explain return meaningful results
 * after a graph is loaded:</p>
 * <ul>
 *   <li>{@link #factStore()} — observed facts projected from graph entities and relations
 *       (mirrors {@code GraphToFactStoreProjector.project()}).</li>
 *   <li>{@link #inferredFactStore()} — MAP-derived inferred facts. Populated at load time
 *       when the graph contains a {@value DatalogRulesBundle#ARTIFACT_KEY} artifact with
 *       bundled Datalog rules (bounded synchronous materialization). Also populated by
 *       inference handlers after a PSL MAP solve.</li>
 *   <li>{@link #justificationIndex()} — derivation lineage built after a MAP solve;
 *       {@code null} until the first PSL inference run completes.</li>
 * </ul>
 *
 * <h3>Rule-bundle materialization</h3>
 * <p>If the loaded {@link UnifiedGraph} carries a {@value DatalogRulesBundle#ARTIFACT_KEY}
 * artifact (a JSON array of Datalog rules produced by
 * {@link DatalogRulesBundle#toJson(List)}), this class runs bounded synchronous Datalog
 * materialization via {@link RecursiveQueryEngine} and stores the derived facts into the
 * inferred fact store. Any failure falls back to observed-only mode silently (logged at
 * WARN level) — {@code open()} never fails due to materialization errors.</p>
 */
public final class LocalKbState {

    private static final Logger log = LoggerFactory.getLogger(LocalKbState.class);

    private final FactStore factStore;
    private final InMemoryInferredFactStore inferredFactStore;
    /** Null until the first MAP solve runs and builds the index via {@link JustificationIndex#build}. */
    private volatile JustificationIndex justificationIndex;

    private LocalKbState(FactStore factStore,
                         InMemoryInferredFactStore inferredFactStore) {
        this.factStore = factStore;
        this.inferredFactStore = inferredFactStore;
        this.justificationIndex = null;
    }

    public FactStore factStore() { return factStore; }
    public InMemoryInferredFactStore inferredFactStore() { return inferredFactStore; }

    /**
     * The current justification index, or {@code null} if no MAP solve has been run yet.
     * Set by inference handlers after a successful PSL MAP inference.
     */
    public JustificationIndex justificationIndex() { return justificationIndex; }

    /**
     * Called by inference handlers after a MAP solve to install the fresh index.
     * Subsequent verify/explain queries will use it.
     */
    public void setJustificationIndex(JustificationIndex index) {
        this.justificationIndex = index;
    }

    /**
     * Create a fresh {@link LocalKbState} for {@code graph}, pre-populated with observed facts
     * projected from the graph's entities (unary type atoms) and relations (binary predicate atoms).
     *
     * <p>This is the same projection logic as {@code GraphToFactStoreProjector} on the server side,
     * reproduced here without Spring or the live store — the graph itself provides the data via
     * {@link UnifiedGraph#facts()}, which synthesises {@code predicate(source, target)} atoms for
     * relations and {@code Type(id)} atoms for each entity type membership.</p>
     *
     * <p>If the graph carries a {@value DatalogRulesBundle#ARTIFACT_KEY} artifact, bounded
     * synchronous Datalog materialization is also run and derived facts are placed in the
     * inferred fact store. Materialization failures fall back silently to observed-only mode.</p>
     */
    public static LocalKbState primeFromGraph(UnifiedGraph graph) {
        FactStore fs = new FactStore();
        InMemoryInferredFactStore ifs = new InMemoryInferredFactStore();

        // Project graph facts — UnifiedGraph.facts() synthesizes:
        //   each relation  → type(source, target)  observed hard fact
        //   each type tag  → Type(id)               observed hard fact
        for (Fact fact : graph.facts()) {
            fs.assertFact(fact);
        }

        // Materialize bundled Datalog rules if present
        materializeBundledRules(graph, fs, ifs);

        return new LocalKbState(fs, ifs);
    }

    /**
     * Re-project facts from a (possibly mutated) graph, clearing the observed fact store first.
     * The inferred-fact store and justification index are NOT cleared — they will be stale until
     * inference re-runs, but remain readable for explain queries against the prior inference epoch.
     *
     * <p>Also re-runs bounded Datalog materialization from any bundled rules so that derived
     * facts remain consistent with the updated observed fact base.</p>
     */
    public void reprimeFromGraph(UnifiedGraph graph) {
        factStore.clear();
        for (Fact fact : graph.facts()) {
            factStore.assertFact(fact);
        }
        // Re-materialize bundled rules over the updated observed fact base
        materializeBundledRules(graph, factStore, inferredFactStore);
    }

    /**
     * Run bounded synchronous Datalog materialization if the graph carries a
     * {@value DatalogRulesBundle#ARTIFACT_KEY} artifact. Derived facts are stored into
     * {@code ifs}. Any error is logged at WARN level and silently ignored — callers
     * always get at least the observed-only KB state.
     *
     * <p>Bound: {@link RecursiveQueryEngine#DEFAULT_MAX_ROUNDS} rounds and
     * {@link RecursiveQueryEngine#DEFAULT_MAX_DERIVED_FACTS} derived facts — same defaults as
     * the engine itself. These limits prevent runaway materialization over large graphs.</p>
     */
    private static void materializeBundledRules(UnifiedGraph graph,
                                                FactStore fs,
                                                InMemoryInferredFactStore ifs) {
        byte[] rulesBytes = graph.artifact(DatalogRulesBundle.ARTIFACT_KEY);
        if (rulesBytes == null || rulesBytes.length == 0) return;

        List<DatalogRule> rules = DatalogRulesBundle.fromBytes(rulesBytes);
        if (rules.isEmpty()) return;

        try {
            RecursiveQueryEngine.EdbProvider edb = FolDatalogAdapter.factStoreEdb(fs);
            RecursiveQueryEngine.FixpointResult result = RecursiveQueryEngine.evaluate(rules, edb);

            List<InferredFact> derived = result.toInferredFacts("local:datalog");
            for (InferredFact fact : derived) {
                ifs.store(fact);
            }
            log.debug("LocalKbState: materialized {} derived facts from {} bundled rules",
                    derived.size(), rules.size());
        } catch (Exception e) {
            log.warn("LocalKbState: Datalog materialization failed (falling back to observed-only): {}",
                    e.getMessage());
        }
    }
}
