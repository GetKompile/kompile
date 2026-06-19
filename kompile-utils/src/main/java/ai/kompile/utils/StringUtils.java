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

/**
 * Common string manipulation utilities used across Kompile modules.
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * Truncate a string to maxLen characters, appending "..." if truncated.
     * Returns empty string for null input.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Truncate a string to maxLen characters, appending a unicode ellipsis if truncated.
     * Returns empty string for null input.
     */
    public static String truncateEllipsis(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\u2026";
    }

    /**
     * Truncate with a size annotation suffix, e.g. "\n... (truncated, 5432 chars total)".
     * Returns empty string for null input.
     */
    public static String truncateWithSize(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, " + s.length() + " chars total)";
    }
}
