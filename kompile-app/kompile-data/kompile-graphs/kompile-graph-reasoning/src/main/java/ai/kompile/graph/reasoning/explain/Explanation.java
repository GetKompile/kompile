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

import java.util.List;

/**
 * A natural-language explanation produced for a reasoning result — e.g. "why is entity X active?".
 *
 * <p>Store- and model-agnostic: the {@code summary} is free text from whatever strategy the
 * {@link ExplanationService} implementation uses (an LLM, a template, etc.), while
 * {@code supportingEntityIds} ties the prose back to concrete {@code GraphEntity} ids so callers can
 * highlight the evidence.</p>
 *
 * @param summary             human-readable explanation text
 * @param confidence          the explainer's confidence in {@code [0, 1]}
 * @param supportingEntityIds ids of the entities the explanation rests on, most relevant first
 */
public record Explanation(String summary, double confidence, List<String> supportingEntityIds) {

    public Explanation {
        summary = summary == null ? "" : summary;
        supportingEntityIds = supportingEntityIds == null ? List.of() : List.copyOf(supportingEntityIds);
    }

    /** An explanation with no supporting-entity attribution. */
    public static Explanation of(String summary, double confidence) {
        return new Explanation(summary, confidence, List.of());
    }

    /**
     * Convert this natural-language explanation into a single-conclusion {@link ReasoningTrace} whose
     * premises are the supporting entities (as {@link ReasoningTrace.StepKind#FACT} leaves).
     */
    public ReasoningTrace toReasoningTrace() {
        List<ReasoningTrace.Step> premises = new java.util.ArrayList<>();
        for (String id : supportingEntityIds) {
            premises.add(ReasoningTrace.Step.fact(id, 1.0, "entity"));
        }
        ReasoningTrace.Step root = new ReasoningTrace.Step(ReasoningTrace.StepKind.INFERENCE,
                summary, "explanation", ReasoningTrace.clamp01(confidence), null, premises,
                null, null);
        return ReasoningTrace.of(root);
    }
}
