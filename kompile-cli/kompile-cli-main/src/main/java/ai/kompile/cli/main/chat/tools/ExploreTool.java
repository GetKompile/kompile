/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Explore a codebase directory recursively to understand its structure.
 * Combines Claude Code's Explore agent pattern (recursive tree with depth control,
 * .gitignore-awareness, language detection) with Codex's tree-view output format.
 *
 * <p>Unlike {@link ListTool} which only shows immediate children, this tool produces
 * a full recursive tree view with file counts, language breakdown, and optional
 * content glimpses (first N lines of key files like README, package.json, pom.xml).
 *
 * <p>Unlike {@link GlobTool} which finds files matching a pattern, this tool gives
 * a structural overview of a directory — answering "what's in this project?" rather
 * than "where is this file?".
 */
public class ExploreTool implements CliTool {

    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_ENTRIES = 500;
    private static final int DEFAULT_GLIMPSE_LINES = 10;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "__pycache__", ".gradle",
            "build", "dist", ".next", ".nuxt", ".cache", "venv", ".venv",
            ".tox", ".mypy_cache", ".pytest_cache", "vendor", ".idea",
            ".vscode", ".angular", "out", ".svn", "coverage", ".nyc_output"
    );

    private static final Set<String> KEY_FILES = Set.of(
            "README.md", "readme.md", "README.txt", "README",
            "package.json", "pom.xml", "build.gradle", "Cargo.toml",
            "go.mod", "pyproject.toml", "setup.py", "Makefile",
            "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "CLAUDE.md", ".env.example", "tsconfig.json", "Gemfile"
    );

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            Map.entry(".java", "Java"), Map.entry(".kt", "Kotlin"), Map.entry(".scala", "Scala"),
            Map.entry(".py", "Python"), Map.entry(".js", "JavaScript"), Map.entry(".ts", "TypeScript"),
            Map.entry(".tsx", "TypeScript/React"), Map.entry(".jsx", "JavaScript/React"),
            Map.entry(".rs", "Rust"), Map.entry(".go", "Go"), Map.entry(".rb", "Ruby"),
            Map.entry(".c", "C"), Map.entry(".cpp", "C++"), Map.entry(".h", "C/C++ Header"),
            Map.entry(".cs", "C#"), Map.entry(".swift", "Swift"), Map.entry(".m", "Objective-C"),
            Map.entry(".php", "PHP"), Map.entry(".lua", "Lua"), Map.entry(".sh", "Shell"),
            Map.entry(".bash", "Bash"), Map.entry(".zsh", "Zsh"),
            Map.entry(".html", "HTML"), Map.entry(".css", "CSS"), Map.entry(".scss", "SCSS"),
            Map.entry(".xml", "XML"), Map.entry(".json", "JSON"), Map.entry(".yaml", "YAML"),
            Map.entry(".yml", "YAML"), Map.entry(".toml", "TOML"), Map.entry(".md", "Markdown"),
            Map.entry(".sql", "SQL"), Map.entry(".proto", "Protobuf"),
            Map.entry(".dart", "Dart"), Map.entry(".ex", "Elixir"), Map.entry(".erl", "Erlang"),
            Map.entry(".zig", "Zig"), Map.entry(".nim", "Nim"), Map.entry(".v", "V"),
            Map.entry(".clj", "Clojure"), Map.entry(".hs", "Haskell")
    );

    @Override
    public String id() { return "explore"; }

    @Override
    public String description() {
        return "Explore a codebase directory recursively to understand its structure, languages, " +
                "and layout. Returns a tree view with file/directory counts, language breakdown, " +
                "total size, and optionally glimpses of key files (README, pom.xml, package.json, etc.). " +
                "Use this to quickly understand what a project contains and how it's organized. " +
                "Respects .gitignore and skips common non-source directories (node_modules, target, .git). " +
                "Unlike 'list' (single-level) or 'glob' (pattern match), this gives a structural overview.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory to explore (default: working directory)");

        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "Maximum recursion depth (default: 3, max: 10). " +
                "Use 1 for just top-level overview, higher for deeper exploration.");

        ObjectNode includeGlimpses = props.putObject("include_glimpses");
        includeGlimpses.put("type", "boolean");
        includeGlimpses.put("description", "Include content previews of key files like README.md, " +
                "pom.xml, package.json (default: true). Set false for tree-only output.");

        ObjectNode glimpseLines = props.putObject("glimpse_lines");
        glimpseLines.put("type", "integer");
        glimpseLines.put("description", "Number of lines to show per key file glimpse (default: 10, max: 50)");

        ObjectNode filterPattern = props.putObject("filter");
        filterPattern.put("type", "string");
        filterPattern.put("description", "Regex pattern to filter shown entries (e.g. '.*\\.java' to only show Java files in tree)");

        ObjectNode showHidden = props.putObject("show_hidden");
        showHidden.put("type", "boolean");
        showHidden.put("description", "Include hidden files/directories (default: false)");

        ObjectNode respectGitignore = props.putObject("respect_gitignore");
        respectGitignore.put("type", "boolean");
        respectGitignore.put("description", "Respect .gitignore patterns (default: true)");

        schema.putArray("required"); // No required params — defaults to cwd with depth 3
        return schema;
    }

    @Override
    public String permissionKey() { return "explore"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Explore directory structure");

        String dirPath = params.path("path").asText("");
        int depth = Math.min(params.path("depth").asInt(DEFAULT_DEPTH), MAX_DEPTH);
        boolean includeGlimpses = params.path("include_glimpses").isMissingNode() ||
                params.path("include_glimpses").asBoolean(true);
        int glimpseLines = Math.min(params.path("glimpse_lines").asInt(DEFAULT_GLIMPSE_LINES), 50);
        String filterStr = params.path("filter").asText("");
        boolean showHidden = params.path("show_hidden").asBoolean(false);
        boolean respectGitignore = params.path("respect_gitignore").isMissingNode() ||
                params.path("respect_gitignore").asBoolean(true);

        Path dir = dirPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(dirPath);

        if (!Files.isDirectory(dir)) {
            return ToolResult.error("Not a directory: " + dir);
        }

        Pattern filter = filterStr.isEmpty() ? null : Pattern.compile(filterStr);
        Set<String> gitignorePatterns = respectGitignore ? loadGitignore(dir) : Set.of();

        // Collect tree structure
        TreeNode root = buildTree(dir, dir, depth, showHidden, gitignorePatterns, filter);

        // Compute statistics
        Stats stats = computeStats(root);

        // Build output
        StringBuilder sb = new StringBuilder();

        // Header
        String displayPath;
        try {
            displayPath = context.getWorkingDirectory().toAbsolutePath()
                    .relativize(dir.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            displayPath = dir.toString();
        }
        if (displayPath.isEmpty()) displayPath = ".";

        sb.append("## ").append(displayPath).append("\n\n");

        // Summary stats
        sb.append("**Files**: ").append(stats.fileCount)
                .append(" | **Directories**: ").append(stats.dirCount)
                .append(" | **Total size**: ").append(formatSize(stats.totalSize)).append("\n");
        if (stats.entriesExceeded) {
            sb.append("*(tree truncated at ").append(MAX_ENTRIES).append(" entries)*\n");
        }
        sb.append("\n");

        // Language breakdown
        if (!stats.languageCounts.isEmpty()) {
            sb.append("### Languages\n");
            stats.languageCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> sb.append("- ").append(e.getKey())
                            .append(": ").append(e.getValue()).append(" files\n"));
            sb.append("\n");
        }

        // Tree view
        sb.append("### Directory Tree\n```\n");
        renderTree(sb, root, "", true);
        sb.append("```\n");

        // Key file glimpses
        if (includeGlimpses && !root.keyFileContents.isEmpty()) {
            sb.append("\n### Key Files\n");
            for (Map.Entry<String, String> entry : root.keyFileContents.entrySet()) {
                sb.append("\n**").append(entry.getKey()).append("**\n```\n");
                String content = entry.getValue();
                String[] lines = content.split("\n", -1);
                int linesToShow = Math.min(lines.length, glimpseLines);
                for (int i = 0; i < linesToShow; i++) {
                    sb.append(lines[i]).append("\n");
                }
                if (lines.length > glimpseLines) {
                    sb.append("... (").append(lines.length - glimpseLines).append(" more lines)\n");
                }
                sb.append("```\n");
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileCount", stats.fileCount);
        metadata.put("dirCount", stats.dirCount);
        metadata.put("totalSize", stats.totalSize);
        metadata.put("depth", depth);
        metadata.put("truncated", stats.entriesExceeded);
        if (!stats.languageCounts.isEmpty()) {
            metadata.put("languages", stats.languageCounts);
        }

        return ToolResult.success("explore: " + displayPath, sb.toString(), metadata);
    }

    private TreeNode buildTree(Path root, Path current, int maxDepth, boolean showHidden,
                               Set<String> gitignorePatterns, Pattern filter) {
        TreeNode node = new TreeNode();
        node.name = current.getFileName() != null ? current.getFileName().toString() : current.toString();
        node.isDirectory = true;
        node.path = root.relativize(current).toString();
        if (node.path.isEmpty()) node.path = ".";

        if (maxDepth <= 0) {
            node.collapsed = true;
            return node;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            List<Path> entries = new ArrayList<>();
            for (Path entry : stream) {
                entries.add(entry);
            }

            // Sort: directories first, then alphabetical
            entries.sort((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });

            int entryCount = 0;
            for (Path entry : entries) {
                if (entryCount >= MAX_ENTRIES) {
                    node.entriesExceeded = true;
                    break;
                }

                String name = entry.getFileName().toString();

                // Skip hidden files unless requested
                if (!showHidden && name.startsWith(".")) continue;

                // Skip well-known non-source directories
                if (Files.isDirectory(entry) && SKIP_DIRS.contains(name)) continue;

                // Respect .gitignore
                if (!gitignorePatterns.isEmpty()) {
                    String relativePath = root.relativize(entry).toString();
                    if (isGitignored(relativePath, name, Files.isDirectory(entry), gitignorePatterns)) {
                        continue;
                    }
                }

                // Apply filter
                if (filter != null && !Files.isDirectory(entry)) {
                    if (!filter.matcher(name).matches()) continue;
                }

                if (Files.isDirectory(entry)) {
                    TreeNode child = buildTree(root, entry, maxDepth - 1, showHidden, gitignorePatterns, filter);
                    node.children.add(child);
                    entryCount += 1 + child.totalEntries();
                } else {
                    TreeNode fileNode = new TreeNode();
                    fileNode.name = name;
                    fileNode.isDirectory = false;
                    fileNode.path = root.relativize(entry).toString();
                    try {
                        fileNode.size = Files.size(entry);
                    } catch (IOException ignored) {}
                    node.children.add(fileNode);
                    entryCount++;

                    // Capture key file content for glimpses
                    if (KEY_FILES.contains(name) && fileNode.size < 100_000) {
                        try {
                            String content = Files.readString(entry);
                            node.keyFileContents.put(fileNode.path, content);
                        } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            node.error = e.getMessage();
        }

        return node;
    }

    private void renderTree(StringBuilder sb, TreeNode node, String prefix, boolean isRoot) {
        if (isRoot) {
            sb.append(node.name).append("/\n");
        }

        List<TreeNode> children = node.children;
        for (int i = 0; i < children.size(); i++) {
            TreeNode child = children.get(i);
            boolean isLast = (i == children.size() - 1);
            String connector = isLast ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 ";
            String childPrefix = isLast ? "    " : "\u2502   ";

            sb.append(prefix).append(connector);
            if (child.isDirectory) {
                sb.append(child.name).append("/");
                if (child.collapsed) {
                    sb.append(" (...)");
                } else if (child.entriesExceeded) {
                    sb.append(" (truncated)");
                }
                int fileCount = child.fileCount();
                int dirCount = child.dirCount();
                if (fileCount > 0 || dirCount > 0) {
                    sb.append(" [");
                    if (dirCount > 0) sb.append(dirCount).append(" dirs, ");
                    sb.append(fileCount).append(" files");
                    sb.append("]");
                }
                sb.append("\n");
                if (!child.collapsed) {
                    renderTree(sb, child, prefix + childPrefix, false);
                }
            } else {
                sb.append(child.name);
                if (child.size > 0) {
                    sb.append(" (").append(formatSize(child.size)).append(")");
                }
                sb.append("\n");
            }
        }
    }

    private Stats computeStats(TreeNode node) {
        Stats stats = new Stats();
        computeStatsRecursive(node, stats);
        return stats;
    }

    private void computeStatsRecursive(TreeNode node, Stats stats) {
        if (node.entriesExceeded) stats.entriesExceeded = true;

        for (TreeNode child : node.children) {
            if (child.isDirectory) {
                stats.dirCount++;
                computeStatsRecursive(child, stats);
            } else {
                stats.fileCount++;
                stats.totalSize += child.size;

                // Detect language
                String ext = getExtension(child.name);
                if (!ext.isEmpty()) {
                    String lang = EXTENSION_TO_LANGUAGE.get(ext);
                    if (lang != null) {
                        stats.languageCounts.merge(lang, 1, Integer::sum);
                    }
                }
            }
        }

        // Bubble up key file contents
        stats.keyFiles.putAll(node.keyFileContents);
    }

    private Set<String> loadGitignore(Path dir) {
        Set<String> patterns = new HashSet<>();
        Path gitignore = dir.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            try {
                List<String> lines = Files.readAllLines(gitignore);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    patterns.add(line);
                }
            } catch (IOException ignored) {}
        }
        return patterns;
    }

    private boolean isGitignored(String relativePath, String name, boolean isDir,
                                 Set<String> patterns) {
        for (String pattern : patterns) {
            // Simple gitignore matching (covers most common cases)
            String p = pattern;
            boolean dirOnly = p.endsWith("/");
            if (dirOnly) {
                p = p.substring(0, p.length() - 1);
                if (!isDir) continue;
            }

            // Direct name match
            if (name.equals(p) || relativePath.equals(p)) return true;

            // Glob-style matching
            if (p.contains("*")) {
                try {
                    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + p);
                    if (matcher.matches(Path.of(name)) || matcher.matches(Path.of(relativePath))) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }

            // Directory prefix match (e.g., "build" matches "build/...")
            if (isDir && relativePath.startsWith(p + "/")) return true;
        }
        return false;
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fM", bytes / (1024.0 * 1024));
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }

    // ── Internal data structures ──────────────────────────────────────────

    private static class TreeNode {
        String name;
        String path;
        boolean isDirectory;
        long size;
        boolean collapsed;
        boolean entriesExceeded;
        String error;
        List<TreeNode> children = new ArrayList<>();
        Map<String, String> keyFileContents = new LinkedHashMap<>();

        int totalEntries() {
            int count = children.size();
            for (TreeNode child : children) {
                if (child.isDirectory) count += child.totalEntries();
            }
            return count;
        }

        int fileCount() {
            int count = 0;
            for (TreeNode child : children) {
                if (child.isDirectory) count += child.fileCount();
                else count++;
            }
            return count;
        }

        int dirCount() {
            int count = 0;
            for (TreeNode child : children) {
                if (child.isDirectory) {
                    count++;
                    count += child.dirCount();
                }
            }
            return count;
        }
    }

    private static class Stats {
        int fileCount;
        int dirCount;
        long totalSize;
        boolean entriesExceeded;
        Map<String, Integer> languageCounts = new LinkedHashMap<>();
        Map<String, String> keyFiles = new LinkedHashMap<>();
    }
}
