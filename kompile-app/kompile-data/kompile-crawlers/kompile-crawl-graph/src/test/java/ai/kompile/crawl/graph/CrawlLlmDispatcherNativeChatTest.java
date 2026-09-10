/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.crawl.graph;

import ai.kompile.core.agent.CliAgentRunner;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.NativeChatCompletion;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.core.llm.chat.LLMChat;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrawlLlmDispatcherNativeChatTest {
    private static final String GRAPH = "{\"entities\":[],\"relations\":[]}";

    @Test
    void pinnedNativeBackendForwardsExactProviderModelAndTextWithoutCliOrDefault() {
        CliAgentRunner cli = mock(CliAgentRunner.class);
        LLMChat defaultModel = mock(LLMChat.class);
        AtomicReference<List<String>> received = new AtomicReference<>();
        NativeChatCompletion bridge = (provider, model, prompt, system, timeout) -> {
            received.set(List.of(provider, model, prompt));
            assertTrue(system.contains("text only"));
            assertEquals(Duration.ofSeconds(25), timeout);
            return GRAPH;
        };
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(cli, null, bridge);
        ReflectionTestUtils.setField(dispatcher, "llmChat", defaultModel);
        dispatcher.setLlmCallTimeoutSeconds(25);
        try {
            ProcessingBackend nativeBackend = nativeBackend();
            nativeBackend.setCapabilities(List.of("text"));
            UnifiedCrawlJob job = job(false, List.of(cliBackend(), nativeBackend));
            assertTrue(dispatcher.hasModelBackend(job));
            assertFalse(dispatcher.hasStructuredChatBackend());
            assertEquals(GRAPH, dispatcher.promptWithCapacityFallback("source marker", "llm", job));
            assertEquals(List.of("codex", "exact-model", "source marker"), received.get());
            verifyNoInteractions(cli, defaultModel);
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void validatedNativeJsonSchemaUsesSelectedProviderModelThinkingAndNoToolExecution() {
        AtomicReference<List<Object>> observed = new AtomicReference<>();
        NativeChatCompletion bridge = new NativeChatCompletion() {
            @Override
            public String complete(String provider, String model, String prompt, String system,
                                   Duration timeout) {
                return GRAPH;
            }

            @Override
            public boolean supportsStructuredChat(String provider, String model, String thinking) {
                assertEquals("codex", provider);
                assertEquals("exact-model", model);
                assertEquals("xhigh", thinking);
                return true;
            }

            @Override
            public String completeStructuredJson(String provider, String model, String thinking,
                                                 String prompt, String system,
                                                 java.util.Map<String, Object> schema,
                                                 Duration timeout) {
                observed.set(List.of(provider, model, thinking, prompt, system, schema));
                return "{\"nodeTypes\":[{\"label\":\"FORECAST\",\"parentType\":\"CONCEPT\"}]}";
            }
        };
        ProcessingBackend backend = nativeBackend();
        backend.setThinking("xhigh");
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(null, null, bridge);
        dispatcher.setNativeStructuredChat(true);
        dispatcher.setTextOnlyRoute(false);
        try {
            UnifiedCrawlJob job = job(false, List.of(backend));
            StructuredChatLanguageModel.Response response =
                    dispatcher.promptStructuredWithCapacityFallback(
                            new StructuredChatLanguageModel.Request(
                                    List.of(new StructuredChatLanguageModel.Message("system", "schema only"),
                                            new StructuredChatLanguageModel.Message("user", "source")),
                                    List.of(new StructuredChatLanguageModel.Tool(
                                            "submit_node_types", "types",
                                            java.util.Map.of("type", "object", "required", List.of("nodeTypes")))),
                                    true,
                                    StructuredChatLanguageModel.ToolDefinitionFormat.STANDARD,
                                    StructuredChatLanguageModel.ToolCallFormat.MODEL,
                                    StructuredChatLanguageModel.ToolChoice.REQUIRED),
                            "llm", job, null);
            assertEquals("{\"nodeTypes\":[{\"label\":\"FORECAST\",\"parentType\":\"CONCEPT\"}]}",
                    response.content());
            assertEquals("codex", observed.get().get(0));
            assertEquals("exact-model", observed.get().get(1));
            assertEquals("xhigh", observed.get().get(2));
            assertTrue(String.valueOf(observed.get().get(3)).contains("user: source"));
            assertTrue(String.valueOf(observed.get().get(3)).contains("tool-call claim"));
            assertTrue(String.valueOf(observed.get().get(4)).contains("schema only"));
            assertTrue(String.valueOf(observed.get().get(4)).contains("provider JSON schema"));
            assertEquals(java.util.Map.of("type", "object", "required", List.of("nodeTypes")),
                    observed.get().get(5));
            assertTrue(dispatcher.hasStructuredChatBackend());
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void explicitChatPrefixSelectsNativeWithoutARoute() {
        AtomicReference<String> providerSeen = new AtomicReference<>();
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(null, null,
                (provider, model, prompt, system, timeout) -> {
                    providerSeen.set(provider);
                    assertEquals("claude-model", model);
                    return GRAPH;
                });
        try {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("prefix").request(
                    UnifiedCrawlRequest.builder().graphExtraction(GraphExtractionConfig.builder()
                            .llmProvider("chat:claude").modelName("claude-model").build()).build()).build();
            assertEquals(GRAPH, dispatcher.promptWithCapacityFallback("text", "llm", job));
            assertEquals("claude", providerSeen.get());
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void fallbackDisabledNeverTriesBackupOrDefaultEvenAfterPrimaryBreakerOpens() {
        CliAgentRunner cli = mock(CliAgentRunner.class);
        LLMChat defaultModel = mock(LLMChat.class);
        AtomicInteger calls = new AtomicInteger();
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(cli, null,
                (provider, model, prompt, system, timeout) -> {
                    calls.incrementAndGet();
                    throw new IOException("native failure");
                });
        ReflectionTestUtils.setField(dispatcher, "llmChat", defaultModel);
        dispatcher.setCircuitBreakerFailureThreshold(1);
        try {
            ProcessingBackend primary = nativeBackend();
            primary.setBackupBackendId("legacy-cli");
            UnifiedCrawlJob job = job(false, List.of(primary, cliBackend()));
            assertNull(dispatcher.promptWithCapacityFallback("text", "llm", job));
            assertNotNull(dispatcher.lastCallFailure());
            assertNull(dispatcher.promptWithCapacityFallback("text", "llm", job));
            assertEquals(1, calls.get());
            verifyNoInteractions(cli, defaultModel);
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void absentNativeBridgeFailsClosedEvenWhenFallbackIsEnabled() {
        CliAgentRunner cli = mock(CliAgentRunner.class);
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(cli, null);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    dispatcher.promptWithCapacityFallback("text", "llm",
                            job(true, List.of(nativeBackend(), cliBackend()))));
            assertTrue(failure.getMessage().contains("native-chat bridge"));
            verifyNoInteractions(cli);
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void unsupportedNativeCapabilitiesAndCredentialsAreRejectedBeforeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(null, null,
                (provider, model, prompt, system, timeout) -> { calls.incrementAndGet(); return GRAPH; });
        try {
            for (String capability : List.of("embedding", "vlm", "tools", "required_choice")) {
                ProcessingBackend backend = nativeBackend();
                backend.setCapabilities(List.of("llm", capability));
                assertThrows(IllegalArgumentException.class, () -> dispatcher.promptWithCapacityFallback(
                        "text", "llm", job(true, List.of(backend))));
            }
            ProcessingBackend key = nativeBackend();
            key.setApiKey("must-not-leak");
            assertThrows(IllegalArgumentException.class, () -> dispatcher.promptWithCapacityFallback(
                    "text", "llm", job(false, List.of(key))));
            assertFalse(dispatcher.isCapableOf(nativeBackend(), "embedding"));
            assertFalse(dispatcher.isCapableOf(nativeBackend(), "vlm"));
            assertEquals(0, calls.get());
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void outputLimitRejectsOversizedTextWithoutFallback() {
        CliAgentRunner cli = mock(CliAgentRunner.class);
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(cli, null,
                (provider, model, prompt, system, timeout) -> "x".repeat(1_048_577));
        try {
            assertNull(dispatcher.promptWithCapacityFallback("text", "llm",
                    job(false, List.of(nativeBackend(), cliBackend()))));
            assertTrue(dispatcher.lastCallFailure().getMessage().contains("output limit"));
            verifyNoInteractions(cli);
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void explicitFallbackStillWorksAndLegacyCliNeedsNoNativeBridge() {
        AtomicInteger cliCalls = new AtomicInteger();
        CliAgentRunner cli = (agent, prompt, timeout) -> {
            assertEquals("codex-cli", agent);
            cliCalls.incrementAndGet();
            return GRAPH;
        };
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(cli, null,
                (provider, model, prompt, system, timeout) -> { throw new IOException("native failure"); });
        CrawlLlmDispatcher legacy = new CrawlLlmDispatcher(cli, null);
        try {
            assertEquals(GRAPH, dispatcher.promptWithCapacityFallback("text", "llm",
                    job(true, List.of(nativeBackend(), cliBackend()))));
            assertEquals(GRAPH, legacy.promptWithCapacityFallback("text", "llm",
                    job(false, List.of(cliBackend()))));
            assertEquals(2, cliCalls.get());
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
            legacy.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void nativeDeadlineInterruptsTheBridgeAndDoesNotLaunchFallback() throws Exception {
        CliAgentRunner cli = mock(CliAgentRunner.class);
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher(cli, null,
                (provider, model, prompt, system, timeout) -> {
                    try {
                        Thread.sleep(30_000);
                        return GRAPH;
                    } catch (InterruptedException cancelled) {
                        interrupted.countDown();
                        throw cancelled;
                    }
                });
        dispatcher.llmCallTimeoutSeconds = 1;
        try {
            assertNull(dispatcher.promptWithCapacityFallback("text", "llm",
                    job(false, List.of(nativeBackend(), cliBackend()))));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, dispatcher.lastCallFailure());
            assertTrue(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            verifyNoInteractions(cli);
        } finally {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    private static ProcessingBackend nativeBackend() {
        return ProcessingBackend.builder().id("native").type(ProcessingBackendType.CHAT_MODEL)
                .provider("codex").modelName("exact-model").priority(1).capabilities(List.of("llm")).build();
    }

    private static ProcessingBackend cliBackend() {
        return ProcessingBackend.builder().id("legacy-cli").type(ProcessingBackendType.CLI_AGENT)
                .agentName("codex-cli").priority(2).capabilities(List.of("llm")).build();
    }

    private static UnifiedCrawlJob job(boolean fallback, List<ProcessingBackend> backends) {
        return UnifiedCrawlJob.builder().jobId("native-test").request(UnifiedCrawlRequest.builder()
                .graphExtraction(GraphExtractionConfig.builder().build())
                .processingRoute(ProcessingRouteConfig.builder().fallbackEnabled(fallback)
                        .servingLaneEnabled(false).backends(backends).build()).build()).build();
    }
}
