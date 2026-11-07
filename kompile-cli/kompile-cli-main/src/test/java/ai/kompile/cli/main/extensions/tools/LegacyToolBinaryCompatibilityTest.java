package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.plugin.api.tools.ExtensionTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit prior-ABI fixture: this is binary-compatibility testing, not a substituted native-host proof. */
class LegacyToolBinaryCompatibilityTest {
    @TempDir Path temporary;

    @Test
    void movedValuesHaveOneDefinitionOwnedByToolApi() throws Exception {
        var api = ExtensionTool.class.getProtectionDomain().getCodeSource().getLocation();
        for (Class<?> value : List.of(ToolResult.class, ToolExecutionException.class, McpToolAnnotations.class)) {
            assertEquals(api, value.getProtectionDomain().getCodeSource().getLocation(), value.getName());
            var definitions = Collections.list(getClass().getClassLoader()
                    .getResources(value.getName().replace('.', '/') + ".class"));
            assertEquals(1, definitions.size(), () -> "Duplicate moved tool value: " + definitions);
        }
    }

    @Test
    void previouslyCompiledConcreteContextToolAlsoDispatchesThroughGenericContract() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path output = Files.createDirectories(temporary.resolve("classes"));
        Path oldContract = source.resolve("ai/kompile/cli/main/chat/tools/CliTool.java");
        Files.createDirectories(oldContract.getParent());
        // The pre-extraction interface had no generic superinterface and used the concrete context descriptor.
        Files.writeString(oldContract, """
                package ai.kompile.cli.main.chat.tools;
                import com.fasterxml.jackson.databind.JsonNode;
                public interface CliTool {
                    String id();
                    String description();
                    JsonNode parameterSchema();
                    String permissionKey();
                    ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException;
                    default McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }
                    default String compactHint() { return null; }
                    default boolean isBackgroundable() { return false; }
                }
                """);
        Path legacy = source.resolve("legacy/PreviouslyCompiledTool.java");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, """
                package legacy;
                import ai.kompile.cli.main.chat.tools.*;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.JsonNodeFactory;
                public final class PreviouslyCompiledTool implements CliTool {
                    public String id() { return "legacy_fixture"; }
                    public String description() { return "An old independently compiled tool"; }
                    public JsonNode parameterSchema() { return JsonNodeFactory.instance.objectNode(); }
                    public String permissionKey() { return "legacy_fixture"; }
                    public ToolResult execute(JsonNode params, ToolContext context) {
                        return ToolResult.success("legacy-binary");
                    }
                }
                """);
        Path compiler = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac");
        assertTrue(Files.isExecutable(compiler), "A JDK is required to compile the prior-ABI fixture: " + compiler);
        Path diagnostics = temporary.resolve("compile.log");
        // Keep javac's runtime separate: the full host classpath contains GraalVM build-time filesystem providers.
        var command = List.of(compiler.toString(), "--release", "17", "-proc:none", "-sourcepath", source.toString(),
                "-classpath", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "-d", output.toString(), oldContract.toString(), legacy.toString());
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(diagnostics.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), () -> "Compiler timed out: " + diagnostics);
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(diagnostics); }
                catch (Exception failure) { return failure.toString(); }
            });
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Owned compiler did not terminate");
            }
        }
        byte[] bytecode = Files.readAllBytes(output.resolve("legacy/PreviouslyCompiledTool.class"));
        // Load ONLY the old implementation; the actual current CliTool and its compiler-generated bridge come from parent.
        Class<?> implementation = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadFixture() { return defineClass("legacy.PreviouslyCompiledTool", bytecode, 0, bytecode.length); }
        }.loadFixture();
        CliTool tool = (CliTool) implementation.getConstructor().newInstance();
        assertEquals("legacy-binary", tool.execute(null, null).getOutput());
        ExtensionTool<ToolContext> generic = tool;
        assertEquals("legacy-binary", generic.execute(null, null).getOutput());
        assertEquals("legacy_fixture", generic.id());
        assertFalse(generic.isBackgroundable());
    }
}
