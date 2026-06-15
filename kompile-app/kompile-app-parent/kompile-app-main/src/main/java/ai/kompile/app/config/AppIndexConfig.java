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
     * Supported vector store types.
     */
    public enum VectorStoreType {
        ANSERINI,    // Embedded Lucene-based (default)
        VESPA,       // Distributed Vespa server
        PGVECTOR,    // PostgreSQL with pgvector extension
        CHROMA       // Chroma vector database
    }

    /**
     * Display title for the application.
     */
    private String appTitle;

    /**
     * The type of vector store to use.
     * Default: ANSERINI (embedded)
     */
    private VectorStoreType vectorStoreType;

    /**
     * Path to the vector store index directory (for Anserini).
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

    // ═══════════════════════════════════════════════════════════════════════════
    // VESPA-SPECIFIC CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Vespa endpoint URL (e.g., http://localhost:8080).
     */
    private String vespaEndpoint;

    /**
     * Vespa document namespace.
     */
    private String vespaNamespace;

    /**
     * Vespa document type.
     */
    private String vespaDocumentType;

    /**
     * Vespa vector field name.
     */
    private String vespaVectorField;

    /**
     * Whether to enable Vespa hybrid search (BM25 + vector).
     */
    private Boolean vespaHybridSearchEnabled;

    /**
     * Vector weight for hybrid search (0.0 - 1.0).
     */
    private Double vespaHybridVectorWeight;

    // ═══════════════════════════════════════════════════════════════════════════
    // PGVECTOR-SPECIFIC CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * PostgreSQL connection URL for pgvector.
     */
    private String pgvectorUrl;

    /**
     * PostgreSQL username.
     */
    private String pgvectorUsername;

    /**
     * PostgreSQL password (note: consider using secrets management in production).
     */
    private String pgvectorPassword;

    /**
     * Table name for vector storage.
     */
    private String pgvectorTableName;

    // ═══════════════════════════════════════════════════════════════════════════
    // CHROMA-SPECIFIC CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Chroma server host.
     */
    private String chromaHost;

    /**
     * Chroma server port.
     */
    private Integer chromaPort;

    /**
     * Chroma collection name.
     */
    private String chromaCollectionName;

    /**
     * Creates a default configuration scoped to the given kompile data dir.
     * When {@code kompile.data.dir} is a project root (set by {@code project open}),
     * indices land under {@code <projectRoot>/data/indices/} — inside the project tree
     * so they can be committed via git-xet or LFS. When {@code dataDir} is the global
     * {@code ~/.kompile} default, indices go to {@code ~/.kompile/data/indices/}.
     */
    public static AppIndexConfig defaults(String dataDir) {
        String effectiveDataDir = (dataDir == null || dataDir.isBlank())
                ? System.getProperty("user.home") + "/.kompile"
                : dataDir;
        String baseDir = effectiveDataDir + "/data/indices";
        return AppIndexConfig.builder()
                .appTitle("Kompile")
                // Default to Anserini (embedded)
                .vectorStoreType(VectorStoreType.ANSERINI)
                .vectorStorePath(baseDir + "/vector_index")
                .keywordIndexPath(baseDir + "/default_index")
                .subprocessEnabled(true)
                .subprocessHeapSize("4g")
                .indexBatchSize(100)
                .adaptiveBatchSize(true)
                .embeddingTargetBatchSize(64)
                // Vespa defaults
                .vespaEndpoint("http://localhost:8080")
                .vespaNamespace("default")
                .vespaDocumentType("document")
                .vespaVectorField("embedding")
                .vespaHybridSearchEnabled(false)
                .vespaHybridVectorWeight(0.7)
                // pgvector defaults
                .pgvectorUrl("jdbc:postgresql://localhost:5432/kompile")
                .pgvectorUsername("postgres")
                .pgvectorPassword("")
                .pgvectorTableName("vector_store")
                // Chroma defaults
                .chromaHost("localhost")
                .chromaPort(8000)
                .chromaCollectionName("kompile")
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
                .appTitle(other.appTitle != null ? other.appTitle : this.appTitle)
                // Core settings
                .vectorStoreType(other.vectorStoreType != null ? other.vectorStoreType : this.vectorStoreType)
                .vectorStorePath(other.vectorStorePath != null ? other.vectorStorePath : this.vectorStorePath)
                .keywordIndexPath(other.keywordIndexPath != null ? other.keywordIndexPath : this.keywordIndexPath)
                .subprocessEnabled(other.subprocessEnabled != null ? other.subprocessEnabled : this.subprocessEnabled)
                .subprocessHeapSize(
                        other.subprocessHeapSize != null ? other.subprocessHeapSize : this.subprocessHeapSize)
                .indexBatchSize(other.indexBatchSize != null ? other.indexBatchSize : this.indexBatchSize)
                .adaptiveBatchSize(other.adaptiveBatchSize != null ? other.adaptiveBatchSize : this.adaptiveBatchSize)
                .embeddingTargetBatchSize(other.embeddingTargetBatchSize != null ? other.embeddingTargetBatchSize
                        : this.embeddingTargetBatchSize)
                // Vespa settings
                .vespaEndpoint(other.vespaEndpoint != null ? other.vespaEndpoint : this.vespaEndpoint)
                .vespaNamespace(other.vespaNamespace != null ? other.vespaNamespace : this.vespaNamespace)
                .vespaDocumentType(other.vespaDocumentType != null ? other.vespaDocumentType : this.vespaDocumentType)
                .vespaVectorField(other.vespaVectorField != null ? other.vespaVectorField : this.vespaVectorField)
                .vespaHybridSearchEnabled(other.vespaHybridSearchEnabled != null ? other.vespaHybridSearchEnabled : this.vespaHybridSearchEnabled)
                .vespaHybridVectorWeight(other.vespaHybridVectorWeight != null ? other.vespaHybridVectorWeight : this.vespaHybridVectorWeight)
                // pgvector settings
                .pgvectorUrl(other.pgvectorUrl != null ? other.pgvectorUrl : this.pgvectorUrl)
                .pgvectorUsername(other.pgvectorUsername != null ? other.pgvectorUsername : this.pgvectorUsername)
                .pgvectorPassword(other.pgvectorPassword != null ? other.pgvectorPassword : this.pgvectorPassword)
                .pgvectorTableName(other.pgvectorTableName != null ? other.pgvectorTableName : this.pgvectorTableName)
                // Chroma settings
                .chromaHost(other.chromaHost != null ? other.chromaHost : this.chromaHost)
                .chromaPort(other.chromaPort != null ? other.chromaPort : this.chromaPort)
                .chromaCollectionName(other.chromaCollectionName != null ? other.chromaCollectionName : this.chromaCollectionName)
                .build();
    }
}
