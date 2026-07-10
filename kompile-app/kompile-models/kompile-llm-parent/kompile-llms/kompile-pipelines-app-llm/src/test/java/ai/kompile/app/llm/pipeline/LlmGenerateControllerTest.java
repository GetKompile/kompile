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
    void extraRequestFieldsAreIgnored() {
        when(languageModel.isLoaded()).thenReturn(true);
        when(languageModel.generateResponse(eq("p"), eq(List.of()))).thenReturn("r");

        // LocalStagingLlmService sends these extras; they must not break the endpoint
        Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("prompt", "p");
        req.put("maxTokens", 512);
        req.put("temperature", 0.0);
        req.put("topK", 1);
        req.put("doSample", false);

        ResponseEntity<Map<String, Object>> resp = controller.generate(req);
        assertEquals("completed", resp.getBody().get("finishReason"));
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
