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

package ai.kompile.app.subprocess;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Arguments passed to the ingest subprocess.
 * These are serialized to a temporary JSON file and the subprocess reads from
 * that file.
 * This avoids command-line length limits and escaping issues.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubprocessArgs(
        /** Unique task identifier for tracking and logging */
        String taskId,

        /** Path to the file to be ingested */
        String filePath,

        /** Name of the loader to use (null for auto-detect) */
        String loaderName,

        /** Name of the chunker to use (null for auto-detect) */
        String chunkerName,

        /** Chunk size for text chunking */
        int chunkSize,

        /** Chunk overlap for text chunking */
        int chunkOverlap,

        /** Embedding batch size */
        int embeddingBatchSize,

        /** Path to the index directory (legacy/combined) */
        String indexPath,

        /** Vector store index path */
        String vectorStorePath,

        /** Keyword index path */
        String keywordIndexPath,

        /** Base URL for HTTP callbacks to main app (e.g., "http://localhost:8080") */
        String callbackBaseUrl,

        /** ND4J environment configuration as JSON string */
        String nd4jConfigJson,

        /** Path for checkpoint storage (optional) */
        String checkpointPath,

        /** Whether to resume from existing checkpoint */
        boolean resume,

        // === Model Source Configuration ===

        /**
         * Model source type: "staging" for remote staging, "archive" for local archive
         */
        String modelSourceType,

        /** Model identifier to use for embedding */
        String modelIdentifier,

        /** Staging service URL (when modelSourceType is "staging") */
        String stagingUrl,

        /** Staging service API key (when modelSourceType is "staging") */
        String stagingApiKey,

        /** Path to archive file (when modelSourceType is "archive") */
        String archivePath,

        // === Memory Monitoring Configuration ===

        /**
         * Memory threshold percentage (0-100) at which to trigger graceful stop.
         * Default: 80
         */
        int memoryThresholdPercent,

        /** Memory critical percentage (0-100) at which to pause and GC. Default: 90 */
        int memoryCriticalPercent,

        /**
         * Memory kill threshold percentage (0-100) at which to forcibly terminate.
         * Default: 95. Set to 0 to disable.
         */
        int memoryKillThresholdPercent,

        /** Memory check interval in milliseconds. Default: 2000 */
        long memoryCheckIntervalMs,

        /** Additional options as key-value pairs */
        Map<String, Object> options) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Default chunk size when not specified.
     */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * Default chunk overlap when not specified.
     */
    public static final int DEFAULT_CHUNK_OVERLAP = 50;

    /**
     * Default embedding batch size when not specified.
     */
    public static final int DEFAULT_EMBEDDING_BATCH_SIZE = 32;

    /**
     * Default memory threshold percentage at which to trigger graceful stop.
     */
    public static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 80;

    /**
     * Default memory critical percentage at which to pause and GC.
     */
    public static final int DEFAULT_MEMORY_CRITICAL_PERCENT = 90;

    /**
     * Default memory kill threshold percentage at which to forcibly terminate.
     */
    public static final int DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT = 95;

    /**
     * Default memory check interval in milliseconds.
     */
    public static final long DEFAULT_MEMORY_CHECK_INTERVAL_MS = 2000;

    /**
     * Create SubprocessArgs with default values for unspecified parameters.
     */
    public static SubprocessArgs create(
            String taskId,
            String filePath,
            String loaderName,
            String chunkerName,
            String indexPath,
            String callbackBaseUrl,
            String nd4jConfigJson,
            String checkpointPath,
            boolean resume) {
        return new SubprocessArgs(
                taskId,
                filePath,
                loaderName,
                chunkerName,
                DEFAULT_CHUNK_SIZE,
                DEFAULT_CHUNK_OVERLAP,
                DEFAULT_EMBEDDING_BATCH_SIZE,
                indexPath, // Maintain as combined for now if needed, but we'll prefer specialized ones
                indexPath, // vectorStorePath
                indexPath, // keywordIndexPath
                callbackBaseUrl,
                nd4jConfigJson,
                checkpointPath,
                resume,
                null, // modelSourceType
                null, // modelIdentifier
                null, // stagingUrl
                null, // stagingApiKey
                null, // archivePath
                DEFAULT_MEMORY_THRESHOLD_PERCENT,
                DEFAULT_MEMORY_CRITICAL_PERCENT,
                DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT,
                DEFAULT_MEMORY_CHECK_INTERVAL_MS,
                new HashMap<>());
    }

    /**
     * Builder for SubprocessArgs.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Write arguments to a temporary JSON file.
     *
     * @return Path to the temporary file
     * @throws IOException if writing fails
     */
    public Path writeToTempFile() throws IOException {
        Path tempFile = Files.createTempFile("ingest-args-" + taskId + "-", ".json");
        OBJECT_MAPPER.writeValue(tempFile.toFile(), this);
        return tempFile;
    }

    /**
     * Read arguments from a JSON file.
     *
     * @param filePath Path to the JSON file
     * @return Parsed SubprocessArgs
     * @throws IOException if reading or parsing fails
     */
    public static SubprocessArgs readFromFile(Path filePath) throws IOException {
        return OBJECT_MAPPER.readValue(filePath.toFile(), SubprocessArgs.class);
    }

    /**
     * Read arguments from a JSON string.
     *
     * @param json JSON string
     * @return Parsed SubprocessArgs
     * @throws IOException if parsing fails
     */
    public static SubprocessArgs fromJson(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, SubprocessArgs.class);
    }

    /**
     * Convert to JSON string.
     *
     * @return JSON representation
     * @throws IOException if serialization fails
     */
    public String toJson() throws IOException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    /**
     * Get an option value with a default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOption(String key, T defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * Builder for SubprocessArgs.
     */
    public static class Builder {
        private String taskId;
        private String filePath;
        private String loaderName;
        private String chunkerName;
        private int chunkSize = DEFAULT_CHUNK_SIZE;
        private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;
        private int embeddingBatchSize = DEFAULT_EMBEDDING_BATCH_SIZE;
        private String indexPath;
        private String vectorStorePath;
        private String keywordIndexPath;
        private String callbackBaseUrl;
        private String nd4jConfigJson;
        private String checkpointPath;
        private boolean resume = false;
        // Model source config
        private String modelSourceType;
        private String modelIdentifier;
        private String stagingUrl;
        private String stagingApiKey;
        private String archivePath;
        // Memory monitoring config
        private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        private int memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        private int memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        private long memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        private Map<String, Object> options = new HashMap<>();

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder loaderName(String loaderName) {
            this.loaderName = loaderName;
            return this;
        }

        public Builder chunkerName(String chunkerName) {
            this.chunkerName = chunkerName;
            return this;
        }

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder chunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
            return this;
        }

        public Builder embeddingBatchSize(int embeddingBatchSize) {
            this.embeddingBatchSize = embeddingBatchSize;
            return this;
        }

        public Builder indexPath(String indexPath) {
            this.indexPath = indexPath;
            return this;
        }

        public Builder vectorStorePath(String vectorStorePath) {
            this.vectorStorePath = vectorStorePath;
            return this;
        }

        public Builder keywordIndexPath(String keywordIndexPath) {
            this.keywordIndexPath = keywordIndexPath;
            return this;
        }

        public Builder callbackBaseUrl(String callbackBaseUrl) {
            this.callbackBaseUrl = callbackBaseUrl;
            return this;
        }

        public Builder nd4jConfigJson(String nd4jConfigJson) {
            this.nd4jConfigJson = nd4jConfigJson;
            return this;
        }

        public Builder checkpointPath(String checkpointPath) {
            this.checkpointPath = checkpointPath;
            return this;
        }

        public Builder resume(boolean resume) {
            this.resume = resume;
            return this;
        }

        public Builder modelSourceType(String modelSourceType) {
            this.modelSourceType = modelSourceType;
            return this;
        }

        public Builder modelIdentifier(String modelIdentifier) {
            this.modelIdentifier = modelIdentifier;
            return this;
        }

        public Builder stagingUrl(String stagingUrl) {
            this.stagingUrl = stagingUrl;
            return this;
        }

        public Builder stagingApiKey(String stagingApiKey) {
            this.stagingApiKey = stagingApiKey;
            return this;
        }

        public Builder archivePath(String archivePath) {
            this.archivePath = archivePath;
            return this;
        }

        public Builder memoryThresholdPercent(int memoryThresholdPercent) {
            this.memoryThresholdPercent = Math.max(0, Math.min(memoryThresholdPercent, 100));
            return this;
        }

        public Builder memoryCriticalPercent(int memoryCriticalPercent) {
            this.memoryCriticalPercent = Math.max(0, Math.min(memoryCriticalPercent, 100));
            return this;
        }

        public Builder memoryKillThresholdPercent(int memoryKillThresholdPercent) {
            this.memoryKillThresholdPercent = Math.max(0, Math.min(memoryKillThresholdPercent, 100));
            return this;
        }

        public Builder memoryCheckIntervalMs(long memoryCheckIntervalMs) {
            this.memoryCheckIntervalMs = Math.max(500, memoryCheckIntervalMs);
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options = options != null ? new HashMap<>(options) : new HashMap<>();
            return this;
        }

        public Builder option(String key, Object value) {
            if (this.options == null) {
                this.options = new HashMap<>();
            }
            this.options.put(key, value);
            return this;
        }

        public SubprocessArgs build() {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId is required");
            }
            if (filePath == null || filePath.isBlank()) {
                throw new IllegalArgumentException("filePath is required");
            }
            if (callbackBaseUrl == null || callbackBaseUrl.isBlank()) {
                throw new IllegalArgumentException("callbackBaseUrl is required");
            }
            return new SubprocessArgs(
                    taskId,
                    filePath,
                    loaderName,
                    chunkerName,
                    chunkSize,
                    chunkOverlap,
                    embeddingBatchSize,
                    indexPath,
                    vectorStorePath,
                    keywordIndexPath,
                    callbackBaseUrl,
                    nd4jConfigJson,
                    checkpointPath,
                    resume,
                    modelSourceType,
                    modelIdentifier,
                    stagingUrl,
                    stagingApiKey,
                    archivePath,
                    memoryThresholdPercent,
                    memoryCriticalPercent,
                    memoryKillThresholdPercent,
                    memoryCheckIntervalMs,
                    options);
        }
    }
}
