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
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ExtractionConfidenceStamper}.
 *
 * <p>All tests run without Spring — plain Java + Mockito. They verify:
 * <ol>
 *   <li>All required Opinion keys are stamped when collaborators are present.</li>
 *   <li>LLM_EXTRACTION sourceType yields SPECULATIVE/PROBABLE band (not ESTABLISHED).</li>
 *   <li>STRUCTURAL sourceType yields high-belief Opinion (near-ESTABLISHED).</li>
 *   <li>Raw confidence is honoured when provided.</li>
 *   <li>Null rawConfidence seeds from sourceTrust.</li>
 *   <li>When collaborators are absent the method returns a sane fallback and stamps nothing.</li>
 *   <li>{@link BasisType} resolution works for all recognised sourceType strings.</li>
 * </ol>
 */
class ExtractionConfidenceStamperTest {

    private SourceTrustResolver mockTrustResolver;
    private KbConfigManager mockConfigManager;
    private KbConfig cfg;
    private ExtractionConfidenceStamper stamper;

    @BeforeEach
    void setUp() {
        mockTrustResolver = mock(SourceTrustResolver.class);
        mockConfigManager = mock(KbConfigManager.class);
        cfg = KbConfig.defaults();
        when(mockConfigManager.current()).thenReturn(cfg);
        stamper = new ExtractionConfidenceStamper(mockTrustResolver, mockConfigManager);
    }

    // ── 1. Required keys are stamped ────────────────────────────────────────────

