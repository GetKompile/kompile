/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.staging.execution.text;

import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.execution.LlmExecutionService.ExecutionConfigurationSnapshot;
import ai.kompile.staging.web.dto.LlmGenerateRequest;
import ai.kompile.staging.web.dto.LlmGenerateResponse;
import ai.kompile.staging.web.dto.LlmModelStatusResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adds an opt-in durable/idempotent envelope around canonical LLM generation.
 * Legacy requests never pass through this service.
 */
@Service
public class DurableLlmGenerationService {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String MANIFEST_TEMP_GLOB = MANIFEST_FILE + ".tmp-*";
    private static final String LOCK_FILE = ".generation.lock";
    private static final String CONFIDENCE_SOURCE = "UNAVAILABLE";
    private static final String CONFIDENCE_EXPLANATION =
            "This backend does not provide a calibrated confidence score";
    private static final int MAX_GENERATED_TEXT_CHARACTERS = 4000;
    static final String RUNS_ROOT_PROPERTY =
            "${kompile.staging.llm-runs-dir:"
                    + "${kompile.staging.generated-artifacts-dir:"
                    + "${user.home}/.kompile/models/.generated}/llm}";
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern REGISTERED_SHA_256 =
            Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern CONFIGURATION_VERSION = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern STABLE_LANGUAGE_CODE =
            Pattern.compile("[a-z]{2,8}(?:-[a-z0-9]{1,8})*");
    private static final Object[] RUN_LOCKS = createRunLocks();

    private final LlmExecutionService executionService;
    private final RegisteredLlmModelResolver registeredModelResolver;
    private final ObjectMapper objectMapper;
    private final String configuredRunsRoot;

