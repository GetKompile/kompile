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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    public String compactHint() {
        return "Read file contents, line-numbered. offset=start line (1-based), limit=lines "
                + "(default & max 2000). Lines over 2000 chars are truncated. The shown "
                + "line-number prefix is display-only.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
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
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

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
            if (SearchExclusions.isLikelyBinaryFile(path)) {
                return ToolResult.success(path.getFileName().toString(),
                        "(binary file, " + size + " bytes)",
                        Map.of("binary", true, "size", size));
            }

            int startLine = Math.max(1, offset);
            int endExclusive = startLine + limit;
            boolean scanWhole = size <= MAX_FILE_SIZE;
            int totalLines = 0;
            int shown = 0;
            boolean truncated = false;
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    if (totalLines >= startLine && shown < limit) {
                        output.append(String.format("%6d\t%s%n", totalLines, truncateLine(line)));
                        shown++;
                    }
                    if (!scanWhole && totalLines > endExclusive) {
                        truncated = true;
                        break;
                    }
                }
            }

            boolean totalLinesKnown = !truncated;
            if (startLine > totalLines && totalLinesKnown) {
                return ToolResult.success(path.getFileName().toString(),
                        "(file has " + totalLines + " lines, offset " + offset + " is past end)",
                        Map.of("totalLines", totalLines, "totalLinesKnown", true));
            }

            context.recordFileRead(path);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("totalLines", totalLines);
            meta.put("totalLinesKnown", totalLinesKnown);
            meta.put("linesShown", shown);
            meta.put("truncated", truncated);
            if (output.isEmpty() && totalLinesKnown && startLine > totalLines) {
                return ToolResult.success(path.getFileName().toString(),
                        "(file has " + totalLines + " lines, offset " + offset + " is past end)",
                        meta);
            }

            String title;
            try {
                title = context.getWorkingDirectory().toAbsolutePath().relativize(path.toAbsolutePath()).toString();
            } catch (IllegalArgumentException ex) {
                title = path.toString();
            }
            return ToolResult.success(title, output.toString(), meta);

        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }
    }

    private static String truncateLine(String line) {
        if (line == null) {
            return "";
        }
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + "... (truncated)";
    }
}
