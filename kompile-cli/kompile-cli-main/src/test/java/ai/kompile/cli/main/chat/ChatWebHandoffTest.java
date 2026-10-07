package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ChatWebHandoffTest {
    @TempDir Path directory;

    @AfterEach void noTeamOutlivesATest() {
        WorkflowSessionContext.activate(null);
    }

    @Test void setupWebHandsOffOnlyAfterValidSelection() throws Exception {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", "secret", "model", "https://example.test/v1");
        command.config.setChatMode("standard");
        String output = successfulOutput(command, "--setup", "--web", "--global-config",
                "--working-dir", directory.toString());
        assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
        assertTrue(command.selected);
        assertFalse(command.wizard);
        assertEquals(directory.toRealPath(), command.started);
        assertNull(command.opened);
    }

    @Test void workspaceRequiresWebAndOpensTheWorkspaceRoute() {
        Stub invalid = new Stub();
        assertEquals(2, new CommandLine(invalid).execute("--workspace"));
        assertFalse(invalid.selected);
        assertNull(invalid.started);
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        String output = successfulOutput(command, "--web", "--workspace", "--open-browser",
                "--working-dir", directory.toString());
        assertTrue(output.contains("http://127.0.0.1:1234/#/chat"), output);
        assertEquals("http://127.0.0.1:1234/#/chat", command.opened);
    }

    @Test void webDefaultsToPrintedUrlWithoutBrowser() {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        String output = successfulOutput(command, "--web", "--working-dir", directory.toString());
        assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
        assertNull(command.opened);
        assertFalse(command.wizard);
        assertNull(command.startedWorkflow);
        assertTrue(command.webWorkspace());
        assertTrue(output.contains("http://127.0.0.1:1234/#/chat"), output);
        assertFalse(output.contains("Workflow team:"), output);
    }

    @Test void singleChatRemainsExplicitAndDoesNotEnableWorkspace() {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        successfulOutput(command, "--web", "--single-chat", "--open-browser", "--working-dir", directory.toString());
        assertFalse(command.webWorkspace());
        assertEquals("http://127.0.0.1:1234/#/single-chat", command.opened);
        for (String[] args : new String[][] {{"--single-chat"}, {"--web", "--single-chat", "--workspace"}}) {
            Stub invalid = new Stub();
            assertEquals(2, new CommandLine(invalid).execute(args));
            assertFalse(invalid.selected);
            assertNull(invalid.started);
        }
    }

    @Test void aNamedTeamStartsEveryNewWebSession() throws Exception {
        saveTeams("session-team");
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        String output = successfulOutput(command, "--web", "--workflow", "session-team",
                "--working-dir", directory.toString());
        assertEquals("session-team", command.startedWorkflow);
        assertTrue(output.contains("Workflow team: session-team."), output);
        assertNull(WorkflowSessionContext.current(), "the browser's harness runs the team, not this process");
    }

    @Test void aTeamThatCannotBeLoadedStopsTheHandoffBeforeTheServerStarts() {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        assertEquals(2, new CommandLine(command).execute("--web", "--workflow", "missing-team",
                "--working-dir", directory.toString()));
        assertNull(command.started);
    }

    @Test void theWizardsTeamCarriesIntoTheBrowserUnlessTheFlagNamesAnother() throws Exception {
        saveTeams("session-team", "other-team");
        for (String flag : new String[] {null, "other-team"}) {
            Stub command = new Stub();
            command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
            command.destination = SetupWizard.Destination.BROWSER;
            command.wizardWorkflow = new WorkflowTeamSnapshot(team("session-team"), Map.of(), null);
            String[] args = flag == null
                    ? new String[] {"--setup", "--working-dir", directory.toString()}
                    : new String[] {"--setup", "--workflow", flag, "--working-dir", directory.toString()};
            successfulOutput(command, args);
            assertTrue(command.wizard);
            assertEquals(flag == null ? "session-team" : flag, command.startedWorkflow);
        }
    }

    @Test void aTeamTheHarnessWouldReadAsTheCreationWizardIsRefused() {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        command.destination = SetupWizard.Destination.BROWSER;
        command.wizardWorkflow = new WorkflowTeamSnapshot(team("Create"), Map.of(), null);
        assertEquals(2, new CommandLine(command).execute("--setup", "--working-dir", directory.toString()));
        assertNull(command.started);
    }

    @Test void explicitBrowserOptInPrintsUrlAndDoesNotRunDestinationWizard() {
        for (boolean setup : new boolean[] {false, true}) {
            Stub command = new Stub();
            command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
            String[] args = setup
                    ? new String[] {"--web", "--open-browser", "--setup", "--working-dir", directory.toString()}
                    : new String[] {"--web", "--open-browser", "--working-dir", directory.toString()};
            String output = successfulOutput(command, args);
            assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
            assertEquals("http://127.0.0.1:1234/#/chat", command.opened);
            assertTrue(command.selected);
            assertFalse(command.wizard);
        }
    }

    @Test void browserOptInRequiresExplicitWebBeforeAnySetupOrLaunch() {
        for (String[] args : new String[][] {
                {"--open-browser"}, {"--open-browser", "--setup"},
                {"--open-browser", "--resume", "id"}}) {
            Stub command = new Stub();
            assertEquals(2, new CommandLine(command).execute(args));
            assertFalse(command.wizard);
            assertFalse(command.selected);
            assertNull(command.started);
            assertNull(command.opened);
        }
    }

    @Test void cancellationOrSaveFailureNeverStartsServer() {
        Stub command = new Stub();
        assertEquals(1, new CommandLine(command).execute("--setup", "--web"));
        assertNull(command.started);
        assertNull(command.opened);
    }

    @Test void incompatibleOptionsAreRejectedBeforeSetup() {
        for (String[] options : new String[][] {
                {"--resume", "id"}, {"--mode", "standard"}, {"--provider", "custom"},
                {"--url", "http://localhost:8081"}, {"--no-start"}, {"--capabilities"},
                {"--multi-session"}, {"--local"}, {"--no-memory"}, {"hello"}}) {
            Stub command = new Stub();
            String[] args = new String[options.length + 1];
            args[0] = "--web";
            System.arraycopy(options, 0, args, 1, options.length);
            assertEquals(2, new CommandLine(command).execute(args));
            assertFalse(command.selected);
            assertNull(command.started);
        }
    }

    @Test void incompatibleConfigurationsAreNotReinterpreted() {
        for (String mode : new String[] {"resume", "resume-all"}) {
            ChatConfig config = new ChatConfig("custom", "secret", "model", "https://example.test/v1");
            config.setChatMode(mode);
            assertNotNull(ChatCommand.webConfigError(config));
        }
        ChatConfig nativeConfig = new ChatConfig(null, null, null, null);
        nativeConfig.setChatMode("passthrough");
        nativeConfig.setPassthroughAgent("opencode");
        nativeConfig.setPassthroughManaged(true);
        assertNull(ChatCommand.webConfigError(nativeConfig));
        nativeConfig.setPassthroughManaged(false);
        assertNotNull(ChatCommand.webConfigError(nativeConfig));
        nativeConfig.setPassthroughManaged(true);
        nativeConfig.setPassthroughAgent("unsupported");
        assertNotNull(ChatCommand.webConfigError(nativeConfig));
        assertNotNull(ChatCommand.webConfigError(new ChatConfig("kompile", null, null, "http://localhost:8081")));
    }

    @Test void wizardBrowserUsesExistingHandoffWithoutSelectingOrAuthenticatingAgain() {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        command.destination = SetupWizard.Destination.BROWSER;
        String output = successfulOutput(command, "--setup", "--working-dir", directory.toString());
        assertTrue(output.contains("Web chat: http://127.0.0.1:1234"));
        assertTrue(command.wizard);
        assertFalse(command.selected);
        assertNotNull(command.started);
        assertNull(command.opened);
    }

    @Test void terminalSetupExitsAndCancelledSetupDoesNotLaunch() {
        Stub command = new Stub();
        command.config = new ChatConfig("custom", null, "model", "http://127.0.0.1:9000/v1");
        assertEquals(0, new CommandLine(command).execute("--setup"));
        assertNull(command.started);
        command.config = null;
        assertEquals(1, new CommandLine(command).execute("--setup"));
        assertNull(command.started);
    }

    private static String successfulOutput(Stub command, String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            assertEquals(0, new CommandLine(command).execute(args));
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Saves each named team in the project, with the roles its participants play. */
    private void saveTeams(String... names) throws Exception {
        for (String name : names) assertTrue(WorkflowTeamStore.save(directory, team(name), true));
        RoleManager roles = new RoleManager(directory);
        if (roles.getRole("architect") == null) roles.createRole("architect", "Architect", "d", "workflow", "design");
        if (roles.getRole("implementer") == null) {
            roles.createRole("implementer", "Implementer", "d", "workflow", "implement");
        }
    }

    private static WorkflowTeam team(String name) {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                List.of("read", "plan", "delegate"), List.of("worker")));
        participants.put("worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                List.of("read", "edit-assigned-files", "validate"), List.of()));
        return new WorkflowTeam(name, 1, "designer", participants, Map.of("implement", "worker"),
                new WorkflowTeam.Limits(2), new WorkflowTeam.Gates("approved-design", null));
    }

    private static class Stub extends ChatCommand {
        SetupWizard.Destination destination = SetupWizard.Destination.TERMINAL;
        WorkflowTeamSnapshot wizardWorkflow;
        boolean wizard;
        @Override SetupWizard.SetupResult runSetupWizard() {
            wizard = true;
            return config == null ? null : new SetupWizard.SetupResult(config, destination, wizardWorkflow);
        }
        ChatConfig config;
        boolean selected;
        Path started;
        String startedWorkflow;
        String opened;
        @Override SetupWizard.SetupResult selectWebConfig(Path path) {
            selected = true;
            return config == null ? null : new SetupWizard.SetupResult(config, SetupWizard.Destination.BROWSER);
        }
        @Override ChatInstanceBootstrap.StartupResult startWeb(Path path, String workflowName) {
            started = path;
            startedWorkflow = workflowName;
            return new ChatInstanceBootstrap.StartupResult("http://127.0.0.1:1234", true);
        }
        @Override void openWebBrowser(String address) { opened = address; }
    }
}
