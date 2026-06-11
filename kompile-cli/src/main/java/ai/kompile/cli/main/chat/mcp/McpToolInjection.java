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

    /** JVM shutdown hook for crash-safe cleanup of injected tools. */
    private static volatile Thread shutdownHook;

    /** Tracks which settings file needs cleanup on shutdown. */
    private static volatile Path pendingCleanupFile;

    /**
     * Tracks whether the settings file existed before injection.
     * If true, removeTools() must restore the file (not delete it) even when
     * the backup is missing or the file only contained a kompile entry.
     * This prevents persistent configs created by {@code kompile init} from being destroyed.
     */
    private static volatile boolean fileExistedBeforeInjection;

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

        // Clean up any leaked kompile entries from prior crashed sessions
        try {
            cleanupLeakedEntries(normalizedWd);
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not clean leaked entries from prior sessions: " + e.getMessage());
        }

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

        // Track whether the settings file already exists before injection.
        // This determines cleanup behavior: pre-existing files are restored, not deleted.
        Path preCheckPath = resolveSettingsPath(normalizedWd, agent);
        fileExistedBeforeInjection = preCheckPath != null && Files.exists(preCheckPath);

        if (agent.contains("claude")) {
            return registerAndReturn(injectForClaude(normalizedWd, launcher, sseUrl));
        } else if (agent.contains("codex")) {
            return registerAndReturn(injectForCodex(normalizedWd, launcher, sseUrl));
        } else if (agent.contains("gemini")) {
            return registerAndReturn(injectForGemini(normalizedWd, launcher, sseUrl));
        } else if (agent.contains("opencode")) {
            return registerAndReturn(injectForOpenCode(normalizedWd, launcher, sseUrl));
        } else {
            // Default: Qwen Code format
            return registerAndReturn(injectForQwen(normalizedWd, launcher, sseUrl));
        }
    }

    /** Helper to register shutdown hook and return the settings file path. */
    private static Path registerAndReturn(Path settingsFile) {
        if (settingsFile != null) {
            registerShutdownHook(settingsFile);
        }
        return settingsFile;
    }

    /**
     * Remove injected kompile MCP tools by restoring the original settings file.
     * If a backup exists, it replaces the settings file. If no backup exists
     * (settings file was created fresh), the settings file is deleted.
     *
     * @param settingsFile the path returned by {@link #injectTools}
     */
    public static void removeTools(Path settingsFile) {
        // Deregister the shutdown hook to avoid double-cleanup
        deregisterShutdownHook();

        if (settingsFile == null) return;
        boolean preExisted = fileExistedBeforeInjection;
        try {
            Path backup = settingsFile.resolveSibling(settingsFile.getFileName() + BACKUP_SUFFIX);
            if (Files.exists(backup)) {
                Files.move(backup, settingsFile, StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[MCP] Restored original settings: " + settingsFile);
            } else if (preExisted) {
                // The file existed before injection (e.g. created by "kompile init")
                // but no backup was needed because injection overwrote it in place.
                // Leave the file as-is — it still has a valid kompile entry which is
                // the persistent config the user expects for future sessions.
                System.err.println("[MCP] Preserved existing settings: " + settingsFile);
            } else {
                // No backup and file didn't exist before — we created it fresh.
                // Remove the kompile entry, and delete the file if empty.
                removeKompileEntry(settingsFile);
            }
        } catch (IOException e) {
            System.err.println("[MCP] Warning: Could not restore settings: " + e.getMessage());
        }
    }

    /**
     * Pre-configure Claude Code hooks in settings.local.json BEFORE launching the agent.
     *
     * <p>This MUST be called before starting Claude Code, not from the MCP subprocess.
     * Claude Code monitors settings.local.json via inotify — writing to it after Claude
     * starts causes it to kill and restart MCP connections, creating a death spiral.</p>
     *
     * @param workingDir the project working directory (contains .claude/)
     */
    public static void ensureHooksPreConfigured(Path workingDir) {
        try {
            Path settingsDir = workingDir.resolve(".claude");
            Path settingsFile = settingsDir.resolve("settings.local.json");

            ObjectNode settings;
            if (Files.exists(settingsFile)) {
                String existing = Files.readString(settingsFile);
                com.fasterxml.jackson.databind.JsonNode parsed = OM.readTree(existing);
                if (parsed.isObject()) {
                    settings = (ObjectNode) parsed;
                } else {
                    settings = OM.createObjectNode();
                }
                // Remove any existing kompile hooks so we always write the latest version
                com.fasterxml.jackson.databind.JsonNode hooks = settings.get("hooks");
                if (hooks != null && hooks.isObject()) {
                    removeKompileMatchersFromArray((ObjectNode) hooks, "PreToolUse");
                    removeKompileMatchersFromArray((ObjectNode) hooks, "PostToolUse");
                }
            } else {
                settings = OM.createObjectNode();
            }

            // Per-project temp file for timing
            String wdHash = Integer.toHexString(workingDir.toAbsolutePath().toString().hashCode() & 0x7fffffff);
            String tsFile = "/tmp/.kompile_hook_ts_" + wdHash;

            String preCmd = "bash -c '"
                    + "INPUT=$(cat); "
                    + "TN=$(echo \"$INPUT\" | jq -r \".tool_name // \\\"unknown\\\"\"); "
                    + "SHORT=${TN#mcp__kompile__}; "
                    + "PARAMS=$(echo \"$INPUT\" | jq -rc \".tool_input // {}\" | head -c 120); "
                    + "echo \"$(($(date +%s%N)/1000000))\" > " + tsFile + "; "
                    + "echo >&2 \"[kompile] $SHORT | $PARAMS\""
                    + "'";

            String postCmd = "bash -c '"
                    + "INPUT=$(cat); "
                    + "TN=$(echo \"$INPUT\" | jq -r \".tool_name // \\\"unknown\\\"\"); "
                    + "SHORT=${TN#mcp__kompile__}; "
                    + "END=$(($(date +%s%N)/1000000)); "
                    + "START=$(cat " + tsFile + " 2>/dev/null || echo $END); "
                    + "MS=$((END - START)); "
                    + "if [ $MS -gt 1000 ]; then FMT=\"$((MS/1000)).$((MS%1000/100))s\"; else FMT=\"${MS}ms\"; fi; "
                    + "echo >&2 \"[kompile] $SHORT done ($FMT)\""
                    + "'";

            ObjectNode hooks = settings.has("hooks") && settings.get("hooks").isObject()
                    ? (ObjectNode) settings.get("hooks")
                    : settings.putObject("hooks");

            com.fasterxml.jackson.databind.node.ArrayNode preArray = hooks.has("PreToolUse") && hooks.get("PreToolUse").isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) hooks.get("PreToolUse")
                    : hooks.putArray("PreToolUse");
            ObjectNode preMatcher = OM.createObjectNode();
            preMatcher.put("matcher", ".*");
            preMatcher.putArray("hooks").addObject().put("type", "command").put("command", preCmd);
            preArray.add(preMatcher);

            com.fasterxml.jackson.databind.node.ArrayNode postArray = hooks.has("PostToolUse") && hooks.get("PostToolUse").isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) hooks.get("PostToolUse")
                    : hooks.putArray("PostToolUse");
            ObjectNode postMatcher = OM.createObjectNode();
            postMatcher.put("matcher", ".*");
            postMatcher.putArray("hooks").addObject().put("type", "command").put("command", postCmd);
            postArray.add(postMatcher);

            // ── Enforcer PreToolUse hook: blocks ALL tool calls matching keyword bans ──
            // This is the only way to truly gate Claude Code's native tools (Bash, Write, etc.)
            // before execution. The hook reads the enforcer policy file from env and runs
            // keyword checks against tool_name + tool_input. Non-zero exit = block.
            String enforcerPolicyEnv = System.getenv("KOMPILE_ENFORCER_POLICY_FILE");
            if (enforcerPolicyEnv != null && !enforcerPolicyEnv.isBlank()) {
                String enforcerHookCmd = "bash -c '"
                        + "INPUT=$(cat); "
                        + "POLICY_FILE=\"" + enforcerPolicyEnv + "\"; "
                        + "if [ ! -f \"$POLICY_FILE\" ]; then exit 0; fi; "
                        + "TN=$(echo \"$INPUT\" | jq -r \".tool_name // \\\"\\\"\" 2>/dev/null); "
                        + "TI=$(echo \"$INPUT\" | jq -rc \".tool_input // {}\" 2>/dev/null); "
                        + "RULES=$(jq -r \".rules // \\\"\\\"\" \"$POLICY_FILE\" 2>/dev/null); "
                        + "if [ -z \"$RULES\" ]; then exit 0; fi; "
                        // Check BAN_TOOL: rules against tool name
                        + "echo \"$RULES\" | grep -i \"^BAN_TOOL:\" | while IFS=: read -r _ BANNED; do "
                        + "  BANNED=$(echo \"$BANNED\" | xargs); "
                        + "  if echo \"$TN\" | grep -qi \"$BANNED\"; then "
                        + "    echo \"BLOCKED: tool $TN is banned by enforcer rule\" >&2; exit 1; "
                        + "  fi; "
                        + "done || exit 1; "
                        // Check BAN_CMD: rules against tool args
                        + "echo \"$RULES\" | grep -i \"^BAN_CMD:\" | while IFS=: read -r _ BANNED; do "
                        + "  BANNED=$(echo \"$BANNED\" | xargs); "
                        + "  if echo \"$TI\" | grep -qi \"$BANNED\"; then "
                        + "    echo \"BLOCKED: command containing \\\"$BANNED\\\" banned by enforcer\" >&2; exit 1; "
                        + "  fi; "
                        + "done || exit 1; "
                        // Check STOP_TOOL: rules
                        + "echo \"$RULES\" | grep -i \"^STOP_TOOL:\" | while IFS=: read -r _ BANNED; do "
                        + "  BANNED=$(echo \"$BANNED\" | xargs); "
                        + "  if echo \"$TN\" | grep -qi \"$BANNED\"; then "
                        + "    echo \"BLOCKED: tool $TN is critically banned by enforcer\" >&2; exit 1; "
                        + "  fi; "
                        + "done || exit 1; "
                        // Check STOP_CMD: rules
                        + "echo \"$RULES\" | grep -i \"^STOP_CMD:\" | while IFS=: read -r _ BANNED; do "
                        + "  BANNED=$(echo \"$BANNED\" | xargs); "
                        + "  if echo \"$TI\" | grep -qi \"$BANNED\"; then "
                        + "    echo \"BLOCKED: command containing \\\"$BANNED\\\" critically banned\" >&2; exit 1; "
                        + "  fi; "
                        + "done || exit 1; "
                        // Check plain lines (keywords banned everywhere)
                        + "echo \"$RULES\" | grep -v \"^#\" | grep -v \"^//\" | grep -v -i \"^BAN\" | grep -v -i \"^STOP\" | grep -v -i \"^REGEX\" | while IFS= read -r KW; do "
                        + "  KW=$(echo \"$KW\" | xargs); "
                        + "  if [ -z \"$KW\" ]; then continue; fi; "
                        + "  if echo \"$TI\" | grep -qi \"$KW\"; then "
                        + "    echo \"BLOCKED: \\\"$KW\\\" found in tool args, violates enforcer rules\" >&2; exit 1; "
                        + "  fi; "
                        + "done || exit 1; "
                        + "exit 0"
                        + "'";

                ObjectNode enforcerMatcher = OM.createObjectNode();
                enforcerMatcher.put("matcher", ".*");
                enforcerMatcher.putArray("hooks").addObject().put("type", "command").put("command", enforcerHookCmd);
                preArray.add(enforcerMatcher);
            }

            Files.createDirectories(settingsDir);
            String newContent = OM.writerWithDefaultPrettyPrinter().writeValueAsString(settings);
            String existingContent = Files.exists(settingsFile) ? Files.readString(settingsFile) : "";
            if (!newContent.equals(existingContent)) {
                Files.writeString(settingsFile, newContent);
                System.err.println("[MCP] Pre-configured Claude Code hooks in " + settingsFile);
            }
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not pre-configure hooks: " + e.getMessage());
        }
    }

    /** Remove kompile-managed matcher entries from a hook event array. */
    private static void removeKompileMatchersFromArray(ObjectNode hooks, String event) {
        com.fasterxml.jackson.databind.JsonNode arr = hooks.get(event);
        if (arr == null || !arr.isArray()) return;
        com.fasterxml.jackson.databind.node.ArrayNode array = (com.fasterxml.jackson.databind.node.ArrayNode) arr;
        for (int i = array.size() - 1; i >= 0; i--) {
            com.fasterxml.jackson.databind.JsonNode entry = array.get(i);
            if (entry.isObject() && entry.has("matcher")) {
                String matcher = entry.get("matcher").asText("");
                if (matcher.contains("kompile") || hasKompileHookCommand(entry)) {
                    array.remove(i);
                }
            }
        }
    }

    private static boolean hasKompileHookCommand(com.fasterxml.jackson.databind.JsonNode entry) {
        com.fasterxml.jackson.databind.JsonNode hooks = entry.path("hooks");
        if (!hooks.isArray()) {
            return false;
        }
        for (com.fasterxml.jackson.databind.JsonNode hook : hooks) {
            String command = hook.path("command").asText("");
            if (command.contains("[kompile]") || command.contains("Kompile MCP")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the settings file path for the given agent without modifying anything.
     * Used to check if the file exists before injection begins.
     */
    private static Path resolveSettingsPath(Path workingDir, String agent) {
        if (agent.contains("claude")) return workingDir.resolve(".mcp.json");
        if (agent.contains("codex")) return Path.of(System.getProperty("user.home"), ".codex", "config.toml");
        if (agent.contains("gemini")) return Path.of(System.getProperty("user.home"), ".gemini", "settings.json");
        if (agent.contains("opencode")) {
            return isCrushFormat() ? workingDir.resolve("opencode.json") : workingDir.resolve(".opencode.json");
        }
        return workingDir.resolve(".qwen").resolve("settings.json"); // default: qwen
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

        // Append the kompile MCP server section.
        // Codex infers stdio from presence of command/args — do NOT add type = "stdio".
        // For SSE, codex uses url key (no type key needed either).
        toml.append("[mcp_servers.kompile]\n");
        if (sseUrl != null && !sseUrl.isBlank()) {
            toml.append("url = \"").append(escapeToml(sseUrl)).append("\"\n");
        } else {
            List<String> fullArgs = launcher.buildArgs(workingDir);
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

    /**
     * Detects whether the installed OpenCode binary is the newer "Crush" fork
     * (which uses {@code "mcp"} key with {@code "type"} field) or the original
     * OpenCode (which uses {@code "mcpServers"} format).
     *
     * @return true if Crush format should be used
     */
    static boolean isCrushFormat() {
        try {
            ProcessBuilder pb = new ProcessBuilder("opencode", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                output = sb.toString().trim();
            }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            // Crush reports version as "crush" or any numeric version.
            // The original OpenCode was archived at 0.0.55 and continued as Crush.
            // The Crush fork was later rebranded back to "opencode" but kept the
            // Crush config format ("mcp" key with "type" field).
            // Any version >= 1.0 is Crush format; original OpenCode never exceeded 0.0.55.
            if (output.toLowerCase().contains("crush")) return true;
            if (output.matches("\\d+.*")) {
                String[] parts = output.split("\\.");
                int major = Integer.parseInt(parts[0]);
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                // Original OpenCode maxed out at 0.0.55; anything >= 0.1 is Crush format
                return major > 0 || minor >= 1;
            }
            return false;
        } catch (Exception e) {
            return false; // default to legacy format
        }
    }

    /**
     * Injects kompile MCP tools into OpenCode's project-local config.
     * <p>
     * OpenCode 1.x reads from {@code opencode.json} (no dot prefix) in the working directory.
     * The schema uses the {@code "mcp"} key with {@code "type"} field ({@code "local"} for
     * stdio, {@code "remote"} for URL-based) and requires an {@code "enabled"} field.
     * <p>
     * The original OpenCode 0.x (archived at 0.0.55) used {@code .opencode.json} with
     * the {@code "mcpServers"} key — we still support that format for legacy installs.
     */
    private static Path injectForOpenCode(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                             String sseUrl) throws IOException {
        if (isCrushFormat()) {
            // OpenCode 1.x: opencode.json (no dot prefix)
            Path settingsFile = workingDir.resolve("opencode.json");
            Files.createDirectories(settingsFile.getParent());
            return writeCrushConfig(settingsFile, workingDir, launcher, sseUrl);
        }
        // Legacy OpenCode 0.x: .opencode.json (dot prefix)
        Path settingsFile = workingDir.resolve(".opencode.json");
        Files.createDirectories(settingsFile.getParent());
        return writeConfig(settingsFile, workingDir, launcher, sseUrl);
    }

    /**
     * Writes OpenCode 1.x config using the {@code "mcp"} key.
     * <p>
     * OpenCode 1.x schema:
     * <ul>
     *   <li>{@code "type": "local"} for stdio servers (with command/args)</li>
     *   <li>{@code "type": "remote"} for URL-based servers</li>
     *   <li>{@code "enabled": true} required on each entry</li>
     * </ul>
     */
    private static Path writeCrushConfig(Path settingsFile, Path workingDir,
                                            McpToolInjectionSupport.CliLauncher launcher,
                                            String sseUrl) throws IOException {
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

        ObjectNode mcpServers;
        if (root.has("mcp") && root.get("mcp").isObject()) {
            mcpServers = (ObjectNode) root.get("mcp");
        } else {
            mcpServers = root.putObject("mcp");
        }

        ObjectNode kompile = mcpServers.putObject("kompile");
        kompile.put("enabled", true);

        String mode;
        if (sseUrl != null && !sseUrl.isBlank()) {
            kompile.put("type", "remote");
            kompile.put("url", sseUrl);
            mode = "remote";
        } else {
            kompile.put("type", "local");
            ArrayNode cmdArray = kompile.putArray("command");
            cmdArray.add(launcher.command());
            for (String arg : launcher.buildArgs(workingDir)) {
                cmdArray.add(arg);
            }
            mode = "local";
        }

        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        System.err.println("[MCP] Injected kompile MCP tools (" + mode + ") into " + settingsFile);
        System.err.flush();

        return settingsFile;
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
     *
     * <p>If a backup already exists, it is checked for contamination (a "kompile" MCP entry).
     * If contaminated, the backup is overwritten with a clean copy from the current file
     * (with the kompile entry stripped out). If the backup is clean, it is preserved.</p>
     *
     * <p>Always creates a verbatim backup of the original file. The backup is used by
     * {@link #removeTools(Path)} to restore the file to its pre-injection state. Even if
     * the file only contains a kompile entry (e.g. from {@code kompile init}), the backup
     * preserves it so the persistent config survives the injection/cleanup cycle.</p>
     */
    private static void backupIfExists(Path settingsFile) throws IOException {
        if (!Files.exists(settingsFile)) return;

        Path backup = settingsFile.resolveSibling(settingsFile.getFileName() + BACKUP_SUFFIX);

        if (Files.exists(backup)) {
            // Check if backup is contaminated (contains kompile entry)
            if (isContaminated(backup)) {
                // Backup is contaminated — try to create a clean backup from the current file.
                // If stripping kompile leaves nothing (file only had kompile), keep the
                // verbatim backup so the persistent config can be restored.
                String cleanContent = stripKompileEntry(settingsFile);
                if (cleanContent != null) {
                    Files.writeString(backup, cleanContent);
                }
                // If cleanContent is null, keep the existing (contaminated) backup.
                // removeTools() will handle this via the fileExistedBeforeInjection flag.
            }
            // If backup exists and is clean, keep it (don't overwrite)
        } else {
            // Always create a verbatim backup — even if the file only has a kompile entry.
            // This preserves persistent configs created by "kompile init".
            Files.copy(settingsFile, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Check whether a settings file contains a kompile MCP entry.
     *
     * @param file the settings file to check
     * @return true if the file has a kompile mcpServers entry (JSON) or [mcp_servers.kompile] section (TOML)
     */
    private static boolean isContaminated(Path file) {
        if (!Files.exists(file)) return false;
        try {
            String fileName = file.getFileName().toString();
            if (fileName.endsWith(".toml")) {
                String content = Files.readString(file);
                return content.matches("(?s).*\\[mcp_servers\\.kompile\\].*");
            }
            String content = Files.readString(file);
            ObjectNode root = (ObjectNode) OM.readTree(content);
            if (root.has("mcpServers") && root.get("mcpServers").isObject()
                    && root.get("mcpServers").has("kompile")) return true;
            if (root.has("mcp") && root.get("mcp").isObject()
                    && root.get("mcp").has("kompile")) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read a settings file and return its content with the kompile entry removed.
     *
     * @param file the settings file to clean
     * @return the cleaned content string, or null if the file cannot be parsed
     */
    private static String stripKompileEntry(Path file) {
        try {
            if (!Files.exists(file)) return null;
            String fileName = file.getFileName().toString();
            if (fileName.endsWith(".toml")) {
                String content = Files.readString(file);
                String cleaned = content.replaceAll(
                    "(?ms)^\\[mcp_servers\\.kompile\\].*?(?=\\n\\[[^.]|\\z)", "").trim();
                cleaned = cleaned.replaceAll("\\n+$", "");
                return cleaned.isEmpty() ? null : cleaned + "\n";
            }
            String content = Files.readString(file);
            ObjectNode root = (ObjectNode) OM.readTree(content);
            if (root.has("mcp") && root.get("mcp").isObject()) {
                ((ObjectNode) root.get("mcp")).remove("kompile");
                if (root.get("mcp").isEmpty()) root.remove("mcp");
            }
            if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
                ((ObjectNode) root.get("mcpServers")).remove("kompile");
                if (root.get("mcpServers").isEmpty()) root.remove("mcpServers");
            }
            if (root.isEmpty()) return null;
            return OM.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not strip kompile entry from " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Clean up any leaked kompile entries from known config file locations.
     * Called at the start of {@link #injectTools} to recover from prior crashes.
     *
     * @param projectDir the project/working directory used to resolve local config files
     */
    public static void cleanupLeakedEntries(Path projectDir) {
        List<Path> candidates = List.of(
            projectDir.resolve(".mcp.json"),
            projectDir.resolve(".qwen/settings.json"),
            projectDir.resolve(".opencode.json"),
            projectDir.resolve("opencode.json"),
            Path.of(System.getProperty("user.home"), ".config", "opencode", "opencode.json"),
            Path.of(System.getProperty("user.home"), ".opencode", "opencode.json"),
            Path.of(System.getProperty("user.home"), ".opencode.json"),
            Path.of(System.getProperty("user.home"), ".codex", "config.toml"),
            Path.of(System.getProperty("user.home"), ".gemini", "settings.json")
        );

        for (Path candidate : candidates) {
            try {
                if (Files.exists(candidate) && isContaminated(candidate)) {
                    Path backup = candidate.resolveSibling(candidate.getFileName() + BACKUP_SUFFIX);
                    if (Files.exists(backup) && !isContaminated(backup)) {
                        // Restore clean backup — a prior injection crashed before cleanup
                        Files.move(backup, candidate, StandardCopyOption.REPLACE_EXISTING);
                        System.err.println("[MCP] Restored clean backup for: " + candidate);
                    } else if (Files.exists(backup)) {
                        // Backup is also contaminated — both got the kompile entry somehow.
                        // Strip kompile from the main file and discard the bad backup.
                        removeKompileEntry(candidate);
                        Files.deleteIfExists(backup);
                    }
                    // If NO backup exists, the file is a persistent config (e.g. from
                    // "kompile init") — leave it alone. Only the presence of a backup
                    // indicates a prior injection that didn't clean up properly.
                }
            } catch (IOException e) {
                System.err.println("[MCP] Warning: Could not clean leaked entry from " + candidate + ": " + e.getMessage());
            }
            // Clean up orphaned backups (backup exists but original doesn't)
            Path backup = candidate.resolveSibling(candidate.getFileName() + BACKUP_SUFFIX);
            try {
                if (Files.exists(backup) && !Files.exists(candidate)) {
                    Files.deleteIfExists(backup);
                }
            } catch (IOException e) {
                System.err.println("[MCP] Warning: Could not delete orphaned backup " + backup + ": " + e.getMessage());
            }
        }
    }

    /**
     * Register a JVM shutdown hook to clean up injected tools if the JVM is killed unexpectedly.
     * Called after successful injection in {@link #injectTools}.
     */
    private static void registerShutdownHook(Path settingsFile) {
        // Remove any prior hook first
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Hook already running or JVM shutting down
            }
        }
        pendingCleanupFile = settingsFile;
        shutdownHook = new Thread(() -> {
            System.err.println("[MCP] Shutdown hook: cleaning up injected tools");
            removeTools(pendingCleanupFile);
        }, "kompile-mcp-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down
        }
    }

    /**
     * Remove the shutdown hook registered by {@link #registerShutdownHook}.
     * Called at the start of {@link #removeTools} to avoid double-cleanup.
     */
    private static void deregisterShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Hook already running or JVM shutting down
            }
            shutdownHook = null;
            pendingCleanupFile = null;
            // Note: fileExistedBeforeInjection is intentionally NOT reset here —
            // it is read by removeTools() which calls this method first.
        }
    }

    /**
     * Remove only the "kompile" entry from mcpServers (or "mcp" for Crush)
     * in a JSON settings file, or remove the [mcp_servers.kompile] section from a TOML config file.
     * If the file was created fresh by injection and has no other content, delete it.
     */
    private static void removeKompileEntry(Path settingsFile) throws IOException {
        if (!Files.exists(settingsFile)) return;

        String fileName = settingsFile.getFileName().toString();
        if (fileName.endsWith(".toml")) {
            removeKompileTomlEntry(settingsFile);
            return;
        }

        try {
            String content = Files.readString(settingsFile);
            ObjectNode root = (ObjectNode) OM.readTree(content);
            if (root.has("mcp") && root.get("mcp").isObject()) {
                ObjectNode mcp = (ObjectNode) root.get("mcp");
                mcp.remove("kompile");
                if (mcp.isEmpty()) root.remove("mcp");
            }
            if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
                ObjectNode mcpServers = (ObjectNode) root.get("mcpServers");
                mcpServers.remove("kompile");
                if (mcpServers.isEmpty()) root.remove("mcpServers");
            }
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
