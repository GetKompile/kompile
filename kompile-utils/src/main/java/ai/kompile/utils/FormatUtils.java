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

package ai.kompile.utils;

import java.time.Duration;

/**
 * Human-readable formatting for durations, byte sizes, and numbers.
 */
public final class FormatUtils {

    private FormatUtils() {}

    /**
     * Format a Duration as a compact human-readable string.
     * Examples: "2h 15m 30s", "45m 12s", "8s", "150ms".
     */
    public static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds <= 0) return d.toMillis() + "ms";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /**
     * Format a duration in milliseconds as a compact human-readable string.
     * Examples: "2h 15m 30s", "45m 12s", "8s", "150ms".
     */
    public static String formatDuration(long millis) {
        return formatDuration(Duration.ofMillis(millis));
    }

    /**
     * Format a byte count as a human-readable string (e.g., "4.50 GB").
     * Returns "unknown" for negative values, then scales through B / KB / MB / GB / TB.
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    /**
     * Format a number with comma grouping for readability.
     * Examples: 42 -> "42", 1234567 -> "1,234,567".
     */
    public static String formatNumber(long n) {
        if (n < 1000 && n > -1000) return String.valueOf(n);
        return String.format("%,d", n);
    }
}
