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
package ai.kompile.graph.reasoning.e2e;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.tms.BeliefReviser;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A REAL end-to-end reasoning scenario over named entities: an employment/residency domain where
 * <b>first-order (Datalog) rules</b> derive new facts, those facts are materialized back onto the
 * graph, and a genuine <b>contradiction</b> — a person derived to live in one city but asserted to
 * live in another — is detected (functional-predicate clash) and resolved (belief revision).
 *
 * <pre>
 *   Entities:  people {alice, bob, carol}, companies {acme, globex}, cities {nyc, london}
 *   Facts:     worksFor(alice,acme) worksFor(bob,acme) worksFor(carol,globex)
 *              locatedIn(acme,nyc)  locatedIn(globex,london)
 *              manages(alice,bob)   manages(bob,carol)
 *   FOL rules: basedIn(P,C)             :- worksFor(P,O), locatedIn(O,C)
 *              managesTransitively(X,Y) :- manages(X,Y)
 *              managesTransitively(X,Z) :- managesTransitively(X,Y), manages(Y,Z)
 * </pre>
 */
class FolContradictionScenarioE2ETest {

    private UnifiedGraph domain() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "Person", "Alice");
        g.addEntity("bob", "Person", "Bob");
        g.addEntity("carol", "Person", "Carol");
        g.addEntity("acme", "Company", "Acme");
        g.addEntity("globex", "Company", "Globex");
        g.addEntity("nyc", "City", "New York");
        g.addEntity("london", "City", "London");
        g.addRelation("w1", "alice", "acme", "worksFor", 1.0);
        g.addRelation("w2", "bob", "acme", "worksFor", 1.0);
        g.addRelation("w3", "carol", "globex", "worksFor", 1.0);
        g.addRelation("l1", "acme", "nyc", "locatedIn", 1.0);
        g.addRelation("l2", "globex", "london", "locatedIn", 1.0);
        g.addRelation("m1", "alice", "bob", "manages", 1.0);
        g.addRelation("m2", "bob", "carol", "manages", 1.0);
        return g;
    }

    /** The FOL program: residency from employment, and the transitive-management closure. */
    private List<DatalogRule> rules() {
        return List.of(
                new DatalogRule("basedIn", List.of("?P", "?C"),
                        List.of(RuleAtom.pos("worksFor", "?P", "?O"),
                                RuleAtom.pos("locatedIn", "?O", "?C"))),
                new DatalogRule("managesTransitively", List.of("?X", "?Y"),
                        List.of(RuleAtom.pos("manages", "?X", "?Y"))),
                new DatalogRule("managesTransitively", List.of("?X", "?Z"),
                        List.of(RuleAtom.pos("managesTransitively", "?X", "?Y"),
                                RuleAtom.pos("manages", "?Y", "?Z"))));
    }

    /** Extensional database (EDB) read straight off the graph's typed relations. */
    private Map<String, List<List<String>>> edbFromGraph(ReasoningGraph g) {
        Map<String, List<List<String>>> edb = new HashMap<>();
        for (GraphRelation r : g.relations()) {
            edb.computeIfAbsent(r.type(), k -> new ArrayList<>()).add(List.of(r.sourceId(), r.targetId()));
        }
        return edb;
    }

    private RecursiveQueryEngine.FixpointResult runFol(ReasoningGraph g) {
        Map<String, List<List<String>>> edb = edbFromGraph(g);
        RecursiveQueryEngine.EdbProvider provider = pred -> edb.getOrDefault(pred, List.of());
        return RecursiveQueryEngine.evaluate(rules(), provider,
                RecursiveQueryEngine.DEFAULT_MAX_ROUNDS, RecursiveQueryEngine.DEFAULT_MAX_DERIVED_FACTS);
    }

    // ── First-order derivation over real entities ────────────────────────────────

    @Test
    void firstOrderRulesDeriveResidencyFromEmployment() {
        Set<List<String>> basedIn = runFol(domain()).derivedFacts().get("basedIn");
        assertTrue(basedIn.contains(List.of("alice", "nyc")), "alice works at NYC-based acme → basedIn(alice,nyc)");
        assertTrue(basedIn.contains(List.of("bob", "nyc")));
        assertTrue(basedIn.contains(List.of("carol", "london")), "carol works at London-based globex");
        assertFalse(basedIn.contains(List.of("alice", "london")), "no rule entails alice in london");
        assertEquals(3, basedIn.size(), "exactly one residency per employee");
    }

    @Test
    void transitiveManagementClosureIsDerived() {
        Set<List<String>> mgmt = runFol(domain()).derivedFacts().get("managesTransitively");
        assertTrue(mgmt.contains(List.of("alice", "bob")), "direct");
        assertTrue(mgmt.contains(List.of("bob", "carol")), "direct");
        assertTrue(mgmt.contains(List.of("alice", "carol")), "transitive: alice → bob → carol");
        assertFalse(mgmt.contains(List.of("carol", "alice")), "management is not symmetric");
    }

    @Test
    void derivedFactsMaterializeBackOntoTheGraphAsInferredRelations() {
        UnifiedGraph g = domain();
        Set<List<String>> basedIn = runFol(g).derivedFacts().get("basedIn");

        List<InferredFact> facts = new ArrayList<>();
        long seq = 1;
        for (List<String> t : basedIn) {
            facts.add(InferredFact.of("basedIn(" + t.get(0) + ", " + t.get(1) + ")", 1.0,
                    List.of(), List.of("basedIn-rule"), "fol-run", seq++));
        }
        MutableReasoningGraph mg = g.mutableGraph();
        var result = InferredFactMaterializer.materialize(facts, mg);
        assertEquals(3, result.relationsAdded(), "three residency relations materialized");

        GraphRelation aliceBasedIn = mg.outgoing("alice").stream()
                .filter(r -> r.type().equals("basedIn")).findFirst().orElseThrow();
        assertEquals("nyc", aliceBasedIn.targetId());
        assertTrue(aliceBasedIn.tags().contains(InferredFactMaterializer.INFERRED_TAG),
                "materialized fact carries the INFERRED tag (provenance)");
    }

    // ── The contradiction: derived residency vs an asserted conflicting claim ────

    @Test
    void functionalContradictionBetweenDerivedAndAssertedResidency() {
        UnifiedGraph g = domain();
        Set<List<String>> basedIn = runFol(g).derivedFacts().get("basedIn");

        // Everything the FOL layer derived becomes a hard fact...
        FactStore store = new FactStore();
        for (List<String> t : basedIn) {
            store.assertFact(Fact.observed("basedIn(" + t.get(0) + ", " + t.get(1) + ")", "fol-derivation"));
        }
        // ...then a conflicting HR record claims Alice is based in London.
        store.assertFact(Fact.observed("basedIn(alice, london)", "hr-record"));

        // basedIn is FUNCTIONAL (a person lives in exactly one city) → alice's two cities clash.
        List<ContradictionDetector.Pair<Fact, Fact>> clashes =
                ContradictionDetector.findFactContradictions(store, Set.of("basedIn"));
        assertEquals(1, clashes.size(), "exactly one functional contradiction — Alice's two cities");

        // Bob and Carol each have a single, consistent city → no contradiction without the HR claim.
        FactStore consistent = new FactStore();
        for (List<String> t : basedIn) {
            consistent.assertFact(Fact.observed("basedIn(" + t.get(0) + ", " + t.get(1) + ")", "fol-derivation"));
        }
        assertEquals(0, ContradictionDetector.findFactContradictions(consistent, Set.of("basedIn")).size(),
                "the FOL derivation alone is internally consistent");
    }

    @Test
    void negatedEmploymentClaimContradictsAndDirectContradicts() {
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("Employed(alice)", "fol-derivation"));   // alice worksFor acme
        store.assertFact(Fact.observed("Not_Employed(alice)", "stale-record")); // a stale source says otherwise
        assertEquals(1, ContradictionDetector.findFactContradictions(store).size(),
                "Employed(alice) vs Not_Employed(alice) is a negated-atom contradiction");

        // The direct polarity check on the same atom key.
        assertTrue(ContradictionDetector.contradicts(
                Fact.observed("Employed(alice)", "s1"),
                new Fact("Employed(alice)", 0.0, "s2", java.time.Instant.now(), true)));
        assertFalse(ContradictionDetector.contradicts(
                Fact.observed("Employed(alice)", "s1"),
                Fact.observed("Employed(bob)", "s2")),
                "different entities do not contradict");
    }

    // ── Resolution: belief revision retracts the offending fact ──────────────────

    @Test
    void beliefRevisionRetractsTheStaleFactAndShrinksTheStore() {
        // A propagation program over the domain: Employed(X) & Manages(X,Y) -> Employed(Y).
        PslProgram program = new PslProgram();
        program.addRule("2.0: Employed(X) & Manages(X, Y) -> Employed(Y) ^2");
        program.observe("Employed", 1.0, "alice");
        program.observe("Manages", 1.0, "alice", "bob");
        program.target("Employed", "bob");

        FactStore factStore = new FactStore();
        factStore.assertFact(Fact.observed("Employed(alice)", "fol-derivation"));
        factStore.assertFact(Fact.observed("Manages(alice, bob)", "fol-derivation"));
        int before = factStore.size();

        HlMrfMapInference.Result solved = HlMrfMapInference.solve(program);
        JustificationIndex index = JustificationIndex.build(solved, factStore);

        BeliefRevisionResult revision = BeliefReviser.retract("Employed(alice)", factStore, index);
        assertEquals("Employed(alice)", revision.retractedFactKey());
        assertEquals(before - 1, factStore.size(), "retraction removes exactly the offending fact");
        assertFalse(revision.revisedFactStore().factFor("Employed(alice)").isPresent());
        assertSame(factStore, revision.revisedFactStore(), "revision mutates the store in place");
    }

    // ── The whole scenario is serialization-stable (capstone fidelity) ───────────

    @Test
    void theDomainAndItsDerivationsSurviveARoundTrip() throws IOException {
        UnifiedGraph g = domain();
        Set<List<String>> before = runFol(g).derivedFacts().get("basedIn");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos, Dtype.F64);
        UnifiedGraph reloaded = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        assertEquals(7, reloaded.entityCount());
        assertEquals(7, reloaded.relationCount());
        Set<List<String>> after = runFol(reloaded).derivedFacts().get("basedIn");
        assertEquals(before, after, "the same FOL residency facts are derived after reload");
    }
}
