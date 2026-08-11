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
import java.util.concurrent.TimeUnit;

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
        BackgroundProcessManager processes =
                field(repl, "processManager", BackgroundProcessManager.class);

        try {
            long started = System.nanoTime();
            handler.handleChatMessage("first");
            long returnedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertTrue(returnedMs < 500,
                    "interactive dispatch must return control to readline immediately");
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS), "first model request was not dispatched");
            assertTrue(repl.isLlmBusy());

            handler.handleChatMessage("second");
            assertEquals(1, queue.size(), "follow-up should be queued while the first turn runs");

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

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
