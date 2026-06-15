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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Find files matching glob patterns. Returns file paths sorted by modification time.
 * Comparable to OpenCode's GlobTool.
 */
public class GlobTool implements CliTool {

    private static final int MAX_RESULTS = 100;

    @Override
    public String id() { return "glob"; }

    @Override
    public String description() {
        return "Find files matching a glob pattern. Returns file paths sorted by modification time " +
                "(most recent first). Supports patterns like '**/*.java', 'src/**/*.ts', '*.xml'. " +
                "Maximum 100 results returned. Use this to discover files before reading or editing them.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "The glob pattern to match files against (e.g. '**/*.java')");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory to search in (default: working directory)");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String permissionKey() { return "glob"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search files");

        String pattern = params.path("pattern").asText("");
        String searchPath = params.path("path").asText("");

        if (pattern.isEmpty()) {
            return ToolResult.error("pattern is required");
        }

        Path dir = searchPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(searchPath);

        if (!Files.isDirectory(dir)) {
            return ToolResult.error("Not a directory: " + dir);
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<Path> matches = new ArrayList<>();
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = dir.relativize(file);
                    if (matcher.matches(relative)) {
                        matches.add(file);
                    }
                    return matches.size() >= MAX_RESULTS * 2 ?
                            FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dirPath, BasicFileAttributes attrs) {
                    String name = dirPath.getFileName() != null ? dirPath.getFileName().toString() : "";
                    // Skip hidden dirs and common large dirs
                    if (name.startsWith(".") || "node_modules".equals(name) ||
                            "target".equals(name) || "__pycache__".equals(name) ||
                            ".git".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            // Sort by modification time (most recent first)
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });

            List<Path> limited = matches.stream().limit(MAX_RESULTS).collect(Collectors.toList());
            boolean truncated = matches.size() > MAX_RESULTS;

            if (limited.isEmpty()) {
                return ToolResult.success("No files matching: " + pattern);
            }

            StringBuilder sb = new StringBuilder();
            for (Path p : limited) {
                sb.append(context.getWorkingDirectory().relativize(p)).append("\n");
            }

            return ToolResult.success("glob: " + pattern,
                    sb.toString().trim(),
                    Map.of("count", limited.size(), "truncated", truncated,
                            "totalMatches", matches.size()));

        } catch (Exception e) {
            return ToolResult.error("Error searching files: " + e.getMessage());
        }
    }
}
