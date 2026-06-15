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

package ai.kompile.core.filter;

/**
 * Defines the phases in the RAG pipeline where filters can be applied.
 * Filters are executed in order within each phase, sorted by priority.
 */
public enum FilterPhase {

    /**
     * Before query processing and retrieval.
     * Use for: Query rewriting, input validation, guardrails, authentication.
     */
    PRE_RETRIEVAL,

    /**
     * After retrieval, before context building.
     * Use for: Document filtering, reranking, relevance scoring, context enrichment.
     */
    POST_RETRIEVAL,

    /**
     * Before sending context and query to the LLM.
     * Use for: Prompt injection detection, context truncation, prompt formatting.
     */
    PRE_LLM,

    /**
     * After LLM response, before returning to user.
     * Use for: Output validation, hallucination detection, PII redaction, formatting.
     */
    POST_LLM
}
