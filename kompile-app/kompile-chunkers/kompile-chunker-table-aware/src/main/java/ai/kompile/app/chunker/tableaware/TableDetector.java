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

package ai.kompile.app.chunker.tableaware;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects table regions within text content.
 *
 * <p>Supports detection of:</p>
 * <ul>
 *   <li>Markdown tables (pipe-delimited)</li>
 *   <li>Tab-separated tables (TSV-like)</li>
 *   <li>HTML tables</li>
 * </ul>
 */
@Component
public class TableDetector {

    // Patterns for detecting different table formats
    private static final Pattern HTML_TABLE_PATTERN = Pattern.compile(
        "<table[^>]*>.*?</table>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    // Minimum number of rows to consider as a table
    private int minTableRows = 2;

    // Minimum number of columns to consider as a table
    private int minTableCols = 2;

    /**
     * Represents a detected table region within text.
     */
    public record TableRegion(
        int startOffset,
        int endOffset,
        int tableIndex,
        String tableType,
        String tableContent
    ) {
        public int length() {
            return endOffset - startOffset;
        }
    }

    /**
     * Detects all table regions in the given text.
     *
     * @param text The text to search for tables
     * @return List of TableRegion objects, sorted by start position
     */
    public List<TableRegion> detectTables(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<TableRegion> regions = new ArrayList<>();

        // Detect markdown tables
        regions.addAll(detectMarkdownTables(text));

        // Detect tab-separated tables
        regions.addAll(detectTabSeparatedTables(text));

        // Detect HTML tables
        regions.addAll(detectHtmlTables(text));

        // Sort by start position and merge overlapping regions
        return mergeOverlapping(regions);
    }

    /**
     * Detects markdown tables in the text.
     * Markdown tables have rows starting and ending with |
     * and a separator row with dashes.
     */
    List<TableRegion> detectMarkdownTables(String text) {
        List<TableRegion> regions = new ArrayList<>();
        String[] lines = text.split("\n");

        int tableStartLine = -1;
        int tableStartOffset = 0;
        int currentOffset = 0;
        int tableIndex = 0;
        boolean foundSeparator = false;
        int columnCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            boolean isTableRow = trimmed.startsWith("|") && trimmed.endsWith("|");
            boolean isSeparator = trimmed.matches("^\\|[-:|\\s]+\\|$");

            if (isTableRow) {
                if (tableStartLine == -1) {
                    // Start of potential table
                    tableStartLine = i;
                    tableStartOffset = currentOffset;
                    columnCount = countColumns(trimmed);
                } else if (isSeparator && !foundSeparator) {
                    // Found separator row (confirms this is a table)
                    foundSeparator = true;
                } else if (foundSeparator) {
                    // Validate column count consistency
                    int rowCols = countColumns(trimmed);
                    if (Math.abs(rowCols - columnCount) > 1) {
                        // Column count mismatch - end the table
                        if (tableStartLine != -1 && foundSeparator) {
                            int endOffset = currentOffset;
                            String tableContent = text.substring(tableStartOffset, endOffset);
                            if (countTableRows(tableContent) >= minTableRows) {
                                regions.add(new TableRegion(
                                    tableStartOffset, endOffset, tableIndex++, "markdown", tableContent));
                            }
                        }
                        tableStartLine = -1;
                        foundSeparator = false;
                    }
                }
            } else {
                // Non-table row - end current table if any
                if (tableStartLine != -1 && foundSeparator) {
                    int endOffset = currentOffset;
                    String tableContent = text.substring(tableStartOffset, endOffset);
                    if (countTableRows(tableContent) >= minTableRows) {
                        regions.add(new TableRegion(
                            tableStartOffset, endOffset, tableIndex++, "markdown", tableContent));
                    }
                }
                tableStartLine = -1;
                foundSeparator = false;
            }

            currentOffset += line.length() + 1; // +1 for newline
        }

        // Handle table at end of text
        if (tableStartLine != -1 && foundSeparator) {
            String tableContent = text.substring(tableStartOffset);
            if (countTableRows(tableContent) >= minTableRows) {
                regions.add(new TableRegion(
                    tableStartOffset, text.length(), tableIndex, "markdown", tableContent));
            }
        }

        return regions;
    }

