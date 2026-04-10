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

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Persistent memory tool that reads and writes MEMORY.md files,
 * providing long-term memory across chat sessions.
 * <p>
 * Memory files are stored at two levels:
 * <ul>
 *   <li><b>Global</b>: {@code ~/.kompile/memory/MEMORY.md} - user-wide preferences,
 *       common patterns, and cross-project knowledge</li>
 *   <li><b>Project</b>: {@code .kompile/memory/MEMORY.md} - project-specific
 *       architecture decisions, conventions, debugging notes</li>
 * </ul>
 * <p>
 * Additional topic files (e.g. {@code debugging.md}, {@code architecture.md})
 * can be created in the same directories for detailed notes that are too large
 * for the main MEMORY.md file.
 * <p>
 * Actions:
 * <ul>
 *   <li><b>read</b> - Read a memory file (defaults to MEMORY.md)</li>
 *   <li><b>write</b> - Overwrite a memory file with new content</li>
 *   <li><b>append</b> - Append content to a memory file</li>
 *   <li><b>list</b> - List all memory files at both levels</li>
 *   <li><b>search</b> - Search across all memory files</li>
 * </ul>
 * <p>
 * Modeled after Claude Code's MEMORY.md system. The agent should:
 * <ul>
 *   <li>Save stable patterns confirmed across multiple interactions</li>
 *   <li>Record key architectural decisions and important file paths</li>
 *   <li>Note user preferences for workflow, tools, and communication style</li>
 *   <li>Store solutions to recurring problems and debugging insights</li>
 *   <li>NOT save session-specific context or speculative conclusions</li>
 * </ul>
 */
public class MemoryTool implements CliTool {

    private static final String MEMORY_DIR = "memory";
    private static final String DEFAULT_FILE = "MEMORY.md";
    private static final int MAX_MEMORY_FILE_SIZE = 50_000; // ~50KB
    private static final int MAX_MAIN_MEMORY_LINES = 200;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    @Override
    public String id() { return "memory"; }

    @Override
    public String description() {
        return "Read and write persistent MEMORY.md files that persist across chat sessions. "
                + "Use 'read' to load memory (project or global), 'write' to save/overwrite, "
                + "'append' to add new entries, 'list' to see all memory files, 'search' to find "
                + "content across memory files. Save important facts, patterns, decisions, and "
                + "preferences here. Project memories are in .kompile/memory/, global in ~/.kompile/memory/. "
                + "Keep MEMORY.md concise (under 200 lines) and create topic files for details.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'read', 'write', 'append', 'list', or 'search'");

