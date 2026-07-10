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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.FormatUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
    private static final int DEFAULT_MAX_ENTRIES = 500;
    private static final int HARD_MAX_ENTRIES = 2_000;
    private static final int DEFAULT_GLIMPSE_LINES = 10;

    /** Hard wall-clock cap on a single explore so a deep tree on a large repo can't hang the
     *  MCP client. Mirrors the caps in {@link GrepTool} and {@link GlobTool}; directory
     *  pruning is shared via {@link SearchExclusions} (was a private SKIP_DIRS copy). */
    private static final long TIMEOUT_MILLIS = 15_000;

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
    public String compactHint() {
        return "Recursive project tree. Params: path, depth, hidden/show_hidden, max_entries, "
                + "include_glimpses. Prunes heavy dirs; output may be truncated.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
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
        filterPattern.put("description", "Regex pattern to filter shown entries by basename or relative path (e.g. '.*\\.java')");

        ObjectNode maxEntries = props.putObject("max_entries");
        maxEntries.put("type", "integer");
        maxEntries.put("description", "Maximum entries to show per directory after sorting (default: 500, max: 2000)");

        ObjectNode showHidden = props.putObject("show_hidden");
        showHidden.put("type", "boolean");
        showHidden.put("description", "Include hidden files/directories (default: false). Alias: hidden");

        ObjectNode hidden = props.putObject("hidden");
        hidden.put("type", "boolean");
        hidden.put("description", "Alias for show_hidden, matching grep/glob parameter naming");

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
        int glimpseLines = Math.max(0, Math.min(params.path("glimpse_lines").asInt(DEFAULT_GLIMPSE_LINES), 50));
        String filterStr = params.path("filter").asText("");
        int maxEntries = Math.max(1, Math.min(params.path("max_entries").asInt(DEFAULT_MAX_ENTRIES), HARD_MAX_ENTRIES));
        boolean showHidden = params.has("show_hidden")
                ? params.path("show_hidden").asBoolean(false)
                : params.path("hidden").asBoolean(false);
        boolean respectGitignore = params.path("respect_gitignore").isMissingNode() ||
                params.path("respect_gitignore").asBoolean(true);

        Path dir = dirPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(dirPath);

        if (!Files.isDirectory(dir)) {
            return ToolResult.error("Not a directory: " + dir);
        }

        Pattern filter = filterStr.isEmpty() ? null : Pattern.compile(filterStr);
        Set<String> gitignorePatterns = respectGitignore ? loadGitignore(dir) : Set.of();
        SearchExclusions.GitignoreDirFilter gitFilter = respectGitignore
                ? SearchExclusions.loadGitignoreDirFilter(dir)
                : SearchExclusions.GitignoreDirFilter.EMPTY;

        // Collect tree structure (bounded by a hard wall-clock deadline)
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        AtomicBoolean timedOut = new AtomicBoolean(false);
        TreeNode root = buildTree(dir, dir, depth, showHidden, gitignorePatterns, filter,
                gitFilter, maxEntries, glimpseLines, deadline, timedOut);

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
                .append(" | **Total size**: ").append(FormatUtils.formatBytesCompact(stats.totalSize)).append("\n");
        if (stats.entriesExceeded) {
            sb.append("*(tree truncated at ").append(maxEntries).append(" entries per directory");
            if (stats.omittedEntryCount > 0) {
                sb.append("; ").append(stats.omittedEntryCount).append(" entries omitted");
            }
            sb.append(")*\n");
        }
        if (timedOut.get()) {
            sb.append("*(exploration timed out after ").append(TIMEOUT_MILLIS / 1000)
                    .append("s — tree only partially scanned; narrow with 'path' or a lower 'depth')*\n");
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
        if (includeGlimpses && !stats.keyFiles.isEmpty()) {
            sb.append("\n### Key Files\n");
            for (Map.Entry<String, String> entry : stats.keyFiles.entrySet()) {
                sb.append("\n**").append(entry.getKey()).append("**\n```\n");
                String content = entry.getValue();
                String[] lines = content.split("\n", -1);
                int linesToShow = Math.min(lines.length, glimpseLines);
                for (int i = 0; i < linesToShow; i++) {
                    sb.append(lines[i]).append("\n");
                }
                if (lines.length > glimpseLines) {
                    sb.append("... (more lines)\n");
                }
                sb.append("```\n");
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileCount", stats.fileCount);
        metadata.put("dirCount", stats.dirCount);
        metadata.put("totalSize", stats.totalSize);
        metadata.put("depth", depth);
        metadata.put("maxEntriesPerDirectory", maxEntries);
        metadata.put("shownEntryCount", stats.shownEntryCount);
        metadata.put("scannedEntryCount", stats.scannedEntryCount);
        metadata.put("omittedEntryCount", stats.omittedEntryCount);
        metadata.put("symlinkCount", stats.symlinkCount);
        metadata.put("errorCount", stats.errorCount);
        metadata.put("truncated", stats.entriesExceeded || timedOut.get());
        metadata.put("timedOut", timedOut.get());
        if (!stats.languageCounts.isEmpty()) {
            metadata.put("languages", stats.languageCounts);
        }
        metadata.put("entries", collectEntries(root));
        if (!stats.errors.isEmpty()) {
            metadata.put("errors", stats.errors);
        }

        return ToolResult.success("explore: " + displayPath, sb.toString(), metadata);
    }

    private TreeNode buildTree(Path root, Path current, int maxDepth, boolean showHidden,
                               Set<String> gitignorePatterns, Pattern filter,
                               SearchExclusions.GitignoreDirFilter gitFilter,
                               int maxEntries, int glimpseLines,
                               long deadline, AtomicBoolean timedOut) {
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
            PriorityQueue<EntryWithType> keptEntries =
                    new PriorityQueue<>(maxEntries, EntryWithType.WORST_DISPLAY_ORDER);
            boolean entriesExceeded = false;
            int candidateCount = 0;
            for (Path entry : stream) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut.set(true);
                    entriesExceeded = true;
                    break;
                }

                String name = entry.getFileName().toString();
                if (!showHidden && name.startsWith(".")) {
                    continue;
                }

                BasicFileAttributes attrs = null;
                String entryError = null;
                try {
                    attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    entryError = e.getMessage();
                }

                boolean isDirectory = attrs != null && attrs.isDirectory();
                if (isDirectory && (SearchExclusions.isExcludedDir(name, showHidden)
                        || gitFilter.isIgnoredDir(gitFilter.relativePath(entry, root), name))) {
                    continue;
                }

                if (attrs != null && !gitignorePatterns.isEmpty()) {
                    String relativePath = root.relativize(entry).toString();
                    if (isGitignored(relativePath, name, isDirectory, gitignorePatterns)) {
                        continue;
                    }
                }

                if (filter != null && attrs != null && !isDirectory && !matchesFilter(filter, root, entry, name)) {
                    continue;
                }

                EntryWithType candidate = new EntryWithType(entry, attrs, entryError);
                candidateCount++;
                if (keptEntries.size() < maxEntries) {
                    keptEntries.add(candidate);
                } else if (EntryWithType.DISPLAY_ORDER.compare(candidate, keptEntries.peek()) < 0) {
                    keptEntries.poll();
                    keptEntries.add(candidate);
                }
            }
            node.scannedEntries = candidateCount;
            node.omittedEntries = Math.max(0, candidateCount - keptEntries.size());
            node.entriesExceeded = entriesExceeded || node.omittedEntries > 0;

            List<EntryWithType> entries = new ArrayList<>(keptEntries);
            entries.sort(EntryWithType.DISPLAY_ORDER);

            for (EntryWithType entry : entries) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut.set(true);
                    node.entriesExceeded = true;
                    break;
                }

                String name = entry.path().getFileName().toString();
                Path entryPath = entry.path();
                BasicFileAttributes attrs = entry.attrs();
                boolean isDirectory = entry.isDirectory();

                if (entry.error() != null) {
                    TreeNode errorNode = new TreeNode();
                    errorNode.name = name;
                    errorNode.path = root.relativize(entryPath).toString();
                    errorNode.error = entry.error();
                    node.children.add(errorNode);
                } else if (isDirectory) {
                    TreeNode child = buildTree(root, entryPath, maxDepth - 1, showHidden, gitignorePatterns, filter,
                            gitFilter, maxEntries, glimpseLines, deadline, timedOut);
                    if (filter == null || matchesFilter(filter, root, entryPath, name) || child.hasVisibleContent()) {
                        node.children.add(child);
                    }
                } else if (entry.isRegularFile()) {
                    TreeNode fileNode = new TreeNode();
                    fileNode.name = name;
                    fileNode.isDirectory = false;
                    fileNode.path = root.relativize(entryPath).toString();
                    fileNode.size = attrs.size();
                    node.children.add(fileNode);

                    // Capture key file content for glimpses
                    if (KEY_FILES.contains(name) && fileNode.size < 100_000) {
                        try {
                            if (!SearchExclusions.isLikelyBinaryFile(entryPath)) {
                                node.keyFileContents.put(fileNode.path, readFileGlimpse(entryPath, glimpseLines + 1));
                            }
                        } catch (IOException ignored) {
                        }
                    }
                } else if (entry.isSymlink()) {
                    TreeNode symlinkNode = new TreeNode();
                    symlinkNode.name = name;
                    symlinkNode.path = root.relativize(entryPath).toString();
                    symlinkNode.isSymlink = true;
                    symlinkNode.symlinkTarget = readSymlinkTarget(entryPath);
                    node.children.add(symlinkNode);
                } else {
                    TreeNode otherNode = new TreeNode();
                    otherNode.name = name;
                    otherNode.path = root.relativize(entryPath).toString();
                    otherNode.entryType = "other";
                    node.children.add(otherNode);
                }
            }
        } catch (IOException e) {
            node.error = e.getMessage();
        }

        return node;
    }

    private static String readFileGlimpse(Path file, int linesToRead) throws IOException {
        StringBuilder sb = new StringBuilder();
        int linesRead = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null && linesToRead > 0 && linesRead < linesToRead) {
                sb.append(line).append('\n');
                linesRead++;
            }
        }
        return sb.toString().trim();
    }

    private static String readSymlinkTarget(Path path) {
        try {
            return Files.readSymbolicLink(path).toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean matchesFilter(Pattern filter, Path root, Path entry, String name) {
        if (filter == null) {
            return true;
        }
        String relative = root.relativize(entry).toString().replace('\\', '/');
        return filter.matcher(name).find() || filter.matcher(relative).find();
    }

    private record EntryWithType(Path path, BasicFileAttributes attrs, String error) {
        private static final Comparator<EntryWithType> DISPLAY_ORDER = Comparator
                .comparingInt(EntryWithType::typeOrder)
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT))
                .thenComparing(EntryWithType::name);
        private static final Comparator<EntryWithType> WORST_DISPLAY_ORDER = DISPLAY_ORDER.reversed();

        String name() {
            return path.getFileName().toString();
        }

        int typeOrder() {
            if (isDirectory()) {
                return 0;
            }
            if (isRegularFile() || isSymlink()) {
                return 1;
            }
            return 2;
        }

        boolean isDirectory() {
            return attrs != null && attrs.isDirectory();
        }

        boolean isRegularFile() {
            return attrs != null && attrs.isRegularFile();
        }

        boolean isSymlink() {
            return attrs != null && attrs.isSymbolicLink();
        }
    }

    private void renderTree(StringBuilder sb, TreeNode node, String prefix, boolean isRoot) {
        if (isRoot) {
            sb.append(node.name).append("/");
            if (node.error != null) {
                sb.append(" (error: ").append(node.error).append(")");
            }
            sb.append("\n");
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
                if (child.error != null) {
                    sb.append(" (error: ").append(child.error).append(")");
                }
                if (child.collapsed) {
                    sb.append(" (...)");
                } else if (child.entriesExceeded) {
                    sb.append(" (truncated");
                    if (child.omittedEntries > 0) {
                        sb.append(", ").append(child.omittedEntries).append(" omitted");
                    }
                    sb.append(")");
                }
                int fileCount = child.fileCount();
                int dirCount = child.dirCount();
                int symlinkCount = child.symlinkCount();
                if (fileCount > 0 || dirCount > 0 || symlinkCount > 0) {
                    sb.append(" [");
                    if (dirCount > 0) sb.append(dirCount).append(" dirs, ");
                    sb.append(fileCount).append(" files");
                    if (symlinkCount > 0) sb.append(", ").append(symlinkCount).append(" symlinks");
                    sb.append("]");
                }
                sb.append("\n");
                if (!child.collapsed) {
                    renderTree(sb, child, prefix + childPrefix, false);
                }
            } else if (child.isSymlink) {
                sb.append(child.name).append(" -> ");
                sb.append(child.symlinkTarget != null ? child.symlinkTarget : "(unreadable target)");
                sb.append("\n");
            } else if (child.error != null) {
                sb.append(child.name).append(" (unreadable: ").append(child.error).append(")\n");
            } else {
                sb.append(child.name);
                if (child.size > 0) {
                    sb.append(" (").append(FormatUtils.formatBytesCompact(child.size)).append(")");
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
        stats.scannedEntryCount += node.scannedEntries;
        stats.omittedEntryCount += node.omittedEntries;
        if (node.error != null) {
            stats.errorCount++;
            stats.errors.add(errorMetadata(node));
        }

        for (TreeNode child : node.children) {
            stats.shownEntryCount++;
            if (child.isDirectory) {
                stats.dirCount++;
                computeStatsRecursive(child, stats);
            } else if (child.isSymlink) {
                stats.symlinkCount++;
                if (child.error != null) {
                    stats.errorCount++;
                    stats.errors.add(errorMetadata(child));
                }
            } else if (child.error != null) {
                stats.errorCount++;
                stats.errors.add(errorMetadata(child));
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

    private static List<Map<String, Object>> collectEntries(TreeNode root) {
        List<Map<String, Object>> entries = new ArrayList<>();
        collectEntriesRecursive(root, 0, entries);
        return entries;
    }

    private static void collectEntriesRecursive(TreeNode node, int depth, List<Map<String, Object>> entries) {
        for (TreeNode child : node.children) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", child.path);
            entry.put("name", child.name);
            entry.put("type", child.entryType());
            entry.put("depth", depth + 1);
            if (child.size > 0) {
                entry.put("size", child.size);
            }
            if (child.symlinkTarget != null) {
                entry.put("symlinkTarget", child.symlinkTarget);
            }
            if (child.entriesExceeded) {
                entry.put("truncated", true);
                entry.put("omittedEntries", child.omittedEntries);
            }
            if (child.error != null) {
                entry.put("error", child.error);
            }
            entries.add(entry);
            if (child.isDirectory && !child.collapsed) {
                collectEntriesRecursive(child, depth + 1, entries);
            }
        }
    }

    private static Map<String, Object> errorMetadata(TreeNode node) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("path", node.path);
        error.put("type", node.entryType());
        error.put("message", node.error);
        return error;
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

    // ── Internal data structures ──────────────────────────────────────────

    private static class TreeNode {
        String name;
        String path;
        boolean isDirectory;
        boolean isSymlink;
        String symlinkTarget;
        String entryType;
        long size;
        boolean collapsed;
        boolean entriesExceeded;
        int scannedEntries;
        int omittedEntries;
        String error;
        List<TreeNode> children = new ArrayList<>();
        Map<String, String> keyFileContents = new LinkedHashMap<>();

        String entryType() {
            if (isDirectory) {
                return "directory";
            }
            if (isSymlink) {
                return "symlink";
            }
            if (error != null) {
                return "error";
            }
            return entryType != null ? entryType : "file";
        }

        boolean hasVisibleContent() {
            return collapsed || entriesExceeded || error != null || !children.isEmpty();
        }

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
                else if (!child.isSymlink && child.error == null) count++;
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

        int symlinkCount() {
            int count = 0;
            for (TreeNode child : children) {
                if (child.isDirectory) {
                    count += child.symlinkCount();
                } else if (child.isSymlink) {
                    count++;
                }
            }
            return count;
        }
    }

    private static class Stats {
        int fileCount;
        int dirCount;
        int symlinkCount;
        int shownEntryCount;
        int scannedEntryCount;
        int omittedEntryCount;
        int errorCount;
        long totalSize;
        boolean entriesExceeded;
        Map<String, Integer> languageCounts = new LinkedHashMap<>();
        Map<String, String> keyFiles = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
    }
}
