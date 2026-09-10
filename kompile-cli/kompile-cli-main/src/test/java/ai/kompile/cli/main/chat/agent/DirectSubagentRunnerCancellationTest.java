package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectSubagentRunnerCancellationTest {

    @TempDir Path directory;

    @Test
    @Timeout(15)
    void childModelAndThinkingReachWireAndRetainedSessionWithoutLeaking() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write((
                    "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":null}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ChatConfig config = new ChatConfig("custom", "test", "parent-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setThinking("low");
            config.setPromptCacheRetention("none");
            config.setAutoCompactEnabled(false);
            config.setAutoCompactThreshold(0.7);
            config.setCompactionReserveTokens(100);
            ChatConfig copy = config.copy();
            assertEquals("none", copy.getPromptCacheRetention());
            assertFalse(copy.isAutoCompactEnabled());
            assertEquals(0.7, copy.getAutoCompactThreshold());
            assertEquals(100, copy.getCompactionReserveTokens());
            PermissionService permissions = new PermissionService();
            ToolRegistry tools = new ToolRegistry(mapper);
            DirectSubagentRunner runner = new DirectSubagentRunner(config, mapper, tools,
                    permissions, new TerminalRenderer(false));
            ToolContext parent = new ToolContext("overrides-parent", AgentConfig.builder("parent").build(),
                    permissions, directory, tools);
            parent.setOutputConsumer(ignored -> {});
            AtomicReference<String> childId = new AtomicReference<>();
            CountDownLatch childCompletedTwice = new CountDownLatch(2);
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) {
                    childId.compareAndSet(null, id);
                }
                @Override public void onSubagentEnd(String id) {
                    childCompletedTwice.countDown();
                }
            });
            assertTrue(runner.runSubagent(AgentConfig.builder("architect").isSubagent(true)
                    .systemPrompt("Read-only audit").modelOverride("gpt-5.6-luna")
                    .thinkingOverride("xhigh").build(), "Audit boundaries", parent).contains("done"));
            assertEquals("parent-model", config.getModel());
            assertEquals("low", config.getThinking());
            // Parent switches must not change an already retained child's settings.
            config.setModel("changed-parent-model");
            config.setThinking("medium");
            assertTrue(runner.sendMessage(childId.get(), "Continue the audit"));
            assertTrue(childCompletedTwice.await(5, TimeUnit.SECONDS));
            runner.runSubagent(AgentConfig.builder("sibling").isSubagent(true).build(), "New task", parent);
            assertEquals(3, requests.size());
            for (int index = 0; index < 2; index++) {
                var request = mapper.readTree(requests.get(index));
                assertEquals("gpt-5.6-luna", request.path("model").asText());
                assertEquals("xhigh", request.path("reasoning_effort").asText());
                assertTrue(request.path("messages").toString().contains("Read-only audit"));
            }
            var sibling = mapper.readTree(requests.get(2));
            assertEquals("changed-parent-model", sibling.path("model").asText());
            assertEquals("medium", sibling.path("reasoning_effort").asText());
            assertEquals(List.of("Bearer test", "Bearer test", "Bearer test"), authorization);
            assertEquals("changed-parent-model", config.getModel());
            assertEquals("medium", config.getThinking());
            assertFalse(parent.isAborted());
        } finally {
            server.stop(0);
        }
    }

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

    @Test
    void followUpUsesTheRetainedChildConversationAfterItsFirstResponse() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch secondRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int index = requestCount.incrementAndGet();
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (index == 2) secondRequest.countDown();
            String content = index == 1 ? "initial child response" : "continued child response";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write((
                    "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"},"
                            + "\"finish_reason\":null}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
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
                "parent-retained", AgentConfig.builder("parent").build(), permissions,
                Path.of("."), tools);
        AtomicReference<String> subagentId = new AtomicReference<>();
        CountDownLatch completedRuns = new CountDownLatch(2);
        runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
            @Override
            public void onSubagentStart(String id, String type, String description) {
                subagentId.compareAndSet(null, id);
            }

            @Override
            public void onSubagentEnd(String id) {
                completedRuns.countDown();
            }
        });

        try {
            String first = runner.runSubagent(
                    AgentConfig.builder("explore").systemPrompt("Explore").build(),
                    "inspect the crawl", parent);
            assertTrue(first.contains("initial child response"));
            String id = subagentId.get();
            assertTrue(id != null && !id.isBlank());

            assertTrue(runner.sendMessage(id, "continue the crawl"));
            assertTrue(secondRequest.await(5, TimeUnit.SECONDS));
            assertTrue(completedRuns.await(5, TimeUnit.SECONDS));
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("continue the crawl"));
            assertTrue(requests.get(1).contains("initial child response"),
                    "the follow-up request must keep the original child history");
            assertFalse(parent.isAborted());
        } finally {
            server.stop(0);
        }
    }
}
