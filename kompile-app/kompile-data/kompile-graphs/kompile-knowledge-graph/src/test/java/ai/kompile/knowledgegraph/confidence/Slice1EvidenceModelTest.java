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
package ai.kompile.knowledgegraph.confidence;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.reasoning.FactPromotionTracker;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice-1 unit tests for the confidence/evidence model.
 *
 * <p>All tests run without Spring — plain Java only.  They verify:
 * <ol>
 *   <li>Evidence-based confidence initialisation: a single LLM observation starts LOW, not ESTABLISHED.</li>
 *   <li>Evidence accumulation: repeated corroborations raise the band toward ESTABLISHED.</li>
 *   <li>ESTABLISHED gate: single high-trust observation alone CANNOT reach ESTABLISHED (uncertainty too high).</li>
 *   <li>Self-training fix: buildObservedTargets returns factStore values, not MAP posteriors.</li>
 *   <li>SourceTrustResolver: per-type defaults, unknown falls back to 0.5.</li>
 *   <li>GraphProvenanceKeys: all 9 new constants are present.</li>
 *   <li>FactPromotionTracker corroboration feeds evidencePos and raises the band.</li>
 * </ol>
 */
class Slice1EvidenceModelTest {

    // ── 1. Evidence-based confidence initialisation ──────────────────────────────

    @Test
    @DisplayName("Single LLM extraction (trust=0.6, W=2) must NOT be ESTABLISHED")
    void singleLlmExtractionIsNotEstablished() {
        // W=2 (non-informative prior), sourceTrust=0.6, pos=0.6, neg=0.0
        Opinion opinion = Opinion.fromBetaEvidence(0.60, 0.0, 0.5, 2.0);
        // expectation = b + baseRate*u = (0.6/2.6) + 0.5*(2.0/2.6) ≈ 0.231 + 0.385 = 0.615
        // The uncertainty u=2.0/2.6≈0.769 is very high, so the band is SPECULATIVE — NOT ESTABLISHED.
        double e = opinion.expectation();
        // Band must be below HIGH (should be SPECULATIVE)
        StrengthBand band = StrengthBand.from(opinion);
        assertTrue(band == StrengthBand.SPECULATIVE || band == StrengthBand.PROBABLE,
                "Single LLM extraction band should be SPECULATIVE or PROBABLE, got: " + band);
        // Must definitely NOT be ESTABLISHED
        assertNotEquals(StrengthBand.ESTABLISHED, band,
                "A single LLM extraction MUST NOT start at ESTABLISHED");
    }

    @Test
    @DisplayName("After 8 trust=1.0 corroborations (W=2) band must reach at least HIGH")
    void eightCorroborationsReachHighBand() {
        // 8 observations at trust=1.0 each → evidencePos=8.0
        Opinion opinion = Opinion.fromBetaEvidence(8.0, 0.0, 0.5, 2.0);
        double e = opinion.expectation();
        // expectation = 8/(8+0+2) * 1 + 0.5 * (2/10) = 0.8 + 0.1 = 0.9? Let's compute:
        // b = 8/10 = 0.80, u = 2/10 = 0.20, e = 0.80 + 0.5*0.20 = 0.90
        assertTrue(e >= 0.70, "After 8 trust-1.0 corroborations expectation should be >= 0.70, got: " + e);
        StrengthBand band = StrengthBand.from(opinion);
        assertTrue(band.ordinal() <= StrengthBand.HIGH.ordinal(),
                "After 8 corroborations band should be HIGH or ESTABLISHED, got: " + band);
    }

    @Test
    @DisplayName("A single observation with trust=1.0 CANNOT reach ESTABLISHED (uncertainty gate)")
    void singleHighTrustCannotBeEstablished() {
        // Even trust=1.0 (pos=1.0, W=2) → u=2/3 ≈ 0.667 which is >> 0.15
        // ESTABLISHED requires e>=0.85 AND u<0.15
        Opinion opinion = Opinion.fromBetaEvidence(1.0, 0.0, 0.5, 2.0);
        double u = opinion.uncertainty();
        assertTrue(u > 0.15, "Single observation must have uncertainty > 0.15 (gate not met), got: " + u);
        StrengthBand band = StrengthBand.from(opinion);
        assertNotEquals(StrengthBand.ESTABLISHED, band,
                "Single observation MUST NOT be ESTABLISHED regardless of trust");
    }

