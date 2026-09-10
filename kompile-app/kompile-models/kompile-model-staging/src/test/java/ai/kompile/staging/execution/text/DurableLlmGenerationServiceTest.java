package ai.kompile.staging.execution.text;

import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.execution.LlmExecutionService.ExecutionConfigurationSnapshot;
import ai.kompile.staging.web.dto.LlmGenerateRequest;
import ai.kompile.staging.web.dto.LlmGenerateResponse;
import ai.kompile.staging.web.dto.LlmModelStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DurableLlmGenerationServiceTest {

    private static final String MODEL_ID = "registry-loaded-llm";
    private static final String MODEL_VERSION = "model-v8";
    private static final String MODEL_CHECKSUM = "sha256:" + "a".repeat(64);
    private static final Path MODEL_PATH = Path.of("/verified/models/registry-loaded/model.sdnb");
    private static final UUID SUBJECT_ID =
            UUID.fromString("11111111-2222-4abc-8def-555555555555");
    private static final long DERIVED_FROM_REVISION = 7;
    private static final String REFERENCE_LANGUAGE = "en";
    private static final String LEARNING_LANGUAGE = "vi";

    @TempDir
    Path temporary;

    private LlmExecutionService executionService;
    private RegisteredLlmModelResolver registeredModelResolver;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        executionService = mock(LlmExecutionService.class);
        registeredModelResolver = mock(RegisteredLlmModelResolver.class);
        objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        when(executionService.getStatus()).thenReturn(LlmModelStatusResponse.builder()
                .modelId(MODEL_ID)
                .loaded(true)
                .decoderPath(MODEL_PATH.toString())
                .message("ready")
                .build());
        when(executionService.isGenerating()).thenReturn(false);
        when(registeredModelResolver.resolve(MODEL_ID, MODEL_PATH.toString()))
                .thenReturn(verifiedModel());
        when(executionService.snapshotExecutionConfiguration(any()))
                .thenReturn(snapshot("<END>"));
        when(executionService.generate(any())).thenReturn(success("generated tutor reply"));
    }

    @Test
    void createsRegistryAuthoritativeRunBoundResponseAndUsesActualMaxTokens() throws Exception {
        Path root = temporary.resolve("runs");
        DurableLlmGenerationService service = service(root);
        UUID runId = UUID.randomUUID();
        String prompt = "private prompt that must never be stored";
        LlmGenerateRequest request = request(runId, prompt);

        LlmGenerateResponse response = service.generate(request);

        assertEquals(runId, response.getRunId());
        assertEquals(SUBJECT_ID, response.getSubjectId());
        assertEquals(DERIVED_FROM_REVISION, response.getDerivedFromRevision());
        assertEquals(REFERENCE_LANGUAGE, response.getReferenceLanguage());
        assertEquals(LEARNING_LANGUAGE, response.getLearningLanguage());
        assertEquals("generated tutor reply", response.getGeneratedText());
        assertEquals(MODEL_ID, response.getModelId());
        assertEquals(MODEL_VERSION, response.getModelVersion());
        assertTrue(response.getConfigurationVersion().matches("sha256:[0-9a-f]{64}"));
        assertEquals("immersive-prompt-v3", response.getPromptVersion());
        assertEquals("strict-tutor-policy-v2", response.getPolicyVersion());
        assertEquals("plain-text-v1", response.getResponseSchemaVersion());
        assertEquals(sha256(prompt), response.getPromptHash());
        assertNotNull(response.getCompletedAt());
        assertEquals(0.0, response.getConfidence());
        assertEquals("UNAVAILABLE", response.getConfidenceSource());
        assertEquals(MODEL_CHECKSUM,
                response.getConfigurationEvidence().get("registeredModelChecksum"));
        assertEquals(SUBJECT_ID.toString(),
                response.getConfigurationEvidence().get("subjectId"));
        assertEquals(DERIVED_FROM_REVISION,
                ((Number) response.getConfigurationEvidence()
                        .get("derivedFromRevision")).longValue());
        assertEquals(REFERENCE_LANGUAGE,
                response.getConfigurationEvidence().get("referenceLanguage"));
        assertEquals(LEARNING_LANGUAGE,
                response.getConfigurationEvidence().get("learningLanguage"));
        assertEquals(response.getConfigurationVersion(),
                response.getConfigurationEvidence().get("stagingConfigurationVersion"));
        assertEquals("This backend does not provide a calibrated confidence score",
                response.getConfigurationEvidence().get("confidenceExplanation"));
        Map<?, ?> settings = (Map<?, ?>) response.getConfigurationEvidence()
                .get("generationSettings");
        assertEquals(777, settings.get("maxTokens"));
        assertEquals(0.25, settings.get("temperature"));

        ArgumentCaptor<LlmGenerateRequest> delegated =
                ArgumentCaptor.forClass(LlmGenerateRequest.class);
        verify(executionService).generate(delegated.capture());
        assertEquals(777, delegated.getValue().getMaxTokens());

        String manifest = Files.readString(root.resolve(runId.toString()).resolve("manifest.json"));
        assertFalse(manifest.contains(prompt), "the manifest must not retain the raw prompt");
        assertTrue(manifest.contains("generated tutor reply"));
    }

    @Test
    void identicalReplaySurvivesServiceRestartWithoutGeneratingAgain() throws Exception {
        Path root = temporary.resolve("restart-runs");
        LlmGenerateRequest request = request(UUID.randomUUID(), "durable replay prompt");
        LlmGenerateResponse first = service(root).generate(request);

        DurableLlmGenerationService restarted = service(root);
        LlmGenerateResponse replay = restarted.generate(request);

        assertEquals(responseJson(first), responseJson(replay));
        verify(executionService, times(1)).generate(any());
    }

    @Test
    void replayRejectsAManifestWithMissingRunBoundIdentity() throws Exception {
        Path root = temporary.resolve("identity-manifest-runs");
        LlmGenerateRequest request = request(UUID.randomUUID(), "identity manifest prompt");
        service(root).generate(request);
        Path manifestPath = root.resolve(request.getRunId()).resolve("manifest.json");
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                        manifestPath.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("response"))
                .remove("subjectId");
        objectMapper.writeValue(manifestPath.toFile(), manifest);

        assertThrows(DurableLlmGenerationService.CorruptRunManifestException.class,
                () -> service(root).generate(request));
        verify(executionService, times(1)).generate(any());
    }

    @Test
    void conflictingRunReuseIsRejected() {
        DurableLlmGenerationService service = service(temporary.resolve("conflict-runs"));
        UUID runId = UUID.randomUUID();
        LlmGenerateRequest original = request(runId, "same prompt");
        service.generate(original);
        LlmGenerateRequest conflicting = request(runId, "same prompt");
        conflicting.setTopP(0.7);

        assertThrows(DurableLlmGenerationService.IdempotencyConflictException.class,
                () -> service.generate(conflicting));
        verify(executionService, times(1)).generate(any());
    }

    @Test
    void everyRunBoundIdentityFieldParticipatesInTheRequestHash() {
        DurableLlmGenerationService service = service(temporary.resolve("identity-conflict-runs"));
        UUID runId = UUID.randomUUID();
        String prompt = "same identity-bound prompt";
        service.generate(request(runId, prompt));

        LlmGenerateRequest changedSubject = request(runId, prompt);
        changedSubject.setSubjectId(UUID.randomUUID().toString());
        LlmGenerateRequest changedRevision = request(runId, prompt);
        changedRevision.setDerivedFromRevision(DERIVED_FROM_REVISION + 1);
        LlmGenerateRequest changedReferenceLanguage = request(runId, prompt);
        changedReferenceLanguage.setReferenceLanguage("fr");
        LlmGenerateRequest changedLearningLanguage = request(runId, prompt);
        changedLearningLanguage.setLearningLanguage("de");

        for (LlmGenerateRequest conflicting : List.of(
                changedSubject, changedRevision,
                changedReferenceLanguage, changedLearningLanguage)) {
            assertThrows(DurableLlmGenerationService.IdempotencyConflictException.class,
                    () -> service.generate(conflicting));
        }
        verify(executionService, times(1)).generate(any());
    }

    @Test
    void requiresCanonicalRunAndSubjectIdentityWithCompleteRequestBinding() {
        DurableLlmGenerationService service = service(temporary.resolve("validation-runs"));
        LlmGenerateRequest missingHash = request(UUID.randomUUID(), "hello");
        missingHash.setPromptHash(null);
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(missingHash));

        LlmGenerateRequest wrongHash = request(UUID.randomUUID(), "hello");
        wrongHash.setPromptHash("b".repeat(64));
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(wrongHash));

        LlmGenerateRequest uppercaseHash = request(UUID.randomUUID(), "hello");
        uppercaseHash.setPromptHash(uppercaseHash.getPromptHash().toUpperCase());
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(uppercaseHash));

        LlmGenerateRequest uppercaseRun = request(UUID.randomUUID(), "hello");
        uppercaseRun.setRunId(uppercaseRun.getRunId().toUpperCase());
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(uppercaseRun));

        LlmGenerateRequest missingSubject = request(UUID.randomUUID(), "hello");
        missingSubject.setSubjectId(null);
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(missingSubject));

        LlmGenerateRequest uppercaseSubject = request(UUID.randomUUID(), "hello");
        uppercaseSubject.setSubjectId(uppercaseSubject.getSubjectId().toUpperCase());
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(uppercaseSubject));

        LlmGenerateRequest missingRevision = request(UUID.randomUUID(), "hello");
        missingRevision.setDerivedFromRevision(null);
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(missingRevision));

        LlmGenerateRequest nonPositiveRevision = request(UUID.randomUUID(), "hello");
        nonPositiveRevision.setDerivedFromRevision(0L);
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(nonPositiveRevision));

        LlmGenerateRequest unstableReferenceLanguage = request(UUID.randomUUID(), "hello");
        unstableReferenceLanguage.setReferenceLanguage(" en");
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(unstableReferenceLanguage));

        LlmGenerateRequest missingLearningLanguage = request(UUID.randomUUID(), "hello");
        missingLearningLanguage.setLearningLanguage(" ");
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(missingLearningLanguage));

        LlmGenerateRequest identicalLanguages = request(UUID.randomUUID(), "hello");
        identicalLanguages.setLearningLanguage(REFERENCE_LANGUAGE);
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(identicalLanguages));

        LlmGenerateRequest missingPolicy = request(UUID.randomUUID(), "hello");
        missingPolicy.setPolicyVersion(" ");
        assertThrows(DurableLlmGenerationService.InvalidRunRequestException.class,
                () -> service.generate(missingPolicy));
        verify(executionService, never()).generate(any());
    }

    @Test
    void rejectsUnverifiedLoadedPathBeforeGeneration() {
        DurableLlmGenerationService service = service(temporary.resolve("registry-runs"));
        String wrongPath = temporary.resolve("arbitrary-model.sdnb").toString();
        when(executionService.getStatus()).thenReturn(LlmModelStatusResponse.builder()
                .modelId(MODEL_ID).loaded(true).decoderPath(wrongPath).build());
        when(registeredModelResolver.resolve(MODEL_ID, wrongPath)).thenThrow(
                new RegisteredLlmModelResolver.ModelVerificationException(
                        "explicit path mismatch"));

        assertThrows(DurableLlmGenerationService.GenerationUnavailableException.class,
                () -> service.generate(request(UUID.randomUUID(), "wrong loaded path")));
        verify(executionService, never()).generate(any());
    }

    @Test
    void rejectsChecksumMismatchDetectedAfterGenerationAndWritesNoManifest() {
        Path root = temporary.resolve("checksum-change-runs");
        UUID runId = UUID.randomUUID();
        when(registeredModelResolver.resolve(MODEL_ID, MODEL_PATH.toString()))
                .thenReturn(verifiedModel())
                .thenThrow(new RegisteredLlmModelResolver.ModelVerificationException(
                        "checksum mismatch"));

        assertThrows(DurableLlmGenerationService.GenerationUnavailableException.class,
                () -> service(root).generate(request(runId, "model bytes changed")));

        assertFalse(Files.exists(root.resolve(runId.toString()).resolve("manifest.json")));
        verify(registeredModelResolver, times(2))
                .resolve(MODEL_ID, MODEL_PATH.toString());
    }

    @Test
    void rejectsEffectiveConfigurationChangeDuringGeneration() {
        Path root = temporary.resolve("configuration-change-runs");
        UUID runId = UUID.randomUUID();
        when(executionService.snapshotExecutionConfiguration(any()))
                .thenReturn(snapshot("<FIRST>"), snapshot("<CHANGED>"));

        assertThrows(DurableLlmGenerationService.GenerationUnavailableException.class,
                () -> service(root).generate(request(runId, "configuration changed")));

        assertFalse(Files.exists(root.resolve(runId.toString()).resolve("manifest.json")));
    }

    @Test
    void effectiveFallbackStopSequenceChangesConfigurationVersion() {
        DurableLlmGenerationService service = service(temporary.resolve("fallback-runs"));
        when(executionService.snapshotExecutionConfiguration(any()))
                .thenReturn(snapshot("<FIRST>"));
        LlmGenerateRequest firstRequest = request(UUID.randomUUID(), "same fallback prompt");
        firstRequest.setStopSequences(null);
        LlmGenerateResponse first = service.generate(firstRequest);

        when(executionService.snapshotExecutionConfiguration(any()))
                .thenReturn(snapshot("<SECOND>"));
        LlmGenerateRequest secondRequest = request(UUID.randomUUID(), "same fallback prompt");
        secondRequest.setStopSequences(null);
        LlmGenerateResponse second = service.generate(secondRequest);

        assertNotEquals(first.getConfigurationVersion(), second.getConfigurationVersion());
        Map<?, ?> firstEffective = (Map<?, ?>) first.getConfigurationEvidence()
                .get("effectiveExecutionConfiguration");
        Map<?, ?> secondEffective = (Map<?, ?>) second.getConfigurationEvidence()
                .get("effectiveExecutionConfiguration");
        assertEquals(List.of("<FIRST>"), firstEffective.get("effectiveStopSequences"));
        assertEquals(List.of("<SECOND>"), secondEffective.get("effectiveStopSequences"));
    }

    @Test
    void everyRunBoundIdentityFieldParticipatesInConfigurationVersion() {
        DurableLlmGenerationService service = service(temporary.resolve("identity-version-runs"));
        String prompt = "same configuration prompt";
        LlmGenerateResponse baseline = service.generate(request(UUID.randomUUID(), prompt));

        LlmGenerateRequest changedSubject = request(UUID.randomUUID(), prompt);
        changedSubject.setSubjectId(UUID.randomUUID().toString());
        LlmGenerateRequest changedRevision = request(UUID.randomUUID(), prompt);
        changedRevision.setDerivedFromRevision(DERIVED_FROM_REVISION + 1);
        LlmGenerateRequest changedReferenceLanguage = request(UUID.randomUUID(), prompt);
        changedReferenceLanguage.setReferenceLanguage("fr");
        LlmGenerateRequest changedLearningLanguage = request(UUID.randomUUID(), prompt);
        changedLearningLanguage.setLearningLanguage("de");

        for (LlmGenerateRequest changed : List.of(
                changedSubject, changedRevision,
                changedReferenceLanguage, changedLearningLanguage)) {
            assertNotEquals(baseline.getConfigurationVersion(),
                    service.generate(changed).getConfigurationVersion());
        }
    }

    @Test
    void backendErrorDoesNotPublishSuccessManifest() {
        Path root = temporary.resolve("failed-runs");
        DurableLlmGenerationService service = service(root);
        UUID errorRun = UUID.randomUUID();
        when(executionService.generate(any())).thenReturn(LlmGenerateResponse.builder()
                .generatedText("")
                .finishReason("error: sensitive internal detail")
                .build());

        assertThrows(DurableLlmGenerationService.GenerationUnavailableException.class,
                () -> service.generate(request(errorRun, "error response")));
        assertFalse(Files.exists(root.resolve(errorRun.toString()).resolve("manifest.json")));

        UUID blankRun = UUID.randomUUID();
        when(executionService.generate(any())).thenReturn(LlmGenerateResponse.builder()
                .generatedText(" ")
                .finishReason("completed")
                .build());
        assertThrows(DurableLlmGenerationService.InvalidExecutionResultException.class,
                () -> service.generate(request(blankRun, "blank response")));
        assertFalse(Files.exists(root.resolve(blankRun.toString()).resolve("manifest.json")));

        UUID nullRun = UUID.randomUUID();
        when(executionService.generate(any())).thenReturn(null);
        assertThrows(DurableLlmGenerationService.InvalidExecutionResultException.class,
                () -> service.generate(request(nullRun, "null response")));
        assertFalse(Files.exists(root.resolve(nullRun.toString()).resolve("manifest.json")));

        UUID oversizedRun = UUID.randomUUID();
        when(executionService.generate(any())).thenReturn(success("x".repeat(4001)));
        assertThrows(DurableLlmGenerationService.InvalidExecutionResultException.class,
                () -> service.generate(request(oversizedRun, "oversized response")));
        assertFalse(Files.exists(root.resolve(oversizedRun.toString()).resolve("manifest.json")));
    }

    @Test
    void corruptOrPartialManifestFailsClosed() throws Exception {
        Path root = temporary.resolve("corrupt-runs");
        DurableLlmGenerationService service = service(root);
        UUID corruptRun = UUID.randomUUID();
        Path corruptDirectory = root.resolve(corruptRun.toString());
        Files.createDirectories(corruptDirectory);
        Files.writeString(corruptDirectory.resolve("manifest.json"), "{not-json");

        assertThrows(DurableLlmGenerationService.CorruptRunManifestException.class,
                () -> service.generate(request(corruptRun, "corrupt manifest")));

        UUID partialRun = UUID.randomUUID();
        Path partialDirectory = root.resolve(partialRun.toString());
        Files.createDirectory(partialDirectory);
        Files.writeString(partialDirectory.resolve("manifest.json.tmp-crash"), "partial");
        assertThrows(DurableLlmGenerationService.CorruptRunManifestException.class,
                () -> service.generate(request(partialRun, "partial manifest")));
        verify(executionService, never()).generate(any());
    }

    @Test
    void unavailableRunRootDoesNotPreventConstructionButFailsTypedOnUse() throws Exception {
        Path blocker = temporary.resolve("not-a-directory");
        Files.writeString(blocker, "blocks the configured run root");

        DurableLlmGenerationService service = assertDoesNotThrow(() ->
                service(blocker.resolve("llm")));

        assertThrows(DurableLlmGenerationService.RunStoreUnavailableException.class,
                () -> service.generate(request(UUID.randomUUID(), "store unavailable")));
        verify(executionService, never()).generate(any());
    }

    @Test
    void documentedGeneratedArtifactsPropertyResolvesToLlmSubdirectory() throws Exception {
        Path generatedRoot = temporary.resolve("configured-generated-artifacts");
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test", Map.of(
                "kompile.staging.generated-artifacts-dir", generatedRoot.toString(),
                "user.home", temporary.toString())));
        PropertySourcesPropertyResolver propertyResolver =
                new PropertySourcesPropertyResolver(sources);

        String resolved = propertyResolver.resolveRequiredPlaceholders(
                DurableLlmGenerationService.RUNS_ROOT_PROPERTY);
        Path runsRoot = generatedRoot.resolve("llm");
        assertEquals(runsRoot, Path.of(resolved));

        UUID runId = UUID.randomUUID();
        DurableLlmGenerationService service = service(runsRoot);
        assertFalse(Files.exists(runsRoot), "the optional run store must initialize lazily");
        service.generate(request(runId, "documented root"));
        assertTrue(Files.isRegularFile(
                runsRoot.resolve(runId.toString()).resolve("manifest.json")));
    }

    @Test
    void concurrentIdenticalRunsAcrossServiceInstancesGenerateOnce() throws Exception {
        Path root = temporary.resolve("concurrent-runs");
        DurableLlmGenerationService firstService = service(root);
        DurableLlmGenerationService secondService = service(root);
        LlmGenerateRequest request = request(UUID.randomUUID(), "concurrent prompt");
        AtomicInteger generations = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executionService.generate(any())).thenAnswer(invocation -> {
            generations.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return success("one generated response");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<LlmGenerateResponse> first = executor.submit(() -> {
                start.await();
                return firstService.generate(request);
            });
            Future<LlmGenerateResponse> second = executor.submit(() -> {
                start.await();
                return secondService.generate(request);
            });
            start.countDown();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();

            assertEquals(responseJson(first.get(5, TimeUnit.SECONDS)),
                    responseJson(second.get(5, TimeUnit.SECONDS)));
            assertEquals(1, generations.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private DurableLlmGenerationService service(Path root) {
        return new DurableLlmGenerationService(
                executionService, registeredModelResolver, objectMapper, root);
    }

    private static RegisteredLlmModelResolver.VerifiedModel verifiedModel() {
        return new RegisteredLlmModelResolver.VerifiedModel(
                MODEL_ID, MODEL_VERSION, MODEL_CHECKSUM, MODEL_PATH);
    }

    private static ExecutionConfigurationSnapshot snapshot(String fallbackStop) {
        return new ExecutionConfigurationSnapshot(
                MODEL_ID, MODEL_PATH.toString(),
                Map.of("temperature", 0.3, "topP", 0.85,
                        "maxNewTokens", 777, "minNewTokens", 2),
                List.of(fallbackStop),
                "STATIC", 2048, 2048, 4096, 4096,
                Map.of("maxContextLength", 2048,
                        "stopSequences", List.of(fallbackStop)),
                Map.of("maxTokens", 777, "minTokens", 2,
                        "frequencyPenalty", 0.15, "presencePenalty", 0.05),
                Map.of("enabled", false));
    }

    private static LlmGenerateRequest request(UUID runId, String prompt) {
        return LlmGenerateRequest.builder()
                .runId(runId.toString())
                .subjectId(SUBJECT_ID.toString())
                .derivedFromRevision(DERIVED_FROM_REVISION)
                .referenceLanguage(REFERENCE_LANGUAGE)
                .learningLanguage(LEARNING_LANGUAGE)
                .prompt(prompt)
                .modelRole("IMMERSIVE_TUTOR")
                .promptVersion("immersive-prompt-v3")
                .policyVersion("strict-tutor-policy-v2")
                .responseSchemaVersion("plain-text-v1")
                .promptHash(sha256(prompt))
                .maxTokens(777)
                .temperature(0.25)
                .topK(20)
                .topP(0.85)
                .repetitionPenalty(1.1)
                .doSample(true)
                .presetName("precise")
                .stopSequences(java.util.List.of("<END>"))
                .seed(42L)
                .minTokens(2)
                .frequencyPenalty(0.15)
                .presencePenalty(0.05)
                .build();
    }

    private static LlmGenerateResponse success(String text) {
        return LlmGenerateResponse.builder()
                .generatedText(text)
                .tokensPerSecond(12.5)
                .firstTokenLatencyMs(18)
                .totalTokens(12)
                .finishReason("COMPLETED")
                .totalTimeMs(33)
                .build();
    }

    private String responseJson(LlmGenerateResponse response) throws Exception {
        return objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(response);
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
