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

import lombok.Builder;
import lombok.Data;

/**
 * Progress information for archive download/upload operations.
 */
@Data
@Builder
public class TransferProgress {

    public enum Phase {
        INITIALIZING,
        CONNECTING,
        DOWNLOADING,
        UPLOADING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Current phase of the transfer.
     */
    private final Phase phase;

    /**
     * Name of the file being transferred.
     */
    private final String fileName;

    /**
     * Bytes transferred so far.
     */
    private final long bytesTransferred;

    /**
     * Total bytes to transfer (-1 if unknown).
     */
    private final long totalBytes;

    /**
     * Transfer speed in bytes per second.
     */
    private final long bytesPerSecond;

    /**
     * Progress message.
     */
    private final String message;

    /**
     * Error message if failed.
     */
    private final String error;

    /**
     * Whether the transfer supports resume.
     */
    private final boolean resumable;

    /**
     * Bytes previously transferred (for resume).
     */
    private final long previouslyTransferred;

    /**
     * Get progress percentage (0-100).
     */
    public int getProgressPercent() {
        if (phase == Phase.COMPLETED) return 100;
        if (phase == Phase.FAILED || phase == Phase.CANCELLED) return 0;
        if (totalBytes <= 0) return 0;
        return (int) ((bytesTransferred * 100.0) / totalBytes);
    }

    /**
     * Get formatted bytes transferred (e.g., "123.4 MB").
     */
    public String getFormattedBytesTransferred() {
        return formatBytes(bytesTransferred);
    }

    /**
     * Get formatted total bytes (e.g., "500.2 MB").
     */
    public String getFormattedTotalBytes() {
        return totalBytes > 0 ? formatBytes(totalBytes) : "unknown";
    }

    /**
     * Get formatted speed (e.g., "25.6 MB/s").
     */
    public String getFormattedSpeed() {
        if (bytesPerSecond <= 0) return "";
        return formatBytes(bytesPerSecond) + "/s";
    }

    /**
     * Get estimated time remaining in seconds.
     */
    public long getEstimatedSecondsRemaining() {
        if (bytesPerSecond <= 0 || totalBytes <= 0) return -1;
        long remaining = totalBytes - bytesTransferred;
        return remaining / bytesPerSecond;
    }

    /**
     * Get formatted time remaining (e.g., "5m 30s").
     */
    public String getFormattedTimeRemaining() {
        long seconds = getEstimatedSecondsRemaining();
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // Factory methods

    public static TransferProgress initializing(String fileName) {
        return TransferProgress.builder()
                .phase(Phase.INITIALIZING)
                .fileName(fileName)
                .message("Initializing download: " + fileName)
                .build();
    }

    public static TransferProgress connecting(String fileName) {
        return TransferProgress.builder()
                .phase(Phase.CONNECTING)
                .fileName(fileName)
                .message("Connecting...")
                .build();
    }

    public static TransferProgress downloading(String fileName, long transferred, long total, long speed) {
        return TransferProgress.builder()
                .phase(Phase.DOWNLOADING)
                .fileName(fileName)
                .bytesTransferred(transferred)
                .totalBytes(total)
                .bytesPerSecond(speed)
                .message("Downloading: " + formatBytesStatic(transferred) + " / " + formatBytesStatic(total))
                .build();
    }

    public static TransferProgress resuming(String fileName, long previousBytes, long total) {
        return TransferProgress.builder()
                .phase(Phase.DOWNLOADING)
                .fileName(fileName)
                .bytesTransferred(previousBytes)
                .totalBytes(total)
                .previouslyTransferred(previousBytes)
                .resumable(true)
                .message("Resuming from " + formatBytesStatic(previousBytes))
                .build();
    }

    public static TransferProgress verifying(String fileName) {
        return TransferProgress.builder()
                .phase(Phase.VERIFYING)
                .fileName(fileName)
                .message("Verifying checksum...")
                .build();
    }

    public static TransferProgress completed(String fileName, long totalBytes) {
        return TransferProgress.builder()
                .phase(Phase.COMPLETED)
                .fileName(fileName)
                .bytesTransferred(totalBytes)
                .totalBytes(totalBytes)
                .message("Download completed: " + formatBytesStatic(totalBytes))
                .build();
    }

    public static TransferProgress failed(String fileName, String error) {
        return TransferProgress.builder()
                .phase(Phase.FAILED)
                .fileName(fileName)
                .error(error)
                .message("Download failed: " + error)
                .build();
    }

    public static TransferProgress cancelled(String fileName) {
        return TransferProgress.builder()
                .phase(Phase.CANCELLED)
                .fileName(fileName)
                .message("Download cancelled")
                .build();
    }

    private static String formatBytesStatic(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
