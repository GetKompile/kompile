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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles injection of kompile MCP tools into spawned agents.
 *
 * <p>Supports all passthrough agents: Qwen Code, Claude Code, Codex, Gemini CLI, OpenCode,
 * and Pi Coding Agent.</p>
 *
 * <p>Two MCP server modes are supported:
 * <ul>
 *   <li><b>SSE mode</b>: When kompile-app is running, connects to it via Server-Sent Events</li>
 *   <li><b>Stdio mode</b> (embedded): When kompile-app is OFF, uses the CLI's built-in MCP stdio server</li>
 * </ul>
 *
 * <p>An agent that takes its MCP servers at launch gets a config file of its own for each
 * launch, so no shared file changes: Claude Code and Qwen Code through {@code --mcp-config},
 * Codex through {@code -c} overrides, OpenCode 1.x through {@code OPENCODE_CONFIG_CONTENT},
 * and Gemini CLI through {@code GEMINI_CLI_SYSTEM_SETTINGS_PATH} (see
 * {@link #commandLineOverrides(Path, String, Path)} and
 * {@link #applyLaunchEnvironment(Map, Path)}). These files live in
 * {@code ~/.kompile/run/mcp-launch}, where temp-directory cleanup cannot delete them while
 * an agent still runs.</p>
 *
 * <p>The other agents read the server from a settings file that their sessions share: Pi's
 * {@code .pi/mcp.json}, Antigravity's {@code ~/.gemini/config/mcp_config.json}, legacy
 * OpenCode's {@code .opencode.json}, and Qwen Code's {@code .qwen/settings.json} when it has
 * no {@code --mcp-config}. Each injection takes a lease on the file in a registry that every
 * Kompile process shares, and {@link #removeTools(Path)} restores the file only when the
 * last live lease is released, so a session that ends leaves the server in place for the
 * others.</p>
 *
 * <p>A settings file that does not parse still gets the server, so the agent has its tools:
 * its bytes are kept beside it and put back when the session ends. A restore deletes no
 * version of a file that it cannot put back; it saves it beside the file under a
 * {@code .kompile-saved-} name. A file with comments, or anything else plain JSON does not
 * allow, is written over only while a copy of its bytes is beside it, since the settings
 * read from it do not keep them.</p>
 */
public class McpToolInjection {

    /**
     * Tolerates what hand-edited settings files hold: the comments and trailing commas of
     * JSONC, {@code #} comments, single quotes, unquoted names and raw control characters in
     * strings.
     */
    private static final ObjectMapper OM = JsonUtils.newStandardMapper()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(),
                    JsonReadFeature.ALLOW_YAML_COMMENTS.mappedFeature(),
                    JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(),
                    JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(),
                    JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(),
                    JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
    /**
     * {@link #OM} for settings files. Content after the first value fails it, since a file
     * written back from what was read would lose that content.
     */
    private static final ObjectReader SETTINGS_READER =
            OM.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /**
     * Plain JSON: no comments, trailing commas or other leniency, and no name twice in one
     * object. Settings read from it and written back keep everything it says.
     */
    private static final ObjectReader PLAIN_JSON_READER = JsonUtils.standardMapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String BACKUP_SUFFIX = ".kompile-backup";
    /** Beside a settings file that did not parse when the server was added: its bytes then. */
    private static final String UNPARSED_SUFFIX = ".kompile-unparsed";
    /** Beside a settings file: an earlier version of it, which no restore reads or deletes. */
    private static final String SAVED_SUFFIX = ".kompile-saved-";
    private static final DateTimeFormatter SAVED_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** File name prefixes of the MCP configs written for a single agent launch. */
    private static final String CLAUDE_LAUNCH_CONFIG_PREFIX = "kompile-mcp-claude-";
    private static final String QWEN_LAUNCH_CONFIG_PREFIX = "kompile-mcp-qwen-";
    private static final String OPENCODE_LAUNCH_CONFIG_PREFIX = "kompile-mcp-opencode-";
    private static final String CODEX_LAUNCH_CONFIG_PREFIX = "kompile-mcp-codex-";
    private static final String GEMINI_LAUNCH_CONFIG_PREFIX = "kompile-mcp-gemini-";
    private static final List<String> LAUNCH_CONFIG_PREFIXES = List.of(CLAUDE_LAUNCH_CONFIG_PREFIX,
            QWEN_LAUNCH_CONFIG_PREFIX, OPENCODE_LAUNCH_CONFIG_PREFIX, CODEX_LAUNCH_CONFIG_PREFIX,
            GEMINI_LAUNCH_CONFIG_PREFIX);

    /** OpenCode 1.x merges this variable's JSON over every config file it reads. */
    static final String OPENCODE_CONFIG_CONTENT_ENV = "OPENCODE_CONFIG_CONTENT";

    /**
     * The file Gemini CLI reads its system settings from, which outrank the user's and the
     * workspace's and merge their {@code mcpServers} with them.
     */
    static final String GEMINI_SYSTEM_SETTINGS_ENV = "GEMINI_CLI_SYSTEM_SETTINGS_PATH";
    /**
     * The file Gemini CLI reads its system defaults from. Unset, it is
     * {@code system-defaults.json} beside the system settings file.
     */
    static final String GEMINI_SYSTEM_DEFAULTS_ENV = "GEMINI_CLI_SYSTEM_DEFAULTS_PATH";

    static final String LEASE_REGISTRY_FILE = "mcp-settings-leases.json";
    private static final String LEASE_LOCK_FILE = "mcp-settings-leases.lock";

    /**
     * Whether each project's {@code .mcp.json} existed before {@code project start} registered
     * the server there. {@link #removeTools(Path)} keeps a file that existed (for example one
     * {@code kompile init} wrote) even when it has no backup to restore.
     */
    private static final Map<Path, Boolean> PROJECT_MCP_JSON_EXISTED = new ConcurrentHashMap<>();

    /** Guards {@link #HELD_LEASES} and this JVM's use of the lease registry's file lock. */
    private static final Object LEASE_MONITOR = new Object();

    /** This JVM's leases on shared settings files, by registry key, newest last. */
    private static final Map<Path, Deque<Lease>> HELD_LEASES = new HashMap<>();

    private static final AtomicBoolean LEASE_HOOK_REGISTERED = new AtomicBoolean();

    /** Whether the installed Qwen Code takes {@code --mcp-config}; null until probed. Tests pin it. */
    static volatile Boolean qwenMcpConfigSupport;

    /** Whether the installed OpenCode reads the 1.x config format; null until probed. Tests pin it. */
    static volatile Boolean openCodeCrushFormat;

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
     * <p>Pass the returned file to {@link #commandLineOverrides(Path, String, Path)} for the
     * agent's command line and to {@link #applyLaunchEnvironment(Map, Path)} for its
     * environment, and to {@link #removeTools(Path)} when the agent exits. When it is a file
     * of the agent's own launch ({@link #isLaunchConfig(Path)}) it must exist when the agent
     * starts: inject again for a relaunch that finds it gone.</p>
     *
     * @param agentWorkingDir the working directory where the agent will run
     * @param agentName       the agent name (claude, codex, qwen, gemini, opencode, pi)
     * @param sseUrl          the kompile-app SSE URL (e.g. http://localhost:8080/mcp/sse), or null for stdio mode
     * @return the path to the settings file that was written, or null if unsupported
     */
    public static Path injectTools(Path agentWorkingDir, String agentName, String sseUrl) throws IOException {
        return injectTools(agentWorkingDir, agentName, sseUrl, null);
    }

    /**
     * Inject kompile MCP tools for an agent whose stdio MCP server runs under
     * {@code workflowEnvironment}'s workflow identity: a delegated participant's, not
     * this process's own. {@code null} keeps this process's identity.
     */
    public static Path injectTools(Path agentWorkingDir, String agentName, String sseUrl,
                                   Map<String, String> workflowEnvironment) throws IOException {
        Path normalizedWd = agentWorkingDir.toAbsolutePath().normalize();
        String agent = agentName != null ? agentName.toLowerCase(Locale.ROOT) : "qwen";

        // Clean up any leaked kompile entries from prior crashed sessions. A Claude Code
        // launch writes no shared file, so it leaves other processes' live entries alone.
        // No agent launch writes .mcp.json, so none repairs it: project start does.
        if (!agent.contains("claude")) {
            try {
                cleanupLeakedEntries(normalizedWd, false);
            } catch (Exception e) {
                McpDiagnostics.log("[MCP] Warning: Could not clean leaked entries from prior sessions: " + e.getMessage());
            }
        }

        if (agent.contains("claude") && hasCustomServers(normalizedWd)) {
            // Claude owns portable project .mcp.json entries directly. Keep the
            // Kompile connection on stdio so user-scoped custom servers remain
            // available through the gateway without requiring the app backend.
            sseUrl = null;
        }
        if ((workflowEnvironment != null && !workflowEnvironment.isEmpty())
                || !WorkflowSessionContext.inheritableEnvironment().isEmpty()) {
            // Workflow teams are enforced by the embedded stdio server, which runs as the
            // agent's participant. kompile-app's SSE server knows no teams, so an agent
            // connected there would delegate outside the team's rules.
            sseUrl = null;
        }
        String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";

        // For stdio mode, resolve the CLI launcher. Codex also requires this
        // even when an SSE URL was detected because Codex's url transport is
        // streamable HTTP, not SSE.
        McpToolInjectionSupport.CliLauncher launcher = null;
        if ("stdio".equals(mode) || agent.contains("codex")) {
            launcher = McpToolInjectionSupport.findCliLauncher();
            if (launcher == null) {
                McpDiagnostics.log("[MCP] Warning: Could not resolve kompile CLI launcher for MCP injection");
                return null;
            }
            launcher = launcher.withWorkflowEnvironment(workflowEnvironment);
        }

        if (agent.contains("claude")) {
            return injectForClaudeLaunch(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("codex")) {
            return injectForCodexLaunch(normalizedWd, launcher);
        } else if (agent.contains("agy") || agent.contains("antigravity")) {
            return injectForAgy(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("gemini")) {
            return injectForGeminiLaunch(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("opencode")) {
            return injectForOpenCode(normalizedWd, launcher, sseUrl);
        } else if (PiMcpAdapterProvisioner.isPiAgent(agent)) {
            return injectForPi(normalizedWd, launcher, sseUrl);
        } else if (agent.contains("qwen") && qwenAcceptsMcpConfig()) {
            return injectForQwenLaunch(normalizedWd, launcher, sseUrl);
        } else {
            // Default: Qwen Code's project settings format
            return injectForQwen(normalizedWd, launcher, sseUrl);
        }
    }

    /**
     * Whether Claude Code in {@code workingDir} gets MCP servers besides Kompile's. A config
     * that cannot be read counts as yes, which keeps Kompile's server on stdio rather than
     * failing the injection.
     */
    private static boolean hasCustomServers(Path workingDir) {
        try {
            return !new McpConfigStore(workingDir).effectiveServers(true).isEmpty();
        } catch (IOException | RuntimeException e) {
            McpDiagnostics.log("[MCP] Warning: Could not read the MCP server configs for " + workingDir
                    + ", so Kompile's server runs on stdio: " + e.getMessage());
            return true;
        }
    }

    /**
     * Return provider-global launch options required by agents whose MCP
     * integration is an extension rather than a built-in config reader.
     */
    public static List<String> commandLineOverrides(Path workingDir, String agentName) throws IOException {
        if (PiMcpAdapterProvisioner.isPiAgent(agentName)) {
            return PiMcpAdapterProvisioner.launchArguments();
        }
        return List.of();
    }

    /**
     * Return the launch options for an agent given the file {@link #injectTools} returned
     * for it: {@link #launchConfigArguments(Path)} for a file of its own launch, otherwise
     * {@link #commandLineOverrides(Path, String)}.
     */
    public static List<String> commandLineOverrides(Path workingDir, String agentName, Path injectedSettingsFile)
            throws IOException {
        List<String> launchConfig = launchConfigArguments(injectedSettingsFile);
        return launchConfig.isEmpty() ? commandLineOverrides(workingDir, agentName) : launchConfig;
    }

    /**
     * The options that give an agent the config {@link #injectTools} wrote for its launch:
     * {@code --mcp-config} for Claude Code and Qwen Code, the {@code -c} overrides for Codex.
     * Empty for any other file, including an OpenCode or Gemini CLI launch's, which travels
     * in the environment ({@link #applyLaunchEnvironment(Map, Path)}).
     */
    public static List<String> launchConfigArguments(Path injectedSettingsFile) {
        String prefix = launchConfigPrefix(injectedSettingsFile);
        if (CLAUDE_LAUNCH_CONFIG_PREFIX.equals(prefix) || QWEN_LAUNCH_CONFIG_PREFIX.equals(prefix)) {
            // The = form: Claude Code's "--mcp-config <file>" would take every following
            // argument as another config file.
            return List.of("--mcp-config=" + injectedSettingsFile);
        }
        if (CODEX_LAUNCH_CONFIG_PREFIX.equals(prefix)) {
            try {
                List<String> overrides = new ArrayList<>();
                OM.readTree(Files.readString(injectedSettingsFile)).forEach(node -> overrides.add(node.asText()));
                return List.copyOf(overrides);
            } catch (IOException e) {
                McpDiagnostics.log("[MCP] Warning: Could not read Codex launch config "
                        + injectedSettingsFile + ": " + e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * Whether {@code settingsFile} is a config {@link #injectTools} wrote for a single agent
     * launch rather than a settings file the agent shares with other sessions.
     */
    public static boolean isLaunchConfig(Path settingsFile) {
        return launchConfigPrefix(settingsFile) != null;
    }

    /**
     * Give a launch the server {@link #injectTools} wrote for it when the agent takes it
     * from its environment. OpenCode 1.x: merge it into {@code environment}'s
     * {@code OPENCODE_CONFIG_CONTENT}, keeping whatever that variable (or this process's own)
     * already holds. OpenCode merges the variable over every config file it reads, so the
     * launch's server wins over a project {@code opencode.json} entry. Gemini CLI: make the
     * launch's config its system settings ({@link #applyGeminiLaunchEnvironment}). Any other
     * file leaves {@code environment} alone.
     *
     * @param environment the environment the agent will be launched with
     */
    public static void applyLaunchEnvironment(Map<String, String> environment, Path injectedSettingsFile) {
        if (environment == null) {
            return;
        }
        String prefix = launchConfigPrefix(injectedSettingsFile);
        if (GEMINI_LAUNCH_CONFIG_PREFIX.equals(prefix)) {
            applyGeminiLaunchEnvironment(environment, injectedSettingsFile);
            return;
        }
        if (!OPENCODE_LAUNCH_CONFIG_PREFIX.equals(prefix)) {
            return;
        }
        try {
            JsonNode kompile = OM.readTree(Files.readString(injectedSettingsFile)).path("mcp").path("kompile");
            if (!kompile.isObject()) {
                throw new IOException("it has no mcp.kompile entry");
            }
            String current = launchEnvironmentValue(environment, OPENCODE_CONFIG_CONTENT_ENV);
            ObjectNode content = null;
            if (current != null && !current.isBlank()) {
                try {
                    JsonNode parsed = OM.readTree(current);
                    content = parsed != null && parsed.isObject() ? (ObjectNode) parsed : null;
                } catch (IOException e) {
                    content = null;
                }
                if (content == null) {
                    McpDiagnostics.log("[MCP] Warning: Replacing " + OPENCODE_CONFIG_CONTENT_ENV
                            + ", which is not a JSON object");
                }
            }
            if (content == null) {
                content = OM.createObjectNode();
            }
            JsonNode mcp = content.get("mcp");
            ObjectNode servers = mcp != null && mcp.isObject() ? (ObjectNode) mcp : content.putObject("mcp");
            servers.set("kompile", kompile.deepCopy());
            environment.put(OPENCODE_CONFIG_CONTENT_ENV, OM.writeValueAsString(content));
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not apply OpenCode launch config "
                    + injectedSettingsFile + ": " + e.getMessage());
        }
    }

    /**
     * Make a Gemini CLI launch's config its system settings. Gemini CLI takes no MCP server
     * on its command line, and its user and workspace settings are shared by every session.
     *
     * <p>The config stands in for the system settings file the launch would have read
     * ({@code environment}'s, this process's, or the platform's), so it takes that file's
     * settings with the Kompile server added, and the system defaults stay where Gemini CLI
     * would have looked for them. A system settings file that does not parse would stop
     * Gemini CLI from starting; the launch gets the Kompile server alone instead.</p>
     */
    private static void applyGeminiLaunchEnvironment(Map<String, String> environment, Path launchConfig) {
        try {
            JsonNode kompile = OM.readTree(Files.readString(launchConfig)).path("mcpServers").path("kompile");
            if (!kompile.isObject()) {
                throw new IOException("it has no mcpServers.kompile entry");
            }
            String configured = launchEnvironmentValue(environment, GEMINI_SYSTEM_SETTINGS_ENV);
            Path systemSettings = configured != null && !configured.isBlank()
                    ? Path.of(configured) : geminiDefaultSystemSettings();
            ObjectNode settings = null;
            if (Files.exists(systemSettings)) {
                settings = settingsOrNull(systemSettings);
                if (settings == null) {
                    McpDiagnostics.log("[MCP] Warning: Gemini CLI's system settings " + systemSettings
                            + " are not a JSON object Kompile can read, so the launch's system settings"
                            + " hold Kompile's server alone");
                }
            }
            if (settings == null) {
                settings = OM.createObjectNode();
            }
            JsonNode servers = settings.get("mcpServers");
            ObjectNode mcpServers = servers != null && servers.isObject()
                    ? (ObjectNode) servers : settings.putObject("mcpServers");
            mcpServers.set("kompile", kompile.deepCopy());
            Files.writeString(launchConfig, OM.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
            environment.put(GEMINI_SYSTEM_SETTINGS_ENV, launchConfig.toString());
            String defaults = launchEnvironmentValue(environment, GEMINI_SYSTEM_DEFAULTS_ENV);
            if (defaults == null || defaults.isBlank()) {
                Path directory = systemSettings.getParent();
                environment.put(GEMINI_SYSTEM_DEFAULTS_ENV, (directory != null
                        ? directory.resolve("system-defaults.json") : Path.of("system-defaults.json")).toString());
            }
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not apply Gemini CLI launch config "
                    + launchConfig + ": " + e.getMessage());
        }
    }

    /** Where Gemini CLI reads its system settings when {@link #GEMINI_SYSTEM_SETTINGS_ENV} is unset. */
    static Path geminiDefaultSystemSettings() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            return Path.of("/Library/Application Support/GeminiCli/settings.json");
        }
        if (os.startsWith("windows")) {
            return Path.of("C:\\ProgramData\\gemini-cli\\settings.json");
        }
        return Path.of("/etc/gemini-cli/settings.json");
    }

    /** {@code name}'s value in a launch environment, or this process's when the environment has none. */
    private static String launchEnvironmentValue(Map<String, String> environment, String name) {
        return environment.containsKey(name) ? environment.get(name) : System.getenv(name);
    }

    /**
     * Register the Kompile stdio server in {@code projectDir}'s {@code .mcp.json}, which the
     * Claude Code sessions a user starts in the project read. {@code project start} owns this
     * registration; Kompile's own Claude Code launches use {@link #injectTools} instead.
     * Call {@link #removeTools(Path)} with the returned path to restore the original file.
     *
     * <p>No shutdown hook removes it: a {@code project start} that finds the app already
     * running exits at once and leaves the registration in place, and the command that owns
     * the registration removes it.</p>
     *
     * @return the {@code .mcp.json} path, or null if the CLI launcher cannot be resolved
     */
    public static Path injectProjectMcpJson(Path projectDir) throws IOException {
        Path normalizedDir = projectDir.toAbsolutePath().normalize();
        try {
            cleanupLeakedEntries(normalizedDir);
        } catch (Exception e) {
            McpDiagnostics.log("[MCP] Warning: Could not clean leaked entries from prior sessions: " + e.getMessage());
        }
        McpToolInjectionSupport.CliLauncher launcher = McpToolInjectionSupport.findCliLauncher();
        if (launcher == null) {
            McpDiagnostics.log("[MCP] Warning: Could not resolve kompile CLI launcher for MCP injection");
            return null;
        }
        Path mcpJson = normalizedDir.resolve(McpConfigStore.PROJECT_CONFIG_FILE);
        PROJECT_MCP_JSON_EXISTED.put(mcpJson, Files.exists(mcpJson));
        return injectForClaude(normalizedDir, launcher, null);
    }

    /**
     * Remove injected kompile MCP tools while preserving unrelated changes made
     * during the session. When nothing else changed, the verbatim backup is restored.
     * A file of one launch is deleted. A shared settings file is restored only when this
     * was its last live lease; while another session still holds one, it keeps the server.
     *
     * @param settingsFile the path returned by {@link #injectTools} or {@link #injectProjectMcpJson}
     */
    public static void removeTools(Path settingsFile) {
        if (settingsFile == null) return;
        if (isLaunchConfig(settingsFile)) {
            // Nothing shared to restore.
            try {
                Files.deleteIfExists(settingsFile);
            } catch (IOException e) {
                McpDiagnostics.log("[MCP] Warning: Could not delete " + settingsFile + ": " + e.getMessage());
            }
            return;
        }
        try {
            if (releaseSharedSettings(settingsFile)) {
                return;
            }
            if (!McpConfigStore.PROJECT_CONFIG_FILE.equals(String.valueOf(settingsFile.getFileName()))) {
                // A shared settings file this process holds no lease on: another session's
                // lease, if any, decides when it is restored.
                return;
            }
            Path mcpJson = settingsFile.toAbsolutePath().normalize();
            boolean preExisted = PROJECT_MCP_JSON_EXISTED.getOrDefault(mcpJson, false);
            Path backup = backupPath(mcpJson);
            if (Files.exists(backup) || Files.exists(unparsedPath(mcpJson))) {
                restoreOriginalKompileEntry(mcpJson, backup);
            } else if (preExisted) {
                // The file existed before injection (e.g. created by "kompile init")
                // but no backup was needed because injection overwrote it in place.
                // Leave the file as-is — it still has a valid kompile entry which is
                // the persistent config the user expects for future sessions.
                McpDiagnostics.log("[MCP] Preserved existing settings: " + mcpJson);
            } else {
                // No backup and file didn't exist before — we created it fresh.
                // Remove the kompile entry, and delete the file if empty.
                removeKompileEntry(mcpJson);
            }
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not restore settings: " + e.getMessage());
        }
    }

    private static Path backupPath(Path settingsFile) {
        return settingsFile.resolveSibling(settingsFile.getFileName() + BACKUP_SUFFIX);
    }

    /** Where the bytes of a settings file that did not parse wait for its restore. */
    private static Path unparsedPath(Path settingsFile) {
        return settingsFile.resolveSibling(settingsFile.getFileName() + UNPARSED_SUFFIX);
    }

    private static void restoreOriginalKompileEntry(Path settingsFile, Path backup)
            throws IOException {
        if (settingsFile.getFileName().toString().equals(McpConfigStore.PROJECT_CONFIG_FILE)) {
            Path lockPath = McpConfigStore.projectLockPath(settingsFile.getParent());
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                restoreOriginalKompileEntryUnlocked(settingsFile, backup);
            }
        } else {
            restoreOriginalKompileEntryUnlocked(settingsFile, backup);
        }
    }

    private static void restoreOriginalKompileEntryUnlocked(Path settingsFile, Path backup)
            throws IOException {
        if (!settingsFile.getFileName().toString().endsWith(".toml")) {
            restoreJsonSettings(settingsFile, backup);
            return;
        }
        if (!Files.exists(settingsFile)) {
            putBack(backup, settingsFile);
            return;
        }
        restoreTomlKompileEntry(settingsFile, backup);
        Files.deleteIfExists(backup);
        McpDiagnostics.log("[MCP] Restored original Kompile entry while preserving current settings: "
                + settingsFile);
    }

    /**
     * Put back a JSON settings file that a session added the Kompile server to. Its bytes from
     * before the session are in {@link #unparsedPath(Path)} when it did not parse then, and
     * otherwise in {@code backup}. A file that only gained the server gets those bytes back. A
     * file changed in other ways keeps the changes and gets back its original Kompile entry, or
     * loses the one added; a file that no longer parses is left as it is. A backup whose
     * settings the changes do not keep is saved beside the file, and so is the file itself
     * before either write when it is no longer plain JSON (it gained comments, say).
     */
    private static void restoreJsonSettings(Path settingsFile, Path backup) throws IOException {
        Path unparsed = unparsedPath(settingsFile);
        Path verbatim = Files.exists(unparsed) ? unparsed : backup;
        if (!Files.exists(settingsFile)) {
            putBack(verbatim, settingsFile);
            settleCopies(settingsFile, backup, unparsed, false);
            McpDiagnostics.log("[MCP] Restored the original " + settingsFile
                    + ", which was deleted during the session");
            return;
        }
        ObjectNode current = readSettings(settingsFile);
        if (current == null) {
            settleCopies(settingsFile, backup, unparsed, false);
            McpDiagnostics.log("[MCP] Warning: Left " + settingsFile + " as it is: it was changed during"
                    + " the session and does not parse, so a Kompile server entry in it stays there");
            return;
        }
        ObjectNode original = settingsOrNull(backup);
        if (original == null) {
            original = OM.createObjectNode();
        }
        boolean originalKept = restoreJsonContainerEntry(current, original, "mcpServers")
                & restoreJsonContainerEntry(current, original, "mcp");
        if (current.equals(original)) {
            saveIfNotPlainJson(settingsFile, backup, unparsed);
            putBack(verbatim, settingsFile);
            settleCopies(settingsFile, backup, unparsed, false);
            McpDiagnostics.log("[MCP] Restored the original " + settingsFile);
            return;
        }
        // The file existed before the session, so it stays even with nothing left in it.
        saveIfNotPlainJson(settingsFile, backup, unparsed);
        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(current));
        if (!originalKept) keepCopy(backup, settingsFile);
        settleCopies(settingsFile, backup, unparsed, true);
        McpDiagnostics.log("[MCP] Restored original Kompile entry while preserving current settings: "
                + settingsFile);
    }

    /**
     * Clear the copies beside {@code settingsFile} once it is restored. A copy whose bytes the
     * file now holds is deleted, and so is a plain JSON backup whose settings were merged back
     * into the file ({@code backupMerged}); a backup that parses otherwise stays, as the
     * original the next restore takes Kompile's entry from. Any other copy holds a version the
     * file lost (its comments, say), and is saved beside it under a {@link #SAVED_SUFFIX} name.
     */
    private static void settleCopies(Path settingsFile, Path backup, Path unparsed,
                                     boolean backupMerged) throws IOException {
        if (Files.exists(unparsed)) {
            if (sameBytes(unparsed, settingsFile)) Files.delete(unparsed);
            else keepCopy(unparsed, settingsFile);
        }
        if (!Files.exists(backup)) return;
        if (sameBytes(backup, settingsFile)) Files.delete(backup);
        else if (settingsOrNull(backup) == null) keepCopy(backup, settingsFile);
        else if (backupMerged) {
            if (isPlainJson(backup)) Files.delete(backup);
            else keepCopy(backup, settingsFile);
        }
    }

    /**
     * Move {@code copy} over {@code settingsFile}. A settings file that is a symbolic link (into
     * a dotfiles repository, say) stays one, and its target gets the bytes.
     */
    private static void putBack(Path copy, Path settingsFile) throws IOException {
        if (Files.isSymbolicLink(settingsFile)) {
            Files.write(settingsFile, Files.readAllBytes(copy));
            Files.delete(copy);
        } else {
            Files.move(copy, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Move {@code copy} beside {@code settingsFile} under a {@link #SAVED_SUFFIX} name, and say so. */
    private static void keepCopy(Path copy, Path settingsFile) throws IOException {
        Path saved = saveBeside(copy, settingsFile, true);
        McpDiagnostics.log("[MCP] Saved an earlier version of " + settingsFile + " as " + saved);
    }

    /**
     * Before Kompile writes over {@code settingsFile}: copy it beside itself under a
     * {@link #SAVED_SUFFIX} name when it is not plain JSON, since the settings read from it do
     * not keep its comments or the rest of what JSONC allows. Nothing is saved when one of
     * {@code holders} has its bytes already.
     */
    private static void saveIfNotPlainJson(Path settingsFile, Path... holders) throws IOException {
        if (!Files.exists(settingsFile) || isPlainJson(settingsFile)) return;
        for (Path holder : holders) {
            if (sameBytes(holder, settingsFile)) return;
        }
        Path saved = saveBeside(settingsFile, settingsFile, false);
        McpDiagnostics.log("[MCP] Saved " + settingsFile + " as " + saved + " before writing over it:"
                + " it is not plain JSON, and what is written back keeps no comments");
    }

    /** Move or copy {@code source} to a free {@link #SAVED_SUFFIX} name beside {@code settingsFile}. */
    private static Path saveBeside(Path source, Path settingsFile, boolean move) throws IOException {
        String name = settingsFile.getFileName() + SAVED_SUFFIX + SAVED_STAMP.format(Instant.now());
        for (int attempt = 0; attempt < 1000; attempt++) {
            Path saved = settingsFile.resolveSibling(attempt == 0 ? name : name + "-" + attempt);
            try {
                if (move) Files.move(source, saved);
                else Files.copy(source, saved, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            return saved;
        }
        throw new IOException("No free name beside " + settingsFile + " to save " + source + " under");
    }

    /** Whether both files exist and hold the same bytes. */
    private static boolean sameBytes(Path first, Path second) throws IOException {
        return Files.exists(first) && Files.exists(second) && Files.mismatch(first, second) == -1;
    }

    /**
     * Take the Kompile server out of the {@code name} object of {@code current}: the entry
     * {@code original} has there goes back in its place. An object the injection made, where
     * {@code original} has none or has another kind of value, goes back to that once nothing
     * else is in it.
     *
     * @return false when {@code original}'s value is lost, since servers were added to the
     *         object the injection put in its place
     */
    private static boolean restoreJsonContainerEntry(ObjectNode current, ObjectNode original, String name) {
        JsonNode originalContainer = original.get(name);
        JsonNode currentContainer = current.get(name);
        ObjectNode target = currentContainer != null && currentContainer.isObject()
                ? (ObjectNode) currentContainer : null;
        if (originalContainer != null && originalContainer.isObject()) {
            JsonNode originalEntry = originalContainer.get("kompile");
            if (originalEntry != null) {
                if (currentContainer == null) target = current.putObject(name);
                if (target != null) target.set("kompile", originalEntry.deepCopy());
            } else if (target != null) {
                target.remove("kompile");
            }
            return true;
        }
        if (target == null) return true;
        target.remove("kompile");
        if (!target.isEmpty()) return originalContainer == null;
        if (originalContainer == null) current.remove(name);
        else current.set(name, originalContainer.deepCopy());
        return true;
    }

    private static void restoreTomlKompileEntry(Path settingsFile, Path backup)
            throws IOException {
        String current = Files.readString(settingsFile);
        String original = Files.readString(backup);
        String currentWithout = removeKompileTomlSection(current);
        String originalWithout = removeKompileTomlSection(original);
        if (currentWithout.trim().equals(originalWithout.trim())) {
            putBack(backup, settingsFile);
            return;
        }
        String originalSection = extractKompileTomlSection(original);
        String restored = currentWithout.trim();
        if (!originalSection.isBlank()) {
            restored = restored.isBlank() ? originalSection.trim()
                    : restored + "\n\n" + originalSection.trim();
        }
        if (restored.isBlank()) Files.deleteIfExists(settingsFile);
        else Files.writeString(settingsFile, restored + "\n");
    }

    private static String removeKompileTomlSection(String content) {
        return content.replaceAll(
                "(?ms)^\\[mcp_servers\\.kompile\\].*?(?=\\n\\[[^.]|\\z)", "").trim();
    }

    private static String extractKompileTomlSection(String content) {
        Matcher matcher = Pattern.compile(
                "(?ms)^\\[mcp_servers\\.kompile\\].*?(?=\\n\\[[^.]|\\z)")
                .matcher(content);
        return matcher.find() ? matcher.group() : "";
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
            byte[] existing = Files.exists(settingsFile) ? Files.readAllBytes(settingsFile) : null;
            if (existing != null) {
                // Strict: this file is rewritten and never restored, so one with comments, or
                // with content after its object, is left alone rather than rewritten without it.
                JsonNode parsed;
                try {
                    parsed = JsonUtils.standardMapper().reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(existing);
                } catch (IOException e) {
                    McpDiagnostics.log("[MCP] Warning: Left " + settingsFile + " as it is, without Kompile's"
                            + " hooks, since it is not strict JSON: " + e.getMessage());
                    return;
                }
                if (parsed == null || parsed.isMissingNode()) {
                    settings = OM.createObjectNode();
                } else if (parsed.isObject()) {
                    settings = (ObjectNode) parsed;
                } else {
                    McpDiagnostics.log("[MCP] Warning: Left " + settingsFile + " as it is, without Kompile's"
                            + " hooks, since it is not a JSON object");
                    return;
                }
                // Remove any existing kompile hooks so we always write the latest version
                JsonNode hooks = settings.get("hooks");
                if (hooks != null && hooks.isObject()) {
                    removeKompileMatchersFromArray((ObjectNode) hooks, "PreToolUse");
                    removeKompileMatchersFromArray((ObjectNode) hooks, "PostToolUse");
                }
            } else {
                settings = OM.createObjectNode();
            }

            mergeAllowedProjectMcpServers(settings, workingDir.resolve(".mcp.json"));

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

            ArrayNode preArray = hooks.has("PreToolUse") && hooks.get("PreToolUse").isArray()
                    ? (ArrayNode) hooks.get("PreToolUse")
                    : hooks.putArray("PreToolUse");
            ObjectNode preMatcher = OM.createObjectNode();
            preMatcher.put("matcher", ".*");
            preMatcher.putArray("hooks").addObject().put("type", "command").put("command", preCmd);
            preArray.add(preMatcher);

            ArrayNode postArray = hooks.has("PostToolUse") && hooks.get("PostToolUse").isArray()
                    ? (ArrayNode) hooks.get("PostToolUse")
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
            byte[] newContent = OM.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            if (!Arrays.equals(newContent, existing)) {
                Files.write(settingsFile, newContent);
                McpDiagnostics.log("[MCP] Pre-configured Claude Code hooks in " + settingsFile);
            }
        } catch (Exception e) {
            McpDiagnostics.log("[MCP] Warning: Could not pre-configure hooks: " + e.getMessage());
        }
    }

    private static void mergeAllowedProjectMcpServers(ObjectNode settings, Path mcpConfigPath) {
        Set<String> serverNames = new LinkedHashSet<>();
        serverNames.add("kompile");

        if (Files.exists(mcpConfigPath)) {
            ObjectNode parsed = settingsOrNull(mcpConfigPath);
            if (parsed == null) {
                McpDiagnostics.log("[MCP] Warning: " + mcpConfigPath + " does not parse, so only"
                        + " Kompile's server was added to allowedMcpServers");
            } else {
                JsonNode mcpServers = parsed.path("mcpServers");
                if (mcpServers.isObject()) {
                    mcpServers.fieldNames().forEachRemaining(serverNames::add);
                }
            }
        }

        ArrayNode allowed = settings.has("allowedMcpServers") && settings.get("allowedMcpServers").isArray()
                ? (ArrayNode) settings.get("allowedMcpServers")
                : settings.putArray("allowedMcpServers");

        Set<String> existingNames = new LinkedHashSet<>();
        for (JsonNode entry : allowed) {
            String name = entry.path("serverName").asText("");
            if (!name.isBlank()) {
                existingNames.add(name);
            }
        }

        for (String serverName : serverNames) {
            if (serverName != null && !serverName.isBlank() && existingNames.add(serverName)) {
                allowed.addObject().put("serverName", serverName);
            }
        }
    }

    /** Remove kompile-managed matcher entries from a hook event array. */
    private static void removeKompileMatchersFromArray(ObjectNode hooks, String event) {
        JsonNode arr = hooks.get(event);
        if (arr == null || !arr.isArray()) return;
        ArrayNode array = (ArrayNode) arr;
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonNode entry = array.get(i);
            if (entry.isObject() && entry.has("matcher")) {
                String matcher = entry.get("matcher").asText("");
                if (matcher.contains("kompile") || hasKompileHookCommand(entry)) {
                    array.remove(i);
                }
            }
        }
    }

    private static boolean hasKompileHookCommand(JsonNode entry) {
        JsonNode hooks = entry.path("hooks");
        if (!hooks.isArray()) {
            return false;
        }
        for (JsonNode hook : hooks) {
            String command = hook.path("command").asText("");
            if (command.contains("[kompile]") || command.contains("Kompile MCP")) {
                return true;
            }
        }
        return false;
    }

    // ── Per-launch configs ─────────────────────────────────────────────────

    /**
     * Where the configs of single agent launches live: {@code ~/.kompile/run/mcp-launch}. Not
     * the temp directory, whose cleaners delete a file nothing has touched for days while a
     * long session can still read it when it reconnects to its servers.
     */
    static Path launchConfigDirectory() {
        return KompileHome.runtimeDirectory().toPath().resolve("mcp-launch");
    }

    /**
     * Write {@code content} to a new launch config whose name records this process's pid,
     * first deleting the configs of Kompile processes that have exited.
     */
    private static Path writeLaunchConfig(String prefix, JsonNode content) throws IOException {
        Path directory = launchConfigDirectory();
        Files.createDirectories(directory);
        sweepLaunchConfigs(directory);
        Path config = Files.createTempFile(directory, prefix + ProcessHandle.current().pid() + "-", ".json");
        config.toFile().deleteOnExit();
        Files.writeString(config, OM.writerWithDefaultPrettyPrinter().writeValueAsString(content));
        return config;
    }

    /**
     * Delete the launch configs in {@code directory} whose writer has exited: a process that
     * is killed never calls {@link #removeTools(Path)} for its launches.
     */
    static void sweepLaunchConfigs(Path directory) {
        try (DirectoryStream<Path> configs = Files.newDirectoryStream(directory, "kompile-mcp-*.json")) {
            for (Path config : configs) {
                long owner = launchConfigOwner(config);
                if (owner <= 0 || ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(config);
                } catch (IOException e) {
                    McpDiagnostics.log("[MCP] Warning: Could not delete stale launch config " + config
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not sweep launch configs in " + directory
                    + ": " + e.getMessage());
        }
    }

    /** The pid of the process that wrote a launch config, or -1 when its name records none. */
    static long launchConfigOwner(Path config) {
        String prefix = launchConfigPrefix(config);
        if (prefix == null) {
            return -1;
        }
        String rest = config.getFileName().toString().substring(prefix.length());
        int end = rest.indexOf('-');
        if (end <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String launchConfigPrefix(Path settingsFile) {
        Path name = settingsFile != null ? settingsFile.getFileName() : null;
        if (name == null) {
            return null;
        }
        for (String prefix : LAUNCH_CONFIG_PREFIXES) {
            if (name.toString().startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    // ── Claude Code ────────────────────────────────────────────────────────

    /**
     * Write the Kompile server into a config file that belongs to one Claude Code launch,
     * which receives it through {@code --mcp-config} (see
     * {@link #commandLineOverrides(Path, String, Path)}).
     *
     * <p>Every Kompile process in a project used to add this server to the shared
     * {@code .mcp.json} and restore the file when its run ended, which removed the server
     * from every other live Claude Code session in the project. A {@code --mcp-config}
     * server takes precedence over a project server of the same name, and Claude Code
     * still loads the project's other servers.</p>
     */
    private static Path injectForClaudeLaunch(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                              String sseUrl) throws IOException {
        ObjectNode root = OM.createObjectNode();
        ObjectNode kompile = root.putObject("mcpServers").putObject("kompile");
        String mode;
        if (sseUrl != null && !sseUrl.isBlank()) {
            // Claude Code drops an SSE entry that lacks "type" without reporting it.
            kompile.put("type", "sse");
            kompile.put("url", sseUrl);
            mode = "sse";
        } else {
            kompile.put("command", launcher.command());
            ArrayNode args = kompile.putArray("args");
            launcher.buildArgs(workingDir).forEach(args::add);
            McpToolInjectionSupport.putEnvironment(kompile, "env", launcher.workflowEnvironment());
            // Claude Code loads the project's .mcp.json servers itself.
            McpToolInjectionSupport.putEnvironment(kompile, "env",
                    Map.of(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV, "true"));
            mode = "stdio";
        }
        Path config = writeLaunchConfig(CLAUDE_LAUNCH_CONFIG_PREFIX, root);
        ensureHooksPreConfigured(workingDir);
        McpDiagnostics.log("[MCP] Wrote kompile MCP tools (" + mode + ") for a Claude Code launch: " + config);
        return config;
    }

    /**
     * Claude Code 2.x reads MCP servers from {@code .mcp.json} in the project root.
     * This takes priority over {@code ~/.claude/settings.json}.
     */
    private static Path injectForClaude(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                         String sseUrl) throws IOException {
        Path settingsFile = workingDir.resolve(".mcp.json");
        backupIfExists(settingsFile);
        Path written = writeConfig(settingsFile, workingDir, launcher, sseUrl);
        if (sseUrl == null || sseUrl.isBlank()) {
            markClaudeAsProjectMcpOwner(written);
        }
        ensureHooksPreConfigured(workingDir);
        return written;
    }

    private static void markClaudeAsProjectMcpOwner(Path settingsFile) throws IOException {
        ObjectNode root = (ObjectNode) OM.readTree(Files.readString(settingsFile));
        ObjectNode kompile = (ObjectNode) root.path("mcpServers").path("kompile");
        ObjectNode environment = kompile.has("env") && kompile.get("env").isObject()
                ? (ObjectNode) kompile.get("env") : kompile.putObject("env");
        environment.put(McpBundleToolLoader.HOST_LOADS_PROJECT_ENV, "true");
        Files.writeString(settingsFile,
                OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    // ── Qwen Code ──────────────────────────────────────────────────────────

    /**
     * Write the Kompile server into a config file that belongs to one Qwen Code launch,
     * which receives it through {@code --mcp-config}. Its servers take precedence over the
     * settings servers of the same name.
     */
    private static Path injectForQwenLaunch(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                            String sseUrl) throws IOException {
        ObjectNode root = OM.createObjectNode();
        String mode = putServerEntry(root.putObject("mcpServers"), workingDir, launcher, sseUrl);
        Path config = writeLaunchConfig(QWEN_LAUNCH_CONFIG_PREFIX, root);
        McpDiagnostics.log("[MCP] Wrote kompile MCP tools (" + mode + ") for a Qwen Code launch: " + config);
        return config;
    }

    /** Qwen Code's project settings, which every Qwen Code session in the project reads. */
    private static Path injectForQwen(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                       String sseUrl) throws IOException {
        return acquireSharedSettings(workingDir.resolve(".qwen").resolve("settings.json"),
                settingsFile -> writeConfig(settingsFile, workingDir, launcher, sseUrl));
    }

    /**
     * Whether the {@code qwen} on the PATH takes {@code --mcp-config}. Asked once per
     * process; when it cannot be asked, the answer is no until the next launch asks again.
     */
    static boolean qwenAcceptsMcpConfig() {
        Boolean known = qwenMcpConfigSupport;
        if (known != null) {
            return known;
        }
        String help = probeOutput("qwen", "--help");
        if (help == null) {
            return false;
        }
        boolean accepts = help.contains("--mcp-config");
        qwenMcpConfigSupport = accepts;
        return accepts;
    }

    /**
     * Run {@code command} and return what it printed, or null when it could not be run or
     * did not finish within ten seconds. The output goes to a file, so a command that leaves
     * a child holding its stdout cannot block the read.
     */
    private static String probeOutput(String... command) {
        return probeOutput(Duration.ofSeconds(10), command);
    }

    /**
     * {@link #probeOutput(String...)} with the caller's time limit. A command still running
     * when it expires is killed and reported as null.
     */
    public static String probeOutput(Duration timeout, String... command) {
        Path output = null;
        try {
            output = Files.createTempFile("kompile-agent-probe-", ".txt");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // A leftover probe file is harmless.
                }
            }
        }
    }

    // ── Codex ──────────────────────────────────────────────────────────────

    /**
     * Build invocation-local Codex config overrides for the Kompile stdio MCP server.
     *
     * <p>Managed Codex subprocesses must not rewrite {@code ~/.codex/config.toml}: separate
     * Kompile processes otherwise race through the shared backup/restore path and can remove
     * the MCP entry between the injection banner and Codex startup. Codex accepts repeatable
     * {@code -c key=value} options, so these arguments keep each delegated run isolated.</p>
     */
    public static List<String> codexCommandLineOverrides(Path workingDir) throws IOException {
        return codexCommandLineOverrides(workingDir, null);
    }

    /**
     * {@link #codexCommandLineOverrides(Path)} for a Codex agent whose stdio MCP server runs
     * under {@code workflowEnvironment}'s workflow identity (a delegated participant's).
     * {@code null} keeps this process's identity.
     */
    public static List<String> codexCommandLineOverrides(Path workingDir, Map<String, String> workflowEnvironment)
            throws IOException {
        McpToolInjectionSupport.CliLauncher launcher = McpToolInjectionSupport.findCliLauncher();
        if (launcher == null) {
            return List.of();
        }
        return codexOverrides(workingDir.toAbsolutePath().normalize(),
                launcher.withWorkflowEnvironment(workflowEnvironment));
    }

    private static List<String> codexOverrides(Path normalizedWorkingDir,
                                               McpToolInjectionSupport.CliLauncher launcher) {
        List<String> launcherArgs = launcher.buildArgs(normalizedWorkingDir);
        StringBuilder argsValue = new StringBuilder("[");
        for (int i = 0; i < launcherArgs.size(); i++) {
            if (i > 0) {
                argsValue.append(", ");
            }
            argsValue.append('"').append(escapeToml(launcherArgs.get(i))).append('"');
        }
        argsValue.append(']');

        var overrides = new ArrayList<String>();
        overrides.add("-c");
        overrides.add("mcp_servers.kompile.command=\"" + escapeToml(launcher.command()) + "\"");
        overrides.add("-c");
        overrides.add("mcp_servers.kompile.args=" + argsValue);
        // Workflow team identity overrides so the child stdio server enforces the
        // same team as the launching chat.
        for (var entry : launcher.workflowEnvironment().entrySet()) {
            overrides.add("-c");
            overrides.add("mcp_servers.kompile.env." + entry.getKey()
                    + "=\"" + escapeToml(entry.getValue()) + "\"");
        }
        return List.copyOf(overrides);
    }

    /**
     * Write {@link #codexOverrides the -c overrides} into a config file that belongs to one
     * Codex launch; {@link #launchConfigArguments(Path)} reads them back for its command line.
     *
     * <p>Always the stdio server: Codex 0.142+ treats a url entry as streamable HTTP, and
     * kompile-app serves SSE, so Codex fails to start against it. The stdio bridge still
     * reaches a running kompile-app for backend-backed tools.</p>
     */
    private static Path injectForCodexLaunch(Path workingDir, McpToolInjectionSupport.CliLauncher launcher)
            throws IOException {
        ArrayNode overrides = OM.createArrayNode();
        codexOverrides(workingDir, launcher).forEach(overrides::add);
        Path config = writeLaunchConfig(CODEX_LAUNCH_CONFIG_PREFIX, overrides);
        McpDiagnostics.log("[MCP] Wrote kompile MCP tools (stdio) for a Codex launch: " + config);
        return config;
    }

    private static String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Gemini CLI ─────────────────────────────────────────────────────────

    /**
     * Write the Kompile server into a config file that belongs to one Gemini CLI launch,
     * which {@link #applyLaunchEnvironment(Map, Path)} makes the launch's system settings.
     * Their {@code mcpServers} outrank a user or workspace server of the same name.
     */
    private static Path injectForGeminiLaunch(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                              String sseUrl) throws IOException {
        ObjectNode root = OM.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        String mode;
        if (sseUrl != null && !sseUrl.isBlank()) {
            // Gemini CLI takes a "url" without "type" for streamable HTTP.
            servers.putObject("kompile").put("url", sseUrl).put("type", "sse");
            mode = "sse";
        } else {
            mode = putServerEntry(servers, workingDir, launcher, null);
        }
        Path config = writeLaunchConfig(GEMINI_LAUNCH_CONFIG_PREFIX, root);
        McpDiagnostics.log("[MCP] Wrote kompile MCP tools (" + mode + ") for a Gemini CLI launch: " + config);
        return config;
    }

    // ── Antigravity CLI ─────────────────────────────────────────────────────

    /**
     * Antigravity reads its MCP servers from the user-wide
     * {@code ~/.gemini/config/mcp_config.json}, which every project shares.
     */
    private static Path injectForAgy(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                     String sseUrl) throws IOException {
        return acquireSharedSettings(agyMcpConfig(),
                settingsFile -> writeAgyConfig(settingsFile, workingDir, launcher, sseUrl));
    }

    static Path agyMcpConfig() {
        return Path.of(System.getProperty("user.home"), ".gemini", "config", "mcp_config.json");
    }

    /** Antigravity's entry for a server it reaches over SSE names the endpoint {@code serverUrl}. */
    private static Path writeAgyConfig(Path settingsFile, Path workingDir,
                                       McpToolInjectionSupport.CliLauncher launcher,
                                       String sseUrl) throws IOException {
        ObjectNode root = loadSettingsForInjection(settingsFile);
        ObjectNode servers = root.has("mcpServers") && root.get("mcpServers").isObject()
                ? (ObjectNode) root.get("mcpServers") : root.putObject("mcpServers");
        String mode;
        if (sseUrl != null && !sseUrl.isBlank()) {
            servers.putObject("kompile").put("serverUrl", sseUrl);
            mode = "sse";
        } else {
            mode = putServerEntry(servers, workingDir, launcher, null);
        }
        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        McpDiagnostics.log("[MCP] Injected kompile MCP tools (" + mode + ") into Antigravity config " + settingsFile);
        return settingsFile;
    }

    // ── Pi Coding Agent ────────────────────────────────────────────────────

    private static Path injectForPi(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                    String sseUrl) throws IOException {
        return acquireSharedSettings(workingDir.resolve(".pi").resolve("mcp.json"),
                settingsFile -> writePiConfig(settingsFile, workingDir, launcher, sseUrl));
    }

    private static Path writePiConfig(Path settingsFile, Path workingDir,
                                      McpToolInjectionSupport.CliLauncher launcher,
                                      String sseUrl) throws IOException {
        ObjectNode root = loadSettingsForInjection(settingsFile);

        ObjectNode servers = root.has("mcpServers") && root.get("mcpServers").isObject()
                ? (ObjectNode) root.get("mcpServers") : root.putObject("mcpServers");
        ObjectNode kompile = servers.putObject("kompile");
        kompile.put("lifecycle", "eager");
        kompile.put("directTools", true);
        kompile.put("toolPrefix", "mcp");
        if (sseUrl != null && !sseUrl.isBlank()) {
            kompile.put("url", sseUrl);
        } else {
            if (launcher == null) {
                throw new IOException("Pi MCP stdio launcher is unavailable");
            }
            kompile.put("command", launcher.command());
            ArrayNode args = kompile.putArray("args");
            for (String arg : launcher.buildArgs(workingDir)) {
                args.add(arg);
            }
        }
        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        McpDiagnostics.log("[MCP] Injected kompile MCP tools into Pi config " + settingsFile);
        return settingsFile;
    }

    // ── OpenCode ───────────────────────────────────────────────────────────

    /**
     * Detects whether the installed OpenCode binary is the newer "Crush" fork
     * (which uses {@code "mcp"} key with {@code "type"} field) or the original
     * OpenCode (which uses {@code "mcpServers"} format).
     *
     * <p>Asked once per process; when it cannot be asked, the answer is the legacy format
     * until the next launch asks again.</p>
     *
     * @return true if Crush format should be used
     */
    static boolean isCrushFormat() {
        Boolean known = openCodeCrushFormat;
        if (known != null) {
            return known;
        }
        String output = probeOutput("opencode", "--version");
        if (output == null) {
            return false; // default to legacy format
        }
        boolean crush = isCrushVersion(output);
        openCodeCrushFormat = crush;
        return crush;
    }

    private static boolean isCrushVersion(String versionOutput) {
        // Crush reports version as "crush" or any numeric version.
        // The original OpenCode was archived at 0.0.55 and continued as Crush.
        // The Crush fork was later rebranded back to "opencode" but kept the
        // Crush config format ("mcp" key with "type" field).
        if (versionOutput.toLowerCase(Locale.ROOT).contains("crush")) return true;
        Pattern version = Pattern.compile("^v?(\\d{1,9})(?:\\.(\\d{1,9}))?");
        for (String line : versionOutput.split("\\R")) {
            Matcher matcher = version.matcher(line.trim());
            if (matcher.find()) {
                int major = Integer.parseInt(matcher.group(1));
                int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
                // Original OpenCode maxed out at 0.0.55; anything >= 0.1 is Crush format
                return major > 0 || minor >= 1;
            }
        }
        return false;
    }

    /**
     * Injects kompile MCP tools for an OpenCode launch.
     * <p>
     * OpenCode 1.x gets a config file of its own launch, which
     * {@link #applyLaunchEnvironment(Map, Path)} merges into its {@code OPENCODE_CONFIG_CONTENT}.
     * That variable outranks the project's {@code opencode.json}; {@code OPENCODE_CONFIG} does
     * not. The schema uses the {@code "mcp"} key with {@code "type"} field ({@code "local"} for
     * stdio, {@code "remote"} for URL-based) and requires an {@code "enabled"} field.
     * <p>
     * The original OpenCode 0.x (archived at 0.0.55) used {@code .opencode.json} with
     * the {@code "mcpServers"} key, which its sessions in the project share — we still support
     * that format for legacy installs.
     */
    private static Path injectForOpenCode(Path workingDir, McpToolInjectionSupport.CliLauncher launcher,
                                          String sseUrl) throws IOException {
        if (isCrushFormat()) {
            ObjectNode root = OM.createObjectNode();
            String mode = putCrushEntry(root.putObject("mcp"), workingDir, launcher, sseUrl);
            Path config = writeLaunchConfig(OPENCODE_LAUNCH_CONFIG_PREFIX, root);
            McpDiagnostics.log("[MCP] Wrote kompile MCP tools (" + mode + ") for an OpenCode launch: " + config);
            return config;
        }
        return acquireSharedSettings(workingDir.resolve(".opencode.json"),
                settingsFile -> writeConfig(settingsFile, workingDir, launcher, sseUrl));
    }

    /**
     * Put the Kompile server into OpenCode 1.x's {@code "mcp"} object.
     * <p>
     * OpenCode 1.x schema:
     * <ul>
     *   <li>{@code "type": "local"} for stdio servers (with command/args)</li>
     *   <li>{@code "type": "remote"} for URL-based servers</li>
     *   <li>{@code "enabled": true} required on each entry</li>
     * </ul>
     *
     * @return the transport, for the log
     */
    private static String putCrushEntry(ObjectNode servers, Path workingDir,
                                        McpToolInjectionSupport.CliLauncher launcher, String sseUrl) {
        ObjectNode kompile = servers.putObject("kompile");
        kompile.put("enabled", true);
        if (sseUrl != null && !sseUrl.isBlank()) {
            kompile.put("type", "remote");
            kompile.put("url", sseUrl);
            return "remote";
        }
        kompile.put("type", "local");
        ArrayNode command = kompile.putArray("command");
        command.add(launcher.command());
        launcher.buildArgs(workingDir).forEach(command::add);
        // OpenCode 1.x names a local server's env block "environment".
        McpToolInjectionSupport.putEnvironment(kompile, "environment", launcher.workflowEnvironment());
        return "local";
    }

    // ── Shared config writer ───────────────────────────────────────────────

    /**
     * Reads or creates a settings.json file, then adds the kompile MCP server config.
     * Supports both SSE mode (when sseUrl is provided) and stdio mode (when launcher is provided).
     */
    private static Path writeConfig(Path settingsFile, Path workingDir,
                                     McpToolInjectionSupport.CliLauncher launcher,
                                     String sseUrl) throws IOException {
        ObjectNode root = loadSettingsForInjection(settingsFile);

        // Add or replace only the "kompile" entry under mcpServers, preserving other servers
        ObjectNode mcpServers;
        if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
            mcpServers = (ObjectNode) root.get("mcpServers");
        } else {
            mcpServers = root.putObject("mcpServers");
        }

        String mode = putServerEntry(mcpServers, workingDir, launcher, sseUrl);

        Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        McpDiagnostics.log("[MCP] Injected kompile MCP tools (" + mode + ") into " + settingsFile);

        return settingsFile;
    }

    /**
     * The settings to add the Kompile server to. A file that does not parse still gets the
     * server, since the agent has no tools otherwise: its bytes are saved beside it for the
     * restore to put back (unless its backup holds them already), and the server goes into the
     * backup's settings when those parse, or into empty ones. A file that parses but is not
     * plain JSON is copied beside itself unless its backup holds its bytes.
     */
    private static ObjectNode loadSettingsForInjection(Path settingsFile) throws IOException {
        if (!Files.exists(settingsFile)) {
            return OM.createObjectNode();
        }
        Path backup = backupPath(settingsFile);
        ObjectNode settings = readSettings(settingsFile);
        if (settings != null) {
            saveIfNotPlainJson(settingsFile, backup);
            return settings;
        }
        Path unparsed = unparsedPath(settingsFile);
        if (Files.exists(unparsed) && !sameBytes(unparsed, settingsFile)) {
            // Bytes an earlier injection saved, which the file no longer holds.
            keepCopy(unparsed, settingsFile);
        }
        if (!Files.exists(unparsed) && !sameBytes(backup, settingsFile)) {
            Files.copy(settingsFile, unparsed, StandardCopyOption.COPY_ATTRIBUTES);
        }
        ObjectNode base = settingsOrNull(backup);
        McpDiagnostics.log("[MCP] Warning: " + settingsFile + " is not a JSON object Kompile can read. Until"
                + " the session ends it holds Kompile's server"
                + (base != null ? " and the settings of " + backup : " alone")
                + "; its own content is kept in " + (Files.exists(unparsed) ? unparsed : backup)
                + " and put back then.");
        return base != null ? base : OM.createObjectNode();
    }

    /**
     * The JSON object in a settings file: an empty one when the file holds no value, or null
     * when it does not parse or holds another kind of value. The encoding is taken from the
     * bytes (UTF-8, UTF-16 or UTF-32, with or without a byte order mark). Bytes that are not
     * valid in it do not parse, since no settings written back from them could keep them.
     *
     * @throws IOException only when the file cannot be read
     */
    private static ObjectNode readSettings(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        JsonNode parsed;
        try {
            parsed = SETTINGS_READER.readTree(bytes);
        } catch (IOException | RuntimeException notJson) {
            return null;
        }
        if (parsed == null || parsed.isMissingNode()) {
            return OM.createObjectNode();
        }
        return parsed.isObject() ? (ObjectNode) parsed : null;
    }

    /** {@link #readSettings(Path)}, or null for a file that is missing or cannot be read. */
    private static ObjectNode settingsOrNull(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return readSettings(file);
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** Whether a file holds {@link #PLAIN_JSON_READER plain JSON}. One that cannot be read does not. */
    private static boolean isPlainJson(Path file) {
        try {
            PLAIN_JSON_READER.readTree(Files.readAllBytes(file));
            return true;
        } catch (IOException | RuntimeException notPlain) {
            return false;
        }
    }

    /**
     * Put the Kompile server into an {@code "mcpServers"} object: SSE mode when {@code sseUrl}
     * is provided, otherwise the embedded stdio server.
     *
     * @return the transport, for the log
     */
    private static String putServerEntry(ObjectNode servers, Path workingDir,
                                         McpToolInjectionSupport.CliLauncher launcher, String sseUrl) {
        ObjectNode kompile = servers.putObject("kompile");
        if (sseUrl != null && !sseUrl.isBlank()) {
            // SSE mode: connect to running kompile-app
            kompile.put("url", sseUrl);
            kompile.put("transport", "sse");
            return "sse";
        }
        // Stdio mode: launch embedded CLI MCP server
        kompile.put("command", launcher.command());
        ArrayNode args = kompile.putArray("args");
        launcher.buildArgs(workingDir).forEach(args::add);
        // Workflow team identity rides in the env block (same contract as
        // McpToolInjectionSupport.createStdioConfig) so the child server
        // enforces the same team as the launching chat.
        McpToolInjectionSupport.putEnvironment(kompile, "env", launcher.workflowEnvironment());
        return "stdio";
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
        restoreLeftoverUnparsed(settingsFile);
        if (!Files.exists(settingsFile)) return;

        Path backup = backupPath(settingsFile);

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
                // If cleanContent is null, keep the existing (contaminated) backup:
                // restoring it puts back the file's original Kompile entry.
            }
            // If backup exists and is clean, keep it (don't overwrite)
        } else {
            // Always create a verbatim backup — even if the file only has a kompile entry.
            // This preserves persistent configs created by "kompile init".
            Files.copy(settingsFile, backup, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /**
     * Restore a settings file that a session found unparseable and ended without restoring,
     * before another session backs it up.
     *
     * @return false when no session left it so
     */
    private static boolean restoreLeftoverUnparsed(Path settingsFile) throws IOException {
        if (!Files.exists(unparsedPath(settingsFile))) return false;
        restoreOriginalKompileEntry(settingsFile, backupPath(settingsFile));
        McpDiagnostics.log("[MCP] Restored " + settingsFile + ", which an earlier session left changed");
        return true;
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
            ObjectNode root = readSettings(file);
            if (root == null) return false;
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
            ObjectNode root = readSettings(file);
            if (root == null) return null;
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
            McpDiagnostics.log("[MCP] Warning: Could not strip kompile entry from " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Clean up any leaked kompile entries from known config file locations.
     * Called at the start of {@link #injectProjectMcpJson} to recover from prior crashes.
     *
     * @param projectDir the project/working directory used to resolve local config files
     */
    public static void cleanupLeakedEntries(Path projectDir) {
        cleanupLeakedEntries(projectDir, true);
    }

    /**
     * {@link #cleanupLeakedEntries(Path)} for an agent launch, which leaves
     * {@code projectDir}'s {@code .mcp.json} alone unless {@code includeProjectMcpJson}:
     * while {@code project start} runs, its registration there has a backup beside it just
     * as a crashed injection would, and restoring that backup would unregister it.
     *
     * <p>A shared settings file with a live lease is left alone for the same reason, and one
     * whose lease holders have all exited is restored, wherever it is.</p>
     */
    private static void cleanupLeakedEntries(Path projectDir, boolean includeProjectMcpJson) {
        Path projectMcpJson = projectDir.resolve(".mcp.json");
        List<Path> candidates = List.of(
            projectMcpJson,
            projectDir.resolve(".qwen/settings.json"),
            projectDir.resolve(".opencode.json"),
            projectDir.resolve("opencode.json"),
            projectDir.resolve(".pi").resolve("mcp.json"),
            Path.of(System.getProperty("user.home"), ".config", "opencode", "opencode.json"),
            Path.of(System.getProperty("user.home"), ".opencode", "opencode.json"),
            Path.of(System.getProperty("user.home"), ".opencode.json"),
            Path.of(System.getProperty("user.home"), ".codex", "config.toml"),
            Path.of(System.getProperty("user.home"), ".gemini", "settings.json"),
            agyMcpConfig(),
            // Where earlier versions wrote Antigravity's server.
            Path.of(System.getProperty("user.home"), ".agy", "settings.json")
        );
        try {
            updateLeases(leaseRegistryDirectory(), registry -> {
                List<String> keys = new ArrayList<>();
                registry.fieldNames().forEachRemaining(keys::add);
                for (String key : keys) {
                    liveRecord(registry, Path.of(key));
                }
                for (Path candidate : candidates) {
                    if (candidate.equals(projectMcpJson) && !includeProjectMcpJson) continue;
                    // Only live leases are left in the registry.
                    if (registry.has(leaseKey(candidate).toString())) continue;
                    cleanupLeakedEntry(candidate);
                }
                return null;
            });
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not read the MCP settings leases: " + e.getMessage());
        }
    }

    private static void cleanupLeakedEntry(Path candidate) {
        try {
            if (!restoreLeftoverUnparsed(candidate) && Files.exists(candidate) && isContaminated(candidate)) {
                Path backup = backupPath(candidate);
                if (Files.exists(backup) && !isContaminated(backup)) {
                    // Restore only Kompile's original entry so edits made after
                    // the crashed injection are not discarded.
                    restoreOriginalKompileEntry(candidate, backup);
                    McpDiagnostics.log("[MCP] Restored clean backup for: " + candidate);
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
            McpDiagnostics.log("[MCP] Warning: Could not clean leaked entry from " + candidate + ": " + e.getMessage());
        }
        // A backup whose file is gone holds the last version of it, which no restore puts back.
        Path backup = backupPath(candidate);
        try {
            if (Files.exists(backup) && !Files.exists(candidate)) {
                keepCopy(backup, candidate);
            }
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not set aside orphaned backup " + backup + ": " + e.getMessage());
        }
    }

    // ── Shared settings leases ─────────────────────────────────────────────

    /** Writes the Kompile server into a shared settings file. */
    private interface SettingsWriter {
        void write(Path settingsFile) throws IOException;
    }

    /** A change to the lease registry, made while holding its lock. */
    private interface LeaseUpdate<T> {
        T apply(ObjectNode registry) throws IOException;
    }

    /** One injection's hold on a shared settings file, recorded in {@code registryDirectory}. */
    private record Lease(String id, Path registryDirectory) {
    }

    /**
     * The directory of the lease registry that every Kompile process of this user shares.
     * Each record names a shared settings file, whether it existed before its first lease,
     * and the processes holding a lease on it.
     */
    static Path leaseRegistryDirectory() {
        return KompileHome.runtimeDirectory().toPath();
    }

    /**
     * Take a lease on the shared settings file {@code settingsFile} and write the Kompile
     * server into it. The first live lease backs the file up (or records that it did not
     * exist), and the release of the last one restores it.
     */
    private static Path acquireSharedSettings(Path settingsFile, SettingsWriter writer) throws IOException {
        Path file = settingsFile.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Path key = leaseKey(file);
        Path registryDirectory = leaseRegistryDirectory();
        updateLeases(registryDirectory, registry -> {
            ObjectNode record = liveRecord(registry, key);
            if (record == null) {
                restoreLeftoverUnparsed(file);
                boolean existed = Files.exists(file);
                record = registry.putObject(key.toString());
                record.put("existed", existed);
                record.putArray("holders");
                // A backup no live lease accounts for holds an original that was never put back
                // (its restore failed, or a Kompile without leases injected the file), so it is
                // kept rather than overwritten with the injected file. Restoring from it keeps
                // the file's other settings as they are then. One whose file is gone is saved.
                if (!existed) {
                    if (Files.exists(backupPath(file))) keepCopy(backupPath(file), file);
                } else if (!Files.exists(backupPath(file))) {
                    Files.copy(file, backupPath(file), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            ArrayNode holders = (ArrayNode) record.get("holders");
            try {
                writer.write(file);
            } catch (IOException | RuntimeException e) {
                if (holders.isEmpty()) {
                    registry.remove(key.toString());
                    try {
                        restoreSharedSettings(file, record.path("existed").asBoolean(true));
                    } catch (IOException restoreFailure) {
                        e.addSuppressed(restoreFailure);
                    }
                }
                throw e;
            }
            String id = UUID.randomUUID().toString();
            holders.addObject()
                    .put("id", id)
                    .put("pid", ProcessHandle.current().pid())
                    .put("started", startedMillis(ProcessHandle.current()));
            HELD_LEASES.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(new Lease(id, registryDirectory));
            return null;
        });
        registerLeaseHook();
        return file;
    }

    /**
     * Release this JVM's newest lease on {@code settingsFile}, restoring the file when no
     * live lease on it remains.
     *
     * @return false when this JVM holds no lease on it
     */
    private static boolean releaseSharedSettings(Path settingsFile) throws IOException {
        Path key = leaseKey(settingsFile);
        synchronized (LEASE_MONITOR) {
            Deque<Lease> leases = HELD_LEASES.get(key);
            Lease lease = leases != null ? leases.pollLast() : null;
            if (leases != null && leases.isEmpty()) {
                HELD_LEASES.remove(key);
            }
            if (lease == null) {
                return false;
            }
            if (!Files.isDirectory(lease.registryDirectory())) {
                // The registry went with its directory; recreating it would bring back a
                // deleted home. Restore as the last lease would.
                restoreSharedSettings(key, true);
                return true;
            }
            updateLeases(lease.registryDirectory(), registry -> {
                JsonNode record = registry.get(key.toString());
                if (record == null || !record.isObject()) {
                    // The registry lost the lease: restore as the last one would.
                    registry.remove(key.toString());
                    restoreSharedSettings(key, true);
                    return null;
                }
                JsonNode holders = record.get("holders");
                if (holders != null && holders.isArray()) {
                    ArrayNode holderArray = (ArrayNode) holders;
                    for (int i = holderArray.size() - 1; i >= 0; i--) {
                        if (lease.id().equals(holderArray.get(i).path("id").asText())) {
                            holderArray.remove(i);
                        }
                    }
                }
                liveRecord(registry, key);
                return null;
            });
            return true;
        }
    }

    /**
     * The registry's record for {@code key} with only its live holders, or null when it has
     * none: then the record is removed and its file restored. A file that cannot be restored
     * is left as it is, with its original still in its backup.
     */
    private static ObjectNode liveRecord(ObjectNode registry, Path key) {
        JsonNode record = registry.get(key.toString());
        if (record == null) {
            return null;
        }
        if (!record.isObject()) {
            registry.remove(key.toString());
            return null;
        }
        ArrayNode live = OM.createArrayNode();
        JsonNode holders = record.get("holders");
        if (holders != null && holders.isArray()) {
            for (JsonNode holder : holders) {
                if (isLive(holder)) {
                    live.add(holder);
                }
            }
        }
        if (!live.isEmpty()) {
            ((ObjectNode) record).set("holders", live);
            return (ObjectNode) record;
        }
        registry.remove(key.toString());
        try {
            restoreSharedSettings(key, record.path("existed").asBoolean(true));
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not restore " + key + ": " + e.getMessage());
        }
        return null;
    }

    private static boolean isLive(JsonNode holder) {
        long pid = holder.path("pid").asLong(-1);
        if (pid <= 0) {
            return false;
        }
        if (pid == ProcessHandle.current().pid()) {
            return heldLeaseIds().contains(holder.path("id").asText());
        }
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) {
            return false;
        }
        // Another start time means the pid now belongs to a different process.
        long recorded = holder.path("started").asLong(0);
        long actual = startedMillis(process.get());
        return recorded == 0 || actual == 0 || Math.abs(recorded - actual) <= 1000;
    }

    private static long startedMillis(ProcessHandle process) {
        return process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    private static Set<String> heldLeaseIds() {
        synchronized (LEASE_MONITOR) {
            Set<String> ids = new HashSet<>();
            HELD_LEASES.values().forEach(leases -> leases.forEach(lease -> ids.add(lease.id())));
            return ids;
        }
    }

    /** Put a shared settings file back as it was before its first live lease. */
    private static void restoreSharedSettings(Path settingsFile, boolean existed) throws IOException {
        Path backup = backupPath(settingsFile);
        if (Files.exists(backup) || Files.exists(unparsedPath(settingsFile))) {
            restoreOriginalKompileEntry(settingsFile, backup);
        } else if (existed) {
            McpDiagnostics.log("[MCP] Preserved existing settings: " + settingsFile);
        } else {
            removeKompileEntry(settingsFile);
        }
    }

    /** The registry's name for a settings file: its real path while its directory exists. */
    private static Path leaseKey(Path settingsFile) {
        Path absolute = settingsFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            try {
                return parent.toRealPath().resolve(absolute.getFileName());
            } catch (IOException e) {
                return absolute;
            }
        }
        return absolute;
    }

    /**
     * Apply {@code update} to the lease registry in {@code registryDirectory} while holding
     * its lock, which serializes every Kompile process of this user. The registry is written
     * back even when {@code update} fails, since it may already have restored files.
     */
    private static <T> T updateLeases(Path registryDirectory, LeaseUpdate<T> update) throws IOException {
        // FileChannel.lock refuses a second lock from the same JVM, so its threads queue here.
        synchronized (LEASE_MONITOR) {
            Files.createDirectories(registryDirectory);
            // A lock file of its own: replacing the registry would drop a lock held on it.
            try (FileChannel channel = FileChannel.open(registryDirectory.resolve(LEASE_LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Path registryFile = registryDirectory.resolve(LEASE_REGISTRY_FILE);
                ObjectNode registry = readLeaseRegistry(registryFile);
                try {
                    return update.apply(registry);
                } finally {
                    writeLeaseRegistry(registryFile, registry);
                }
            }
        }
    }

    private static ObjectNode readLeaseRegistry(Path registryFile) {
        if (!Files.exists(registryFile)) {
            return OM.createObjectNode();
        }
        try {
            JsonNode parsed = OM.readTree(Files.readString(registryFile));
            if (parsed != null && parsed.isObject()) {
                return (ObjectNode) parsed;
            }
            McpDiagnostics.log("[MCP] Warning: Ignoring an MCP settings lease registry that is not a JSON object: "
                    + registryFile);
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Ignoring an unreadable MCP settings lease registry "
                    + registryFile + ": " + e.getMessage());
        }
        return OM.createObjectNode();
    }

    private static void writeLeaseRegistry(Path registryFile, ObjectNode registry) throws IOException {
        if (registry.isEmpty()) {
            Files.deleteIfExists(registryFile);
            return;
        }
        Path temp = Files.createTempFile(registryFile.getParent(), LEASE_REGISTRY_FILE, ".tmp");
        try {
            Files.writeString(temp, OM.writerWithDefaultPrettyPrinter().writeValueAsString(registry));
            Files.move(temp, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Release this JVM's leases when it exits without {@link #removeTools(Path)}. */
    private static void registerLeaseHook() {
        if (!LEASE_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(McpToolInjection::releaseHeldLeases, "kompile-mcp-cleanup"));
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down
        }
    }

    private static void releaseHeldLeases() {
        synchronized (LEASE_MONITOR) {
            for (Path key : List.copyOf(HELD_LEASES.keySet())) {
                boolean released = true;
                while (released) {
                    try {
                        released = releaseSharedSettings(key);
                    } catch (IOException | RuntimeException e) {
                        McpDiagnostics.log("[MCP] Warning: Could not release " + key + ": " + e.getMessage());
                    }
                }
            }
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
            ObjectNode root = readSettings(settingsFile);
            if (root == null) {
                McpDiagnostics.log("[MCP] Warning: Left " + settingsFile + " as it is: it does not parse,"
                        + " so Kompile's entry could not be taken out of it");
                return;
            }
            saveIfNotPlainJson(settingsFile);
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
                McpDiagnostics.log("[MCP] Removed injected settings file: " + settingsFile);
            } else {
                Files.writeString(settingsFile, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                McpDiagnostics.log("[MCP] Removed kompile entry from: " + settingsFile);
            }
        } catch (Exception e) {
            McpDiagnostics.log("[MCP] Warning: Could not clean up kompile entry: " + e.getMessage());
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
                McpDiagnostics.log("[MCP] Removed injected TOML config file: " + tomlFile);
            } else {
                Files.writeString(tomlFile, cleaned + "\n");
                McpDiagnostics.log("[MCP] Removed kompile section from: " + tomlFile);
            }
        } catch (Exception e) {
            McpDiagnostics.log("[MCP] Warning: Could not clean up kompile TOML entry: " + e.getMessage());
        }
    }

    /**
     * Public accessor for {@link #isCrushFormat()} so callers outside this package
     * (e.g. {@code InitAgentProvisioner}) can detect the opencode config format
     * without duplicating the version-probe logic.
     *
     * <p>This is a pure additive delegation — it does not change the behaviour of
     * the package-private method.</p>
     */
    public static boolean detectCrushFormat() {
        return isCrushFormat();
    }
}
