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

import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E3 — root-step metadata from ReasoningTrail.toReasoningTrace() in BOTH branches
 * (derivation-tree and entailments-only) and CompositeReasoningTrail.toReasoningTrace().
 */
@DisplayName("E3 Trace root metadata")
class TraceMetadataTest {

    // ── ReasoningTrail → entailments branch ───────────────────────────────────

    @Test
    void trail_entailmentsBranch_rootMetaContainsRunIdQuestionBreakdown() {
        EntailmentRecord ent = new EntailmentRecord(
                "risk(a)", 0.8, List.of("overdue(a)"), List.of("risk_rule"),
                Instant.parse("2026-07-01T00:00:00Z"), "run-E1");
        ReasoningTrail trail = ReasoningTrail.builder("risk(a)")
                .question("Is the account risky?")
                .inferenceMode("PSL")
                .confidence(0.8)
                .runId("run-E1")
                .computedAt(Instant.parse("2026-07-09T12:00:00Z"))
                .breakdown(ConfidenceBreakdown.ofPsl(0.82, 0.18))
                .entailments(List.of(ent))
                .activatedRules(List.of("risk_rule", "overdue_rule"))
                .build();

        ReasoningTrace trace = trail.toReasoningTrace();
        Map<String, String> meta = trace.conclusion().meta();

        assertNotNull(meta, "root step meta must not be null");
        assertEquals("run-E1", meta.get("runId"), "runId must be in root meta");
        assertEquals("2026-07-09T12:00:00Z", meta.get("computedAt"), "computedAt must be in root meta");
        assertEquals("Is the account risky?", meta.get("question"), "question must be in root meta");
        assertEquals("PSL", meta.get("inferenceMode"), "inferenceMode must be in root meta");
        // breakdown fields
        assertTrue(meta.containsKey("breakdown.pslSoftTruth"), "pslSoftTruth must be in meta");
        assertTrue(meta.containsKey("breakdown.distanceToSatisfaction"), "distanceToSatisfaction must be in meta");
        // non-NaN check
        assertFalse(meta.containsKey("breakdown.groundingConfidence"), "NaN fields must be omitted");
        // activatedRules.count (entailments branch only)
        assertEquals("2", meta.get("activatedRules.count"), "activatedRules.count must reflect rule count");
    }

    @Test
    void trail_entailmentsBranch_noActivatedRules_noCountKey() {
        ReasoningTrail trail = ReasoningTrail.builder("x")
                .question("q")
                .runId("r1")
                .computedAt(Instant.parse("2026-07-09T12:00:00Z"))
                .build();

        Map<String, String> meta = trail.toReasoningTrace().conclusion().meta();
        assertFalse(meta.containsKey("activatedRules.count"),
                "activatedRules.count must not appear when no rules fired");
    }

    // ── ReasoningTrail → derivation-tree branch ───────────────────────────────

    @Test
    void trail_derivationBranch_rootMetaAttachedToDerivationRoot() {
        DerivationTree tree = new DerivationTree("basedIn(alice, nyc)", 0.9, "rule1", "fol", List.of());
        ReasoningTrail trail = ReasoningTrail.builder("basedIn(alice, nyc)")
                .question("Where does Alice live?")
                .inferenceMode("GROUNDING")
                .confidence(0.9)
                .runId("run-D1")
                .computedAt(Instant.parse("2026-07-09T08:00:00Z"))
                .breakdown(ConfidenceBreakdown.ofGrounding(0.9))
                .derivationTree(tree)
                .build();

        ReasoningTrace trace = trail.toReasoningTrace();
        Map<String, String> meta = trace.conclusion().meta();

        assertNotNull(meta, "root step meta must not be null in derivation branch");
        assertEquals("run-D1", meta.get("runId"));
        assertEquals("2026-07-09T08:00:00Z", meta.get("computedAt"));
        assertEquals("Where does Alice live?", meta.get("question"));
        assertEquals("GROUNDING", meta.get("inferenceMode"));
        assertTrue(meta.containsKey("breakdown.groundingConfidence"), "groundingConfidence must be in meta");
        // Tree shape is preserved: the derivation root's conclusion must still be correct
        assertEquals("basedIn(alice, nyc)", trace.conclusion().conclusion());
        assertEquals(ReasoningTrace.StepKind.FACT, trace.conclusion().kind()); // isLeaf → FACT
    }

