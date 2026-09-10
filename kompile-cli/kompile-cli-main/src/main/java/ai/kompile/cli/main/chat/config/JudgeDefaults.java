/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Judge-only defaults. No credentials or main-chat model/thinking settings are stored here. */
public final class JudgeDefaults {
    public static final String CONFIG_FILE = "judge-defaults.json";
    private static final ObjectMapper MAPPER = ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final TypeReference<Map<String, Selection>> TYPE = new TypeReference<>() {};
    private static final List<String> EFFORT_ORDER = List.of(
            "off", "none", "disabled", "minimal", "min", "low", "medium", "high", "xhigh", "max");

    private JudgeDefaults() {}

    public record Selection(String model, String thinking, String thinkingProvider) {
        public Selection(String model, String thinking) { this(model, thinking, null); }

        public Selection {
            model = clean(model);
            thinking = clean(thinking);
            thinkingProvider = clean(thinkingProvider);
        }
    }

    /** API and native login routes for the same vendor share one judge default. */
    public static String vendor(String provider) {
        String value = clean(provider);
        if (value == null) return "";
        value = value.toLowerCase(Locale.ROOT);
        if (value.endsWith("-cli")) value = value.substring(0, value.length() - 4);
        return switch (value) {
            case "claude", "claude-code" -> "anthropic";
            case "codex", "openai-codex" -> "openai";
            case "google" -> "gemini";
            case "kompile-local" -> "kompile";
            default -> value;
        };
    }

    public static Path configPath(ChatConfig.Scope scope, Path projectRoot) {
        return ChatConfig.configPath(scope, projectRoot).resolveSibling(CONFIG_FILE);
    }

    public static Selection configured(String provider, Path projectRoot) {
        try {
            ChatProfiles.Profile profile = ChatProfiles.activeJudge(projectRoot, provider);
            if (profile != null) return new Selection(profile.model(), profile.thinking(), profile.provider());
        } catch (IOException e) {
            System.err.println("Warning: Could not load project judge profile: " + e.getMessage());
        }
        return vendorDefault(provider, projectRoot);
    }

    /** Legacy scoped defaults remain available when no project profile is selected. */
    public static Selection vendorDefault(String provider, Path projectRoot) {
        String vendor = vendor(provider);
        Selection project = load(configPath(ChatConfig.Scope.PROJECT, projectRoot)).get(vendor);
        return project != null ? project : load(configPath(ChatConfig.Scope.GLOBAL, projectRoot)).get(vendor);
    }

    private static Map<String, Selection> read(Path path) throws IOException {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        Map<String, Selection> values = MAPPER.readValue(path.toFile(), TYPE);
        if (values == null) throw new IOException("Judge defaults must be a JSON object");
        return new LinkedHashMap<>(values);
    }

    private static Map<String, Selection> load(Path path) {
        try {
            return read(path);
        } catch (IOException e) {
            System.err.println("Warning: Could not load judge defaults " + path + ": " + e.getMessage());
            return Map.of();
        }
    }

    /** Merge one vendor only; a bad existing file is never silently overwritten. */
    public static synchronized void save(ChatConfig.Scope scope, Path projectRoot,
                                         String provider, Selection selection) throws IOException {
        if (vendor(provider).isBlank() || selection == null || selection.model() == null) {
            throw new IllegalArgumentException("A vendor and judge model are required");
        }
        Path path = configPath(scope, projectRoot);
        Map<String, Selection> values = read(path);
        values.put(vendor(provider), new Selection(selection.model(), selection.thinking(),
                selection.thinking() == null ? null : capabilityProvider(provider)));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "judge-defaults-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Explicit judge model > active project profile > saved vendor default > fallback. Never inherit chat thinking. */
    public static Selection resolve(String provider, Path projectRoot, String explicitModel, String fallbackModel) {
        Selection saved = configured(provider, projectRoot);
        String model = clean(explicitModel);
        if (model == null && saved != null) model = saved.model();
        if (model == null) model = clean(fallbackModel);
        String thinking = saved != null && model != null && model.equals(saved.model())
                ? saved.thinking() : null;
        List<SetupWizard.ThinkingOption> options = SetupWizard.thinkingOptions(
                capabilityProvider(provider), model, null, null, null);
        String selectedThinking = thinking;
        // A choice verified live by the wizard outranks an older documented fallback on that route.
        // Other login routes share the model, but must not receive an unsupported thinking value.
        String thinkingProvider = saved == null || saved.thinkingProvider() == null
                ? vendor(provider) : saved.thinkingProvider();
        if (thinking == null || (!capabilityProvider(provider).equals(thinkingProvider)
                && options.stream().noneMatch(option -> selectedThinking.equals(option.value())))) {
            thinking = lowestThinking(options);
        }
        return new Selection(model, thinking);
    }

    /** Select only advertised values, not a guessed provider flag or model-family default. */
    public static String lowestThinking(String provider, String model, ModelDiscovery.Result discovery) {
        return lowestThinking(SetupWizard.thinkingOptions(capabilityProvider(provider), model, null, null, discovery));
    }

    static String capabilityProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "codex", "codex-cli", "openai-codex" -> "openai-codex";
            case "claude", "claude-cli", "claude-code" -> "anthropic";
            default -> ChatProviderRegistry.find(normalized) != null ? normalized : vendor(normalized);
        };
    }

    static String lowestThinking(List<SetupWizard.ThinkingOption> options) {
        // Provider lists need not be ordered (some return their default/high tier first).
        for (String effort : EFFORT_ORDER) {
            for (var option : options) {
                if (effort.equalsIgnoreCase(option.value())) return option.value();
            }
        }
        // Unknown semantics: do not claim that the provider's first variant is its cheapest.
        return null;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
