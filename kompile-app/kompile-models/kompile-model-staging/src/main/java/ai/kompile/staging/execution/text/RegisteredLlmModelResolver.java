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

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.RegistryService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves an LLM registry identity to the one regular model file it is allowed to load.
 * Resolution is fail-closed: the version and checksum must be complete, every path component
 * below the configured model root must be free of symbolic links, and the registered bytes
 * must match their SHA-256 before the path is returned to an execution backend.
 */
@Service
public class RegisteredLlmModelResolver {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int DIGEST_BUFFER_SIZE = 1024 * 1024;

    private final RegistryService registryService;

    public RegisteredLlmModelResolver(RegistryService registryService) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
    }

    /**
     * Resolve and verify the registered model. When {@code explicitModelPath} is present it
     * must name exactly the same verified registry file; it never widens the allowed root.
     */
    public VerifiedModel resolve(String modelId, String explicitModelPath) {
        String requestedId = requireNonBlank(modelId, "modelId");
        final Optional<ModelEntry> registered;
        try {
            registered = registryService.getModel(requestedId);
        } catch (RuntimeException failure) {
            throw new ModelVerificationException("The LLM model registry is unavailable", failure);
        }
        ModelEntry entry = registered.orElseThrow(() ->
                new ModelVerificationException("The requested LLM model is not registered"));
        if (!requestedId.equals(requireNonBlank(entry.getModelId(), "registered model id"))) {
            throw new ModelVerificationException("The registered LLM model identity is inconsistent");
        }

        String version = requireNonBlank(entry.getEffectiveVersion(), "registered model version");
        String checksum = canonicalChecksum(entry.getChecksum());

        try {
            Path configuredRoot = registryService.getModelsDir().toAbsolutePath().normalize();
            if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(configuredRoot)) {
                throw new ModelVerificationException("The registered LLM model root is invalid");
            }
            Path realRoot = configuredRoot.toRealPath();

            Path registeredDirectory = relativePath(
                    requireNonBlank(entry.getPath(), "registered model path"),
                    "registered model path");
            Path registeredFileName = relativePath(
                    requireNonBlank(entry.getModelFile(), "registered model file"),
                    "registered model file");
            Path candidate = configuredRoot.resolve(registeredDirectory)
                    .resolve(registeredFileName).normalize();
            if (!candidate.startsWith(configuredRoot)) {
                throw new ModelVerificationException(
                        "The registered LLM model path escaped the model root");
            }
            rejectSymlinkComponents(configuredRoot, candidate);
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate)) {
                throw new ModelVerificationException(
                        "The registered LLM model file is missing or invalid");
            }

            Path verifiedFile = candidate.toRealPath();
            if (!verifiedFile.startsWith(realRoot)) {
                throw new ModelVerificationException(
                        "The registered LLM model file escaped the model root");
            }
            requireExactExplicitPath(explicitModelPath, candidate, verifiedFile);

            String actualChecksum = "sha256:" + sha256(verifiedFile);
            if (!constantTimeEquals(checksum, actualChecksum)) {
                throw new ModelVerificationException(
                        "The registered LLM model file does not match its SHA-256 checksum");
            }
            return new VerifiedModel(requestedId, version, checksum, verifiedFile);
        } catch (ModelVerificationException failure) {
            throw failure;
        } catch (IOException | InvalidPathException | SecurityException failure) {
            throw new ModelVerificationException(
                    "The registered LLM model file could not be verified", failure);
        }
    }

    private static Path relativePath(String value, String field) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            throw new ModelVerificationException(field + " must be relative to the model root");
        }
        return path;
    }

    private static void rejectSymlinkComponents(Path root, Path candidate) {
        Path current = root;
        for (Path component : root.relativize(candidate)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new ModelVerificationException(
                        "The registered LLM model path must not contain symbolic links");
            }
        }
    }

    private static void requireExactExplicitPath(String explicitModelPath,
                                                 Path registeredFile,
                                                 Path verifiedFile) throws IOException {
        if (explicitModelPath == null || explicitModelPath.isBlank()) {
            return;
        }
        Path explicit = Path.of(explicitModelPath).toAbsolutePath().normalize();
        if (!explicit.equals(registeredFile)
                || Files.isSymbolicLink(explicit)
                || !explicit.toRealPath().equals(verifiedFile)) {
            throw new ModelVerificationException(
                    "The explicit LLM model path does not match the registered model file");
        }
    }

    private static String canonicalChecksum(String registered) {
        String checksum = requireNonBlank(registered, "registered model checksum");
        if (checksum.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
            checksum = checksum.substring("sha256:".length());
        }
        if (!SHA_256.matcher(checksum).matches()) {
            throw new ModelVerificationException(
                    "The registered LLM model checksum must be a complete SHA-256 digest");
        }
        return "sha256:" + checksum.toLowerCase(Locale.ROOT);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[DIGEST_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ModelVerificationException(field + " is required");
        }
        return value;
    }

    public record VerifiedModel(String modelId, String modelVersion,
                                String registeredChecksum, Path modelFile) {
        public VerifiedModel {
            modelId = Objects.requireNonNull(modelId, "modelId");
            modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
            registeredChecksum = Objects.requireNonNull(
                    registeredChecksum, "registeredChecksum");
            modelFile = Objects.requireNonNull(modelFile, "modelFile")
                    .toAbsolutePath().normalize();
        }
    }

    public static class ModelVerificationException extends RuntimeException {
        public ModelVerificationException(String message) {
            super(message);
        }

        public ModelVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
