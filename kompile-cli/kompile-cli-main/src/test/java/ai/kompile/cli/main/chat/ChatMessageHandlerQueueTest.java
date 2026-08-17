package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

            long started = System.nanoTime();
            handler.handleChatMessage("first");
            long returnedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertTrue(returnedMs < 500,
                    "interactive dispatch must return control to readline immediately");
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
        } finally {
            releaseStream.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
