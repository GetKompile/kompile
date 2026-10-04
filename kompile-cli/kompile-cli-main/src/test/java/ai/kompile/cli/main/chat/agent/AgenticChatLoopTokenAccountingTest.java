package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.ForegroundRequestProgress;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class AgenticChatLoopTokenAccountingTest {
    @TempDir Path directory;

    @AfterEach
    void resetUi() {
        ChatCompleter.setContentOutput(null);
        ChatCompleter.setActivity(null);
    }

    @Test
    void liveUsageAndFinalResultAreCountedExactlyOnceIncludingCompaction() throws Exception {
        verifyAccounting(true, true);
    }

    @Test
    void totalsOnlyRouteStillUpdatesBothCounters() throws Exception {
        verifyAccounting(false, true);
    }

    @Test
    void textFallbackAfterUsageDoesNotAddAnEstimateToExactOutput() throws Exception {
        verifyAccounting(true, false);
    }

    @Test
    void toolArgumentsCountWhileTheModelStreamsThem() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var config = new ChatConfig("openai", "test-key", "test-model", null);
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        var metrics = new ChatSessionMetrics("tool-arguments");
        var progress = new ForegroundRequestProgress();
        progress.begin();
        AtomicReference<ForegroundRequestProgress.Snapshot> streaming = new AtomicReference<>();
        ChatCompleter.setContentOutput(ignored -> { });
        try (var client = new DirectLlmClient(config, mapper, directory) {
            @Override
            public StreamResult streamChat(String message, String systemPrompt, ArrayNode tools,
                                           List<ToolCallResultInput> results, String model,
                                           List<AttachmentInput> attachments) {
                // A tool call's arguments are output; the request's usage counts them at its end.
                getProviderActivityListener().onToolInputDelta("x".repeat(400));
                streaming.set(progress.snapshot());
                var result = new StreamResult();
                result.text = "done";
                result.inputTokens = 10;
                result.outputTokens = 20;
                return result;
            }
        }) {
            var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            loop.configureConversationSession("tool-arguments");
            loop.setSessionMetrics(metrics);
            loop.setForegroundProgress(progress);
            loop.chat("hello", "tool-arguments", "coder", "default", false);
        }
        assertNotNull(streaming.get());
        assertEquals(100, streaming.get().tokens(), streaming.get().toString());
        assertTrue(streaming.get().estimate(), streaming.get().toString());
        assertEquals(20, metrics.getOutputTokens());
        assertEquals(30, metrics.getTotalTokens());
        assertEquals(20, progress.snapshot().tokens(), "the request's usage replaces the estimate");
        assertFalse(progress.snapshot().estimate());
    }

    @Test
    void anthropicDisconnectAfterInitialUsageKeepsOutputEstimate() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\""
                    + "x".repeat(400) + "\"}}\n\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\n";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close(); // no message_delta/message_stop
        });
        server.start();
        var mapper = JsonUtils.standardMapper();
        var config = new ChatConfig("anthropic", "test-key", "claude-sonnet-4-6",
                "http://127.0.0.1:" + server.getAddress().getPort());
        config.setDefaultMemory(false);
        var metrics = new ChatSessionMetrics("incomplete-usage");
        var progress = new ForegroundRequestProgress();
        progress.begin();
        ChatCompleter.setContentOutput(ignored -> { });
        try (var client = new DirectLlmClient(config, mapper, directory)) {
            var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            loop.setSessionMetrics(metrics);
            loop.setForegroundProgress(progress);
            var stream = AgenticChatLoop.class.getDeclaredMethod("streamDirectTurn",
                    String.class, String.class, ArrayNode.class, List.class, String.class, List.class);
            stream.setAccessible(true);
            stream.invoke(loop, "hello", "system", null, null, null, List.of());
            assertEquals(10, metrics.getInputTokens());
            assertEquals(1, metrics.getOutputTokens(), "retain the provider's reported lower bound");
            assertTrue(progress.snapshot().tokens() >= 100, progress.snapshot().toString());
            assertTrue(progress.snapshot().estimate(), "initial usage does not prove complete output");
        } finally {
            server.stop(0);
        }
    }

    private void verifyAccounting(boolean emitUsage, boolean compaction) throws Exception {
        var mapper = JsonUtils.standardMapper();
        var config = new ChatConfig("openai", "test-key", "test-model", null);
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        var metrics = new ChatSessionMetrics("token-accounting");
        var progress = new ForegroundRequestProgress();
        progress.begin();
        ChatCompleter.setContentOutput(ignored -> { });
        try (var client = new DirectLlmClient(config, mapper, directory) {
            @Override
            public StreamResult streamChat(String message, String systemPrompt, ArrayNode tools,
                                           List<ToolCallResultInput> results, String model,
                                           List<AttachmentInput> attachments) {
                getThinkingConsumer().accept("x".repeat(400));
                assertEquals(100, progress.snapshot().tokens());
                getOutputConsumer().accept("done");
                if (emitUsage) {
                    getProviderActivityListener().onTokenUsage(10, 20, 30, 40);
                    assertEquals(100, metrics.getTotalTokens(), "top counter updates before return");
                    assertTrue(progress.snapshot().estimate(),
                            "a usage callback alone does not prove a complete stream");
                    // A native transport may deliver its REST fallback text after usage.
                    getOutputConsumer().accept("delayed rendered text");
                }
                var result = new StreamResult();
                result.text = "done";
                result.inputTokens = 10;
                result.outputTokens = 20;
                result.cacheReadTokens = 30;
                result.cacheCreationTokens = 40;
                if (compaction) {
                    result.compactionInputTokens = 5;
                    result.compactionOutputTokens = 6;
                    result.compactionCacheReadTokens = 7;
                    result.compactionCacheCreationTokens = 8;
                }
                return result;
            }
        }) {
            var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            loop.configureConversationSession("token-accounting");
            loop.setSessionMetrics(metrics);
            loop.setForegroundProgress(progress);
            loop.chat("hello", "token-accounting", "coder", "default", false);
        }
        assertEquals(compaction ? 15 : 10, metrics.getInputTokens());
        assertEquals(compaction ? 26 : 20, metrics.getOutputTokens());
        assertEquals(compaction ? 37 : 30, metrics.getCacheReadTokens());
        assertEquals(compaction ? 48 : 40, metrics.getCacheCreationTokens());
        assertEquals(compaction ? 126 : 100, metrics.getTotalTokens());
        assertEquals(compaction ? "↑100 ↓26 Σ126" : "↑80 ↓20 Σ100", metrics.compactTokenSummary());
        assertEquals(compaction ? 26 : 20, progress.snapshot().tokens());
        assertFalse(progress.snapshot().estimate());
    }
}
