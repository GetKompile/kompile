package ai.kompile.staging.web;

import ai.kompile.staging.execution.ChatTemplateService;
import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.execution.PromptTemplateService;
import ai.kompile.staging.execution.TextPipelineService;
import ai.kompile.staging.execution.text.DurableLlmGenerationService;
import ai.kompile.staging.execution.text.RegisteredLlmModelResolver;
import ai.kompile.staging.web.dto.LlmGenerateRequest;
import ai.kompile.staging.web.dto.LlmGenerateResponse;
import ai.kompile.staging.web.dto.LlmLoadModelRequest;
import ai.kompile.staging.web.dto.LlmModelStatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LlmExecutionControllerRunBoundTest {

    @Test
    void modelLoadDelegatesOnlyTheRegistryVerifiedFile() {
        LlmExecutionService executionService = mock(LlmExecutionService.class);
        RegisteredLlmModelResolver resolver = mock(RegisteredLlmModelResolver.class);
        Path verifiedPath = Path.of("/models/llms/verified/model.sdnb");
        when(resolver.resolve("verified-llm", null)).thenReturn(
                new RegisteredLlmModelResolver.VerifiedModel(
                        "verified-llm", "v3", "sha256:" + "a".repeat(64), verifiedPath));
        when(executionService.loadModel(
                "verified-llm", verifiedPath.toString(), "STATIC"))
                .thenReturn(LlmModelStatusResponse.builder()
                        .modelId("verified-llm").loaded(true).build());
        LlmExecutionController controller = new LlmExecutionController(
                executionService,
                mock(ChatTemplateService.class),
                mock(PromptTemplateService.class),
                mock(TextPipelineService.class),
                mock(DurableLlmGenerationService.class),
                resolver);

        LlmModelStatusResponse response = controller.loadModel(
                LlmLoadModelRequest.builder().modelId("verified-llm").build()).getBody();

        org.junit.jupiter.api.Assertions.assertNotNull(response);
        org.junit.jupiter.api.Assertions.assertTrue(response.isLoaded());
        verify(resolver).resolve("verified-llm", null);
        verify(executionService).loadModel(
                "verified-llm", verifiedPath.toString(), "STATIC");
    }

    @Test
    void legacyConstructionSeamFailsUnsafeModelLoadWithoutDelegating() {
        LlmExecutionService executionService = mock(LlmExecutionService.class);
        LlmExecutionController controller = new LlmExecutionController(
                executionService,
                mock(ChatTemplateService.class),
                mock(PromptTemplateService.class),
                mock(TextPipelineService.class));

        LlmModelStatusResponse response = controller.loadModel(LlmLoadModelRequest.builder()
                .modelId("claimed-registry-id")
                .modelPath("/arbitrary/unverified/model.sdnb")
                .build()).getBody();

        org.junit.jupiter.api.Assertions.assertNotNull(response);
        org.junit.jupiter.api.Assertions.assertFalse(response.isLoaded());
        verify(executionService, never()).loadModel(any(), any(), any());
    }

    @Test
    void legacyJsonDelegatesUnchangedAndOmitsRunBoundFields() throws Exception {
        LlmExecutionService executionService = mock(LlmExecutionService.class);
        DurableLlmGenerationService durableService = mock(DurableLlmGenerationService.class);
        when(executionService.generate(any())).thenReturn(LlmGenerateResponse.builder()
                .generatedText("legacy response")
                .tokensPerSecond(3.5)
                .firstTokenLatencyMs(7)
                .totalTokens(4)
                .finishReason("COMPLETED")
                .totalTimeMs(11)
                .build());

        mvc(executionService, durableService).perform(post("/api/llm/generate")
                        .contentType("application/json")
                        .content("{\"prompt\":\"legacy prompt\",\"maxTokens\":19}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedText").value("legacy response"))
                .andExpect(jsonPath("$.tokensPerSecond").value(3.5))
                .andExpect(jsonPath("$.firstTokenLatencyMs").value(7))
                .andExpect(jsonPath("$.totalTokens").value(4))
                .andExpect(jsonPath("$.finishReason").value("COMPLETED"))
                .andExpect(jsonPath("$.totalTimeMs").value(11))
                .andExpect(jsonPath("$.runId").doesNotExist())
                .andExpect(jsonPath("$.subjectId").doesNotExist())
                .andExpect(jsonPath("$.derivedFromRevision").doesNotExist())
                .andExpect(jsonPath("$.referenceLanguage").doesNotExist())
                .andExpect(jsonPath("$.learningLanguage").doesNotExist())
                .andExpect(jsonPath("$.configurationEvidence").doesNotExist())
                .andExpect(jsonPath("$.confidence").doesNotExist());

        ArgumentCaptor<LlmGenerateRequest> delegated =
                ArgumentCaptor.forClass(LlmGenerateRequest.class);
        verify(executionService).generate(delegated.capture());
        org.junit.jupiter.api.Assertions.assertEquals(19, delegated.getValue().getMaxTokens());
        verifyNoInteractions(durableService);
    }

    @Test
    void languageClientRunBoundJsonUsesMaxTokensAndReturnsStrictShape() throws Exception {
        LlmExecutionService executionService = mock(LlmExecutionService.class);
        DurableLlmGenerationService durableService = mock(DurableLlmGenerationService.class);
        UUID runId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        UUID subjectId = UUID.fromString("11111111-2222-4abc-8def-555555555555");
        String prompt = "Respond with one plain-text tutor turn.";
        String promptHash = sha256(prompt);
        Instant completedAt = Instant.parse("2026-08-31T04:00:00Z");
        when(durableService.generate(any())).thenReturn(LlmGenerateResponse.builder()
                .runId(runId)
                .subjectId(subjectId)
                .derivedFromRevision(7L)
                .referenceLanguage("en")
                .learningLanguage("vi")
                .generatedText("Chào bạn! Hôm nay bạn thế nào?")
                .modelId("registry-loaded-llm")
                .modelVersion("model-v8")
                .configurationVersion("sha256:" + "f".repeat(64))
                .promptVersion("immersive-prompt-v3")
                .policyVersion("strict-tutor-policy-v2")
                .responseSchemaVersion("plain-text-v1")
                .promptHash(promptHash)
                .completedAt(completedAt)
                .configurationEvidence(Map.of(
                        "registeredModelChecksum", "sha256:" + "a".repeat(64)))
                .confidence(0.0)
                .confidenceSource("UNAVAILABLE")
                .finishReason("COMPLETED")
                .build());

        String body = """
                {
                  "runId":"%s",
                  "subjectId":"%s",
                  "derivedFromRevision":7,
                  "referenceLanguage":"en",
                  "learningLanguage":"vi",
                  "prompt":"%s",
                  "modelRole":"IMMERSIVE_TUTOR",
                  "promptVersion":"immersive-prompt-v3",
                  "policyVersion":"strict-tutor-policy-v2",
                  "responseSchemaVersion":"plain-text-v1",
                  "promptHash":"%s",
                  "maxTokens":512
                }
                """.formatted(runId, subjectId, prompt, promptHash);

        mvc(executionService, durableService).perform(post("/api/llm/generate")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.subjectId").value(subjectId.toString()))
                .andExpect(jsonPath("$.derivedFromRevision").value(7))
                .andExpect(jsonPath("$.referenceLanguage").value("en"))
                .andExpect(jsonPath("$.learningLanguage").value("vi"))
                .andExpect(jsonPath("$.generatedText")
                        .value("Chào bạn! Hôm nay bạn thế nào?"))
                .andExpect(jsonPath("$.modelId").value("registry-loaded-llm"))
                .andExpect(jsonPath("$.modelVersion").value("model-v8"))
                .andExpect(jsonPath("$.configurationVersion")
                        .value("sha256:" + "f".repeat(64)))
                .andExpect(jsonPath("$.promptVersion").value("immersive-prompt-v3"))
                .andExpect(jsonPath("$.policyVersion").value("strict-tutor-policy-v2"))
                .andExpect(jsonPath("$.responseSchemaVersion").value("plain-text-v1"))
                .andExpect(jsonPath("$.promptHash").value(promptHash))
                .andExpect(jsonPath("$.completedAt").value(completedAt.toString()))
                .andExpect(jsonPath("$.configurationEvidence.registeredModelChecksum")
                        .value("sha256:" + "a".repeat(64)))
                .andExpect(jsonPath("$.confidence").value(0.0))
                .andExpect(jsonPath("$.confidenceSource").value("UNAVAILABLE"));

        ArgumentCaptor<LlmGenerateRequest> request =
                ArgumentCaptor.forClass(LlmGenerateRequest.class);
        verify(durableService).generate(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals(512, request.getValue().getMaxTokens());
        org.junit.jupiter.api.Assertions.assertEquals(runId.toString(), request.getValue().getRunId());
        org.junit.jupiter.api.Assertions.assertEquals(
                subjectId.toString(), request.getValue().getSubjectId());
        org.junit.jupiter.api.Assertions.assertEquals(
                7L, request.getValue().getDerivedFromRevision());
        org.junit.jupiter.api.Assertions.assertEquals(
                "en", request.getValue().getReferenceLanguage());
        org.junit.jupiter.api.Assertions.assertEquals(
                "vi", request.getValue().getLearningLanguage());
        org.junit.jupiter.api.Assertions.assertEquals(promptHash, request.getValue().getPromptHash());
        verifyNoInteractions(executionService);
    }

    @Test
    void mapsRunConflictAndMalformedExecutionToTypedNonSuccessStatuses() throws Exception {
        LlmExecutionService executionService = mock(LlmExecutionService.class);
        DurableLlmGenerationService durableService = mock(DurableLlmGenerationService.class);
        String request = validRequest(UUID.randomUUID(), "hello");
        when(durableService.generate(any())).thenThrow(
                new DurableLlmGenerationService.IdempotencyConflictException("runId is in use"));

        mvc(executionService, durableService).perform(post("/api/llm/generate")
                        .contentType("application/json").content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LLM_RUN_CONFLICT"));

        reset(durableService);
        when(durableService.generate(any())).thenThrow(
                new DurableLlmGenerationService.InvalidExecutionResultException(
                        "The LLM generation backend returned blank text"));
        mvc(executionService, durableService).perform(post("/api/llm/generate")
                        .contentType("application/json").content(request))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_GENERATION_INVALID"));
    }

    @Test
    void unexpectedRunFailureDoesNotExposeInternalExceptionText() throws Exception {
        LlmExecutionService executionService = mock(LlmExecutionService.class);
        DurableLlmGenerationService durableService = mock(DurableLlmGenerationService.class);
        when(durableService.generate(any())).thenThrow(
                new IllegalStateException("sensitive /internal/model/path"));

        mvc(executionService, durableService).perform(post("/api/llm/generate")
                        .contentType("application/json")
                        .content(validRequest(UUID.randomUUID(), "hello")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LLM_GENERATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive"))));
    }

    private static MockMvc mvc(LlmExecutionService executionService,
                               DurableLlmGenerationService durableService) {
        return MockMvcBuilders.standaloneSetup(new LlmExecutionController(
                executionService,
                mock(ChatTemplateService.class),
                mock(PromptTemplateService.class),
                mock(TextPipelineService.class),
                durableService)).build();
    }

    private static String validRequest(UUID runId, String prompt) {
        UUID subjectId = UUID.fromString("11111111-2222-4abc-8def-555555555555");
        return """
                {"runId":"%s","subjectId":"%s","derivedFromRevision":7,
                 "referenceLanguage":"en","learningLanguage":"vi",
                 "prompt":"%s","modelRole":"IMMERSIVE_TUTOR",
                 "promptVersion":"prompt-v1","policyVersion":"policy-v1",
                 "responseSchemaVersion":"plain-v1","promptHash":"%s","maxTokens":64}
                """.formatted(runId, subjectId, prompt, sha256(prompt));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
