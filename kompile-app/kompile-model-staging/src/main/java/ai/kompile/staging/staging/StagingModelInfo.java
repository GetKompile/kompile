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

package ai.kompile.staging.staging;

import ai.kompile.modelmanager.registry.ModelType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Information about a model in the staging pipeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StagingModelInfo {

    /**
     * Model ID.
     */
    @JsonProperty("model_id")
    private String modelId;

    /**
     * Current staging status.
     */
    @JsonProperty("status")
    private StagingStatus status;

    /**
     * Progress percentage (0-100).
     */
    @JsonProperty("progress")
    private int progress;

    /**
     * Error message if failed.
     */
    @JsonProperty("error")
    private String error;

    /**
     * Source of the model (e.g., "huggingface:BAAI/bge-base-en-v1.5").
     */
    @JsonProperty("source")
    private String source;

    /**
     * Model type.
     */
    @JsonProperty("type")
    private ModelType type;

    /**
     * When staging started.
     */
    @JsonProperty("started_at")
    private String startedAt;

    /**
     * When staging completed or failed.
     */
    @JsonProperty("completed_at")
    private String completedAt;

    /**
     * Current status message.
     */
    @JsonProperty("message")
    private String message;

    /**
     * Bytes downloaded so far.
     */
    @JsonProperty("bytes_downloaded")
    private long bytesDownloaded;

    /**
     * Total bytes to download (-1 if unknown).
     */
    @JsonProperty("total_bytes")
    private long totalBytes;

    /**
     * Current download speed in bytes per second.
     */
    @JsonProperty("bytes_per_second")
    private long bytesPerSecond;

    /**
     * Name of the file currently being downloaded.
     */
    @JsonProperty("current_file")
    private String currentFile;

    /**
     * Create a new staging model info.
     */
    public static StagingModelInfo create(String modelId, String source, ModelType type) {
        return StagingModelInfo.builder()
                .modelId(modelId)
                .source(source)
                .type(type)
                .status(StagingStatus.PENDING)
                .progress(0)
                .startedAt(Instant.now().toString())
                .build();
    }

    /**
     * Update status.
     */
    public StagingModelInfo withStatus(StagingStatus newStatus, int progress, String message) {
        this.status = newStatus;
        this.progress = progress;
        this.message = message;
        if (newStatus.isTerminal()) {
            this.completedAt = Instant.now().toString();
        }
        return this;
    }

    /**
     * Update download progress details.
     */
    public StagingModelInfo withDownloadProgress(int progress, String message,
                                                  long bytesDownloaded, long totalBytes,
                                                  long bytesPerSecond, String currentFile) {
        this.status = StagingStatus.DOWNLOADING;
        this.progress = progress;
        this.message = message;
        this.bytesDownloaded = bytesDownloaded;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
        this.currentFile = currentFile;
        return this;
    }

    /**
     * Mark as failed.
     */
    public StagingModelInfo failed(String errorMessage) {
        this.status = StagingStatus.FAILED;
        this.error = errorMessage;
        this.message = errorMessage;
        this.completedAt = Instant.now().toString();
        return this;
    }

    /**
     * Mark as completed.
     */
    public StagingModelInfo completed() {
        this.status = StagingStatus.COMPLETED;
        this.progress = 100;
        this.message = "Model staged successfully";
        this.completedAt = Instant.now().toString();
        return this;
    }
}
