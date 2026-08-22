package ai.kompile.app.llm.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmGenerateController}.
 *
 * <p>These are plain Mockito tests (no Spring context) that verify the wire
 * contract: HTTP 200 always, {@code finishReason="completed"} on success,
 * {@code finishReason="error: ..."} on failure, and all required JSON fields
 * present in the response body.</p>
 */
@ExtendWith(MockitoExtension.class)
class LlmGenerateControllerTest {

    @Mock
    private SameDiffLanguageModelImpl languageModel;

    private LlmGenerateController controller;

    @BeforeEach
    void setUp() {
        controller = new LlmGenerateController(languageModel);
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    void nullBodyReturnsError() {
        ResponseEntity<Map<String, Object>> resp = controller.generate(null);
        assertOkWithErrorFinishReason(resp);
        verify(languageModel, never()).generateResponse(any(), any());
    }

    @Test
    void emptyBodyMissingPromptReturnsError() {
        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of());
        assertOkWithErrorFinishReason(resp);
        verify(languageModel, never()).generateResponse(any(), any());
    }

    @Test
    void blankPromptReturnsError() {
        // blank-prompt guard fires before isLoaded() is consulted — no stub needed
        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of("prompt", "   "));
        assertOkWithErrorFinishReason(resp);
        verify(languageModel, never()).isLoaded();
        verify(languageModel, never()).generateResponse(any(), any());
    }

    // ── No model loaded ───────────────────────────────────────────────────────

    @Test
    void noModelLoadedReturnsErrorFinishReason() {
        when(languageModel.isLoaded()).thenReturn(false);
        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of("prompt", "hello"));
        assertOkWithErrorFinishReason(resp);
        // Verify no attempt to generate
        verify(languageModel, never()).generateResponse(any(), any());
    }

    @Test
    void noModelLoadedErrorMessageContainsHint() {
        when(languageModel.isLoaded()).thenReturn(false);
        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of("prompt", "hello"));
        String fr = (String) resp.getBody().get("finishReason");
        assertTrue(fr.contains("no model loaded"), "error message should hint at load; got: " + fr);
    }

    // ── Successful generation ─────────────────────────────────────────────────

    @Test
    void successReturnsCompletedFinishReason() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(eq("hello"), eq(List.of()))).thenReturn("world");

        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of("prompt", "hello"));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("completed", resp.getBody().get("finishReason"));
        assertEquals("world", resp.getBody().get("generatedText"));
    }

    @Test
    void successResponseIncludesAllWireContractFields() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(any(), any())).thenReturn("ok");

        Map<String, Object> body = controller.generate(Map.of("prompt", "test")).getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("generatedText"),     "missing generatedText");
        assertTrue(body.containsKey("finishReason"),      "missing finishReason");
        assertTrue(body.containsKey("totalTimeMs"),       "missing totalTimeMs");
        assertTrue(body.containsKey("tokensPerSecond"),   "missing tokensPerSecond");
        assertTrue(body.containsKey("firstTokenLatencyMs"), "missing firstTokenLatencyMs");
        assertTrue(body.containsKey("totalTokens"),       "missing totalTokens");
    }

    @Test
    void promptIsPassedToLanguageModelWithEmptyContext() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(any(), any())).thenReturn("response");

        controller.generate(Map.of("prompt", "my prompt"));

        verify(languageModel).generateResponse(eq("my prompt"), eq(List.of()));
    }

    @Test
    void requestTokenBudgetIsHonoredWhileOtherExtraFieldsRemainCompatible() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(eq("p"), eq(List.of()), eq(512))).thenReturn("r");

        Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("prompt", "p");
        req.put("maxTokens", 512);
        req.put("temperature", 0.0);
        req.put("topK", 1);
        req.put("doSample", false);

        ResponseEntity<Map<String, Object>> resp = controller.generate(req);

        assertEquals("completed", resp.getBody().get("finishReason"));
        verify(languageModel).generateResponse("p", List.of(), 512);
        verify(languageModel, never()).generateResponse("p", List.of());
    }

    @Test
    void requestTokenBudgetIsCappedAtServingSafetyLimit() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(
                eq("p"), eq(List.of()), eq(LlmGenerateController.MAX_REQUEST_MAX_TOKENS)))
                .thenReturn("r");

        ResponseEntity<Map<String, Object>> resp =
                controller.generate(Map.of("prompt", "p", "maxTokens", 50_000));

        assertEquals("completed", resp.getBody().get("finishReason"));
        verify(languageModel).generateResponse(
                "p", List.of(), LlmGenerateController.MAX_REQUEST_MAX_TOKENS);
    }

    @Test
    void oversizedBigIntegerTokenBudgetIsCappedWithoutNarrowingOverflow() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(
                eq("p"), eq(List.of()), eq(LlmGenerateController.MAX_REQUEST_MAX_TOKENS)))
                .thenReturn("r");

        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of(
                "prompt", "p",
                "maxTokens", new java.math.BigInteger("18446744073709551617")));

        assertEquals("completed", resp.getBody().get("finishReason"));
        verify(languageModel).generateResponse(
                "p", List.of(), LlmGenerateController.MAX_REQUEST_MAX_TOKENS);
    }

    @Test
    void invalidRequestTokenBudgetReturnsGenerationErrorWithoutCallingModel() {
        ResponseEntity<Map<String, Object>> resp =
                controller.generate(Map.of("prompt", "p", "maxTokens", 0));

        assertOkWithErrorFinishReason(resp);
        verify(languageModel, never()).isLoaded();
        verify(languageModel, never()).generateResponse(any(), any(), anyInt());
    }

    @Test
    void structuredChatPreservesRolesToolsFormatsAndParsedCalls() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateChat(
                any(ai.kompile.core.llm.StructuredChatLanguageModel.Request.class), eq(128)))
                .thenReturn(new ai.kompile.core.llm.StructuredChatLanguageModel.Response(
                        "<think>inspect source</think><|tool_call_start|>"
                                + "[submit_graph_delta(entities=[], relations=[])]<|tool_call_end|>",
                        "",
                        "inspect source",
                        List.of(new ai.kompile.core.llm.StructuredChatLanguageModel.ToolCall(
                                "call-1", "submit_graph_delta",
                                Map.of("entities", List.of(), "relations", List.of()))),
                        List.of()));

        Map<String, Object> request = Map.of(
                "request", Map.of(
                        "messages", List.of(
                                Map.of("role", "system", "content", "extract"),
                                Map.of("role", "user", "content", "source")),
                        "tools", List.of(Map.of(
                                "name", "submit_graph_delta",
                                "description", "submit",
                                "parameters", Map.of("type", "object"))),
                        "addGenerationPrompt", true,
                        "toolDefinitionFormat", "FLAT",
                        "toolCallFormat", "NATIVE"),
                "maxTokens", 128,
                "correlation", Map.of(
                        "crawlJobId", "local-11111111-1111-4111-8111-111111111111",
                        "subprocessRunId", "runtime-1",
                        "transportRequestId", "request-1"));

        ResponseEntity<Map<String, Object>> response = controller.chat(request);

        assertEquals("completed", response.getBody().get("finishReason"));
        assertEquals("", response.getBody().get("content"));
        assertEquals("inspect source", response.getBody().get("reasoningContent"));
        assertEquals(1, ((List<?>) response.getBody().get("toolCalls")).size());
        assertEquals(request.get("correlation"), response.getBody().get("correlation"));
        var captor = org.mockito.ArgumentCaptor.forClass(
                ai.kompile.core.llm.StructuredChatLanguageModel.Request.class);
        verify(languageModel).generateChat(captor.capture(), eq(128));
        assertEquals(List.of("system", "user"),
                captor.getValue().messages().stream().map(
                        ai.kompile.core.llm.StructuredChatLanguageModel.Message::role).toList());
        assertEquals(ai.kompile.core.llm.StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                captor.getValue().toolDefinitionFormat());
        assertEquals(ai.kompile.core.llm.StructuredChatLanguageModel.ToolCallFormat.NATIVE,
                captor.getValue().toolCallFormat());
    }

    @Test
    void structuredChatNeverFallsBackToRawGeneration() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateChat(
                any(ai.kompile.core.llm.StructuredChatLanguageModel.Request.class), eq(64)))
                .thenThrow(new IllegalStateException("native parser failed"));

        ResponseEntity<Map<String, Object>> response = controller.chat(Map.of(
                "request", Map.of(
                        "messages", List.of(Map.of("role", "user", "content", "source")),
                        "tools", List.of(),
                        "addGenerationPrompt", true,
                        "toolDefinitionFormat", "FLAT",
                        "toolCallFormat", "NATIVE"),
                "maxTokens", 64));

        assertOkWithErrorFinishReason(response);
        verify(languageModel, never()).generateResponse(any(), any());
        verify(languageModel, never()).generateResponse(any(), any(), anyInt());
    }

    // ── Generation failure ────────────────────────────────────────────────────

    @Test
    void generationExceptionReturnsErrorFinishReason() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(any(), any()))
                .thenThrow(new RuntimeException("runner exploded"));

        ResponseEntity<Map<String, Object>> resp = controller.generate(Map.of("prompt", "hello"));
        assertOkWithErrorFinishReason(resp);
        String fr = (String) resp.getBody().get("finishReason");
        assertTrue(fr.contains("runner exploded"), "error message should contain cause; got: " + fr);
    }

    @Test
    void generationExceptionResponseIncludesEmptyGeneratedText() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(any(), any()))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> body = controller.generate(Map.of("prompt", "hi")).getBody();
        assertEquals("", body.get("generatedText"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertOkWithErrorFinishReason(ResponseEntity<Map<String, Object>> resp) {
        assertNotNull(resp);
        assertNotNull(resp.getBody());
        assertEquals(200, resp.getStatusCode().value(),
                "always HTTP 200 (error encoded in finishReason)");
        String fr = (String) resp.getBody().get("finishReason");
        assertNotNull(fr, "finishReason must be present");
        assertTrue(fr.toLowerCase().startsWith("error"),
                "finishReason should start with 'error', got: " + fr);
    }
}
