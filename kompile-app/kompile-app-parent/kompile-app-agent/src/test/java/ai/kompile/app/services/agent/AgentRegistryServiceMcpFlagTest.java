package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An agent's MCP flags are the ones its own help names, whole. */
class AgentRegistryServiceMcpFlagTest {

    /** Gemini CLI's --help, trimmed to the lines that name MCP and a few around them. */
    private static final String GEMINI_HELP = """
            Usage: gemini [options] [command]

            Gemini CLI - Launch an interactive CLI, use -p/--prompt for non-interactive mode

            Commands:
              gemini [query..]             Launch Gemini CLI  [default]
              gemini mcp                   Manage MCP servers
              gemini extensions <command>  Manage Gemini CLI extensions.

            Options:
              -m, --model                     Model  [string]
              -p, --prompt                    Prompt. Appended to input on stdin (if any).  [string]
              -y, --yolo                      Automatically accept all actions  [boolean] [default: false]
                  --allowed-mcp-server-names  Allowed MCP server names  [array]
                  --allowed-tools             Tools that are allowed to run without confirmation  [array]
              -h, --help                      Show help  [boolean]
            """;

    /** Claude Code's --help, trimmed the same way. */
    private static final String CLAUDE_HELP = """
            Usage: claude [options] [command] [prompt]

            Options:
              --allowedTools, --allowed-tools <tools...>  Comma or space-separated list of tool names to allow
              --mcp-config <configs...>                   Load MCP servers from JSON files or strings
              --strict-mcp-config                         Only use MCP servers from --mcp-config
              -p, --print                                 Print response and exit

            Commands:
              mcp                                         Configure and manage MCP servers
            """;

    @Test
    void anOptionCountsOnlyWhenTheHelpNamesItWhole() {
        assertTrue(AgentRegistryService.advertisesOption(CLAUDE_HELP, "--mcp-config"));
        assertTrue(AgentRegistryService.advertisesOption("  --mcp-config=<file>", "--mcp-config"));

        assertFalse(AgentRegistryService.advertisesOption(GEMINI_HELP, "--mcp-server"));
        assertFalse(AgentRegistryService.advertisesOption(GEMINI_HELP, "-mcp-server"));
        assertFalse(AgentRegistryService.advertisesOption("  --mcp-configs", "--mcp-config"));
        assertFalse(AgentRegistryService.advertisesOption("  x--mcp-config", "--mcp-config"));
        assertFalse(AgentRegistryService.advertisesOption(null, "--mcp-config"));
        assertFalse(AgentRegistryService.advertisesOption(CLAUDE_HELP, null));
        assertFalse(AgentRegistryService.advertisesOption(CLAUDE_HELP, " "));
    }

    @Test
    void geminiCliGetsNoMcpServerFlag() {
        AgentProvider gemini = AgentProvider.builder().name("gemini-cli").command("gemini").build();

        new AgentRegistryService().parseMcpFlags(gemini, GEMINI_HELP);

        assertTrue(gemini.isMcpSupported());
        assertNull(gemini.getMcpServerFlag(), "Gemini CLI exits on --mcp-server");
        assertNull(gemini.getMcpConfigFlag());
    }

    @Test
    void claudeCodeKeepsItsConfigFlag() {
        AgentProvider claude = AgentProvider.builder()
                .name("claude-cli")
                .command("claude")
                .mcpConfigFlag("--mcp-config")
                .build();

        new AgentRegistryService().parseMcpFlags(claude, CLAUDE_HELP);

        assertTrue(claude.isMcpSupported());
        assertEquals("--mcp-config", claude.getMcpConfigFlag());
        assertNull(claude.getMcpServerFlag());
        assertEquals("--allowedTools", claude.getMcpAllowToolsFlag());
    }

    @Test
    void presetFlagsTheHelpDoesNotNameAreCleared() {
        AgentProvider agent = AgentProvider.builder()
                .name("stale-cli")
                .command("stale")
                .mcpServerFlag("--mcp-server")
                .mcpConfigFlag("--mcp-config")
                .build();

        new AgentRegistryService().parseMcpFlags(agent, "Commands:\n  mcp  Manage MCP servers\n");

        assertTrue(agent.isMcpSupported());
        assertNull(agent.getMcpServerFlag());
        assertNull(agent.getMcpConfigFlag());
    }

    @Test
    void anAdvertisedServerFlagIsUsed() {
        AgentProvider agent = AgentProvider.builder().name("other-cli").command("other").build();

        new AgentRegistryService().parseMcpFlags(agent, "  --mcp-server <name:url>  Add an MCP server\n");

        assertTrue(agent.isMcpSupported());
        assertEquals("--mcp-server", agent.getMcpServerFlag());
    }

    @Test
    void helpThatNeverMentionsMcpMeansNoMcpSupport() {
        AgentProvider agent = AgentProvider.builder().name("pi-cli").command("pi").mcpSupported(true).build();

        new AgentRegistryService().parseMcpFlags(agent, "Usage: pi [options]\n  --model <id>\n");

        assertFalse(agent.isMcpSupported());
    }
}
