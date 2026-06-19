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

    /** Hard wall-clock cap on a single glob walk, so a pattern that matches few/no files
     *  (the match-count early-termination never triggers) cannot walk a huge tree unbounded. */
    private static final long TIMEOUT_MILLIS = 15_000;

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
        ObjectMapper om = JsonUtils.standardMapper();
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
            final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = dir.relativize(file);
                    if (matcher.matches(relative)) {
                        matches.add(file);
                    }
                    return matches.size() >= MAX_RESULTS * 2 ?
                            FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dirPath, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) {
                        return FileVisitResult.TERMINATE;
                    }
                    // Never prune the explicitly requested root, even if it is hidden or a build
                    // dir; only prune excluded directories encountered while descending.
                    if (!dirPath.equals(dir)) {
                        String name = dirPath.getFileName() != null ? dirPath.getFileName().toString() : "";
                        if (SearchExclusions.isExcludedDir(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            boolean timedOut = System.currentTimeMillis() > deadline;

            // Sort by modification time (most recent first)
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });

            List<Path> limited = matches.stream().limit(MAX_RESULTS).collect(Collectors.toList());
            boolean truncated = matches.size() > MAX_RESULTS || timedOut;

            if (limited.isEmpty()) {
                return ToolResult.success(timedOut
                        ? "Search timed out after " + (TIMEOUT_MILLIS / 1000) + "s before matching: " + pattern
                          + " (tree too large — narrow the search with 'path')"
                        : "No files matching: " + pattern);
            }

            StringBuilder sb = new StringBuilder();
            for (Path p : limited) {
                sb.append(context.getWorkingDirectory().relativize(p)).append("\n");
            }

            return ToolResult.success("glob: " + pattern,
                    sb.toString().trim() + (timedOut ? "\n... (search timed out — results partial)" : ""),
                    Map.of("count", limited.size(), "truncated", truncated,
                            "totalMatches", matches.size(), "timedOut", timedOut));

        } catch (Exception e) {
            return ToolResult.error("Error searching files: " + e.getMessage());
        }
    }
}
