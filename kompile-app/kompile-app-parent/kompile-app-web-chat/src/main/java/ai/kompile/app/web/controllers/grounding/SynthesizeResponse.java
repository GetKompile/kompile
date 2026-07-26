/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.explain.OpinionTree;

import java.util.List;

/**
 * Response body for POST /api/kb-grounding/synthesize (WP12c). Each answer carries its headline
 * likelihood, the subjective-logic opinion (belief/disbelief/uncertainty), and the {@link OpinionTree}
 * trace — the operator tree that produced the likelihood, so the answer is checkable, not asserted.
 */
public record SynthesizeResponse(
        String query,
        long factSheetId,
        int answerCount,
        List<Answer> answers
) {
    /** A single ranked, synthesized answer. */
    public record Answer(
            /** Human-readable title resolved server-side from {@link #entityId}. */
            String answer,
            /** Raw entity id — the canonical KB key. Always present alongside {@link #answer}. */
            String entityId,
            double likelihood,
            double belief,
            double disbelief,
            double uncertainty,
            OpinionTree trace
    ) {}
}
