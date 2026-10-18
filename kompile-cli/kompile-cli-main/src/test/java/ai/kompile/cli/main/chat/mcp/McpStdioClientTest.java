/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disposable Java-only child: no application server, database, model or provider. */
@TemporaryUserHome
@Timeout(20)
class McpStdioClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String transcriptId = "stdio-client-test-" + UUID.randomUUID();

    @TempDir
    Path workspace;

    @Test
    void toolChildrenDoNotInheritTheirBrowserHostsProductIdentity() {
        var environment = new HashMap<>(Map.of("KOMPILE_CHAT_PRODUCT_PROFILE", "/installed/policy.json",
                "KOMPILE_CHAT_HANDOFF_WORKING_DIRECTORY", "/parent/play", "NORMAL", "keep"));
        McpStdioClient.configureEnvironment(environment, Map.of("ELTHORIA_WORKSPACE", "/approved/play"));
        assertFalse(environment.containsKey("KOMPILE_CHAT_PRODUCT_PROFILE"));
        assertFalse(environment.containsKey("KOMPILE_CHAT_HANDOFF_WORKING_DIRECTORY"));
        assertEquals("keep", environment.get("NORMAL"));
        assertEquals("/approved/play", environment.get("ELTHORIA_WORKSPACE"));
    }

    @Test
    void retainedTimeoutKeepsActualChildAliveAndLateResponseCannotRecoverClient() throws Exception {
        try (McpStdioClient client = startRetainingClient("late")) {
            assertStickyRetention(client, true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-result", "malformed-json", "null-json", "both-fields", "wrong-id"})
    void retainedInvalidResponseKeepsChildAliveAndPermanentlyPoisonsClient(String mode) throws Exception {
        try (McpStdioClient client = startRetainingClient(mode)) {
            assertStickyRetention(client, false);
        }
    }

    private void assertStickyRetention(McpStdioClient client, boolean expectTimeout) throws Exception {
        client.initialize();
        assertTrue(client.listTools().isEmpty());
        ProcessHandle child = ProcessHandle.of(client.childProcessPid()).orElseThrow();
        Thread stdoutReader = readerThread("mcp-stdio-reader-" + child.pid());
        Thread stderrReader = readerThread("mcp-stderr-reader-" + child.pid());
        BlockingQueue<?> bufferedResponses = bufferedResponses(client);

        IOException first = assertThrows(IOException.class,
                () -> client.callTool("blocked", MAPPER.createObjectNode()));
        if (expectTimeout) assertTrue(first.getMessage().contains("Timed out"), first.toString());
        await(() -> Files.exists(workspace.resolve("call-started")));
        assertTrue(child.isAlive(), "an uncertain tool outcome must not kill the actual child");
        assertTrue(stdoutReader.isAlive());
        assertTrue(stderrReader.isAlive());
        assertAllRpcsRefused(client, first);
        assertTrue(bufferedResponses.isEmpty());

        // The fixture, not a cancelled Future, controls when the actual response is sent.
        Files.writeString(workspace.resolve("release"), "release");
        await(() -> Files.exists(workspace.resolve("late-response")));
        await(() -> !bufferedResponses.isEmpty()); // Reply reached the actual client reader.
        assertTrue(child.isAlive());
        assertTrue(stdoutReader.isAlive(), "late output must still have a live bounded reader");
        assertTrue(stderrReader.isAlive());
        assertAllRpcsRefused(client, first);

        // Child acknowledges a pipe-drain checkpoint after every attempted follow-up RPC.
        // Its actual received-request journal must contain only the original exchange.
        Files.writeString(workspace.resolve("checkpoint"), "check");
        await(() -> Files.exists(workspace.resolve("checked")));
        List<String> methods = new ArrayList<>();
        for (String line : Files.readAllLines(workspace.resolve("checked-requests.jsonl"))) {
            methods.add(MAPPER.readTree(line).path("method").asText());
        }
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"), methods);

        Path lifecycleLog = LogPaths.ensureTranscriptDirectory(transcriptId).toPath()
                .resolve("mcp/bundle-fixture.stderr.log");
        client.close();
        client.close();
        await(() -> !child.isAlive() && !stdoutReader.isAlive() && !stderrReader.isAlive());
        assertEquals(1L, Files.readAllLines(lifecycleLog).stream()
                // McpStderrLogger prefixes every diagnostic line with its timestamp.
                .map(line -> line.replaceFirst("^\\[[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]+\\] ", ""))
                .filter(line -> line.startsWith("[kompile-parent] closed ")).count(),
                "explicit close must clean up only once");
        assertThrows(IOException.class, () -> client.callTool("after-close", null));
    }

    private static void assertAllRpcsRefused(McpStdioClient client, IOException first) {
        IOException call = assertThrows(IOException.class, () -> client.callTool("must-not-run", null));
        IOException list = assertThrows(IOException.class, client::listTools);
        IOException initialize = assertThrows(IOException.class, client::initialize);
        for (IOException refused : List.of(call, list, initialize)) {
            assertTrue(refused.getMessage().contains("poisoned"), refused.toString());
            assertSame(first, refused.getCause(), "the first uncertainty must remain sticky");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryConstructorsStillTerminateOnTimeout(boolean withLogContext) throws Exception {
        String java = javaCommand();
        List<String> args = fixtureArgs("late");
        try (McpStdioClient client = withLogContext
                ? new McpStdioClient(MAPPER, java, args, Map.of(), workspace, 1,
                        "fixture", transcriptId)
                : new McpStdioClient(MAPPER, java, args, Map.of(), workspace, 1)) {
            await(() -> Files.exists(workspace.resolve("ready")));
            client.initialize();
            ProcessHandle child = ProcessHandle.of(client.childProcessPid()).orElseThrow();
            IOException failure = assertThrows(IOException.class, () -> client.callTool("blocked", null));
            assertTrue(failure.getMessage().contains("Timed out"), failure.toString());
            await(() -> !child.isAlive());
            assertFalse(child.isAlive(), "ordinary timeout must retain its terminating behavior");
        }
    }

    @Test
    void completedBusinessErrorDoesNotPoisonRetainingClient() throws Exception {
        try (McpStdioClient client = startRetainingClient("business-error")) {
            client.initialize();
            assertTrue(client.callTool("first", null).path("isError").asBoolean());
            assertTrue(client.callTool("second", null).path("isError").asBoolean());
            assertTrue(client.listTools().isEmpty());
            assertTrue(ProcessHandle.of(client.childProcessPid()).orElseThrow().isAlive());
        }
    }

    @Test
    void productCloseConfirmsOnlyActualCooperativeEofExit() throws Exception {
        McpStdioClient client = startRetainingClient("cooperative-eof");
        ProcessHandle child = ProcessHandle.of(client.childProcessPid()).orElseThrow();
        client.close();
        assertTrue(client.cleanupConfirmed());
        assertFalse(child.isAlive());
        client.close(); assertTrue(client.cleanupConfirmed());
    }

    @Test
    void productForcedShutdownCannotClaimCooperativeCleanup() throws Exception {
        McpStdioClient client = startRetainingClient("ignore-eof");
        ProcessHandle child = ProcessHandle.of(client.childProcessPid()).orElseThrow();
        client.close();
        assertFalse(client.cleanupConfirmed());
        await(() -> !child.isAlive());
    }

    private McpStdioClient startRetainingClient(String mode) throws Exception {
        McpStdioClient client = new McpStdioClient(MAPPER, javaCommand(), fixtureArgs(mode),
                Map.of(), workspace, 1, "fixture", transcriptId, true);
        try {
            await(() -> Files.exists(workspace.resolve("ready")));
            return client;
        } catch (Exception | AssertionError failure) {
            client.close();
            throw failure;
        }
    }

    private List<String> fixtureArgs(String mode) {
        // Same disposable-JVM convention as the existing stdio transport regression fixture,
        // but launch only this tiny test child, never MainCommand or application services.
        return List.of("-Xmx64m", "-XX:ActiveProcessorCount=2",
                "-Duser.home=" + System.getProperty("user.home"), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FixtureChild.class.getName(), workspace.toString(), mode);
    }

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static Thread readerThread(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> name.equals(thread.getName())).findFirst().orElseThrow();
    }

    private static BlockingQueue<?> bufferedResponses(McpStdioClient client)
            throws ReflectiveOperationException {
        // Observation only: do not consume events or add a production testing API.
        var field = McpStdioClient.class.getDeclaredField("readerEvents");
        field.setAccessible(true);
        return (BlockingQueue<?>) field.get(client);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "fixture condition was not reached within five seconds");
    }

    /** Standalone child with deterministic release/checkpoint files and an actual RPC journal. */
    public static final class FixtureChild {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            String mode = args[1];
            Path requests = directory.resolve("requests.jsonl");
            Files.writeString(requests, "");
            MAPPER.readTree("{}"); // Finish mapper/class initialization before reporting ready.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                Files.writeString(directory.resolve("ready"), "ready");
                while (System.nanoTime() < deadline) {
                    if (!input.ready() && !mode.equals("cooperative-eof") && !mode.equals("ignore-eof")) {
                        if (Files.exists(directory.resolve("checkpoint"))
                                && !Files.exists(directory.resolve("checked"))) {
                            Files.writeString(directory.resolve("checked-requests.jsonl"), Files.readString(requests));
                            Files.writeString(directory.resolve("checked"), "checked");
                        }
                        Thread.sleep(10);
                        continue;
                    }
                    String line = input.readLine();
                    if (line == null) {
                        if (mode.equals("ignore-eof")) new java.util.concurrent.CountDownLatch(1).await();
                        return;
                    }
                    Files.writeString(requests, line + "\n", StandardOpenOption.APPEND);
                    JsonNode request = MAPPER.readTree(line);
                    String method = request.path("method").asText();
                    if (!request.has("id")) continue;
                    ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", request.path("id"));
                    if ("initialize".equals(method)) {
                        response.putObject("result").put("protocolVersion", "2024-11-05");
                    } else if ("tools/list".equals(method)) {
                        response.putObject("result").putArray("tools");
                    } else if ("business-error".equals(mode)) {
                        response.putObject("result").put("isError", true).putArray("content");
                    } else {
                        if (!"late".equals(mode)) {
                            switch (mode) {
                                case "malformed-json" -> send("{invalid-json");
                                case "null-json" -> send("null");
                                case "both-fields" -> {
                                    response.putObject("result");
                                    response.putObject("error").put("code", -32603);
                                    send(response.toString());
                                    response.remove("error");
                                }
                                case "wrong-id" -> {
                                    ObjectNode wrong = response.deepCopy();
                                    wrong.put("id", request.path("id").asLong() + 100);
                                    wrong.putObject("result");
                                    send(wrong.toString());
                                }
                                default -> send(response.toString());
                            }
                        }
                        Files.writeString(directory.resolve("call-started"), "started");
                        while (!Files.exists(directory.resolve("release"))) {
                            if (System.nanoTime() >= deadline) return;
                            Thread.sleep(10);
                        }
                        response.putObject("result").put("isError", false).putArray("content");
                        send(response.toString());
                        // This valid late reply is evidence of output only, not recovery.
                        Files.writeString(directory.resolve("late-response"), "sent");
                        continue;
                    }
                    send(response.toString());
                }
            }
        }

        private static void send(String line) {
            System.out.println(line);
            System.out.flush();
        }
    }
}
