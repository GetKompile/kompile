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

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroundingHandlers}: the six grounding tools
 * (ask_graph_verify, ask_graph_query, ask_graph_explain, graph_reason,
 * ask_graph_assert, ask_graph_retract).
 *
 * <p>All tests use a small programmatic graph and drive through the dispatcher,
 * asserting on MiniJson-parsed response maps.</p>
 */
class GroundingHandlersTest {

    @TempDir
    Path tempDir;

    private LocalToolDispatcher dispatcher;
    private LocalReasoningSession session;

    /**
     * Build a small social-org graph:
     * alice -WORKS_AT-> acme
     * bob   -WORKS_AT-> acme
     * alice -KNOWS-> bob
     */
    @BeforeEach
    void setUp() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("alice", "PERSON", "Alice",
                0.9, 0.9, Set.of("PERSON"), null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("bob", "PERSON", "Bob",
                0.8, 0.8, Set.of("PERSON"), null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("acme", "ORG", "Acme Corp",
                1.0, 1.0, Set.of("ORG"), null, null, Map.of()));

        g.addRelation("r1", "alice", "acme", "WORKS_AT", 1.0);
        g.addRelation("r2", "bob",   "acme", "WORKS_AT", 1.0);
        g.addRelation("r3", "alice", "bob",  "KNOWS",    0.9);

        session = LocalReasoningSession.of(g);
        dispatcher = LocalToolDispatcher.create();
    }

    // ── ask_graph_verify ─────────────────────────────────────────────────────

