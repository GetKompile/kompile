package ai.kompile.app.services.agent;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.DiscoveredTool;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.ProcessStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static ai.kompile.app.services.agent.AgentSubprocessExecutor.GEMINI_SYSTEM_DEFAULTS_ENV;
import static ai.kompile.app.services.agent.AgentSubprocessExecutor.GEMINI_SYSTEM_SETTINGS_ENV;
import static ai.kompile.app.services.agent.AgentSubprocessExecutor.OPENCODE_CONFIG_CONTENT_ENV;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every agent the app launches gets the app's MCP server the way that agent takes one, and
 * settings Kompile cannot read never cost an agent its tools.
 */
class AgentSubprocessExecutorMcpLaneTest {

    private static final String URL = "http://localhost:8080/mcp/sse";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Above every platform's largest pid, so no process has it. */
    private static final long DEAD_PID = Integer.MAX_VALUE;

    @TempDir
    Path tempDir;

    private AgentRegistryService registry;
    private AgentProcessDiagnosticService diagnostics;
    private BuiltInToolDiscoveryService tools;
    private ServerPortService ports;
    private AgentSubprocessExecutor executor;

    @BeforeEach
    void setUp() {
        registry = mock(AgentRegistryService.class);
        diagnostics = mock(AgentProcessDiagnosticService.class);
        executor = new AgentSubprocessExecutor(
                registry, diagnostics, mock(ClaudeStreamParser.class), tempDir.resolve("config"));
        tools = mock(BuiltInToolDiscoveryService.class);
        when(tools.getDiscoveredTools()).thenReturn(List.of(new DiscoveredTool()));
        ports = mock(ServerPortService.class);
        when(ports.getMcpSseUrl()).thenReturn(URL);
        executor.toolDiscoveryService = tools;
        executor.serverPortService = ports;
    }