    @Autowired
    public DurableLlmGenerationService(
            LlmExecutionService executionService,
            RegisteredLlmModelResolver registeredModelResolver,
            ObjectMapper objectMapper,
            @Value(RUNS_ROOT_PROPERTY)
            String runsRoot) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.registeredModelResolver = Objects.requireNonNull(
                registeredModelResolver, "registeredModelResolver");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.configuredRunsRoot = runsRoot;
    }

    /** Test seam for an isolated owner-local run root. */
    DurableLlmGenerationService(LlmExecutionService executionService,
                                RegisteredLlmModelResolver registeredModelResolver,
                                ObjectMapper objectMapper,
                                Path runsRoot) {
        this(executionService, registeredModelResolver, objectMapper,
                Objects.requireNonNull(runsRoot, "runsRoot").toString());
    }

    public LlmGenerateResponse generate(LlmGenerateRequest request) {
        BoundRequest bound = validate(request);
        Object stripe = RUN_LOCKS[(bound.runId().hashCode() & Integer.MAX_VALUE) % RUN_LOCKS.length];
        synchronized (stripe) {
            try {
                Path runsRoot = initializeRoot();
                Path runDirectory = createRunDirectory(runsRoot, bound.runId());
                Path lockPath = runDirectory.resolve(LOCK_FILE);
                rejectSymbolicLink(lockPath, "Run lock must not be a symbolic link");
                try (FileChannel channel = FileChannel.open(lockPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    setOwnerOnlyFile(lockPath);
                    try (FileLock ignored = channel.lock()) {
                        return generateLocked(request, bound, runDirectory);
                    }
                }
            } catch (RunBoundGenerationException failure) {
                throw failure;
            } catch (IOException | SecurityException failure) {
                throw new RunStoreUnavailableException(
                        "Durable LLM run storage is unavailable", failure);
            }
        }
    }

    private LlmGenerateResponse generateLocked(LlmGenerateRequest request,
                                                BoundRequest bound,
                                                Path runDirectory) throws IOException {
        String requestHash = canonicalHash(requestMap(request, bound));
        Path manifestPath = runDirectory.resolve(MANIFEST_FILE);
        if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(manifestPath)) {
                throw new CorruptRunManifestException(
                        "Stored durable LLM run manifest is invalid");
            }
            StoredManifest stored = readManifest(manifestPath);
            if (!constantTimeEquals(requestHash, stored.requestHash())) {
                throw new IdempotencyConflictException(
                        "runId already belongs to a different LLM generation request");
            }
            validateStoredResponse(stored.response(), bound);
            return stored.response();
        }
        rejectPartialManifest(runDirectory);

        RegisteredLlmModelResolver.VerifiedModel model = loadedModelIdentity();
        ExecutionConfigurationSnapshot executionConfiguration =
                executionConfiguration(request);
        requireMatchingExecutionModel(model, executionConfiguration);
        if (executionService.isGenerating()) {
            throw new GenerationUnavailableException("The LLM generation backend is busy");
        }

        LlmGenerateResponse executionResponse;
        try {
            executionResponse = executionService.generate(request);
        } catch (RuntimeException failure) {
            throw new GenerationUnavailableException(
                    "The LLM generation backend is unavailable", failure);
        }
        validateExecutionResponse(executionResponse);

        RegisteredLlmModelResolver.VerifiedModel completedModel = loadedModelIdentity();
        if (!model.equals(completedModel)) {
            throw new GenerationUnavailableException(
                    "The loaded LLM identity changed during generation");
        }
        ExecutionConfigurationSnapshot completedConfiguration =
                executionConfiguration(request);
        requireMatchingExecutionModel(completedModel, completedConfiguration);
        if (!executionConfiguration.equals(completedConfiguration)) {
            throw new GenerationUnavailableException(
                    "The effective LLM execution configuration changed during generation");
        }

        String configurationVersion = configurationVersion(
                model, bound, executionConfiguration);
        Map<String, Object> evidence = configurationEvidence(model, request, bound,
                executionConfiguration, configurationVersion);
        LlmGenerateResponse durableResponse = LlmGenerateResponse.builder()
                .generatedText(executionResponse.getGeneratedText())
                .tokensPerSecond(executionResponse.getTokensPerSecond())
                .firstTokenLatencyMs(executionResponse.getFirstTokenLatencyMs())
                .totalTokens(executionResponse.getTotalTokens())
                .finishReason(executionResponse.getFinishReason())
                .totalTimeMs(executionResponse.getTotalTimeMs())
                .runId(bound.runId())
                .subjectId(bound.subjectId())
                .derivedFromRevision(bound.derivedFromRevision())
                .referenceLanguage(bound.referenceLanguage())
                .learningLanguage(bound.learningLanguage())
                .modelId(model.modelId())
                .modelVersion(model.modelVersion())
                .configurationVersion(configurationVersion)
                .promptVersion(bound.promptVersion())
                .policyVersion(bound.policyVersion())
                .responseSchemaVersion(bound.responseSchemaVersion())
                .promptHash(bound.promptHash())
                .completedAt(Instant.now())
                .configurationEvidence(evidence)
                .confidence(0.0)
                .confidenceSource(CONFIDENCE_SOURCE)
                .build();
        writeManifest(manifestPath, requestHash, durableResponse);
        return durableResponse;
    }

    private BoundRequest validate(LlmGenerateRequest request) {
        if (request == null) {
            throw new InvalidRunRequestException("Request is required");
        }
        UUID runId = requireCanonicalUuid(request.getRunId(), "runId");
        UUID subjectId = requireCanonicalUuid(request.getSubjectId(), "subjectId");
        Long derivedFromRevision = request.getDerivedFromRevision();
        if (derivedFromRevision == null || derivedFromRevision < 1) {
            throw new InvalidRunRequestException(
                    "derivedFromRevision must be positive");
        }
        String referenceLanguage = requireStableLanguageCode(
                request.getReferenceLanguage(), "referenceLanguage");
        String learningLanguage = requireStableLanguageCode(
                request.getLearningLanguage(), "learningLanguage");
        if (referenceLanguage.equals(learningLanguage)) {
            throw new InvalidRunRequestException(
                    "referenceLanguage and learningLanguage must differ");
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new InvalidRunRequestException("prompt must not be blank");
        }
        String modelRole = requireNonBlank(request.getModelRole(), "modelRole");
        String promptVersion = requireNonBlank(request.getPromptVersion(), "promptVersion");
        String policyVersion = requireNonBlank(request.getPolicyVersion(), "policyVersion");
        String responseSchemaVersion = requireNonBlank(
                request.getResponseSchemaVersion(), "responseSchemaVersion");
        String promptHash = request.getPromptHash();
        if (promptHash == null || !SHA_256.matcher(promptHash).matches()) {
            throw new InvalidRunRequestException(
                    "promptHash must be a lowercase SHA-256 digest");
        }
        String actualPromptHash = sha256(request.getPrompt().getBytes(StandardCharsets.UTF_8));
        if (!constantTimeEquals(promptHash, actualPromptHash)) {
            throw new InvalidRunRequestException(
                    "promptHash does not match the UTF-8 prompt");
        }
        return new BoundRequest(runId, subjectId, derivedFromRevision,
                referenceLanguage, learningLanguage, modelRole, promptVersion, policyVersion,
                responseSchemaVersion, promptHash);
    }

    private RegisteredLlmModelResolver.VerifiedModel loadedModelIdentity() {
        final LlmModelStatusResponse status;
        try {
            status = executionService.getStatus();
        } catch (RuntimeException failure) {
            throw new GenerationUnavailableException(
                    "The loaded LLM status is unavailable", failure);
        }
        if (status == null || !status.isLoaded() || status.getModelId() == null
                || status.getModelId().isBlank()) {
            throw new GenerationUnavailableException("No LLM model is loaded");
        }
        String modelId = status.getModelId();
        if (status.getDecoderPath() == null || status.getDecoderPath().isBlank()) {
            throw new GenerationUnavailableException(
                    "The loaded LLM decoder path is unavailable");
        }
        try {
            return registeredModelResolver.resolve(modelId, status.getDecoderPath());
        } catch (RuntimeException failure) {
            throw new GenerationUnavailableException(
                    "The loaded LLM does not match its verified registry artifact", failure);
        }
    }

    private ExecutionConfigurationSnapshot executionConfiguration(
            LlmGenerateRequest request) {
        try {
            ExecutionConfigurationSnapshot snapshot =
                    executionService.snapshotExecutionConfiguration(request);
            if (snapshot == null) {
                throw new IllegalStateException("execution configuration snapshot is missing");
            }
            return snapshot;
        } catch (RuntimeException failure) {
            throw new GenerationUnavailableException(
                    "The effective LLM execution configuration is unavailable", failure);
        }
    }

    private static void requireMatchingExecutionModel(
            RegisteredLlmModelResolver.VerifiedModel model,
            ExecutionConfigurationSnapshot configuration) {
        try {
            if (!model.modelId().equals(configuration.loadedModelId())
                    || configuration.loadedDecoderPath() == null
                    || !model.modelFile().equals(Path.of(configuration.loadedDecoderPath())
                            .toAbsolutePath().normalize())) {
                throw new GenerationUnavailableException(
                        "The effective LLM execution model does not match its verified artifact");
            }
        } catch (InvalidPathException failure) {
            throw new GenerationUnavailableException(
                    "The effective LLM execution model path is invalid", failure);
        }
    }

    private static void validateExecutionResponse(LlmGenerateResponse response) {
        if (response == null) {
            throw new InvalidExecutionResultException(
                    "The LLM generation backend returned no result");
        }
        String finishReason = response.getFinishReason();
        if (finishReason != null
                && finishReason.stripLeading().toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new GenerationUnavailableException(
                    "The LLM generation backend reported an unavailable result");
        }
        if (response.getGeneratedText() == null || response.getGeneratedText().isBlank()) {
            throw new InvalidExecutionResultException(
                    "The LLM generation backend returned blank text");
        }
        if (response.getGeneratedText().length() > MAX_GENERATED_TEXT_CHARACTERS) {
            throw new InvalidExecutionResultException(
                    "The LLM generation backend returned more than 4000 characters");
        }
    }

    private void validateStoredResponse(LlmGenerateResponse response, BoundRequest bound) {
        try {
            validateExecutionResponse(response);
        } catch (RunBoundGenerationException invalid) {
            throw new CorruptRunManifestException(
                    "Stored durable LLM run manifest is invalid", invalid);
        }
        if (!bound.runId().equals(response.getRunId())
                || !bound.subjectId().equals(response.getSubjectId())
                || !Objects.equals(bound.derivedFromRevision(),
                        response.getDerivedFromRevision())
                || !bound.referenceLanguage().equals(response.getReferenceLanguage())
                || !bound.learningLanguage().equals(response.getLearningLanguage())
                || !bound.promptVersion().equals(response.getPromptVersion())
                || !bound.policyVersion().equals(response.getPolicyVersion())
                || !bound.responseSchemaVersion().equals(response.getResponseSchemaVersion())
                || !bound.promptHash().equals(response.getPromptHash())
                || isBlank(response.getModelId())
                || isBlank(response.getModelVersion())
                || response.getConfigurationVersion() == null
                || !CONFIGURATION_VERSION.matcher(response.getConfigurationVersion()).matches()
                || response.getCompletedAt() == null
                || response.getConfigurationEvidence() == null
                || response.getConfidence() == null
                || !Double.isFinite(response.getConfidence())
                || Double.compare(response.getConfidence(), 0.0) != 0
                || !CONFIDENCE_SOURCE.equals(response.getConfidenceSource())) {
            throw new CorruptRunManifestException(
                    "Stored durable LLM run manifest is invalid");
        }
        Object registeredChecksum = response.getConfigurationEvidence()
                .get("registeredModelChecksum");
        Object effectiveConfiguration = response.getConfigurationEvidence()
                .get("effectiveExecutionConfiguration");
        Object evidenceSubjectId = response.getConfigurationEvidence().get("subjectId");
        Object evidenceRevision = response.getConfigurationEvidence().get("derivedFromRevision");
        Object evidenceReferenceLanguage = response.getConfigurationEvidence()
                .get("referenceLanguage");
        Object evidenceLearningLanguage = response.getConfigurationEvidence()
                .get("learningLanguage");
        Object evidenceConfigurationVersion = response.getConfigurationEvidence()
                .get("stagingConfigurationVersion");
        if (!(registeredChecksum instanceof String checksum)
                || !REGISTERED_SHA_256.matcher(checksum).matches()
                || !(effectiveConfiguration instanceof Map<?, ?>)
                || !bound.subjectId().toString().equals(evidenceSubjectId)
                || !matchesLong(evidenceRevision, bound.derivedFromRevision())
                || !bound.referenceLanguage().equals(evidenceReferenceLanguage)
                || !bound.learningLanguage().equals(evidenceLearningLanguage)
                || !response.getConfigurationVersion().equals(
                        evidenceConfigurationVersion)) {
            throw new CorruptRunManifestException(
                    "Stored durable LLM run manifest is invalid");
        }
    }

    private String configurationVersion(
            RegisteredLlmModelResolver.VerifiedModel model,
            BoundRequest bound,
            ExecutionConfigurationSnapshot executionConfiguration) throws IOException {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("modelId", model.modelId());
        canonical.put("modelVersion", model.modelVersion());
        canonical.put("modelChecksum", model.registeredChecksum());
        canonical.put("subjectId", bound.subjectId().toString());
        canonical.put("derivedFromRevision", bound.derivedFromRevision());
        canonical.put("referenceLanguage", bound.referenceLanguage());
        canonical.put("learningLanguage", bound.learningLanguage());
        canonical.put("modelRole", bound.modelRole());
        canonical.put("promptVersion", bound.promptVersion());
        canonical.put("policyVersion", bound.policyVersion());
        canonical.put("responseSchemaVersion", bound.responseSchemaVersion());
        canonical.put("promptHash", bound.promptHash());
        canonical.put("effectiveExecutionConfiguration",
                executionConfiguration.asEvidence());
        return "sha256:" + canonicalHash(canonical);
    }

    private Map<String, Object> configurationEvidence(
                                                       RegisteredLlmModelResolver.VerifiedModel model,
                                                       LlmGenerateRequest request,
                                                       BoundRequest bound,
                                                       ExecutionConfigurationSnapshot executionConfiguration,
                                                       String configurationVersion) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("loadedModelId", model.modelId());
        evidence.put("registeredModelVersion", model.modelVersion());
        evidence.put("registeredModelChecksum", model.registeredChecksum());
        evidence.put("subjectId", bound.subjectId().toString());
        evidence.put("derivedFromRevision", bound.derivedFromRevision());
        evidence.put("referenceLanguage", bound.referenceLanguage());
        evidence.put("learningLanguage", bound.learningLanguage());
        evidence.put("modelRole", bound.modelRole());
        evidence.put("promptVersion", bound.promptVersion());
        evidence.put("policyVersion", bound.policyVersion());
        evidence.put("responseSchemaVersion", bound.responseSchemaVersion());
        evidence.put("promptHash", bound.promptHash());
        evidence.put("generationSettings", generationSettings(request));
        evidence.put("effectiveExecutionConfiguration",
                executionConfiguration.asEvidence());
        evidence.put("stagingConfigurationVersion", configurationVersion);
        evidence.put("confidenceSource", CONFIDENCE_SOURCE);
        evidence.put("confidenceExplanation", CONFIDENCE_EXPLANATION);
        return Map.copyOf(evidence);
    }

    private static Map<String, Object> requestMap(
            LlmGenerateRequest request, BoundRequest bound) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("runId", bound.runId().toString());
        canonical.put("subjectId", bound.subjectId().toString());
        canonical.put("derivedFromRevision", bound.derivedFromRevision());
        canonical.put("referenceLanguage", bound.referenceLanguage());
        canonical.put("learningLanguage", bound.learningLanguage());
        canonical.put("prompt", request.getPrompt());
        canonical.put("modelRole", request.getModelRole());
        canonical.put("promptVersion", request.getPromptVersion());
        canonical.put("policyVersion", request.getPolicyVersion());
        canonical.put("responseSchemaVersion", request.getResponseSchemaVersion());
        canonical.put("promptHash", request.getPromptHash());
        canonical.putAll(generationSettings(request));
        return canonical;
    }

    private static Map<String, Object> generationSettings(LlmGenerateRequest request) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("maxTokens", request.getMaxTokens());
        settings.put("temperature", request.getTemperature());
        settings.put("topK", request.getTopK());
        settings.put("topP", request.getTopP());
        settings.put("repetitionPenalty", request.getRepetitionPenalty());
        settings.put("doSample", request.isDoSample());
        settings.put("presetName", request.getPresetName());
        settings.put("stopSequences", request.getStopSequences() == null
                ? null : new ArrayList<>(request.getStopSequences()));
        settings.put("seed", request.getSeed());
        settings.put("minTokens", request.getMinTokens());
        settings.put("frequencyPenalty", request.getFrequencyPenalty());
        settings.put("presencePenalty", request.getPresencePenalty());
        return settings;
    }

    private String canonicalHash(Map<String, Object> canonical) throws IOException {
        byte[] bytes = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(canonical);
        return sha256(bytes);
    }

    private StoredManifest readManifest(Path manifestPath) throws IOException {
        final JsonNode root;
        try (InputStream input = Files.newInputStream(manifestPath)) {
            try {
                root = objectMapper.readTree(input);
            } catch (JsonProcessingException malformed) {
                throw new CorruptRunManifestException(
                        "Stored durable LLM run manifest is invalid", malformed);
            }
        }
        if (root == null || !root.isObject()) {
            throw new CorruptRunManifestException(
                    "Stored durable LLM run manifest is invalid");
        }
        JsonNode requestHash = root.get("requestHash");
        JsonNode responseNode = root.get("response");
        if (requestHash == null || !requestHash.isTextual()
                || !SHA_256.matcher(requestHash.asText()).matches()
                || responseNode == null || !responseNode.isObject()) {
            throw new CorruptRunManifestException(
                    "Stored durable LLM run manifest is incomplete");
        }
        try {
            return new StoredManifest(requestHash.asText(),
                    objectMapper.treeToValue(responseNode, LlmGenerateResponse.class));
        } catch (JsonProcessingException malformed) {
            throw new CorruptRunManifestException(
                    "Stored durable LLM run manifest is invalid", malformed);
        }
    }

    private void writeManifest(Path manifestPath, String requestHash,
                               LlmGenerateResponse response) throws IOException {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("requestHash", requestHash);
        stored.put("response", response);
        Path temporary = manifestPath.resolveSibling(
                MANIFEST_FILE + ".tmp-" + UUID.randomUUID());
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), stored);
            setOwnerOnlyFile(temporary);
            move(temporary, manifestPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path createRunDirectory(Path runsRoot, UUID runId) throws IOException {
        Path runDirectory = runsRoot.resolve(runId.toString()).normalize();
        if (!runDirectory.startsWith(runsRoot)) {
            throw new RunStoreUnavailableException("Invalid durable LLM run directory");
        }
        try {
            Files.createDirectory(runDirectory);
        } catch (FileAlreadyExistsException existing) {
            // Validated below while holding the in-process stripe.
        }
        if (!Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(runDirectory)) {
            throw new RunStoreUnavailableException(
                    "Durable LLM run directory is invalid");
        }
        Path real = runDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(runsRoot)) {
            throw new RunStoreUnavailableException(
                    "Durable LLM run directory escaped its root");
        }
        setOwnerOnlyDirectory(runDirectory);
        return runDirectory;
    }

    private Path initializeRoot() {
        try {
            if (configuredRunsRoot == null || configuredRunsRoot.isBlank()) {
                throw new RunStoreUnavailableException(
                        "Durable LLM run root is not configured");
            }
            Path normalized = Path.of(configuredRunsRoot).toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalized)) {
                throw new RunStoreUnavailableException(
                        "Durable LLM run root is invalid");
            }
            setOwnerOnlyDirectory(normalized);
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (RunStoreUnavailableException failure) {
            throw failure;
        } catch (IOException | InvalidPathException | SecurityException failure) {
            throw new RunStoreUnavailableException(
                    "Durable LLM run storage is unavailable", failure);
        }
    }

    private static void rejectSymbolicLink(Path path, String message) {
        if (Files.isSymbolicLink(path)) {
            throw new RunStoreUnavailableException(message);
        }
    }

    private static void rejectPartialManifest(Path runDirectory) throws IOException {
        try (DirectoryStream<Path> partials = Files.newDirectoryStream(
                runDirectory, MANIFEST_TEMP_GLOB)) {
            if (partials.iterator().hasNext()) {
                throw new CorruptRunManifestException(
                        "Stored durable LLM run manifest is incomplete");
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRunRequestException(field + " must not be blank");
        }
        return value;
    }

    private static UUID requireCanonicalUuid(String value, String field) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw new InvalidRunRequestException(field + " must be a canonical UUID");
        }
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRunRequestException(field + " must be a canonical UUID");
        }
        if (!parsed.toString().equals(value)) {
            throw new InvalidRunRequestException(field + " must be a canonical UUID");
        }
        return parsed;
    }

    private static String requireStableLanguageCode(String value, String field) {
        value = requireNonBlank(value, field);
        if (!STABLE_LANGUAGE_CODE.matcher(value).matches()) {
            throw new InvalidRunRequestException(field + " must be a stable language code");
        }
        return value;
    }

    private static boolean matchesLong(Object value, long expected) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue() == expected;
        }
        if (value instanceof java.math.BigInteger bigInteger) {
            try {
                return bigInteger.longValueExact() == expected;
            } catch (ArithmeticException outOfRange) {
                return false;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object[] createRunLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static void setOwnerOnlyDirectory(Path path) {
        setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void setOwnerOnlyFile(Path path) {
        setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
    }

    private static void setPermissions(Path path, EnumSet<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Containment, canonical UUID directories, and no-symlink checks still apply.
        }
    }

    private record BoundRequest(UUID runId, UUID subjectId, long derivedFromRevision,
                                String referenceLanguage, String learningLanguage,
                                String modelRole, String promptVersion,
                                String policyVersion, String responseSchemaVersion,
                                String promptHash) {
    }

    private record StoredManifest(String requestHash, LlmGenerateResponse response) {
    }

    public abstract static class RunBoundGenerationException extends RuntimeException {
        protected RunBoundGenerationException(String message) {
            super(message);
        }

        protected RunBoundGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class InvalidRunRequestException extends RunBoundGenerationException {
        public InvalidRunRequestException(String message) {
            super(message);
        }
    }

    public static class IdempotencyConflictException extends RunBoundGenerationException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    public static class GenerationUnavailableException extends RunBoundGenerationException {
        public GenerationUnavailableException(String message) {
            super(message);
        }

        public GenerationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class InvalidExecutionResultException extends RunBoundGenerationException {
        public InvalidExecutionResultException(String message) {
            super(message);
        }
    }

    public static class CorruptRunManifestException extends RunBoundGenerationException {
        public CorruptRunManifestException(String message) {
            super(message);
        }

        public CorruptRunManifestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RunStoreUnavailableException extends RunBoundGenerationException {
        public RunStoreUnavailableException(String message) {
            super(message);
        }

        public RunStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
