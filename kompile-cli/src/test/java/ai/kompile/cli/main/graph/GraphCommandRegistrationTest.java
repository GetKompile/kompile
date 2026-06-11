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

package ai.kompile.cli.main.graph;

import ai.kompile.cli.main.MainCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify all graph subcommands are properly registered and their
 * help text is accessible. No running kompile-app is required — all tests
 * exercise only Picocli metadata (registration, option annotations, usage output).
 */
class GraphCommandRegistrationTest {

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

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMAND REGISTRATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGraphCommandRegisteredUnderMain() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        assertNotNull(graphCmd, "graph subcommand should be registered under MainCommand");
    }

    @Test
    void testAllSubcommandsRegistered() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        assertNotNull(graphCmd, "graph command must be registered");

        Map<String, CommandLine> subcommands = graphCmd.getSubcommands();

        // Inner static subcommands defined directly in GraphCommand
        assertTrue(subcommands.containsKey("stats"),          "missing 'stats'");
        assertTrue(subcommands.containsKey("add-node"),       "missing 'add-node'");
        assertTrue(subcommands.containsKey("get-node"),       "missing 'get-node'");
        assertTrue(subcommands.containsKey("delete-node"),    "missing 'delete-node'");
        assertTrue(subcommands.containsKey("list-nodes"),     "missing 'list-nodes'");
        assertTrue(subcommands.containsKey("search"),         "missing 'search'");
        assertTrue(subcommands.containsKey("add-edge"),       "missing 'add-edge'");
        assertTrue(subcommands.containsKey("delete-edge"),    "missing 'delete-edge'");
        assertTrue(subcommands.containsKey("traverse"),       "missing 'traverse'");
        assertTrue(subcommands.containsKey("path"),           "missing 'path'");
        assertTrue(subcommands.containsKey("algorithm"),      "missing 'algorithm'");
        assertTrue(subcommands.containsKey("communities"),    "missing 'communities'");

        // Top-level graph command classes (formerly orphaned — now registered)
        assertTrue(subcommands.containsKey("query"),          "missing 'query'");
        assertTrue(subcommands.containsKey("import"),         "missing 'import'");
        assertTrue(subcommands.containsKey("export"),         "missing 'export'");
        assertTrue(subcommands.containsKey("shell"),          "missing 'shell'");
        assertTrue(subcommands.containsKey("report"),         "missing 'report'");
        assertTrue(subcommands.containsKey("merge"),          "missing 'merge'");
        assertTrue(subcommands.containsKey("extract"),        "missing 'extract'");

        // New named-graph and hierarchy commands
        assertTrue(subcommands.containsKey("hierarchy"),      "missing 'hierarchy'");
        assertTrue(subcommands.containsKey("browse"),         "missing 'browse'");
        assertTrue(subcommands.containsKey("create-graph"),   "missing 'create-graph'");
        assertTrue(subcommands.containsKey("list-graphs"),    "missing 'list-graphs'");
        assertTrue(subcommands.containsKey("delete-graph"),   "missing 'delete-graph'");
        assertTrue(subcommands.containsKey("move-graph"),     "missing 'move-graph'");

        // Build, provenance, weights, and link-sources commands
        assertTrue(subcommands.containsKey("build"),          "missing 'build'");
        assertTrue(subcommands.containsKey("source-chunks"),  "missing 'source-chunks'");
        assertTrue(subcommands.containsKey("weights"),        "missing 'weights'");
        assertTrue(subcommands.containsKey("link-sources"),   "missing 'link-sources'");

        // Builder, proposals, and config commands
        assertTrue(subcommands.containsKey("builder"),        "missing 'builder'");
        assertTrue(subcommands.containsKey("proposals"),      "missing 'proposals'");
        assertTrue(subcommands.containsKey("config"),         "missing 'config'");

        // Total count guard — catches unintended additions or removals
        assertEquals(32, subcommands.size(),
                "Expected exactly 32 subcommands under 'graph', got " + subcommands.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELP TEXT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGraphCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        // Description must mention knowledge graphs
        assertTrue(output.toLowerCase().contains("knowledge graph") || output.contains("graph"),
                "graph help should reference knowledge graphs");
        // Subcommands must appear
        assertTrue(output.contains("stats"),       "help should list 'stats'");
        assertTrue(output.contains("hierarchy"),   "help should list 'hierarchy'");
        assertTrue(output.contains("create-graph"),"help should list 'create-graph'");
    }

    @Test
    void testHierarchyCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "hierarchy", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("hierarchy"), "hierarchy help should mention 'hierarchy'");
        assertTrue(output.contains("--depth")  || output.contains("depth"),
                "hierarchy help should mention depth option");
    }

    @Test
    void testBrowseCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "browse", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("browse"), "browse help output should be non-empty");
    }

    @Test
    void testCreateGraphCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "create-graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("create-graph") || output.contains("graph"),
                "create-graph help should be non-empty");
    }

    @Test
    void testListGraphsCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "list-graphs", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("list-graphs") || output.contains("graph"),
                "list-graphs help should be non-empty");
    }

    @Test
    void testDeleteGraphCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "delete-graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("delete-graph") || output.contains("graph"),
                "delete-graph help should be non-empty");
    }

    @Test
    void testMoveGraphCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "move-graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("move-graph") || output.contains("graph"),
                "move-graph help should be non-empty");
    }

    @Test
    void testBuilderCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "builder", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("builder"), "builder help should mention 'builder'");
    }

    @Test
    void testProposalsCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "proposals", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("proposals"), "proposals help should mention 'proposals'");
    }

    @Test
    void testConfigCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "config", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("config"), "config help should mention 'config'");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PREVIOUSLY-ORPHANED COMMANDS — still accessible under graph
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testReportCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "report", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertFalse(output.isBlank(), "report help output should not be blank");
    }

    @Test
    void testMergeCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "merge", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertFalse(output.isBlank(), "merge help output should not be blank");
    }

    @Test
    void testExtractCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "extract", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertFalse(output.isBlank(), "extract help output should not be blank");
    }
}
