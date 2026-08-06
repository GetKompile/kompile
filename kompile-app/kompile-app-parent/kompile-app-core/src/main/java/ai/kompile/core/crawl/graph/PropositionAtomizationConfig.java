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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Controls how one production crawl chunk is reduced to proposition-sized model tasks.
 *
 * <p>This is deliberately separate from document/retrieval chunking. Retrieval chunks preserve
 * enough surrounding context for indexing and graph reconciliation; proposition events bound one
 * small-model decision. A project or crawl can therefore tune either layer without silently
 * changing the other.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PropositionAtomizationConfig {

    public static final int DEFAULT_MAX_EVENT_CHARS = 1_200;
    public static final int DEFAULT_REFERENCE_CONTEXT_CHARS = 1_200;

    /** Boundary source used before the proposition model is called. */
    public enum Mode {
        /** Prefer a complete validated source-provided span plan, otherwise use the fallback segmenter. */
        AUTO,
        /** Always use the deterministic prose/structured-text fallback segmenter. */
        HEURISTIC,
        /** Use only a complete source-provided span plan; missing/invalid plans keep the chunk whole. */
        SOURCE_SPANS,
        /**
         * Give the complete production crawl chunk to the proposition model so it can perform
         * semantic atomization and return every independent assertion.
         */
        WHOLE_CHUNK
    }

    @Builder.Default
    private Mode mode = Mode.WHOLE_CHUNK;

    /** Maximum characters in one proposition focus event; values below 128 are clamped. */
    @Builder.Default
    private int maxEventChars = DEFAULT_MAX_EVENT_CHARS;

    /** Maximum preceding source characters retained as non-focus reference context. */
    @Builder.Default
    private int referenceContextChars = DEFAULT_REFERENCE_CONTEXT_CHARS;

    public static PropositionAtomizationConfig defaults() {
        return PropositionAtomizationConfig.builder().build();
    }

    /** Null-safe effective mode for legacy JSON and programmatic callers. */
    public Mode effectiveMode() {
        return mode == null ? Mode.WHOLE_CHUNK : mode;
    }

    /** Production floor prevents accidental single-token model tasks. */
    public int effectiveMaxEventChars() {
        return Math.max(128, maxEventChars);
    }

    public int effectiveReferenceContextChars() {
        return Math.max(0, referenceContextChars);
    }
}