    /**
     * Detects tab-separated tables.
     * Looks for consecutive lines with consistent tab counts.
     */
    List<TableRegion> detectTabSeparatedTables(String text) {
        List<TableRegion> regions = new ArrayList<>();
        String[] lines = text.split("\n");

        int tableStartLine = -1;
        int tableStartOffset = 0;
        int currentOffset = 0;
        int tableIndex = 0;
        int expectedTabs = -1;
        int consecutiveRows = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int tabCount = countTabs(line);

            if (tabCount >= minTableCols - 1) {
                if (tableStartLine == -1) {
                    tableStartLine = i;
                    tableStartOffset = currentOffset;
                    expectedTabs = tabCount;
                    consecutiveRows = 1;
                } else if (Math.abs(tabCount - expectedTabs) <= 1) {
                    consecutiveRows++;
                } else {
                    // Tab count mismatch - end current table
                    if (consecutiveRows >= minTableRows) {
                        int endOffset = currentOffset;
                        String tableContent = text.substring(tableStartOffset, endOffset);
                        regions.add(new TableRegion(
                            tableStartOffset, endOffset, tableIndex++, "tsv", tableContent));
                    }
                    tableStartLine = i;
                    tableStartOffset = currentOffset;
                    expectedTabs = tabCount;
                    consecutiveRows = 1;
                }
            } else {
                // Not enough tabs - end current table
                if (tableStartLine != -1 && consecutiveRows >= minTableRows) {
                    int endOffset = currentOffset;
                    String tableContent = text.substring(tableStartOffset, endOffset);
                    regions.add(new TableRegion(
                        tableStartOffset, endOffset, tableIndex++, "tsv", tableContent));
                }
                tableStartLine = -1;
                expectedTabs = -1;
                consecutiveRows = 0;
            }

            currentOffset += line.length() + 1;
        }

        // Handle table at end of text
        if (tableStartLine != -1 && consecutiveRows >= minTableRows) {
            String tableContent = text.substring(tableStartOffset);
            regions.add(new TableRegion(
                tableStartOffset, text.length(), tableIndex, "tsv", tableContent));
        }

        return regions;
    }

    /**
     * Detects HTML tables using regex.
     */
    List<TableRegion> detectHtmlTables(String text) {
        List<TableRegion> regions = new ArrayList<>();
        Matcher matcher = HTML_TABLE_PATTERN.matcher(text);
        int tableIndex = 0;

        while (matcher.find()) {
            String tableContent = matcher.group();
            // Verify it has at least minTableRows rows
            int rowCount = countHtmlTableRows(tableContent);
            if (rowCount >= minTableRows) {
                regions.add(new TableRegion(
                    matcher.start(), matcher.end(), tableIndex++, "html", tableContent));
            }
        }

        return regions;
    }

    /**
     * Merges overlapping table regions and sorts by start position.
     */
    List<TableRegion> mergeOverlapping(List<TableRegion> regions) {
        if (regions.isEmpty()) {
            return regions;
        }

        // Sort by start position
        List<TableRegion> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(TableRegion::startOffset));

        List<TableRegion> merged = new ArrayList<>();
        TableRegion current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            TableRegion next = sorted.get(i);

            if (next.startOffset() <= current.endOffset()) {
                // Overlapping - keep the larger one
                if (next.length() > current.length()) {
                    current = next;
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        // Re-index tables
        List<TableRegion> reindexed = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            TableRegion r = merged.get(i);
            reindexed.add(new TableRegion(
                r.startOffset(), r.endOffset(), i, r.tableType(), r.tableContent()));
        }

        return reindexed;
    }

    // Helper methods

    private int countColumns(String row) {
        // Count cells in a markdown row
        return row.split("\\|").length - 2; // -2 for empty strings at start and end
    }

    private int countTableRows(String tableContent) {
        String[] lines = tableContent.split("\n");
        int count = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // Skip separator row
                if (!trimmed.matches("^\\|[-:|\\s]+\\|$")) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countTabs(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == '\t') count++;
        }
        return count;
    }

    private int countHtmlTableRows(String tableHtml) {
        // Count <tr> tags
        int count = 0;
        int idx = 0;
        String lower = tableHtml.toLowerCase();
        while ((idx = lower.indexOf("<tr", idx)) != -1) {
            count++;
            idx++;
        }
        return count;
    }

    // Configuration

    public void setMinTableRows(int minTableRows) {
        this.minTableRows = minTableRows;
    }

    public void setMinTableCols(int minTableCols) {
        this.minTableCols = minTableCols;
    }

    public int getMinTableRows() {
        return minTableRows;
    }

    public int getMinTableCols() {
        return minTableCols;
    }
}
