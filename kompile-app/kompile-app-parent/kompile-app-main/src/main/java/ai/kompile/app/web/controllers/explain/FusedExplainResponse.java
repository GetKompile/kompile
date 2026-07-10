/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.explain;

import ai.kompile.graph.reasoning.explain.CompositeReasoningTrail;
import ai.kompile.graph.reasoning.explain.ModalityEvidence;
import ai.kompile.graph.reasoning.explain.ReasoningTraceGapAnalyzer;
import ai.kompile.graph.reasoning.explain.ReasoningTraceRenderer;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/explain/fused}.
 *
 * <p>Carries the full {@link CompositeReasoningTrail} plus flat summary fields so
 * callers can render quick overlays without traversing the nested modality list.</p>
 *
 * @param targetId              the target that was explained
 * @param fusedConfidence       blended confidence across all active modalities
 * @param modalityCount         total number of modalities that ran
 * @param activeModalityCount   number of modalities that produced a non-zero signal
 * @param naturalLanguageAnswer LLM synthesis answer (empty string when stubbed)
 * @param summaries             one-liner summary from each modality, in run order
 * @param computedAt            wall-clock timestamp
 * @param trail                 the full composite trail (all modality evidence)
 * @param llmContext            compact, bounded reasoning trace text for model prompts
 * @param attributionIndex      citeable trace steps for claim-level attribution
 * @param traceGaps             actionable open questions tied to trace step IDs
 */
public record FusedExplainResponse(
        String targetId,
        double fusedConfidence,
        int modalityCount,
        long activeModalityCount,
        String naturalLanguageAnswer,
        List<String> summaries,
        Instant computedAt,
        CompositeReasoningTrail trail,
        String llmContext,
        List<ReasoningTraceRenderer.AttributionStep> attributionIndex,
        List<ReasoningTraceGapAnalyzer.TraceGap> traceGaps
) {
    /** Build a response from the completed trail. */
    public static FusedExplainResponse fromTrail(CompositeReasoningTrail trail) {
        List<String> summaries = trail.modalities().stream()
                .map(ModalityEvidence::summary)
                .toList();
        return new FusedExplainResponse(
                trail.targetId(),
                trail.fusedConfidence(),
                trail.modalities().size(),
                trail.activeModalityCount(),
                trail.naturalLanguageAnswer(),
                summaries,
                trail.computedAt(),
                trail,
                ReasoningTraceRenderer.toLlmContext(trail, 40),
                ReasoningTraceRenderer.attributionIndex(trail),
                ReasoningTraceGapAnalyzer.traceGaps(trail)
        );
    }
}
