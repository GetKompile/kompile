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

package ai.kompile.vectorstore.vespa;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Vespa VectorStore implementation.
 * Only created when kompile.vectorstore.vespa.enabled=true is set.
 *
 * <p>Vespa is a distributed search and vector database that supports:</p>
 * <ul>
 *   <li>Hybrid search combining BM25 text search with vector similarity</li>
 *   <li>HNSW approximate nearest neighbor search</li>
 *   <li>Built-in embedding generation via embedders</li>
 *   <li>Horizontal scaling across multiple nodes</li>
 * </ul>
 *
 * <p>Note: Vespa requires a running server (Docker or Vespa Cloud) - it is not embedded like Lucene.</p>
 */
@Data
@ConfigurationProperties(prefix = "kompile.vectorstore.vespa")
public class VespaVectorStoreProperties {

    /**
     * Whether to enable the Vespa VectorStore.
     * Default is FALSE - must be explicitly enabled via property:
     * kompile.vectorstore.vespa.enabled=true
     */
    private boolean enabled = false;

    /**
     * Vespa endpoint URL for queries and document operations.
     * Example: http://localhost:8080
     */
    private String endpoint = "http://localhost:8080";

    /**
     * Vespa feed endpoint URL (if different from query endpoint).
     * If not specified, uses the main endpoint.
     * Example: http://localhost:8080
     */
    private String feedEndpoint;

    /**
     * Vespa document namespace.
     * Documents are organized as: namespace/documentType/documentId
     * Default: "default"
     */
    private String namespace = "default";

    /**
     * Vespa document type as defined in the schema.
     * This should match the 'document' definition in your Vespa schema.
     * Default: "document"
     */
    private String documentType = "document";

    /**
     * Name of the content cluster in Vespa.
     * Default: "content"
     */
    private String contentCluster = "content";

    /**
     * Name of the vector/embedding field in the Vespa schema.
     * This field should be defined as a tensor type in your schema.
     * Default: "embedding"
     */
    private String vectorField = "embedding";

    /**
     * Name of the content/text field in the Vespa schema.
     * Default: "content"
     */
    private String contentField = "content";

    /**
     * Name of the metadata field in the Vespa schema.
     * This should be a map or struct type for storing document metadata.
     * Default: "metadata"
     */
    private String metadataField = "metadata";

    /**
     * Vector embedding dimensions.
     * Must match the tensor dimensions in your Vespa schema.
     * Default: 768 (common for BERT-based models)
     */
    private int dimensions = 768;

    /**
     * Distance metric for vector similarity.
     * Options: angular (cosine), euclidean, innerproduct, hamming
     * Default: "angular" (equivalent to cosine similarity)
     */
    private String distanceMetric = "angular";

    /**
     * Target number of hits for approximate nearest neighbor search.
     * Higher values give better recall but slower queries.
     * Default: 100
     */
    private int targetHits = 100;

    /**
     * Number of candidates to explore during HNSW search.
     * Higher values give better recall but slower queries.
     * Default: 200
     */
    private int hnswExploreAdditionalHits = 200;

    /**
     * Connection configuration.
     */
    private ConnectionProperties connection = new ConnectionProperties();

    /**
     * TLS/SSL configuration for secure connections.
     */
    private TlsProperties tls = new TlsProperties();

    /**
     * Batch feeding configuration.
     */
    private BatchProperties batch = new BatchProperties();

    /**
     * Hybrid search configuration.
     */
    private HybridSearchProperties hybridSearch = new HybridSearchProperties();

    @Data
    public static class ConnectionProperties {
        /**
         * Connection timeout in milliseconds.
         * Default: 5000 (5 seconds)
         */
        private int connectionTimeoutMs = 5000;

        /**
         * Request/response timeout in milliseconds.
         * Default: 30000 (30 seconds)
         */
        private int requestTimeoutMs = 30000;

        /**
         * Maximum number of concurrent connections per destination.
         * Default: 8
         */
        private int maxConnections = 8;

        /**
         * Maximum number of retries for failed requests.
         * Default: 3
         */
        private int maxRetries = 3;

        /**
         * Minimum time between retries in milliseconds.
         * Default: 1000 (1 second)
         */
        private int retryDelayMs = 1000;
    }

    @Data
    public static class TlsProperties {
        /**
         * Whether to use TLS/SSL for connections.
         * Required for Vespa Cloud.
         * Default: false
         */
        private boolean enabled = false;

        /**
         * Path to the CA certificate file.
         */
        private String caCertificatePath;

        /**
         * Path to the client certificate file.
         * Required for mTLS authentication.
         */
        private String clientCertificatePath;

        /**
         * Path to the client private key file.
         * Required for mTLS authentication.
         */
        private String clientKeyPath;

        /**
         * Whether to skip hostname verification (NOT recommended for production).
         * Default: false
         */
        private boolean skipHostnameVerification = false;
    }

    @Data
    public static class BatchProperties {
        /**
         * Maximum number of documents per batch feed operation.
         * Default: 100
         */
        private int maxDocumentsPerBatch = 100;

        /**
         * Maximum number of concurrent feed operations.
         * Default: 4
         */
        private int maxConcurrentOperations = 4;

        /**
         * Whether to use async feeding for better throughput.
         * Default: true
         */
        private boolean asyncFeeding = true;

        /**
         * Grace period in seconds to wait for pending operations on shutdown.
         * Default: 30
         */
        private int gracePeriodSeconds = 30;
    }

    @Data
    public static class HybridSearchProperties {
        /**
         * Whether to enable hybrid search (combining vector + text).
         * Default: false (vector-only search)
         */
        private boolean enabled = false;

        /**
         * Weight for vector similarity in hybrid scoring (0.0 - 1.0).
         * Text weight = 1.0 - vectorWeight
         * Default: 0.7 (70% vector, 30% text)
         */
        private double vectorWeight = 0.7;

        /**
         * Name of the text field to use for BM25 matching.
         * Default: "content" (same as contentField)
         */
        private String textField = "content";

        /**
         * Ranking profile to use for hybrid search.
         * Default: "hybrid"
         */
        private String rankingProfile = "hybrid";
    }

    /**
     * Returns the effective feed endpoint, defaulting to the main endpoint if not specified.
     */
    public String getEffectiveFeedEndpoint() {
        return feedEndpoint != null && !feedEndpoint.isBlank() ? feedEndpoint : endpoint;
    }
}