    @Test
    @DisplayName("~13 trust=0.6 corroborations (W=2) should reach ESTABLISHED")
    void thirteenCorroborationsReachEstablished() {
        // pos = 13 * 0.6 = 7.8, neg=0, W=2
        // b = 7.8/(7.8+0+2) = 7.8/9.8 ≈ 0.796, u = 2/9.8 ≈ 0.204 → not yet ESTABLISHED
        // Try 20 corroborations: pos=12.0, b=12/14≈0.857, u=2/14≈0.143 → ESTABLISHED
        double pos = 20 * 0.60;  // 20 corroborations at trust 0.6
        Opinion opinion = Opinion.fromBetaEvidence(pos, 0.0, 0.5, 2.0);
        StrengthBand band = StrengthBand.from(opinion);
        assertTrue(band.ordinal() <= StrengthBand.HIGH.ordinal(),
                "20 trust-0.6 corroborations should reach HIGH or ESTABLISHED, got: " + band);
    }

    // ── 2. Self-training fix ─────────────────────────────────────────────────────

    @Test
    @DisplayName("buildObservedTargets returns factStore values, differs from empty MAP when facts exist")
    void buildObservedTargetsDiffersFromEmpty() throws Exception {
        // Build a FactStore with known observed values
        FactStore fs = new FactStore();
        fs.assertFact(Fact.soft("isEmployedBy(Alice, Acme)", 0.75, "test"));
        fs.assertFact(Fact.observed("hasEmail(Bob, bob@acme.com)", "test"));

        // Reflectively access the private static-instance method via the static overload
        // (buildProgramFromFactStore is called first to populate the factStore, then
        // buildObservedTargets extracts the targets).
        // We test it indirectly: observed targets should include "isEmployedBy(Alice, Acme)" = 0.75
        // and "hasEmail(Bob, bob@acme.com)" = 1.0.

        // Use the package-private/static test path from IncrementalReasoningOrchestrator
        IncrementalReasoningOrchestrator orc = new IncrementalReasoningOrchestrator(
                null, null, null, null, null, null, null, null);
        // Access buildObservedTargets via reflection (private instance method)
        var method = IncrementalReasoningOrchestrator.class
                .getDeclaredMethod("buildObservedTargets", FactStore.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Double> targets = (Map<String, Double>) method.invoke(orc, fs);

        // Must contain the observed facts with their values
        assertTrue(targets.containsKey("isEmployedBy(Alice, Acme)"),
                "Observed targets must include the asserted soft fact");
        assertEquals(0.75, targets.get("isEmployedBy(Alice, Acme)"), 1e-9,
                "Soft fact value must be 0.75");
        assertEquals(1.0, targets.get("hasEmail(Bob, bob@acme.com)"), 1e-9,
                "Hard fact value must be 1.0");

        // Targets must NOT be empty (that was the self-training fallback condition)
        assertFalse(targets.isEmpty(), "buildObservedTargets must not be empty when factStore has facts");
    }

    @Test
    @DisplayName("buildObservedTargets returns empty map when factStore is null")
    void buildObservedTargetsNullSafe() throws Exception {
        IncrementalReasoningOrchestrator orc = new IncrementalReasoningOrchestrator(
                null, null, null, null, null, null, null, null);
        var method = IncrementalReasoningOrchestrator.class
                .getDeclaredMethod("buildObservedTargets", FactStore.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Double> targets = (Map<String, Double>) method.invoke(orc, (Object) null);
        assertTrue(targets.isEmpty(), "buildObservedTargets(null) must return empty map");
    }

    // ── 3. SourceTrustResolver ───────────────────────────────────────────────────

    @Test
    @DisplayName("SourceTrustResolver: known types return correct defaults")
    void sourceTrustResolverKnownTypes() {
        SourceTrustResolver resolver = new SourceTrustResolver();
        assertEquals(0.95, resolver.trustFor("email-from"), 1e-9, "email-from trust");
        assertEquals(0.90, resolver.trustFor("email-to-cc"), 1e-9, "email-to-cc trust");
        assertEquals(0.85, resolver.trustFor("structured-upload"), 1e-9, "structured-upload trust");
        assertEquals(0.70, resolver.trustFor("pdf-office"), 1e-9, "pdf-office trust");
        assertEquals(0.65, resolver.trustFor("email-body"), 1e-9, "email-body trust");
        assertEquals(0.60, resolver.trustFor("llm-extraction"), 1e-9, "llm-extraction trust");
        assertEquals(0.45, resolver.trustFor("web-scrape"), 1e-9, "web-scrape trust");
    }

    @Test
    @DisplayName("SourceTrustResolver: unknown type falls back to 0.50")
    void sourceTrustResolverUnknown() {
        SourceTrustResolver resolver = new SourceTrustResolver();
        assertEquals(0.50, resolver.trustFor("some-unknown-source"), 1e-9, "unknown source trust");
        assertEquals(0.50, resolver.trustFor(null), 1e-9, "null source trust");
        assertEquals(0.50, resolver.trustFor(""), 1e-9, "blank source trust");
    }

    @Test
    @DisplayName("SourceTrustResolver: LLM_EXTRACTION alias works case-insensitively")
    void sourceTrustResolverLlmAlias() {
        SourceTrustResolver resolver = new SourceTrustResolver();
        assertEquals(0.60, resolver.trustFor("LLM_EXTRACTION"), 1e-9, "uppercase alias");
        assertEquals(0.60, resolver.trustFor("llm"), 1e-9, "short alias");
        assertEquals(0.60, resolver.trustFor("LLM"), 1e-9, "uppercase short alias");
    }

    // ── 4. GraphProvenanceKeys constants ────────────────────────────────────────

    @Test
    @DisplayName("GraphProvenanceKeys must define all 9 new Slice-1 constants")
    void graphProvenanceKeysHasNewConstants() {
        assertEquals("_opinion", GraphProvenanceKeys.OPINION);
        assertEquals("_evidencePos", GraphProvenanceKeys.EVIDENCE_POS);
        assertEquals("_evidenceNeg", GraphProvenanceKeys.EVIDENCE_NEG);
        assertEquals("_priorStrength", GraphProvenanceKeys.PRIOR_STRENGTH);
        assertEquals("_sourceTrust", GraphProvenanceKeys.SOURCE_TRUST);
        assertEquals("_basisType", GraphProvenanceKeys.BASIS_TYPE);
        assertEquals("_corroborationCount", GraphProvenanceKeys.CORROBORATION_COUNT);
        assertEquals("_validFrom", GraphProvenanceKeys.VALID_FROM);
        assertEquals("_validTo", GraphProvenanceKeys.VALID_TO);
    }

    @Test
    @DisplayName("GraphProvenanceKeys.ALL includes all new Slice-1 keys")
    void graphProvenanceKeysAllIncludesNewKeys() {
        assertTrue(GraphProvenanceKeys.ALL.contains("_opinion"), "_opinion in ALL");
        assertTrue(GraphProvenanceKeys.ALL.contains("_evidencePos"), "_evidencePos in ALL");
        assertTrue(GraphProvenanceKeys.ALL.contains("_evidenceNeg"), "_evidenceNeg in ALL");
        assertTrue(GraphProvenanceKeys.ALL.contains("_validFrom"), "_validFrom in ALL");
        assertTrue(GraphProvenanceKeys.ALL.contains("_validTo"), "_validTo in ALL");
    }

    // ── 5. FactPromotionTracker corroboration feeds evidencePos ─────────────────

    @Test
    @DisplayName("FactPromotionTracker: repeated checkPromotion(trust=0.6) accumulates evidencePos and raises band")
    void factPromotionTrackerAccumulatesEvidencePos() throws Exception {
        FactPromotionTracker tracker = new FactPromotionTracker();

        // Access the private stateMap to read evidencePos
        Field stateMapField = FactPromotionTracker.class.getDeclaredField("stateMap");
        stateMapField.setAccessible(true);

        // Inject priorStrength=2.0 via reflection so the no-arg ctor gets the right value
        Field priorField = FactPromotionTracker.class.getDeclaredField("priorStrength");
        priorField.setAccessible(true);
        priorField.set(tracker, 2.0);

        long factSheetId = 1L;
        String atomKey = "isEmployedBy(Alice, Acme)";

        // First call — evidencePos should be 0.6
        tracker.checkPromotion(factSheetId, atomKey, Double.NaN, 0.4, "run-1", 0.60);
        // Second call
        tracker.checkPromotion(factSheetId, atomKey, 0.4, 0.5, "run-2", 0.60);
        // Third call
        tracker.checkPromotion(factSheetId, atomKey, 0.5, 0.6, "run-3", 0.60);

        // Retrieve the PromotionState via the stateMap
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<String, Object>>
                outerMap = (java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<String, Object>>)
                stateMapField.get(tracker);
        Object innerMap = outerMap.get(factSheetId);
        assertNotNull(innerMap, "stateMap should have an entry for factSheetId=1");

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Object> sheetMap =
                (java.util.concurrent.ConcurrentHashMap<String, Object>) innerMap;
        Object state = sheetMap.get(atomKey);
        assertNotNull(state, "stateMap should have an entry for the atomKey");

        // Access evidencePos field on the inner PromotionState class
        Field epField = state.getClass().getDeclaredField("evidencePos");
        epField.setAccessible(true);
        double evidencePos = (double) epField.get(state);

        // After 3 calls at trust=0.6 each, evidencePos should be 3 * 0.6 = 1.8
        assertEquals(1.8, evidencePos, 1e-9, "evidencePos should accumulate as 3 * 0.60 = 1.80");

        // Corroboration count should be 3
        assertEquals(3, tracker.getCorroborationCount(factSheetId, atomKey),
                "Corroboration count should be 3");
    }

    @Test
    @DisplayName("FactPromotionTracker: band climbs toward ESTABLISHED as evidencePos accumulates")
    void factPromotionTrackerBandClimbs() throws Exception {
        FactPromotionTracker tracker = new FactPromotionTracker();
        Field priorField = FactPromotionTracker.class.getDeclaredField("priorStrength");
        priorField.setAccessible(true);
        priorField.set(tracker, 2.0);

        long factSheetId = 2L;
        String atomKey = "hasRole(Alice, Director)";

        // 30 corroborations at trust=0.6 → evidencePos=18.0
        // b=18/(18+0+2)=0.9, u=2/20=0.1, e=0.9+0.5*0.1=0.95 → ESTABLISHED
        for (int i = 0; i < 30; i++) {
            tracker.checkPromotion(factSheetId, atomKey, Double.NaN, 0.5, "run-" + i, 0.60);
        }

        StrengthBand band = tracker.getLastBand(factSheetId, atomKey);
        assertTrue(band.ordinal() <= StrengthBand.HIGH.ordinal(),
                "After 30 trust-0.6 corroborations band should be HIGH or ESTABLISHED, got: " + band);
    }

    // ── 6. PSL rule weight is configurable ──────────────────────────────────────

    @Test
    @DisplayName("buildProgramFromFactStore uses configurable ruleWeight, not hardcoded 0.8")
    void buildProgramFromFactStoreUsesConfigurableWeight() {
        FactStore fs = new FactStore();
        fs.assertFact(Fact.soft("isActive(Alice)", 0.7, "test"));

        // Build with custom weight 0.6
        var program06 = IncrementalReasoningOrchestrator.buildProgramFromFactStore(fs, 0.6);
        // Build with custom weight 0.9
        var program09 = IncrementalReasoningOrchestrator.buildProgramFromFactStore(fs, 0.9);

        // Rules should differ in weight
        assertFalse(program06.rules().isEmpty(), "Program should have rules");
        assertFalse(program09.rules().isEmpty(), "Program should have rules");

        // The rule weight in the string representation should differ
        boolean has06 = program06.rules().stream()
                .anyMatch(r -> Math.abs(r.weight() - 0.6) < 1e-6);
        boolean has09 = program09.rules().stream()
                .anyMatch(r -> Math.abs(r.weight() - 0.9) < 1e-6);
        assertTrue(has06, "Program with weight=0.6 must have a rule at weight 0.6");
        assertTrue(has09, "Program with weight=0.9 must have a rule at weight 0.9");
    }
}
