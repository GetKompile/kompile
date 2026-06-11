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

package ai.kompile.cli.main.chat.tools;

/**
 * Simple inline diff generator for edit/write tool results.
 * Produces a compact unified-style diff showing removed and added lines
 * with minimal context (1 line before/after).
 */
public class InlineDiff {

    private static final int CONTEXT_LINES = 1;
    private static final int MAX_DIFF_LINES = 20;

    /**
     * Compute a compact unified diff between old and new content.
     * Returns null if no meaningful diff can be produced.
     *
     * @param oldContent  original file content (may be null for new files)
     * @param newContent  new file content
     * @param fileName    file name for the diff header
     * @return unified diff string, or null if empty/too large
     */
    public static String compute(String oldContent, String newContent, String fileName) {
        if (newContent == null) return null;
        if (oldContent == null) oldContent = "";

        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        // Find the first and last differing lines
        int prefixLen = 0;
        int minLen = Math.min(oldLines.length, newLines.length);
        while (prefixLen < minLen && oldLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }

        // If identical, no diff
        if (prefixLen == minLen && oldLines.length == newLines.length) {
            return null;
        }

        int oldSuffixStart = oldLines.length;
        int newSuffixStart = newLines.length;
        while (oldSuffixStart > prefixLen && newSuffixStart > prefixLen
                && oldLines[oldSuffixStart - 1].equals(newLines[newSuffixStart - 1])) {
            oldSuffixStart--;
            newSuffixStart--;
        }

        // Compute context boundaries
        int contextStart = Math.max(0, prefixLen - CONTEXT_LINES);
        int oldContextEnd = Math.min(oldLines.length, oldSuffixStart + CONTEXT_LINES);
        int newContextEnd = Math.min(newLines.length, newSuffixStart + CONTEXT_LINES);

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(fileName).append("\n");
        sb.append("+++ ").append(fileName).append("\n");

        // Hunk header
        int oldHunkStart = contextStart + 1; // 1-based
        int oldHunkLen = oldContextEnd - contextStart;
        int newHunkStart = contextStart + 1;
        int newHunkLen = newContextEnd - contextStart;
        sb.append(String.format("@@ -%d,%d +%d,%d @@\n", oldHunkStart, oldHunkLen, newHunkStart, newHunkLen));

        int lineCount = 0;

        // Context before
        for (int i = contextStart; i < prefixLen && lineCount < MAX_DIFF_LINES; i++) {
            sb.append(" ").append(oldLines[i]).append("\n");
            lineCount++;
        }

        // Removed lines
        for (int i = prefixLen; i < oldSuffixStart && lineCount < MAX_DIFF_LINES; i++) {
            sb.append("-").append(oldLines[i]).append("\n");
            lineCount++;
        }

        // Added lines
        for (int i = prefixLen; i < newSuffixStart && lineCount < MAX_DIFF_LINES; i++) {
            sb.append("+").append(newLines[i]).append("\n");
            lineCount++;
        }

        // Context after
        for (int i = Math.max(oldSuffixStart, newSuffixStart);
             i < Math.max(oldContextEnd, newContextEnd) && lineCount < MAX_DIFF_LINES; i++) {
            if (i < newLines.length) {
                sb.append(" ").append(newLines[i]).append("\n");
            }
            lineCount++;
        }

        // Truncation notice
        int totalChanged = (oldSuffixStart - prefixLen) + (newSuffixStart - prefixLen);
        if (lineCount >= MAX_DIFF_LINES && totalChanged > MAX_DIFF_LINES) {
            sb.append("... (").append(totalChanged - MAX_DIFF_LINES).append(" more lines)\n");
        }

        return sb.toString().stripTrailing();
    }
}
