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
                "(most recent first). Bare filename patterns like 'pom.xml' or '*.java' search " +
                "recursively; path patterns like 'src/**/*.ts' are also supported. Hidden files and " +
                "directories are skipped by default; set 'hidden' to true to include them. Maximum " +
                "100 results returned. Use this to discover files before reading or editing them.";
    }

    @Override
    public String compactHint() {
        return "Find FILES by name/path (not contents; use grep). Bare names like pom.xml or "
                + "*.java search recursively; path globs like src/**/*.ts work. Max 100, newest first.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "The glob pattern to match files against. Bare names like 'pom.xml' or '*.java' search recursively; path globs like 'src/**/*.ts' are also supported.");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory to search in (default: working directory)");

        ObjectNode hidden = props.putObject("hidden");
        hidden.put("type", "boolean");
        hidden.put("description", "Include hidden files and directories (dot-prefixed). Default false. "
                + "Heavy trees like .git/node_modules/target are always skipped regardless.");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String permissionKey() { return "glob"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search files");

        String pattern = params.path("pattern").asText("");
        String searchPath = params.path("path").asText("");
        final boolean includeHidden = params.path("hidden").asBoolean(false);

        if (pattern.isEmpty()) {
            return ToolResult.error("pattern is required");
        }

        Path dir = searchPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(searchPath);

        if (!Files.isDirectory(dir)) {
            return ToolResult.error("Not a directory: " + dir);
        }

        // Prune git-ignored data directories (model builds, corpora, generated indices) so a
        // recursive glob over a large repo doesn't walk gigabytes the project never tracks.
        final SearchExclusions.GitignoreDirFilter gitFilter = SearchExclusions.loadGitignoreDirFilter(dir);

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            PathMatcher rootMatcher = rootEquivalentMatcher(pattern);
            boolean basenameOnlyPattern = isBasenameOnlyPattern(pattern);

            PriorityQueue<GlobMatch> matches = new PriorityQueue<>(GlobMatch.OLDEST_FIRST);
            final int[] totalMatches = {0};
            final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            int maxDepth = traversalDepthForGlob(pattern);
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    // Skip hidden files (dot-prefixed basename) unless opted in, so file-level
                    // results stay consistent with the pruned hidden directories below and with
                    // the grep/ripgrep backends.
                    if (!includeHidden) {
                        Path fileName = file.getFileName();
                        if (fileName != null && fileName.toString().startsWith(".")) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    Path relative = dir.relativize(file);
                    if (matchesGlob(matcher, rootMatcher, basenameOnlyPattern, relative)) {
                        totalMatches[0]++;
                        GlobMatch match = new GlobMatch(file, attrs.lastModifiedTime());
                        if (matches.size() < MAX_RESULTS) {
                            matches.add(match);
                        } else if (GlobMatch.OLDEST_FIRST.compare(match, matches.peek()) > 0) {
                            matches.poll();
                            matches.add(match);
                        }
                    }
                    return FileVisitResult.CONTINUE;
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
                        String rel = gitFilter.relativePath(dirPath, dir);
                        if (SearchExclusions.isExcludedDir(name, includeHidden)
                                || gitFilter.isIgnoredDir(rel, name)) {
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

            List<GlobMatch> sortedMatches = new ArrayList<>(matches);
            sortedMatches.sort(GlobMatch.NEWEST_FIRST);

            List<Path> limited = sortedMatches.stream().map(GlobMatch::path).collect(Collectors.toList());
            boolean truncated = totalMatches[0] > MAX_RESULTS || timedOut;

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
                            "totalMatches", totalMatches[0], "timedOut", timedOut));

        } catch (Exception e) {
            return ToolResult.error("Error searching files: " + e.getMessage());
        }
    }

    static int traversalDepthForGlob(String pattern) {
        if (pattern == null || pattern.contains("**") || isBasenameOnlyPattern(pattern)) {
            return Integer.MAX_VALUE;
        }
        String normalized = pattern.replace('\\', '/');
        int depth = 1;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '/') {
                depth++;
            }
        }
        return Math.max(1, depth);
    }

    static boolean isBasenameOnlyPattern(String pattern) {
        return pattern != null && !pattern.contains("/") && !pattern.contains("\\");
    }

    private static PathMatcher rootEquivalentMatcher(String pattern) {
        if (pattern == null) {
            return null;
        }
        String normalized = pattern.replace('\\', '/');
        if (!normalized.startsWith("**/")) {
            return null;
        }
        String rootPattern = normalized.substring(3);
        if (rootPattern.isEmpty()) {
            return null;
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + rootPattern);
    }

    private static boolean matchesGlob(PathMatcher matcher, PathMatcher rootMatcher,
                                       boolean basenameOnlyPattern, Path relative) {
        if (basenameOnlyPattern) {
            Path fileName = relative.getFileName();
            return fileName != null && matcher.matches(fileName);
        }
        return matcher.matches(relative) || (rootMatcher != null && rootMatcher.matches(relative));
    }

    private record GlobMatch(Path path, java.nio.file.attribute.FileTime modifiedTime) {
        private static final Comparator<GlobMatch> OLDEST_FIRST = Comparator
                .comparing(GlobMatch::modifiedTime)
                .thenComparing(match -> match.path().toString());
        private static final Comparator<GlobMatch> NEWEST_FIRST = OLDEST_FIRST.reversed();
    }
}
