/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.main.chat.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Handles injection of kompile MCP tools into spawned agents.
 *
 * Supports all passthrough agents: Qwen Code, Claude Code, Codex, Gemini CLI, OpenCode.
 * Each agent reads MCP server configs from its own settings file.
 */
public class McpToolInjection {

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * Inject kompile MCP stdio server config into the appropriate agent's settings file.
     *
     * @param agentWorkingDir the working directory where the agent will run
     * @param agentName       the agent name (claude, codex, qwen, gemini, opencode)
     * @return the path to the settings file that was written, or null if unsupported
     */
    public static Path injectTools(Path agentWorkingDir, String agentName) throws IOException {
        McpToolInjectionSupport.CliLauncher launcher = McpToolInjectionSupport.findCliLauncher();
        if (launcher == null) {
            System.err.println("[MCP] Warning: Could not resolve kompile CLI launcher for MCP injection");
            return null;
        }

        Path normalizedWd = agentWorkingDir.toAbsolutePath().normalize();
        String agent = agentName != null ? agentName.toLowerCase(Locale.ROOT) : "qwen";

        if (agent.contains("claude")) {
            return injectForClaude(normalizedWd, launcher);
        } else if (agent.contains("codex")) {
            return injectForCodex(normalizedWd, launcher);
        } else if (agent.contains("gemini")) {
            return injectForGemini(normalizedWd, launcher);
        } else if (agent.contains("opencode")) {
            return injectForOpenCode(normalizedWd, launcher);
        } else {
            // Default: Qwen Code format
            return injectForQwen(normalizedWd, launcher);
        }
    }

    /**
     * Legacy overload for backward compatibility.
     * Injects for Qwen by default (original behavior).
     */
    public static Path injectTools(Path agentWorkingDir, String kompileBinary, String ignored) throws IOException {
        return injectTools(agentWorkingDir, "qwen");
    }

    // ── Claude Code ────────────────────────────────────────────────────────

    /**
     * Claude Code reads MCP servers from ~/.claude/settings.json under mcpServers.
     * Format: { "mcpServers": { "kompile": { "command": "...", "args": [...] } } }
     */
    private static Path injectForClaude(Path workingDir, McpToolInjectionSupport.CliLauncher launcher) throws IOException {
        Path claudeDir = Path.of(System.getProperty("user.home"), ".claude");
        Files.createDirectories(claudeDir);
        Path settingsFile = claudeDir.resolve("settings.json");
        return writeStdioConfig(settingsFile, workingDir, launcher);
    }

    // ── Qwen Code ──────────────────────────────────────────────────────────

    /**
     * Qwen Code reads MCP servers from .qwen/settings.json (project-local) or ~/.qwen/settings.json.
     */
    private static Path injectForQwen(Path workingDir, McpToolInjectionSupport.CliLauncher launcher) throws IOException {
        Path qwenDir = workingDir.resolve(".qwen");
        Files.createDirectories(qwenDir);
        Path settingsFile = qwenDir.resolve("settings.json");
        return writeStdioConfig(settingsFile, workingDir, launcher);
    }

    // ── Codex ──────────────────────────────────────────────────────────────

