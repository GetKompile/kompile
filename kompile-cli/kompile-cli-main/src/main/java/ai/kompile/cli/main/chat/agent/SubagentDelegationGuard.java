package ai.kompile.cli.main.chat.agent;

import java.util.List;
import java.util.Set;

/**
 * Harness-level block on subagents launching subagents. Children are started with
 * {@code KOMPILE_SUBAGENT_DEPTH >= 1}; any kompile MCP server running under such an
 * environment refuses delegation tools, in-process subagent children are started with a
 * tool context marked as a supervised child that refuses them, and Claude Code children
 * additionally get its own native sub-agent tools disallowed on the command line.
 *
 * <p>Depth comes from the process environment or, when absent (the kompile daemon serves
 * many clients in one JVM), the MCP client's connection header
 * ({@code KOMPILE_SUBAGENT_DEPTH} key, value the spawning runner put in the child's
 * environment). The environment is the authority when present, so a session whose own
 * env says depth 0 is never blocked by its daemon's inherited depth.</p>
 */
public final class SubagentDelegationGuard {

    public static final String DEPTH_ENV = "KOMPILE_SUBAGENT_DEPTH";

    /** Kompile MCP tools that launch another agent. */
    public static final Set<String> DELEGATION_TOOLS = Set.of(
            "task", "multi_task", "quorum_task", "agent", "agents", "subagent_resume");

    /**
     * Kompile MCP tools that launch or steer another agent without carrying the
     * obvious "task" name: {@code a2a} messages sibling sessions (including
     * {@code send_local} to another agent this CLI spawned), and {@code enforcer}
     * launches a judged CLI agent. Steered or launched work is still delegation.
     */
    public static final Set<String> EXTENDED_DELEGATION_TOOLS = Set.of("a2a", "enforcer");

    /** Claude Code tools that spawn sub-agents, plus the kompile MCP tools as Claude names them. */
    public static final List<String> CLAUDE_DISALLOWED = List.of(
            "Task", "Agent",
            "mcp__kompile__task", "mcp__kompile__multi_task", "mcp__kompile__quorum_task",
            "mcp__kompile__agent", "mcp__kompile__agents", "mcp__kompile__subagent_resume",
            "mcp__kompile__a2a", "mcp__kompile__enforcer");

    /**
     * Claude Code's native shell tools. When a launch carries Kompile's MCP
     * server, both are removed from the model's context with bare-name
     * {@code --disallowedTools} entries: Claude Code never sees them, so its
     * process work routes through the kompile {@code process} tool instead —
     * panel rows, durable output capture, wake-on-exit. A scoped rule (or a
     * prompt instruction) would leave the tools visible for the model to pick
     * again; only removal changes the routing.
     */
    public static final List<String> CLAUDE_NATIVE_SHELL_TOOLS = List.of("Bash", "PowerShell");

    /**
     * Set {@code -Dkompile.chat.claude.allowNativeShell=true} to keep Claude
     * Code's native shells available to a harness launch that carries Kompile's
     * MCP server.
     */
    public static final String ALLOW_NATIVE_SHELL_PROPERTY = "kompile.chat.claude.allowNativeShell";

    public static boolean allowNativeShell() {
        return Boolean.parseBoolean(System.getProperty(ALLOW_NATIVE_SHELL_PROPERTY));
    }

    /**
     * The native shell tools to disallow on a claude launch. Non-empty only
     * when the launch carries Kompile's MCP server ({@code mcp__kompile__process}
     * exists to route through) and the escape hatch is unset: without the
     * server there is nothing to route process work through, so the native
     * shells stay.
     */
    public static List<String> nativeShellGuard(boolean kompileServerInjected) {
        if (!kompileServerInjected || allowNativeShell()) {
            return List.of();
        }
        return CLAUDE_NATIVE_SHELL_TOOLS;
    }

    /**
     * Claude Code tools that hand work to another agent indirectly: {@code a2a}
     * reaches sibling sessions over the kompile MCP server, {@code enforcer}
     * launches a judged CLI agent. Not Claude-native names, so they are not in
     * {@link #CLAUDE_DISALLOWED}; they are kompile MCP tool ids.
     */
    public static final Set<String> EXTENDED_DELEGATION_TOOL_NAMES =
            EXTENDED_DELEGATION_TOOLS;

    /** Qwen CLI tools that spawn sub-agents, excluded with {@code --exclude-tools}. */
    public static final String QWEN_EXCLUDE_TOOLS = "agent";

    private SubagentDelegationGuard() {
    }

    public static boolean isSubagent() {
        return depth(System.getenv(DEPTH_ENV)) >= 1;
    }

    /**
     * Whether a daemon connection belongs to a subagent. Its handshake depth is
     * authoritative: the shared daemon's environment belongs to whichever client
     * started it, not to this connection. Stdio uses {@link #isSubagent()} instead.
     */
    public static boolean isSubagent(String headerDepth) {
        return effectiveDepth(headerDepth) >= 1;
    }

    public static int depth(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True when this process is a subagent and {@code toolName} would launch another agent. */
    public static boolean blocks(String toolName) {
        return blocks(toolName, System.getenv(DEPTH_ENV));
    }

    /**
     * Resolve the effective depth for a process whose daemon header declared
     * {@code headerDepth} (or none). See {@link #isSubagent(String)}.
     */
    public static int effectiveDepth(String headerDepth) {
        return depth(headerDepth);
    }

    /**
     * True when a process at the effective depth of {@code headerDepth} calling
     * {@code toolName} would launch another agent. Extended delegation tools
     * ({@code a2a}, {@code enforcer}) are included.
     */
    public static boolean blocksWithDepth(String toolName, String headerDepth) {
        return toolName != null && depthOrExtended(toolName) && effectiveDepth(headerDepth) >= 1;
    }

    static boolean blocks(String toolName, String depthValue) {
        return toolName != null && DELEGATION_TOOLS.contains(toolName) && depth(depthValue) >= 1;
    }

    private static boolean depthOrExtended(String toolName) {
        return DELEGATION_TOOLS.contains(toolName) || EXTENDED_DELEGATION_TOOLS.contains(toolName);
    }

    public static String message(String toolName) {
        return "BLOCKED: '" + toolName + "' is unavailable inside a subagent. Subagents must not launch "
                + "other agents; do the work directly with your own tools and report the result.";
    }
}
