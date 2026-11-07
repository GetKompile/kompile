/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a provider usage-limit window ends a turn (session, five-hour, or weekly
 * cap), every recurring schedule in the session must stop firing into the
 * quota: otherwise each tick re-enters a turn that can only fail, polluting
 * the transcript with error turns until the window closes.
 *
 * <p>Drives the real {@link ChatRepl}/{@code ChatMessageHandler} against a
 * local provider that rejects the first request with a quota failure and then
 * answers normally, mirroring {@link ChatMessageHandlerFailoverTest}'s harness
 * without a ladder, so the watchdog pause path is what is under test.</p>
 */
class UsageLimitLoopPauseTest {

    private String originalHome;
    private java.nio.file.Path fakeHome;

    @BeforeEach
    void isolateConfigHome(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        originalHome = System.getProperty("user.home");
        fakeHome = tmp;
        System.setProperty("user.home", tmp.toString());
        java.nio.file.Files.createDirectories(tmp.resolve(".kompile/conversations"));
    }

    @AfterEach
    void restoreHome() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
    }

    @Test
    void aQuotaWindowPausesTheSessionLoopUntilTheUserResumes() throws Exception {
        try (LimitThenOk provider = new LimitThenOk()) {
            ChatRepl repl = newRepl("loop-pause-" + System.nanoTime(), provider);
            try {
                ScheduledLoopManager loops = repl.getScheduledLoopManager();
                ScheduledLoopManager.ScheduledLoop loop = loops.create("1h", "keep watching");
                assertNotNull(loop);

                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(provider.rejected.await(10, TimeUnit.SECONDS),
                        "the provider never issued the quota rejection");
                assertTrue(await(() -> !repl.isLlmBusy(), 10), "the failed turn never finished");

                assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED, loop.getStatus(),
                        "the usage-limit window must pause the loop");
                assertEquals(0, loops.activeCount());
                assertTrue(provider.requests.get() == 1,
                        "the paused loop must not fire into the same window");

                // The ordinary resume command restarts the loop; the watchdog's
                // auto-continue wake re-runs the failed message separately.
                assertTrue(loops.resume(loop.getId()));
                assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.ACTIVE, loop.getStatus());
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void aNonLimitFailureLeavesLoopsRunning() throws Exception {
        try (ErroringProvider provider = new ErroringProvider()) {
            ChatRepl repl = newRepl("loop-stay-" + System.nanoTime(), provider);
            try {
                ScheduledLoopManager loops = repl.getScheduledLoopManager();
                ScheduledLoopManager.ScheduledLoop loop = loops.create("1h", "keep watching");
                assertNotNull(loop);

                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(await(() -> !repl.isLlmBusy(), 10), "the failed turn never finished");

                assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.ACTIVE, loop.getStatus(),
                        "transport/server errors are not usage windows and must not stop loops");
                assertEquals(1, loops.activeCount());
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void aWeeklyWindowPausesLoopsWithoutArmingAutoContinue() throws Exception {
        try (WeeklyLimitProvider provider = new WeeklyLimitProvider()) {
            ChatRepl repl = newRepl("loop-weekly-" + System.nanoTime(), provider);
            try {
                ScheduledLoopManager loops = repl.getScheduledLoopManager();
                ScheduledLoopManager.ScheduledLoop loop = loops.create("1h", "keep watching");
                assertNotNull(loop);

                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(await(() -> !repl.isLlmBusy(), 10), "the failed turn never finished");

                assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED, loop.getStatus(),
                        "a weekly cap has no reset a wake can bridge; the loop still must stop");
            } finally {
                close(repl);
            }
        }
    }

    @Test
    void boundedLoopsPauseWithTheScheduledScopes() throws Exception {
        try (LimitThenOk provider = new LimitThenOk()) {
            ChatRepl repl = newRepl("loop-bounded-" + System.nanoTime(), provider);
            try {
                BoundedLoopManager bounded = repl.getBoundedLoopManager();
                BoundedLoopManager.BoundedLoop boundedLoop = bounded.create("1h", "5", "bounded watch");
                assertNotNull(boundedLoop);

                ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
                handler.handleChatMessage("do the work");
                assertTrue(await(() -> !repl.isLlmBusy(), 10), "the failed turn never finished");

                assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.PAUSED,
                        boundedLoop.getStatus(),
                        "bounded loops share the transcript and must pause with scheduled loops");
                assertTrue(bounded.pauseAllActive().isEmpty(),
                        "a second call has nothing active left to pause");
                assertEquals(5, boundedLoop.getRemainingFires(),
                        "the remaining bound must survive the pause");
                assertTrue(bounded.resume(boundedLoop.getId()));
            } finally {
                close(repl);
            }
        }
    }

    // ------------------------------------------------------------------
    // Provider fixtures
    // ------------------------------------------------------------------

    /** Rejects the first model request with a 429 usage-limit window, then answers normally. */
    private static final class LimitThenOk implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch rejected = new CountDownLatch(1);
        private final HttpServer server;

        LimitThenOk() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                if (skipUtility(exchange)) return;
                int request = requests.incrementAndGet();
                if (request == 1) {
                    // Structured quota error, the shape DirectLlmClient parses;
                    // the message carries the usage-limit window phrases.
                    byte[] error = ("{\"error\":{\"code\":\"usage_limit_reached\","
                            + "\"type\":\"usage_limit_error\",\"message\":\"usage limit reached, "
                            + "try again in 45 minutes\"}}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(429, error.length);
                    try (var out = exchange.getResponseBody()) { out.write(error); }
                    rejected.countDown();
                    return;
                }
                succeed(exchange);
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }

    /** Answers every model request with a terminal weekly usage-limit window (no reset hint). */
    private static final class WeeklyLimitProvider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;

        WeeklyLimitProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                if (skipUtility(exchange)) return;
                requests.incrementAndGet();
                byte[] error = "429 usage limit reached (weekly limit)".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(429, error.length);
                try (var out = exchange.getResponseBody()) { out.write(error); }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }

    /** Answers every model request with a non-limit server error. */
    private static final class ErroringProvider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;

        ErroringProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                if (skipUtility(exchange)) return;
                requests.incrementAndGet();
                byte[] error = "internal server error".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(500, error.length);
                try (var out = exchange.getResponseBody()) { out.write(error); }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }

    private static void succeed(HttpExchange exchange) throws IOException {
        byte[] ok = ("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, ok.length);
        try (var out = exchange.getResponseBody()) { out.write(ok); }
    }

    /** Keep model-limit probes and title generation out of the request counts. */
    private static boolean skipUtility(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return true;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.contains(ChatTitleGenerator.SYSTEM_PROMPT)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return true;
        }
        exchange.setStreams(new ByteArrayInputStream(body), null);
        return false;
    }

    // ------------------------------------------------------------------
    // Harness helpers (mirroring ChatMessageHandlerFailoverTest)
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
