package ai.kompile.cli.agent;

import picocli.CommandLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the RulesCommand CLI argument parsing and dispatch routing.
 * Tests cover the new availability, python, and javascript subcommands.
 *
 * Since the actual HTTP calls require a running server, these tests validate:
 * - Picocli parameter parsing (resource, operation, options)
 * - Error handling for unknown resources/operations
 * - Validation of required options
 *
 * The command uses System.err.println() for error messages, so we capture System.err.
 */
class RulesCommandTest {

    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void setUp() {
        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
    }

    /**
     * Execute a rules command with the given args and return the exit code.
     * stderr is captured via System.err redirect.
     */
    private CommandResult execute(String... args) {
        capturedErr.reset();
        RulesCommand cmd = new RulesCommand();
        CommandLine cl = new CommandLine(cmd);
        int exitCode = cl.execute(args);
        return new CommandResult(exitCode, capturedErr.toString());
    }

    private record CommandResult(int exitCode, String stderr) {}

    // ==================== Resource Routing ====================

    @Test
    void testUnknownResource_returnsError() {
        CommandResult result = execute("nonexistent");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testAvailability_validResource() {
        // Will fail with connection refused but should NOT report "Unknown resource"
        CommandResult result = execute("availability");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testPython_validResource() {
        CommandResult result = execute("python", "runtime");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testJavascript_validResource() {
        CommandResult result = execute("javascript", "runtime");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testJs_aliasForJavascript() {
        CommandResult result = execute("js", "runtime");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    // ==================== Python Operations ====================

    @Test
    void testPython_unknownOperation() {
        CommandResult result = execute("python", "nonexistent");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown python operation"),
                "Expected 'Unknown python operation' in: " + result.stderr());
    }

    @Test
    void testPython_checkRequiresPackage() {
        CommandResult result = execute("python", "check");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("--package required"),
                "Expected '--package required' in: " + result.stderr());
    }

    @Test
    void testPython_installRequiresPackageOrRequirements() {
        CommandResult result = execute("python", "install");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("--package or --requirements required"),
                "Expected '--package or --requirements required' in: " + result.stderr());
    }

    @Test
    void testPython_uninstallRequiresPackage() {
        CommandResult result = execute("python", "uninstall");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("--package required"),
                "Expected '--package required' in: " + result.stderr());
    }

    @Test
    void testPython_checkWithPackage_passesValidation() {
        CommandResult result = execute("python", "check", "--package", "numpy");
        // Routing + validation passed — failure is from HTTP connection, not routing
        assertFalse(result.stderr().contains("--package required"));
        assertFalse(result.stderr().contains("Unknown python operation"));
    }

    @Test
    void testPython_installWithPackage_passesValidation() {
        CommandResult result = execute("python", "install", "--package", "numpy");
        assertFalse(result.stderr().contains("--package or --requirements required"));
    }

    @Test
    void testPython_installWithVersion_passesValidation() {
        CommandResult result = execute("python", "install", "--package", "numpy", "--version", "1.24.0");
        assertFalse(result.stderr().contains("--package or --requirements required"));
    }

    @Test
    void testPython_installWithRequirements_passesValidation() {
        CommandResult result = execute("python", "install", "--requirements", "/tmp/requirements.txt");
        assertFalse(result.stderr().contains("--package or --requirements required"));
    }

    @Test
    void testPython_defaultOperationIsRuntime() {
        CommandResult result = execute("python");
        assertFalse(result.stderr().contains("Unknown python operation"));
    }

    // ==================== JavaScript Operations ====================

    @Test
    void testJavascript_unknownOperation() {
        CommandResult result = execute("javascript", "nonexistent");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown javascript operation"),
                "Expected 'Unknown javascript operation' in: " + result.stderr());
    }

    @Test
    void testJavascript_defaultOperationIsRuntime() {
        CommandResult result = execute("javascript");
        assertFalse(result.stderr().contains("Unknown javascript operation"));
    }

    // ==================== Existing Resources Still Work ====================

    @Test
    void testEvaluate_validResource() {
        CommandResult result = execute("evaluate");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testGraph_configOperation() {
        CommandResult result = execute("graph", "config");
        assertFalse(result.stderr().contains("Unknown resource"));
        assertFalse(result.stderr().contains("Unknown graph operation"));
    }

    @Test
    void testGraph_unknownOperation() {
        CommandResult result = execute("graph", "nonexistent");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown graph operation"),
                "Expected 'Unknown graph operation' in: " + result.stderr());
    }

    @Test
    void testWorkflow_validResource() {
        CommandResult result = execute("workflow", "list");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testDecisionTable_validResource() {
        CommandResult result = execute("decision-table", "inspect");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    // ==================== Port/URL Options ====================

    @Test
    void testCustomPort() {
        CommandResult result = execute("availability", "-p", "9090");
        assertFalse(result.stderr().contains("Unknown resource"));
    }

    @Test
    void testCustomUrl() {
        CommandResult result = execute("availability", "--url", "http://my-server:8080");
        assertFalse(result.stderr().contains("Unknown resource"));
    }
}