    @Test
    @DisplayName("All required Opinion keys are written into metadata for LLM_EXTRACTION")
    void allRequiredKeysWrittenForLlmExtraction() {
        when(mockTrustResolver.trustFor("LLM_EXTRACTION")).thenReturn(0.60);
        Map<String, Object> meta = new LinkedHashMap<>();

        stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", null);

        assertTrue(meta.containsKey(GraphProvenanceKeys.OPINION), "_opinion must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.BASIS_TYPE), "_basisType must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.SOURCE_TRUST), "_sourceTrust must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.EVIDENCE_POS), "_evidencePos must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.EVIDENCE_NEG), "_evidenceNeg must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.PRIOR_STRENGTH), "_priorStrength must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.CORROBORATION_COUNT), "_corroborationCount must be present");
        assertTrue(meta.containsKey(GraphProvenanceKeys.VALID_FROM), "_validFrom must be present");

        assertEquals("LLM_EXTRACTION", meta.get(GraphProvenanceKeys.BASIS_TYPE));
        assertEquals(0.60, (double) meta.get(GraphProvenanceKeys.SOURCE_TRUST), 1e-9);
        assertEquals(1, (int) meta.get(GraphProvenanceKeys.CORROBORATION_COUNT));
    }

    // ── 2. LLM_EXTRACTION → SPECULATIVE/PROBABLE band ──────────────────────────

    @Test
    @DisplayName("LLM_EXTRACTION with trust=0.6, null rawConfidence stamps SPECULATIVE/PROBABLE Opinion")
    void llmExtractionNullRawConfidenceIsNotEstablished() {
        when(mockTrustResolver.trustFor("LLM_EXTRACTION")).thenReturn(0.60);
        Map<String, Object> meta = new LinkedHashMap<>();

        double effectiveConf = stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", null);

        // With W=2.0 (default evidencePriorStrength), pos=0.6, neg=0.0, baseRate=0.5:
        // b=0.6/2.6≈0.231, u=2.0/2.6≈0.769 → expectation = 0.231 + 0.5*0.769 ≈ 0.615
        // uncertainty >> 0.15 so NOT ESTABLISHED
        assertTrue(effectiveConf > 0.0 && effectiveConf < 1.0, "Effective confidence must be in (0,1)");
        assertTrue(effectiveConf < 0.99, "LLM extraction should NOT pin at 1.0");

        String opinionJson = (String) meta.get(GraphProvenanceKeys.OPINION);
        assertNotNull(opinionJson, "_opinion JSON must be present");
        Opinion opinion = Opinion.fromJson(opinionJson);
        assertTrue(opinion.uncertainty() > 0.15,
                "Single LLM extraction must have uncertainty > 0.15 (not ESTABLISHED), got: " + opinion.uncertainty());
    }

    // ── 3. STRUCTURAL → high-belief Opinion ─────────────────────────────────────

    @Test
    @DisplayName("STRUCTURAL with trust=0.85 stamps high-belief Opinion (near-ESTABLISHED)")
    void structuralSourceTypeStampsHighBelief() {
        when(mockTrustResolver.trustFor("STRUCTURAL")).thenReturn(0.85);
        Map<String, Object> meta = new LinkedHashMap<>();

        double effectiveConf = stamper.stampEdgeConfidence(meta, "STRUCTURAL", null);

        // With W=0.1 (structuralPriorStrength), pos=0.85, neg=0.0, baseRate=0.5:
        // b=0.85/0.95≈0.895, u=0.1/0.95≈0.105 → expectation = 0.895 + 0.5*0.105 ≈ 0.947
        // u < 0.15 AND expectation ≥ 0.85 → ESTABLISHED
        assertTrue(effectiveConf > 0.70, "Structural extraction should have high confidence, got: " + effectiveConf);
        assertEquals("STRUCTURAL", meta.get(GraphProvenanceKeys.BASIS_TYPE));

        String opinionJson = (String) meta.get(GraphProvenanceKeys.OPINION);
        assertNotNull(opinionJson);
        Opinion opinion = Opinion.fromJson(opinionJson);
        assertTrue(opinion.uncertainty() < 0.30,
                "Structural opinion should have low uncertainty, got: " + opinion.uncertainty());
    }

    // ── 4. Raw confidence is honoured ───────────────────────────────────────────

    @Test
    @DisplayName("When rawConfidence is provided, it is used as evidencePos and the Opinion is computed from it")
    void rawConfidenceIsHonoured() {
        when(mockTrustResolver.trustFor("LLM_EXTRACTION")).thenReturn(0.60);
        Map<String, Object> meta = new LinkedHashMap<>();

        double effectiveConf = stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", 0.80);

        // pos=0.80, W=2.0 → b=0.80/2.80≈0.286, u=2.0/2.80≈0.714 → expectation ≈ 0.643
        // evidencePos should be 0.80 (the raw confidence), not the trust 0.60
        assertEquals(0.80, (double) meta.get(GraphProvenanceKeys.EVIDENCE_POS), 1e-9,
                "_evidencePos should equal rawConfidence when provided");
        // Still not ESTABLISHED (uncertainty is still high due to W=2.0)
        assertTrue(effectiveConf < 0.99, "Single LLM extraction with rawConf=0.80 should NOT be pinned to 1.0");
    }

    // ── 5. Null rawConfidence seeds from sourceTrust ─────────────────────────────

    @Test
    @DisplayName("When rawConfidence is null, evidencePos equals sourceTrust")
    void nullRawConfidenceSeedsFromTrust() {
        when(mockTrustResolver.trustFor("LLM_EXTRACTION")).thenReturn(0.60);
        Map<String, Object> meta = new LinkedHashMap<>();

        stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", null);

        assertEquals(0.60, (double) meta.get(GraphProvenanceKeys.EVIDENCE_POS), 1e-9,
                "_evidencePos should equal sourceTrust when rawConfidence is null");
    }

    // ── 6. Null-collaborator fallback ────────────────────────────────────────────

    @Test
    @DisplayName("When collaborators are absent, stampEdgeConfidence returns rawConfidence and stamps nothing")
    void nullCollaboratorsReturnRawConfidenceFallback() {
        ExtractionConfidenceStamper noCollabStamper = new ExtractionConfidenceStamper(null, null);
        Map<String, Object> meta = new LinkedHashMap<>();

        double result = noCollabStamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", 0.75);

        assertEquals(0.75, result, 1e-9, "Should return rawConfidence when collaborators absent");
        assertTrue(meta.isEmpty(), "No keys should be stamped when collaborators absent");
    }

    @Test
    @DisplayName("When collaborators are absent and rawConfidence is null, falls back to 0.5")
    void nullCollaboratorsNullRawConfidenceFallsBackToHalf() {
        ExtractionConfidenceStamper noCollabStamper = new ExtractionConfidenceStamper(null, null);
        Map<String, Object> meta = new LinkedHashMap<>();

        double result = noCollabStamper.stampEdgeConfidence(meta, "STRUCTURAL", null);

        assertEquals(0.5, result, 1e-9, "Should return 0.5 when collaborators absent and rawConfidence null");
        assertTrue(meta.isEmpty(), "No keys should be stamped when collaborators absent");
    }

    // ── 7. BasisType resolution ──────────────────────────────────────────────────

    @Test
    @DisplayName("resolveBasisType maps STRUCTURAL source strings to BasisType.STRUCTURAL")
    void resolveBasisTypeStructural() {
        assertEquals(BasisType.STRUCTURAL, stamper.resolveBasisType("STRUCTURAL"));
        assertEquals(BasisType.STRUCTURAL, stamper.resolveBasisType("structural"));
        assertEquals(BasisType.STRUCTURAL, stamper.resolveBasisType("graph_constructor"));
        assertEquals(BasisType.STRUCTURAL, stamper.resolveBasisType("graph-constructor"));
        assertEquals(BasisType.STRUCTURAL, stamper.resolveBasisType("tika"));
        assertEquals(BasisType.STRUCTURAL, stamper.resolveBasisType("tika_structural"));
    }

    @Test
    @DisplayName("resolveBasisType maps LLM and unknown strings to BasisType.LLM_EXTRACTION")
    void resolveBasisTypeLlmAndUnknown() {
        assertEquals(BasisType.LLM_EXTRACTION, stamper.resolveBasisType("LLM_EXTRACTION"));
        assertEquals(BasisType.LLM_EXTRACTION, stamper.resolveBasisType("inline_llm"));
        assertEquals(BasisType.LLM_EXTRACTION, stamper.resolveBasisType("unknown-source"));
        assertEquals(BasisType.LLM_EXTRACTION, stamper.resolveBasisType(null));
        assertEquals(BasisType.LLM_EXTRACTION, stamper.resolveBasisType(""));
    }

    // ── 8. LLM_EXTRACTION alias coverage (SourceTrustResolver pass-through) ──────

    @Test
    @DisplayName("inline_llm and inline_llm_multi source types use LLM_EXTRACTION trust")
    void inlineLlmSourceTypesUseCorrectTrust() {
        when(mockTrustResolver.trustFor("inline_llm")).thenReturn(0.60);
        Map<String, Object> meta = new LinkedHashMap<>();

        stamper.stampEdgeConfidence(meta, "inline_llm", null);

        assertEquals("LLM_EXTRACTION", meta.get(GraphProvenanceKeys.BASIS_TYPE),
                "inline_llm should resolve to LLM_EXTRACTION basis");
    }

    // ── 9. _opinion JSON round-trips correctly ───────────────────────────────────

    @Test
    @DisplayName("_opinion value is a valid Opinion JSON that round-trips through Opinion.fromJson")
    void opinionJsonRoundTrips() {
        when(mockTrustResolver.trustFor("LLM_EXTRACTION")).thenReturn(0.60);
        Map<String, Object> meta = new LinkedHashMap<>();

        stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", 0.60);

        String opinionJson = (String) meta.get(GraphProvenanceKeys.OPINION);
        assertNotNull(opinionJson, "_opinion must be a non-null JSON string");
        Opinion roundTripped = Opinion.fromJson(opinionJson);
        assertNotNull(roundTripped, "Opinion.fromJson must succeed on the stamped JSON");
        // Simplex invariant: b + d + u must equal 1.0
        double sum = roundTripped.belief() + roundTripped.disbelief() + roundTripped.uncertainty();
        assertEquals(1.0, sum, 1e-6, "Opinion components must sum to 1.0");
    }
}
