/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.core.llm.CliModelCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Thinking capabilities resolved from the CLI's on-disk models.dev catalog
 * ({@code reasoning_options} — the same hot-reloaded file that supplies context
 * windows and output limits).
 *
 * <p>The catalog is provider-native metadata, published and maintained upstream in
 * the models.dev format; parsing it is not a heuristic. It ranks between live HTTP
 * discovery and the documented fallback resource: live wins when a provider answers
 * with thinking metadata, the catalog covers the long tail of OpenAI-compatible
 * providers (zai, groq, deepseek, …) whose {@code /models} endpoint returns bare
 * ids, and the documented fallback remains the no-catalog last resort.</p>
 */
final class CatalogThinkingCapabilities {
    private CatalogThinkingCapabilities() {
    }

    /** Reasoning on/off switch — the catalog's {@code toggle} entry. */
    static final String TOGGLE = "toggle";
    /** Reasoning effort marker — effort values follow this token in the list. */
    static final String EFFORT = "effort";

    static ThinkingCapabilityProvider forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        return model -> resolve(provider, model);
    }

    private static ThinkingCapabilityProvider.ThinkingCapabilities resolve(
            String provider, LiveModelDiscovery.Model model) {
        if (model == null || model.id().isBlank() || provider.isBlank()) {
            return ThinkingCapabilityProvider.ThinkingCapabilities.none();
        }
        Optional<CliModelCatalog.ModelSpec> spec =
                CliModelCatalog.lookup(provider, model.id());
        if (spec.isEmpty()) {
            return ThinkingCapabilityProvider.ThinkingCapabilities.none();
        }
        List<String> tokens = spec.get().reasoningOptions();
        if (tokens.isEmpty()) {
            return ThinkingCapabilityProvider.ThinkingCapabilities.none();
        }

        List<ThinkingCapabilityProvider.Option> options = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (TOGGLE.equals(token)) {
                options.add(new ThinkingCapabilityProvider.Option(
                        "enabled", "Enabled", "Model reasoning on"));
                options.add(new ThinkingCapabilityProvider.Option(
                        "disabled", "Disabled", "Model reasoning off"));
            } else if (EFFORT.equals(token)) {
                // Following tokens are the effort values until the next type marker.
                for (int j = i + 1; j < tokens.size(); j++) {
                    String value = tokens.get(j);
                    if (TOGGLE.equals(value) || EFFORT.equals(value)) break;
                    options.add(new ThinkingCapabilityProvider.Option(
                            value, value.toUpperCase(Locale.ROOT)));
                }
            }
        }
        if (options.isEmpty()) {
            return ThinkingCapabilityProvider.ThinkingCapabilities.none();
        }
        return new ThinkingCapabilityProvider.ThinkingCapabilities(
                options,
                "",
                ThinkingCapabilityProvider.Source.LIVE_PROVIDER,
                "catalog:reasoning_options",
                "",
                "models.dev catalog (reasoning_options)",
                "",
                false);
    }
}
