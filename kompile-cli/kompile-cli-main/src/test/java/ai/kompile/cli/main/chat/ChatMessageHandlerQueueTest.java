package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageHandlerQueueTest {

    @Test
    void activeTurnKeepsInputAvailableAndQueuesFollowUp() throws Exception {
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch twoRequests = new CountDownLatch(2);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            firstRequest.countDown();
            twoRequests.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
                byte[] body = (
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "queue-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, "queue-test", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        MessageQueueManager queueManager = field(repl, "queueManager", MessageQueueManager.class);
        BackgroundProcessManager processes =
                field(repl, "processManager", BackgroundProcessManager.class);

        try {
            assertTrue(repl.isAutoDequeueEnabled());
            queueManager.toggleAutoDequeue();
            assertFalse(repl.isAutoDequeueEnabled(),
                    "the toggle and completion path must share one effective state");
            queueManager.toggleAutoDequeue();
            assertTrue(repl.isAutoDequeueEnabled());

            Field registryLockField = SessionRegistry.class.getDeclaredField("JVM_REGISTRY_LOCK");
            registryLockField.setAccessible(true);
            ExecutorService inputThread = Executors.newSingleThreadExecutor();
            try {
                // A sibling session may be persisting its registry entry. Input must
                // return before that lock is released, not merely win a timing race.
                synchronized (registryLockField.get(null)) {
                    var dispatch = inputThread.submit(() -> {
                        long started = System.nanoTime();
                        handler.handleChatMessage("first");
                        return Duration.ofNanos(System.nanoTime() - started).toMillis();
                    });
                    long returnedMs = dispatch.get(2, TimeUnit.SECONDS);
                    assertTrue(returnedMs < 500,
                            "interactive dispatch must return control to readline immediately: " + returnedMs + "ms");
                    assertEquals("first", repl.currentSessionTitle(),
                            "the in-memory title must be available before registry persistence");
                }
            } finally {
                inputThread.shutdown();
                assertTrue(inputThread.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS), "first model request was not dispatched");
            assertTrue(repl.isLlmBusy());

            handler.handleChatMessage("second");
            assertEquals(1, queue.size(), "follow-up should be queued while the first turn runs");
            String queuedId = queue.peek().getId();
            queueManager.sendNextQueuedMessage();
            assertEquals(1, queue.size(), "send-now must not dequeue while another turn is active");
            assertEquals(queuedId, queue.peek().getId(),
                    "a busy send attempt must preserve queue identity and order");

            releaseResponse.countDown();
            assertTrue(twoRequests.await(10, TimeUnit.SECONDS),
                    "queued follow-up was not automatically dispatched");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (repl.isLlmBusy() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(repl.isLlmBusy(), "queue chain should return to idle");
            assertTrue(queue.isEmpty(), "auto-dequeue should drain the queued follow-up");
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void scheduledLoopUsesExistingQueueWhileTurnIsBusy() throws Exception {
        String testSession = "scheduled-loop-queue-" + java.util.UUID.randomUUID();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch scheduledRequest = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (requestBody.contains("scheduled follow-up")) {
                    requestCount.incrementAndGet();
                    scheduledRequest.countDown();
                } else if (requestBody.contains("active work")) {
                    requestCount.incrementAndGet();
                    firstRequest.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "scheduled-loop-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, testSession, false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);

        try {
            queue.clear();
            handler.handleChatMessage("active work");
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS));

            repl.dispatchScheduledLoop("scheduled follow-up");
            assertEquals(1, queue.size());
            assertEquals("scheduled follow-up", queue.peek().getContent());

            releaseFirst.countDown();
            assertTrue(scheduledRequest.await(10, TimeUnit.SECONDS),
                    "scheduled prompt was not dispatched through the queue");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS),
                    "scheduled queue owner did not release after its response");
            assertTrue(queue.isEmpty());
            assertEquals(2, requestCount.get());
        } finally {
            releaseFirst.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void scheduledLoopSerializesAndCancelsInInteractiveCrawlProfile() throws Exception {
        String testSession = "scheduled-crawl-loop-" + java.util.UUID.randomUUID();
        CountDownLatch activeRequest = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        CountDownLatch scheduledRequest = new CountDownLatch(1);
        CountDownLatch releaseScheduled = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        ExecutorService turns = Executors.newFixedThreadPool(2);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (requestBody.contains("scheduled crawl check")) {
                    requestCount.incrementAndGet();
                    scheduledRequest.countDown();
                    releaseScheduled.await(30, TimeUnit.SECONDS);
                } else if (requestBody.contains("active crawl work")) {
                    requestCount.incrementAndGet();
                    activeRequest.countDown();
                    releaseActive.await(30, TimeUnit.SECONDS);
                }
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(serverExecutor);
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "scheduled-crawl-loop-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, testSession, false, "default", false, config, true);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);

        try {
            var active = turns.submit(() -> handler.handleChatMessage("active crawl work"));
            assertTrue(activeRequest.await(5, TimeUnit.SECONDS));

            var scheduled = turns.submit(() -> repl.dispatchScheduledLoop("scheduled crawl check"));
            assertFalse(scheduledRequest.await(250, TimeUnit.MILLISECONDS),
                    "a scheduled crawl turn must not overlap the active crawl owner");

            releaseActive.countDown();
            active.get(10, TimeUnit.SECONDS);
            assertTrue(scheduledRequest.await(5, TimeUnit.SECONDS),
                    "scheduled crawl prompt did not run after the active turn released");
            assertTrue(handler.requestCancel(),
                    "the serialized scheduled crawl turn must remain cancellable");
            scheduled.get(5, TimeUnit.SECONDS);

            assertFalse(repl.isLlmBusy(), "synchronous crawl turns must return to idle");
            assertEquals(2, requestCount.get());
        } finally {
            releaseActive.countDown();
            releaseScheduled.countDown();
            turns.shutdownNow();
            repl.close();
            server.stop(0);
            serverExecutor.shutdownNow();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void processCompletionWakesBusyAgentEvenWhenAutoDequeueIsDisabled() throws Exception {
        String testSession = "process-wakeup-" + System.nanoTime();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch wakeRequest = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                int requestNumber = requestCount.incrementAndGet();
                if (requestNumber == 1) {
                    firstRequest.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } else if (requestBody.contains("System process completion")) {
                    wakeRequest.countDown();
                }
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatRepl repl = new ChatRepl(
                null, null, testSession, false, "default", false,
                new ChatConfig("custom", null, "wake-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        MessageQueueManager queueManager = field(repl, "queueManager", MessageQueueManager.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        try {
            queue.clear();
            queueManager.toggleAutoDequeue();
            assertFalse(repl.isAutoDequeueEnabled());

            handler.handleChatMessage("active parent");
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS));
            handler.handleExternalMessage("[System process completion] process proc-001 exited");
            assertTrue(queue.isEmpty(), "mandatory process events must not enter the user queue");

            releaseFirst.countDown();
            assertTrue(wakeRequest.await(10, TimeUnit.SECONDS),
                    "process completion did not wake the parent agent");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS),
                    "completion-event owner did not release after its response");
            assertTrue(requestCount.get() >= 2,
                    "the completion must trigger at least one follow-up model request");
        } finally {
            releaseFirst.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void judgeFeedbackInterruptsAndRunsAsAUserTurn(@TempDir Path workingDirectory)
            throws Exception {
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch feedbackRequest = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> feedbackBody = new AtomicReference<>();
        ExecutorService serverExecutor = Executors.newCachedThreadPool();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                int requestNumber = requestCount.incrementAndGet();
                if (requestNumber == 1) {
                    firstRequest.countDown();
                    releaseFirst.await(30, TimeUnit.SECONDS);
                }
                if (requestBody.contains("Repair the blocked approach")) {
                    feedbackBody.set(requestBody);
                    feedbackRequest.countDown();
                }
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatRepl repl = new ChatRepl(
                null, null, "judge-feedback-" + System.nanoTime(),
                false, "default", false,
                new ChatConfig("custom", null, "feedback-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()),
                workingDirectory);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        MessageQueueManager queueManager = field(repl, "queueManager", MessageQueueManager.class);
        ChatSessionMetrics metrics = field(repl, "sessionMetrics", ChatSessionMetrics.class);
        AuxiliaryChatRepl judge = field(repl, "judgeRepl", AuxiliaryChatRepl.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        try {
            field(repl, "agenticLoop", AgenticChatLoop.class).setPerformanceHarness(null);
            queue.clear();
            queueManager.toggleAutoDequeue();
            assertFalse(repl.isAutoDequeueEnabled());

            handler.handleChatMessage("active parent turn");
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS));
            assertTrue(judge.sendFeedback("Repair the blocked approach", true));
            assertTrue(queue.isEmpty(), "judge feedback must not depend on ordinary auto-dequeue");

            assertTrue(feedbackRequest.await(10, TimeUnit.SECONDS),
                    "interrupting judge feedback never reached the provider");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS));
            assertTrue(feedbackBody.get().contains("[judge feedback]"));
            assertTrue(feedbackBody.get().contains("Repair the blocked approach"));
            assertEquals(2, metrics.getUserTurns(),
                    "feedback must be recorded through the user-turn path, not as a system event");
            assertEquals(2, requestCount.get(), "feedback must dispatch exactly one successor turn");
        } finally {
            releaseFirst.countDown();
            queue.clear();
            handler.shutdown();
            repl.close();
            server.stop(0);
            serverExecutor.shutdownNow();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void processCompletionHasPriorityOverOrdinaryQueuedInput() throws Exception {
        CountDownLatch activeRequest = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        CountDownLatch completionFirst = new CountDownLatch(1);
        CountDownLatch ordinaryRequest = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (requestBody.contains("priority-active")
                        && activeRequest.getCount() > 0) {
                    activeRequest.countDown();
                    releaseActive.await(5, TimeUnit.SECONDS);
                }
                if (requestBody.contains("priority-completion")
                        && !requestBody.contains("priority-ordinary")) {
                    completionFirst.countDown();
                }
                if (requestBody.contains("priority-ordinary")) {
                    ordinaryRequest.countDown();
                }
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatRepl repl = new ChatRepl(
                null, null, "process-priority-" + System.nanoTime(),
                false, "default", false,
                new ChatConfig("custom", null, "priority-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        try {
            field(repl, "agenticLoop", AgenticChatLoop.class).setPerformanceHarness(null);
            queue.clear();
            handler.handleChatMessage("priority-active");
            assertTrue(activeRequest.await(5, TimeUnit.SECONDS));
            handler.handleChatMessage("priority-ordinary");
            handler.handleExternalMessage("[System process completion] priority-completion");
            releaseActive.countDown();

            assertTrue(completionFirst.await(5, TimeUnit.SECONDS),
                    "mandatory process completion must precede ordinary queued input");
            assertTrue(ordinaryRequest.await(5, TimeUnit.SECONDS),
                    "ordinary input must remain queued after the completion wakeup");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS),
                    "priority queue chain did not release after ordinary input completed");
        } finally {
            releaseActive.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void closingExternalLaneDropsCompletionQueuedBehindActiveTurn() throws Exception {
        CountDownLatch activeRequest = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicBoolean completionObserved = new AtomicBoolean(false);
        AtomicBoolean postShutdownObserved = new AtomicBoolean(false);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (requestBody.contains("close-active") && activeRequest.getCount() > 0) {
                    activeRequest.countDown();
                    releaseActive.await(5, TimeUnit.SECONDS);
                }
                if (requestBody.contains("close-completion")) {
                    completionObserved.set(true);
                }
                if (requestBody.contains("after-shutdown")) {
                    postShutdownObserved.set(true);
                }
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatRepl repl = new ChatRepl(
                null, null, "process-close-" + System.nanoTime(),
                false, "default", false,
                new ChatConfig("custom", null, "close-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        try {
            field(repl, "agenticLoop", AgenticChatLoop.class).setPerformanceHarness(null);
            handler.handleChatMessage("close-active");
            assertTrue(activeRequest.await(5, TimeUnit.SECONDS));
            handler.handleChatMessage("close-queued");
            handler.handleExternalMessage("[System process completion] close-completion");
            handler.shutdown();
            handler.handleChatMessage("after-shutdown");
            repl.dispatchScheduledLoop("/queue scheduled-after-shutdown");
            releaseActive.countDown();
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(completionObserved.get(),
                    "a queued process completion must not run after session closure");
            assertFalse(postShutdownObserved.get(),
                    "ordinary callbacks must not start a new owner after shutdown");
            assertEquals(1, queue.size(),
                    "shutdown must preserve the already queued message and reject scheduled mutations");
            assertEquals("close-queued", queue.peek().getContent());
        } finally {
            releaseActive.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void resourceAvailabilityWakesIdleAgentOnce(@TempDir Path root) throws Exception {
        CountDownLatch wake = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.incrementAndGet();
            if (request.contains("[System resource availability]") && request.contains("blocked-build")) wake.countDown();
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String session = "resource-wake-" + System.nanoTime();
        ChatRepl repl = new ChatRepl(null, null, session, false, "default", false,
                new ChatConfig("custom", null, "wake-test", "http://127.0.0.1:" + server.getAddress().getPort()), root);
        var coordination = field(repl, "coordinationManager",
                ai.kompile.cli.main.coordination.CoordinationStateManager.class);
        var handler = field(repl, "messageHandler", ChatMessageHandler.class);
        var accepting = field(repl, "acceptingProcessWakeups", AtomicBoolean.class);
        try {
            field(repl, "agenticLoop", AgenticChatLoop.class).setPerformanceHarness(null);
            accepting.set(true);
            repl.installResourceWakeups();
            var wait = coordination.activityWaits().watch(session, "build", "BUILD", "blocked-build", () -> true);
            assertTrue(wait.wakeSupported());
            coordination.activityWaits().checkNow();
            assertTrue(wake.await(5, TimeUnit.SECONDS), "an idle agent must receive an actual new model request");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS));
            int count = requests.get();
            coordination.activityWaits().checkNow();
            assertEquals(count, requests.get());
            accepting.set(false);
            coordination.activityWaits().watch(session, "other", "BUILD", "after-close", () -> true);
            coordination.activityWaits().checkNow();
            assertEquals(count, requests.get(), "closed chat must not wake");
        } finally {
            accepting.set(false);
            coordination.shutdown();
            handler.shutdown();
            field(repl, "processManager", BackgroundProcessManager.class).close();
            server.stop(0);
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void monitoredProcessExitsWakeIdleSessionUntilItCloses() throws Exception {
        CountDownLatch completedWake = new CountDownLatch(1);
        CountDownLatch failedWake = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestCount.incrementAndGet();
            if (requestBody.contains("bridge-completed")) completedWake.countDown();
            if (requestBody.contains("bridge-failed")) failedWake.countDown();
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                    + "\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ChatRepl repl = new ChatRepl(
                null, null, "process-bridge-" + System.nanoTime(),
                false, "default", false,
                new ChatConfig("custom", null, "bridge-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()));
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        AgenticChatLoop loop = field(repl, "agenticLoop", AgenticChatLoop.class);
        AtomicBoolean accepting = field(
                repl, "acceptingProcessWakeups", AtomicBoolean.class);
        try {
            loop.setPerformanceHarness(null);
            accepting.set(true);

            BackgroundProcessManager.ProcessEntry unmonitored = processes.launch(
                    "printf 'ignored\\n'", "bridge-unmonitored",
                    Path.of(System.getProperty("user.dir")));
            assertTrue(awaitCondition(() -> !unmonitored.isRunning(), 5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertEquals(0, requestCount.get(),
                    "unmonitored process exits must not wake the agent");

            processes.launchMonitored("printf 'done\\n'", "bridge-completed",
                    Path.of(System.getProperty("user.dir")), "inspect completed output");
            assertTrue(completedWake.await(5, TimeUnit.SECONDS),
                    "a successful process exit must wake an idle parent agent");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS));

            processes.launchMonitored("exit 7", "bridge-failed",
                    Path.of(System.getProperty("user.dir")), "diagnose failure");
            assertTrue(failedWake.await(5, TimeUnit.SECONDS),
                    "a failed process exit must wake an idle parent agent");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS));
            int requestsBeforeClose = requestCount.get();
            assertTrue(requestsBeforeClose >= 2,
                    "successful and failed exits must each trigger an agent request");

            accepting.set(false);
            handler.stopAcceptingExternalMessages();
            BackgroundProcessManager.ProcessEntry closedExit = processes.launchMonitored(
                    "printf 'closed\\n'", "bridge-after-close",
                    Path.of(System.getProperty("user.dir")), "must not wake closed session");
            assertTrue(awaitCondition(() -> !closedExit.isRunning(), 5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertEquals(requestsBeforeClose, requestCount.get(),
                    "process exits must not wake a session after it stops accepting events");
        } finally {
            accepting.set(false);
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void backgroundRequestRequiresSubagentPhaseAndPublishesCompletionNotification() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch completionWakeRequest = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                String requestBody = new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (requestBody.contains("[System background task completion]")) {
                    completionWakeRequest.countDown();
                } else {
                    requestStarted.countDown();
                    releaseResponse.await(5, TimeUnit.SECONDS);
                }
                byte[] body = (
                        "data: {\"choices\":[{\"delta\":{\"content\":\"background result\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "background-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, "background-test", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        AgenticChatLoop loop = field(repl, "agenticLoop", AgenticChatLoop.class);
        AtomicBoolean subagentInvocation = field(
                loop, "blockingSubagentInvocation", AtomicBoolean.class);
        BackgroundTaskManager tasks = field(
                repl, "backgroundTaskManager", BackgroundTaskManager.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        List<String> transcript = new CopyOnWriteArrayList<>();

        try {
            ChatCompleter.setContentOutput(transcript::add);
            ChatCompleter.setContentRedraw(() -> { });
            handler.handleChatMessage("keep running after ctrl-b");
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "model request did not start");
            assertFalse(transcript.stream().anyMatch(line -> line.contains("Ctrl+B")),
                    "main-model thinking must not claim it can be backgrounded");
            assertFalse(handler.requestBackground(),
                    "Ctrl+B must be rejected while only the main model is thinking");

            // The agent loop publishes this phase only while TaskTool blocks the
            // parent turn. Flip it directly here so this HTTP fixture can focus on
            // handler detachment/completion behavior rather than model tool syntax.
            subagentInvocation.set(true);
            assertTrue(handler.requestBackground(), "Ctrl+B should detach the active turn");
            subagentInvocation.set(false);
            assertFalse(handler.requestBackground(), "repeated Ctrl+B must not duplicate detachment");

            BackgroundTaskManager.BackgroundTask task = tasks.getCurrentTask();
            assertTrue(task != null && task.wasBackgrounded());
            assertEquals(BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.BACKGROUNDED,
                    task.getStatus());
            assertFalse(tasks.removeTask(task.getId()),
                    "an active background task must not be removable");

            releaseResponse.countDown();
            assertTrue(completionWakeRequest.await(10, TimeUnit.SECONDS),
                    "a completed Ctrl+B task must initiate a follow-up agent turn");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (repl.isLlmBusy() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertFalse(repl.isLlmBusy());
            assertEquals(BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.COMPLETED,
                    task.getStatus());
            assertTrue(task.getOutput().contains("background result"),
                    "detached output should remain available in the task log");
            assertTrue(tasks.drainNotifications().stream()
                    .anyMatch(completed -> completed.getId().equals(task.getId())));
        } finally {
            subagentInvocation.set(false);
            releaseResponse.countDown();
            server.stop(0);
            processes.close();
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setContentRedraw(null);
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void backgroundRequestImmediatelyDrainsQueueAndProcessesNewInputDirectly() throws Exception {
        String testSession = "background-input-" + System.nanoTime();
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        CountDownLatch queuedInputProcessed = new CountDownLatch(1);
        CountDownLatch directInputProcessed = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (requestBody.contains("active foreground task")
                        && firstRequestStarted.getCount() > 0) {
                    firstRequestStarted.countDown();
                    releaseFirstRequest.await(5, TimeUnit.SECONDS);
                }
                // Later provider payloads include earlier conversation history, so
                // classify the newest input first rather than relying on ordinals.
                if (requestBody.contains("entered after ctrl-b")) {
                    directInputProcessed.countDown();
                } else if (requestBody.contains("waiting before ctrl-b")) {
                    queuedInputProcessed.countDown();
                }
                byte[] body = (
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatRepl repl = new ChatRepl(
                null, null, testSession, false, "default", false,
                new ChatConfig("custom", null, "background-input-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        AgenticChatLoop loop = field(repl, "agenticLoop", AgenticChatLoop.class);
        AtomicBoolean subagentInvocation = field(
                loop, "blockingSubagentInvocation", AtomicBoolean.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        MessageQueueManager queueManager = field(repl, "queueManager", MessageQueueManager.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        try {
            field(repl, "agenticLoop", AgenticChatLoop.class).setPerformanceHarness(null);
            queue.clear();
            queueManager.toggleAutoDequeue();
            assertFalse(repl.isAutoDequeueEnabled());

            handler.handleChatMessage("active foreground task");
            assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS));

            handler.handleChatMessage("waiting before ctrl-b");
            assertEquals(1, queue.size());

            subagentInvocation.set(true);
            assertTrue(handler.requestBackground());
            subagentInvocation.set(false);
            assertTrue(queue.isEmpty(),
                    "Ctrl+B must remove pending input from the durable queue immediately");

            handler.handleChatMessage("entered after ctrl-b");
            assertTrue(queue.isEmpty(),
                    "ordinary input must bypass the durable queue while the task is backgrounded");

            releaseFirstRequest.countDown();
            assertTrue(queuedInputProcessed.await(10, TimeUnit.SECONDS),
                    "input released by Ctrl+B was not processed at the next boundary");
            assertTrue(directInputProcessed.await(10, TimeUnit.SECONDS),
                    "input entered after Ctrl+B was not processed directly");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 10, TimeUnit.SECONDS));
            assertEquals(0, handler.pendingBackgroundInputCount());
            assertTrue(requestCount.get() >= 3);
        } finally {
            subagentInvocation.set(false);
            releaseFirstRequest.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void escapeDuringUncooperativeSyncToolReleasesOwnerAndSendsQueuedMessage() throws Exception {
        String testSession = "sync-cancel-test-" + java.util.UUID.randomUUID();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch releaseToolWorker = new CountDownLatch(1);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body;
            if (requestBody.contains("send after sync cancellation")) {
                requestCount.incrementAndGet();
                secondRequestStarted.countDown();
                body = ("data: {\"choices\":[{\"delta\":{\"content\":\"queued complete\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else if (requestBody.contains("start blocking tool")) {
                requestCount.incrementAndGet();
                body = ("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call-blocking\",\"function\":{\"name\":\"blocking_sync\","
                        + "\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                body = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "sync-cancel-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, testSession, false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        ToolRegistry tools = field(repl, "toolRegistry", ToolRegistry.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);

        tools.register(new CliTool() {
            @Override
            public String id() {
                return "blocking_sync";
            }

            @Override
            public String description() {
                return "blocking synchronous cancellation fixture";
            }

            @Override
            public JsonNode parameterSchema() {
                return JsonUtils.standardMapper().createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() {
                return "read";
            }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                toolStarted.countDown();
                while (releaseToolWorker.getCount() > 0) {
                    try {
                        releaseToolWorker.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately ignore interruption: the dispatch owner must
                        // still be able to cancel and move on to queued input.
                    }
                }
                return ToolResult.success("released");
            }
        });

        try {
            queue.clear();
            handler.handleChatMessage("start blocking tool");
            assertTrue(toolStarted.await(5, TimeUnit.SECONDS), "synchronous tool did not start");

            handler.handleChatMessage("send after sync cancellation");
            assertEquals(1, queue.size());
            assertTrue(handler.requestCancel(), "Escape should cancel the synchronous tool turn");

            assertTrue(secondRequestStarted.await(5, TimeUnit.SECONDS),
                    "queued successor waited for an uncooperative tool worker");
            assertEquals(1L, releaseToolWorker.getCount(),
                    "the fixture must still be blocked when the next message starts");
            assertTrue(queue.isEmpty(), "sending means atomically removing the queued message");
            assertEquals(2, requestCount.get(), "queued message must be sent exactly once");

            long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (repl.isLlmBusy() && System.nanoTime() < idleDeadline) {
                Thread.sleep(10);
            }
            assertFalse(repl.isLlmBusy());
        } finally {
            releaseToolWorker.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void backgroundTaskReleasesParentBeforeWorkerCompletes() throws Exception {
        verifyBackgroundTaskReleasesParent(false);
    }

    @Test
    void detachedTaskFailureIsRetainedWithoutFailingParent() throws Exception {
        verifyBackgroundTaskReleasesParent(true);
    }

    private void verifyBackgroundTaskReleasesParent(boolean failWorker) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch followup = new CountDownLatch(1);
        CountDownLatch freshFollowup = new CountDownLatch(1);
        AtomicReference<ToolContext> child = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String delta;
            if (request.contains("background fresh followup")) freshFollowup.countDown();
            if (request.contains("background followup")) {
                followup.countDown();
                delta = "{\"content\":\"followup handled\"}";
            } else if (request.contains("call-background")) {
                delta = "{\"content\":\"task continues\"}";
            } else {
                delta = "{\"tool_calls\":[{\"index\":0,\"id\":\"call-background\","
                        + "\"function\":{\"name\":\"task\",\"arguments\":\"{}\"}}]}";
            }
            byte[] body = ("data: {\"choices\":[{\"delta\":" + delta
                    + ",\"finish_reason\":null}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ChatRepl repl = new ChatRepl(null, null, "background-test-" + java.util.UUID.randomUUID(),
                false, "default", false, new ChatConfig("custom", null, "background-test",
                "http://127.0.0.1:" + server.getAddress().getPort()));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        BackgroundTaskManager manager = field(repl, "backgroundTaskManager", BackgroundTaskManager.class);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger completionCount = new AtomicInteger();
        manager.addCompletionListener(task -> {
            if (task.wasBackgrounded()) {
                completionCount.incrementAndGet();
                completed.countDown();
            }
        });
        ToolRegistry tools = field(repl, "toolRegistry", ToolRegistry.class);
        BackgroundProcessManager processes = field(repl, "processManager", BackgroundProcessManager.class);
        tools.register(new CliTool() {
            public String id() { return "task"; }
            public String description() { return "blocked background fixture"; }
            public JsonNode parameterSchema() { return JsonUtils.standardMapper().createObjectNode().put("type", "object"); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode params, ToolContext context) {
                child.set(context.forkForToolExecution());
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { return ToolResult.error("worker interrupted"); }
                child.get().emitOutput("late child output");
                if (failWorker) throw new IllegalStateException("real background failure");
                return ToolResult.success("real background result");
            }
        });
        try {
            queue.clear();
            handler.handleChatMessage("start background fixture");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            BackgroundTaskManager.BackgroundTask task = manager.getCurrentTask();
            handler.handleChatMessage("background followup");
            assertTrue(handler.requestBackground());
            handler.handleChatMessage("background fresh followup");
            assertTrue(followup.await(5, TimeUnit.SECONDS), "parent must send input before worker finishes");
            assertTrue(freshFollowup.await(5, TimeUnit.SECONDS), "fresh input must not wait for the worker either");
            assertEquals(1L, release.getCount());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (repl.isLlmBusy() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(repl.isLlmBusy());
            assertEquals(BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.BACKGROUNDED, task.getStatus());
            assertNull(task.getCompletedAt(), "parent completion must not complete the detached task");
            // A later parent cancellation must not leak through a retained child context.
            field(repl, "cancelSignal", AtomicBoolean.class).set(true);
            assertFalse(child.get().isAborted());
            BackgroundTaskManager.BackgroundTask successor = manager.startTask("later foreground task");
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS), "detached completion was lost");
            assertEquals(failWorker ? BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.FAILED
                    : BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.COMPLETED, task.getStatus());
            assertTrue(task.getOutput().contains("late child output"));
            assertTrue(task.getOutput().contains(failWorker ? "real background failure" : "real background result"));
            assertEquals(successor, manager.getCurrentTask(), "late completion must not clear a successor");
            assertEquals(BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.RUNNING, successor.getStatus());
            assertTrue(manager.hasNotifications());
            String savedLine = task.getOutput().lines()
                    .filter(line -> line.startsWith("[Final tool result saved to: ")).findFirst().orElseThrow();
            Path savedResult = Path.of(savedLine.substring("[Final tool result saved to: ".length(), savedLine.length() - 1));
            assertTrue(java.nio.file.Files.readString(savedResult).contains(
                    failWorker ? "real background failure" : "real background result"));
            manager.completeDetachedTask(task, null);
            assertEquals(1, completionCount.get(), "completion must only be delivered once");
            manager.completeCurrentTask();
        } finally {
            release.countDown();
            queue.clear();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void requestCancelInterruptsBlockingModelRequest() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestStarted.countDown();
            try {
                releaseResponse.await(30, TimeUnit.SECONDS);
                byte[] body = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "cancel-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, "cancel-test", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        BackgroundProcessManager processes =
                field(repl, "processManager", BackgroundProcessManager.class);

        try {
            handler.handleChatMessage("block until cancelled");
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "model request was not dispatched");
            assertTrue(repl.isLlmBusy());

            long cancelStarted = System.nanoTime();
            assertTrue(handler.requestCancel(), "active turn should be registered for interruption");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (repl.isLlmBusy() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertFalse(repl.isLlmBusy(), "Escape cancellation must release the prompt promptly");
            assertTrue(Duration.ofNanos(System.nanoTime() - cancelStarted).toMillis() < 3000,
                    "blocking HTTP send was not interrupted");
            assertEquals("Interrupted by user", ChatCompleter.getActivity(),
                    "an interrupted turn should leave a transient status message");
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void requestCancelSendsAndRemovesNextQueuedMessageAfterOwnerReleases() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        ExecutorService serverExecutor = Executors.newCachedThreadPool();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            int requestNumber = requestCount.incrementAndGet();
            try {
                if (requestNumber == 1) {
                    firstRequestStarted.countDown();
                    releaseFirstResponse.await(30, TimeUnit.SECONDS);
                } else {
                    secondRequestStarted.countDown();
                }
                byte[] body = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "cancel-queue-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, null, "cancel-queue-test", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        BackgroundProcessManager processes =
                field(repl, "processManager", BackgroundProcessManager.class);

        try {
            queue.clear();
            handler.handleChatMessage("first blocks");
            assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS),
                    "first model request was not dispatched");

            handler.handleChatMessage("send me after Escape");
            assertEquals(1, queue.size(), "follow-up should wait while the first turn owns dispatch");

            assertTrue(handler.requestCancel(), "Escape should cancel the active turn");
            assertTrue(secondRequestStarted.await(10, TimeUnit.SECONDS),
                    "the next queued message was not sent after the interrupted owner released");
            assertTrue(queue.isEmpty(),
                    "starting a queued message means atomically removing it from the queue");

            long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (repl.isLlmBusy() && System.nanoTime() < idleDeadline) {
                Thread.sleep(10);
            }
            assertFalse(repl.isLlmBusy(), "the dispatched follow-up should return the session to idle");
            assertEquals(2, requestCount.get(), "interruption must dispatch the queued message exactly once");
        } finally {
            queue.clear();
            releaseFirstResponse.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void duplicatePendingInputsAreSilentAcrossChatLanes() throws Exception {
        ChatRepl repl = new ChatRepl(
                null, null, "queue-dedup-" + System.nanoTime(), false, "default", false,
                new ChatConfig("custom", null, "queue-test", "http://127.0.0.1:1"));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        ChatSessionMetrics metrics = field(repl, "sessionMetrics", ChatSessionMetrics.class);
        BackgroundProcessManager processes = field(repl, "processManager", BackgroundProcessManager.class);
        Method background = ChatMessageHandler.class.getDeclaredMethod(
                "acceptBackgroundInput", String.class, String.class);
        background.setAccessible(true);
        Method claimMethod = ChatMessageHandler.class.getDeclaredMethod("claimPendingInputAtBoundary");
        claimMethod.setAccessible(true);
        java.io.PrintStream original = System.out;
        var output = new java.io.ByteArrayOutputStream();
        try (var capture = new java.io.PrintStream(output)) {
            repl.setLlmBusy(true);
            handler.handleChatMessage("Queued reminder");
            handler.handleExternalMessage("Completion reminder");
            assertTrue(handler.handleUserFeedback("Judge reminder", false));
            background.invoke(handler, "Background reminder", null);
            System.setOut(capture);
            handler.handleChatMessage("Queued reminder");
            handler.handleExternalMessage("Completion reminder");
            assertTrue(handler.handleUserFeedback("Judge reminder", true));
            background.invoke(handler, "Background reminder", null);
            assertEquals("", output.toString(), "duplicate additions must not announce themselves");
            assertEquals(1, metrics.getMessagesQueued());
            assertEquals(1, queue.size());
            assertEquals(1, handler.pendingBackgroundInputCount());

            // A repeated event can arrive while its predecessor is claimed but
            // not yet accepted. Rollback must not leave both copies pending.
            for (String content : List.of("Judge reminder", "Completion reminder", "Background reminder")) {
                AgenticChatLoop.QueuedInput claim =
                        (AgenticChatLoop.QueuedInput) claimMethod.invoke(handler);
                if (content.startsWith("Judge")) handler.handleUserFeedback(content, false);
                else if (content.startsWith("Completion")) handler.handleExternalMessage(content);
                else background.invoke(handler, content, null);
                claim.restore();
                String lane = content.startsWith("Judge") ? "mandatoryUserFeedback"
                        : content.startsWith("Completion") ? "mandatoryExternalMessages" : "backgroundInputs";
                java.util.Deque<?> pending = field(handler, lane, java.util.Deque.class);
                assertEquals(1, pending.size(), lane);
                pending.clear();
            }
        } finally {
            System.setOut(original);
            handler.shutdown();
            queue.clear();
            repl.setLlmBusy(false);
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void boundaryClaimRestoresSameQueueEntryWhenCancelWinsAcceptance() throws Exception {
        String sessionId = "queue-claim-cancel-" + System.nanoTime();
        ChatRepl repl = new ChatRepl(
                null, null, sessionId, false, "default", false,
                new ChatConfig("custom", null, "queue-lease-test", "http://127.0.0.1:1"));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        AtomicBoolean cancelSignal = field(repl, "cancelSignal", AtomicBoolean.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);

        try {
            queue.clear();
            MessageQueue.QueuedMessage queued = queue.enqueue("process this next");
            Method claimMethod = ChatMessageHandler.class.getDeclaredMethod(
                    "claimPendingInputAtBoundary");
            claimMethod.setAccessible(true);
            AgenticChatLoop.QueuedInput claim =
                    (AgenticChatLoop.QueuedInput) claimMethod.invoke(handler);

            assertTrue(queue.isEmpty(), "claim should temporarily reserve the queue head");
            cancelSignal.set(true);
            assertFalse(claim.accept(), "cancellation must win before provider ownership");
            handler.shutdown();
            claim.restore();

            assertEquals(1, queue.size(),
                    "shutdown must not discard a claim that never reached the provider");
            assertEquals(queued.getId(), queue.peek().getId(),
                    "rollback must preserve queue identity and FIFO position");
            assertEquals(queued.getContent(), queue.peek().getContent());
        } finally {
            queue.clear();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void serverAskTurnIsInterruptibleAndCancelsRemoteProcess() throws Exception {
        CountDownLatch startEventSent = new CountDownLatch(1);
        CountDownLatch cancelCalled = new CountDownLatch(1);
        CountDownLatch releaseStream = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/api/agents/chat/stream", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                byte[] start = ("event: start\n"
                        + "data: {\"agent\":\"test-agent\",\"processId\":\"remote-123\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseBody().write(start);
                exchange.getResponseBody().flush();
                startEventSent.countDown();
                // Keep the stream open until Escape closes it or test cleanup runs.
                releaseStream.await(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Client cancellation closes the exchange.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/api/agents/chat/cancel/remote-123", exchange -> {
            cancelCalled.countDown();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "custom", null, "server-cancel-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ChatRepl repl = new ChatRepl(
                null, "http://127.0.0.1:" + server.getAddress().getPort(),
                "server-cancel-test", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        BackgroundProcessManager processes =
                field(repl, "processManager", BackgroundProcessManager.class);

        try {
            long started = System.nanoTime();
            handler.streamAgentChat("interrupt remote stream");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 500,
                    "server slash command must return control to readline");
            assertTrue(startEventSent.await(5, TimeUnit.SECONDS), "server stream did not start");
            AtomicReference<String> remoteProcess = field(handler, "activeRemoteProcessId", AtomicReference.class);
            long processDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (remoteProcess.get() == null && System.nanoTime() < processDeadline) {
                Thread.sleep(10);
            }
            assertEquals("remote-123", remoteProcess.get(), "SSE start must register the remote process ID");
            assertTrue(handler.requestCancel(), "server stream should have an active owner thread");
            assertTrue(cancelCalled.await(5, TimeUnit.SECONDS),
                    "Escape must cancel the remote agent process");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS),
                    "server-stream owner did not release after cancellation");
        } finally {
            releaseStream.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void closedUnauthorizedTurnReleasesBusyStateAndAcceptsTheNextMessage()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int request = requests.incrementAndGet();
            if (request == 1) {
                // Reproduce both reported edge cases: OpenAI's 401 status arrives,
                // but its optional diagnostic body remains open and never responds.
                // The client must discard it from the status path, not wait for the
                // provider's two-minute stream-idle timeout.
                exchange.sendResponseHeaders(401, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            byte[] body = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"},"
                            + "\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ChatConfig config = new ChatConfig(
                "openai", "rejected-key", "chat-recovery-test",
                "http://127.0.0.1:" + server.getAddress().getPort());
        config.setContextWindowTokens(8_192);
        config.setMaxOutputTokens(1_024);
        ChatRepl repl = new ChatRepl(
                null, null, "closed-401-recovery-" + System.nanoTime(),
                false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        MessageQueue queue = field(repl, "messageQueue", MessageQueue.class);
        BackgroundProcessManager processes =
                field(repl, "processManager", BackgroundProcessManager.class);

        try {
            handler.handleChatMessage("first request");
            assertTrue(awaitCondition(() -> requests.get() == 1, 5, TimeUnit.SECONDS));
            boolean released = awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS);
            assertTrue(released, () -> "a closed 401 body must release the first turn owner\n"
                    + activeTurnStack(handler));
            assertEquals(1, requests.get(),
                    "an API-key 401 must not be replayed with the same rejected key");

            handler.handleChatMessage("second request");
            assertTrue(awaitCondition(() -> requests.get() == 2, 5, TimeUnit.SECONDS),
                    "the prompt must accept and dispatch a turn after the 401");
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS));
            assertTrue(queue.isEmpty());
        } finally {
            handler.shutdown();
            server.stop(0);
            serverExecutor.shutdownNow();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    @SuppressWarnings("unchecked")
    private static String activeTurnStack(ChatMessageHandler handler) {
        try {
            AtomicReference<Thread> owner = field(
                    handler, "activeDispatchThread", AtomicReference.class);
            Thread thread = owner.get();
            if (thread == null) return "activeDispatchThread=<released>";
            StringBuilder stack = new StringBuilder("activeDispatchThread=")
                    .append(thread.getName()).append(" state=").append(thread.getState());
            for (StackTraceElement frame : thread.getStackTrace()) {
                stack.append("\n  at ").append(frame);
            }
            return stack.toString();
        } catch (Exception failure) {
            return "could not capture active turn stack: " + failure;
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static boolean awaitCondition(
            java.util.function.BooleanSupplier condition,
            long timeout,
            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
