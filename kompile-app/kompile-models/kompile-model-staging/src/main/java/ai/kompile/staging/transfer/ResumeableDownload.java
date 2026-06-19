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

package ai.kompile.staging.transfer;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * State for resumable downloads.
 * Tracks progress and allows resumption of interrupted downloads.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeableDownload {

    private static final Logger log = LoggerFactory.getLogger(ResumeableDownload.class);
    private static final String STATE_FILE_SUFFIX = ".download-state";
    private static final ObjectMapper objectMapper = JsonUtils.standardMapper();

    /**
     * Source URL of the download.
     */
    @JsonProperty("url")
    private String url;

    /**
     * Local file path for the download.
     */
    @JsonProperty("local_path")
    private String localPath;

    /**
     * Bytes downloaded so far.
     */
    @JsonProperty("bytes_downloaded")
    private long bytesDownloaded;

    /**
     * Total file size (-1 if unknown).
     */
    @JsonProperty("total_bytes")
    private long totalBytes;

    /**
     * Expected checksum of the complete file.
     */
    @JsonProperty("expected_checksum")
    private String expectedChecksum;

    /**
     * Whether the server supports Range requests.
     */
    @JsonProperty("supports_resume")
    private boolean supportsResume;

    /**
     * ETag from the server for validation.
     */
    @JsonProperty("etag")
    private String etag;

    /**
     * When the download was started.
     */
    @JsonProperty("started_at")
    private String startedAt;

    /**
     * When the download was last updated.
     */
    @JsonProperty("updated_at")
    private String updatedAt;

    /**
     * Create a new download state.
     */
    public static ResumeableDownload create(String url, Path localPath) {
        return ResumeableDownload.builder()
                .url(url)
                .localPath(localPath.toString())
                .bytesDownloaded(0)
                .totalBytes(-1)
                .startedAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();
    }

    /**
     * Get the local path as a Path object.
     */
    public Path getLocalPathAsPath() {
        return Path.of(localPath);
    }

    /**
     * Get the state file path.
     */
    public Path getStateFilePath() {
        return Path.of(localPath + STATE_FILE_SUFFIX);
    }

    /**
     * Update the bytes downloaded and save state.
     */
    public void updateProgress(long bytesDownloaded) {
        this.bytesDownloaded = bytesDownloaded;
        this.updatedAt = Instant.now().toString();
    }

    /**
     * Check if the download can be resumed.
     */
    public boolean canResume() {
        if (!supportsResume) {
            return false;
        }
        Path path = getLocalPathAsPath();
        if (!Files.exists(path)) {
            return false;
        }
        try {
            long fileSize = Files.size(path);
            return fileSize == bytesDownloaded && fileSize < totalBytes;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get actual bytes on disk.
     */
    public long getActualBytesOnDisk() {
        try {
            Path path = getLocalPathAsPath();
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Check if download is complete.
     */
    public boolean isComplete() {
        return totalBytes > 0 && bytesDownloaded >= totalBytes;
    }

    /**
     * Get progress percentage.
     */
    public int getProgressPercent() {
        if (totalBytes <= 0) return 0;
        return (int) ((bytesDownloaded * 100.0) / totalBytes);
    }

    /**
     * Save state to disk.
     */
    public void save() throws IOException {
        Path statePath = getStateFilePath();
        objectMapper.writeValue(statePath.toFile(), this);
    }

    /**
     * Load state from disk.
     */
    public static ResumeableDownload load(Path localPath) {
        Path statePath = Path.of(localPath + STATE_FILE_SUFFIX);
        if (!Files.exists(statePath)) {
            return null;
        }
        try {
            return objectMapper.readValue(statePath.toFile(), ResumeableDownload.class);
        } catch (IOException e) {
            log.warn("Failed to load download state", e);
            return null;
        }
    }

    /**
     * Delete the state file.
     */
    public void deleteStateFile() {
        try {
            Files.deleteIfExists(getStateFilePath());
        } catch (IOException e) {
            log.warn("Failed to delete state file", e);
        }
    }

    /**
     * Clear partial download and state.
     */
    public void clear() {
        try {
            Files.deleteIfExists(getLocalPathAsPath());
            deleteStateFile();
        } catch (IOException e) {
            log.warn("Failed to clear download", e);
        }
    }
}
