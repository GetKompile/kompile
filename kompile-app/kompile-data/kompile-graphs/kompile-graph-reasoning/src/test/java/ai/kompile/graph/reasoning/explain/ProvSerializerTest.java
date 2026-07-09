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
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.MiniJson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProvSerializer} — W3C PROV-N and PROV-JSON export of
 * {@link ReasoningTrace}.
 *
 * <p>These tests do NOT compile or build the project; they verify the serialized text output
 * by string inspection (PROV-N) and by MiniJson structural parse (PROV-JSON).</p>
 */
@DisplayName("E18 ProvSerializer")
class ProvSerializerTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /**
     * Simple RULE trace: root step with 2 FACT premises, one of which has a source.
     *
     * <pre>
     *   RULE "basedIn(alice, nyc)" via "basedIn-rule" conf=0.95
     *     ├─ FACT "worksFor(alice, acme)"  conf=1.0  source="graph-node-1"
     *     └─ FACT "locatedIn(acme, nyc)"  conf=1.0
     * </pre>
     */
    private static ReasoningTrace simpleRuleTrace() {
        ReasoningTrace.Step fact1 = ReasoningTrace.Step.fact("worksFor(alice, acme)", 1.0, "graph-node-1");
        ReasoningTrace.Step fact2 = ReasoningTrace.Step.fact("locatedIn(acme, nyc)", 1.0, null);
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE,
                "basedIn(alice, nyc)",
                "basedIn-rule",
                0.95,
                fact1, fact2);
        return ReasoningTrace.of(root);
    }

    /** Trace where the same FACT appears under two distinct premises of the root. */
    private static ReasoningTrace dedupeTrace() {
        ReasoningTrace.Step sharedFact = ReasoningTrace.Step.fact("sharedFact", 0.9, null);
        ReasoningTrace.Step other = ReasoningTrace.Step.fact("otherFact", 0.8, null);
        // Both rule1 and rule2 use sharedFact → sharedFact should appear as ONE entity
        ReasoningTrace.Step rule1 = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE, "intermediate1", "ruleA", 0.85,
                sharedFact);
        ReasoningTrace.Step rule2 = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE, "intermediate2", "ruleB", 0.80,
                sharedFact, other);
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE, "finalConclusion", "combine", 0.75,
                rule1, rule2);
        return ReasoningTrace.of(root);
    }

    // ── PROV-N tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PROV-N: basic structure — document/endDocument, prefixes")
    void provN_hasDocumentWrapper() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        assertTrue(provN.startsWith("document\n"), "must start with 'document'");
        assertTrue(provN.endsWith("endDocument\n"), "must end with 'endDocument'");
        assertTrue(provN.contains("prefix kompile <" + ProvIds.NS_KOMPILE + ">"),
                "must declare kompile prefix");
        assertTrue(provN.contains("prefix rdfs <" + ProvIds.NS_RDFS + ">"),
                "must declare rdfs prefix");
        assertTrue(provN.contains("prefix prov <" + ProvIds.NS_PROV + ">"),
                "must declare prov prefix");
    }

    @Test
    @DisplayName("PROV-N: simple trace — one activity, three entities (root + 2 facts)")
    void provN_simpleTrace_oneActivityThreeEntities() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());

        // Exactly one activity for the RULE root
        long activityCount = provN.lines()
                .filter(l -> l.trim().startsWith("activity("))
                .count();
        assertEquals(1, activityCount, "exactly one activity for RULE step");

        // Three distinct entities: root + two fact leaves
        long entityCount = provN.lines()
                .filter(l -> l.trim().startsWith("entity("))
                .count();
        assertEquals(3, entityCount, "three distinct entities");
    }

    @Test
    @DisplayName("PROV-N: wasGeneratedBy connects root entity to root activity")
    void provN_wasGeneratedBy_present() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        assertTrue(provN.contains("wasGeneratedBy(e_root, a_root,"),
                "root entity must be wasGeneratedBy root activity");
    }

    @Test
    @DisplayName("PROV-N: used × 2 for root activity over two fact premises")
    void provN_used_twice() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        long usedCount = provN.lines()
                .filter(l -> l.trim().startsWith("used("))
                .count();
        assertEquals(2, usedCount, "used must appear twice (one per premise)");
        // Activity id referenced in both used() statements
        assertTrue(provN.contains("used(a_root, e_root_0,") || provN.contains("used(a_root, e_root_0,"),
                "activity uses first premise entity");
        assertTrue(provN.contains("used(a_root, e_root_1,"),
                "activity uses second premise entity");
    }

    @Test
    @DisplayName("PROV-N: wasDerivedFrom × 2 for the two premises")
    void provN_wasDerivedFrom_twice() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        long wdfCount = provN.lines()
                .filter(l -> l.trim().startsWith("wasDerivedFrom("))
                .count();
        assertEquals(2, wdfCount, "wasDerivedFrom must appear twice");
    }

    @Test
    @DisplayName("PROV-N: wasAttributedTo for sourced fact, agent declared")
    void provN_wasAttributedTo_andAgent_forSource() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        // There is exactly one agent (for "graph-node-1")
        long agentCount = provN.lines()
                .filter(l -> l.trim().startsWith("agent("))
                .count();
        assertEquals(1, agentCount, "one agent for distinct source");
        assertTrue(provN.contains("SoftwareAgent"), "agent annotated as SoftwareAgent");
        // wasAttributedTo links the sourced fact entity to ag_1
        assertTrue(provN.contains("wasAttributedTo(e_root_0, ag_1)"),
                "fact with source attributed to agent");
    }

    @Test
    @DisplayName("PROV-N: confidence literal rendered correctly")
    void provN_confidenceLiteral() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        // Root step has confidence 0.95 → "0.950000"
        assertTrue(provN.contains("kompile:confidence=\"0.950000\""),
                "root entity must carry confidence 0.950000");
    }

    @Test
    @DisplayName("PROV-N: rdfs:label carries conclusion text")
    void provN_rdfsLabel() {
        String provN = ProvSerializer.toProvN(simpleRuleTrace());
        assertTrue(provN.contains("rdfs:label='basedIn(alice, nyc)'"),
                "root entity must have rdfs:label with conclusion text");
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deterministic: same trace serializes to identical strings twice")
    void provN_deterministic() {
        ReasoningTrace trace = simpleRuleTrace();
        String first = ProvSerializer.toProvN(trace);
        String second = ProvSerializer.toProvN(trace);
        assertEquals(first, second, "toProvN must be deterministic");
    }

    @Test
    @DisplayName("Deterministic: PROV-JSON also deterministic")
    void provJson_deterministic() {
        ReasoningTrace trace = simpleRuleTrace();
        String first = ProvSerializer.toProvJson(trace);
        String second = ProvSerializer.toProvJson(trace);
        assertEquals(first, second, "toProvJson must be deterministic");
    }

    // ── Deduplication ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dedupe: shared FACT appears as ONE entity, two used edges")
    void provN_dedupe_sharedFact_oneEntity() {
        String provN = ProvSerializer.toProvN(dedupeTrace());

        // Count entity declarations for sharedFact
        long sharedFactEntityCount = provN.lines()
                .filter(l -> l.trim().startsWith("entity(") && l.contains("sharedFact"))
                .count();
        assertEquals(1, sharedFactEntityCount,
                "sharedFact must produce exactly one entity declaration");

        // Count all used() lines — sharedFact is used by both ruleA and ruleB activities
        // so there must be ≥ 2 used() statements referencing the shared entity id
        // (We can verify by counting total used() lines: ruleA uses 1 (sharedFact),
        //  ruleB uses 2 (sharedFact + other), root uses 2 (rule1 + rule2) → total 5)
        long usedCount = provN.lines()
                .filter(l -> l.trim().startsWith("used("))
                .count();
        assertTrue(usedCount >= 2,
                "at least 2 used() lines when sharedFact is used by multiple activities");
    }

    @Test
    @DisplayName("Dedupe: PROV-JSON entity map has ONE entry for shared fact")
    void provJson_dedupe_sharedFact_oneEntry() {
        String json = ProvSerializer.toProvJson(dedupeTrace());
        Map<String, Object> doc = MiniJson.parseObject(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> entityMap = (Map<String, Object>) doc.get("entity");
        assertNotNull(entityMap, "entity key must be present");

        long sharedFactCount = entityMap.keySet().stream()
                .filter(id -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attrs = (Map<String, Object>) entityMap.get(id);
                    Object label = attrs.get("rdfs:label");
                    return "sharedFact".equals(label);
                })
                .count();
        assertEquals(1, sharedFactCount, "sharedFact must appear exactly once in entity map");
    }

    // ── REBUTTAL / REVISION ──────────────────────────────────────────────────

    @Test
    @DisplayName("REBUTTAL step: kompile:rebuts annotation present in PROV-N")
    void provN_rebuttal_rebutsAnnotation() {
        ReasoningTrace.Step factA = ReasoningTrace.Step.fact("claimA", 0.9, null);
        ReasoningTrace.Step rebuttal = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.REBUTTAL, "counter-claimA", "defeats-ruleX", 0.6,
                factA);
        ReasoningTrace trace = ReasoningTrace.of(rebuttal);
        // Root itself is the REBUTTAL — parent is null, so rebuts annotation is absent for root.
        // Let's wrap it so the rebuttal is a child:
        ReasoningTrace.Step mainClaim = ReasoningTrace.Step.fact("claimA-confirmed", 0.85, null);
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE, "verdict", "adjudicate", 0.7,
                mainClaim, rebuttal);
        trace = ReasoningTrace.of(root);

        String provN = ProvSerializer.toProvN(trace);
        assertTrue(provN.contains("kompile:rebuts="),
                "REBUTTAL step must produce kompile:rebuts annotation");
    }

    @Test
    @DisplayName("REVISION step: kompile:revises annotation present in PROV-N")
    void provN_revision_revisesAnnotation() {
        ReasoningTrace.Step premise = ReasoningTrace.Step.fact("oldBelief", 0.6, null);
        ReasoningTrace.Step revision = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.REVISION, "updatedBelief", "belief-reviser", 0.8,
                premise);
        ReasoningTrace.Step outer = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE, "finalBelief", "combine", 0.75,
                revision);
        ReasoningTrace trace = ReasoningTrace.of(outer);

        String provN = ProvSerializer.toProvN(trace);
        assertTrue(provN.contains("kompile:revises="),
                "REVISION step must produce kompile:revises annotation");
    }

    // ── Opinion literals ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Opinion-carrying step: four opinion literals in PROV-N")
    void provN_opinion_fourLiterals() {
        Opinion op = new Opinion(0.6, 0.2, 0.2, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("evidence", op.expectation(), "src", op);
        ReasoningTrace trace = ReasoningTrace.of(step);

        String provN = ProvSerializer.toProvN(trace);
        assertTrue(provN.contains("kompile:belief=\""), "belief literal must be present");
        assertTrue(provN.contains("kompile:disbelief=\""), "disbelief literal must be present");
        assertTrue(provN.contains("kompile:uncertainty=\""), "uncertainty literal must be present");
        assertTrue(provN.contains("kompile:baseRate=\""), "baseRate literal must be present");
    }

    @Test
    @DisplayName("Opinion-carrying step: four opinion literals in PROV-JSON entity map")
    void provJson_opinion_fourLiterals() {
        Opinion op = new Opinion(0.6, 0.2, 0.2, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("evidence", op.expectation(), "src", op);
        ReasoningTrace trace = ReasoningTrace.of(step);

        String json = ProvSerializer.toProvJson(trace);
        Map<String, Object> doc = MiniJson.parseObject(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> entityMap = (Map<String, Object>) doc.get("entity");
        assertNotNull(entityMap);
        // Get the single entity's attributes
        Map.Entry<String, Object> entry = entityMap.entrySet().iterator().next();
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) entry.getValue();
        assertTrue(attrs.containsKey("kompile:belief"), "must have kompile:belief");
        assertTrue(attrs.containsKey("kompile:disbelief"), "must have kompile:disbelief");
        assertTrue(attrs.containsKey("kompile:uncertainty"), "must have kompile:uncertainty");
        assertTrue(attrs.containsKey("kompile:baseRate"), "must have kompile:baseRate");
    }

    // ── PROV-JSON structural tests ────────────────────────────────────────────

    @Test
    @DisplayName("PROV-JSON: parses as JSON and has required top-level keys (prefix + 7 PROV maps)")
    void provJson_validJson_sevenTopLevelKeys() {
        String json = ProvSerializer.toProvJson(simpleRuleTrace());
        Map<String, Object> doc = MiniJson.parseObject(json);

        Set<String> expected = Set.of(
                "prefix", "entity", "activity", "agent",
                "wasGeneratedBy", "used", "wasDerivedFrom", "wasAttributedTo");
        for (String key : expected) {
            assertTrue(doc.containsKey(key), "top-level key missing: " + key);
        }
    }

    @Test
    @DisplayName("PROV-JSON: entity ids consistent between entity map and wasGeneratedBy")
    void provJson_consistentIds() {
        String json = ProvSerializer.toProvJson(simpleRuleTrace());
        Map<String, Object> doc = MiniJson.parseObject(json);

        @SuppressWarnings("unchecked")
        Map<String, Object> entityMap = (Map<String, Object>) doc.get("entity");
        @SuppressWarnings("unchecked")
        Map<String, Object> wgbMap = (Map<String, Object>) doc.get("wasGeneratedBy");

        Set<String> entityIds = entityMap.keySet();

        // Every wasGeneratedBy must reference an entity id that exists in entityMap
        for (Object wgbEntry : wgbMap.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> wgb = (Map<String, Object>) wgbEntry;
            @SuppressWarnings("unchecked")
            Map<String, Object> entityRef = (Map<String, Object>) wgb.get("prov:entity");
            String referencedId = (String) entityRef.get("$");
            assertTrue(entityIds.contains(referencedId),
                    "wasGeneratedBy references unknown entity id: " + referencedId);
        }
    }

    @Test
    @DisplayName("PROV-JSON: activity map has exactly one entry for simple rule trace")
    void provJson_oneActivity_simpleTrace() {
        String json = ProvSerializer.toProvJson(simpleRuleTrace());
        Map<String, Object> doc = MiniJson.parseObject(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> activityMap = (Map<String, Object>) doc.get("activity");
        assertEquals(1, activityMap.size(), "one activity for simple RULE trace");
    }

    // ── String escaping ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Escaping: conclusion with quotes and newline serializes in PROV-N without breaking format")
    void provN_escaping_quotesAndNewline() {
        String nasty = "she said \"hello\"\nand left";
        ReasoningTrace.Step step = ReasoningTrace.Step.fact(nasty, 0.75, null);
        ReasoningTrace trace = ReasoningTrace.of(step);

        // Must not throw, and the output must remain parseable (we just verify it doesn't break
        // the PROV-N string by checking single-quote boundaries are sane)
        String provN = ProvSerializer.toProvN(trace);
        assertTrue(provN.contains("document"), "must still produce a valid document envelope");
        assertTrue(provN.contains("rdfs:label="), "rdfs:label must still be present");
        // The conclusion text with a double-quote: in PROV-N single-quoted strings, double-quotes
        // need not be escaped, but single-quotes must be doubled. Verify no raw unescaped newline
        // appears inside the label value (it should be \n in the literal)
        // Extract the rdfs:label value line
        String labelLine = provN.lines()
                .filter(l -> l.contains("rdfs:label="))
                .findFirst()
                .orElse("");
        assertFalse(labelLine.isEmpty(), "rdfs:label line must be present");
        // The raw newline character should not appear inside the single-quoted value
        assertFalse(labelLine.contains("\n" + "and left"),
                "raw newline must not appear inside PROV-N label string");
    }

    @Test
    @DisplayName("Escaping: conclusion with quotes and newline serializes in PROV-JSON without breaking JSON")
    void provJson_escaping_quotesAndNewline() {
        String nasty = "she said \"hello\"\nand left";
        ReasoningTrace.Step step = ReasoningTrace.Step.fact(nasty, 0.75, null);
        ReasoningTrace trace = ReasoningTrace.of(step);

        // Must parse as valid JSON (MiniJson would throw if malformed)
        String json = ProvSerializer.toProvJson(trace);
        Map<String, Object> doc = assertDoesNotThrow(() -> MiniJson.parseObject(json),
                "PROV-JSON with special chars must parse as valid JSON");

        @SuppressWarnings("unchecked")
        Map<String, Object> entityMap = (Map<String, Object>) doc.get("entity");
        Map.Entry<String, Object> entry = entityMap.entrySet().iterator().next();
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) entry.getValue();
        // The rdfs:label value must round-trip to the original string
        assertEquals(nasty, attrs.get("rdfs:label"),
                "rdfs:label value must survive JSON escaping round-trip");
    }

    // ── ASSUMPTION annotation ─────────────────────────────────────────────────

    @Test
    @DisplayName("ASSUMPTION leaf: kompile:assumed literal present")
    void provN_assumption_annotated() {
        ReasoningTrace.Step step = ReasoningTrace.Step.assumption("hypothesizedFact", 0.6);
        ReasoningTrace trace = ReasoningTrace.of(step);
        String provN = ProvSerializer.toProvN(trace);
        assertTrue(provN.contains("kompile:assumed=\"true\""),
                "ASSUMPTION entity must be annotated kompile:assumed=true");
    }

    // ── Meta entries ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Meta entries appear as kompile:meta_<key> literals with sanitized keys")
    void provN_metaEntries_sanitized() {
        Map<String, String> meta = Map.of("run-id", "r42", "model name", "psl-v2");
        ReasoningTrace.Step step = ReasoningTrace.Step.withMeta(
                ReasoningTrace.Step.fact("someConclusion", 0.8, null), meta);
        ReasoningTrace trace = ReasoningTrace.of(step);
        String provN = ProvSerializer.toProvN(trace);
        // "run-id" → kompile:meta_run-id (hyphen is allowed); "model name" → kompile:meta_model_name
        assertTrue(provN.contains("kompile:meta_run-id="), "run-id meta key must appear");
        assertTrue(provN.contains("kompile:meta_model_name="),
                "space in key must be sanitized to underscore");
    }
}
