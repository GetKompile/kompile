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
package ai.kompile.knowledgegraph.domain;

/**
 * Classifies the epistemological provenance of a graph edge, indicating how
 * the relationship was discovered.
 *
 * <p>Every edge in the knowledge graph carries one of these tags so that
 * downstream consumers (RAG queries, reports, visualisations) can distinguish
 * hard evidence from probabilistic inference.</p>
 */
public enum EdgeProvenance {

    /**
     * The relationship was directly found in the source material — e.g.
     * an explicit "X works at Y" statement in text, a foreign-key constraint
     * in SQL, or a class-extends declaration in code AST.
     */
    EXTRACTED,

    /**
     * The relationship was inferred by an LLM or algorithm with a confidence
     * score (0.0–1.0). Reasonable inference, but not directly stated.
     * Examples: embedding-similarity edges, LLM-proposed connections between
     * concepts mentioned in separate paragraphs.
     */
    INFERRED,

    /**
     * The relationship is uncertain and flagged for human review.
     * The system found evidence suggesting a link but confidence is below
     * the auto-accept threshold.
     */
    AMBIGUOUS
}
