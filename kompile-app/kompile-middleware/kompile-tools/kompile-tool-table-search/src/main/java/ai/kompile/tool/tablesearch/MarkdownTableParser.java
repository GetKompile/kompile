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

package ai.kompile.tool.tablesearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses markdown tables into structured row/column data.
 *
 * <p>Handles the standard markdown table format:
 * <pre>
 * | Header1 | Header2 |
 * | ------- | ------- |
 * | val1    | val2    |
 * </pre>
 */
public final class MarkdownTableParser {

    private MarkdownTableParser() {}

    /**
     * Parsed table with typed access to headers and rows.
     */
    public static class ParsedTable {
        private final List<String> headers;
        private final List<List<String>> rows;

        public ParsedTable(List<String> headers, List<List<String>> rows) {
            this.headers = headers;
            this.rows = rows;
        }

        public List<String> getHeaders() { return headers; }
        public List<List<String>> getRows() { return rows; }
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return headers.size(); }

        /**
         * Returns rows as list of maps keyed by header name.
         */
        public List<Map<String, String>> toRowMaps() {
            List<Map<String, String>> result = new ArrayList<>(rows.size());
            for (List<String> row : rows) {
                Map<String, String> map = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    map.put(headers.get(i), i < row.size() ? row.get(i) : "");
                }
                result.add(map);
            }
            return result;
        }

        /**
         * Returns all values in a specific column.
         */
        public List<String> getColumn(String header) {
            int idx = headers.indexOf(header);
            if (idx < 0) return Collections.emptyList();
            List<String> values = new ArrayList<>(rows.size());
            for (List<String> row : rows) {
                values.add(idx < row.size() ? row.get(idx) : "");
            }
            return values;
        }

        /**
         * Returns the column index for a header, case-insensitive.
         */
        public int findColumnIndex(String header) {
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).equalsIgnoreCase(header)) return i;
            }
            return -1;
        }

        /**
         * Renders this table back to markdown format.
         */
        public String toMarkdown() {
            if (headers.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|");
            for (int i = 0; i < headers.size(); i++) {
                sb.append(" --- |");
            }
            sb.append("\n");
            for (List<String> row : rows) {
                sb.append("| ");
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < headers.size(); i++) {
                    cells.add(i < row.size() ? row.get(i) : "");
                }
                sb.append(String.join(" | ", cells));
                sb.append(" |\n");
            }
            return sb.toString().trim();
        }

        /**
         * Renders this table as CSV.
         */
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(csvRow(headers)).append("\n");
            for (List<String> row : rows) {
                sb.append(csvRow(row)).append("\n");
            }
            return sb.toString().trim();
        }

        private String csvRow(List<String> cells) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) sb.append(",");
                String cell = cells.get(i);
                if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
                    sb.append("\"").append(cell.replace("\"", "\"\"")).append("\"");
                } else {
                    sb.append(cell);
                }
            }
            return sb.toString();
        }

        /**
         * Renders this table as a list of JSON-style row objects.
         */
        public List<Map<String, Object>> toJsonRows() {
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (List<String> row : rows) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String val = i < row.size() ? row.get(i) : "";
                    map.put(headers.get(i), tryParseNumeric(val));
                }
                result.add(map);
            }
            return result;
        }
    }

    /**
     * Parses a markdown table string into headers and data rows.
     */
    public static ParsedTable parse(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return new ParsedTable(Collections.emptyList(), Collections.emptyList());
        }

        String[] lines = markdown.split("\\r?\\n");
        List<String> headers = Collections.emptyList();
        List<List<String>> rows = new ArrayList<>();
        boolean headerParsed = false;
        boolean separatorSkipped = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.contains("|")) continue;

            List<String> cells = parseCells(trimmed);

            if (!headerParsed) {
                headers = cells;
                headerParsed = true;
            } else if (!separatorSkipped) {
                // Skip separator row (| --- | --- |)
                if (isSeparatorRow(trimmed)) {
                    separatorSkipped = true;
                } else {
                    // No separator — treat as data row
                    separatorSkipped = true;
                    rows.add(cells);
                }
            } else {
                rows.add(cells);
            }
        }

        return new ParsedTable(headers, rows);
    }

    private static List<String> parseCells(String row) {
        // Strip leading/trailing pipes
        String stripped = row;
        if (stripped.startsWith("|")) stripped = stripped.substring(1);
        if (stripped.endsWith("|")) stripped = stripped.substring(0, stripped.length() - 1);

        String[] parts = stripped.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private static boolean isSeparatorRow(String row) {
        // Separator rows contain only |, -, :, and spaces
        return row.matches("^[\\s|\\-:]+$");
    }

    /**
     * Attempts to parse a string as a number, returning the original string if it fails.
     */
    public static Object tryParseNumeric(String value) {
        if (value == null || value.isEmpty()) return value;
        String trimmed = value.trim();
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * Attempts to parse a string as a double, returning null if it fails.
     */
    public static Double tryParseDouble(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
