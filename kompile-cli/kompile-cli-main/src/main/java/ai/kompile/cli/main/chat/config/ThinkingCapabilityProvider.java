/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import java.util.List;

/**
 * Resolves thinking controls from provider-supplied model metadata.
 *
 * <p>This contract deliberately receives the discovered model instead of a
 * model id. Implementations may interpret provider-specific metadata, while
 * the default implementation never invents model families, option values, or
 * defaults.</p>
 */
@FunctionalInterface
public interface ThinkingCapabilityProvider {
    ThinkingCapabilities resolve(LiveModelDiscovery.Model model);

    static ThinkingCapabilityProvider liveModel() {
        return ThinkingCapabilityProvider::fromLiveModel;
    }

    static ThinkingCapabilityProvider liveThen(ThinkingCapabilityProvider fallback) {
        return model -> {
            ThinkingCapabilities live = fromLiveModel(model);
            if (live.supported()) {
                return live;
            }
            return fallback == null ? ThinkingCapabilities.none() : fallback.resolve(model);
        };
    }

    static ThinkingCapabilities fromLiveModel(LiveModelDiscovery.Model model) {
        if (model == null || model.variants().isEmpty()) {
            return ThinkingCapabilities.none();
        }
        List<Option> options = model.thinkingVariants().stream()
                .map(variant -> new Option(
                        variant.value(), variant.label(), variant.description()))
                .toList();
        return new ThinkingCapabilities(
                options,
                model.defaultVariant(),
                Source.LIVE_PROVIDER,
                model.capabilitySource(),
                "",
                "Live provider metadata",
                "",
                model.reasoningMandatory());
    }

    enum Source {
        NONE,
        LIVE_PROVIDER,
        DOCUMENTED_FALLBACK
    }

    record ThinkingCapabilities(
            List<Option> options,
            String defaultValue,
            Source source,
            String sourceResource,
            String sourceUrl,
            String sourceLabel,
            String verifiedAt,
            boolean mandatory) {
        public ThinkingCapabilities(List<Option> options, String defaultValue) {
            this(options, defaultValue, Source.NONE, "", "", "", "", false);
        }

        public ThinkingCapabilities {
            options = options == null ? List.of() : List.copyOf(options);
            defaultValue = defaultValue == null || defaultValue.isBlank() ? "" : defaultValue;
            source = source == null ? Source.NONE : source;
            sourceResource = normalize(sourceResource);
            sourceUrl = normalize(sourceUrl);
            sourceLabel = normalize(sourceLabel);
            verifiedAt = normalize(verifiedAt);
        }

        public static ThinkingCapabilities none() {
            return new ThinkingCapabilities(
                    List.of(), "", Source.NONE, "", "", "", "", false);
        }

        public boolean supported() {
            return !options.isEmpty();
        }

        public boolean documentedFallback() {
            return source == Source.DOCUMENTED_FALLBACK;
        }

        /**
         * Human-readable provenance shown beside documented fallback controls.
         * The classpath resource and its authoritative upstream URL are both
         * deliberately present so the origin cannot be mistaken for live data.
         */
        public String sourceIndicator() {
            if (source == Source.LIVE_PROVIDER) {
                return "LIVE PROVIDER METADATA"
                        + (sourceResource.isBlank() ? "" : " (" + sourceResource + ")");
            }
            if (source != Source.DOCUMENTED_FALLBACK) {
                return "";
            }
            StringBuilder indicator = new StringBuilder("DOCUMENTED FALLBACK");
            if (!sourceResource.isBlank()) {
                indicator.append(": ").append(sourceResource);
            }
            if (!sourceUrl.isBlank()) {
                indicator.append(" -> ").append(sourceUrl);
            }
            if (!verifiedAt.isBlank()) {
                indicator.append(" (verified ").append(verifiedAt).append(')');
            }
            return indicator.toString();
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    record Option(String value, String label, String description) {
        public Option(String value, String label) {
            this(value, label, "");
        }

        public Option {
            value = value == null ? "" : value;
            label = label == null || label.isBlank() ? value : label;
            description = description == null ? "" : description.trim();
        }
    }
}
