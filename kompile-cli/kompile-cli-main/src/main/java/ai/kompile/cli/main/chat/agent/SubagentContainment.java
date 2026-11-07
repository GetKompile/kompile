package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.mcp.PiMcpAdapterProvisioner;
import ai.kompile.cli.main.mcp.PlatformHooksInstaller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps an external CLI agent that Kompile launched as a subagent from launching agents of
 * its own. The kompile MCP server inside the child already refuses delegation tools
 * ({@link SubagentDelegationGuard}); this class covers each vendor's NATIVE sub-agent tools,
 * which never pass through Kompile.
 *
 * <p>Every vendor Kompile can launch is listed with the native tools that delegate and the
 * launch-time control that removes or denies them. A vendor without a verified control is
 * refused as a child ({@link #refusal}) rather than launched with tools Kompile cannot see.
 * The launch-time controls are backed by {@link #violation}: a child whose stream shows one
 * of its native delegation tools anyway is stopped.</p>
 *
 * <ul>
 *   <li>Claude Code: {@code Task}/{@code Agent} removed with {@code --disallowedTools}.</li>
 *   <li>Codex: {@code -c features.multi_agent=false} (and {@code multi_agent_v2}); the
 *       {@code multi_agent} feature supplies {@code spawn_agent} and its companions.</li>
 *   <li>Gemini CLI: {@code experimental.enableAgents=false} in the launch's system settings,
 *       which outrank user and workspace settings.</li>
 *   <li>OpenCode: {@code permission.task=deny} in the launch's {@code OPENCODE_CONFIG_CONTENT},
 *       plus the guard plugin, which fails {@code task} in any child (a per-agent permission
 *       can override the top-level rule).</li>
 *   <li>Antigravity (agy): a {@code PreToolUse} hook denying its sub-agent tools, in a
 *       Kompile-owned workspace the launch adds with {@code --add-dir}; agy loads each
 *       workspace's {@code .agents/hooks.json}.</li>
 *   <li>Pi: no sub-agent tool in core; {@code --no-extensions} stops discovered extensions
 *       from adding one (Kompile's MCP adapter loads with an explicit {@code -e}).</li>
 *   <li>Qwen Code: {@code --exclude-tools agent}.</li>
 * </ul>
 */
public final class SubagentContainment {

    /** The vendors Kompile knows how to contain; {@link #UNKNOWN} cannot be. */
    public enum Vendor { CLAUDE, CODEX, GEMINI, AGY, OPENCODE, PI, QWEN, UNKNOWN }

    /** Claude Code's native sub-agent tools. */
    static final Set<String> CLAUDE_TOOLS = Set.of("Task", "Agent");
    /** Codex's multi_agent feature tools. */
    static final Set<String> CODEX_TOOLS = Set.of(
            "spawn_agent", "send_input", "resume_agent", "wait_agent", "close_agent");
    /** OpenCode's sub-agent tool and the permission key that governs it. */
    static final Set<String> OPENCODE_TOOLS = Set.of("task");
    /** Antigravity's sub-agent tools, as its stream-json {@code init} event lists them. */
    static final Set<String> AGY_TOOLS = Set.of(
            "invoke_subagent", "define_subagent", "manage_subagents", "browser_subagent",
            "send_message", "schedule");
    /** Qwen Code's sub-agent tool. */
    static final Set<String> QWEN_TOOLS = Set.of("agent");

    /** Codex config overrides that turn its sub-agent features off. */
    static final List<String> CODEX_ARGUMENTS = List.of(
            "-c", "features.multi_agent=false", "-c", "features.multi_agent_v2=false");

    static final String AGY_HOOK_NAME = "kompile-subagent-guard";

    private SubagentContainment() {
    }

    public static Vendor vendor(String agentName) {
        if (agentName == null || agentName.isBlank()) return Vendor.UNKNOWN;
        String name = agentName.trim().toLowerCase(Locale.ROOT);
        // agy before gemini: Antigravity is a Gemini product but a different CLI.
        if (name.contains("agy") || name.contains("antigravity")) return Vendor.AGY;
        if (name.contains("claude")) return Vendor.CLAUDE;
        if (name.contains("codex")) return Vendor.CODEX;
        if (name.contains("opencode")) return Vendor.OPENCODE;
        if (name.contains("qwen")) return Vendor.QWEN;
        if (name.contains("gemini")) return Vendor.GEMINI;
        if (PiMcpAdapterProvisioner.isPiAgent(name)) return Vendor.PI;
        return Vendor.UNKNOWN;
    }

    /** Whether a launch environment belongs to a subagent ({@code KOMPILE_SUBAGENT_DEPTH >= 1}). */
    public static boolean isChild(Map<String, String> environment) {
        return environment != null
                && SubagentDelegationGuard.depth(environment.get(SubagentDelegationGuard.DEPTH_ENV)) >= 1;
    }

    /**
     * Why {@code agentName} may not be launched as a subagent, or null when Kompile can
     * contain its native delegation tools.
     */
    public static String refusal(String agentName) {
        if (vendor(agentName) != Vendor.UNKNOWN) return null;
        return "BLOCKED: '" + agentName + "' cannot run as a Kompile subagent: Kompile has no verified "
                + "control over its native sub-agent tools, so it could launch agents outside the "
                + "harness. Delegate to claude, codex, gemini, opencode, agy, pi, or qwen instead.";
    }

    /**
     * Global options a child launch of {@code agentName} needs, placed right after the
     * executable. Claude Code's {@code --disallowedTools} is not included: it shares one flag
     * with the native-shell guard, which the caller merges.
     */
    public static List<String> launchArguments(String agentName) throws IOException {
        return switch (vendor(agentName)) {
            case CODEX -> CODEX_ARGUMENTS;
            case AGY -> List.of("--add-dir", agyGuardWorkspace().toString());
            case PI -> List.of("--no-extensions");
            case QWEN -> List.of("--exclude-tools", SubagentDelegationGuard.QWEN_EXCLUDE_TOOLS);
            default -> List.of();
        };
    }

    /**
     * Environment controls for a child launch, applied after the launch's MCP environment
     * (both edit the same OpenCode config content and Gemini system settings).
     */
    public static void applyLaunchEnvironment(String agentName, Map<String, String> environment,
                                              Path workingDir) throws IOException {
        switch (vendor(agentName)) {
            case OPENCODE -> {
                McpToolInjection.denyOpenCodeTools(environment, OPENCODE_TOOLS);
                // The plugin backs the permission rule, which a per-agent permission can override.
                if (workingDir != null) PlatformHooksInstaller.ensureOpenCodeGuardPlugin(workingDir);
            }
            case GEMINI -> McpToolInjection.disableGeminiSubagents(environment);
            default -> { }
        }
    }

    /** The native tools of {@code agentName} that launch or steer another agent. */
    public static Set<String> nativeDelegationTools(String agentName) {
        return switch (vendor(agentName)) {
            case CLAUDE -> CLAUDE_TOOLS;
            case CODEX -> CODEX_TOOLS;
            case OPENCODE -> OPENCODE_TOOLS;
            case AGY -> AGY_TOOLS;
            case QWEN -> QWEN_TOOLS;
            default -> Set.of();
        };
    }

    /**
     * The stop notice for a child whose stream shows {@code toolName}, one of its vendor's
     * native delegation tools; null for any other tool.
     */
    public static String violation(String agentName, String toolName) {
        if (toolName == null || !nativeDelegationTools(agentName).contains(toolName)) return null;
        return "subagent called its native sub-agent tool '" + toolName + "', which launches agents "
                + "outside Kompile's harness; Kompile stopped it. Subagents must do the work themselves";
    }

    /**
     * The stop notice for a raw stream line that reports a delegated agent, for vendors whose
     * stream marks one beyond a tool call: agy's stream-json attaches a {@code subagent_info}
     * payload to delegated subagents. The key appears unescaped only as a JSON field, never
     * inside streamed text. Null for any other line.
     */
    public static String violationInLine(String agentName, String line) {
        if (line == null || vendor(agentName) != Vendor.AGY || !line.contains("\"subagent_info\"")) return null;
        return "subagent delegated to a native agy subagent, which runs outside Kompile's harness; "
                + "Kompile stopped it. Subagents must do the work themselves";
    }

    /**
     * Kompile's Antigravity guard workspace: {@code .agents/hooks.json} with a PreToolUse hook
     * that denies agy's sub-agent tools. Only child launches add it, so the hook denies
     * unconditionally and never touches a hooks file a standalone agy session reads.
     */
    static Path agyGuardWorkspace() throws IOException {
        Path workspace = KompileHome.homeDirectory().toPath().resolve("guards").resolve("agy-subagent");
        Path agents = workspace.resolve(".agents");
        Files.createDirectories(agents);
        // The hook runs in the directory holding hooks.json; it prints the decision file.
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        writeIfChanged(agents.resolve("deny.json"), "{\"decision\":\"deny\",\"reason\":\""
                + "BLOCKED: this agent is a Kompile subagent; subagents must not launch other agents. "
                + "Do the work directly with your own tools and report the result.\"}\n");
        writeIfChanged(agents.resolve("hooks.json"), "{\n"
                + "  \"" + AGY_HOOK_NAME + "\": {\n"
                + "    \"PreToolUse\": [\n"
                + "      {\n"
                + "        \"matcher\": \"^(" + String.join("|", AGY_TOOLS.stream().sorted().toList()) + ")$\",\n"
                + "        \"hooks\": [\n"
                + "          { \"type\": \"command\", \"command\": \""
                + (windows ? "type deny.json" : "cat deny.json") + "\", \"timeout\": 10 }\n"
                + "        ]\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}\n");
        return workspace;
    }

    private static void writeIfChanged(Path file, String content) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) return;
        Path staging = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        Files.writeString(staging, content, StandardCharsets.UTF_8);
        try {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
