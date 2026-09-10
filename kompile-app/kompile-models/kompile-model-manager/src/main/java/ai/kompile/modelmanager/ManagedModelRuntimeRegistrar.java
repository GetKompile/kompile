/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.modelmanager;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.modelmanager.registry.TokenizerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Registers a converted managed model as a runnable project-local runtime artifact. */
public final class ManagedModelRuntimeRegistrar {

    private ManagedModelRuntimeRegistrar() {
    }

    public static ModelEntry register(
            Path modelsRoot,
            ManagedModelArtifactCatalog.Definition definition,
            Path convertedModel,
            Path tokenizerPath) throws IOException {
        Path root = modelsRoot.toAbsolutePath().normalize();
        Path model = convertedModel.toAbsolutePath().normalize();
        Path tokenizer = tokenizerPath.toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();
        if (!Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(model)
                || !model.toRealPath().startsWith(realRoot)) {
            throw new IOException("Converted managed model must exist under " + root + ": " + model);
        }
        validateConvertedModel(model);
        if (!Files.isRegularFile(tokenizer, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(tokenizer)
                || !tokenizer.toRealPath().startsWith(realRoot)) {
            throw new IOException("Managed encoder tokenizer must exist under " + root + ": " + tokenizer);
        }
        if (!JsonUtils.standardMapper().readTree(tokenizer.toFile()).isObject()) {
            throw new IOException("Managed encoder tokenizer is not a JSON object: " + tokenizer);
        }
        Path directory = model.getParent();
        if (!directory.equals(tokenizer.getParent())) {
            throw new IOException("Managed model and tokenizer must be in the same runtime bundle directory");
        }
        for (ManagedModelArtifactCatalog.Component component : definition.components()) {
            if (!component.required()) {
                continue;
            }
            Path source = directory.resolve(component.localFileName()).normalize();
            if (!source.startsWith(directory)
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)
                    || !source.toRealPath().startsWith(realRoot)) {
                throw new IOException("Managed runtime registration requires pinned component '"
                        + component.key() + "': " + source);
            }
            verifyPinnedComponent(component, source);
        }
        ManagedModelArtifactCatalog.Component tokenizerComponent = definition
                .component(definition.tokenizerComponentKey())
                .orElseThrow(() -> new IOException(
                        "Managed definition has no tokenizer component: " + definition.modelId()));
        Path pinnedTokenizer = directory.resolve(tokenizerComponent.localFileName()).normalize();
        if (!tokenizer.equals(pinnedTokenizer)) {
            throw new IOException("Managed tokenizer does not match the pinned component path: "
                    + tokenizer);
        }

        ModelMetadata metadata = ModelMetadata.builder()
                .embeddingDim(integerMetadata(definition, "embedding_dim", 0))
                .maxSequenceLength(integerMetadata(definition, "max_sequence_length", 512))
                .modelType("dense")
                .encoderType(definition.metadata().get("encoder_type"))
                .poolingStrategy(definition.metadata().get("pooling_strategy"))
                .inputPrefix(definition.metadata().get("input_prefix"))
                .normalizeOutput(booleanMetadata(definition, "normalize_output", true))
                .framework("samediff")
                .sourceOrigin(definition.source())
                .sourceRepository(definition.repository())
                .originalFormat(definition.format())
                .conversionDate(Instant.now().toString())
                .version(definition.revision())
                .supportedLanguages(List.of(definition.metadata()
                        .getOrDefault("supported_languages", "multilingual")))
                .build();
        ModelEntry entry = ModelEntry.builder()
                .modelId(definition.modelId())
                .type(ModelType.fromValue(definition.modelType()))
                .path(root.relativize(directory).toString())
                .modelFile(model.getFileName().toString())
                .vocabFile(tokenizer.getFileName().toString())
                .checksum(ManagedModelArtifactDownloader.sha256(model))
                .status(ModelStatus.ACTIVE)
                .promotedAt(Instant.now().toString())
                .metadata(metadata)
                .tokenizer(TokenizerConfig.builder()
                        .doLowerCase(false)
                        .stripAccents(false)
                        .addSpecialTokens(true)
                        .maxLength(integerMetadata(definition, "max_sequence_length", 512))
                        .padding("max_length")
                        .truncation(true)
                        .build())
                .build();
        new RegistryService(root).addModel(entry);
        return entry;
    }

    /** Validate the minimum portable contract before a converter result is reported as successful. */
    public static void validateConvertedModel(Path convertedModel) throws IOException {
        Path model = convertedModel.toAbsolutePath().normalize();
        if (!Files.isRegularFile(model) || Files.isSymbolicLink(model)
                || !model.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sdz")
                || Files.size(model) < 1_024L) {
            throw new IOException("Converted model is not a plausible SameDiff SDZ artifact: " + model);
        }
    }

    private static void verifyPinnedComponent(
            ManagedModelArtifactCatalog.Component component, Path path) throws IOException {
        long actualBytes = Files.size(path);
        if (component.expectedBytes() > 0 && actualBytes != component.expectedBytes()) {
            throw new IOException("Managed component size mismatch for " + path
                    + ": expected " + component.expectedBytes() + ", got " + actualBytes);
        }
        if (component.sha256() != null && !component.sha256().isBlank()) {
            String actualChecksum = ManagedModelArtifactDownloader.sha256(path);
            if (!component.sha256().equalsIgnoreCase(actualChecksum)) {
                throw new IOException("Managed component checksum mismatch for " + path);
            }
        }
    }

    private static int integerMetadata(
            ManagedModelArtifactCatalog.Definition definition, String key, int defaultValue) {
        String value = definition.metadata().get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return defaultValue;
        }
    }

    private static boolean booleanMetadata(
            ManagedModelArtifactCatalog.Definition definition, String key, boolean defaultValue) {
        String value = definition.metadata().get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }
}
