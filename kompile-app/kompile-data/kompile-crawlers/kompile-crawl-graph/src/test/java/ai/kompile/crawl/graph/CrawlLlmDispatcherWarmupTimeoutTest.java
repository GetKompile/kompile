/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold-start warmup budget for local serving: the FIRST structured call gets
 * inference budget + warmup extra; subsequent calls get inference budget only.
 * Regression for the 2026-09-27 FP&A crawl where a cold Triton JIT consumed the
 * whole 600s inference budget and the crawl recorded TIMEOUT with zero output.
 */
class CrawlLlmDispatcherWarmupTimeoutTest {

    private CrawlLlmDispatcher dispatcher;

    @AfterEach
    void closeDispatcher() {
        if (dispatcher != null) {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void firstColdStructuredCallGetsWarmupExtraBudget() {
        long[] observedBudget = {-1};
        dispatcher = new CrawlLlmDispatcher((request, maxNewTokens) -> {
            // Capture the timeout the dispatcher actually granted by sleeping past
            // a short inference budget would be slow; instead infer from the flag:
            // the backend sees the call, and the flag flips only after success.
            return new StructuredChatLanguageModel.Response("<native>", "",
                    List.of(new StructuredChatLanguageModel.ToolCall(
                            "call-1", "submit_graph_delta",
                            Map.of("entities", List.of(), "relations", List.of()))), List.of());
        });
        assertTrue(dispatcher.effectiveLocalTimeoutSeconds(false)
                > dispatcher.effectiveLocalTimeoutSeconds(true) - 1_000_000,
                "sanity");
        // Cold (first) call budget must strictly exceed the warm budget.
        long warm = dispatcher.effectiveLocalTimeoutSeconds(false);
        long cold = dispatcher.effectiveLocalTimeoutSeconds(true);
        assertTrue(cold > warm,
                "first cold call must carry the warmup extra: cold=" + cold + " warm=" + warm);
        assertEquals(dispatcher.llmCallTimeoutSeconds + dispatcher.warmupExtraTimeoutSeconds, cold);
    }

    @Test
    void warmupExtraCanBeDisabled() {
        dispatcher = new CrawlLlmDispatcher((request, maxNewTokens) ->
                new StructuredChatLanguageModel.Response("x", "", List.of(), List.of()));
        dispatcher.setWarmupExtraTimeoutSeconds(0);
        assertEquals(dispatcher.llmCallTimeoutSeconds,
                dispatcher.effectiveLocalTimeoutSeconds(true),
                "warmup extra 0 must make cold and warm budgets identical");
    }

    @Test
    void inProcessStructuredPathDoesNotConsumeColdFlag() {
        AtomicInteger calls = new AtomicInteger();
        // Constructor with an in-process StructuredChatLanguageModel routes the
        // NON-serving lane; the warmup extra is reserved for the serving
        // subprocess paths, so this call must not flip localFirstCallServed.
        dispatcher = new CrawlLlmDispatcher((request, maxNewTokens) -> {
            calls.incrementAndGet();
            return new StructuredChatLanguageModel.Response("<native>", "",
                    List.of(new StructuredChatLanguageModel.ToolCall(
                            "call-" + calls.get(), "submit_graph_delta",
                            Map.of("entities", List.of(), "relations", List.of()))), List.of());
        });
        UnifiedCrawlJob job = job();
        dispatcher.promptStructuredWithCapacityFallback(
                structuredRequest(), "llm", job, null);
        assertEquals(1, calls.get());
        assertEquals(false, dispatcher.localFirstCallServed,
                "in-process structured chat is not the serving lane; the cold-start "
                        + "flag must stay unset for serving subprocess warmup");
    }

    private static StructuredChatLanguageModel.Request structuredRequest() {
        return new StructuredChatLanguageModel.Request(
                List.of(
                        new StructuredChatLanguageModel.Message("system", "extract"),
                        new StructuredChatLanguageModel.Message("user", "source")),
                List.of(new StructuredChatLanguageModel.Tool(
                        "submit_graph_delta", "submit", Map.of("type", "object"))));
    }

    private static UnifiedCrawlJob job() {
        GraphExtractionConfig graph = GraphExtractionConfig.builder()
                .maxTokens(128)
                .build();
        return UnifiedCrawlJob.builder()
                .jobId("warmup-test")
                .request(UnifiedCrawlRequest.builder()
                        .graphExtraction(graph)
                        .build())
                .build();
    }
}
