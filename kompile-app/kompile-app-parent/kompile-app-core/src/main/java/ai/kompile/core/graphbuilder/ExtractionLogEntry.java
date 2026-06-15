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
package ai.kompile.core.graphbuilder;

import java.time.Instant;
import java.util.List;

/**
 * Full transparency log entry for LLM-based extraction.
 * Contains the complete prompt, response, and parsed results.
 *
 * @param chunkId the chunk being processed
 * @param documentId the source document
 * @param prompt the complete LLM prompt
 * @param response the complete LLM response
 * @param parsedTriples the triples parsed from the response
 * @param modelProvider the LLM provider used
 * @param modelName the specific model used
 * @param latencyMs response latency in milliseconds
 * @param promptTokens number of prompt tokens
 * @param responseTokens number of response tokens
 * @param timestamp when this log entry was created
 * @param success whether extraction succeeded
 * @param errorMessage error message if extraction failed
 */
public record ExtractionLogEntry(
        String chunkId,
        String documentId,
        String prompt,
        String response,
        List<ProposedTriple> parsedTriples,
        String modelProvider,
        String modelName,
        Long latencyMs,
        Integer promptTokens,
        Integer responseTokens,
        Instant timestamp,
        Boolean success,
        String errorMessage
) {
    /**
     * Create a successful extraction log entry.
     */
    public static ExtractionLogEntry success(
            String chunkId, String documentId,
            String prompt, String response,
            List<ProposedTriple> parsedTriples,
            String modelProvider, String modelName,
            long latencyMs, Integer promptTokens, Integer responseTokens) {
        return new ExtractionLogEntry(
                chunkId, documentId,
                prompt, response, parsedTriples,
                modelProvider, modelName,
                latencyMs, promptTokens, responseTokens,
                Instant.now(), true, null
        );
    }

    /**
     * Create a failed extraction log entry.
     */
    public static ExtractionLogEntry failure(
            String chunkId, String documentId,
            String prompt, String errorMessage,
            String modelProvider, String modelName,
            long latencyMs) {
        return new ExtractionLogEntry(
                chunkId, documentId,
                prompt, null, List.of(),
                modelProvider, modelName,
                latencyMs, null, null,
                Instant.now(), false, errorMessage
        );
    }
}
