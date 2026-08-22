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
        List<UnifiedCrawlJob.LlmCallRecord> observed = new java.util.ArrayList<>();
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
        dispatcher.setLlmCallObserver(observed::add);
        UnifiedCrawlJob job = job();

        StructuredChatLanguageModel.Response response =
                dispatcher.promptStructuredWithCapacityFallback(
                        request(), "llm", job, null);

        assertEquals(1, calls.get());
        assertEquals("<native>", response.rawText());
        assertTrue(dispatcher.hasStructuredChatBackend());
        assertEquals(false, dispatcher.hasLlmChat(),
                "structured capability must not require a raw LLMChat wrapper");
        assertEquals(1, observed.size());
        UnifiedCrawlJob.LlmCallRecord record = observed.get(0);
        assertTrue(record.isStructured());
        assertTrue(record.getStructuredRequestJson().contains("submit_graph_delta"));
        assertTrue(record.getStructuredResponseJson().contains("toolCalls"));
        assertTrue(record.getStructuredResponseJson().contains("call-1"));
        assertEquals(record.getLlmCallId(), job.getRecentLlmCalls().get(0).getLlmCallId());
    }

    @Test
    void structuredFailureIsSurfacedInsteadOfFallingBackToRawGeneration() {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new CrawlLlmDispatcher((request, maxNewTokens) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("native parser unavailable");
        });

        UnifiedCrawlJob job = job();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher.promptStructuredWithCapacityFallback(
                        request(), "llm", job, null));

        assertTrue(failure.getMessage().contains("Structured model call failed"));
        assertEquals(1, calls.get());
        assertEquals(false, dispatcher.hasLlmChat());
        assertEquals(1, job.getRecentLlmCalls().size());
        assertEquals(false, job.getRecentLlmCalls().get(0).isSuccess());
        assertTrue(job.getRecentLlmCalls().get(0).getStructuredRequestJson()
                .contains("submit_graph_delta"));
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
