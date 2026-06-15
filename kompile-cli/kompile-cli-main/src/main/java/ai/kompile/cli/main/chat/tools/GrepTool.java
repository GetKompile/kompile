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
        ObjectMapper om = new ObjectMapper();
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
        boolean useRg = isCommandAvailable("rg");

        if (useRg) {
            cmd.add("rg");
            cmd.add("--no-heading");
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
            cmd.add("--max-count");
            cmd.add(String.valueOf(MAX_MATCHES));
            cmd.add(pattern);
            cmd.add(dir.toString());
        } else {
            cmd.add("grep");
            cmd.add("-r");
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
                cmd.add("--include=" + glob);
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

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return ToolResult.success("No matches found for: " + pattern);
            }

            boolean truncated = lineCount >= MAX_MATCHES * 3;
            return ToolResult.success("grep: " + pattern,
                    result + (truncated ? "\n... (results truncated)" : ""),
                    Map.of("matchCount", lineCount, "truncated", truncated));

        } catch (Exception e) {
            return ToolResult.error("Error running grep: " + e.getMessage());
        }
    }

    private boolean isCommandAvailable(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("which", command).start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
