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

package ai.kompile.cli.main.app;

import ai.kompile.cli.main.MainCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SetupCommand} and its subcommands.
 * SetupCommand is registered under AppCommand (kompile app setup ...),
 * not directly on MainCommand. Verifies command registration, option
 * parsing, and help text generation.
 */
class SetupCommandTest {

    private CommandLine cmd;
    private StringWriter outWriter;
    private StringWriter errWriter;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(new MainCommand());
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        cmd.setOut(new PrintWriter(outWriter));
        cmd.setErr(new PrintWriter(errWriter));
    }

    /** Resolve setup from its actual location: kompile app setup */
    private CommandLine setupCmd() {
        CommandLine appCmd = cmd.getSubcommands().get("app");
        assertNotNull(appCmd, "app subcommand should be registered on MainCommand");
        return appCmd.getSubcommands().get("setup");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMAND REGISTRATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testSetupCommandRegistered() {
        // SetupCommand was migrated from top-level to 'app setup'
        CommandLine appCmd = cmd.getSubcommands().get("app");
        assertNotNull(appCmd, "app subcommand should be registered");
        assertNotNull(appCmd.getSubcommands().get("setup"),
                "setup subcommand should be registered under app");
    }

    @Test
    void testSetupSubcommands() {
        CommandLine setupCmd = setupCmd();
        assertNotNull(setupCmd);

        var subcommands = setupCmd.getSubcommands();
        assertTrue(subcommands.containsKey("status"), "should have 'status' subcommand");
        assertTrue(subcommands.containsKey("staging"), "should have 'staging' subcommand");
        assertTrue(subcommands.containsKey("staging-server"), "should have 'staging-server' subcommand");
        assertTrue(subcommands.containsKey("reload"), "should have 'reload' subcommand");
        assertTrue(subcommands.containsKey("watch"), "should have 'watch' subcommand");
        assertTrue(subcommands.containsKey("run"), "should have 'run' subcommand");
    }

    @Test
    void testStagingServerSubcommands() {
        CommandLine setupCmd = setupCmd();
        assertNotNull(setupCmd);
        CommandLine stagingServerCmd = setupCmd.getSubcommands().get("staging-server");
        assertNotNull(stagingServerCmd);

        var subcommands = stagingServerCmd.getSubcommands();
        assertTrue(subcommands.containsKey("start"), "should have 'start' subcommand");
        assertTrue(subcommands.containsKey("stop"), "should have 'stop' subcommand");
        assertTrue(subcommands.containsKey("status"), "should have 'status' subcommand");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELP TEXT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testSetupHelp() {
        int exitCode = cmd.execute("app", "setup", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("setup"));
        assertTrue(output.contains("staging-server"));
        assertTrue(output.contains("run"));
    }

    @Test
    void testStagingServerHelp() {
        int exitCode = cmd.execute("app", "setup", "staging-server", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("staging-server"));
        assertTrue(output.contains("start"));
        assertTrue(output.contains("stop"));
        assertTrue(output.contains("status"));
    }

    @Test
    void testStagingServerStartHelp() {
        int exitCode = cmd.execute("app", "setup", "staging-server", "start", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--port"));
        assertTrue(output.contains("--stage-model"));
    }

    @Test
    void testRunHelp() {
        int exitCode = cmd.execute("app", "setup", "run", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--staging-url"), "should show --staging-url option");
        assertTrue(output.contains("--staging-port"), "should show --staging-port option");
        assertTrue(output.contains("--stage-model"), "should show --stage-model option");
        assertTrue(output.contains("--timeout"), "should show --timeout option");
        // negatable boolean shows as --[no-]start-staging
        assertTrue(output.contains("start-staging"), "should show start-staging option");
    }

    @Test
    void testWatchHelp() {
        int exitCode = cmd.execute("app", "setup", "watch", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--timeout"), "should show --timeout option");
        assertTrue(output.contains("--interval"), "should show --interval option");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPTION PARSING via help output (verifies picocli annotations are correct)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testRunCommandOptions_allPresent() {
        // Verify all options show in help text (proves annotations are valid)
        int exitCode = cmd.execute("app", "setup", "run", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--staging-url"), "missing --staging-url");
        assertTrue(output.contains("--staging-port"), "missing --staging-port");
        assertTrue(output.contains("--staging-name"), "missing --staging-name");
        assertTrue(output.contains("--staging-api-key"), "missing --staging-api-key");
        assertTrue(output.contains("--stage-model"), "missing --stage-model");
        assertTrue(output.contains("--timeout"), "missing --timeout");
    }

    @Test
    void testStagingServerStartOptions_allPresent() {
        int exitCode = cmd.execute("app", "setup", "staging-server", "start", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--port"), "missing --port");
        assertTrue(output.contains("--stage-model"), "missing --stage-model");
    }

    @Test
    void testWatchOptions_allPresent() {
        int exitCode = cmd.execute("app", "setup", "watch", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--timeout"), "missing --timeout");
        assertTrue(output.contains("--interval"), "missing --interval");
    }

    @Test
    void testStagingOptions_allPresent() {
        int exitCode = cmd.execute("app", "setup", "staging", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--name"), "missing --name");
        assertTrue(output.contains("--api-key"), "missing --api-key");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS DISPLAY HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testPrintStepStatus_complete() {
        // Capture stdout
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(baos));
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode step = om.createObjectNode();
            step.put("status", "COMPLETE");
            step.put("complete", true);
            step.put("name", "Staging Server");
            step.put("message", "Running on port 8081");

            SetupCommand.printStepStatus(step);

            String output = baos.toString();
            assertTrue(output.contains("\u2714")); // check mark
            assertTrue(output.contains("Staging Server"));
            assertTrue(output.contains("Running on port 8081"));
        } finally {
            System.setOut(System.out);
        }
    }

    @Test
    void testAllStepKeys() {
        // Verify ALL_STEP_KEYS has 5 entries matching the backend model
        assertEquals(5, SetupCommand.ALL_STEP_KEYS.length);
        assertEquals("stagingServer", SetupCommand.ALL_STEP_KEYS[0]);
        assertEquals("modelSource", SetupCommand.ALL_STEP_KEYS[1]);
        assertEquals("embeddingModel", SetupCommand.ALL_STEP_KEYS[2]);
        assertEquals("indexing", SetupCommand.ALL_STEP_KEYS[3]);
        assertEquals("searchReady", SetupCommand.ALL_STEP_KEYS[4]);
    }
}
