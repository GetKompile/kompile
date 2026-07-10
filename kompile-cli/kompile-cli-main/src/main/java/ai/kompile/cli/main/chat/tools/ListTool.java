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
import ai.kompile.utils.FormatUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * List directory contents with metadata (size, type, modification time).
 * Comparable to OpenCode's ListTool.
 */
public class ListTool implements CliTool {

    private static final int MAX_ENTRIES = 500;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    @Override
    public String id() { return "list"; }

    @Override
    public String description() {
        return "List the contents of a directory with file metadata (size, type, modification time). " +
                "Use this to explore project structure and understand directory layout.";
    }

    @Override
    public String compactHint() {
        return "List ONE directory (non-recursive) with sizes/mtimes. Use glob to search file "
                + "names recursively, read for file contents, grep for contents by regex.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory to list (default: working directory)");

        schema.putArray("required"); // No required params
        return schema;
    }

    @Override
    public String permissionKey() { return "list"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "List directory");

        String dirPath = params.path("path").asText("");
        Path dir = dirPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(dirPath);

        if (!Files.isDirectory(dir)) {
            return ToolResult.error("Not a directory: " + dir);
        }

        try {
            List<String> entries = new ArrayList<>();
            int fileCount = 0, dirCount = 0;
            int totalEntries = 0;
            boolean truncated = false;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (totalEntries >= MAX_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        continue;
                    }
                    totalEntries++;
                    String name = entry.getFileName().toString();
                    String type = attrs.isDirectory() ? "dir"
                            : attrs.isRegularFile() ? "file"
                            : attrs.isSymbolicLink() ? "symlink" : "other";
                    long size = attrs.size();
                    Instant mtime = attrs.lastModifiedTime().toInstant();

                    String sizeStr = attrs.isDirectory() ? "-" : FormatUtils.formatBytesCompact(size);
                    entries.add(String.format("%-4s  %8s  %s  %s",
                            type, sizeStr, DATE_FMT.format(mtime), name));

                    if (attrs.isDirectory()) dirCount++;
                    else fileCount++;
                }
            }

            entries.sort(String::compareTo);

            String title;
            try {
                title = context.getWorkingDirectory().toAbsolutePath().relativize(dir.toAbsolutePath()).toString();
            } catch (IllegalArgumentException e) {
                title = dir.toString();
            }
            if (title.isEmpty()) title = ".";

            StringBuilder sb = new StringBuilder();
            for (String e : entries) {
                sb.append(e).append("\n");
            }

            return ToolResult.success(title, sb.toString().trim(),
                    Map.of("files", fileCount, "directories", dirCount, "truncated", truncated));

        } catch (IOException e) {
            return ToolResult.error("Error listing directory: " + e.getMessage());
        }
    }

}