    // ── CompositeReasoningTrail → root meta ───────────────────────────────────

    @Test
    void compositeTrail_rootMetaContainsQuestionRunIdComputedAt() {
        CompositeReasoningTrail composite = new CompositeReasoningTrail(
                "alice",
                "Why is Alice flagged?",
                List.of(ModalityEvidence.of(ModalityKind.PSL, 0.85, "PSL result", List.of())),
                0.85,
                "",
                Instant.parse("2026-07-09T10:00:00Z"),
                "run-C1");

        ReasoningTrace trace = composite.toReasoningTrace();
        Map<String, String> rootMeta = trace.conclusion().meta();

        assertNotNull(rootMeta);
        assertEquals("Why is Alice flagged?", rootMeta.get("question"));
        assertEquals("run-C1", rootMeta.get("runId"));
        assertEquals("2026-07-09T10:00:00Z", rootMeta.get("computedAt"));
    }

    @Test
    void compositeTrail_modalityStepMeta_containsModality() {
        CompositeReasoningTrail composite = new CompositeReasoningTrail(
                "alice",
                "q",
                List.of(
                        ModalityEvidence.of(ModalityKind.PSL, 0.85, "PSL result", List.of()),
                        ModalityEvidence.of(ModalityKind.GRAPH_RAG, 0.70, "RAG result", List.of())),
                0.78,
                "",
                Instant.parse("2026-07-09T10:00:00Z"),
                "run-C2");

        ReasoningTrace trace = composite.toReasoningTrace();
        List<ReasoningTrace.Step> premises = trace.conclusion().premises();

        assertEquals(2, premises.size(), "should have 2 modality premises");
        assertEquals("PSL", premises.get(0).meta().get("modality"), "first modality step meta must say PSL");
        assertEquals("GRAPH_RAG", premises.get(1).meta().get("modality"), "second modality step meta must say GRAPH_RAG");
    }

    // ── ReasoningTraceRenderer header contains runId/computedAt ───────────────

    @Test
    void trailRenderer_headerIncludesRunId() {
        ReasoningTrail trail = ReasoningTrail.builder("x")
                .inferenceMode("PSL")
                .confidence(0.8)
                .runId("run-render-1")
                .computedAt(Instant.parse("2026-07-09T12:00:00Z"))
                .build();

        String ctx = ReasoningTraceRenderer.toLlmContext(trail);
        assertTrue(ctx.contains("runId=run-render-1"), "LLM context header must include runId");
        assertTrue(ctx.contains("computedAt=2026-07-09T12:00:00Z"), "LLM context header must include computedAt");
    }

    @Test
    void traceRenderer_headerIncludesRunIdFromMeta_whenPresent() {
        ReasoningTrace.Step root = ReasoningTrace.Step.withMeta(
                ReasoningTrace.Step.fact("conclusion", 0.9, null),
                Map.of("runId", "run-meta-1", "computedAt", "2026-07-09T12:00:00Z"));
        ReasoningTrace trace = ReasoningTrace.of(root);

        String ctx = ReasoningTraceRenderer.toLlmContext(trace);
        assertTrue(ctx.contains("runId=run-meta-1"), "trace LLM context must include runId from meta");
        assertTrue(ctx.contains("computedAt=2026-07-09T12:00:00Z"), "trace LLM context must include computedAt from meta");
    }

    @Test
    void compositeRenderer_headerIncludesRunIdAndComputedAt() {
        CompositeReasoningTrail composite = new CompositeReasoningTrail(
                "entity1", "q",
                List.of(ModalityEvidence.of(ModalityKind.PSL, 0.7, "s", List.of())),
                0.7, "", Instant.parse("2026-07-09T15:00:00Z"), "run-comp-3");

        String ctx = ReasoningTraceRenderer.toLlmContext(composite);
        assertTrue(ctx.contains("runId=run-comp-3"), "composite header must include runId");
        assertTrue(ctx.contains("computedAt=2026-07-09T15:00:00Z"), "composite header must include computedAt");
    }
}
