/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.staging.execution.audio;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PreDestroy;
import org.eclipse.deeplearning4j.audio.synthesis.AudioFileGenerator;
import org.eclipse.deeplearning4j.audio.synthesis.AudioSynthesisRequest;
import org.eclipse.deeplearning4j.audio.synthesis.GeneratedAudioFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Owns audio model selection/loading, completed-file generation and durable
 * run-to-artifact manifests. No application filesystem path or remote URL is
 * accepted or returned.
 */
@Service
public class AudioSynthesisService {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CONTENT_FILE = "content";
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Object[] RUN_LOCKS = createRunLocks();

    private final RegistryService registryService;
    private final List<AudioSynthesisBackend> backends;
    private final ObjectMapper objectMapper;
    private final Path artifactRoot;
    private final Object generatorExecutionLock = new Object();
    private volatile LoadedGenerator loadedGenerator;

    public AudioSynthesisService(
            RegistryService registryService,
            List<AudioSynthesisBackend> backends,
            ObjectMapper objectMapper,
            @Value("${kompile.staging.generated-artifacts-dir:"
                    + "#{systemProperties['user.home'] + '/.kompile/models/.generated'}}")
            String artifactRoot) {
        this(registryService, backends, objectMapper, Path.of(artifactRoot));
    }

    AudioSynthesisService(RegistryService registryService, List<AudioSynthesisBackend> backends,
                          ObjectMapper objectMapper, Path artifactRoot) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.backends = List.copyOf(backends == null ? List.of() : backends);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.artifactRoot = initializeRoot(artifactRoot);
    }

    public AudioSynthesisManifest synthesize(SynthesisCommand command) throws Exception {
        Objects.requireNonNull(command, "command");
        Object lock = RUN_LOCKS[(command.runId().hashCode() & Integer.MAX_VALUE)
                % RUN_LOCKS.length];
        synchronized (lock) {
            Path runDirectory = contained(artifactRoot.resolve(command.runId().toString()));
            Files.createDirectories(runDirectory);
            setOwnerOnlyDirectory(runDirectory);
            Path lockPath = runDirectory.resolve(".handoff.lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                setOwnerOnlyFile(lockPath);
                try (FileLock ignored = channel.lock()) {
                    return synthesizeLocked(command, runDirectory);
                }
            }
        }
    }

    public ArtifactContent openArtifact(UUID artifactReference) throws IOException {
        Path runDirectory = contained(artifactRoot.resolve(artifactReference.toString()));
        Path manifestPath = runDirectory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactNotFoundException("Generated audio manifest does not exist");
        }
        StoredManifest stored = readStoredManifest(manifestPath);
        AudioSynthesisManifest manifest = stored.manifest();
        if (!manifest.runId().equals(artifactReference)
                || !manifest.artifactReference().equals(artifactReference.toString())) {
            throw new IOException("Stored generated audio manifest has invalid identity");
        }
        Path content = runDirectory.resolve(CONTENT_FILE);
        if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(content)) {
            throw new ArtifactGoneException("Generated audio file is missing");
        }
        Path real = content.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(artifactRoot)) {
            throw new IOException("Generated audio file escaped the artifact root");
        }
        verifyTransferShape(real, manifest);
        return new ArtifactContent(manifest, real);
    }

    private AudioSynthesisManifest synthesizeLocked(SynthesisCommand command,
                                                     Path runDirectory) throws Exception {
        String requestHash = requestHash(command);

        Path manifestPath = runDirectory.resolve(MANIFEST_FILE);
        if (Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            StoredManifest stored = readStoredManifest(manifestPath);
            if (!MessageDigest.isEqual(requestHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    stored.requestHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IdempotencyConflictException(
                        "runId already belongs to a different audio synthesis request");
            }
            verifyContent(runDirectory.resolve(CONTENT_FILE), stored.manifest());
            return stored.manifest();
        }

        Files.deleteIfExists(runDirectory.resolve(CONTENT_FILE));
        Path workDirectory = contained(runDirectory.resolve(".work-" + UUID.randomUUID()));
        Files.createDirectory(workDirectory);
        setOwnerOnlyDirectory(workDirectory);
        try {
            ActiveGenerator active;
            GeneratedAudioFile generated;
            synchronized (generatorExecutionLock) {
                active = activeGenerator();
                AudioSynthesisRequest modelRequest = new AudioSynthesisRequest(
                        command.runId(), command.text(), command.voice(), command.language(),
                        command.configuration());
                generated = active.generator().generate(modelRequest, workDirectory);
            }
            if (generated == null) {
                throw new InvalidGeneratedFileException("Audio generator returned no completed file");
            }
            requireMatchingProvenance(active.model(), generated);

            Path completed = generated.getCompletedFile();
            if (!completed.isAbsolute()) {
                completed = workDirectory.resolve(completed);
            }
            completed = completed.toAbsolutePath().normalize();
            Path workReal = workDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!completed.startsWith(workReal)
                    || !Files.isRegularFile(completed, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(completed)) {
                throw new InvalidGeneratedFileException(
                        "Audio generator must return a regular file inside its output directory");
            }
            Path completedReal = completed.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!completedReal.startsWith(workReal)) {
                throw new InvalidGeneratedFileException(
                        "Audio generator output escaped its output directory");
            }

            Path content = runDirectory.resolve(CONTENT_FILE);
            move(completedReal, content);
            setOwnerOnlyFile(content);
            Digest digest = digest(content);

            String registeredVersion = requireNonBlank(active.model().getEffectiveVersion(),
                    "active model version");
            String configurationVersion = configurationVersion(active.model(), command);
            Map<String, Object> evidence = new LinkedHashMap<>(generated.getConfigurationEvidence());
            evidence.put("activeModelType", ModelType.AUDIO_SYNTHESIS.getValue());
            evidence.put("registeredModelVersion", registeredVersion);
            if (active.model().getChecksum() != null && !active.model().getChecksum().isBlank()) {
                evidence.put("registeredModelChecksum", active.model().getChecksum());
            }
            evidence.put("generatorConfigurationVersion", generated.getConfigurationVersion());
            evidence.put("stagingConfigurationVersion", configurationVersion);
            AudioSynthesisManifest manifest = new AudioSynthesisManifest(
                    command.runId(), command.runId().toString(), generated.getMediaType(),
                    digest.sha256(), digest.byteLength(), generated.getConfidence(),
                    active.model().getModelId(), registeredVersion,
                    configurationVersion, evidence);
            writeManifest(manifestPath, requestHash, manifest);
            return manifest;
        } finally {
            deleteRecursively(workDirectory);
        }
    }

    private ActiveGenerator activeGenerator() throws Exception {
        ModelEntry model = registryService.getActiveModelByType(ModelType.AUDIO_SYNTHESIS)
                .orElseThrow(() -> new ModelUnavailableException(
                        "No active audio_synthesis model is registered"));
        Path modelRoot = registryService.getModelsDir().toAbsolutePath().normalize();
        Path modelDirectory = modelRoot.resolve(requireNonBlank(model.getPath(), "active model path"))
                .normalize();
        if (!modelDirectory.startsWith(modelRoot)
                || !Files.isDirectory(modelDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(modelDirectory)) {
            throw new ModelUnavailableException("Active audio model directory is missing or invalid");
        }
        Path modelRootReal = modelRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path modelReal = modelDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!modelReal.startsWith(modelRootReal)) {
            throw new ModelUnavailableException("Active audio model escaped the model root");
        }

        Path modelFile = modelReal.resolve(requireNonBlank(model.getModelFile(),
                "active model file")).normalize();
        if (!modelFile.startsWith(modelReal)
                || !Files.isRegularFile(modelFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(modelFile)) {
            throw new ModelUnavailableException("Active audio model file is missing or invalid");
        }
        Path modelFileReal = modelFile.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!modelFileReal.startsWith(modelReal)) {
            throw new ModelUnavailableException("Active audio model file escaped its model directory");
        }
        BasicFileAttributes attributes = Files.readAttributes(
                modelFileReal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String servingConfigurationIdentity = servingConfigurationIdentity(model);
        String modelIdentity = String.join("|",
                model.getModelId(),
                Objects.toString(model.getEffectiveVersion(), ""),
                Objects.toString(model.getChecksum(), ""),
                servingConfigurationIdentity,
                modelFileReal.toString(),
                Long.toString(attributes.size()),
                Long.toString(attributes.lastModifiedTime().toMillis()));

        LoadedGenerator current = loadedGenerator;
        if (current != null && current.modelIdentity().equals(modelIdentity)) {
            return new ActiveGenerator(model, current.generator());
        }

        verifyRegisteredModelChecksum(model, modelFileReal);
        AudioSynthesisBackend backend = backends.stream()
                .filter(candidate -> candidate.supports(model))
                .findFirst()
                .orElseThrow(() -> new ModelUnavailableException(
                        "No audio synthesis backend supports active model " + model.getModelId()));
        AudioFileGenerator generator = Objects.requireNonNull(
                backend.load(model, modelReal), "audio synthesis backend returned no generator");
        LoadedGenerator replacement = new LoadedGenerator(modelIdentity, generator);
        loadedGenerator = replacement;
        closeReplacedGenerator(current, generator);
        return new ActiveGenerator(model, generator);
    }

    private String servingConfigurationIdentity(ModelEntry model) throws IOException {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("audioSynthesis", model.getAudioSynthesis());
        return canonicalHash(canonical);
    }

    private void verifyRegisteredModelChecksum(ModelEntry model, Path modelFile) throws IOException {
        String registered = requireNonBlank(model.getChecksum(), "active model checksum");
        if (registered.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
            registered = registered.substring("sha256:".length());
        }
        if (!registered.matches("[0-9a-fA-F]{64}")) {
            throw new ModelUnavailableException(
                    "Active audio model checksum must be a complete SHA-256 digest");
        }
        String actual = digest(modelFile).sha256();
        if (!MessageDigest.isEqual(
                registered.toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new ModelUnavailableException(
                    "Active audio model file does not match its registered SHA-256");
        }
    }

    private static void closeReplacedGenerator(LoadedGenerator previous,
                                               AudioFileGenerator replacement) throws Exception {
        if (previous != null && previous.generator() != replacement) {
            previous.generator().close();
        }
    }

    private void requireMatchingProvenance(ModelEntry active, GeneratedAudioFile generated) {
        if (!active.getModelId().equals(generated.getModelId())) {
            throw new InvalidGeneratedFileException(
                    "Generated file modelId does not match the active registry model");
        }
        String registeredVersion = active.getEffectiveVersion();
        if (registeredVersion != null && !registeredVersion.isBlank()
                && !registeredVersion.equals(generated.getModelVersion())) {
            throw new InvalidGeneratedFileException(
                    "Generated file modelVersion does not match the active registry model");
        }
    }

    private String requestHash(SynthesisCommand command) throws IOException {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("runId", command.runId().toString());
        canonical.put("text", command.text());
        canonical.put("voice", command.voice());
        canonical.put("language", command.language());
        canonical.put("configuration", new TreeMap<>(command.configuration()));
        return canonicalHash(canonical);
    }

    private String configurationVersion(ModelEntry model, SynthesisCommand command)
            throws IOException {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("modelId", model.getModelId());
        canonical.put("modelVersion", requireNonBlank(model.getEffectiveVersion(),
                "active model version"));
        canonical.put("modelChecksum", Objects.toString(model.getChecksum(), ""));
        canonical.put("audioSynthesis", model.getAudioSynthesis());
        canonical.put("voice", command.voice());
        canonical.put("language", command.language());
        canonical.put("configuration", new TreeMap<>(command.configuration()));
        return "sha256:" + canonicalHash(canonical);
    }

    private String canonicalHash(Map<String, Object> canonical) throws IOException {
        byte[] bytes = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(canonical);
        return sha256(bytes);
    }

    private void writeManifest(Path manifestPath, String requestHash,
                               AudioSynthesisManifest manifest) throws IOException {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("requestHash", requestHash);
        stored.put("manifest", manifest);
        Path temporary = manifestPath.resolveSibling(MANIFEST_FILE + ".tmp-" + UUID.randomUUID());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), stored);
            setOwnerOnlyFile(temporary);
            move(temporary, manifestPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private StoredManifest readStoredManifest(Path path) throws IOException {
        JsonNode root = objectMapper.readTree(path.toFile());
        JsonNode requestHash = root.get("requestHash");
        JsonNode manifest = root.get("manifest");
        if (requestHash == null || !requestHash.isTextual() || manifest == null) {
            throw new IOException("Stored generated audio manifest is incomplete");
        }
        return new StoredManifest(requestHash.asText(),
                objectMapper.treeToValue(manifest, AudioSynthesisManifest.class));
    }

    private void verifyContent(Path content, AudioSynthesisManifest manifest) throws IOException {
        if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(content)) {
            throw new ArtifactGoneException("Generated audio file is missing");
        }
        Digest actual = digest(content);
        if (actual.byteLength() != manifest.byteLength()
                || !MessageDigest.isEqual(actual.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                manifest.contentHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new ArtifactGoneException("Generated audio file no longer matches its manifest");
        }
    }

    private void verifyTransferShape(Path content, AudioSynthesisManifest manifest) throws IOException {
        if (Files.size(content) != manifest.byteLength()) {
            throw new ArtifactGoneException("Generated audio file length no longer matches its manifest");
        }
    }

    private static Digest digest(Path file) throws IOException {
        MessageDigest digest = newDigest();
        long length = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                length += read;
            }
        }
        return new Digest(HexFormat.of().formatHex(digest.digest()), length);
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(bytes));
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

    private static Path initializeRoot(Path root) {
        try {
            Path normalized = Objects.requireNonNull(root, "artifactRoot")
                    .toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            setOwnerOnlyDirectory(normalized);
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to initialize generated artifact root", error);
        }
    }

    private static Path contained(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
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
            // Non-POSIX filesystems still retain containment and no-symlink checks.
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ModelUnavailableException(name + " is blank");
        }
        return value;
    }

    @PreDestroy
    public void closeLoadedGenerator() throws Exception {
        synchronized (generatorExecutionLock) {
            LoadedGenerator current = loadedGenerator;
            loadedGenerator = null;
            if (current != null) {
                current.generator().close();
            }
        }
    }

    public record SynthesisCommand(UUID runId, String text, String voice, String language,
                                   Map<String, Object> configuration) {
        public SynthesisCommand {
            Objects.requireNonNull(runId, "runId");
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
            voice = voice == null ? "" : voice;
            language = language == null ? "" : language;
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        }
    }

    public record ArtifactContent(AudioSynthesisManifest manifest, Path path) {
    }

    private record StoredManifest(String requestHash, AudioSynthesisManifest manifest) {
    }

    private record LoadedGenerator(String modelIdentity, AudioFileGenerator generator) {
    }

    private record ActiveGenerator(ModelEntry model, AudioFileGenerator generator) {
    }

    private record Digest(String sha256, long byteLength) {
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    public static class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message) {
            super(message);
        }
    }

    public static class InvalidGeneratedFileException extends RuntimeException {
        public InvalidGeneratedFileException(String message) {
            super(message);
        }
    }

    public static class ArtifactNotFoundException extends IOException {
        public ArtifactNotFoundException(String message) {
            super(message);
        }
    }

    public static class ArtifactGoneException extends IOException {
        public ArtifactGoneException(String message) {
            super(message);
        }
    }
}
