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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Search file contents using regex patterns. Uses ripgrep (rg) if available,
 * falls back to Java grep. Comparable to OpenCode's GrepTool.
 */
public class GrepTool implements CliTool {

    private static final int MAX_MATCHES = 100;

    /** Hard wall-clock cap on a single grep invocation. A watchdog kills the process at
     *  this deadline so a no-match scan over a huge tree can never hang the MCP client. */
    private static final int TIMEOUT_SECONDS = 20;

    /** Cached result of probing for a real ripgrep executable, computed once per JVM
     *  (the answer can't change without a restart, and probing spawns a process). */
    private static volatile Boolean ripgrepAvailable;

    @Override
    public String id() { return "grep"; }

    @Override
    public String description() {
        return "Search file contents for a regex pattern. Returns matching lines with file paths " +
                "and line numbers. Uses ripgrep if available. Supports glob filtering to narrow " +
                "the search to specific file types. Output modes: 'content' shows matching lines, " +
                "'files' shows only file paths, 'count' shows match counts per file. " +
                "Maximum 100 matches returned.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "The regex pattern to search for");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory or file to search in (default: working directory)");

        ObjectNode glob = props.putObject("glob");
        glob.put("type", "string");
        glob.put("description", "Glob pattern to filter files (e.g. '*.java', '*.{ts,tsx}')");

        ObjectNode caseInsensitive = props.putObject("case_insensitive");
        caseInsensitive.put("type", "boolean");
        caseInsensitive.put("description", "Case insensitive search (default: false)");

        ObjectNode outputMode = props.putObject("output_mode");
        outputMode.put("type", "string");
        outputMode.put("description", "Output mode: 'content' (default), 'files', or 'count'");

        ObjectNode contextLines = props.putObject("context_lines");
        contextLines.put("type", "integer");
        contextLines.put("description", "Number of context lines before and after each match");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String permissionKey() { return "grep"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search files");

        String pattern = params.path("pattern").asText("");
        String searchPath = params.path("path").asText("");
        String glob = params.path("glob").asText("");
        boolean caseInsensitive = params.path("case_insensitive").asBoolean(false);
        String outputMode = params.path("output_mode").asText("content");
        int contextLines = params.path("context_lines").asInt(0);

        if (pattern.isEmpty()) {
            return ToolResult.error("pattern is required");
        }

        Path dir = searchPath.isEmpty() ? context.getWorkingDirectory() : context.resolvePath(searchPath);

        // Try ripgrep first, fall back to grep
        List<String> cmd = new ArrayList<>();
        boolean useRg = isRipgrepAvailable();

        if (useRg) {
            cmd.add("rg");
            cmd.add("--no-heading");
            // Do not let rg respect .gitignore / .rgignore. The repo's .gitignore typically
            // contains entries like *.json, *.log, etc. that are perfectly valid files to search
            // for code references. Silently skipping them is more harmful than accidentally
            // including a large auto-generated file. rg's own binary-content detection (-I by
            // default) still skips true binary blobs regardless of this flag.
            cmd.add("--no-ignore");
            if ("files".equals(outputMode)) {
                cmd.add("-l");
            } else if ("count".equals(outputMode)) {
                cmd.add("-c");
            } else {
                cmd.add("-n");
            }
            if (caseInsensitive) cmd.add("-i");
            if (contextLines > 0) {
                cmd.add("-C");
                cmd.add(String.valueOf(contextLines));
            }
            if (!glob.isEmpty()) {
                cmd.add("--glob");
                cmd.add(glob);
            }
            // --no-ignore disables .gitignore handling, so rg no longer prunes build/dependency
            // dirs on its own. Exclude them explicitly (mirrors the grep fallback's --exclude-dir)
            // so a default search doesn't drag in node_modules/, target/, dist/, etc. Added AFTER
            // any caller glob because rg resolves overlapping globs last-match-wins, so these
            // exclusions must take precedence over an include like "*.xml".
            for (String ex : SearchExclusions.DIRS) {
                cmd.add("--glob");
                cmd.add("!" + ex);
            }
            cmd.add("--max-count");
            cmd.add(String.valueOf(MAX_MATCHES));
            cmd.add(pattern);
            cmd.add(dir.toString());
        } else {
            cmd.add("grep");
            cmd.add("-r");
            // -E: extended regex, so a pattern is interpreted like ripgrep's default — alternation
            // (a|b), groups, and +/?/{n} quantifiers all work. Plain grep is BRE, where `|` is a
            // LITERAL character, so `a|b` silently matches nothing (a false negative).
            cmd.add("-E");
            // -I: skip binary files (.git objects, model blobs, .so/.jar) — avoids wasted work
            // and "Binary file ... matches" noise.
            cmd.add("-I");
            // -D skip: don't read device/FIFO/socket files. NOTE: grep -r ALREADY skips FIFOs it
            // encounters while recursing (GNU default — verified), so this only matters when a
            // FIFO is passed directly as the search 'path'. Cheap belt-and-suspenders, no downside.
            cmd.add("-D");
            cmd.add("skip");
            // The actual fix for grep "stalls": prune VCS/build/dependency dirs. Without this,
            // grep -r walks the entire monorepo (.git/, every target/, node_modules/, native build
            // trees) — measured >30s (timed out) vs ~2s with these excludes — and the blocking
            // read makes the MCP call appear to return "No matches".
            for (String ex : SearchExclusions.DIRS) {
                cmd.add("--exclude-dir=" + ex);
            }
            if ("files".equals(outputMode)) {
                cmd.add("-l");
            } else if ("count".equals(outputMode)) {
                cmd.add("-c");
            } else {
                cmd.add("-n");
            }
            if (caseInsensitive) cmd.add("-i");
            if (contextLines > 0) {
                cmd.add("-C");
                cmd.add(String.valueOf(contextLines));
            }
            if (!glob.isEmpty()) {
                // grep --include matches the BASENAME with fnmatch — it understands neither
                // ripgrep/git-style "**/" recursion nor "{a,b}" brace groups, so a glob like
                // "**/pom.xml" or "*.{ts,tsx}" silently matches nothing. Translate to basename
                // include(s); grep ORs multiple --include patterns.
                for (String inc : globToGrepIncludes(glob)) {
                    cmd.add("--include=" + inc);
                }
            }
            cmd.add("-m");
            cmd.add(String.valueOf(MAX_MATCHES));
            cmd.add(pattern);
            cmd.add(dir.toString());
        }

        try {
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < MAX_MATCHES * 3) {
                    // Relativize paths for cleaner output
                    String workDirStr = context.getWorkingDirectory().toString();
                    if (line.startsWith(workDirStr)) {
                        line = line.substring(workDirStr.length() + 1);
                    }
                    output.append(line).append("\n");
                    lineCount++;
                }
            }

