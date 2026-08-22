package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectSubagentRunnerCancellationTest {

    @Test
    void cancelStopsOnlySelectedSubagentAndLeavesParentAlive() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestStarted.countDown();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write((
                        "data: {\"choices\":[{\"delta\":{\"content\":\"late\"},"
                                + "\"finish_reason\":null}]}\n\n"
                                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        PermissionService permissions = new PermissionService();
        ChatConfig config = new ChatConfig(
                "custom", "test", "model",
                "http://127.0.0.1:" + server.getAddress().getPort());
        DirectSubagentRunner runner = new DirectSubagentRunner(
                config, mapper, tools, permissions, new TerminalRenderer(false));
        ToolContext parent = new ToolContext(
                "parent", AgentConfig.builder("parent").build(), permissions,
                Path.of("."), tools);
        AtomicReference<String> subagentId = new AtomicReference<>();
        runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
            @Override
            public void onSubagentStart(String id, String type, String description) {
                subagentId.set(id);
            }

            @Override
            public void onSubagentEnd(String id) {
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = executor.submit(() -> runner.runSubagent(
                    AgentConfig.builder("explore").systemPrompt("Explore").build(),
                    "wait for cancellation", parent));
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            String id = subagentId.get();
            assertTrue(id != null && runner.canCancel(id));
            assertTrue(runner.cancel(id));
            assertFalse(runner.cancel(id), "repeated Delete must not cancel twice");
            assertFalse(parent.isAborted(), "subagent Delete must not cancel its parent turn");

            releaseResponse.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS).contains("Subagent aborted"));
            assertFalse(runner.canCancel(id));
            assertFalse(runner.cancel("missing"));
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }
}
