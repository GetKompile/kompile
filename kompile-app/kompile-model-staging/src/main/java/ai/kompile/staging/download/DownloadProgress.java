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

/**
 * Progress update for a download operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadProgress {

    /**
     * Current phase of the download.
     */
    private Phase phase;

    /**
     * Name of the file being downloaded.
     */
    private String fileName;

    /**
     * Bytes downloaded so far.
     */
    private long bytesDownloaded;

    /**
     * Total bytes to download (-1 if unknown).
     */
    private long totalBytes;

    /**
     * Progress percentage (0-100).
     */
    private int progressPercent;

    /**
     * Current download speed in bytes per second.
     */
    private long bytesPerSecond;

    /**
     * Status message.
     */
    private String message;

    /**
     * Download phases.
     */
    public enum Phase {
        INITIALIZING,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        COMPLETED,
        FAILED
    }

    /**
     * Create a progress update for the initializing phase.
     */
    public static DownloadProgress initializing(String message) {
        return DownloadProgress.builder()
                .phase(Phase.INITIALIZING)
                .message(message)
                .progressPercent(0)
                .build();
    }

    /**
     * Create a progress update for downloading.
     */
    public static DownloadProgress downloading(String fileName, long bytesDownloaded,
                                                long totalBytes, long bytesPerSecond) {
        int percent = totalBytes > 0
            ? (int) ((bytesDownloaded * 100) / totalBytes)
            : 0;
        return DownloadProgress.builder()
                .phase(Phase.DOWNLOADING)
                .fileName(fileName)
                .bytesDownloaded(bytesDownloaded)
                .totalBytes(totalBytes)
                .progressPercent(percent)
                .bytesPerSecond(bytesPerSecond)
                .message(String.format("Downloading %s: %d%%", fileName, percent))
                .build();
    }

    /**
     * Create a progress update for verification.
     */
    public static DownloadProgress verifying(String fileName) {
        return DownloadProgress.builder()
                .phase(Phase.VERIFYING)
                .fileName(fileName)
                .message("Verifying checksum: " + fileName)
                .progressPercent(95)
                .build();
    }

    /**
     * Create a progress update for extraction.
     */
    public static DownloadProgress extracting(String fileName) {
        return DownloadProgress.builder()
                .phase(Phase.EXTRACTING)
                .fileName(fileName)
                .message("Extracting: " + fileName)
                .progressPercent(97)
                .build();
    }

    /**
     * Create a completed progress update.
     */
    public static DownloadProgress completed(String message) {
        return DownloadProgress.builder()
                .phase(Phase.COMPLETED)
                .message(message)
                .progressPercent(100)
                .build();
    }

    /**
     * Create a failed progress update.
     */
    public static DownloadProgress failed(String errorMessage) {
        return DownloadProgress.builder()
                .phase(Phase.FAILED)
                .message(errorMessage)
                .build();
    }

    /**
     * Format bytes to human-readable string.
     */
    public String getFormattedBytesDownloaded() {
        return formatBytes(bytesDownloaded);
    }

    /**
     * Format total bytes to human-readable string.
     */
    public String getFormattedTotalBytes() {
        return totalBytes > 0 ? formatBytes(totalBytes) : "unknown";
    }

    /**
     * Format speed to human-readable string.
     */
    public String getFormattedSpeed() {
        return formatBytes(bytesPerSecond) + "/s";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