            // We stopped reading because grep finished, we hit the line cap, or the watchdog
            // killed it. If grep is still alive (line cap hit), kill it now so the final
            // waitFor() can't block on a process stuck writing to a full stdout pipe.
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.waitFor();
            watchdog.interrupt();

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return ToolResult.success(timedOut.get()
                        ? "Search timed out after " + TIMEOUT_SECONDS + "s with no matches for: " + pattern
                          + " (tree too large — narrow the search with 'path' or 'glob')"
                        : "No matches found for: " + pattern);
            }

            boolean truncated = lineCount >= MAX_MATCHES * 3 || timedOut.get();
            return ToolResult.success("grep: " + pattern,
                    result + (truncated ? "\n... (results truncated)" : ""),
                    Map.of("matchCount", lineCount, "truncated", truncated, "timedOut", timedOut.get()));

        } catch (Exception e) {
            return ToolResult.error("Error running grep: " + e.getMessage());
        }
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
     */
    private boolean isRipgrepAvailable() {
        Boolean cached = ripgrepAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available = probeExecutable("rg");
        ripgrepAvailable = available;
        return available;
    }

    /**
     * Probes whether {@code command} can actually be executed by running
     * "{@code command} --version" directly via {@link ProcessBuilder} (no shell).
     *
     * <p>Deliberately more robust than shelling out to {@code which}: {@code which} may be
     * absent on minimal systems, and neither it nor ProcessBuilder resolves shell
     * functions/aliases. In some environments {@code rg} on the interactive PATH is only a
     * shell function (a wrapper), which a {@code which} probe can misreport; executing the
     * binary directly matches exactly what the real search call does, so we report
     * "available" only when a genuine {@code rg} executable is on PATH.
     */
    private boolean probeExecutable(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            // Not on PATH / not runnable → treat as unavailable and use the grep fallback.
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
