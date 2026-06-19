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
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * MCP tool for viewing, editing, and managing kompile project configuration.
 *
 * <p>Exposes the configuration managed by {@code kompile init} so any agent
 * can inspect and modify AGENTS.md, .mcp.json, platform configs (CLAUDE.md,
 * .cursorrules, .windsurfrules, .github/copilot-instructions.md), and
 * system prompt overrides (~/.kompile/system-prompt.md, per-agent prompts).
 */
public class ProjectConfigTool implements CliTool {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private static final String AGENTS_MD_RESOURCE = "/templates/AGENTS.md";
    private static final String SYSTEM_PROMPT_RESOURCE = "/templates/system-prompt.md";
    private static final String MANDATE_MARKER = "KOMPILE TOOL ORCHESTRATION MANDATE";

    private static final List<String> AGENT_TYPES = CliAgentRegistry.commandNames();

    private static final String[][] PLATFORM_CONFIGS = {
            {"CLAUDE.md",                       "CLAUDE.md"},
            {".cursorrules",                    ".cursorrules"},
            {".windsurfrules",                  ".windsurfrules"},
            {".github/copilot-instructions.md", ".github/copilot-instructions.md"},
    };

    /** All recognized file keys for the "file" parameter. */
    private static final Set<String> ALL_FILE_KEYS;
    static {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("AGENTS.md");
        keys.add(".mcp.json");
        for (String[] pc : PLATFORM_CONFIGS) keys.add(pc[0]);
        keys.add("system-prompt.md");
        for (String agent : AGENT_TYPES) keys.add("system-prompts/" + agent + ".md");
        ALL_FILE_KEYS = Collections.unmodifiableSet(keys);
    }

    @Override
    public String id() { return "project_config"; }

