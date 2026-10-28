/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@code ChatMessageHandler} applies a failover decision: a typed,
 * replay-safe out-of-quota failure climbs the session ladder and re-dispatches
 * the same turn on another model, while everything that must not switch
 * (a policy refusal, an unreplayable turn, a disabled ladder) falls through to
 * the existing wait-for-reset watchdog untouched.
 *
 * <p>These drive the real {@code ChatRepl}/{@code ChatMessageHandler} against a
 * local HTTP provider, so they cover the wiring; the ladder's tiering and
 * cooldown rules are covered by {@code SessionModelLadderTest}.</p>
 */
class ChatMessageHandlerFailoverTest {

    private String originalHome;
    private java.nio.file.Path fakeHome;

    @BeforeEach
    void isolateConfigHome(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        originalHome = System.getProperty("user.home");
        fakeHome = tmp;
        System.setProperty("user.home", tmp.toString());
        java.nio.file.Files.createDirectories(tmp.resolve(".kompile/conversations"));
    }

    @org.junit.jupiter.api.AfterEach
    void restoreHome() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
    }

    /** Write the session's ladder file, the layer {@link SessionModelLadder} loads last. */
    private void ladder(String sessionId, String rungModel, String extra) throws Exception {
        java.nio.file.Path file = fakeHome.resolve(".kompile/conversations/" + sessionId + ".model-ladder.json");
        java.nio.file.Files.writeString(file, "{\"enabled\":true,\"rungs\":[{\"model\":\""
                + rungModel + "\"}]" + (extra == null ? "" : "," + extra) + "}");
    }

    @Test
    void quotaExhaustionSwitchesToTheNextLadderRung() throws Exception {
        String session = "failover-switch-" + System.nanoTime();
        ladder(session, "backup-model", null);
        try (QuotaThenOk provider = new QuotaThenOk()) {
            ChatRepl repl = newRepl(session, provider);
            try {
                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(provider.succeeded.await(10, TimeUnit.SECONDS),
                        "the turn was never replayed on the backup rung");
                assertTrue(await(() -> !repl.isLlmBusy(), 5),
                        "the failover turn did not finish");
                assertEquals(2, provider.requests.get(),
                        "expected one rejected attempt plus one retry on the backup");
                assertEquals("backup-model", provider.models.get(provider.models.size() - 1),
                        "the retry must name the configured rung");
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void justSavedConfigurationIsUsedOnTheVeryNextTurn() throws Exception {
        String session = "failover-fresh-" + System.nanoTime();
        try (QuotaThenOk provider = new QuotaThenOk()) {
            ChatRepl repl = newRepl(session, provider);
            try {
                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                Method load = ChatMessageHandler.class.getDeclaredMethod("failoverLadder");
                load.setAccessible(true);
                assertTrue(((SessionModelLadder) load.invoke(handler)).isEmpty());
                ladder(session, "backup-model", null);
                handler.handleChatMessage("do the work");
                assertTrue(provider.succeeded.await(10, TimeUnit.SECONDS));
                assertTrue(await(() -> !repl.isLlmBusy(), 5));
                assertEquals("backup-model", repl.getChatConfig().getModel());
            } finally { close(repl); }
        }
    }

    @Test
    void theConfiguredPrimaryIsNotPersistedByAFailover() throws Exception {
        String session = "failover-primary-" + System.nanoTime();
        ladder(session, "backup-model", null);
        try (QuotaThenOk provider = new QuotaThenOk()) {
            ChatRepl repl = newRepl(session, provider);
            try {
                // The constructor binds the session and persists the chosen
                // primary, so there IS a real baseline to protect.
                java.nio.file.Path sessionFile = ChatConfig.sessionConfigPath(session);
                assertTrue(java.nio.file.Files.exists(sessionFile),
                        "the session config should exist before the turn");
                String before = java.nio.file.Files.readString(sessionFile);
                assertEquals("primary-model", ChatConfig.loadSession(session).getModel());

                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(provider.succeeded.await(10, TimeUnit.SECONDS));
                assertTrue(await(() -> !repl.isLlmBusy(), 5));

                // The live session moved to the rung...
                assertEquals("backup-model", repl.getChatConfig().getModel());
                // ...but the user's chosen primary survives verbatim on disk.
                assertEquals(before, java.nio.file.Files.readString(sessionFile),
                        "a failover must never rewrite the session config");
                assertEquals("primary-model", ChatConfig.loadSession(session).getModel(),
                        "the chosen primary must still be what a new turn starts from");
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void aPolicyRefusalNeverSwitches() throws Exception {
        String session = "failover-refusal-" + System.nanoTime();
        ladder(session, "backup-model", null);
        try (RefusingProvider provider = new RefusingProvider()) {
            ChatRepl repl = newRepl(session, provider);
            try {
                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(await(() -> !repl.isLlmBusy(), 10));
                assertEquals(1, provider.requests.get(),
                        "switching models to dodge a safety refusal is circumvention");
                assertFalse("backup-model".equals(repl.getChatConfig().getModel()));
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void anEmptyLadderCannotFailOver() throws Exception {
        String session = "failover-empty-" + System.nanoTime();
        try (QuotaThenOk provider = new QuotaThenOk()) {
            ChatRepl repl = newRepl(session, provider);
            try {
                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(await(() -> !repl.isLlmBusy(), 10));
                assertEquals(1, provider.requests.get(), "no rungs means no retry");
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void failoverContinuesAfterToolsWithoutExecutingThemTwice() throws Exception {
        String session = "failover-tools-" + System.nanoTime();
        ladder(session, "backup-model", null);
        try (QuotaThenOk provider = new QuotaThenOk(1)) {
            ChatRepl repl = newRepl(session, provider);
            AtomicInteger executions = new AtomicInteger();
            field(repl, "toolRegistry", ai.kompile.cli.main.chat.tools.ToolRegistry.class).register(
                    new ai.kompile.cli.main.chat.tools.CliTool() {
                        public String id() { return "failover_probe"; }
                        public String permissionKey() { return "read"; }
                        public String description() { return "Regression probe"; }
                        public com.fasterxml.jackson.databind.JsonNode parameterSchema() {
                            return ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("type", "object");
                        }
                        public ai.kompile.cli.main.chat.tools.ToolResult execute(
                                com.fasterxml.jackson.databind.JsonNode args,
                                ai.kompile.cli.main.chat.tools.ToolContext context) {
                            executions.incrementAndGet();
                            return ai.kompile.cli.main.chat.tools.ToolResult.success("TOOL_COMPLETED_ONCE");
                        }
                    });
            try {
                field(repl, "messageHandler", ChatMessageHandler.class).handleChatMessage("do the work");
                assertTrue(provider.succeeded.await(10, TimeUnit.SECONDS));
                assertTrue(await(() -> !repl.isLlmBusy(), 5));
                assertEquals(3, provider.requests.get());
                assertEquals(1, executions.get(), "completed side effects must not be replayed");
                var messages = ai.kompile.cli.common.util.JsonUtils.standardMapper()
                        .readTree(provider.bodies.get(2)).path("messages");
                int users = 0, results = 0;
                for (var entry : messages) {
                    if (entry.path("role").asText().equals("user") && entry.path("content").asText().contains("do the work")) users++;
                    if (entry.path("role").asText().equals("tool") && entry.path("content").asText().contains("TOOL_COMPLETED_ONCE")) results++;
                }
                assertEquals(1, users, "the initial task must not be appended twice");
                assertEquals(1, results, "the backup must receive the completed tool result once");
            } finally { close(repl); }
        }
    }

    @Test
    void oneTurnCannotExceedTheConfiguredSwitchBudget() throws Exception {
        String session = "failover-budget-" + System.nanoTime();
        ladder(session, "backup-model", "\"maxRungsPerTurn\":1,\"rungs\":[{\"model\":\"backup-model\"},{\"model\":\"third-model\"}]");
        try (QuotaThenOk provider = new QuotaThenOk(2)) {
            ChatRepl repl = newRepl(session, provider);
            try {
                field(repl, "messageHandler", ChatMessageHandler.class).handleChatMessage("do the work");
                assertTrue(await(() -> provider.requests.get() >= 2, 10));
                assertTrue(await(() -> !repl.isLlmBusy(), 5));
                assertEquals(2, provider.requests.get(), "exhaustion must not redispatch a fresh turn budget");
            } finally { close(repl); }
        }
    }

    // ------------------------------------------------------------------
    // Provider fixtures
    // ------------------------------------------------------------------

    /**
     * Rejects the first model request with an OpenAI business quota code, then
     * answers normally and records the model each request named.
     */
    private static final class QuotaThenOk implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final List<String> models = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch succeeded =
                new java.util.concurrent.CountDownLatch(1);
        private final HttpServer server;

        QuotaThenOk() throws IOException { this(0); }

        QuotaThenOk(int mode) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                if (skipUtility(exchange)) return;
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                models.add(modelFrom(body));
                bodies.add(body);
                int request = requests.incrementAndGet();
                if (mode == 1 && request == 1) {
                    byte[] tool = ("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"probe-1\",\"type\":\"function\",\"function\":{\"name\":\"failover_probe\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, tool.length);
                    try (var out = exchange.getResponseBody()) { out.write(tool); }
                    return;
                }
                if (mode == 2 || request == (mode == 1 ? 2 : 1)) {
                    // A structured quota code: DirectLlmClient classifies this as
                    // QUOTA_EXHAUSTED, which is switch-eligible.
                    byte[] error = ("{\"error\":{\"code\":\"insufficient_quota\","
                            + "\"type\":\"insufficient_quota\",\"message\":\"out of quota\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(429, error.length);
                    try (var out = exchange.getResponseBody()) { out.write(error); }
                    return;
                }
                succeeded.countDown();
                byte[] ok = ("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, ok.length);
                try (var out = exchange.getResponseBody()) { out.write(ok); }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }

    /** Answers every model request with a policy refusal. */
    private static final class RefusingProvider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;

        RefusingProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                if (skipUtility(exchange)) return;
                requests.incrementAndGet();
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"\"},"
                        + "\"finish_reason\":\"content_filter\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }

    /** Keep model-limit probes and title generation out of the request counts. */
    private static boolean skipUtility(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return true;
        }
        // Match the real title prompt: a guessed marker silently counts the
        // background title request as a model turn and inflates every count.
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.contains(ChatTitleGenerator.SYSTEM_PROMPT)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return true;
        }
        exchange.setStreams(new ByteArrayInputStream(body), null);
        return false;
    }

    /** The {@code model} field of a chat-completions request body, or "". */
    private static String modelFrom(String body) {
        int at = body.indexOf("\"model\"");
        if (at < 0) return "";
        int colon = body.indexOf(':', at);
        if (colon < 0) return "";
        int quote = body.indexOf('"', colon + 1);
        if (quote < 0) return "";
        int end = body.indexOf('"', quote + 1);
        return end < 0 ? "" : body.substring(quote + 1, end);
    }

    // ------------------------------------------------------------------
    // Harness helpers (mirroring ChatMessageHandlerQueueTest)
    // ------------------------------------------------------------------

    private ChatRepl newRepl(String session, Object provider) throws Exception {
        String baseUrl = (String) provider.getClass().getDeclaredMethod("baseUrl").invoke(provider);
        ChatConfig config = new ChatConfig("custom", null, "primary-model", baseUrl);
        return new ChatRepl(null, null, session, false, "default", false, config);
    }

    private static void close(ChatRepl repl) throws Exception {
        repl.close();
        field(repl, "processManager", BackgroundProcessManager.class).close();
        ChatCompleter.setActivity(null);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        return condition.getAsBoolean();
    }
}