        ObjectNode scope = props.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "Memory scope: 'project' (default) or 'global'");

        ObjectNode file = props.putObject("file");
        file.put("type", "string");
        file.put("description", "Memory file name (default: MEMORY.md). Use topic files like 'debugging.md' for detailed notes.");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "Content to write or append");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query for 'search' action");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "memory"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Access persistent memory");

        String action = params.path("action").asText("");
        String scope = params.path("scope").asText("project");
        String fileName = params.path("file").asText(DEFAULT_FILE);

        // Sanitize filename
        fileName = sanitizeFileName(fileName);

        Path memDir = resolveMemoryDir(scope, context.getWorkingDirectory());

        switch (action) {
            case "read":
                return readMemory(memDir, fileName, scope);
            case "write":
                return writeMemory(memDir, fileName, params.path("content").asText(""), scope);
            case "append":
                return appendMemory(memDir, fileName, params.path("content").asText(""), scope);
            case "list":
                return listMemoryFiles(context.getWorkingDirectory());
            case "search":
                return searchMemory(params.path("query").asText(""), context.getWorkingDirectory());
            default:
                return ToolResult.error("Unknown action: " + action
                        + ". Use 'read', 'write', 'append', 'list', or 'search'.");
        }
    }

    private ToolResult readMemory(Path memDir, String fileName, String scope) {
        Path file = memDir.resolve(fileName);
        if (!Files.exists(file)) {
            return ToolResult.success("No " + scope + " memory file found at: " + file
                    + "\nUse action='write' to create one.");
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // Truncate main MEMORY.md if too long (warn but still return)
            if (fileName.equals(DEFAULT_FILE)) {
                String[] lines = content.split("\n");
                if (lines.length > MAX_MAIN_MEMORY_LINES) {
                    return ToolResult.success("memory: " + scope + "/" + fileName, content,
                            Map.of("scope", scope, "file", fileName,
                                    "lines", lines.length,
                                    "warning", "MEMORY.md exceeds " + MAX_MAIN_MEMORY_LINES
                                            + " lines. Consider moving details to topic files."));
                }
            }
            return ToolResult.success("memory: " + scope + "/" + fileName, content,
                    Map.of("scope", scope, "file", fileName));
        } catch (IOException e) {
            return ToolResult.error("Error reading memory file: " + e.getMessage());
        }
    }

    private ToolResult writeMemory(Path memDir, String fileName, String content, String scope) {
        if (content.isEmpty()) {
            return ToolResult.error("content is required for 'write' action");
        }

        if (content.length() > MAX_MEMORY_FILE_SIZE) {
            return ToolResult.error("Content too large (" + content.length()
                    + " chars). Maximum is " + MAX_MEMORY_FILE_SIZE
                    + ". Break into topic files.");
        }

        Path file = memDir.resolve(fileName);
        try {
            Files.createDirectories(memDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            int lines = content.split("\n").length;

            String warning = null;
            if (fileName.equals(DEFAULT_FILE) && lines > MAX_MAIN_MEMORY_LINES) {
                warning = "MEMORY.md has " + lines + " lines (recommended max: "
                        + MAX_MAIN_MEMORY_LINES + "). Consider moving details to topic files.";
            }

            Map<String, Object> meta = warning != null
                    ? Map.of("scope", scope, "file", fileName, "lines", lines, "warning", warning)
                    : Map.of("scope", scope, "file", fileName, "lines", lines);

            return ToolResult.success("memory: wrote " + scope + "/" + fileName
                    + " (" + lines + " lines)", "Saved to " + file, meta);
        } catch (IOException e) {
            return ToolResult.error("Error writing memory file: " + e.getMessage());
        }
    }

    private ToolResult appendMemory(Path memDir, String fileName, String content, String scope) {
        if (content.isEmpty()) {
            return ToolResult.error("content is required for 'append' action");
        }

        Path file = memDir.resolve(fileName);
        try {
            Files.createDirectories(memDir);

            String existing = "";
            if (Files.exists(file)) {
                existing = Files.readString(file, StandardCharsets.UTF_8);
            }

            // Add timestamp marker for appended content
            String timestamped = "\n\n## " + TIMESTAMP_FMT.format(Instant.now()) + "\n" + content;
            String newContent = existing + timestamped;

            if (newContent.length() > MAX_MEMORY_FILE_SIZE) {
                return ToolResult.error("Append would exceed maximum file size ("
                        + MAX_MEMORY_FILE_SIZE + " chars). Use 'write' to replace or create a topic file.");
            }

            Files.writeString(file, newContent, StandardCharsets.UTF_8);
            int lines = newContent.split("\n").length;

            return ToolResult.success("memory: appended to " + scope + "/" + fileName
                            + " (" + lines + " lines)",
                    "Appended to " + file,
                    Map.of("scope", scope, "file", fileName, "lines", lines));
        } catch (IOException e) {
            return ToolResult.error("Error appending to memory file: " + e.getMessage());
        }
    }

    private ToolResult listMemoryFiles(Path workDir) {
        StringBuilder sb = new StringBuilder();

        // Project memory
        Path projectDir = workDir.resolve(".kompile").resolve(MEMORY_DIR);
        sb.append("Project memory (").append(projectDir).append("):\n");
        listDir(projectDir, sb);

        // Global memory
        Path globalDir = KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        sb.append("\nGlobal memory (").append(globalDir).append("):\n");
        listDir(globalDir, sb);

        return ToolResult.success("memory: list", sb.toString(), Map.of());
    }

    private void listDir(Path dir, StringBuilder sb) {
        if (!Files.exists(dir)) {
            sb.append("  (empty - no memory files yet)\n");
            return;
        }

        try (var files = Files.list(dir)) {
            var list = files.filter(p -> !Files.isDirectory(p))
                    .sorted()
                    .toList();

            if (list.isEmpty()) {
                sb.append("  (empty)\n");
                return;
            }

            for (Path f : list) {
                try {
                    long size = Files.size(f);
                    String[] lines = Files.readString(f, StandardCharsets.UTF_8).split("\n");
                    sb.append(String.format("  %-30s %5d lines  %6d bytes%n",
                            f.getFileName(), lines.length, size));
                } catch (IOException e) {
                    sb.append("  ").append(f.getFileName()).append(" (unreadable)\n");
                }
            }
        } catch (IOException e) {
            sb.append("  (error listing: ").append(e.getMessage()).append(")\n");
        }
    }

    private ToolResult searchMemory(String query, Path workDir) {
        if (query.isEmpty()) {
            return ToolResult.error("query is required for 'search' action");
        }

        String queryLower = query.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int matchCount = 0;

        // Search project memory
        Path projectDir = workDir.resolve(".kompile").resolve(MEMORY_DIR);
        matchCount += searchDir(projectDir, queryLower, "project", sb);

        // Search global memory
        Path globalDir = KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        matchCount += searchDir(globalDir, queryLower, "global", sb);

        if (matchCount == 0) {
            return ToolResult.success("No memory matches for: " + query);
        }

        return ToolResult.success("memory: search '" + query + "'", sb.toString(),
                Map.of("query", query, "matchCount", matchCount));
    }

    private int searchDir(Path dir, String queryLower, String scope, StringBuilder sb) {
        if (!Files.exists(dir)) return 0;

        int matches = 0;
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> !Files.isDirectory(p)).toList()) {
                try {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].toLowerCase().contains(queryLower)) {
                            if (matches == 0 || !sb.toString().endsWith(f.getFileName() + ":\n")) {
                                sb.append("\n").append(scope).append("/").append(f.getFileName()).append(":\n");
                            }
                            // Context: 1 line before and after
                            int start = Math.max(0, i - 1);
                            int end = Math.min(lines.length - 1, i + 1);
                            for (int j = start; j <= end; j++) {
                                sb.append(j == i ? ">>> " : "    ")
                                        .append("L").append(j + 1).append(": ")
                                        .append(lines[j]).append("\n");
                            }
                            matches++;
                            if (matches >= 30) return matches;
                        }
                    }
                } catch (IOException e) {
                    // Skip unreadable files
                }
            }
        } catch (IOException e) {
            // Skip
        }
        return matches;
    }

    // ========================================================================
    // Static helpers for loading memory content at session start
    // ========================================================================

    /**
     * Loads all MEMORY.md content for injection into the system prompt.
     * Reads both global and project-level MEMORY.md files.
     *
     * @param workDir current working directory for project-level resolution
     * @return combined memory content, or null if no memory files exist
     */
    public static String loadMemoryForContext(Path workDir) {
        StringBuilder sb = new StringBuilder();

        // Global MEMORY.md
        Path globalFile = KompileHome.homeDirectory().toPath()
                .resolve(MEMORY_DIR).resolve(DEFAULT_FILE);
        String globalContent = readFileQuietly(globalFile, MAX_MAIN_MEMORY_LINES);
        if (globalContent != null) {
            sb.append("[Global memory - ~/.kompile/memory/MEMORY.md]\n");
            sb.append(globalContent);
            sb.append("\n\n");
        }

        // Project MEMORY.md
        Path projectFile = workDir.resolve(".kompile")
                .resolve(MEMORY_DIR).resolve(DEFAULT_FILE);
        String projectContent = readFileQuietly(projectFile, MAX_MAIN_MEMORY_LINES);
        if (projectContent != null) {
            sb.append("[Project memory - .kompile/memory/MEMORY.md]\n");
            sb.append(projectContent);
            sb.append("\n\n");
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Lists all memory files with their sizes for status display.
     */
    public static String getMemoryStatus(Path workDir) {
        StringBuilder sb = new StringBuilder();

        Path globalDir = KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        Path projectDir = workDir.resolve(".kompile").resolve(MEMORY_DIR);

        int globalCount = countFiles(globalDir);
        int projectCount = countFiles(projectDir);

        sb.append("  Persistent memory: ");
        if (globalCount == 0 && projectCount == 0) {
            sb.append("(none)\n");
        } else {
            sb.append(globalCount).append(" global, ").append(projectCount).append(" project files\n");
        }

        // Show MEMORY.md sizes
        Path globalMem = globalDir.resolve(DEFAULT_FILE);
        Path projectMem = projectDir.resolve(DEFAULT_FILE);
        if (Files.exists(globalMem)) {
            sb.append("  Global MEMORY.md:  ").append(lineCount(globalMem)).append(" lines\n");
        }
        if (Files.exists(projectMem)) {
            sb.append("  Project MEMORY.md: ").append(lineCount(projectMem)).append(" lines\n");
        }

        return sb.toString();
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    private Path resolveMemoryDir(String scope, Path workDir) {
        if ("global".equalsIgnoreCase(scope)) {
            return KompileHome.homeDirectory().toPath().resolve(MEMORY_DIR);
        }
        // Project-level: .kompile/memory/ in working directory
        return workDir.resolve(".kompile").resolve(MEMORY_DIR);
    }

    private String sanitizeFileName(String name) {
        // Prevent directory traversal
        Path fileName = Paths.get(name).getFileName();
        name = fileName != null ? fileName.toString() : name;
        // Ensure it ends with .md
        if (!name.endsWith(".md")) {
            name = name + ".md";
        }
        return name;
    }

    private static String readFileQuietly(Path file, int maxLines) {
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return null;
            // Truncate to max lines
            String[] lines = content.split("\n");
            if (lines.length > maxLines) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < maxLines; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("\n... (").append(lines.length - maxLines)
                        .append(" more lines, use memory tool to read full file)");
                return sb.toString();
            }
            return content;
        } catch (IOException e) {
            return null;
        }
    }

    private static int countFiles(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (var files = Files.list(dir)) {
            return (int) files.filter(p -> !Files.isDirectory(p)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int lineCount(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).split("\n").length;
        } catch (IOException e) {
            return 0;
        }
    }
}
