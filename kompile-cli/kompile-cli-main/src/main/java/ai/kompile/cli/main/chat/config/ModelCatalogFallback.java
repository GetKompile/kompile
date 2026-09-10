/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Last-known-good provider model catalogs for the interactive picker.
 *
 * <p>Live provider discovery stays authoritative: every usable catalog
 * response is recorded here, and a recorded catalog is never presented as a
 * live list. When the provider's live catalog cannot be populated (transient
 * outage, rate limit, timeout), the picker and setup wizard offer the
 * recorded catalog with its age and the live failure reason, so a provider
 * switch never collapses into a blind manual id prompt.</p>
 *
 * <p>The store is deliberately small: one entry per provider id holding the
 * model ids from the most recent usable response, the base URL that served
 * it, and the recording time. It persists under
 * {@code ~/.kompile/cache/model-catalogs.json} so the fallback survives a
 * restart. All failures are swallowed at the call boundary: the fallback
 * store must never break a live switch.</p>
 */
public final class ModelCatalogFallback {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_MODELS = 500;
    private static final Object LOCK = new Object();
    private static Map<String, RecordedCatalog> memory;
    private static Path memoryPath;

    private ModelCatalogFallback() {
    }

    /** A provider catalog captured from an earlier usable discovery response. */
    public record RecordedCatalog(
            String provider, List<String> models, String baseUrl, Instant recordedAt) {
        public RecordedCatalog {
            models = models == null ? List.of() : List.copyOf(models);
        }

        /** Case-insensitive membership test mirroring picker id matching. */
        public boolean knows(String modelId) {
            if (modelId == null || modelId.isBlank()) {
                return false;
            }
            for (String candidate : models) {
                if (candidate.equalsIgnoreCase(modelId.trim())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Record a usable catalog response as the provider's last known good. */
    public static void record(String provider, List<String> models, String baseUrl) {
        record(provider, models, baseUrl, null);
    }

    /**
     * Record against an explicit store; {@code null} storePath uses the default
     * {@code ~/.kompile/cache/model-catalogs.json} location.
     */
    static void record(String provider, List<String> models, String baseUrl, Path storePath) {
        Path effective = storePath == null ? defaultStorePath() : storePath;
        if (provider == null || provider.isBlank()
                || models == null || models.isEmpty()) {
            return;
        }
        List<String> trimmed = models.stream()
                .filter(model -> model != null && !model.isBlank())
                .limit(MAX_MODELS)
                .toList();
        if (trimmed.isEmpty()) {
            return;
        }
        RecordedCatalog entry = new RecordedCatalog(
                provider.trim(), trimmed, baseUrl, Instant.now());
        synchronized (LOCK) {
            Map<String, RecordedCatalog> current = new LinkedHashMap<>(
                    memory != null && memoryPath != null && memoryPath.equals(effective)
                            ? memory : load(effective));
            current.put(normalize(provider), entry);
            memory = Map.copyOf(current);
            memoryPath = effective;
            persist(effective, memory);
        }
    }

    /** The provider's last known good catalog, if one was ever recorded. */
    public static Optional<RecordedCatalog> lookup(String provider) {
        return lookup(provider, defaultStorePath());
    }

    static Optional<RecordedCatalog> lookup(String provider, Path storePath) {
        if (provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        Path effective = storePath == null ? defaultStorePath() : storePath;
        synchronized (LOCK) {
            return Optional.ofNullable(load(effective).get(normalize(provider)));
        }
    }

    /** Whether the model id appears in the provider's recorded catalog. */
    public static boolean knows(String provider, String modelId) {
        return knows(provider, modelId, defaultStorePath());
    }

    static boolean knows(String provider, String modelId, Path storePath) {
        return lookup(provider, storePath)
                .map(recorded -> recorded.knows(modelId))
                .orElse(false);
    }

    /** Force the next read to reload from disk (used by tests). */
    static void resetMemoryForTest() {
        synchronized (LOCK) {
            memory = null;
            memoryPath = null;
        }
    }

    /** Human-readable age for picker labels: "just now", "5m ago", "2h ago", "3d ago". */
    public static String ageLabel(Instant recordedAt) {
        return ageLabel(recordedAt, Instant.now());
    }

    static String ageLabel(Instant recordedAt, Instant now) {
        if (recordedAt == null) {
            return "an unknown time";
        }
        Duration age = Duration.between(recordedAt, now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        long minutes = age.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = age.toHours();
        if (hours < 24) {
            return hours + "h ago";
        }
        return age.toDays() + "d ago";
    }

    private static Path defaultStorePath() {
        return KompileHome.homeDirectory().toPath()
                .resolve("cache")
                .resolve("model-catalogs.json");
    }

    private static String normalize(String provider) {
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, RecordedCatalog> load(Path storePath) {
        synchronized (LOCK) {
            if (memory != null && (memoryPath == null || memoryPath.equals(storePath))) {
                return memory;
            }
            Map<String, RecordedCatalog> loaded = readStore(storePath);
            memory = Map.copyOf(loaded);
            memoryPath = storePath;
            return memory;
        }
    }

    private static Map<String, RecordedCatalog> readStore(Path storePath) {
        if (storePath == null || !Files.isRegularFile(storePath)) {
            return new LinkedHashMap<>();
        }
        try (InputStream input = Files.newInputStream(storePath)) {
            String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return new LinkedHashMap<>();
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    ai.kompile.cli.common.util.JsonUtils.standardMapper().readTree(body);
            if (root == null || !root.isObject()
                    || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                    || !root.path("providers").isObject()) {
                return new LinkedHashMap<>();
            }
            Map<String, RecordedCatalog> entries = new LinkedHashMap<>();
            var fields = root.path("providers").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                RecordedCatalog entry = parseEntry(field.getKey(), field.getValue());
                if (entry != null && !entry.models().isEmpty()) {
                    entries.put(normalize(entry.provider()), entry);
                }
            }
            return entries;
        } catch (Exception ignored) {
            // A corrupt store degrades to "no fallback recorded"; never fatal.
            return new LinkedHashMap<>();
        }
    }

    private static RecordedCatalog parseEntry(String provider, com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> models = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode model : node.path("models")) {
            if (model.isTextual() && !model.asText().isBlank()) {
                models.add(model.asText().trim());
            }
        }
        if (models.isEmpty()) {
            return null;
        }
        String baseUrl = node.path("baseUrl").isTextual() && !node.path("baseUrl").asText().isBlank()
                ? node.path("baseUrl").asText() : null;
        Instant recordedAt;
        String recorded = node.path("recordedAt").isTextual() ? node.path("recordedAt").asText() : "";
        try {
            recordedAt = recorded.isBlank() ? null : Instant.parse(recorded);
        } catch (Exception ignored) {
            recordedAt = null;
        }
        return new RecordedCatalog(provider, models, baseUrl, recordedAt);
    }

    private static void persist(Path storePath, Map<String, RecordedCatalog> entries) {
        if (storePath == null) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    ai.kompile.cli.common.util.JsonUtils.standardMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
            root.put("schemaVersion", SCHEMA_VERSION);
            com.fasterxml.jackson.databind.node.ObjectNode providers = root.putObject("providers");
            for (RecordedCatalog entry : entries.values()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = providers.putObject(
                        normalize(entry.provider()));
                com.fasterxml.jackson.databind.node.ArrayNode models = node.putArray("models");
                entry.models().forEach(models::add);
                if (entry.baseUrl() != null) {
                    node.put("baseUrl", entry.baseUrl());
                }
                if (entry.recordedAt() != null) {
                    node.put("recordedAt", entry.recordedAt().toString());
                }
            }
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, "model-catalogs", ".tmp");
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, storePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedMove) {
                Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Persistence is best-effort; the in-memory entry remains active.
        }
    }
}
