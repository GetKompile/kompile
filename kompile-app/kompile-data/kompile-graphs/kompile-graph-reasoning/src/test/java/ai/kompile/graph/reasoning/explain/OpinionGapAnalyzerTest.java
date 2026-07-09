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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E3 — opinion-based gap types: UNCERTAIN_OPINION, CONTESTED_EVIDENCE.
 * Also verifies that scalar-only low-confidence steps still yield LOW_CONFIDENCE.
 */
@DisplayName("E3 Opinion gap analysis")
class OpinionGapAnalyzerTest {

    // ── UNCERTAIN_OPINION (u ≥ 0.6) ──────────────────────────────────────────

    @Test
    void highUncertainty_yieldsUncertainOpinionGap() {
        // u=0.8 → "evidence is thin"
        Opinion thinEvidence = new Opinion(0.1, 0.1, 0.8, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("claim", thinEvidence.expectation(), "src", thinEvidence);
        ReasoningTrace trace = ReasoningTrace.of(step);

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);

        assertTrue(gaps.stream().anyMatch(g -> "UNCERTAIN_OPINION".equals(g.gapType())),
                "high-uncertainty opinion should produce UNCERTAIN_OPINION gap");
        // UNCERTAIN_OPINION must NOT also produce LOW_CONFIDENCE for opinion-carrying steps
        assertFalse(gaps.stream().anyMatch(g -> "LOW_CONFIDENCE".equals(g.gapType())),
                "opinion-carrying step should not produce LOW_CONFIDENCE (suppressed by UNCERTAIN_OPINION)");
    }

    @Test
    void exactBoundary_u060_yieldsUncertainOpinionGap() {
        // u=0.6 is exactly at the threshold
        Opinion atBoundary = new Opinion(0.2, 0.2, 0.6, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("claim", atBoundary.expectation(), "src", atBoundary);
        ReasoningTrace trace = ReasoningTrace.of(step);

        assertTrue(ReasoningTraceGapAnalyzer.traceGaps(trace).stream()
                .anyMatch(g -> "UNCERTAIN_OPINION".equals(g.gapType())));
    }

    // ── CONTESTED_EVIDENCE (b ≥ 0.3 AND d ≥ 0.3) ────────────────────────────

    @Test
    void contestedOpinion_yieldsContestedEvidenceGap() {
        // b=0.45, d=0.45, u=0.1 → both sides well-supported
        Opinion contested = new Opinion(0.45, 0.45, 0.1, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("contested-claim", contested.expectation(), "src", contested);
        ReasoningTrace trace = ReasoningTrace.of(step);

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);

        assertTrue(gaps.stream().anyMatch(g -> "CONTESTED_EVIDENCE".equals(g.gapType())),
                "(b=0.45, d=0.45) should yield CONTESTED_EVIDENCE");
    }

    @Test
    void atBoundary_b030_d030_yieldsContestedGap() {
        // Exactly at threshold: b=0.3, d=0.3, u=0.4
        Opinion atBoundary = new Opinion(0.3, 0.3, 0.4, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("c", atBoundary.expectation(), "src", atBoundary);
        ReasoningTrace trace = ReasoningTrace.of(step);

        assertTrue(ReasoningTraceGapAnalyzer.traceGaps(trace).stream()
                .anyMatch(g -> "CONTESTED_EVIDENCE".equals(g.gapType())));
    }

    // ── Sibling conflict() ≥ 0.25 ────────────────────────────────────────────

    @Test
    void siblingConflict_yieldsContestedEvidenceGap() {
        // Two opposing leaf opinions: high conflict()
        Opinion pro = new Opinion(0.8, 0.1, 0.1, 0.5);   // believes
        Opinion con = new Opinion(0.1, 0.8, 0.1, 0.5);   // disbelieves
        // conflict(pro, con) = pro.belief * con.disbelief + pro.disbelief * con.belief
        //                     = 0.8*0.1 + 0.1*0.8 = 0.16 — NOT ≥ 0.25; use stronger pair
        Opinion strongPro = new Opinion(0.7, 0.1, 0.2, 0.5);
        Opinion strongCon = new Opinion(0.1, 0.7, 0.2, 0.5);
        // conflict = 0.7*0.7 + 0.1*0.1 = 0.49 + 0.01 = 0.50 ≥ 0.25

        double conflictScore = strongPro.conflict(strongCon);
        assertTrue(conflictScore >= 0.25, "test prerequisite: conflict must be ≥ 0.25, got " + conflictScore);

        ReasoningTrace.Step premise1 = ReasoningTrace.Step.fact("evidence-for", strongPro.expectation(), "src1", strongPro);
        ReasoningTrace.Step premise2 = ReasoningTrace.Step.fact("evidence-against", strongCon.expectation(), "src2", strongCon);
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE, "conclusion", "psl", 0.55, List.of(premise1, premise2));
        ReasoningTrace trace = ReasoningTrace.of(root);

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);
        assertTrue(gaps.stream().anyMatch(g -> "CONTESTED_EVIDENCE".equals(g.gapType())),
                "sibling opinions with conflict() ≥ 0.25 must yield CONTESTED_EVIDENCE");
    }

