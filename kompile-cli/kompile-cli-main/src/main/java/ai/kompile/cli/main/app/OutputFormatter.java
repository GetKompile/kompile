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

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Output formatting utilities for CLI commands that display API responses.
 */
public class OutputFormatter {

    private static final ObjectMapper PRETTY_MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Pretty-prints a JSON string to stdout.
     */
    public static void printJson(String json) {
        try {
            Object parsed = PRETTY_MAPPER.readValue(json, Object.class);
            System.out.println(PRETTY_MAPPER.writeValueAsString(parsed));
        } catch (Exception e) {
            // Not valid JSON, print as-is
            System.out.println(json);
        }
    }

    /**
     * Prints a key-value pair in aligned format.
     */
    public static void printKv(String key, Object value) {
        System.out.printf("  %-24s %s%n", key + ":", value != null ? value : "-");
    }

    /**
     * Prints a JSON array as a formatted table with the given columns.
     * Column names correspond to JSON field names; headers are capitalized.
     */
    public static void printTable(JsonNode array, String... columns) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            System.out.println("  (no results)");
            return;
        }

        // Calculate column widths
        int[] widths = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            widths[i] = columns[i].length();
        }
        for (JsonNode row : array) {
            for (int i = 0; i < columns.length; i++) {
                String val = nodeToString(row, columns[i]);
                widths[i] = Math.max(widths[i], val.length());
            }
        }

        // Cap widths at 60 characters
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.min(widths[i], 60);
        }

        // Print header
        StringBuilder header = new StringBuilder("  ");
        StringBuilder separator = new StringBuilder("  ");
        for (int i = 0; i < columns.length; i++) {
            header.append(String.format("%-" + (widths[i] + 2) + "s", columns[i].toUpperCase()));
            separator.append("-".repeat(widths[i])).append("  ");
        }
        System.out.println(header);
        System.out.println(separator);

        // Print rows
        for (JsonNode row : array) {
            StringBuilder line = new StringBuilder("  ");
            for (int i = 0; i < columns.length; i++) {
                String val = nodeToString(row, columns[i]);
                if (val.length() > 60) {
                    val = val.substring(0, 57) + "...";
                }
                line.append(String.format("%-" + (widths[i] + 2) + "s", val));
            }
            System.out.println(line);
        }
    }

    /**
     * Prints an informational message to stderr.
     */
    public static void info(String message) {
        System.err.println(message);
    }

    private static String nodeToString(JsonNode row, String field) {
        JsonNode val = row.get(field);
        if (val == null || val.isNull()) return "-";
        if (val.isTextual()) return val.asText();
        return val.toString();
    }
}
