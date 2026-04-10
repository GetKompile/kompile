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
import java.util.List;
import java.util.Map;

/**
 * Read file contents with optional line range (offset/limit).
 * Comparable to OpenCode's ReadTool.
 */
public class ReadTool implements CliTool {

    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final long MAX_FILE_SIZE = 50 * 1024; // 50KB

    @Override
    public String id() { return "read"; }

    @Override
    public String description() {
        return "Read the contents of a file. Returns the file content with line numbers. " +
                "Supports optional offset (starting line, 1-based) and limit (number of lines). " +
                "Lines longer than 2000 characters are truncated. " +
                "Use this tool to understand existing code before making changes.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "The path to the file to read (absolute or relative to working directory)");

        ObjectNode offset = props.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "Line number to start reading from (1-based). Optional.");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum number of lines to read. Optional, defaults to 2000.");

        schema.putArray("required").add("file_path");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read file");

        String filePath = params.path("file_path").asText("");
        if (filePath.isEmpty()) {
            return ToolResult.error("file_path is required");
        }

        int offset = params.path("offset").asInt(1);
        int limit = params.path("limit").asInt(MAX_LINES);
        if (offset < 1) offset = 1;
        if (limit < 1) limit = MAX_LINES;
        limit = Math.min(limit, MAX_LINES);

        Path path = context.resolvePath(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }

        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a regular file: " + path + ". Use the list tool for directories.");
        }

        try {
            long size = Files.size(path);
            // Check if binary
            if (isBinary(path)) {
                return ToolResult.success(path.getFileName().toString(),
                        "(binary file, " + size + " bytes)",
                        Map.of("binary", true, "size", size));
            }

            List<String> allLines = Files.readAllLines(path);
            int totalLines = allLines.size();

            int startIdx = offset - 1; // convert to 0-based
            int endIdx = Math.min(startIdx + limit, totalLines);

            if (startIdx >= totalLines) {
                return ToolResult.success(path.getFileName().toString(),
                        "(file has " + totalLines + " lines, offset " + offset + " is past end)",
                        Map.of("totalLines", totalLines));
            }

            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                String line = allLines.get(i);
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "... (truncated)";
                }
                sb.append(String.format("%6d\t%s%n", i + 1, line));
            }

            boolean truncated = endIdx < totalLines;
            Map<String, Object> meta = Map.of(
                    "totalLines", totalLines,
                    "linesShown", endIdx - startIdx,
                    "truncated", truncated
            );

            String title;
            try {
                title = context.getWorkingDirectory().toAbsolutePath().relativize(path.toAbsolutePath()).toString();
            } catch (IllegalArgumentException ex) {
                title = path.toString();
            }
            return ToolResult.success(title, sb.toString(), meta);

        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }
    }

    private boolean isBinary(Path path) throws IOException {
        byte[] header = new byte[512];
        try (var is = Files.newInputStream(path)) {
            int read = is.read(header);
            if (read <= 0) return false;
            for (int i = 0; i < read; i++) {
                if (header[i] == 0) return true;
            }
        }
        return false;
    }
}
