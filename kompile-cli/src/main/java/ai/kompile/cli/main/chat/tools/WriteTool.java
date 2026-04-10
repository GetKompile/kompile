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
 * Write/create a file with the given content. Overwrites existing files.
 * Comparable to OpenCode's WriteTool.
 */
public class WriteTool implements CliTool {

    @Override
    public String id() { return "write"; }

    @Override
    public String description() {
        return "Create a new file or overwrite an existing file with the given content. " +
                "Use this tool for creating new files. For modifying existing files, " +
                "prefer the edit tool which performs targeted string replacements. " +
                "Parent directories are created automatically if they don't exist.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "The path to the file to write (absolute or relative to working directory)");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "The content to write to the file");

        schema.putArray("required").add("file_path").add("content");
        return schema;
    }

    @Override
    public String permissionKey() { return "edit"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = params.path("file_path").asText("");
        String content = params.path("content").asText("");

        if (filePath.isEmpty()) {
            return ToolResult.error("file_path is required");
        }

        Path path = context.resolvePath(filePath);
        boolean exists = Files.exists(path);

        context.checkPermission(permissionKey(),
                (exists ? "Overwrite" : "Create") + " file: " + path);

        try {
            // Create parent directories
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content);

            long lines = content.lines().count();
            String relativePath;
            try {
                relativePath = context.getWorkingDirectory().toAbsolutePath().relativize(path.toAbsolutePath()).toString();
            } catch (IllegalArgumentException ex) {
                relativePath = path.toString();
            }
            return ToolResult.success(relativePath,
                    (exists ? "Overwrote" : "Created") + " file with " + lines + " lines",
                    Map.of("path", relativePath, "lines", lines, "created", !exists));
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }
    }
}
