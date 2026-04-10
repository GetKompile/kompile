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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Perform exact string replacement edits in a file.
 * Comparable to OpenCode's EditTool with multiple fallback matching strategies.
 */
public class EditTool implements CliTool {

    @Override
    public String id() { return "edit"; }

    @Override
    public String description() {
        return "Perform exact string replacement in a file. Provide the old_string to find " +
                "and new_string to replace it with. The old_string must be unique in the file " +
                "(provide more context if needed). Set replace_all to true to replace all " +
                "occurrences. Always read the file first before editing.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "The path to the file to edit");

        ObjectNode oldString = props.putObject("old_string");
        oldString.put("type", "string");
        oldString.put("description", "The exact text to find and replace");

        ObjectNode newString = props.putObject("new_string");
        newString.put("type", "string");
        newString.put("description", "The replacement text");

        ObjectNode replaceAll = props.putObject("replace_all");
        replaceAll.put("type", "boolean");
        replaceAll.put("description", "Replace all occurrences (default: false)");

        schema.putArray("required").add("file_path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public String permissionKey() { return "edit"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = params.path("file_path").asText("");
        String oldString = params.path("old_string").asText("");
        String newString = params.path("new_string").asText("");
        boolean replaceAll = params.path("replace_all").asBoolean(false);

        if (filePath.isEmpty()) {
            return ToolResult.error("file_path is required");
        }
        if (oldString.isEmpty()) {
            return ToolResult.error("old_string is required");
        }
        if (oldString.equals(newString)) {
            return ToolResult.error("old_string and new_string must be different");
        }

        Path path = context.resolvePath(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }

        context.checkPermission(permissionKey(), "Edit file: " + path);

        try {
            String content = Files.readString(path);

            // Try exact match first
            int count = countOccurrences(content, oldString);

            if (count == 0) {
                // Fallback: try trimmed matching (handle whitespace differences)
                String trimmedResult = tryTrimmedMatch(content, oldString, newString, replaceAll);
                if (trimmedResult != null) {
                    Files.writeString(path, trimmedResult);
                    String relativePath;
                    try {
                        relativePath = context.getWorkingDirectory().toAbsolutePath().relativize(path.toAbsolutePath()).toString();
                    } catch (IllegalArgumentException ex) {
                        relativePath = path.toString();
                    }
                    return ToolResult.success(relativePath,
                            "Applied edit (trimmed match)",
                            Map.of("path", relativePath, "matchType", "trimmed"));
                }
                return ToolResult.error("old_string not found in file. Read the file first to see exact content.");
            }

            if (!replaceAll && count > 1) {
                return ToolResult.error("old_string found " + count + " times. " +
                        "Provide more surrounding context to make it unique, or set replace_all=true.");
            }

            String newContent;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
            } else {
                int idx = content.indexOf(oldString);
                newContent = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }

            Files.writeString(path, newContent);

            String relativePath = context.getWorkingDirectory().relativize(path).toString();
            return ToolResult.success(relativePath,
                    "Applied edit" + (replaceAll ? " (" + count + " replacements)" : ""),
                    Map.of("path", relativePath, "replacements", replaceAll ? count : 1));

        } catch (IOException e) {
            return ToolResult.error("Error editing file: " + e.getMessage());
        }
    }

    private int countOccurrences(String content, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    /**
     * Fallback matching: normalize whitespace on each line and try matching.
     */
    private String tryTrimmedMatch(String content, String oldString, String newString, boolean replaceAll) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = oldString.split("\n", -1);
        String[] replaceLines = newString.split("\n", -1);

        if (searchLines.length == 0) return null;

        // Try to find the search block by trimmed line matching
        for (int i = 0; i <= contentLines.length - searchLines.length; i++) {
            boolean match = true;
            for (int j = 0; j < searchLines.length; j++) {
                if (!contentLines[i + j].trim().equals(searchLines[j].trim())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                // Found a trimmed match - replace preserving original indentation of first line
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < i; k++) {
                    sb.append(contentLines[k]).append("\n");
                }
                sb.append(newString);
                if (i + searchLines.length < contentLines.length) {
                    sb.append("\n");
                    for (int k = i + searchLines.length; k < contentLines.length; k++) {
                        sb.append(contentLines[k]);
                        if (k < contentLines.length - 1) sb.append("\n");
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }
}
