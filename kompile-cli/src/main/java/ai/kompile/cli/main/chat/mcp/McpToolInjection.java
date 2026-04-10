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
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Handles injection of kompile MCP tools into spawned agents.
 *
 * <p>Supports all passthrough agents: Qwen Code, Claude Code, Codex, Gemini CLI, OpenCode.
 * Each agent reads MCP server configs from its own settings file.</p>
 *
 * <p>Two MCP server modes are supported:
 * <ul>
 *   <li><b>SSE mode</b>: When kompile-app is running, connects to it via Server-Sent Events</li>
 *   <li><b>Stdio mode</b> (embedded): When kompile-app is OFF, uses the CLI's built-in MCP stdio server</li>
 * </ul>
 *
 * <p>Injection is <b>dynamic</b>: the original settings file is backed up before injection
 * and restored via {@link #removeTools(Path)} after the agent exits, preventing pollution.</p>
 */
public class McpToolInjection {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String BACKUP_SUFFIX = ".kompile-backup";

    /**
     * Inject kompile MCP tools into the appropriate agent's settings file.
     * Automatically selects stdio mode (embedded MCP server).
     *
     * @param agentWorkingDir the working directory where the agent will run
     * @param agentName       the agent name (claude, codex, qwen, gemini, opencode)
     * @return the path to the settings file that was written, or null if unsupported
     */
    public static Path injectTools(Path agentWorkingDir, String agentName) throws IOException {
        return injectTools(agentWorkingDir, agentName, null);
    }

    /**
     * Inject kompile MCP tools into the appropriate agent's settings file.
     * Selects SSE mode when {@code sseUrl} is provided (kompile-app is running),
     * otherwise falls back to stdio mode (embedded MCP server).
     *
     * <p>The original settings file is backed up before injection.
     * Call {@link #removeTools(Path)} with the returned path to restore the original.</p>
     *
     * @param agentWorkingDir the working directory where the agent will run
     * @param agentName       the agent name (claude, codex, qwen, gemini, opencode)
     * @param sseUrl          the kompile-app SSE URL (e.g. http://localhost:8080/mcp/sse), or null for stdio mode
     * @return the path to the settings file that was written, or null if unsupported
     */
    public static Path injectTools(Path agentWorkingDir, String agentName, String sseUrl) throws IOException {
        Path normalizedWd = agentWorkingDir.toAbsolutePath().normalize();
        String agent = agentName != null ? agentName.toLowerCase(Locale.ROOT) : "qwen";
        String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";

        // For stdio mode, resolve the CLI launcher
        McpToolInjectionSupport.CliLauncher launcher = null;
        if ("stdio".equals(mode)) {
            launcher = McpToolInjectionSupport.findCliLauncher();
            if (launcher == null) {
                System.err.println("[MCP] Warning: Could not resolve kompile CLI launcher for MCP injection");
                return null;
            }
        }

        if (agent.contains("claude")) {
            return injectForClaude(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("codex")) {
            return injectForCodex(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("gemini")) {
            return injectForGemini(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("opencode")) {
            return injectForOpenCode(normalizedWd, launcher, sseUrl);
        } else {
            // Default: Qwen Code format
            return injectForQwen(normalizedWd, launcher, sseUrl);
        }
    }

    /**
     * Remove injected kompile MCP tools by restoring the original settings file.
     * If a backup exists, it replaces the settings file. If no backup exists
     * (settings file was created fresh), the settings file is deleted.
     *
     * @param settingsFile the path returned by {@link #injectTools}
     */
    public static void removeTools(Path settingsFile) {
        if (settingsFile == null) return;
        try {
            Path backup = settingsFile.resolveSibling(settingsFile.getFileName() + BACKUP_SUFFIX);
            if (Files.exists(backup)) {
                Files.move(backup, settingsFile, StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[MCP] Restored original settings: " + settingsFile);
            } else {
                // No backup means we created the file fresh — remove the kompile entry
                // but leave the file if it has other content
                removeKompileEntry(settingsFile);
            }
        } catch (IOException e) {
            System.err.println("[MCP] Warning: Could not restore settings: " + e.getMessage());
        }
    }

    // ── Claude Code ────────────────────────────────────────────────────────

    /**
     * Claude Code 2.x reads MCP servers from {@code .mcp.json} in the project root.
     * This takes priority over {@code ~/.claude/settings.json}.
     */
    private static Path injectForClaude(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                         String sseUrl) throws IOException {
        Path settingsFile = workingDir.resolve(".mcp.json");
        return writeConfig(settingsFile, workingDir, launcher, sseUrl);
    }

    // ── Qwen Code ──────────────────────────────────────────────────────────

    private static Path injectForQwen(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                       String sseUrl) throws IOException {
        Path qwenDir = workingDir.resolve(".qwen");
        Files.createDirectories(qwenDir);
        Path settingsFile = qwenDir.resolve("settings.json");
        return writeConfig(settingsFile, workingDir, launcher, sseUrl);
    }

    // ── Codex ──────────────────────────────────────────────────────────────

    private static Path injectForCodex(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                        String sseUrl) throws IOException {
        Path codexDir = Path.of(System.getProperty("user.home"), ".codex");
        Files.createDirectories(codexDir);
        Path configFile = codexDir.resolve("config.toml");

        // Backup before modifying
        backupIfExists(configFile);

        // Read existing content, remove [mcp_servers.kompile] section to prevent stale/duplicate config
        StringBuilder toml = new StringBuilder();
        if (Files.exists(configFile)) {
            String existing = Files.readString(configFile);
            // Use greedy matching that stops only at a new top-level section
            // (sections without a dot after the opening bracket), so nested
            // sections like [mcp_servers.kompile.env] are included in the removal.
            String cleaned = existing.replaceAll(
                "(?ms)^\\[mcp_servers\\.kompile\\].*?(?=\\n\\[[^.]|\\z)", "").trim();
            toml.append(cleaned);
            if (toml.length() > 0) toml.append("\n\n");
        }

        // Append the kompile MCP server section
        toml.append("[mcp_servers.kompile]\n");
        if (sseUrl != null && !sseUrl.isBlank()) {
            toml.append("type = \"sse\"\n");
            toml.append("url = \"").append(escapeToml(sseUrl)).append("\"\n");
        } else {
            List<String> fullArgs = launcher.buildArgs(workingDir);
            toml.append("type = \"stdio\"\n");
            toml.append("command = \"").append(escapeToml(launcher.command())).append("\"\n");
            toml.append("args = [");
            for (int i = 0; i < fullArgs.size(); i++) {
                if (i > 0) toml.append(", ");
                toml.append("\"").append(escapeToml(fullArgs.get(i))).append("\"");
            }
            toml.append("]\n");
        }

        Files.writeString(configFile, toml.toString());

        System.err.println("[MCP] Injected kompile MCP tools (" + (sseUrl != null ? "sse" : "stdio") + ") into " + configFile);
        System.err.flush();

        return configFile;
    }

    private static String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Gemini CLI ─────────────────────────────────────────────────────────

    private static Path injectForGemini(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                         String sseUrl) throws IOException {
        Path geminiDir = Path.of(System.getProperty("user.home"), ".gemini");
        Files.createDirectories(geminiDir);
        Path settingsFile = geminiDir.resolve("settings.json");
        return writeConfig(settingsFile, workingDir, launcher, sseUrl);
    }

    // ── OpenCode ───────────────────────────────────────────────────────────

    private static Path injectForOpenCode(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                           String sseUrl) throws IOException {
        Path settingsFile = workingDir.resolve(".opencode.json");
        return writeConfig(settingsFile, workingDir, launcher, sseUrl);
    }

    // ── Shared config writer ───────────────────────────────────────────────

    /**
     * Reads or creates a settings.json file, backs it up, then adds the kompile MCP server config.
     * Supports both SSE mode (when sseUrl is provided) and stdio mode (when launcher is provided).
     */
    private static Path writeConfig(Path settingsFile, Path workingDir,
                                     McpToolInjectionSupport.CliLauncher launcher,
                                     String sseUrl) throws IOException {
        // Backup before modifying
        backupIfExists(settingsFile);

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

        // Add or replace only the "kompile" entry under mcpServers, preserving other servers
        ObjectNode mcpServers;
        if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
            mcpServers = (ObjectNode) root.get("mcpServers");
        } else {
            mcpServers = root.putObject("mcpServers");
        }

        ObjectNode kompile = mcpServers.putObject("kompile");

        String mode;
        if (sseUrl != null && !sseUrl.isBlank()) {
            // SSE mode: connect to running kompile-app
            kompile.put("url", sseUrl);
            kompile.put("transport", "sse");
            mode = "sse";
        } else {
            // Stdio mode: launch embedded CLI MCP server
            kompile.put("command", launcher.command());
            List<String> fullArgs = launcher.buildArgs(workingDir);
            ArrayNode argsArray = kompile.putArray("args");
            for (String arg : fullArgs) {
                argsArray.add(arg);
            }
            mode = "stdio";
        }

        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        System.err.println("[MCP] Injected kompile MCP tools (" + mode + ") into " + settingsFile);
        System.err.flush();

        return settingsFile;
    }

    /**
     * Create a backup of the settings file if it exists.
     * Preserves the original backup — never overwrites an existing .kompile-backup
     * to avoid destroying the original clean backup on rapid re-injection.
     */
    private static void backupIfExists(Path settingsFile) throws IOException {
        if (Files.exists(settingsFile)) {
            Path backup = settingsFile.resolveSibling(settingsFile.getFileName() + BACKUP_SUFFIX);
            if (!Files.exists(backup)) {
                Files.copy(settingsFile, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Remove only the "kompile" entry from mcpServers in a JSON settings file,
     * or remove the [mcp_servers.kompile] section from a TOML config file.
     * If the file was created fresh by injection and has no other content, delete it.
     */
    private static void removeKompileEntry(Path settingsFile) throws IOException {
        if (!Files.exists(settingsFile)) return;

        String fileName = settingsFile.getFileName().toString();
        // Handle TOML files (e.g., Codex config.toml)
        if (fileName.endsWith(".toml")) {
            removeKompileTomlEntry(settingsFile);
            return;
        }

        // Handle JSON settings files
        try {
            String content = Files.readString(settingsFile);
            ObjectNode root = (ObjectNode) OM.readTree(content);
            if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
                ObjectNode mcpServers = (ObjectNode) root.get("mcpServers");
                mcpServers.remove("kompile");
                if (mcpServers.isEmpty()) {
                    root.remove("mcpServers");
                }
            }
            // If the root only had mcpServers and it's now empty, delete the file
            if (root.isEmpty()) {
                Files.deleteIfExists(settingsFile);
                System.err.println("[MCP] Removed injected settings file: " + settingsFile);
            } else {
                Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                System.err.println("[MCP] Removed kompile entry from: " + settingsFile);
            }
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not clean up kompile entry: " + e.getMessage());
        }
    }

    /**
     * Remove the [mcp_servers.kompile] section from a TOML config file.
     */
    private static void removeKompileTomlEntry(Path tomlFile) throws IOException {
        if (!Files.exists(tomlFile)) return;
        try {
            String existing = Files.readString(tomlFile);
            // Use greedy matching that stops only at a new top-level section
            // (sections without a dot after the opening bracket), so nested
            // sections like [mcp_servers.kompile.env] are included in the removal.
            String cleaned = existing.replaceAll(
                "(?ms)^\\[mcp_servers\\.kompile\\].*?(?=\\n\\[[^.]|\\z)", "").trim();
            // Remove trailing blank lines
            cleaned = cleaned.replaceAll("\\n+$", "");
            if (cleaned.isEmpty()) {
                Files.deleteIfExists(tomlFile);
                System.err.println("[MCP] Removed injected TOML config file: " + tomlFile);
            } else {
                Files.writeString(tomlFile, cleaned + "\n");
                System.err.println("[MCP] Removed kompile section from: " + tomlFile);
            }
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not clean up kompile TOML entry: " + e.getMessage());
        }
    }
}
