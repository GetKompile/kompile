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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningTraceGapAnalyzerTest {

    @Test
    void flagsLowConfidenceAndMissingSupportForTrail() {
        ReasoningTrail trail = ReasoningTrail.builder("risk(account_1)")
                .inferenceMode("PSL")
                .confidence(0.35)
                .build();

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trail);

        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("LOW_CONFIDENCE")
                && gap.severity().equals("HIGH")
                && gap.relatedStepIds().contains("trace.root")));
        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("MISSING_SUPPORT")
                && gap.relatedStepIds().contains("trace.root")));
    }

    @Test
    void flagsMissingProvenanceForCanonicalTraceSupportSteps() {
        ReasoningTrace.Step factWithoutSource = new ReasoningTrace.Step(
                ReasoningTrace.StepKind.FACT,
                "invoice(account_1) is overdue",
                "observed",
                1.0,
                null,
                List.of(),
                null,
                null);
        ReasoningTrace trace = ReasoningTrace.of(new ReasoningTrace.Step(
                ReasoningTrace.StepKind.INFERENCE,
                "risk(account_1)",
                "risk_rule",
                0.7,
                "run-1",
                List.of(factWithoutSource),
                null,
                null));

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trace);

        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("MISSING_PROVENANCE")
                && gap.relatedStepIds().contains("trace.root.0")));
    }

    @Test
    void flagsCompositeModalityClarificationGaps() {
        CompositeReasoningTrail trail = new CompositeReasoningTrail(
                "account_1",
                "Why is the account risky?",
                List.of(
                        ModalityEvidence.of(ModalityKind.PSL, 0.8, "PSL fired", List.of()),
                        ModalityEvidence.of(ModalityKind.GRAPH_RAG, Double.NaN, "no signal", List.of())),
                0.8,
                "",
                Instant.parse("2026-07-07T00:00:00Z"),
                "run-1");

        List<ReasoningTraceGapAnalyzer.TraceGap> gaps = ReasoningTraceGapAnalyzer.traceGaps(trail);

        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("LOW_MODALITY_CORROBORATION")
                && gap.relatedStepIds().contains("trace.modality.0")));
        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("NO_SIGNAL_MODALITY")
                && gap.relatedStepIds().contains("trace.modality.1")));
        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("MISSING_MODALITY_DETAIL")
                && gap.relatedStepIds().contains("trace.modality.0")));
        assertTrue(gaps.stream().anyMatch(gap -> gap.gapType().equals("UNCERTAIN_EVIDENCE")
                && gap.relatedStepIds().contains("trace.modality.1")));
    }
}