    @Test
    void codexGetsTheServerThroughAConfigOverrideBeforeExec() {
        AgentProvider codex = agent("codex-cli", "codex").build();

        List<String> command = executor.buildCommand(
                codex, false, true, null, "question", tempDir.toString());

        int override = command.indexOf("mcp_servers.kompile-app.url=\"" + URL + "\"");
        assertTrue(override > 0, command.toString());
        assertEquals("-c", command.get(override - 1));
        assertTrue(override < command.indexOf("exec"), command.toString());

        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                codex, false, true, null, "question", tempDir.toString())) {
            assertEquals(command, prepared.command());
            assertTrue(prepared.environment().isEmpty());
            assertNull(prepared.ephemeralMcpConfig());
        }
    }

    @Test
    void geminiGetsSystemSettingsThatKeepTheOriginalAndAddTheServer() throws IOException {
        Path system = tempDir.resolve("etc/gemini-cli/settings.json");
        Files.createDirectories(system.getParent());
        String handWritten = """
                // written by hand
                {
                  'general': { 'vimMode': true, },
                  mcpServers: {
                    "other": { "command": "other-server", "args": ["--stdio"] },
                  },
                  # a YAML-style comment
                  "security": { "auth": { "selectedType": "oauth-personal" } },
                }
                """;
        Files.writeString(system, handWritten);
        AgentProvider gemini = gemini(Map.of(
                GEMINI_SYSTEM_SETTINGS_ENV, system.toString(),
                GEMINI_SYSTEM_DEFAULTS_ENV, ""));

        AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                gemini, false, true, null, "question", tempDir.toString());

        assertEquals(List.of("gemini", "-p"), prepared.command().subList(0, 2));
        assertEquals(3, prepared.command().size(), "Gemini CLI takes no MCP argument");
        Path launch = Path.of(prepared.environment().get(GEMINI_SYSTEM_SETTINGS_ENV));
        assertEquals(launch, prepared.ephemeralMcpConfig());
        assertEquals(launchDirectory(), launch.getParent());
        assertTrue(launch.getFileName().toString()
                .startsWith("gemini-" + ProcessHandle.current().pid() + "-"), launch.toString());

        // Strict JSON: Gemini CLI reads it with JSON.parse.
        JsonNode settings = JSON.readTree(Files.readString(launch));
        assertTrue(settings.path("general").path("vimMode").asBoolean());
        assertEquals("other-server", settings.path("mcpServers").path("other").path("command").asText());
        assertEquals("oauth-personal",
                settings.path("security").path("auth").path("selectedType").asText());
        JsonNode server = settings.path("mcpServers").path("kompile-app");
        assertEquals(URL, server.path("url").asText());
        assertEquals("sse", server.path("type").asText());
        assertEquals(system.getParent().resolve("system-defaults.json").toString(),
                prepared.environment().get(GEMINI_SYSTEM_DEFAULTS_ENV),
                "the system defaults stay where Gemini CLI would have looked for them");

        if (Files.getFileAttributeView(launch, PosixFileAttributeView.class) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(launch)));
            assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(launch.getParent())));
        }

        Map<String, String> environment = new HashMap<>(gemini.safeEnvironment());
        prepared.applyEnvironment(environment);
        assertEquals(launch.toString(), environment.get(GEMINI_SYSTEM_SETTINGS_ENV));

        prepared.close();
        assertFalse(Files.exists(launch));
        assertEquals(handWritten, Files.readString(system), "the system settings themselves are untouched");
    }

    @Test
    void geminiSettingsKompileCannotReadGiveSettingsWithTheServerAlone() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("etc"));
        Map<String, String> contents = Map.of(
                "broken.json", "{ not json",
                "array.json", "[1, 2]",
                "string.json", "\"text\"",
                "null.json", "null",
                "two-objects.json", "{\"general\": {}} {}",
                "empty.json", "",
                "comment-only.json", "// nothing yet\n");
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            Path system = directory.resolve(entry.getKey());
            Files.writeString(system, entry.getValue());
            assertServerAlone(system, entry.getKey());
        }
        assertServerAlone(directory.resolve("missing.json"), "missing.json");
    }

    private void assertServerAlone(Path system, String label) throws IOException {
        AgentProvider gemini = gemini(Map.of(
                GEMINI_SYSTEM_SETTINGS_ENV, system.toString(),
                GEMINI_SYSTEM_DEFAULTS_ENV, ""));
        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                gemini, false, true, null, "question", tempDir.toString())) {
            String launch = prepared.environment().get(GEMINI_SYSTEM_SETTINGS_ENV);
            assertNotNull(launch, label);
            JsonNode settings = JSON.readTree(Files.readString(Path.of(launch)));
            assertEquals(Set.of("mcpServers"), fieldNames(settings), label);
            assertEquals(Set.of("kompile-app"), fieldNames(settings.path("mcpServers")), label);
            assertEquals(URL, settings.path("mcpServers").path("kompile-app").path("url").asText(), label);
            assertEquals(system.getParent().resolve("system-defaults.json").toString(),
                    prepared.environment().get(GEMINI_SYSTEM_DEFAULTS_ENV), label);
        }
    }

    @Test
    void anExplicitGeminiSystemDefaultsPathIsLeftAlone() {
        Path defaults = tempDir.resolve("opt/defaults.json");
        AgentProvider gemini = gemini(Map.of(
                GEMINI_SYSTEM_SETTINGS_ENV, tempDir.resolve("etc/settings.json").toString(),
                GEMINI_SYSTEM_DEFAULTS_ENV, defaults.toString()));

        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareInteractiveCommand(
                gemini, false, true, null)) {
            assertEquals(Set.of(GEMINI_SYSTEM_SETTINGS_ENV), prepared.environment().keySet());
            Map<String, String> environment = new HashMap<>(gemini.safeEnvironment());
            prepared.applyEnvironment(environment);
            assertEquals(defaults.toString(), environment.get(GEMINI_SYSTEM_DEFAULTS_ENV));
        }
    }

    @Test
    void openCodeGetsTheServerMergedIntoItsConfigContent() throws IOException {
        AgentProvider opencode = agent("opencode-cli", "opencode")
                .args(List.of("run", "--format", "json"))
                .environment(Map.of(OPENCODE_CONFIG_CONTENT_ENV, """
                        { // the user's own
                          model: 'anthropic/claude-sonnet-4-5',
                          mcp: { other: { type: 'local', command: ['other-server'] }, },
                        }
                        """))
                .build();

        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareInteractiveCommand(
                opencode, false, true, null)) {
            assertEquals(List.of("opencode", "run", "--format", "json"), prepared.command());
            assertNull(prepared.ephemeralMcpConfig());
            assertEquals(Set.of(OPENCODE_CONFIG_CONTENT_ENV), prepared.environment().keySet());
            JsonNode content = JSON.readTree(prepared.environment().get(OPENCODE_CONFIG_CONTENT_ENV));
            assertEquals("anthropic/claude-sonnet-4-5", content.path("model").asText());
            assertEquals("other-server", content.path("mcp").path("other").path("command").path(0).asText());
            JsonNode server = content.path("mcp").path("kompile-app");
            assertTrue(server.path("enabled").asBoolean());
            assertEquals("remote", server.path("type").asText());
            assertEquals(URL, server.path("url").asText());
        }
        assertFalse(Files.exists(launchDirectory()), "OpenCode's server needs no file");
    }

    @Test
    void openCodeConfigContentThatIsNotAnObjectIsReplaced() throws IOException {
        for (String current : List.of("[1]", "{ not json", "\"text\"", "{\"model\": \"x\"} {}", "")) {
            AgentProvider opencode = agent("opencode-cli", "opencode")
                    .environment(Map.of(OPENCODE_CONFIG_CONTENT_ENV, current))
                    .build();
            try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareInteractiveCommand(
                    opencode, false, true, null)) {
                String merged = prepared.environment().get(OPENCODE_CONFIG_CONTENT_ENV);
                assertNotNull(merged, current);
                JsonNode content = JSON.readTree(merged);
                assertEquals(Set.of("mcp"), fieldNames(content), current);
                assertEquals(Set.of("kompile-app"), fieldNames(content.path("mcp")), current);
                assertEquals(URL, content.path("mcp").path("kompile-app").path("url").asText(), current);
            }
        }
    }

    @Test
    void bareCommandLinesCannotCarryGeminiOrOpenCodeServers() {
        List<String> gemini = executor.buildCommand(
                gemini(Map.of()), false, true, null, "question", tempDir.toString());
        assertEquals(List.of("gemini", "-p"), gemini.subList(0, 2));
        assertEquals(3, gemini.size());

        AgentProvider opencode = agent("opencode-cli", "opencode").args(List.of("run")).build();
        assertEquals(List.of("opencode", "run", "--verbose"),
                executor.buildInteractiveCommand(opencode, false, true, List.of("--verbose")));

        assertFalse(Files.exists(launchDirectory()));
    }

    @Test
    void configFlagAgentsGetOneTokenAndAFilePerPort() throws IOException {
        AgentProvider claude = agent("claude-cli", "claude").mcpConfigFlag("--mcp-config").build();
        Path config = tempDir.resolve("config");
        Path admin = config.resolve("agent-mcp-config-8080.json");
        Files.createDirectories(config);
        Files.writeString(admin, "{\"mcpServers\":{\"kompile-app\":{\"type\":\"sse\","
                + "\"url\":\"http://localhost:9999/mcp/sse\"}}}");

        // A path apart from its flag would take the positional argument after it too.
        assertEquals(List.of("claude", "--mcp-config=" + admin, "positional"),
                executor.buildInteractiveCommand(claude, false, true, List.of("positional")));
        assertEquals(URL, serverUrl(admin), "a stale file is replaced");

        FileTime written = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(admin, written);
        executor.buildInteractiveCommand(claude, false, true, null);
        assertEquals(written, Files.getLastModifiedTime(admin), "an unchanged file is not rewritten");

        when(ports.getMcpSseUrl()).thenReturn("http://localhost:8081/mcp/sse");
        Path chat = config.resolve("agent-mcp-config-8081.json");
        assertEquals(List.of("claude", "--mcp-config=" + chat),
                executor.buildInteractiveCommand(claude, false, true, null));
        assertEquals("http://localhost:8081/mcp/sse", serverUrl(chat));
        assertEquals(URL, serverUrl(admin), "each process keeps its own file");

        try (Stream<Path> files = Files.list(config)) {
            assertEquals(List.of(), files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp"))
                    .toList());
        }
    }

    @Test
    void serverFlagAgentsGetTheNameAndUrl() {
        AgentProvider agent = agent("other-cli", "other").mcpServerFlag("--mcp-server").build();

        assertEquals(List.of("other", "--mcp-server", "kompile-app:" + URL),
                executor.buildInteractiveCommand(agent, false, true));
    }

    @Test
    void agentsWithoutALaneRunWithoutMcpArguments() {
        AgentProvider pi = agent("pi-cli", "pi").build();

        assertFalse(executor.supportsMcpInjection(pi));
        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                pi, false, true, null, "question", tempDir.toString())) {
            assertEquals(List.of("pi", "-p", "question"), prepared.command());
            assertTrue(prepared.environment().isEmpty());
            assertNull(prepared.ephemeralMcpConfig());
        }
    }

    @Test
    void supportsMcpInjectionNamesEveryLane() {
        assertTrue(executor.supportsMcpInjection(agent("codex-cli", "codex").build()));
        assertTrue(executor.supportsMcpInjection(agent("gemini-cli", "gemini").build()));
        assertTrue(executor.supportsMcpInjection(agent("opencode-cli", "opencode").build()));
        assertTrue(executor.supportsMcpInjection(
                agent("claude-cli", "claude").mcpConfigFlag("--mcp-config").build()));
        assertTrue(executor.supportsMcpInjection(
                agent("other-cli", "other").mcpServerFlag("--mcp-server").build()));

        assertFalse(executor.supportsMcpInjection(agent("agy-cli", "agy").build()),
                "Antigravity keeps its MCP servers elsewhere");
        assertFalse(executor.supportsMcpInjection(agent("gemini-cli", "antigravity").build()));
        assertFalse(executor.supportsMcpInjection(
                agent("gemini-cli", "gemini").mcpSupported(false).build()));
        assertFalse(executor.supportsMcpInjection(null));
    }

    @Test
    void noInjectionWithoutDiscoveredToolsOrWhenTurnedOff() {
        AgentProvider codex = agent("codex-cli", "codex").build();
        AgentProvider gemini = gemini(Map.of(
                GEMINI_SYSTEM_SETTINGS_ENV, tempDir.resolve("etc/settings.json").toString()));

        assertFalse(executor.buildCommand(codex, false, false, null, "question", tempDir.toString())
                .contains("-c"));
        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                gemini, false, false, null, "question", tempDir.toString())) {
            assertTrue(prepared.environment().isEmpty());
        }

        when(tools.getDiscoveredTools()).thenReturn(List.of());
        assertFalse(executor.buildCommand(codex, false, true, null, "question", tempDir.toString())
                .contains("-c"));
        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                gemini, false, true, null, "question", tempDir.toString())) {
            assertTrue(prepared.environment().isEmpty());
            assertNull(prepared.ephemeralMcpConfig());
        }
        assertFalse(Files.exists(launchDirectory()));
    }

    @Test
    void aCommandThatCannotBeBuiltLeavesNoLaunchFileBehind() throws IOException {
        AgentProvider gemini = gemini(Map.of(
                GEMINI_SYSTEM_SETTINGS_ENV, tempDir.resolve("etc/settings.json").toString()));

        // Gemini CLI's prompt goes in a file under the working directory, which is missing;
        // its settings were written before the prompt failed.
        assertThrows(IllegalStateException.class, () -> executor.prepareCommand(
                gemini, false, true, null, "question", null));

        assertTrue(Files.isDirectory(launchDirectory()), "the settings were written");
        try (Stream<Path> files = Files.list(launchDirectory())) {
            assertEquals(List.of(), files.toList(), "and deleted with the failed launch");
        }
    }

    @Test
    void launchFilesOfExitedProcessesAreSwept() throws IOException {
        long self = ProcessHandle.current().pid();
        Path launches = Files.createDirectories(launchDirectory());
        Path dead = launches.resolve("gemini-" + DEAD_PID + "-1.json");
        Path live = launches.resolve("gemini-" + self + "-2.json");
        Path unnumbered = launches.resolve("gemini-legacy.json");
        Path unrelated = launches.resolve("notes.txt");
        for (Path file : List.of(dead, live, unnumbered, unrelated)) {
            Files.writeString(file, "{}");
        }

        try (AgentSubprocessExecutor.PreparedCommand prepared = executor.prepareCommand(
                gemini(Map.of(GEMINI_SYSTEM_SETTINGS_ENV, tempDir.resolve("etc/settings.json").toString())),
                false, true, null, "question", tempDir.toString())) {
            assertTrue(Files.exists(prepared.ephemeralMcpConfig()));
            assertFalse(Files.exists(dead));
            assertTrue(Files.exists(live));
            assertTrue(Files.exists(unnumbered));
            assertTrue(Files.exists(unrelated));
        }

        Path scopes = Files.createDirectories(
                tempDir.resolve("config").resolve(AgentSubprocessExecutor.SCOPES_DIRECTORY));
        Path earlierScope = scopes.resolve("scope-123.json");
        Path deadScope = scopes.resolve("scope-" + DEAD_PID + "-3.json");
        Files.writeString(earlierScope, "{}");
        Files.writeString(deadScope, "{}");
        AgentProvider claude = agent("claude-cli", "claude")
                .mcpConfigFlag("--mcp-config")
                .helpOutput("--mcp-config --strict-mcp-config")
                .build();
        try (AgentSubprocessExecutor.PreparedCommand scoped = executor.buildScopedCommand(
                claude, false, List.of(), "question", tempDir.toString(),
                "http://localhost:8081/mcp/scoped/" + "D".repeat(43) + "/sse")) {
            Path file = scoped.ephemeralMcpConfig();
            assertEquals(scopes, file.getParent());
            assertTrue(file.getFileName().toString().startsWith("scope-" + self + "-"), file.toString());
            assertFalse(Files.exists(deadScope));
            assertTrue(Files.exists(earlierScope), "a name that records no pid is never swept");
        }
    }

    @Test
    void launchFileOwnerReadsThePidAfterThePrefix() {
        assertEquals(123, AgentSubprocessExecutor.launchFileOwner(Path.of("gemini-123-456.json"), "gemini-"));
        assertEquals(-1, AgentSubprocessExecutor.launchFileOwner(Path.of("gemini-legacy.json"), "gemini-"));
        assertEquals(-1, AgentSubprocessExecutor.launchFileOwner(Path.of("gemini--5-x.json"), "gemini-"));
        assertEquals(-1, AgentSubprocessExecutor.launchFileOwner(Path.of("gemini-abc-1.json"), "gemini-"));
        assertEquals(-1, AgentSubprocessExecutor.launchFileOwner(
                Path.of("gemini-99999999999999999999-1.json"), "gemini-"));
        assertEquals(-1, AgentSubprocessExecutor.launchFileOwner(Path.of("scope-123.json"), "scope-"));
        assertEquals(-1, AgentSubprocessExecutor.launchFileOwner(Path.of("other-1-2.json"), "gemini-"));
    }

    @Test
    void executeSyncLaunchesGeminiWithItsSettingsAndDeletesThemAfterExit() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs a POSIX shell");
        Path script = tempDir.resolve("fake-gemini.sh");
        Files.writeString(script, """
                echo "settings=$GEMINI_CLI_SYSTEM_SETTINGS_PATH"
                echo "defaults=$GEMINI_CLI_SYSTEM_DEFAULTS_PATH"
                cat "$GEMINI_CLI_SYSTEM_SETTINGS_PATH"
                """);
        Path system = tempDir.resolve("etc/settings.json");
        Files.createDirectories(system.getParent());
        Files.writeString(system, "{ \"ui\": { \"theme\": \"Default\" }, }");
        AgentProvider gemini = AgentProvider.builder()
                .name("gemini-cli")
                .displayName("Gemini")
                .command("/bin/sh")
                .args(List.of(script.toString()))
                .mcpSupported(true)
                .available(true)
                .environment(Map.of(
                        GEMINI_SYSTEM_SETTINGS_ENV, system.toString(),
                        GEMINI_SYSTEM_DEFAULTS_ENV, ""))
                .build();
        when(registry.getAgent("gemini-cli")).thenReturn(Optional.of(gemini));
        when(diagnostics.startProcess(anyString(), anyList()))
                .thenAnswer(call -> new ProcessStatus(call.getArgument(0), call.getArgument(1)));

        AgentSubprocessExecutor.SubprocessResult result = executor.executeSync(
                "gemini-cli", "question", false, true, tempDir.toString(), 60);

        String output = result.content();
        assertTrue(result.isSuccess(), result.error() + "\n" + output);
        Path launch = Path.of(output.lines()
                .filter(line -> line.startsWith("settings="))
                .findFirst()
                .orElseThrow()
                .substring("settings=".length()));
        assertEquals(launchDirectory(), launch.getParent());
        assertTrue(output.contains("defaults=" + system.getParent().resolve("system-defaults.json")), output);
        assertTrue(output.contains(URL), output);
        assertTrue(output.contains("\"theme\""), output);
        assertFalse(Files.exists(launch), "the launch's settings are deleted once it has exited");
    }

    private Path launchDirectory() {
        return tempDir.resolve("config").resolve(AgentSubprocessExecutor.LAUNCH_DIRECTORY);
    }

    private static AgentProvider.Builder agent(String name, String command) {
        return AgentProvider.builder()
                .name(name)
                .displayName(name)
                .command(command)
                .mcpSupported(true)
                .available(true);
    }

    private static AgentProvider gemini(Map<String, String> environment) {
        return agent("gemini-cli", "gemini").environment(environment).build();
    }

    private static String serverUrl(Path config) throws IOException {
        return JSON.readTree(Files.readString(config))
                .path("mcpServers").path("kompile-app").path("url").asText();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
