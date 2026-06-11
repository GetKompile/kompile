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

import java.util.ArrayList;
import java.util.List;

/**
 * Request to start a unified crawl-to-graph job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnifiedCrawlRequest {

    /** Human-readable name for this crawl job */
    private String name;

    /** Fact-sheet scope where graph/vector crawl output should be written */
    private Long factSheetId;

    /** Fact-sheet name to resolve ID from (used when factSheetId is not set) */
    private String factSheetName;

    /** List of sources to crawl */
    @Builder.Default
    private List<UnifiedCrawlSource> sources = new ArrayList<>();

    /** Graph extraction configuration (null or disabled = skip graph extraction) */
    private GraphExtractionConfig graphExtraction;

    /** Vector indexing configuration (null or disabled = skip vector indexing) */
    private VectorIndexConfig vectorIndex;

    /** Processing route configuration (null = use defaults from ~/.kompile/config/processing-route-config.json) */
    private ProcessingRouteConfig processingRoute;

    /** Per-request runtime overrides (null fields fall back to global config) */
    private RuntimeConfig runtimeConfig;

    /**
     * Per-request runtime configuration overrides.
     * Any non-null field overrides the corresponding global config value.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeConfig {
        /** Graph extraction parallelism (1-32). CLI/I/O-bound extraction benefits from 8-16. */
        private Integer graphExtractionParallelism;

        /** Max items per graph extraction batch */
        private Integer graphExtractionBatchSize;

        /** Target characters per graph extraction batch */
        private Integer graphExtractionTargetCharsPerBatch;

        /** Source loading parallelism (1-32) */
        private Integer sourceLoadParallelism;

        /** Chunking parallelism (1-32) */
        private Integer chunkingParallelism;

        /** Vector indexing batch size (0 = auto) */
        private Integer vectorBatchSize;

        /** Whether to sort chunks by estimated cost before batching */
        private Boolean costSortChunks;
    }
}
