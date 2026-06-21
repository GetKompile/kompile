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

import ai.kompile.graph.reasoning.model.ReasoningGraph;

/**
 * Service-provider interface for turning a reasoning result into a human-readable
 * {@link Explanation}. This is the "explanations" seam of the library: the <em>contract</em> lives
 * here (store- and model-agnostic, over the generic {@link ReasoningGraph}), while the concrete
 * generation strategy is supplied by a client.
 *
 * <p>The reference implementation is {@code AttributionLlmService} in
 * {@code kompile-event-attribution}, which calls an LLM — it is intentionally <em>not</em> part of
 * this library, so the library carries no model or runtime dependency. Other implementations
 * (template-based, rule-based) can be plugged in behind the same interface.</p>
 */
public interface ExplanationService {

    /**
     * Explain why {@code targetEntityId} holds (e.g. is active / occurred) given {@code graph}.
     *
     * @param graph          the unified graph the reasoning ran over
     * @param targetEntityId the entity whose state is being explained
     * @param question       optional natural-language framing of the question; may be {@code null}
     * @return a natural-language explanation with supporting evidence
     */
    Explanation explain(ReasoningGraph graph, String targetEntityId, String question);
}
