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

        /** Path to the index directory */
        String indexPath,

        /** Base URL for HTTP callbacks to main app (e.g., "http://localhost:8080") */
        String callbackBaseUrl,

        /** ND4J environment configuration as JSON string */
        String nd4jConfigJson,

        /** Path for checkpoint storage (optional) */
        String checkpointPath,

        /** Whether to resume from existing checkpoint */
        boolean resume,

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
                indexPath,
                callbackBaseUrl,
                nd4jConfigJson,
                checkpointPath,
                resume,
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
        private String callbackBaseUrl;
        private String nd4jConfigJson;
        private String checkpointPath;
        private boolean resume = false;
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
                    callbackBaseUrl,
                    nd4jConfigJson,
                    checkpointPath,
                    resume,
                    options);
        }
    }
}
