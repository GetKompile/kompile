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
package ai.kompile.core.citation;

import java.util.Map;

/**
 * Uniform citation object surfaced on every retrieval endpoint.
 *
 * <p>Fields are additive — callers must not depend on any specific field being non-null.
 * Use {@code CitationSupport.from()} (in kompile-knowledge-graph) to build instances
 * from document or graph-node metadata using {@code SourceMetadataConstants} and
 * {@code GraphProvenanceKeys}.</p>
 *
 * <p>This record lives in {@code kompile-app-core} so it is visible to all modules
 * without introducing an upward dependency.</p>
 */
public record CitationDto(
        /** Stable identifier for the source document (source_id / _sourceDocumentId). */
        String sourceId,

        /** Human-readable source name (filename, title, etc.). */
        String sourceName,

        /** 1-indexed page number within the source document; null if not applicable. */
        Integer pageNumber,

        /** 0-indexed chunk position within the document; null if not applicable. */
        Integer chunkIndex,

        /** Retrieval similarity or relevance score; null if not available. */
        Double score,

        /** Calibrated epistemic confidence in [0,1]; null if not available. */
        Double confidence,

        /** Basis type: STRUCTURAL | LLM_EXTRACTION | PSL_INFERENCE | … (_basisType). */
        String basisType,

        /** Crawl run / job id that produced the fact (_crawlRunId). */
        String crawlRunId,

        /** Source URL or file path (source_url / source_path). */
        String sourceUrl,

        /**
         * Provenance sub-map containing all {@code GraphProvenanceKeys.ALL} values
         * that were present in the metadata, with the leading underscore stripped.
         * Null when no provenance keys are found.
         */
        Map<String, Object> provenance
) {}