    // ── Scalar-only low-confidence step still yields LOW_CONFIDENCE ──────────

    @Test
    void scalarOnly_lowConfidence_yieldsLowConfidenceGap() {
        // No opinion attached — scalar confidence 0.35 < 0.60 → LOW_CONFIDENCE
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("claim", 0.35, "src");
        ReasoningTrace trace = ReasoningTrace.of(step);

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);

        assertTrue(gaps.stream().anyMatch(g -> "LOW_CONFIDENCE".equals(g.gapType())),
                "scalar-only low-confidence step must produce LOW_CONFIDENCE gap");
        assertFalse(gaps.stream().anyMatch(g -> "UNCERTAIN_OPINION".equals(g.gapType())),
                "scalar-only step must not produce UNCERTAIN_OPINION");
    }

    @Test
    void scalarOnly_highConfidence_noLowConfidenceGap() {
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("claim", 0.85, "src");
        ReasoningTrace trace = ReasoningTrace.of(step);
        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);
        assertFalse(gaps.stream().anyMatch(g -> "LOW_CONFIDENCE".equals(g.gapType())));
        assertFalse(gaps.stream().anyMatch(g -> "UNCERTAIN_OPINION".equals(g.gapType())));
    }

    // ── Low-uncertainty opinion does NOT yield UNCERTAIN_OPINION ─────────────

    @Test
    void wellSupportedOpinion_neitherGapType() {
        // u=0.1 (well-supported, not contested) — should yield no opinion gap
        Opinion wellSupported = new Opinion(0.8, 0.1, 0.1, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("claim", wellSupported.expectation(), "src", wellSupported);
        ReasoningTrace trace = ReasoningTrace.of(step);

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);
        assertFalse(gaps.stream().anyMatch(g -> "UNCERTAIN_OPINION".equals(g.gapType())));
        assertFalse(gaps.stream().anyMatch(g -> "CONTESTED_EVIDENCE".equals(g.gapType())));
        assertFalse(gaps.stream().anyMatch(g -> "LOW_CONFIDENCE".equals(g.gapType())),
                "high-confidence opinion must not yield LOW_CONFIDENCE");
    }

    // ── Renderer includes [b= d= u=] annotation for opinion-carrying steps ───

    @Test
    void renderer_opinionAnnotation_appearsInLlmContext() {
        Opinion op = new Opinion(0.6, 0.2, 0.2, 0.5);
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("alice_active", op.expectation(), "graph", op);
        ReasoningTrace trace = ReasoningTrace.of(step);

        String ctx = ReasoningTraceRenderer.toLlmContext(trace);
        assertTrue(ctx.contains("[b="), "renderer must include belief annotation");
        assertTrue(ctx.contains("d="), "renderer must include disbelief annotation");
        assertTrue(ctx.contains("u="), "renderer must include uncertainty annotation");
    }

    @Test
    void renderer_noOpinionAnnotation_whenOpinionAbsent() {
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("x", 0.8, "src");
        ReasoningTrace trace = ReasoningTrace.of(step);
        String ctx = ReasoningTraceRenderer.toLlmContext(trace);
        assertFalse(ctx.contains("[b="), "renderer must not add opinion annotation when absent");
    }
}
