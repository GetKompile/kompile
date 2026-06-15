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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Apply a unified diff patch to a file using the system's patch command.
 * Comparable to OpenCode's PatchTool.
 */
public class PatchTool implements CliTool {

    @Override
    public String id() { return "patch"; }

    @Override
    public String description() {
        return "Apply a unified diff patch to a file. Provide the file path and the patch content " +
                "in unified diff format. The patch is applied using the system's patch command. " +
                "Use this when you need to apply multi-hunk changes that are easier to express as diffs.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "The file to patch");

        ObjectNode patch = props.putObject("patch");
        patch.put("type", "string");
        patch.put("description", "The unified diff patch content");

        schema.putArray("required").add("file_path").add("patch");
        return schema;
    }

    @Override
    public String permissionKey() { return "edit"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = params.path("file_path").asText("");
        String patch = params.path("patch").asText("");

        if (filePath.isEmpty()) return ToolResult.error("file_path is required");
        if (patch.isEmpty()) return ToolResult.error("patch is required");

        Path path = context.resolvePath(filePath);
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }

        context.checkPermission(permissionKey(), "Patch file: " + path);

        try {
            // Write patch to temp file
            Path patchFile = Files.createTempFile("kompile-patch-", ".diff");
            Files.writeString(patchFile, patch);

            try {
                ProcessBuilder pb = new ProcessBuilder("patch", "-u", path.toString(), patchFile.toString());
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                boolean done = process.waitFor(30, TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                    return ToolResult.error("Patch command timed out");
                }

                int exitCode = process.exitValue();
                String relativePath;
                try {
                    relativePath = context.getWorkingDirectory().toAbsolutePath().relativize(path.toAbsolutePath()).toString();
                } catch (IllegalArgumentException ex) {
                    relativePath = path.toString();
                }

                if (exitCode == 0) {
                    return ToolResult.success(relativePath,
                            "Patch applied successfully\n" + output.toString().trim(),
                            Map.of("path", relativePath));
                } else {
                    return ToolResult.error("Patch failed (exit " + exitCode + "):\n" + output.toString().trim());
                }
            } finally {
                Files.deleteIfExists(patchFile);
            }
        } catch (Exception e) {
            return ToolResult.error("Error applying patch: " + e.getMessage());
        }
    }
}