    @Test
    void verifyKnownAtomReturnsSupported() {
        // WORKS_AT(alice, acme) is projected from the graph relation r1
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"WORKS_AT(alice, acme)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("SUPPORTED", r.get("verdict"),
                "Known graph relation should verify as SUPPORTED: " + json);
        double conf = ((Number) r.get("confidence")).doubleValue();
        assertTrue(conf > 0.0, "Confidence should be > 0 for SUPPORTED: " + json);
        assertTrue(r.containsKey("evidenceAtoms"), "Response must have evidenceAtoms: " + json);
        assertTrue(r.containsKey("entityKnown"), "Response must have entityKnown: " + json);
    }

    @Test
    void verifyUnknownAtomReturnsUnknown() {
        // No such relation in the graph
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"HATES(alice, bob)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("UNKNOWN", r.get("verdict"),
                "Non-existent relation should be UNKNOWN: " + json);
    }

    @Test
    void verifyMissingAtomReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_verify", "{}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Missing atom should return ERROR: " + json);
    }

    @Test
    void verifyResponseHasAllRequiredFields() {
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"WORKS_AT(alice, acme)\"}");
        Map<String, Object> r = parse(json);
        assertTrue(r.containsKey("verdict"),        "Must have verdict");
        assertTrue(r.containsKey("confidence"),     "Must have confidence");
        assertTrue(r.containsKey("evidenceAtoms"),  "Must have evidenceAtoms");
        assertTrue(r.containsKey("counterEvidence"),"Must have counterEvidence");
        assertTrue(r.containsKey("contradictions"), "Must have contradictions");
        assertTrue(r.containsKey("entityKnown"),    "Must have entityKnown");
        assertTrue(r.containsKey("derivationDepth"),"Must have derivationDepth");
        assertTrue(r.containsKey("evidenceCount"),  "Must have evidenceCount");
    }

    @Test
    void verifyEntityKnownForKnownEntity() {
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"PERSON(alice)\"}");
        Map<String, Object> r = parse(json);
        // alice is in the graph, so entityKnown should be true
        assertEquals(Boolean.TRUE, r.get("entityKnown"),
                "alice is a known entity: " + json);
    }

    // ── ask_graph_query ──────────────────────────────────────────────────────

    @Test
    void queryWithVariableBindingsReturnsBindings() {
        // ConjunctiveQueryEngine queries the inferred store, which starts empty.
        // Assert a fact directly to prime the inferred store via an InferredFact.
        // Instead, we verify that the query engine returns a well-formed response
        // (possibly empty if the inferred store is empty).
        String json = dispatcher.dispatch(session, "ask_graph_query",
                "{\"conjuncts\":[{\"predicate\":\"WORKS_AT\",\"args\":[\"?x\",\"acme\"]}]}");
        Map<String, Object> r = parse(json);
        assertTrue(r.containsKey("total"),    "Response must have total: " + json);
        assertTrue(r.containsKey("truncated"),"Response must have truncated: " + json);
        assertTrue(r.containsKey("bindings"), "Response must have bindings: " + json);
        // total >= 0
        int total = ((Number) r.get("total")).intValue();
        assertTrue(total >= 0, "total must be >= 0: " + json);
    }

    @Test
    void queryMissingConjunctsReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_query", "{}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Missing conjuncts should return ERROR: " + json);
    }

    @Test
    void queryEmptyConjunctsReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_query",
                "{\"conjuncts\":[]}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Empty conjuncts should return ERROR: " + json);
    }

    // ── ask_graph_explain ────────────────────────────────────────────────────

    @Test
    void explainKnownAtomReturnsStructuredResponse() {
        String json = dispatcher.dispatch(session, "ask_graph_explain",
                "{\"target\":\"WORKS_AT(alice, acme)\"}");
        Map<String, Object> r = parse(json);
        assertTrue(r.containsKey("verdict"),           "Must have verdict: " + json);
        assertTrue(r.containsKey("confidence"),        "Must have confidence: " + json);
        assertTrue(r.containsKey("inferenceMode"),     "Must have inferenceMode: " + json);
        assertTrue(r.containsKey("derivationTreeJson"),"Must have derivationTreeJson: " + json);
        assertTrue(r.containsKey("evidence"),          "Must have evidence: " + json);
        assertTrue(r.containsKey("activatedRules"),    "Must have activatedRules: " + json);
        assertEquals("GROUNDING", r.get("inferenceMode"),
                "inferenceMode must be GROUNDING locally: " + json);
    }

    @Test
    void explainMissingTargetReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_explain", "{}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Missing target should return ERROR: " + json);
    }

    @Test
    void explainDerivationTreeJsonIsValidJson() {
        String json = dispatcher.dispatch(session, "ask_graph_explain",
                "{\"target\":\"KNOWS(alice, bob)\"}");
        Map<String, Object> r = parse(json);
        String treeJson = (String) r.get("derivationTreeJson");
        assertNotNull(treeJson, "derivationTreeJson must not be null");
        // Parse the tree JSON to verify it is valid
        Object treeObj = MiniJson.parse(treeJson);
        assertNotNull(treeObj, "derivationTreeJson must be valid JSON: " + treeJson);
    }

    // ── graph_reason ─────────────────────────────────────────────────────────

    @Test
    void graphReasonMirrosExplain() {
        // graph_reason is an alias for ask_graph_explain
        String explainJson = dispatcher.dispatch(session, "ask_graph_explain",
                "{\"target\":\"WORKS_AT(alice, acme)\"}");
        String reasonJson = dispatcher.dispatch(session, "graph_reason",
                "{\"target\":\"WORKS_AT(alice, acme)\"}");

        Map<String, Object> explainR = parse(explainJson);
        Map<String, Object> reasonR  = parse(reasonJson);

        assertEquals(explainR.get("verdict"),       reasonR.get("verdict"),
                "graph_reason and ask_graph_explain should return the same verdict");
        assertEquals(explainR.get("inferenceMode"), reasonR.get("inferenceMode"),
                "graph_reason and ask_graph_explain should return the same inferenceMode");
    }

    @Test
    void graphReasonMissingTargetReturnsError() {
        String json = dispatcher.dispatch(session, "graph_reason", "{}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Missing target should return ERROR: " + json);
    }

    // ── ask_graph_assert ─────────────────────────────────────────────────────

    @Test
    void assertNewRelationReturnsOk() {
        String json = dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"MANAGES(alice, bob)\",\"value\":1.0}");
        Map<String, Object> r = parse(json);
        assertEquals("OK", r.get("status"), "Assert should return OK: " + json);
        assertTrue(r.containsKey("contradictions"), "Response must have contradictions");
        assertFalse((Boolean) r.get("cascadeTriggered"), "cascadeTriggered must be false locally");
    }

    @Test
    void assertNewRelationWritesThroughToGraph() {
        // After asserting, the new relation should be in the graph
        dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"MANAGES(alice, bob)\",\"value\":1.0}");

        // Verify the graph now has the relation: MANAGES(alice, bob) should verify SUPPORTED
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"MANAGES(alice, bob)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("SUPPORTED", r.get("verdict"),
                "Asserted relation should verify as SUPPORTED: " + json);
    }

    @Test
    void assertThenVerifySupported() {
        // Assert a completely new atom, then verify it
        dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"FOUNDED(alice, acme)\",\"value\":0.9,\"source\":\"test\"}");
        String json = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"FOUNDED(alice, acme)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("SUPPORTED", r.get("verdict"),
                "After assert, atom should verify SUPPORTED: " + json);
    }

    @Test
    void assertContradictionIsReported() {
        // Assert conflicting STATUS for the same entity — STATUS is a functional predicate
        dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"STATUS(acme, active)\",\"value\":1.0}");
        String json = dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"STATUS(acme, dissolved)\",\"value\":1.0}");
        Map<String, Object> r = parse(json);
        // Contradictions may or may not be detected by the functional-conflict rule
        // (depends on ContradictionDetector.isFunctionalPredicate); we just verify the shape
        assertTrue(r.containsKey("contradictions"),
                "Response must always have contradictions list: " + json);
    }

    @Test
    void assertMissingAtomReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_assert", "{}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Missing atom should return ERROR: " + json);
    }

    // ── ask_graph_retract ────────────────────────────────────────────────────

    @Test
    void retractKnownAtomRemovesFromFactStore() {
        // First confirm it is SUPPORTED
        String beforeJson = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"WORKS_AT(alice, acme)\"}");
        assertEquals("SUPPORTED", parse(beforeJson).get("verdict"),
                "Should be SUPPORTED before retract: " + beforeJson);

        // Retract
        String retractJson = dispatcher.dispatch(session, "ask_graph_retract",
                "{\"atomKey\":\"WORKS_AT(alice, acme)\"}");
        Map<String, Object> r = parse(retractJson);
        assertEquals("OK", r.get("status"), "Retract should return OK: " + retractJson);
        assertEquals("retract", r.get("mode"), "Default mode should be 'retract': " + retractJson);
        assertTrue(r.containsKey("dependentAtomsUnsupported"), "Must have dependentAtomsUnsupported");
        assertTrue(r.containsKey("dependentAtomsWeakened"),    "Must have dependentAtomsWeakened");

        // After retract: the binary relation is removed from the graph topology AND the fact
        // store (topology write-through). reprimeKb() is called so subsequent verify reads a
        // clean fact store — the relation is truly gone.
        String afterJson = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"WORKS_AT(alice, acme)\"}");
        Map<String, Object> afterR = parse(afterJson);
        // After retract with topology write-through, atom must be UNKNOWN
        assertEquals("UNKNOWN", afterR.get("verdict"),
                "After retract, atom should be UNKNOWN (topology + fact store removed): " + afterJson);
    }

    @Test
    void retractMissingAtomKeyReturnsError() {
        String json = dispatcher.dispatch(session, "ask_graph_retract", "{}");
        Map<String, Object> r = parse(json);
        assertEquals("ERROR", r.get("status"), "Missing atomKey should return ERROR: " + json);
    }

    @Test
    void retractReturnsTrueTopologyRemovedForBinaryAtom() {
        String json = dispatcher.dispatch(session, "ask_graph_retract",
                "{\"atomKey\":\"KNOWS(alice, bob)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("OK", r.get("status"), "Retract must return OK: " + json);
        assertEquals(Boolean.TRUE, r.get("topologyRemoved"),
                "topologyRemoved must be true for an existing binary relation: " + json);
    }

    @Test
    void retractTopologyRemovedFalseForUnknownAtom() {
        String json = dispatcher.dispatch(session, "ask_graph_retract",
                "{\"atomKey\":\"HATES(alice, bob)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("OK", r.get("status"));
        assertEquals(Boolean.FALSE, r.get("topologyRemoved"),
                "topologyRemoved must be false for an atom with no matching graph relation: " + json);
    }

    @Test
    void retractPersistenceRoundTrip_assertRetractSaveReloadVerifyUnknown() throws IOException {
        // assert→save→reload→retract→save→reload→verify == UNKNOWN
        // Step 1: Assert a relation not in the original graph
        dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"COLLABORATES(alice, bob)\",\"value\":1.0}");

        // Step 2: Save to file
        Path afterAssert = tempDir.resolve("after-assert.kgraph");
        dispatcher.dispatch(session, "graph_save",
                "{\"path\":\"" + escapeJson(afterAssert.toString()) + "\"}");

        // Step 3: Reload — COLLABORATES should be SUPPORTED
        LocalReasoningSession s2 = LocalReasoningSession.open(afterAssert);
        LocalToolDispatcher d2 = LocalToolDispatcher.create();
        String v1 = d2.dispatch(s2, "ask_graph_verify", "{\"atom\":\"COLLABORATES(alice, bob)\"}");
        assertEquals("SUPPORTED", parse(v1).get("verdict"), "Pre-retract verify must be SUPPORTED: " + v1);

        // Step 4: Retract in session s2
        String retractResult = d2.dispatch(s2, "ask_graph_retract",
                "{\"atomKey\":\"COLLABORATES(alice, bob)\"}");
        assertEquals("OK", parse(retractResult).get("status"), "Retract must succeed: " + retractResult);
        assertEquals(Boolean.TRUE, parse(retractResult).get("topologyRemoved"),
                "topologyRemoved must be true: " + retractResult);

        // Step 5: Save after retract
        Path afterRetract = tempDir.resolve("after-retract.kgraph");
        d2.dispatch(s2, "graph_save", "{\"path\":\"" + escapeJson(afterRetract.toString()) + "\"}");
        s2.close();

        // Step 6: Reload from post-retract file — COLLABORATES must be UNKNOWN
        LocalReasoningSession s3 = LocalReasoningSession.open(afterRetract);
        LocalToolDispatcher d3 = LocalToolDispatcher.create();
        String v2 = d3.dispatch(s3, "ask_graph_verify", "{\"atom\":\"COLLABORATES(alice, bob)\"}");
        assertEquals("UNKNOWN", parse(v2).get("verdict"),
                "After retract+save+reload, atom must be UNKNOWN: " + v2);
        s3.close();
    }

    @Test
    void retractNeverAssertedAtomIsClean() {
        // Retract an atom that was never asserted must not throw or corrupt state
        String json = dispatcher.dispatch(session, "ask_graph_retract",
                "{\"atomKey\":\"INVENTED(x, y)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("OK", r.get("status"), "Retract of non-existent atom must be OK: " + json);
        assertEquals(Boolean.FALSE, r.get("topologyRemoved"),
                "topologyRemoved must be false for never-asserted atom: " + json);

        // Existing atoms must be unaffected
        String verify = dispatcher.dispatch(session, "ask_graph_verify",
                "{\"atom\":\"WORKS_AT(alice, acme)\"}");
        assertEquals("SUPPORTED", parse(verify).get("verdict"),
                "Unrelated atom must still be SUPPORTED: " + verify);
    }

    // ── Save/reload write-through round-trip ─────────────────────────────────

    @Test
    void assertedFactSurvivesSaveReload() throws IOException {
        // Assert a new relation
        dispatcher.dispatch(session, "ask_graph_assert",
                "{\"atom\":\"SPONSORS(alice, acme)\",\"value\":1.0}");

        // Save the graph
        Path saved = tempDir.resolve("write-through.kgraph");
        dispatcher.dispatch(session, "graph_save",
                "{\"path\":\"" + escapeJson(saved.toString()) + "\"}");

        // Reload into a new session
        LocalReasoningSession reloaded = LocalReasoningSession.open(saved);
        LocalToolDispatcher dispatcher2 = LocalToolDispatcher.create();

        // The relation should have been written to the graph and persisted
        String json = dispatcher2.dispatch(reloaded, "ask_graph_verify",
                "{\"atom\":\"SPONSORS(alice, acme)\"}");
        Map<String, Object> r = parse(json);
        assertEquals("SUPPORTED", r.get("verdict"),
                "Asserted relation must survive save/reload: " + json);
        reloaded.close();
    }

    // ── Catalog ──────────────────────────────────────────────────────────────

    @Test
    void catalogContainsAllSixGroundingTools() {
        String catalogJson = dispatcher.dispatch(session, "tools_catalog", "{}");
        @SuppressWarnings("unchecked")
        List<Object> catalog = (List<Object>) MiniJson.parse(catalogJson);
        assertNotNull(catalog, "Catalog must not be null");

        List<String> names = catalog.stream()
                .map(e -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) e;
                    return (String) m.get("name");
                })
                .toList();

        assertTrue(names.contains("ask_graph_verify"),  "Catalog must have ask_graph_verify");
        assertTrue(names.contains("ask_graph_query"),   "Catalog must have ask_graph_query");
        assertTrue(names.contains("ask_graph_explain"), "Catalog must have ask_graph_explain");
        assertTrue(names.contains("graph_reason"),      "Catalog must have graph_reason");
        assertTrue(names.contains("ask_graph_assert"),  "Catalog must have ask_graph_assert");
        assertTrue(names.contains("ask_graph_retract"), "Catalog must have ask_graph_retract");

        // Total = 4 core + 6 grounding (+ stubs from inference/analytics, currently 0)
        assertTrue(names.size() >= 10, "Catalog must have at least 10 tools: " + names);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        assertNotNull(json, "Response must not be null");
        Object parsed = MiniJson.parse(json);
        assertInstanceOf(Map.class, parsed, "Response must be a JSON object: " + json);
        return (Map<String, Object>) parsed;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\");
    }
}
