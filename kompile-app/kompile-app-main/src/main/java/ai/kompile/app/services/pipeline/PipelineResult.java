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

package ai.kompile.app.services.pipeline;

import java.util.List;

/**
 * Result of pipeline processing.
 *
 * @param documentsProcessed Number of documents processed
 * @param chunksCreated      Total chunks created from chunking
 * @param chunksIndexed      Total chunks indexed
 * @param totalTimeMs        Total processing time in milliseconds
 * @param indexedDocumentIds List of indexed document IDs
 */
public record PipelineResult(
        int documentsProcessed,
        int chunksCreated,
        int chunksIndexed,
        long totalTimeMs,
        List<String> indexedDocumentIds
) {
    /**
     * Alias for indexedDocumentIds() for backward compatibility.
     */
    public List<String> processedDocumentIds() {
        return indexedDocumentIds;
    }

    /**
     * Calculate chunks per second throughput.
     */
    public double getChunksPerSecond() {
        return totalTimeMs > 0 ? (chunksIndexed * 1000.0 / totalTimeMs) : 0;
    }
}
