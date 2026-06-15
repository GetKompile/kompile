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
package ai.kompile.core.staging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Information about a model in the staging pipeline.
 * Shared between kompile-app-main and kompile-model-staging.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StagingModelInfo {
    @JsonProperty("model_id")
    private String modelId;

    @JsonProperty("status")
    private StagingStatus status;

    @JsonProperty("progress")
    private int progress;

    @JsonProperty("error")
    private String error;

    @JsonProperty("source")
    private String source;

    @JsonProperty("type")
    private Object type;

    @JsonProperty("started_at")
    private String startedAt;

    @JsonProperty("completed_at")
    private String completedAt;

    @JsonProperty("message")
    private String message;

    @JsonProperty("bytes_downloaded")
    private long bytesDownloaded;

    @JsonProperty("total_bytes")
    private long totalBytes;

    @JsonProperty("bytes_per_second")
    private long bytesPerSecond;

    @JsonProperty("current_file")
    private String currentFile;

    /** Factory: create a new staging info in PENDING status. */
    public static StagingModelInfo create(String modelId, String source, Object type) {
        StagingModelInfo info = new StagingModelInfo();
        info.modelId = modelId;
        info.source = source;
        info.type = type;
        info.status = StagingStatus.PENDING;
        info.startedAt = Instant.now().toString();
        info.totalBytes = -1;
        return info;
    }

    /** Transition to a new status with progress and message. */
    public void withStatus(StagingStatus status, int progress, String message) {
        this.status = status;
        this.progress = progress;
        this.message = message;
    }

    /** Update download progress. */
    public void withDownloadProgress(int progress, String message, long bytesDownloaded, long totalBytes, long bytesPerSecond) {
        this.status = StagingStatus.DOWNLOADING;
        this.progress = progress;
        this.message = message;
        this.bytesDownloaded = bytesDownloaded;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Mark as failed. Returns this for chaining. */
    public StagingModelInfo failed(String error) {
        this.status = StagingStatus.FAILED;
        this.error = error;
        this.completedAt = Instant.now().toString();
        return this;
    }

    /** Mark as completed. */
    public void completed() {
        this.status = StagingStatus.COMPLETED;
        this.progress = 100;
        this.completedAt = Instant.now().toString();
    }
}
