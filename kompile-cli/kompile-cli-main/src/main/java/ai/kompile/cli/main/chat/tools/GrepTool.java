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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitOption;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Search file contents using regex patterns. Uses ripgrep (rg) if available,
 * falls back to Java grep. Comparable to OpenCode's GrepTool.
 */
public class GrepTool implements CliTool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    private static final int MAX_MATCHES = 100;
    private static final int MAX_CONTEXT_LINES = 20;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final Set<String> PARAM_KEYS = Set.of(
            "pattern", "path", "glob", "case_insensitive", "output_mode", "context_lines", "hidden");

    /** Hard wall-clock cap on a single grep invocation, for both rg and Java fallback scans. */
    private static final int TIMEOUT_SECONDS = 20;

    /** Cached result of probing for a real ripgrep executable, computed once per JVM
     *  (the answer can't change without a restart, and probing spawns a process). */
    private static volatile Boolean ripgrepAvailable;

    /** Grep flavor classifier kept for version-output tests and diagnostics. */
    enum GrepFlavor { GNU, UGREP, OTHER }

    @Override
    public String id() { return "grep"; }

    @Override
    public String description() {
        return "Search file contents for a regex pattern. Returns matching lines with file paths " +
                "and line numbers. Uses ripgrep if available. Supports glob filtering to narrow " +
                "the search to specific file types. Output modes: 'content' shows matching lines, " +
                "'files' shows only file paths, 'count' shows match counts per file. " +
                "Up to 100 matching results are shown; context lines may add surrounding output.";
    }

    @Override
    public String compactHint() {
        return "Regex content search. Required: pattern. Scope with path/glob. "
                + "output_mode=content|files|count; context_lines=N; hidden=true. "
                + "Portable regex: use [0-9], not \\d.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");

        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "The regex pattern to search for");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory or file to search in (default: working directory)");

        ObjectNode glob = props.putObject("glob");
        glob.put("type", "string");
        glob.put("description", "Glob pattern(s) to filter files (e.g. '*.java', '*.cpp,*.cu,*.h', '*.{ts,tsx}')");

        ObjectNode caseInsensitive = props.putObject("case_insensitive");
        caseInsensitive.put("type", "boolean");
        caseInsensitive.put("description", "Case insensitive search (default: false)");
        caseInsensitive.put("default", false);

        ObjectNode outputMode = props.putObject("output_mode");
        outputMode.put("type", "string");
        outputMode.put("description", "Output mode: 'content' (default), 'files', or 'count'");
        outputMode.put("default", "content");
        outputMode.putArray("enum").add("content").add("files").add("count");

        ObjectNode contextLines = props.putObject("context_lines");
        contextLines.put("type", "integer");
        contextLines.put("description", "Number of context lines before and after each match");
        contextLines.put("default", 0);
        contextLines.put("minimum", 0);
        contextLines.put("maximum", MAX_CONTEXT_LINES);

        ObjectNode hidden = props.putObject("hidden");
        hidden.put("type", "boolean");
        hidden.put("description", "Search hidden files and directories (dot-prefixed). Default false. "
                + "Heavy trees like .git/node_modules/target are always skipped regardless.");
        hidden.put("default", false);

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String permissionKey() { return "grep"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    private static List<String> unknownParams(JsonNode params) {
        if (params == null || !params.isObject()) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        Iterator<String> names = params.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!PARAM_KEYS.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search files");

        String pattern = params.path("pattern").asText("");
        String searchPath = params.path("path").asText("");
        String glob = params.path("glob").asText("");
        boolean caseInsensitive = params.path("case_insensitive").asBoolean(false);
        String outputMode = params.path("output_mode").asText("content");
        int contextLines = params.path("context_lines").asInt(0);
        boolean includeHidden = params.path("hidden").asBoolean(false);

        List<String> unknownParams = unknownParams(params);
        if (!unknownParams.isEmpty()) {
            return ToolResult.error("Unknown grep parameter(s): " + String.join(", ", unknownParams)
                    + ". Accepted parameters: " + String.join(", ", PARAM_KEYS));
        }
        if (pattern.isEmpty()) {
            return ToolResult.error("pattern is required");
        }
        if (!"content".equals(outputMode) && !"files".equals(outputMode) && !"count".equals(outputMode)) {
            return ToolResult.error("output_mode must be one of: content, files, count");
        }
        if (contextLines < 0 || contextLines > MAX_CONTEXT_LINES) {
            return ToolResult.error("context_lines must be between 0 and " + MAX_CONTEXT_LINES);
        }

        Path dir = searchPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(searchPath);
        if (!Files.exists(dir)) {
            return ToolResult.error("Search path not found: " + dir);
        }
        if (!Files.isDirectory(dir) && !Files.isRegularFile(dir)) {
            return ToolResult.error("Search path is not a regular file or directory: " + dir);
        }
        List<String> globPatterns = splitGlobPatterns(glob);

        // Load the .gitignore directory prunes for this search root. This skips huge ignored
        // DATA directories (model builds, downloaded corpora, generated indices) that the static
        // exclusion list below can't know about — without suppressing ignored file *types*
        // (*.json, *.log), which --no-ignore deliberately keeps searchable.
        Path gitignoreRoot = Files.isDirectory(dir) ? dir : dir.getParent();
        SearchExclusions.GitignoreDirFilter gitFilter = SearchExclusions.loadGitignoreDirFilter(gitignoreRoot);

        // Try ripgrep first, fall back to the in-process Java search.
        List<String> cmd;
        boolean useRg = isRipgrepAvailable();

        if (useRg) {
            cmd = buildRipgrepCommand(
                    pattern, dir, globPatterns, outputMode, caseInsensitive,
                    contextLines, includeHidden, gitFilter);
        } else {
            return executeJavaSearch(pattern, dir, globPatterns, caseInsensitive,
                    outputMode, contextLines, includeHidden, gitignoreRoot, gitFilter);
        }

        try {
            log.debug("grep: launching command {}", cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Watchdog: a no-match scan produces no output, so the read loop below blocks
            // on readLine() for the entire grep runtime. Killing the process from a separate
            // thread at the deadline unblocks the read (EOF) and bounds wall-clock time.
            // The previous code called waitFor(30s) only AFTER fully draining stdout, so the
            // timeout never fired on the exact case that hangs — a sparse/no-match scan.
            final java.util.concurrent.atomic.AtomicBoolean timedOut =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "grep-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            StringBuilder output = new StringBuilder();
            int lineCount = 0;
            int outputLineLimit = outputLineLimit(contextLines);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < outputLineLimit) {
                    output.append(relativizeOutputLine(truncateLine(line), context.getWorkingDirectory())).append("\n");
                    lineCount++;
                }
            }

            // We stopped reading because grep finished, we hit the line cap, or the watchdog
            // killed it. If grep is still alive (line cap hit), kill it now so the final
            // waitFor() can't block on a process stuck writing to a full stdout pipe.
            boolean lineCapHit = lineCount >= outputLineLimit;
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.waitFor();
            watchdog.interrupt();
            int exitCode = process.exitValue();
            log.debug("grep: command {} exited with code {}", cmd, exitCode);

            String result = output.toString().trim();
            if (exitCode > 1 && !timedOut.get() && !lineCapHit) {
                String detail = result.isEmpty() ? "no diagnostic output" : result;
                return ToolResult.error("grep failed (exit " + exitCode + "):\n" + detail);
            }
            if (result.isEmpty()) {
                return ToolResult.success(timedOut.get()
                        ? "Search timed out after " + TIMEOUT_SECONDS + "s with no matches for: " + pattern
                          + " (tree too large — narrow the search with 'path' or 'glob')"
                        : "No matches found for: " + pattern);
            }

            boolean truncated = lineCapHit || timedOut.get();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("outputMode", outputMode);
            metadata.put("outputLineCount", lineCount);
            metadata.put("linesReturned", lineCount);
            if ("content".equals(outputMode) && contextLines == 0) {
                metadata.put("matchCount", lineCount);
            }
            metadata.put("truncated", truncated);
            metadata.put("timedOut", timedOut.get());
            return ToolResult.success("grep: " + pattern,
                    result + (truncated ? "\n... (results truncated)" : ""),
                    metadata);

        } catch (Exception e) {
            return ToolResult.error("Error running grep: " + e.getMessage());
        }
    }

    static List<String> buildRipgrepCommand(String pattern, Path dir, List<String> globPatterns,
                                           String outputMode, boolean caseInsensitive, int contextLines,
                                           boolean includeHidden, SearchExclusions.GitignoreDirFilter gitFilter) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--no-heading");
        cmd.add("--line-buffered");
        cmd.add("-I");
        // Do not let rg respect .gitignore / .rgignore. The repo's .gitignore typically contains
        // file-type ignores like *.json or *.log that are valid files to search for code references.
        // Those remain searchable here, but known binary extensions are added as explicit globs.
        cmd.add("--no-ignore");
        // rg skips hidden files/dirs by default (--no-ignore does not change that). Opt in
        // with --hidden; the SearchExclusions "!<dir>" globs below still prune .git etc.
        if (includeHidden) {
            cmd.add("--hidden");
        }
        if ("files".equals(outputMode)) {
            cmd.add("-l");
        } else if ("count".equals(outputMode)) {
            cmd.add("-c");
            cmd.add("--with-filename");
        } else {
            cmd.add("-n");
            cmd.add("--with-filename");
        }
        if (caseInsensitive) cmd.add("-i");
        if (contextLines > 0) {
            cmd.add("-C");
            cmd.add(String.valueOf(contextLines));
        }
        for (String globPattern : globPatterns) {
            cmd.add("--glob");
            cmd.add(globPattern);
        }
        // --no-ignore disables .gitignore handling, so rg no longer prunes build/dependency
        // dirs on its own. Exclude them explicitly, matching the Java fallback's pruning,
        // so a default search doesn't drag in node_modules/, target/, dist/, etc. Added AFTER
        // any caller glob because rg resolves overlapping globs last-match-wins, so these
        // exclusions must take precedence over an include like "*.xml".
        for (String ex : SearchExclusions.DIRS) {
            cmd.add("--glob");
            cmd.add("!" + ex);
        }
        // Project-specific git-ignored data directories (no hard-coded names).
        if (gitFilter != null) {
            for (String ex : gitFilter.excludeDirArgs(dir)) {
                cmd.add("--glob");
                cmd.add("!" + ex);
            }
        }
        // Skip known binary extensions up-front to avoid expensive reads of auto-generated
        // model/vector artifacts that aren't obvious from extensionless or late-occurring payloads.
        for (String ex : SearchExclusions.binaryFileGlobs()) {
            cmd.add("--glob");
            cmd.add("!" + ex);
        }
        if (!"count".equals(outputMode)) {
            cmd.add("--max-count");
            cmd.add(String.valueOf(MAX_MATCHES));
        }
        cmd.add("--");
        cmd.add(pattern);
        cmd.add(dir.toString());
        return cmd;
    }

    private ToolResult executeJavaSearch(String pattern, Path root, List<String> globPatterns,
                                         boolean caseInsensitive, String outputMode, int contextLines,
                                         boolean includeHidden, Path gitignoreRoot,
                                         SearchExclusions.GitignoreDirFilter gitFilter) {
        Pattern compiled;
        try {
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            compiled = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("grep failed (invalid regex): " + e.getMessage());
        }

        List<GlobMatcher> globMatchers;
        try {
            globMatchers = compileGlobMatchers(globPatterns);
        } catch (RuntimeException e) {
            return ToolResult.error("Invalid glob pattern: " + e.getMessage());
        }

        JavaSearchState state = new JavaSearchState(outputMode, contextLines,
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS));
        Path baseRoot = Files.isDirectory(root) ? root : root.getParent();
        if (baseRoot == null) {
            baseRoot = root.toAbsolutePath().getParent();
        }
        if (baseRoot == null) {
            baseRoot = root.toAbsolutePath();
        }
        final Path traversalRoot = root;
        final Path relativeRoot = baseRoot;
        final Path ignoreRoot = gitignoreRoot != null ? gitignoreRoot : relativeRoot;

        try {
            if (Files.isRegularFile(root)) {
                searchJavaFile(root, relativeRoot, globMatchers, compiled, state);
            } else {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                        new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (state.shouldStop()) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (!dir.equals(traversalRoot)) {
                            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                            String rel = gitFilter.relativePath(dir, ignoreRoot);
                            if (SearchExclusions.isExcludedDir(name, includeHidden)
                                    || gitFilter.isIgnoredDir(rel, name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (state.shouldStop()) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!includeHidden) {
                            Path fileName = file.getFileName();
                            if (fileName != null && fileName.toString().startsWith(".")) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        searchJavaFile(file, relativeRoot, globMatchers, compiled, state);
                        return state.shouldStop() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            return ToolResult.error("Error running grep: " + e.getMessage());
        }

        String result = state.output().trim();
        if (result.isEmpty()) {
            return ToolResult.success(state.timedOut
                    ? "Search timed out after " + TIMEOUT_SECONDS + "s with no matches for: " + pattern
                      + " (tree too large — narrow the search with 'path' or 'glob')"
                    : "No matches found for: " + pattern);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("outputMode", outputMode);
        metadata.put("outputLineCount", state.outputLineCount);
        metadata.put("linesReturned", state.outputLineCount);
        if ("content".equals(outputMode) && contextLines == 0) {
            metadata.put("matchCount", state.matchCount);
        }
        metadata.put("truncated", state.truncated || state.timedOut);
        metadata.put("timedOut", state.timedOut);
        return ToolResult.success("grep: " + pattern,
                result + ((state.truncated || state.timedOut) ? "\n... (results truncated)" : ""),
                metadata);
    }

    private void searchJavaFile(Path file, Path root, List<GlobMatcher> globMatchers,
                                Pattern pattern, JavaSearchState state) {
        if (state.shouldStop() || !matchesGlob(file, root, globMatchers)) {
            return;
        }
        try {
            if (SearchExclusions.isLikelyBinaryFile(file)) {
                return;
            }
            String rel = relativeString(root, file);
            if (state.contextLines > 0 && "content".equals(state.outputMode)) {
                searchJavaFileWithContext(file, rel, pattern, state);
            } else {
                searchJavaFileStreaming(file, rel, pattern, state);
            }
        } catch (IOException | RuntimeException ignored) {
            // Match grep's tolerance for unreadable/non-text files during recursive scans.
        }
    }

    private static void searchJavaFileStreaming(Path file, String rel, Pattern pattern,
                                                JavaSearchState state) throws IOException {
        int fileMatches = 0;
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (state.shouldStop()) {
                    return;
                }
                if (!pattern.matcher(line).find()) {
                    continue;
                }
                if ("files".equals(state.outputMode)) {
                    if (state.addLine(rel)) {
                        state.matchCount++;
                    }
                    return;
                }
                fileMatches++;
                if ("content".equals(state.outputMode)) {
                    if (state.addLine(rel + ":" + lineNo + ":" + truncateLine(line))) {
                        state.matchCount++;
                    }
                } else {
                    state.matchCount++;
                }
            }
        }
        if ("count".equals(state.outputMode) && fileMatches > 0) {
            state.addLine(rel + ":" + fileMatches);
        }
    }

    private static void searchJavaFileWithContext(Path file, String rel, Pattern pattern,
                                                  JavaSearchState state) throws IOException {
        Deque<LineEntry> before = new ArrayDeque<>();
        int afterRemaining = 0;
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (state.shouldStop()) {
                    return;
                }
                boolean matched = pattern.matcher(line).find();
                if (matched) {
                    for (LineEntry prev : before) {
                        state.addLine(rel + "-" + prev.lineNo + "-" + truncateLine(prev.text));
                    }
                    before.clear();
                    if (state.addLine(rel + ":" + lineNo + ":" + truncateLine(line))) {
                        state.matchCount++;
                    }
                    afterRemaining = state.contextLines;
                } else if (afterRemaining > 0) {
                    state.addLine(rel + "-" + lineNo + "-" + truncateLine(line));
                    afterRemaining--;
                } else {
                    before.addLast(new LineEntry(lineNo, line));
                    while (before.size() > state.contextLines) {
                        before.removeFirst();
                    }
                }
            }
        }
    }

    private static List<GlobMatcher> compileGlobMatchers(List<String> globPatterns) {
        if (globPatterns == null || globPatterns.isEmpty()) {
            return List.of();
        }
        List<GlobMatcher> matchers = new ArrayList<>();
        for (String glob : globPatterns) {
            boolean basename = glob.indexOf('/') < 0 && glob.indexOf(File.separatorChar) < 0;
            matchers.add(new GlobMatcher(glob,
                    FileSystems.getDefault().getPathMatcher("glob:" + glob), basename));
        }
        return matchers;
    }

    private static boolean matchesGlob(Path file, Path root, List<GlobMatcher> matchers) {
        if (matchers.isEmpty()) {
            return true;
        }
        Path rel;
        try {
            rel = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            rel = file.getFileName();
        }
        Path fileName = file.getFileName();
        for (GlobMatcher matcher : matchers) {
            if (matcher.matcher().matches(rel)
                    || (matcher.basenameOnly() && fileName != null && matcher.matcher().matches(fileName))) {
                return true;
            }
        }
        return false;
    }

    private static String relativeString(Path root, Path path) {
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private record GlobMatcher(String glob, PathMatcher matcher, boolean basenameOnly) {}
    private record LineEntry(int lineNo, String text) {}

    private static final class JavaSearchState {
        private final String outputMode;
        private final int contextLines;
        private final long deadlineNanos;
        private final StringBuilder output = new StringBuilder();
        private final int outputLineLimit;
        private int outputLineCount;
        private int matchCount;
        private boolean truncated;
        private boolean timedOut;

        private JavaSearchState(String outputMode, int contextLines, long deadlineNanos) {
            this.outputMode = outputMode;
            this.contextLines = contextLines;
            this.deadlineNanos = deadlineNanos;
            this.outputLineLimit = outputLineLimit(contextLines);
        }

        private boolean addLine(String line) {
            if (shouldStop()) {
                return false;
            }
            if (outputLineCount >= outputLineLimit) {
                truncated = true;
                return false;
            }
            output.append(line).append('\n');
            outputLineCount++;
            return true;
        }

        private boolean shouldStop() {
            if (truncated) {
                return true;
            }
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true;
                return true;
            }
            return false;
        }

        private String output() {
            return output.toString();
        }
    }

    static List<String> splitGlobPatterns(String glob) {
        if (glob == null || glob.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '{') {
                braceDepth++;
            } else if (ch == '}' && braceDepth > 0) {
                braceDepth--;
            }
            if (ch == ',' && braceDepth == 0) {
                addGlobPart(result, current);
            } else {
                current.append(ch);
            }
        }
        addGlobPart(result, current);
        return result;
    }

    private static void addGlobPart(List<String> result, StringBuilder current) {
        String part = current.toString().trim();
        if (!part.isEmpty()) {
            result.add(part);
        }
        current.setLength(0);
    }

    private static int outputLineLimit(int contextLines) {
        if (contextLines <= 0) {
            return MAX_MATCHES;
        }
        int linesPerMatch = (contextLines * 2) + 1;
        return Math.min(1000, MAX_MATCHES * linesPerMatch);
    }

    private static String relativizeOutputLine(String line, Path workingDirectory) {
        String prefix = workingDirectory.toAbsolutePath().normalize().toString() + File.separator;
        return line.startsWith(prefix) ? line.substring(prefix.length()) : line;
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

    /**
     * Translates a ripgrep/git-style glob into equivalent grep {@code --include} patterns.
     * grep's {@code --include} matches the file <em>basename</em> with fnmatch: it does not
     * understand {@code **} recursion or {@code {a,b}} brace groups, so a glob such as
     * {@code **}{@code /pom.xml} or {@code *.{ts,tsx}} matches nothing and the search returns a
     * silent false negative. We reduce the glob to its basename (dropping directory components —
     * a slight over-match, never a false negative) and expand a single {@code {a,b,...}} brace
     * group into one include per option. The {@code rg} backend handles the raw glob natively.
     */
    static List<String> globToGrepIncludes(String glob) {
        List<String> globParts = splitGlobPatterns(glob);
        if (globParts.size() > 1) {
            List<String> includes = new ArrayList<>();
            for (String part : globParts) {
                includes.addAll(globToGrepIncludes(part));
            }
            return includes;
        }
        if (globParts.isEmpty()) {
            return List.of();
        }
        glob = globParts.get(0);
        int lastSlash = glob.lastIndexOf('/');
        String base = lastSlash >= 0 ? glob.substring(lastSlash + 1) : glob;
        if (base.isEmpty()) {
            return List.of();
        }
        int open = base.indexOf('{');
        int close = open >= 0 ? base.indexOf('}', open) : -1;
        if (open >= 0 && close > open) {
            String prefix = base.substring(0, open);
            String suffix = base.substring(close + 1);
            String[] options = base.substring(open + 1, close).split(",");
            List<String> includes = new ArrayList<>(options.length);
            for (String opt : options) {
                String trimmed = opt.trim();
                if (!trimmed.isEmpty()) {
                    includes.add(prefix + trimmed + suffix);
                }
            }
            if (!includes.isEmpty()) {
                return includes;
            }
        }
        return List.of(base);
    }


    /**
     * True if a real ripgrep executable is usable. Cached for the life of the JVM.
     * Requires both exit 0 AND stdout that identifies itself as ripgrep, so a shell
     * function/shim named {@code rg} that exits 0 but isn't ripgrep is rejected.
     */
    private boolean isRipgrepAvailable() {
        Boolean cached = ripgrepAvailable;
        if (cached != null) {
            return cached;
        }
        String versionOutput = captureVersionOutput("rg");
        boolean available = isRipgrepVersion(versionOutput);
        log.debug("grep: rg --version output={}, identified as ripgrep={}", versionOutput, available);
        ripgrepAvailable = available;
        return available;
    }

    /**
     * Pure predicate: returns {@code true} iff {@code versionOutput} (stdout from
     * {@code rg --version}) identifies the binary as ripgrep. A shell function/shim that
     * exits 0 but emits a different tool name is rejected here, preventing ripgrep-only
     * flags ({@code --glob}, {@code --no-heading}) from being passed to the wrong tool.
     *
     * <p>This method is package-private for unit testing.
     */
    static boolean isRipgrepVersion(String versionOutput) {
        return versionOutput != null
                && versionOutput.toLowerCase().contains("ripgrep");
    }

    /**
     * Pure classifier: maps {@code grep --version} output to a {@link GrepFlavor}.
     * Kept package-private for unit tests that verify version-output parsing.
     */
    static GrepFlavor detectGrepFlavor(String versionOutput) {
        if (versionOutput == null || versionOutput.isEmpty()) {
            return GrepFlavor.OTHER;
        }
        String lower = versionOutput.toLowerCase();
        if (lower.contains("ugrep")) {
            return GrepFlavor.UGREP;
        }
        if (lower.contains("gnu")) {
            return GrepFlavor.GNU;
        }
        return GrepFlavor.OTHER;
    }

    /**
     * Captures the first line of stdout from "{@code command} --version" executed directly
     * via {@link ProcessBuilder} (no shell, so shell functions/aliases are invisible).
     * Returns an empty string if the command is not on PATH, fails to start, times out,
     * or produces no output.
     */
    private String captureVersionOutput(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            String firstLine;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                firstLine = br.readLine();
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return firstLine != null ? firstLine : "";
        } catch (Exception e) {
            // Not on PATH / not runnable.
            return "";
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
