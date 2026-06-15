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

package ai.kompile.cli.common.util;

/**
 * Shared utility for formatting byte counts as human-readable strings.
 *
 * <p>This is the single canonical implementation used across all Kompile modules.
 * Use this instead of local copies of formatBytes().</p>
 */
public final class ByteFormatUtils {

    private ByteFormatUtils() {
        // utility class
    }

    /**
     * Format a byte count as a human-readable string (e.g., "4.50 GB").
     *
     * <p>Returns "unknown" for negative values, then scales through B / KB / MB / GB / TB.</p>
     *
     * @param bytes byte count (may be 0; negative returns "unknown")
     * @return formatted string such as "512.0 MB" or "1.25 GB"
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}