    @Override
    public String description() {
        return "View, edit, and manage kompile project configuration files. "
                + "Actions: "
                + "'status' — show which config files exist, their sizes, and whether they contain the kompile mandate. "
                + "'view' — read the contents of a specific config file. "
                + "'update' — write new content to a config file (creates if missing, replaces if exists). "
                + "'append' — append content to an existing config file (useful for adding mandate to existing configs). "
                + "'reset' — reset a file to the default template from kompile. "
                + "'remove_mandate' — remove the kompile mandate section from a platform config, keeping other content. "
                + "'add_mcp_server' — add or update a server entry in .mcp.json (merges with existing servers). "
                + "Files: AGENTS.md, .mcp.json, CLAUDE.md, .cursorrules, .windsurfrules, "
                + ".github/copilot-instructions.md, system-prompt.md, system-prompts/<agent>.md";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode actionProp = props.putObject("action");
        actionProp.put("type", "string");
        actionProp.put("description",
                "Action to perform: 'status', 'view', 'update', 'append', 'reset', "
                        + "'remove_mandate', or 'add_mcp_server'");
        ArrayNode actionEnum = actionProp.putArray("enum");
        actionEnum.add("status").add("view").add("update").add("append")
                  .add("reset").add("remove_mandate").add("add_mcp_server");

        ObjectNode fileProp = props.putObject("file");
        fileProp.put("type", "string");
        fileProp.put("description",
                "Config file to operate on. One of: AGENTS.md, .mcp.json, CLAUDE.md, "
                        + ".cursorrules, .windsurfrules, .github/copilot-instructions.md, "
                        + "system-prompt.md, system-prompts/claude.md, system-prompts/qwen.md, "
                        + "system-prompts/codex.md, system-prompts/gemini.md, system-prompts/opencode.md. "
                        + "Required for all actions except 'status'.");

        ObjectNode contentProp = props.putObject("content");
        contentProp.put("type", "string");
        contentProp.put("description",
                "Content to write (for 'update' and 'append' actions).");

        ObjectNode serverNameProp = props.putObject("server_name");
        serverNameProp.put("type", "string");
        serverNameProp.put("description",
                "MCP server name for 'add_mcp_server' action (default: 'kompile').");

        ObjectNode commandProp = props.putObject("command");
        commandProp.put("type", "string");
        commandProp.put("description",
                "Command path for 'add_mcp_server' action.");

        ObjectNode argsProp = props.putObject("args");
        argsProp.put("type", "array");
        argsProp.put("description",
                "Command args array for 'add_mcp_server' action.");
        argsProp.putObject("items").put("type", "string");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "config"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage project configuration");

        String action = params.path("action").asText("");
        try {
            return switch (action) {
                case "status"          -> doStatus(context);
                case "view"            -> doView(params, context);
                case "update"          -> doUpdate(params, context);
                case "append"          -> doAppend(params, context);
                case "reset"           -> doReset(params, context);
                case "remove_mandate"  -> doRemoveMandate(params, context);
                case "add_mcp_server"  -> doAddMcpServer(params, context);
                default -> ToolResult.error("Unknown action: '" + action
                        + "'. Use: status, view, update, append, reset, remove_mandate, or add_mcp_server.");
            };
        } catch (Exception e) {
            return ToolResult.error("project_config error: " + e.getMessage());
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private ToolResult doStatus(ToolContext context) throws IOException {
        Path projDir = context.getWorkingDirectory();
        Path instDir = installDir();

        StringBuilder sb = new StringBuilder();
        sb.append("Project directory: ").append(projDir).append('\n');
        sb.append("Install directory: ").append(instDir).append('\n');
        sb.append('\n');

        sb.append(String.format("%-42s %-8s %-6s %s%n", "File", "Status", "Size", "Mandate"));
        sb.append("─".repeat(75)).append('\n');

        // Project-level files
        appendFileStatus(sb, "AGENTS.md", projDir.resolve("AGENTS.md"));
        appendFileStatus(sb, ".mcp.json", projDir.resolve(".mcp.json"));
        for (String[] pc : PLATFORM_CONFIGS) {
            appendFileStatus(sb, pc[0], projDir.resolve(pc[1]));
        }

        // System-level files
        appendFileStatus(sb, "system-prompt.md", instDir.resolve("system-prompt.md"));
        for (String agent : AGENT_TYPES) {
            appendFileStatus(sb, "system-prompts/" + agent + ".md",
                    instDir.resolve("system-prompts").resolve(agent + ".md"));
        }

        // Template info
        sb.append('\n');
        String agentsTmpl = loadResource(AGENTS_MD_RESOURCE);
        String sysTmpl = loadResource(SYSTEM_PROMPT_RESOURCE);
        sb.append("Available templates:\n");
        sb.append("  AGENTS.md template: ").append(agentsTmpl != null
                ? agentsTmpl.split("\n").length + " lines" : "not found").append('\n');
        sb.append("  system-prompt.md template: ").append(sysTmpl != null
                ? sysTmpl.split("\n").length + " lines" : "not found").append('\n');

        return ToolResult.success("project_config status", sb.toString());
    }

    private ToolResult doView(JsonNode params, ToolContext context) throws Exception {
        String file = requireFile(params);
        Path path = resolveConfigPath(file, context);

        if (!Files.exists(path)) {
            return ToolResult.error("File does not exist: " + path);
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        return ToolResult.success("project_config view: " + file, content,
                Map.of("file", file, "path", path.toString(),
                        "lines", content.split("\n").length,
                        "bytes", content.length()));
    }

    private ToolResult doUpdate(JsonNode params, ToolContext context) throws Exception {
        String file = requireFile(params);
        String content = params.path("content").asText(null);
        if (content == null || content.isEmpty()) {
            return ToolResult.error("'content' is required for 'update' action.");
        }

        Path path = resolveConfigPath(file, context);
        Files.createDirectories(path.getParent());

        boolean existed = Files.exists(path);
        String previous = existed ? Files.readString(path, StandardCharsets.UTF_8) : null;

        Files.writeString(path, content, StandardCharsets.UTF_8);

        String verb = existed ? "Updated" : "Created";
        StringBuilder sb = new StringBuilder();
        sb.append(verb).append(": ").append(path).append('\n');
        sb.append("Size: ").append(content.length()).append(" bytes, ")
                .append(content.split("\n").length).append(" lines\n");
        if (existed && previous != null) {
            sb.append("Previous size: ").append(previous.length()).append(" bytes\n");
        }

        return ToolResult.success("project_config " + verb.toLowerCase() + ": " + file,
                sb.toString(), Map.of("action", verb.toLowerCase(), "file", file));
    }

    private ToolResult doAppend(JsonNode params, ToolContext context) throws Exception {
        String file = requireFile(params);
        String content = params.path("content").asText(null);
        if (content == null || content.isEmpty()) {
            return ToolResult.error("'content' is required for 'append' action.");
        }

        Path path = resolveConfigPath(file, context);

        if (!Files.exists(path)) {
            return ToolResult.error("File does not exist: " + path
                    + ". Use 'update' to create a new file.");
        }

        String existing = Files.readString(path, StandardCharsets.UTF_8);
        String combined = existing + "\n\n" + content;
        Files.writeString(path, combined, StandardCharsets.UTF_8);

        return ToolResult.success("project_config appended: " + file,
                "Appended " + content.split("\n").length + " lines to " + path + "\n"
                        + "Total size: " + combined.length() + " bytes, "
                        + combined.split("\n").length + " lines",
                Map.of("action", "appended", "file", file));
    }

    private ToolResult doReset(JsonNode params, ToolContext context) throws Exception {
        String file = requireFile(params);
        Path path = resolveConfigPath(file, context);

        String template = resolveTemplate(file);
        if (template == null) {
            return ToolResult.error("No default template available for: " + file);
        }

        Files.createDirectories(path.getParent());
        boolean existed = Files.exists(path);
        Files.writeString(path, template, StandardCharsets.UTF_8);

        return ToolResult.success("project_config reset: " + file,
                (existed ? "Replaced" : "Created") + " " + path + " with default template ("
                        + template.split("\n").length + " lines)",
                Map.of("action", "reset", "file", file));
    }

    private ToolResult doRemoveMandate(JsonNode params, ToolContext context) throws Exception {
        String file = requireFile(params);

        // Only valid for platform configs
        boolean isPlatformConfig = false;
        for (String[] pc : PLATFORM_CONFIGS) {
            if (pc[0].equals(file)) { isPlatformConfig = true; break; }
        }
        if (!isPlatformConfig) {
            return ToolResult.error("'remove_mandate' only applies to platform config files: "
                    + "CLAUDE.md, .cursorrules, .windsurfrules, .github/copilot-instructions.md");
        }

        Path path = resolveConfigPath(file, context);
        if (!Files.exists(path)) {
            return ToolResult.error("File does not exist: " + path);
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (!content.contains(MANDATE_MARKER)) {
            return ToolResult.success("project_config remove_mandate: " + file,
                    "No kompile mandate found in " + file + " — nothing to remove.");
        }

        String cleaned = removeMandateSection(content);
        Files.writeString(path, cleaned, StandardCharsets.UTF_8);

        return ToolResult.success("project_config remove_mandate: " + file,
                "Removed kompile mandate from " + file + "\n"
                        + "Before: " + content.split("\n").length + " lines, "
                        + "After: " + cleaned.split("\n").length + " lines",
                Map.of("action", "remove_mandate", "file", file));
    }

    private ToolResult doAddMcpServer(JsonNode params, ToolContext context) throws IOException {
        String serverName = params.path("server_name").asText("kompile");
        String command = params.path("command").asText(null);
        JsonNode argsNode = params.path("args");

        if (command == null || command.isEmpty()) {
            return ToolResult.error("'command' is required for 'add_mcp_server' action.");
        }

        List<String> args = new ArrayList<>();
        if (argsNode.isArray()) {
            for (JsonNode a : argsNode) args.add(a.asText());
        }

        Path mcpJsonPath = context.getWorkingDirectory().resolve(".mcp.json");
        String existing = Files.exists(mcpJsonPath)
                ? Files.readString(mcpJsonPath, StandardCharsets.UTF_8) : null;

        // Build the server entry
        ObjectNode root;
        ObjectNode servers;
        if (existing != null && !existing.isBlank()) {
            try {
                root = (ObjectNode) MAPPER.readTree(existing);
            } catch (Exception e) {
                return ToolResult.error("Failed to parse existing .mcp.json: " + e.getMessage());
            }
            servers = root.has("mcpServers")
                    ? (ObjectNode) root.get("mcpServers")
                    : root.putObject("mcpServers");
        } else {
            root = MAPPER.createObjectNode();
            servers = root.putObject("mcpServers");
        }

        boolean existed = servers.has(serverName);
        ObjectNode entry = servers.putObject(serverName);
        entry.put("command", command);
        ArrayNode argsArray = entry.putArray("args");
        for (String a : args) argsArray.add(a);

        String output = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        Files.createDirectories(mcpJsonPath.getParent());
        Files.writeString(mcpJsonPath, output, StandardCharsets.UTF_8);

        String verb = existed ? "Updated" : "Added";
        return ToolResult.success("project_config add_mcp_server: " + serverName,
                verb + " server '" + serverName + "' in .mcp.json\n"
                        + "command: " + command + "\n"
                        + "args: " + args,
                Map.of("action", "add_mcp_server", "server", serverName, "verb", verb.toLowerCase()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String requireFile(JsonNode params) throws ToolExecutionException {
        String file = params.path("file").asText(null);
        if (file == null || file.isEmpty()) {
            throw new ToolExecutionException("'file' parameter is required for this action.");
        }
        return file;
    }

    /**
     * Resolve a file key to an actual filesystem path.
     * Project-level files resolve relative to working directory.
     * System-level files (system-prompt.md, system-prompts/*) resolve to ~/.kompile/.
     */
    private Path resolveConfigPath(String file, ToolContext context) {
        Path projDir = context.getWorkingDirectory();
        Path instDir = installDir();

        if (file.equals("system-prompt.md")) {
            return instDir.resolve("system-prompt.md");
        }
        if (file.startsWith("system-prompts/")) {
            return instDir.resolve(file);
        }

        // Platform config path mapping
        for (String[] pc : PLATFORM_CONFIGS) {
            if (pc[0].equals(file)) {
                return projDir.resolve(pc[1]);
            }
        }

        // Direct project-level file
        return projDir.resolve(file);
    }

    /**
     * Resolve the default template content for a given file key.
     * Returns the template or a generated per-agent prompt.
     */
    private String resolveTemplate(String file) {
        if (file.equals("AGENTS.md")) {
            return loadResource(AGENTS_MD_RESOURCE);
        }
        if (file.equals("system-prompt.md")) {
            return loadResource(SYSTEM_PROMPT_RESOURCE);
        }
        if (file.startsWith("system-prompts/") && file.endsWith(".md")) {
            String agent = file.replace("system-prompts/", "").replace(".md", "");
            String systemPrompt = loadResource(SYSTEM_PROMPT_RESOURCE);
            if (systemPrompt == null) return null;
            return buildPerAgentPrompt(agent, systemPrompt);
        }

        // Platform configs get the mandate directive as their default
        for (String[] pc : PLATFORM_CONFIGS) {
            if (pc[0].equals(file)) {
                String mandate = loadResource(AGENTS_MD_RESOURCE);
                if (mandate == null) return null;
                return buildPlatformDirective(mandate);
            }
        }

        return null;
    }

    private void appendFileStatus(StringBuilder sb, String label, Path path) throws IOException {
        if (Files.exists(path)) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            boolean hasMandate = content.contains(MANDATE_MARKER);
            sb.append(String.format("%-42s %-8s %5dB %s%n",
                    label, "exists", content.length(),
                    hasMandate ? "yes" : "no"));
        } else {
            sb.append(String.format("%-42s %-8s %6s %s%n",
                    label, "missing", "-", "-"));
        }
    }

    private String removeMandateSection(String content) {
        int start = content.indexOf("# " + MANDATE_MARKER);
        if (start < 0) start = content.indexOf(MANDATE_MARKER);
        if (start < 0) return content;

        String endMarker = "**For the complete tool reference with parameters, see [AGENTS.md](AGENTS.md).**";
        int end = content.indexOf(endMarker, start);
        if (end > 0) {
            end += endMarker.length();
            while (end < content.length() && content.charAt(end) == '\n') end++;
        } else {
            end = content.length();
        }

        String before = content.substring(0, start).stripTrailing();
        String after = end < content.length() ? content.substring(end).stripLeading() : "";

        if (before.isEmpty()) return after;
        if (after.isEmpty()) return before;
        return before + "\n\n" + after;
    }

    private String buildPerAgentPrompt(String agentName, String centralPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Kompile Tool Mandate — ").append(agentName).append(" agent override\n\n");
        sb.append("You are a ").append(agentName).append(" agent operating under kompile orchestration.\n");
        sb.append("The kompile MCP tools are available to you. You MUST use them.\n\n");

        switch (agentName) {
            case "claude" -> {
                sb.append("The kompile MCP server is configured in this project's .mcp.json.\n");
                sb.append("All kompile tools (read, write, edit, grep, glob, etc.) are available as MCP tool calls.\n");
                sb.append("Use `mcp__kompile__<tool>` for each tool. Do NOT use bash for file I/O or search.\n");
            }
            case "codex", "opencode" -> {
                sb.append("The kompile tool mandate has been injected into this project's AGENTS.md.\n");
                sb.append("You MUST follow all rules in AGENTS.md. Do NOT use shell commands for file I/O or search.\n");
            }
            case "qwen" -> {
                sb.append("The kompile MCP server provides all file I/O, search, and orchestration tools.\n");
                sb.append("Use the MCP tools, not shell commands. Do NOT use bash for file reading, searching, or editing.\n");
            }
            case "gemini" -> {
                sb.append("The kompile tool mandate is active. All file I/O, search, and web operations\n");
                sb.append("MUST use the kompile MCP tools, not shell equivalents.\n");
            }
            default -> {
                sb.append("The kompile MCP tools are available and MUST be used for all file I/O, search,\n");
                sb.append("and web operations. Do NOT use shell equivalents.\n");
            }
        }

        sb.append("\nRefer to AGENTS.md in the project root for the full tool reference and parameter documentation.\n");
        return sb.toString();
    }

    private String buildPlatformDirective(String fullMandate) {
        StringBuilder sb = new StringBuilder();
        sb.append("# KOMPILE TOOL ORCHESTRATION MANDATE\n\n");
        sb.append("# THIS SECTION IS AUTO-GENERATED BY `kompile init`. DO NOT REMOVE.\n");
        sb.append("# Full tool reference: see AGENTS.md in this project root.\n\n");

        int mcpConfigStart = fullMandate.indexOf("## MCP SERVER CONFIGURATION");
        if (mcpConfigStart > 0) {
            sb.append(fullMandate, 0, mcpConfigStart);
        } else {
            sb.append(fullMandate);
        }

        sb.append("\n---\n\n");
        sb.append("**For the complete tool reference with parameters, see [AGENTS.md](AGENTS.md).**\n");
        return sb.toString();
    }

    private static Path installDir() {
        return Paths.get(System.getProperty("user.home"), ".kompile").toAbsolutePath().normalize();
    }

    private String loadResource(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
