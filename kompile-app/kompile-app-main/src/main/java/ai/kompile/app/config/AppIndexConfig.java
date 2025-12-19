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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for index paths and related settings.
 * Persisted to JSON for retention across restarts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppIndexConfig {

    /**
     * Path to the vector store index directory.
     */
    private String vectorStorePath;

    /**
     * Path to the keyword (Lucene) index directory.
     */
    private String keywordIndexPath;

    /**
     * Whether subprocess indexing is enabled.
     */
    private Boolean subprocessEnabled;

    /**
     * Heap size for subprocess (e.g., "4g", "8g").
     */
    private String subprocessHeapSize;

    /**
     * Batch size for indexing operations.
     */
    private Integer indexBatchSize;

    /**
     * Whether to use adaptive batch sizing.
     */
    private Boolean adaptiveBatchSize;

    /**
     * Target batch size for embedding generation.
     */
    private Integer embeddingTargetBatchSize;

    /**
     * Creates a default configuration with system defaults.
     */
    public static AppIndexConfig defaults() {
        String homeDir = System.getProperty("user.home");
        return AppIndexConfig.builder()
                .vectorStorePath(homeDir + "/.kompile/anserini-vector-index")
                .keywordIndexPath(homeDir + "/.kompile/models/anserini/indexes/default_index")
                .subprocessEnabled(true)
                .subprocessHeapSize("4g")
                .indexBatchSize(100)
                .adaptiveBatchSize(true)
                .embeddingTargetBatchSize(64)
                .build();
    }

    /**
     * Merges non-null values from another config into this one.
     */
    public AppIndexConfig merge(AppIndexConfig other) {
        if (other == null) {
            return this;
        }
        return AppIndexConfig.builder()
                .vectorStorePath(other.vectorStorePath != null ? other.vectorStorePath : this.vectorStorePath)
                .keywordIndexPath(other.keywordIndexPath != null ? other.keywordIndexPath : this.keywordIndexPath)
                .subprocessEnabled(other.subprocessEnabled != null ? other.subprocessEnabled : this.subprocessEnabled)
                .subprocessHeapSize(other.subprocessHeapSize != null ? other.subprocessHeapSize : this.subprocessHeapSize)
                .indexBatchSize(other.indexBatchSize != null ? other.indexBatchSize : this.indexBatchSize)
                .adaptiveBatchSize(other.adaptiveBatchSize != null ? other.adaptiveBatchSize : this.adaptiveBatchSize)
                .embeddingTargetBatchSize(other.embeddingTargetBatchSize != null ? other.embeddingTargetBatchSize : this.embeddingTargetBatchSize)
                .build();
    }
}
