/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.extensions.process.ProcessExtensionManifest;
import ai.kompile.cli.main.extensions.process.ProcessExtensionStore.InstalledExtension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real disposable Java MCP fixtures only: no application, model, shell, or external service. */
@Timeout(20)
class ProcessExtensionMcpBridgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;
    private final TrackingRegistry registry = new TrackingRegistry();

    @AfterEach
    void removeFixtureProcessesOnAssertionFailure() throws Exception {
        // Only exact PIDs written by this test's own fixtures; never enumerate unrelated processes.
        for (String file : List.of("descendant.pid", "child.pid")) {
            Path marker = workspace.resolve(file);
            if (Files.exists(marker)) {
                ProcessHandle.of(Long.parseLong(Files.readString(marker).trim()))
                        .filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void descriptorConstructionAndSchemaLookupAreLazyAndDefensive() throws Exception {
        var installed = installed("success", 5, "echo"); // fixture.jar deliberately does not exist
        CliTool tool = proxy(installed);
        assertEquals("mcp__extension_bridge-fixture__echo", tool.id());
        assertEquals("Fixture echo", tool.description());
        assertEquals("mcp.extension-bridge-fixture", tool.permissionKey());
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        ((ObjectNode) tool.parameterSchema()).put("mutated", true);
        assertFalse(tool.parameterSchema().has("mutated"));
        assertFalse(Files.exists(workspace.resolve("child.pid")));
        assertFalse(Files.exists(installed.directory()));
    }

    @Test
    void deniedPermissionNeverResolvesOrLaunchesTarget() throws Exception {
        CliTool tool = proxy(installed("success", 5, "echo"));
        ToolContext denied = context(false);
        var failure = assertThrows(ToolExecutionException.class, () -> tool.execute(JSON.createObjectNode(), denied));
        assertTrue(failure.getMessage().contains("Permission denied"));
        assertFalse(Files.exists(workspace.resolve("child.pid")));
        assertEquals(0, registry.unknown.get());
    }

    @Test
    void shellMandateChecksResolvedPublicToolBeforeLaunch() throws Exception {
        CliTool tool = proxy(installed("success", 5, "bash"));
        assertTrue(tool.execute(JSON.createObjectNode().put("command", "cat input.txt"), context(true)).isError());
        assertFalse(Files.exists(workspace.resolve("child.pid")));
        assertEquals(0, registry.unknown.get());
    }

    @Test
    void alreadyAbortedContextNeverLaunches() throws Exception {
        CliTool tool = proxy(installed("success", 5, "echo"));
        ToolContext context = context(true);
        context.abort();
        assertThrows(ToolExecutionException.class, () -> tool.execute(JSON.createObjectNode(), context));
        assertFalse(Files.exists(workspace.resolve("child.pid")));
        assertEquals(0, registry.unknown.get());
    }

    @Test
    void missingDeclaredToolClosesChildBeforeAnyCall() throws Exception {
        assertRejectedCatalog("missing", false);
    }

    @Test
    void schemaMismatchClosesChildBeforeAnyCall() throws Exception {
        assertRejectedCatalog("schema-mismatch", false);
    }

    @Test
    void validatesEveryManifestExportNotOnlyInvokedTool() throws Exception {
        assertRejectedCatalog("success", true);
    }

    private void assertRejectedCatalog(String mode, boolean anotherDeclaration) throws Exception {
        var installed = fixture(mode, 5);
        if (anotherDeclaration) {
            ObjectNode manifest = manifest(mode, 5, "echo");
            ObjectNode second = manifest.withArray("tools").addObject().put("name", "second")
                    .put("description", "Another declared export");
            second.set("inputSchema", schema());
            installed = new InstalledExtension(ProcessExtensionManifest.parse(manifest), installed.directory());
        }
        CliTool tool = proxy(installed);
        assertThrows(ToolExecutionException.class, () -> tool.execute(JSON.createObjectNode(), context(true)));
        assertFalse(called());
        assertEquals(0, registry.unknown.get(), "catalog failure is known not to have invoked the action");
        assertDead("child.pid");
    }

    @Test
    void successUsesFixedNameLiteralArgvContextAndEnvironmentAndCleansDescendants() throws Exception {
        var installed = fixture("success-descendant", 5);
        JsonNode params = JSON.createObjectNode().put("name", "undeclared").put("value", "literal value");
        var result = proxy(installed).execute(params, context(true));
        assertFalse(result.isError());
        assertEquals("done", result.getOutput());
        assertEquals("{\"status\":\"unknown\"}", result.getMetadata().get("structuredContent"));
        assertEquals(List.of("image:image/png;base64,AA=="), result.getMetadata().get("attachments"));
        assertEquals(0, registry.unknown.get(), "data saying 'unknown' is not execution uncertainty");
        JsonNode request = requests().stream().filter(row -> "tools/call".equals(row.path("method").asText()))
                .findFirst().orElseThrow();
        assertEquals("echo", request.path("params").path("name").asText());
        assertEquals(params, request.path("params").path("arguments"));
        JsonNode launch = JSON.readTree(Files.readString(workspace.resolve("launch.json")));
        assertEquals("bridge-fixture", launch.path("id").asText());
        assertEquals("1.0", launch.path("version").asText());
        assertEquals("1", launch.path("depth").asText());
        assertEquals(installed.directory().toString(), launch.path("root").asText());
        assertEquals(workspace.resolve("cwd").toRealPath().toString(), launch.path("cwd").asText());
        assertEquals("literal arg ;not-a-shell", launch.path("literal").asText());
        assertFalse(launch.path("transcript").asText().isBlank());
        assertDead("child.pid");
        assertDead("descendant.pid");
        assertNoWatchdog();
    }

    @Test
    void repeatedCallsUseFreshChildrenAndHandshakes() throws Exception {
        CliTool tool = proxy(fixture("success", 5));
        assertFalse(tool.execute(JSON.createObjectNode(), context(true)).isError());
        String first = Files.readString(workspace.resolve("child.pid"));
        assertDead("child.pid");
        assertFalse(tool.execute(JSON.createObjectNode(), context(true)).isError());
        assertNotEquals(first, Files.readString(workspace.resolve("child.pid")));
        assertEquals(2, requests().stream().filter(row -> "initialize".equals(row.path("method").asText())).count());
        assertEquals(2, requests().stream().filter(row -> "tools/list".equals(row.path("method").asText())).count());
        assertEquals(2, requests().stream().filter(row -> "tools/call".equals(row.path("method").asText())).count());
        assertDead("child.pid");
        assertNoWatchdog();
    }

    @Test
    void remoteBusinessErrorIsCompletedNotTransportUnknown() throws Exception {
        var result = proxy(fixture("business-error", 5)).execute(JSON.createObjectNode(), context(true));
        assertTrue(result.isError());
        assertEquals("business rejected", result.getOutput());
        assertEquals(0, registry.unknown.get());
        assertDead("child.pid");
    }

    @Test
    void transportFailureAfterDispatchMarksUnknownEvenThoughChildIsKilled() throws Exception {
        CliTool tool = proxy(fixture("broken-call", 5));
        var failure = assertThrows(ToolExecutionException.class,
                () -> tool.execute(JSON.createObjectNode(), context(true)));
        assertTrue(failure.getMessage().contains("remote outcome unknown"));
        assertTrue(called());
        assertEquals(1, registry.unknown.get());
        assertDead("child.pid");
    }

    @Test
    void initializationTransportFailureIsKnownNotToHaveInvokedAction() throws Exception {
        CliTool tool = proxy(fixture("broken-initialize", 5));
        assertThrows(ToolExecutionException.class, () -> tool.execute(JSON.createObjectNode(), context(true)));
        assertFalse(called());
        assertEquals(0, registry.unknown.get());
        assertDead("child.pid");
    }

    @Test
    void timeoutUnblocksAndCleansOwnedChildAndDescendantWithoutClaimingRollback() throws Exception {
        CliTool tool = proxy(fixture("block", 3));
        assertThrows(ToolExecutionException.class, () -> tool.execute(JSON.createObjectNode(), context(true)));
        assertTrue(called(), "fixture must reach tools/call, not merely time out during startup");
        assertEquals(1, registry.unknown.get());
        assertFalse(Thread.currentThread().isInterrupted(), "watchdog wakeup must not poison the next tool");
        assertDead("child.pid");
        assertDead("descendant.pid");
        assertNoWatchdog();
    }

    @Test
    void contextCancellationUnblocksAndCleansOwnedTree() throws Exception {
        assertCancellation("block", JSON.createObjectNode(), "called");
    }

    @Test
    void cancellationAlsoUnblocksAPipeWriteWhenServerStopsReading() throws Exception {
        assertCancellation("not-reading", JSON.createObjectNode().put("value", "x".repeat(512 * 1024)), "not-reading");
    }

    private void assertCancellation(String mode, JsonNode params, String checkpoint) throws Exception {
        CliTool tool = proxy(fixture(mode, 30));
        ToolContext context = context(true);
        var executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            var pending = executor.submit(() -> {
                worker.set(Thread.currentThread());
                return tool.execute(params, context);
            });
            awaitFile(checkpoint);
            awaitFile("descendant.pid");
            if ("not-reading".equals(mode)) awaitBlockedWrite(worker.get());
            context.abort();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ToolExecutionException.class, failure.getCause());
            assertEquals(1, registry.unknown.get());
            assertDead("child.pid");
            assertDead("descendant.pid");
            assertNoWatchdog();
        } finally {
            context.abort();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void malformedCallResultsNeverEstablishACompletedOutcome() throws Exception {
        int expectedUnknown = 0;
        for (String mode : List.of("null-result", "scalar-result", "missing-content", "bad-error", "bad-content")) {
            var installed = fixture(mode, 5);
            var failure = assertThrows(ToolExecutionException.class,
                    () -> proxy(installed).execute(JSON.createObjectNode(), context(true)));
            assertTrue(failure.getMessage().contains("remote outcome unknown"));
            assertTrue(called());
            assertEquals(++expectedUnknown, registry.unknown.get());
            assertDead("child.pid");
        }
    }

    @Test
    void malformedAdvertisedSchemaCannotMatchAnEmptyManifestSchema() throws Exception {
        var installed = fixture("missing-schema", 5);
        ObjectNode metadata = manifest("missing-schema", 5, "echo");
        ((ObjectNode) metadata.path("tools").get(0)).set("inputSchema", JSON.createObjectNode());
        var empty = new InstalledExtension(ProcessExtensionManifest.parse(metadata), installed.directory());
        assertThrows(ToolExecutionException.class, () -> proxy(empty).execute(JSON.createObjectNode(), context(true)));
        assertFalse(called());
        assertDead("child.pid");
    }

    private InstalledExtension installed(String mode, int timeout, String toolName) {
        return new InstalledExtension(ProcessExtensionManifest.parse(manifest(mode, timeout, toolName)),
                workspace.resolve("package"));
    }

    private ObjectNode manifest(String mode, int timeout, String toolName) {
        ObjectNode node = JSON.createObjectNode().put("schemaVersion", 1).put("apiVersion", 1)
                .put("id", "bridge-fixture").put("version", "1.0").put("timeoutSeconds", timeout);
        node.putObject("mcp").put("kind", "jar").put("path", "fixture.jar").putArray("args")
                .add(workspace.toString()).add(mode).add("literal arg ;not-a-shell");
        node.putArray("tools").addObject().put("name", toolName).put("description", "Fixture " + toolName)
                .set("inputSchema", schema());
        return node;
    }

    private static JsonNode schema() { return JSON.createObjectNode().put("type", "object"); }

    private CliTool proxy(InstalledExtension installed) {
        return ProcessExtensionMcpBridge.tool(installed, installed.manifest().tools().get(0), JSON, javaCommand());
    }

    private InstalledExtension fixture(String mode, int timeout) throws Exception {
        var installed = installed(mode, timeout, "echo");
        Files.createDirectories(installed.directory());
        Manifest jarManifest = new Manifest();
        jarManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        jarManifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FixtureChild.class.getName());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        jarManifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
                Arrays.stream(classpath.split(java.io.File.pathSeparator))
                        .map(entry -> Path.of(entry).toAbsolutePath().toUri().toString())
                        .collect(Collectors.joining(" ")));
        try (var jar = new JarOutputStream(Files.newOutputStream(installed.directory().resolve("fixture.jar")), jarManifest)) {
            for (Class<?> fixtureClass : List.of(FixtureChild.class, FixtureDescendant.class)) {
                String resource = fixtureClass.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(resource));
                try (var input = fixtureClass.getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(input, resource);
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
        return installed;
    }

    private ToolContext context(boolean allow) throws Exception {
        Files.createDirectories(workspace.resolve("cwd"));
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("mcp.extension-bridge-fixture", allow
                ? PermissionService.PermissionLevel.ALLOW : PermissionService.PermissionLevel.DENY);
        return new ToolContext("process-extension-bridge-test", null, permissions, workspace.resolve("cwd"), registry);
    }

    private List<JsonNode> requests() throws Exception {
        Path journal = workspace.resolve("requests.jsonl");
        if (!Files.exists(journal)) return List.of();
        try (var lines = Files.lines(journal)) {
            return lines.filter(line -> !line.isBlank()).map(line -> {
                try { return JSON.readTree(line); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
        }
    }

    private boolean called() { return Files.exists(workspace.resolve("called")); }

    private void awaitFile(String name) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(workspace.resolve(name)) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(Files.exists(workspace.resolve(name)), "missing fixture marker: " + name);
    }

    private static void awaitBlockedWrite(Thread worker) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!isWritingToolCall(worker) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(isWritingToolCall(worker), "fixture must block the native pipe write before cancellation");
    }

    private static boolean isWritingToolCall(Thread worker) {
        var stack = Arrays.asList(worker.getStackTrace());
        return stack.stream().anyMatch(frame -> McpStdioClient.class.getName().equals(frame.getClassName())
                && "callTool".equals(frame.getMethodName()))
                && stack.stream().anyMatch(frame -> "writeBytes".equals(frame.getMethodName())
                        || "write0".equals(frame.getMethodName()));
    }

    private void assertDead(String marker) throws Exception {
        awaitFile(marker);
        long pid = Long.parseLong(Files.readString(workspace.resolve(marker)).trim());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until)
            Thread.sleep(10);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "surviving fixture PID " + pid);
    }

    private void assertNoWatchdog() throws Exception {
        String name = "extension-mcp-watchdog-" + Files.readString(workspace.resolve("child.pid")).trim();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && name.equals(thread.getName())));
    }

    private static String javaCommand() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static final class TrackingRegistry extends ToolRegistry {
        private final AtomicInteger unknown = new AtomicInteger();
        TrackingRegistry() { super(JSON); }
        @Override public void markProductUnknownOutcome() {
            unknown.incrementAndGet();
            super.markProductUnknownOutcome();
        }
    }

    /** Packaged as a tiny executable fixture JAR by the test, using the normal Java/MCP boundary. */
    public static final class FixtureChild {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        public static void main(String[] args) throws Exception {
            Path workspace = Path.of(args[0]);
            String mode = args[1];
            Files.writeString(workspace.resolve("child.pid"), Long.toString(ProcessHandle.current().pid()));
            Files.writeString(workspace.resolve("launch.json"), MAPPER.createObjectNode()
                    .put("id", System.getenv("KOMPILE_EXTENSION_ID"))
                    .put("version", System.getenv("KOMPILE_EXTENSION_VERSION"))
                    .put("root", System.getenv("KOMPILE_EXTENSION_ROOT"))
                    .put("depth", System.getenv(McpBundleToolLoader.CUSTOM_MCP_DEPTH_ENV))
                    .put("transcript", System.getenv("KOMPILE_TRANSCRIPT_UUID"))
                    .put("cwd", Path.of("").toRealPath().toString()).put("literal", args[2]).toString());
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    Files.writeString(workspace.resolve("requests.jsonl"), line + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    JsonNode request = MAPPER.readTree(line);
                    if (!request.has("id")) continue;
                    String method = request.path("method").asText();
                    ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", request.get("id"));
                    if ("initialize".equals(method)) {
                        if ("broken-initialize".equals(mode)) { send("{bad-json"); continue; }
                        response.putObject("result").put("protocolVersion", "2024-11-05").putObject("capabilities").putObject("tools");
                    } else if ("tools/list".equals(method)) {
                        var tools = response.putObject("result").putArray("tools");
                        if (!"missing".equals(mode)) {
                            ObjectNode echo = tools.addObject().put("name", "echo");
                            if (!"missing-schema".equals(mode)) echo.putObject("inputSchema")
                                    .put("type", "schema-mismatch".equals(mode) ? "string" : "object");
                        }
                        tools.addObject().put("name", "undeclared").putObject("inputSchema").put("type", "object");
                    } else if ("tools/call".equals(method)) {
                        if ("block".equals(mode) || "success-descendant".equals(mode)) {
                            spawnDescendant(workspace);
                        }
                        Files.writeString(workspace.resolve("called"), "called");
                        if ("block".equals(mode)) { new CountDownLatch(1).await(); return; }
                        if ("broken-call".equals(mode)) { send("{bad-json"); continue; }
                        ObjectNode result = response.putObject("result").put("isError", "business-error".equals(mode));
                        var content = result.putArray("content");
                        content.addObject().put("type", "text").put("text", "business-error".equals(mode) ? "business rejected" : "done");
                        if (!"business-error".equals(mode)) {
                            content.addObject().put("type", "image").put("mimeType", "image/png").put("data", "AA==");
                            result.putObject("structuredContent").put("status", "unknown");
                        }
                        if ("null-result".equals(mode)) response.putNull("result");
                        if ("scalar-result".equals(mode)) response.put("result", "not a CallToolResult");
                        if ("missing-content".equals(mode)) result.remove("content");
                        if ("bad-error".equals(mode)) result.put("isError", "false");
                        if ("bad-content".equals(mode)) result.putArray("content").addNull();
                    } else throw new IllegalStateException("Unexpected fixture method: " + method);
                    send(response.toString());
                    if ("not-reading".equals(mode) && "tools/list".equals(method)) {
                        spawnDescendant(workspace);
                        Files.writeString(workspace.resolve("not-reading"), "not-reading");
                        new CountDownLatch(1).await();
                        return;
                    }
                }
            }
        }
        private static void spawnDescendant(Path workspace) throws Exception {
            Process descendant = new ProcessBuilder(javaExecutable(), "-Xmx32m", "-cp",
                    System.getProperty("java.class.path"), FixtureDescendant.class.getName(), workspace.toString()).start();
            Files.writeString(workspace.resolve("descendant.pid"), Long.toString(descendant.pid()));
        }
        private static String javaExecutable() {
            String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", name).toString();
        }
        private static void send(String response) { System.out.println(response); System.out.flush(); }
    }

    public static final class FixtureDescendant {
        public static void main(String[] args) throws Exception { new CountDownLatch(1).await(); }
    }
}
