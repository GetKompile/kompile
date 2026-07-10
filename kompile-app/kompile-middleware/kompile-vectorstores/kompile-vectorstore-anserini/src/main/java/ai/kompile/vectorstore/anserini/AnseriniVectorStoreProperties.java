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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Managed-JSON configuration for the Anserini VectorStore implementation.
 * <p>
 * Configuration is loaded from {@code <dataDir>/config/vectorstore-anserini-config.json}
 * at startup and can be persisted back via {@link #persist()}. When the file is
 * absent the class field defaults are used unchanged.
 * </p>
 */
@Data
@Component
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnseriniVectorStoreProperties {

    private static final Logger log = LoggerFactory.getLogger(AnseriniVectorStoreProperties.class);
    private static final String CONFIG_FILENAME = "vectorstore-anserini-config.json";

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Path configFilePath;

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ObjectMapper objectMapper;

    /**
     * Set explicitly via the JSON config to customize.
     */
    private String indexPath;

    /**
     * Whether to enable the Anserini VectorStore.
     * Default is TRUE — enabled by default; set to false in the JSON config file to disable.
     */
    private boolean enabled = true;

    /**
     * Whether to enable persistent storage for the index.
     * If true (DEFAULT), the index path will be used as-is without appending a
     * unique JVM suffix.
     * This allows multiple processes (e.g., main app and subprocesses) to share the
     * same index,
     * ensuring semantic search with RAG always uses a consistent location.
     * If false, a JVM-unique suffix is appended to prevent lock conflicts during
     * testing.
     * Default: true (consistent location for production use)
     */
    private boolean persistenceEnabled = true;

    /**
     * Memory buffer size for IndexWriter in MB.
     * PERFORMANCE: Higher values reduce flush frequency, improving indexing speed.
     * Trade-off: More memory usage during indexing.
     * Recommended: 512-1024 for systems with 8GB+ RAM, 1024-2048 for 16GB+ RAM.
     * Default: 512 (increased from 256 for better bulk indexing performance)
     */
    private double memoryBufferSizeMb = 512.0;

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
     * Number of batch add operations before committing to the index.
     * PERFORMANCE: Higher values reduce commit overhead during bulk indexing.
     * Set to 1 for immediate durability (slower), or 10-50 for bulk indexing
     * (faster).
     * Note: A commit is always performed after the final batch in a bulk operation.
     * Default: 10 (commit every 10 batches for 10x fewer commits)
     */
    private int batchCommitInterval = 10;

    /**
     * Maximum number of documents to buffer before forcing a commit.
     * This provides a safety net to ensure commits happen even if batch count is
     * low.
     * Set to 0 to disable document-based commit triggering.
     * Default: 5000 (commit at least every 5000 documents)
     */
    private int maxDocumentsBeforeCommit = 5000;

    /**
     * HNSW parameters for hierarchical navigable small world indexing.
     * Only used if using HNSW instead of flat indexing.
     */
    private HnswParameters hnsw = new HnswParameters();

    public AnseriniVectorStoreProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.objectMapper = JsonUtils.newStandardMapper();
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        // Default the index path under the resolved data dir so a config-less project (golden-path
        // `project init` writes no vectorstore-anserini-config.json) still gets a durable, shared
        // index. 2026-07-05: with no default the graph-matrix subprocess ran MEMORY-ONLY — 727
        // node-persist failures and tableGraph NPEs — because indexPath stayed null. The JSON config
        // (loaded in init()) still overrides this value when present.
        this.indexPath = Paths.get(effectiveDataDir, "data", "indices", "anserini").toString();
        log.info("AnseriniVectorStoreProperties initialized, config path: {}, default indexPath: {}",
                configFilePath, indexPath);
    }

    /**
     * Loads the JSON config file on startup, overlaying values onto this instance.
     * When the file is absent all field defaults are kept as-is. Never throws.
     */
    @PostConstruct
    public void init() {
        if (!Files.exists(configFilePath)) {
            log.info("No Anserini vectorstore config found at {} — using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            // readerForUpdating calls setters on *this* for each present key;
            // absent keys keep their existing (default) values.
            objectMapper.readerForUpdating(this).readValue(json);
            log.info("Loaded Anserini vectorstore config from {}: enabled={}, indexPath={}, persistenceEnabled={}",
                    configFilePath, enabled, indexPath, persistenceEnabled);
        } catch (IOException e) {
            log.warn("Could not read Anserini vectorstore config from {} — using defaults: {}",
                    configFilePath, e.getMessage());
        }
    }

    /**
     * Persists the current field values to {@code <dataDir>/config/vectorstore-anserini-config.json}
     * as pretty-printed JSON. Parent directories are created if absent.
     */
    public void persist() {
        try {
            Path parent = configFilePath.getParent();
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created config directory: {}", parent);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            Files.writeString(configFilePath, json);
            log.info("Persisted Anserini vectorstore config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist Anserini vectorstore config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    @Data
    public static class HnswParameters {
        /**
         * HNSW M parameter - maximum number of bi-directional links.
         * PERFORMANCE: Lower values (8-12) = faster indexing, lower recall.
         * Higher values (24-32) = slower indexing, better recall.
         * Default: 16 (balanced)
         */
        private int m = 16;

        /**
         * HNSW efConstruction parameter - size of the dynamic candidate list.
         * PERFORMANCE: Lower values (50-100) = faster indexing, lower recall.
         * Higher values (200-500) = slower indexing, better recall.
         * For bulk indexing speed, use 50-100. For search quality, use 200+.
         * Default: 100 (balanced)
         */
        private int efConstruction = 100;

        /**
         * Whether to use HNSW indexing instead of flat indexing.
         * PERFORMANCE: HNSW is faster for large-scale search but slower to index.
         * Flat indexing is faster to index but O(n) search.
         * Recommendation: Use flat for < 100k docs, HNSW for > 100k docs.
         * Default: false (use flat indexing for simplicity)
         */
        private boolean enabled = false;
    }
}