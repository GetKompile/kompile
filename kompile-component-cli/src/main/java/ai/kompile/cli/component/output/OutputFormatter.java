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

package ai.kompile.cli.component.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Output formatter supporting multiple formats: text, JSON, YAML, CSV, table
 */
public class OutputFormatter {

    public enum Format {
        TEXT,
        JSON,
        YAML,
        CSV,
        TABLE
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    
    private static final CsvMapper CSV_MAPPER = new CsvMapper();

    /**
     * Format a single object to the specified format
     */
    public static String format(Object data, Format format) throws Exception {
        return switch (format) {
            case TEXT -> formatAsText(data);
            case JSON -> JSON_MAPPER.writeValueAsString(data);
            case YAML -> YAML_MAPPER.writeValueAsString(data);
            case CSV -> formatAsCsv(List.of(data));
            case TABLE -> formatAsTable(List.of(data));
        };
    }

    /**
     * Format a list of objects to the specified format
     */
    public static String formatList(List<?> dataList, Format format) throws Exception {
        return switch (format) {
            case TEXT -> formatListAsText(dataList);
            case JSON -> JSON_MAPPER.writeValueAsString(dataList);
            case YAML -> YAML_MAPPER.writeValueAsString(dataList);
            case CSV -> formatAsCsv(dataList);
            case TABLE -> formatAsTable(dataList);
        };
    }

    /**
     * Format a map of objects to text (for configs)
     */
    public static String formatMap(Map<String, Object> data, Format format) throws Exception {
        return switch (format) {
            case TEXT -> formatMapAsText(data);
            case JSON -> JSON_MAPPER.writeValueAsString(data);
            case YAML -> YAML_MAPPER.writeValueAsString(data);
            case CSV, TABLE -> throw new IllegalArgumentException("CSV/Table format not supported for map data");
        };
    }

    /**
     * Format single object as human-readable text
     */
    private static String formatAsText(Object data) {
        if (data instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            return sb.toString();
        }
        return data.toString();
    }

    /**
     * Format list as human-readable text
     */
    private static String formatListAsText(List<?> dataList) {
        if (dataList.isEmpty()) {
            return "(no data)";
        }

        StringBuilder sb = new StringBuilder();
        for (Object item : dataList) {
            if (item instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                sb.append("\n");
            } else {
                sb.append("  ").append(item).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Format map as human-readable text
     */
    private static String formatMapAsText(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "(no configuration)";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            sb.append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof Map<?, ?> nestedMap) {
                sb.append("\n");
                for (Map.Entry<?, ?> nested : nestedMap.entrySet()) {
                    sb.append("  ").append(nested.getKey()).append(": ").append(nested.getValue()).append("\n");
                }
            } else {
                sb.append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Format list as CSV
     */
    private static String formatAsCsv(List<?> dataList) throws Exception {
        if (dataList.isEmpty()) {
            return "";
        }

        CsvSchema schema = CSV_MAPPER.schemaFor(dataList.get(0).getClass());
        return CSV_MAPPER.writer(schema.withUseHeader(true)).writeValueAsString(dataList);
    }

    /**
     * Format list as ASCII table
     */
    @SuppressWarnings("unchecked")
    private static String formatAsTable(List<?> dataList) throws Exception {
        if (dataList.isEmpty()) {
            return "(no data)";
        }

        // Convert to list of maps for generic table formatting
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : dataList) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                maps.add(map);
            } else {
                // Convert POJO to map using JSON mapper
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JSON_MAPPER.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                maps.add(map);
            }
        }

        if (maps.isEmpty()) {
            return "(no data)";
        }

        // Get all column headers
        List<String> headers = maps.get(0).keySet().stream().toList();
        
        // Calculate column widths
        int[] colWidths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            colWidths[i] = headers.get(i).length();
        }

        List<Map<String, String>> stringRows = maps.stream().map(map -> {
            Map<String, String> row = new java.util.LinkedHashMap<>();
            for (String header : headers) {
                Object value = map.getOrDefault(header, "");
                String strValue = value != null ? value.toString() : "";
                row.put(header, strValue);
                int idx = headers.indexOf(header);
                colWidths[idx] = Math.max(colWidths[idx], strValue.length());
            }
            return row;
        }).toList();

        // Cap column widths at 40 chars for readability
        for (int i = 0; i < colWidths.length; i++) {
            colWidths[i] = Math.min(colWidths[i], 40);
        }

        // Build table
        StringBuilder sb = new StringBuilder();
        
        // Header row
        sb.append("| ");
        for (int i = 0; i < headers.size(); i++) {
            sb.append(padRight(headers.get(i), colWidths[i])).append(" | ");
        }
        sb.append("\n");

        // Separator
        sb.append("|");
        for (int width : colWidths) {
            sb.append("-".repeat(width + 2)).append("|");
        }
        sb.append("\n");

        // Data rows
        for (Map<String, String> row : stringRows) {
            sb.append("| ");
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                String value = row.getOrDefault(header, "");
                if (value.length() > colWidths[i]) {
                    value = value.substring(0, colWidths[i] - 3) + "...";
                }
                sb.append(padRight(value, colWidths[i])).append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Pad string to the right to specified width
     */
    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }
}
