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

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.CompositeReasoningTrail;
import ai.kompile.graph.reasoning.explain.Explanation;
import ai.kompile.graph.reasoning.explain.ModalityEvidence;
import ai.kompile.graph.reasoning.explain.ModalityKind;
import ai.kompile.graph.reasoning.explain.OpinionTree;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the consolidation: every "how was this concluded" type in the library converges onto the one
 * canonical {@link ReasoningTrace} via {@code toReasoningTrace()} — the FOL proof {@link DerivationTree}
 * inside a {@link ReasoningTrail}, a PSL/MEBN {@link ReasoningTrail}, a multi-modal
 * {@link CompositeReasoningTrail}, a subjective-logic {@link OpinionTree}, and a plain
 * {@link Explanation} — and the converged traces serialize into a {@code .kgraph}.
 */
class TraceConsolidationE2ETest {

    @Test
    void folTrailBecomesTheProofTree() {
        DerivationTree dt = new DerivationTree("basedIn(alice, nyc)", 1.0, "basedIn-rule", "fol",
                List.of(new DerivationTree("worksFor(alice, acme)", 1.0, null, "graph", List.of()),
                        new DerivationTree("locatedIn(acme, nyc)", 1.0, null, "graph", List.of())));
        ReasoningTrail trail = ReasoningTrail.builder("basedIn(alice, nyc)").derivationTree(dt).confidence(1.0).build();

        ReasoningTrace trace = trail.toReasoningTrace();
        assertEquals("basedIn(alice, nyc)", trace.conclusion().conclusion());
        assertEquals(3, trace.size());
        assertEquals(2, trace.leaves().size());
        assertTrue(trace.leaves().stream().allMatch(s -> s.kind() == StepKind.FACT));
    }

    @Test
    void pslTrailBecomesAnInferenceTrace() {
        EntailmentRecord e = new EntailmentRecord("isActive(alice)", 0.9, List.of(), List.of("R1"),
                Instant.now(), "run-1");
        ReasoningTrail trail = ReasoningTrail.builder("isActive(alice)")
                .confidence(0.8).inferenceMode("PSL")
                .entailments(List.of(e)).evidence(List.of("alice fired R1")).build();

        ReasoningTrace trace = trail.toReasoningTrace();
        assertEquals("isActive(alice)", trace.conclusion().conclusion());
        assertEquals(StepKind.INFERENCE, trace.conclusion().kind());
        assertEquals(0.8, trace.conclusion().confidence(), 1e-9);
        assertEquals(2, trace.premisesOf("isActive(alice)").size()); // 1 entailment + 1 evidence
        assertTrue(trace.contains("alice fired R1"));
    }

    @Test
    void nonFiniteConfidenceIsClampedNotThrown() {
        ReasoningTrail trail = ReasoningTrail.builder("x").confidence(Double.NaN).inferenceMode("MEBN").build();
        ReasoningTrace trace = trail.toReasoningTrace(); // must not throw despite NaN
        assertEquals(0.0, trace.conclusion().confidence(), 0.0);
    }

    @Test
    void compositeTrailBecomesAFusionTrace() {
        CompositeReasoningTrail composite = new CompositeReasoningTrail("alice", "why active?",
                List.of(ModalityEvidence.of(ModalityKind.PSL, 0.9, "PSL: active", List.of("rule1", "rule2")),
                        ModalityEvidence.of(ModalityKind.GRAPH_RAG, 0.7, "RAG: supporting doc", List.of("chunk-1"))),
                0.85, "Alice is active.", Instant.now(), "run-x");

        ReasoningTrace trace = composite.toReasoningTrace();
        assertEquals("Alice is active.", trace.conclusion().conclusion());
        assertEquals(StepKind.FUSION, trace.conclusion().kind());
        assertEquals(2, trace.premisesOf("Alice is active.").size(), "one step per modality");
        assertEquals(6, trace.size(), "root + 2 modalities + 3 detail facts");
        assertEquals(3, trace.leaves().size());
    }

    @Test
    void opinionTreeBecomesAFusionTrace() {
        OpinionTree ot = OpinionTree.fuseIndependent("answer", List.of(
                OpinionTree.leaf("src1", Opinion.fromBetaEvidence(8, 2)),
                OpinionTree.leaf("src2", Opinion.fromBetaEvidence(6, 4))));

        ReasoningTrace trace = ot.toReasoningTrace();
        assertEquals("answer", trace.conclusion().conclusion());
        assertEquals(StepKind.FUSION, trace.conclusion().kind());
        assertEquals(2, trace.leaves().size());
        assertTrue(trace.leaves().stream().allMatch(s -> s.kind() == StepKind.FACT));
        double c = trace.conclusion().confidence();
        assertTrue(c > 0.0 && c <= 1.0, "confidence is the projected expectation: " + c);
    }

    @Test
    void explanationBecomesAnInferenceTrace() {
        Explanation exp = new Explanation("alice is active because R1 fired", 0.8, List.of("alice", "R1"));
        ReasoningTrace trace = exp.toReasoningTrace();
        assertEquals("alice is active because R1 fired", trace.conclusion().conclusion());
        assertEquals(2, trace.leaves().size());
    }

    @Test
    void convergedTracesBundleAndRoundTripInAGraph() throws IOException {
        ReasoningTrace fromTrail = ReasoningTrail.builder("isActive(alice)").confidence(0.8)
                .inferenceMode("PSL").evidence(List.of("R1")).build().toReasoningTrace();
        ReasoningTrace fromOpinion = OpinionTree.fuseIndependent("answer",
                List.of(OpinionTree.leaf("s1", Opinion.fromBetaEvidence(9, 1)))).toReasoningTrace();

        UnifiedGraph g = new UnifiedGraph();
        g.putModel("trace:trail", fromTrail);
        g.putModel("trace:opinion", fromOpinion);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        assertEquals(fromTrail.toJson(), ((ReasoningTrace) back.model("trace:trail")).toJson());
        assertEquals(fromOpinion.toJson(), ((ReasoningTrace) back.model("trace:opinion")).toJson());
    }
}
