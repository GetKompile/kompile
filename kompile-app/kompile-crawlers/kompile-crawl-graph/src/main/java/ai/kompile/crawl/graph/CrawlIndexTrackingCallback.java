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

package ai.kompile.crawl.graph;

import java.util.List;
import java.util.Map;

/**
 * Callback for registering crawl-originated documents and passages in the
 * cross-index tracking tables. Implemented in kompile-app-main where
 * {@code CrossIndexTrackingService} is available.
 *
 * <p>This mirrors the pattern of {@link CrawlFactRegistrationCallback}:
 * defined in kompile-crawl-graph, implemented in kompile-app-main, and
 * injected optionally into {@code UnifiedCrawlGraphServiceImpl}.</p>
 */
public interface CrawlIndexTrackingCallback {

    /**
     * Register a source document and its chunked passages in the cross-index
     * tracking tables. Called once per source document after chunking completes.
     *
     * @param sourceId    unique identifier for the source (typically the URL or file path)
     * @param fileName    human-readable file name
     * @param factSheetId the fact sheet this document belongs to
     * @param passages    the chunked passages for this document
     * @return the number of passages registered
     */
    int registerDocumentAndPassages(String sourceId, String fileName,
                                    Long factSheetId, List<CrawlPassageInfo> passages);

    /**
     * Mark a batch of passages as vector-indexed. Called after a successful
     * {@code addVectorBatch()} call.
     *
     * @param chunkIds the chunk IDs that were successfully vector-indexed
     */
    void markPassagesVectorIndexed(List<String> chunkIds);

    /**
     * Mark a passage as graph-indexed. Called after a snippet or table node
     * is created in the knowledge graph.
     *
     * @param chunkId     the chunk ID
     * @param graphNodeId the graph node ID it was registered as
     */
    void markPassageGraphIndexed(String chunkId, String graphNodeId);

    /**
     * Update the document-level vector index status after all batches complete.
     *
     * @param sourceId       the source document identifier
     * @param factSheetId    the fact sheet ID
     * @param passageCount   number of passages vector-indexed
     */
    void markDocumentVectorIndexed(String sourceId, Long factSheetId, int passageCount);

    /**
     * Update the document-level graph status after graph registration completes.
     *
     * @param sourceId       the source document identifier
     * @param factSheetId    the fact sheet ID
     * @param nodeCount      number of graph nodes created
     */
    void markDocumentGraphIndexed(String sourceId, Long factSheetId, int nodeCount);

    /**
     * Describes a single chunked passage from the crawl pipeline.
     */
    record CrawlPassageInfo(
            /** Unique chunk ID */
            String chunkId,
            /** Index of this chunk within the parent document */
            int chunkIndex,
            /** Full text content of the chunk */
            String content,
            /** Metadata map (content_type, full_table_content, table_row_count, etc.) */
            Map<String, Object> metadata
    ) {}
}
