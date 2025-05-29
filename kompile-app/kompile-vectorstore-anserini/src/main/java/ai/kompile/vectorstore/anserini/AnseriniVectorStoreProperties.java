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

package ai.kompile.vectorstore.anserini;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Anserini VectorStore implementation.
 * Provides sensible defaults so it works out of the box without configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "kompile.vectorstore.anserini")
public class AnseriniVectorStoreProperties {

    /**
     * Path to the Lucene index directory for storing vectors.
     * Default: "./anserini-vector-index"
     */
    private String indexPath = "./anserini-vector-index";

    /**
     * Whether to enable the Anserini VectorStore.
     * Changed default to TRUE so it works out of the box.
     */
    private boolean enabled = true;

    /**
     * Memory buffer size for IndexWriter in MB.
     * Default: 256
     */
    private double memoryBufferSizeMb = 256.0;

    /**
     * Whether to use compound files for the index.
     * Default: false (better for performance)
     */
    private boolean useCompoundFile = false;

    /**
     * Vector similarity function to use.
     * Options: COSINE, DOT_PRODUCT, EUCLIDEAN
     * Default: COSINE
     */
    private String similarityFunction = "COSINE";

    /**
     * Maximum number of dimensions supported.
     * Default: 4096
     */
    private int maxDimensions = 4096;

    /**
     * Whether to quantize vectors to int8 for storage efficiency.
     * Default: false
     */
    private boolean quantizeInt8 = false;

    /**
     * HNSW parameters for hierarchical navigable small world indexing.
     * Only used if using HNSW instead of flat indexing.
     */
    private HnswParameters hnsw = new HnswParameters();

    @Data
    public static class HnswParameters {
        /**
         * HNSW M parameter - maximum number of bi-directional links.
         * Default: 16
         */
        private int m = 16;

        /**
         * HNSW efConstruction parameter - size of the dynamic candidate list.
         * Default: 100
         */
        private int efConstruction = 100;

        /**
         * Whether to use HNSW indexing instead of flat indexing.
         * Default: false (use flat indexing for simplicity)
         */
        private boolean enabled = false;
    }
}