/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReasoningTraceJsonCodecTest {

    @Test
    void deterministicRoundTripPreservesStepsOpinionAndMetadata() {
        ReasoningTrace.Step leaf = ReasoningTrace.Step.fact(
                "observed", 0.8, "source", new Opinion(0.7, 0.1, 0.2, 0.5));
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.FUSION, "result", "fuse", 0.75,
                null, Map.of("z", "last", "a", "first"), List.of(leaf)));

        String json = ReasoningTraceJsonCodec.encode("process-trace:s1", "s1", trace);
        ReasoningTrace restored = ReasoningTraceJsonCodec.decode(json, "s1");

        assertEquals(trace.steps(), restored.steps());
        assertEquals(json, ReasoningTraceJsonCodec.encode("process-trace:s1", "s1", restored));
    }

    @Test
    void rejectsPathMismatchCyclesAndNonFiniteConfidence() {
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.fact("x", 1.0, "source"));
        String json = ReasoningTraceJsonCodec.encode("process-trace:s1", "s1", trace);
        assertThrows(IllegalArgumentException.class, () -> ReasoningTraceJsonCodec.decode(json, "other"));
        assertThrows(IllegalArgumentException.class, () -> ReasoningTraceJsonCodec.decode(
                json.replace("\"premiseIds\":[]", "\"premiseIds\":[\"s0\"]"), "s1"));
        assertThrows(IllegalArgumentException.class, () -> ReasoningTraceJsonCodec.decode(
                json.replace("\"confidence\":1.0", "\"confidence\":\"NaN\""), "s1"));
    }
}
