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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for vector indexing during a unified crawl.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VectorIndexConfig {

    /** Whether vector indexing is enabled (default: true) */
    @Builder.Default
    private boolean enabled = true;

    /** Target collection name for indexed documents */
    private String collectionName;

    /** Chunker to use (null = default) */
    private String chunkerName;

    /** Chunk size in tokens (null = use default) */
    private Integer chunkSize;

    /** Chunk overlap in tokens (null = use default) */
    private Integer chunkOverlap;

    /** Explicit embedding batch size (null = use model/provider optimal size) */
    private Integer embeddingBatchSize;

    /** Upper bound for adaptive embedding batch sizing (null = use model/provider max) */
    private Integer maxEmbeddingBatchSize;

    /** Whether to reduce embedding batches under heap pressure */
    @Builder.Default
    private boolean adaptiveBatching = true;
}
