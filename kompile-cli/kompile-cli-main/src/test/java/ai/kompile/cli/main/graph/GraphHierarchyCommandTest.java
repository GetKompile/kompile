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
 * Tests for {@link GraphHierarchyCommand}: option annotations, default values,
 * and help text generation. No running kompile-app is required.
 */
class GraphHierarchyCommandTest {

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
    // DEFAULT VALUES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testDefaultOptions() {
        // Parse with no options specified and verify Picocli applies declared defaults.
        // We parse using the GraphHierarchyCommand directly to inspect its fields
        // via the ParseResult (defaults are injected by Picocli during parse).
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        CommandLine.ParseResult pr = hierarchyLine.parseArgs(); // no args → all defaults

        // --depth defaults to 5 (as declared in the @Option annotation)
        assertTrue(pr.hasMatchedOption("--depth") == false,
                "depth should use its declared default when not supplied");
        // Verify the default value token registered in Picocli metadata is "5"
        CommandLine.Model.OptionSpec depthSpec = hierarchyLine.getCommandSpec().findOption("--depth");
        assertNotNull(depthSpec, "--depth option must be declared");
        assertEquals("5", depthSpec.defaultValue(),
                "--depth default value should be 5");

        // --format defaults to "tree"
        CommandLine.Model.OptionSpec formatSpec = hierarchyLine.getCommandSpec().findOption("--format");
        assertNotNull(formatSpec, "--format option must be declared");
        assertEquals("tree", formatSpec.defaultValue(),
                "--format default value should be 'tree'");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELP TEXT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testHelpContainsOptions() {
        int exitCode = cmd.execute("graph", "hierarchy", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();

        assertTrue(output.contains("--node-id"),  "help should mention --node-id");
        assertTrue(output.contains("--depth"),    "help should mention --depth");
        assertTrue(output.contains("--format"),   "help should mention --format");
        // --server comes from AppClientMixin (--url / --port)
        assertTrue(output.contains("--url") || output.contains("--port"),
                "help should mention server connection option (--url or --port from AppClientMixin)");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FORMAT OPTION ACCEPTANCE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testAcceptsJsonFormat() {
        // Parsing --format=json should not produce a parse error.
        // The command will fail at call() time because no server is running,
        // but Picocli option binding must succeed (exit code 1 from requireClient, not 2).
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        // A clean parse with a known value should not throw
        assertDoesNotThrow(() -> hierarchyLine.parseArgs("--format", "json"),
                "--format=json should be accepted by Picocli without parse error");
    }

    @Test
    void testAcceptsTableFormat() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        assertDoesNotThrow(() -> hierarchyLine.parseArgs("--format", "table"),
                "--format=table should be accepted by Picocli without parse error");
    }

    @Test
    void testAcceptsTreeFormat() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        assertDoesNotThrow(() -> hierarchyLine.parseArgs("--format", "tree"),
                "--format=tree should be accepted by Picocli without parse error");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE ID OPTION ACCEPTANCE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testAcceptsNodeIdOption() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        assertDoesNotThrow(
                () -> hierarchyLine.parseArgs("--node-id", "some-uuid-1234"),
                "--node-id should be accepted without parse error");
    }

    @Test
    void testAcceptsDepthOption() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        assertDoesNotThrow(
                () -> hierarchyLine.parseArgs("--depth", "10"),
                "--depth=10 should be accepted without parse error");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPTION SPEC INTROSPECTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testDepthOptionIsInteger() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        CommandLine.Model.OptionSpec depthSpec = hierarchyLine.getCommandSpec().findOption("--depth");
        assertNotNull(depthSpec, "--depth option must exist");
        assertEquals(int.class, depthSpec.type(),
                "--depth must be bound to an int field");
    }

    @Test
    void testFormatOptionIsString() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        CommandLine.Model.OptionSpec formatSpec = hierarchyLine.getCommandSpec().findOption("--format");
        assertNotNull(formatSpec, "--format option must exist");
        assertEquals(String.class, formatSpec.type(),
                "--format must be bound to a String field");
    }

    @Test
    void testNodeIdOptionIsOptional() {
        CommandLine hierarchyLine = new CommandLine(new GraphHierarchyCommand());
        CommandLine.Model.OptionSpec nodeIdSpec = hierarchyLine.getCommandSpec().findOption("--node-id");
        assertNotNull(nodeIdSpec, "--node-id option must exist");
        assertFalse(nodeIdSpec.required(), "--node-id should be optional (not required)");
    }
}