    /**
     * Codex reads MCP servers from ~/.codex/config.toml under [mcp_servers.NAME].
     * Format:
     * <pre>
     * [mcp_servers.kompile]
     * type = "stdio"
     * command = "/path/to/kompile"
     * args = ["mcp-stdio", "--work-dir", "/path/to/dir"]
     * </pre>
     */
    private static Path injectForCodex(Path workingDir, McpToolInjectionSupport.CliLauncher launcher) throws IOException {
        Path codexDir = Path.of(System.getProperty("user.home"), ".codex");
        Files.createDirectories(codexDir);
        Path configFile = codexDir.resolve("config.toml");

        // Read existing content, remove [mcp_servers.kompile] section to prevent stale/duplicate config
        StringBuilder toml = new StringBuilder();
        if (Files.exists(configFile)) {
            String existing = Files.readString(configFile);
            // Remove the [mcp_servers.kompile] section specifically
            String cleaned = existing.replaceAll(
                "(?ms)^\\[mcp_servers\\.kompile\\].*?(?=\\n\\[|\\z)", "").trim();
            toml.append(cleaned);
            if (toml.length() > 0) toml.append("\n\n");
        }

        // Append the kompile MCP server section
        List<String> fullArgs = launcher.buildArgs(workingDir);
        toml.append("[mcp_servers.kompile]\n");
        toml.append("type = \"stdio\"\n");
        toml.append("command = \"").append(escapeToml(launcher.command())).append("\"\n");
        toml.append("args = [");
        for (int i = 0; i < fullArgs.size(); i++) {
            if (i > 0) toml.append(", ");
            toml.append("\"").append(escapeToml(fullArgs.get(i))).append("\"");
        }
        toml.append("]\n");

        Files.writeString(configFile, toml.toString());

        System.err.println("[MCP] Injected kompile MCP tools into " + configFile);
        System.err.flush();

        return configFile;
    }

    private static String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Gemini CLI ─────────────────────────────────────────────────────────

    /**
     * Gemini CLI reads MCP servers from ~/.gemini/settings.json under mcpServers.
     */
    private static Path injectForGemini(Path workingDir, McpToolInjectionSupport.CliLauncher launcher) throws IOException {
        Path geminiDir = Path.of(System.getProperty("user.home"), ".gemini");
        Files.createDirectories(geminiDir);
        Path settingsFile = geminiDir.resolve("settings.json");
        return writeStdioConfig(settingsFile, workingDir, launcher);
    }

    // ── OpenCode ───────────────────────────────────────────────────────────

    /**
     * OpenCode reads MCP servers from .opencode.json in the project root.
     * Format: { "mcpServers": { "kompile": { "type": "stdio", "command": "...", "args": [...] } } }
     */
    private static Path injectForOpenCode(Path workingDir, McpToolInjectionSupport.CliLauncher launcher) throws IOException {
        Path settingsFile = workingDir.resolve(".opencode.json");
        return writeStdioConfig(settingsFile, workingDir, launcher);
    }

    // ── Shared config writer ───────────────────────────────────────────────

    /**
     * Reads or creates a settings.json file, then adds/updates the kompile MCP server config.
     * Properly separates command and args (fixes the broken single-string command issue).
     *
     * Clears all existing MCP servers before injecting kompile to prevent deadlocks
     * (e.g., a subagent reading an .opencode.json that points back to the parent kompile-cli).
     */
    private static Path writeStdioConfig(Path settingsFile, Path workingDir,
                                          McpToolInjectionSupport.CliLauncher launcher) throws IOException {
        ObjectNode root;
        if (Files.exists(settingsFile)) {
            String existing = Files.readString(settingsFile);
            try {
                root = (ObjectNode) OM.readTree(existing);
            } catch (Exception e) {
                System.err.println("[MCP] Warning: Could not parse existing " + settingsFile.getFileName() + ", creating new: " + e.getMessage());
                root = OM.createObjectNode();
            }
        } else {
            root = OM.createObjectNode();
        }

        // Clear ALL existing MCP servers to prevent deadlocks (subagent reading parent's config)
        // Then create a fresh mcpServers object with only kompile
        root.remove("mcpServers");
        ObjectNode mcpServers = root.putObject("mcpServers");

        ObjectNode kompile = mcpServers.putObject("kompile");
        kompile.put("command", launcher.command());

        // Build the full args list: launcher prefix args + mcp-stdio + --work-dir + path
        List<String> fullArgs = launcher.buildArgs(workingDir);
        ArrayNode argsArray = kompile.putArray("args");
        for (String arg : fullArgs) {
            argsArray.add(arg);
        }

        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        System.err.println("[MCP] Injected kompile MCP tools into " + settingsFile);
        System.err.flush();

        return settingsFile;
    }
}
