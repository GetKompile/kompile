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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlLlmDispatcherStructuredChatTest {

    private CrawlLlmDispatcher dispatcher;

    @AfterEach
    void closeDispatcher() {
        if (dispatcher != null) {
            dispatcher.shutdownLlmTimeoutExecutor();
        }
    }

    @Test
    void dispatchesStructuredCapabilityWithoutRawPromptSerialization() {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new CrawlLlmDispatcher((request, maxNewTokens) -> {
            calls.incrementAndGet();
            assertEquals(128, maxNewTokens);
            assertEquals(StructuredChatLanguageModel.ToolCallFormat.MODEL,
                    request.toolCallFormat());
            assertEquals("submit_graph_delta", request.tools().get(0).name());
            return new StructuredChatLanguageModel.Response(
                    "<native>", "", List.of(new StructuredChatLanguageModel.ToolCall(
                            "call-1", "submit_graph_delta",
                            Map.of("entities", List.of(), "relations", List.of()))), List.of());
        });

        StructuredChatLanguageModel.Response response =
                dispatcher.promptStructuredWithCapacityFallback(
                        request(), "llm", job(), null);

        assertEquals(1, calls.get());
        assertEquals("<native>", response.rawText());
        assertTrue(dispatcher.hasStructuredChatBackend());
        assertEquals(false, dispatcher.hasLlmChat(),
                "structured capability must not require a raw LLMChat wrapper");
    }

    @Test
    void structuredFailureIsSurfacedInsteadOfFallingBackToRawGeneration() {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new CrawlLlmDispatcher((request, maxNewTokens) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("native parser unavailable");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher.promptStructuredWithCapacityFallback(
                        request(), "llm", job(), null));

        assertTrue(failure.getMessage().contains("Structured model call failed"));
        assertEquals(1, calls.get());
        assertEquals(false, dispatcher.hasLlmChat());
    }

    private static StructuredChatLanguageModel.Request request() {
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
                .jobId("structured-dispatch-test")
                .request(UnifiedCrawlRequest.builder()
                        .graphExtraction(graph)
                        .build())
                .build();
    }
}
