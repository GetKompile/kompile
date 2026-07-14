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

import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.materialization.FolDatalogAdapter;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link LocalKbState} materializes bundled Datalog rules at session open time
 * so that {@code ask_graph_verify} / {@code ask_graph_explain} return derived atoms as
 * SUPPORTED with a derivation depth > 0.
 *
 * <h3>Rule model: grandparent</h3>
 * <pre>
 *   ancestor(?X, ?Y) :- parent(?X, ?Y)         [copy rule — seed IDB]
 *   ancestor(?X, ?Z) :- ancestor(?X, ?Y), ancestor(?Y, ?Z)  [transitivity]
 * </pre>
 * <p>Base facts: parent(alice, bob), parent(bob, carol).
 * Derived: ancestor(alice, bob), ancestor(bob, carol), ancestor(alice, carol).</p>
 */
class RulesBundleMaterializationTest {

    @TempDir
    Path tmp;

    // ── Build helpers ─────────────────────────────────────────────────────────

    /** Build a graph with PARENT edges and bundle transitivity rules. */
    private UnifiedGraph buildGraphWithRules() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "PERSON", "Alice");
        g.addEntity("bob",   "PERSON", "Bob");
        g.addEntity("carol", "PERSON", "Carol");

        // Base facts: parent edges
        g.addRelation("r1", "alice", "bob",   "parent", 1.0);
        g.addRelation("r2", "bob",   "carol", "parent", 1.0);

        // Bundle Datalog rules
        List<DatalogRule> rules = List.of(
            FolDatalogAdapter.copyRule("ancestor", "parent"),       // seed
            FolDatalogAdapter.transitivityRule("ancestor"));        // transitive closure

        g.putArtifactText(DatalogRulesBundle.ARTIFACT_KEY, DatalogRulesBundle.toJson(rules));
        return g;
    }

    // ── Core: verify DERIVED atom is SUPPORTED ─────────────────────────────

    @Test
    void derivedAtom_isSupportedAfterMaterialization() {
        UnifiedGraph g = buildGraphWithRules();
        LocalReasoningSession session = LocalReasoningSession.of(g);
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();

        // Directly observed: parent(alice, bob) → SUPPORTED
        String v1 = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"parent(alice, bob)\"}");
        assertEquals("SUPPORTED", parse(v1).get("verdict"), "Base fact must be SUPPORTED: " + v1);

        // Derived via copy rule: ancestor(alice, bob) → SUPPORTED
        String v2 = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"ancestor(alice, bob)\"}");
        assertEquals("SUPPORTED", parse(v2).get("verdict"),
                "Derived ancestor(alice,bob) must be SUPPORTED: " + v2);

        // Derived via transitivity: ancestor(alice, carol) → SUPPORTED
        String v3 = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"ancestor(alice, carol)\"}");
        assertEquals("SUPPORTED", parse(v3).get("verdict"),
                "Transitive ancestor(alice,carol) must be SUPPORTED: " + v3);
    }

    @Test
    void explain_showsRuleDerivationForDerivedAtom() {
        UnifiedGraph g = buildGraphWithRules();
        LocalReasoningSession session = LocalReasoningSession.of(g);
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();

        // Explain the transitive atom — must reference the rule in evidence/activatedRules
        // Note: ask_graph_explain uses "target" not "atom" as the key
        String json = dispatcher.dispatch(session, "ask_graph_explain",
                "{\"target\":\"ancestor(alice, carol)\"}");

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) MiniJson.parse(json);
        assertNotNull(r, "explain must not return null");
        assertNotEquals("ERROR", r.get("verdict"), "explain must not error: " + json);

        // The derivation tree JSON must mention "ancestor"
        String derivJson = String.valueOf(r.getOrDefault("derivationTreeJson", ""));
        assertTrue(derivJson.contains("ancestor"),
                "derivation tree must reference 'ancestor': " + derivJson);
    }

    @Test
    void nonDerivedAtom_remainsUnknown() {
        UnifiedGraph g = buildGraphWithRules();
        LocalReasoningSession session = LocalReasoningSession.of(g);
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();

        // No parent(carol, alice) — must be UNKNOWN
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"parent(carol, alice)\"}");
        assertEquals("UNKNOWN", parse(json).get("verdict"),
                "Non-existent atom must be UNKNOWN: " + json);
    }

    // ── Round-trip: rules survive save/load ──────────────────────────────────

    @Test
    void bundledRules_surviveSaveLoad_andDerivedAtomsRemainSupported() throws IOException {
        UnifiedGraph g = buildGraphWithRules();
        Path file = tmp.resolve("ancestor.kgraph");
        g.save(file);

        // Reload — materialization must re-run at open() time
        LocalReasoningSession reloaded = LocalReasoningSession.open(file);
        LocalToolDispatcher d2 = LocalToolDispatcher.create();

        String v = d2.dispatch(reloaded, "ask_graph_verify",
                "{\"atom\":\"ancestor(alice, carol)\"}");
        assertEquals("SUPPORTED", parse(v).get("verdict"),
                "Derived atom must be SUPPORTED after save+reload: " + v);
        reloaded.close();
    }

    // ── Backwards-compat: graph without rules behaves exactly as before ───────

    @Test
    void graphWithoutRules_observedOnlyBehaviorUnchanged() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("x", "T", "X");
        g.addEntity("y", "T", "Y");
        g.addRelation("r1", "x", "y", "edge", 1.0);

        LocalReasoningSession session = LocalReasoningSession.of(g);
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();

        // Observed fact is SUPPORTED
        String v1 = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"edge(x, y)\"}");
        assertEquals("SUPPORTED", parse(v1).get("verdict"), "Observed fact must be SUPPORTED");

        // Any derived predicate is UNKNOWN (no rules)
        String v2 = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"derived(x, y)\"}");
        assertEquals("UNKNOWN", parse(v2).get("verdict"),
                "Without rules, non-observed atom must be UNKNOWN");
    }

    @Test
    void malformedRulesArtifact_fallsBackToObservedOnly() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "P", "Alice");
        g.addRelation("r1", "alice", "acme", "WORKS_AT", 1.0);
        // Put garbage as the rules artifact
        g.putArtifactText(DatalogRulesBundle.ARTIFACT_KEY, "{\"not\":\"a rule array\"}");

        // Must not throw — fallback to observed-only
        LocalReasoningSession session = assertDoesNotThrow(() -> LocalReasoningSession.of(g),
                "Session open must not throw on malformed rules");

        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();
        String v = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"WORKS_AT(alice, acme)\"}");
        assertEquals("SUPPORTED", parse(v).get("verdict"),
                "Observed fact must remain SUPPORTED even with malformed rules: " + v);
    }

    // ── DatalogRulesBundle JSON round-trip ───────────────────────────────────

    @Test
    void datalogRulesBundle_jsonRoundTrip() {
        List<DatalogRule> original = List.of(
            FolDatalogAdapter.copyRule("ancestor", "parent"),
            FolDatalogAdapter.transitivityRule("ancestor"),
            FolDatalogAdapter.twoHopRule("grandparent", "parent", "parent"));

        String json = DatalogRulesBundle.toJson(original);
        assertNotNull(json);
        assertFalse(json.isBlank());

        List<DatalogRule> roundTripped = DatalogRulesBundle.fromJson(json);
        assertEquals(original.size(), roundTripped.size(),
                "Rule count must survive JSON round-trip");
        for (int i = 0; i < original.size(); i++) {
            DatalogRule o = original.get(i);
            DatalogRule r = roundTripped.get(i);
            assertEquals(o.headPredicate(), r.headPredicate(), "headPredicate mismatch at " + i);
            assertEquals(o.headArgs(),      r.headArgs(),      "headArgs mismatch at " + i);
            assertEquals(o.body().size(),   r.body().size(),   "body size mismatch at " + i);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        assertNotNull(json, "Response must not be null");
        Object parsed = MiniJson.parse(json);
        assertInstanceOf(Map.class, parsed, "Response must be a JSON object: " + json);
        return (Map<String, Object>) parsed;
    }
}
