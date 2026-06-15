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

package ai.kompile.staging.download;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of a download operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResult {

    /**
     * Whether the download was successful.
     */
    private boolean success;

    /**
     * Error message if download failed.
     */
    private String errorMessage;

    /**
     * Path to the downloaded model file.
     */
    private Path modelPath;

    /**
     * Path to the downloaded vocabulary file.
     */
    private Path vocabPath;

    /**
     * Path to the tokenizer config file (if present).
     */
    private Path tokenizerConfigPath;

    /**
     * Map of file type to downloaded path.
     */
    @Builder.Default
    private Map<String, Path> downloadedFiles = new HashMap<>();

    /**
     * SHA256 checksum of the main model file.
     */
    private String checksum;

    /**
     * Total bytes downloaded.
     */
    private long totalBytes;

    /**
     * Download duration in milliseconds.
     */
    private long durationMs;

    /**
     * Create a successful result.
     */
    public static DownloadResult success(Path modelPath, Path vocabPath, String checksum) {
        return DownloadResult.builder()
                .success(true)
                .modelPath(modelPath)
                .vocabPath(vocabPath)
                .checksum(checksum)
                .build();
    }

    /**
     * Create a failed result.
     */
    public static DownloadResult failure(String errorMessage) {
        return DownloadResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Add a downloaded file to the result.
     */
    public DownloadResult addFile(String key, Path path) {
        downloadedFiles.put(key, path);
        return this;
    }
}
