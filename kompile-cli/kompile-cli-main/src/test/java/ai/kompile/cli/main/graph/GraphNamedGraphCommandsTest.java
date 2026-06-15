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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the named-graph management commands:
 * {@link GraphCreateCommand}, {@link GraphListCommand},
 * {@link GraphDeleteCommand}, and {@link GraphMoveCommand}.
 *
 * All tests exercise Picocli metadata only (option annotations, required flags,
 * defaults, help text). No running kompile-app is required.
 */
class GraphNamedGraphCommandsTest {

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
    // create-graph
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testCreateGraphRequiresName() {
        // Invoking create-graph without --name must produce a non-zero exit code
        // (Picocli exits 2 for missing required options).
        int exitCode = cmd.execute("graph", "create-graph");
        assertNotEquals(0, exitCode,
                "create-graph without --name should fail with a non-zero exit code");
    }

    @Test
    void testCreateGraphHelp() {
        int exitCode = cmd.execute("graph", "create-graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();

        assertTrue(output.contains("--name"),            "help should mention --name");
        assertTrue(output.contains("--description"),     "help should mention --description");
        assertTrue(output.contains("--parent-graph-id"), "help should mention --parent-graph-id");
        assertTrue(output.contains("--ontology-type"),   "help should mention --ontology-type");
    }

    @Test
    void testCreateGraphNameIsRequired() {
        CommandLine createLine = new CommandLine(new GraphCreateCommand());
        CommandLine.Model.OptionSpec nameSpec = createLine.getCommandSpec().findOption("--name");
        assertNotNull(nameSpec, "--name option must be declared on GraphCreateCommand");
        assertTrue(nameSpec.required(), "--name must be marked as required");
    }

    @Test
    void testCreateGraphNameOption_parseAccepted() {
        CommandLine createLine = new CommandLine(new GraphCreateCommand());
        assertDoesNotThrow(() -> createLine.parseArgs("--name", "my-graph"),
                "--name my-graph should parse without error");
    }

    @Test
    void testCreateGraphOptionalOptions_allPresent() {
        CommandLine createLine = new CommandLine(new GraphCreateCommand());
        assertNotNull(createLine.getCommandSpec().findOption("--description"),
                "--description must be declared");
        assertNotNull(createLine.getCommandSpec().findOption("--parent-graph-id"),
                "--parent-graph-id must be declared");
        assertNotNull(createLine.getCommandSpec().findOption("--fact-sheet-id"),
                "--fact-sheet-id must be declared");
        assertNotNull(createLine.getCommandSpec().findOption("--ontology-type"),
                "--ontology-type must be declared");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // list-graphs
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testListGraphsDefaultFormat() {
        CommandLine listLine = new CommandLine(new GraphListCommand());
        CommandLine.Model.OptionSpec formatSpec = listLine.getCommandSpec().findOption("--format");
        assertNotNull(formatSpec, "--format option must be declared on GraphListCommand");
        assertEquals("tree", formatSpec.defaultValue(),
                "--format default should be 'tree'");
    }

    @Test
    void testListGraphsHelp() {
        int exitCode = cmd.execute("graph", "list-graphs", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--format"), "list-graphs help should mention --format");
        assertTrue(output.contains("--query") || output.contains("query"),
                "list-graphs help should mention --query filter option");
    }

    @Test
    void testListGraphsFormatOptionAcceptsTree() {
        CommandLine listLine = new CommandLine(new GraphListCommand());
        assertDoesNotThrow(() -> listLine.parseArgs("--format", "tree"),
                "--format=tree should parse without error");
    }

    @Test
    void testListGraphsFormatOptionAcceptsJson() {
        CommandLine listLine = new CommandLine(new GraphListCommand());
        assertDoesNotThrow(() -> listLine.parseArgs("--format", "json"),
                "--format=json should parse without error");
    }

    @Test
    void testListGraphsFormatOptionAcceptsTable() {
        CommandLine listLine = new CommandLine(new GraphListCommand());
        assertDoesNotThrow(() -> listLine.parseArgs("--format", "table"),
                "--format=table should parse without error");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // delete-graph
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testDeleteGraphRequiresGraphId() {
        // Invoking delete-graph without --graph-id must fail
        int exitCode = cmd.execute("graph", "delete-graph");
        assertNotEquals(0, exitCode,
                "delete-graph without --graph-id should fail with a non-zero exit code");
    }

    @Test
    void testDeleteGraphHasForceOption() {
        int exitCode = cmd.execute("graph", "delete-graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--force"), "delete-graph help should mention --force");
    }

    @Test
    void testDeleteGraphGraphIdIsRequired() {
        CommandLine deleteLine = new CommandLine(new GraphDeleteCommand());
        CommandLine.Model.OptionSpec graphIdSpec = deleteLine.getCommandSpec().findOption("--graph-id");
        assertNotNull(graphIdSpec, "--graph-id must be declared on GraphDeleteCommand");
        assertTrue(graphIdSpec.required(), "--graph-id must be marked as required");
    }

    @Test
    void testDeleteGraphForceOptionIsOptional() {
        CommandLine deleteLine = new CommandLine(new GraphDeleteCommand());
        CommandLine.Model.OptionSpec forceSpec = deleteLine.getCommandSpec().findOption("--force");
        assertNotNull(forceSpec, "--force must be declared on GraphDeleteCommand");
        assertFalse(forceSpec.required(), "--force should be optional");
    }

    @Test
    void testDeleteGraphParseWithGraphId() {
        CommandLine deleteLine = new CommandLine(new GraphDeleteCommand());
        assertDoesNotThrow(() -> deleteLine.parseArgs("--graph-id", "abc-123", "--force"),
                "--graph-id + --force should parse without error");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // move-graph
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testMoveGraphRequiresGraphId() {
        // Invoking move-graph without --graph-id must fail
        int exitCode = cmd.execute("graph", "move-graph");
        assertNotEquals(0, exitCode,
                "move-graph without --graph-id should fail with a non-zero exit code");
    }

    @Test
    void testMoveGraphHelp() {
        int exitCode = cmd.execute("graph", "move-graph", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("--new-parent-id"), "move-graph help should mention --new-parent-id");
        assertTrue(output.contains("--graph-id"),      "move-graph help should mention --graph-id");
    }

    @Test
    void testMoveGraphGraphIdIsRequired() {
        CommandLine moveLine = new CommandLine(new GraphMoveCommand());
        CommandLine.Model.OptionSpec graphIdSpec = moveLine.getCommandSpec().findOption("--graph-id");
        assertNotNull(graphIdSpec, "--graph-id must be declared on GraphMoveCommand");
        assertTrue(graphIdSpec.required(), "--graph-id must be marked as required");
    }

    @Test
    void testMoveGraphNewParentIdIsOptional() {
        CommandLine moveLine = new CommandLine(new GraphMoveCommand());
        CommandLine.Model.OptionSpec newParentSpec = moveLine.getCommandSpec().findOption("--new-parent-id");
        assertNotNull(newParentSpec, "--new-parent-id must be declared on GraphMoveCommand");
        assertFalse(newParentSpec.required(),
                "--new-parent-id should be optional (omit to make the graph root-level)");
    }

    @Test
    void testMoveGraphParseWithOnlyGraphId() {
        // --graph-id alone (no --new-parent-id) is valid — makes the graph root-level
        CommandLine moveLine = new CommandLine(new GraphMoveCommand());
        assertDoesNotThrow(() -> moveLine.parseArgs("--graph-id", "graph-uuid-42"),
                "--graph-id alone should parse without error");
    }

    @Test
    void testMoveGraphParseWithNewParentId() {
        CommandLine moveLine = new CommandLine(new GraphMoveCommand());
        assertDoesNotThrow(
                () -> moveLine.parseArgs("--graph-id", "child-uuid", "--new-parent-id", "parent-uuid"),
                "--graph-id + --new-parent-id should parse without error");
    }
}
