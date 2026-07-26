/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.crawl.graph;

/**
 * How a chunk is handed to the extraction model.
 *
 * <p>The two modes produce the same artifact — a
 * {@link ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult} — so everything
 * downstream (validation, {@code toGraph}, merge, persistence, telemetry) is identical. They differ
 * only in how much the model is asked to decide in one breath.</p>
 */
public enum ExtractionMode {

    /**
     * One prompt per chunk: the model reads the text and returns the whole graph fragment. It is
     * simultaneously segmenting, resolving identity, judging assertability, choosing relation
     * types, and inventing ids — with no vocabulary to choose from and nothing to check it
     * against.
     */
    SINGLE_PASS,

    /**
     * The chunk is decomposed into bounded per-pass decisions
     * ({@link ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline}): propositions,
     * mention identity against retrieved candidates, epistemic class, relation-type selection
     * from an admissible set, and claim matching. The engine owns retrieval and every proposal
     * is checked against the source text before it is projected.
     */
    DECOMPOSED
}
