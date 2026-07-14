package ai.kompile.staging.web;

import ai.kompile.staging.execution.audio.AudioSynthesisManifest;
import ai.kompile.staging.execution.audio.AudioSynthesisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioSynthesisControllerTest {

    @TempDir
    Path temporary;

    @Test
    void requiresBearerAuthenticationForGenerationAndTransfer() throws Exception {
        AudioSynthesisService service = mock(AudioSynthesisService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/audio/synthesize")
                        .contentType("application/json")
                        .content("""
                                {"runId":"%s","text":"hello"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUDIO_TRANSFER_UNAUTHORIZED"));

        mvc.perform(get("/api/audio/artifacts/{id}/content", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsIncompleteAuthenticatedRequestsBeforeCallingTheModelService() throws Exception {
        AudioSynthesisService service = mock(AudioSynthesisService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/audio/synthesize")
                        .header("Authorization", "Bearer transfer-secret")
                        .contentType("application/json")
                        .content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void doesNotExposeInternalFailureDetails() throws Exception {
        AudioSynthesisService service = mock(AudioSynthesisService.class);
        UUID runId = UUID.randomUUID();
        when(service.synthesize(any())).thenThrow(
                new IllegalStateException("sensitive /staging/model/path"));
        when(service.openArtifact(runId)).thenThrow(
                new IllegalStateException("sensitive /staging/artifact/path"));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/audio/synthesize")
                        .header("Authorization", "Bearer transfer-secret")
                        .contentType("application/json")
                        .content(("{\"runId\":\"%s\",\"text\":\"hello\"}").formatted(runId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Audio synthesis failed"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive"))));

        mvc.perform(get("/api/audio/artifacts/{id}/content", runId)
                        .header("Authorization", "Bearer transfer-secret"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Audio artifact transfer failed"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive"))));
    }

    @Test
    void returnsACompleteManifestAndStreamsOnlyTheOpaqueArtifactReference() throws Exception {
        AudioSynthesisService service = mock(AudioSynthesisService.class);
        UUID runId = UUID.randomUUID();
        byte[] bytes = "streamed-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path content = temporary.resolve("content");
        Files.write(content, bytes);
        AudioSynthesisManifest manifest = new AudioSynthesisManifest(
                runId, runId.toString(), "audio/wav", "a".repeat(64), bytes.length,
                0.9, "speech-model", "v1", "config-v2", Map.of("voice", "standard"));
        when(service.synthesize(any())).thenReturn(manifest);
        when(service.openArtifact(runId))
                .thenReturn(new AudioSynthesisService.ArtifactContent(manifest, content));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/audio/synthesize")
                        .header("Authorization", "Bearer transfer-secret")
                        .contentType("application/json")
                        .content("""
                                {"runId":"%s","text":"hello","voice":"standard",
                                 "configuration":{"temperature":0.1}}
                                """.formatted(runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.artifactReference").value(runId.toString()))
                .andExpect(jsonPath("$.byteLength").value(bytes.length))
                .andExpect(jsonPath("$.configurationVersion").value("config-v2"));

        mvc.perform(get("/api/audio/artifacts/{id}/content", runId)
                        .header("Authorization", "Bearer transfer-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/wav"))
                .andExpect(header().longValue("Content-Length", bytes.length))
                .andExpect(content().bytes(bytes));

        mvc.perform(head("/api/audio/artifacts/{id}/content", runId)
                        .header("Authorization", "Bearer transfer-secret"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", bytes.length))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void mapsMissingAndGoneGeneratedFilesToTypedResponses() throws Exception {
        AudioSynthesisService service = mock(AudioSynthesisService.class);
        UUID missing = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(service.openArtifact(missing))
                .thenThrow(new AudioSynthesisService.ArtifactNotFoundException("missing"));
        when(service.openArtifact(gone))
                .thenThrow(new AudioSynthesisService.ArtifactGoneException("gone"));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/audio/artifacts/{id}/content", missing)
                        .header("Authorization", "Bearer transfer-secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIO_ARTIFACT_NOT_FOUND"));
        mvc.perform(get("/api/audio/artifacts/{id}/content", gone)
                        .header("Authorization", "Bearer transfer-secret"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUDIO_ARTIFACT_GONE"));
    }

    private static MockMvc mvc(AudioSynthesisService service) {
        return MockMvcBuilders.standaloneSetup(
                new AudioSynthesisController(service, "transfer-secret")).build();
    }
}
