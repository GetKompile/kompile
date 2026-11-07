package ai.kompile.cli.main.extensions.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in acceptance against the actual production CLI image, not a substitute smoke entry point. */
@EnabledIfSystemProperty(named = "kompile.nativeCliExecutable", matches = ".+")
@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessExtensionProductionNativeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancel", "malformed"})
    void productionNativeCliInstallsDiscoversAndCallsIndependentMcpExtension(String mode) throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path cli = Path.of(System.getProperty("kompile.nativeCliExecutable")).toAbsolutePath();
        assertTrue(Files.isExecutable(cli), "Production native CLI is required: " + cli);
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path source = Files.createDirectory(temporary.resolve("package"));
        Path marker = temporary.resolve("worker.started");
        Path journal = temporary.resolve("requests.jsonl");
        Path worker = source.resolve("worker");
        Path compileLog = temporary.resolve("worker-build.log");
        Process compiler = new ProcessBuilder(System.getProperty("kompile.testCCompiler", "cc"),
                "-std=c99", "-Wall", "-Wextra", "-Werror",
                basedir.resolve("src/test/native/process-extension-mcp-worker.c").toString(), "-o", worker.toString())
                .redirectErrorStream(true).redirectOutput(compileLog.toFile()).start();
        try {
            assertTrue(compiler.waitFor(30, TimeUnit.SECONDS), "Fixture compiler timed out");
            assertEquals(0, compiler.exitValue(), () -> contents(compileLog));
        } finally { stop(compiler); }
        ObjectNode manifest = JSON.createObjectNode().put("schemaVersion", 1).put("apiVersion", 1)
                .put("id", "native-mcp").put("version", "1.0").put("timeoutSeconds", 10);
        manifest.putObject("mcp").put("kind", "executable").put("path", "worker").putArray("args")
                .add(marker.toString()).add(journal.toString()).add("literal arg ;not-a-shell");
        manifest.putArray("tools").addObject().put("name", "echo").put("description", "Independent native MCP echo")
                .set("inputSchema", JSON.readTree("""
                        {"type":"object","properties":{"value":{"type":"string"}},
                         "required":["value"],"additionalProperties":false}
                        """));
        Files.writeString(source.resolve("extension.json"), manifest.toString());
        Path installLog = temporary.resolve("install.log");
        run(cli, home, workspace, installLog, "extension", "install", source.toString());
        assertFalse(Files.exists(marker), "Installation must not execute the extension");
        Path inspectLog = temporary.resolve("inspect.log");
        run(cli, home, workspace, inspectLog, "extension", "inspect", "native-mcp", "--json");
        JsonNode inspection = JSON.readTree(Files.readString(inspectLog));
        assertEquals("compiled-and-installed-process", inspection.path("composition").asText());
        assertFalse(inspection.path("runtimeReadinessChecked").asBoolean(true));

        String tool = "mcp__extension_native-mcp__echo";
        Path errorLog = temporary.resolve("mcp.stderr.log");
        Process host = builder(cli, home, workspace, "mcp-stdio", "--work-dir", workspace.toString(),
                "--transcript-id", UUID.randomUUID().toString(), "--profile", "full", "--schema-level", "none")
                .redirectError(errorLog.toFile()).start();
        var readerExecutor = Executors.newSingleThreadExecutor();
        var input = new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8));
        var output = new BufferedWriter(new OutputStreamWriter(host.getOutputStream(), StandardCharsets.UTF_8));
        try {
            JsonNode initialized = request(input, output, readerExecutor, 1, "initialize", JSON.createObjectNode()
                    .put("protocolVersion", "2024-11-05").set("capabilities", JSON.createObjectNode()));
            assertTrue(initialized.path("result").isObject(), () -> initialized + "\n" + contents(errorLog));
            output.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            output.flush();
            JsonNode listed = request(input, output, readerExecutor, 2, "tools/list", JSON.createObjectNode());
            assertTrue(listed.path("result").path("tools").isArray(), () -> listed + "\n" + contents(errorLog));
            boolean found = false;
            for (JsonNode descriptor : listed.path("result").path("tools")) {
                if (tool.equals(descriptor.path("name").asText())) found = true;
            }
            assertTrue(found, () -> "Installed tool missing from production MCP surface\n" + listed + "\n" + contents(errorLog));
            assertFalse(Files.exists(marker), "Tool discovery must not execute the extension");
            ObjectNode arguments = JSON.createObjectNode().put("name", tool);
            arguments.putObject("arguments").put("value", switch (mode) {
                case "cancel" -> "native-mcp-block";
                case "malformed" -> "native-mcp-malformed";
                default -> "native-mcp-input";
            });
            JsonNode called;
            if ("cancel".equals(mode)) {
                ObjectNode frame = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call");
                frame.set("params", arguments);
                output.write(frame.toString());
                output.newLine();
                output.flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((!Files.exists(journal) || !Files.readString(journal).contains("native-mcp-block"))
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(journal) && Files.readString(journal).contains("native-mcp-block"),
                        "Worker did not reach the cancellation checkpoint");
                output.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}\n");
                output.flush();
                long pid = Long.parseLong(Files.readAllLines(marker).get(0));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                        "Cancellation did not terminate its owned extension child");
                // MCP cancellation intentionally suppresses response id=3. A new ping
                // must work without any forbidden late response for the retired call.
                called = request(input, output, readerExecutor, 4, "ping", JSON.createObjectNode());
            } else {
                called = request(input, output, readerExecutor, 3, "tools/call", arguments);
            }
            if ("cancel".equals(mode)) {
                assertFalse(called.has("error"), () -> called + "\n" + contents(errorLog));
                assertTrue(called.path("result").isObject(), "Native MCP host did not remain responsive after cancellation");
            } else if ("success".equals(mode)) {
                assertFalse(called.has("error"), () -> called + "\n" + contents(errorLog));
                assertFalse(called.path("result").path("isError").asBoolean(), () -> called + "\n" + contents(errorLog));
                assertTrue(called.toString().contains("NATIVE_MCP_PLUGIN_RESULT"), () -> called + "\n" + contents(errorLog));
            } else {
                assertTrue(called.has("error") || called.path("result").path("isError").asBoolean(),
                        () -> "Invalid native plugin invocation appeared successful: " + called + "\n" + contents(errorLog));
                if ("malformed".equals(mode)) assertTrue(called.toString().contains("remote outcome unknown"));
            }
        } finally {
            closeRpc(host, readerExecutor, input, output);
        }
        List<String> receipt = Files.readAllLines(marker);
        long childPid = Long.parseLong(receipt.get(0));
        assertEquals("native-mcp", receipt.get(1));
        assertEquals("1", receipt.get(5));
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false), "Extension child survived completed invocation");
        String frames = Files.readString(journal);
        assertTrue(frames.contains("initialize") && frames.contains("tools/list") && frames.contains("tools/call"));
        run(cli, home, workspace, temporary.resolve("remove.log"), "extension", "remove", "native-mcp");
    }

    private static JsonNode request(BufferedReader input, BufferedWriter output,
                                    java.util.concurrent.ExecutorService reader, int id,
                                    String method, JsonNode params) throws Exception {
        return request(input, output, reader, id, method, params, 120_000);
    }

    static JsonNode request(BufferedReader input, BufferedWriter output,
                            java.util.concurrent.ExecutorService reader, int id,
                            String method, JsonNode params, long timeoutMillis) throws Exception {
        ObjectNode frame = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        frame.set("params", params);
        output.write(frame.toString());
        output.newLine();
        output.flush();
        var pending = reader.submit(() -> {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode response = JSON.readTree(line);
                if (response.path("id").asInt(-1) == id) return response;
                if (response.has("id")) throw new IllegalStateException(
                        "Unexpected MCP response id (possibly a forbidden late cancelled response): " + response);
            }
            throw new IllegalStateException("Production MCP host closed stdout before response " + id);
        });
        try { return pending.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }

    static void closeRpc(Process host, java.util.concurrent.ExecutorService reader,
                         BufferedReader input, BufferedWriter output) throws Exception {
        // Closing BufferedReader while readLine holds its lock can deadlock. Terminate
        // the owned process first so EOF releases the blocked reader before closing it.
        try { stop(host); }
        finally { reader.shutdownNow(); }
        assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS), "RPC reader did not stop after host termination");
        input.close();
        output.close();
    }

    private static ProcessBuilder builder(Path cli, Path home, Path workspace, String... args) {
        List<String> command = new ArrayList<>(List.of(cli.toString(), "-Duser.home=" + home));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(workspace.toFile());
        builder.environment().put("HOME", home.toString());
        builder.environment().put("KOMPILE_NATIVE_LIB_DIR", System.getProperty("kompile.nativeCliLibDirectory",
                cli.getParent().resolve("lib").toString()));
        builder.environment().remove("KOMPILE_CUSTOM_MCP_DEPTH");
        return builder;
    }

    private static void run(Path cli, Path home, Path workspace, Path log, String... args) throws Exception {
        Process child = builder(cli, home, workspace, args).redirectErrorStream(false)
                .redirectOutput(log.toFile()).redirectError(log.resolveSibling(log.getFileName() + ".stderr").toFile()).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Native CLI command timed out");
            assertEquals(0, child.exitValue(), () -> contents(log) + "\n" + contents(log.resolveSibling(log.getFileName() + ".stderr")));
        } finally { stop(child); }
    }

    private static void stop(Process child) throws InterruptedException {
        if (child.isAlive()) {
            var descendants = child.descendants().toList();
            child.destroy();
            if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Owned native test process did not terminate");
        }
    }

    private static String contents(Path log) {
        try { return Files.readString(log); }
        catch (Exception failure) { return "Log unavailable: " + failure.getMessage(); }
    }
}
