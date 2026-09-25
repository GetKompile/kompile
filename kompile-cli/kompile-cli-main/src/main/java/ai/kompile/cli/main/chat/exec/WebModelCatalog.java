/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatProviderRegistry;
import ai.kompile.cli.main.chat.config.ModelCatalogFallback;
import ai.kompile.cli.main.chat.config.ModelContextResolver;
import ai.kompile.cli.main.chat.config.SetupWizard;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared, non-secret model catalog and validation surface used by web-JSON
 * {@code /model} command resolution — extracted from the interactive
 * {@code ChatRepl} picker flow so both UIs agree on what is selectable.
 *
 * <p><b>Honest local-only listing.</b> The interactive picker's first-choice
 * source is live provider discovery, which requires reachable provider
 * endpoints or installed native CLIs. Web/headless command runs must stay
 * cheap, deterministic, and network-free, so this API lists only models that
 * are available <em>without any live call</em>:</p>
 *
 * <ul>
 *   <li>the provider's last-known-good catalog from {@link ModelCatalogFallback}
 *       ({@code ~/.kompile/cache/model-catalogs.json}, recorded exclusively by
 *       verified interactive live discovery), and</li>
 *   <li>the currently configured model id.</li>
 * </ul>
 *
 * <p>{@link Listing#liveListingAvailable()} is {@code false} whenever live
 * discovery was not consulted, and callers must surface that honestly in the
 * menu payload instead of implying the list is complete.</p>
 *
 * <p>Validation mirrors {@code ChatRepl.applyModelSelection} semantics (the
 * {@link ai.kompile.cli.main.chat.config.ModelCatalogSelection} decision policy):
 * a selection is only accepted when it is locally verifiable. Unlike the
 * interactive flow, {@code MANUAL_ENTRY} is not offered here — without a live
 * list, accepting an arbitrary id would make a web command look authoritative
 * when it cannot verify the model exists.</p>
 */
public final class WebModelCatalog {

    /** One selectable catalog entry. */
    public record Entry(String id, String display, Integer contextLimit) {
    }

    /** Non-secret catalog snapshot for a working directory's effective config. */
    public record Listing(
            String provider,
            String currentModel,
            List<Entry> entries,
            boolean liveListingAvailable,
            String note) {
        public Listing {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** Outcome of validating an explicit {@code /model <id>} selection. */
    public enum Selection {
        /** Present in the recorded (last-known-good) catalog or equal to the current model. */
        KNOWN,
        /** Not locally verifiable; rejected for web command runs. */
        UNKNOWN
    }

    private WebModelCatalog() {
    }

    /** Listing for the effective config in {@code workingDirectory}. */
    public static Listing listing(ChatConfig config) {
        return listing(config, null);
    }

    /**
     * Listing with an explicit fallback-store override; {@code null} uses the
     * default {@code ~/.kompile/cache/model-catalogs.json} location.
     */
    static Listing listing(ChatConfig config, java.nio.file.Path fallbackStorePath) {
        if (config == null) {
            return new Listing("", null, List.of(), false,
                    "No chat configuration found; run `kompile chat --setup`.");
        }
        String provider = emptyToNull(config.getProvider());
        String current = emptyToNull(config.getModel());
        List<Entry> entries = new ArrayList<>();
        if (current != null) {
            entries.add(new Entry(current, current, contextLimit(provider, current)));
        }
        ModelCatalogFallback.lookup(provider, fallbackStorePath).ifPresent(recorded -> {
            for (String id : recorded.models()) {
                if (entries.stream().noneMatch(entry -> entry.id().equalsIgnoreCase(id))) {
                    entries.add(new Entry(id, id, contextLimit(provider, id)));
                }
            }
        });
        String note = liveNote(recordedAge(provider, fallbackStorePath));
        return new Listing(provider == null ? "" : provider, current,
                entries, false, note);
    }

    /** Validate one explicitly typed model id against the local catalog surface. */
    public static Selection validate(ChatConfig config, String modelId) {
        return validate(config, modelId, null);
    }

    /** Validation with an explicit fallback-store override (tests/embedders). */
    static Selection validate(ChatConfig config, String modelId, java.nio.file.Path fallbackStorePath) {
        if (config == null || modelId == null || modelId.isBlank()) {
            return Selection.UNKNOWN;
        }
        String candidate = modelId.trim();
        if (candidate.equalsIgnoreCase(emptyToNull(config.getModel()) == null
                ? "" : config.getModel().trim())) {
            return Selection.KNOWN;
        }
        return ModelCatalogFallback.knows(config.getProvider(), candidate, fallbackStorePath)
                ? Selection.KNOWN : Selection.UNKNOWN;
    }

    /**
     * Canonical id for a selection: the recorded catalog's exact casing when the
     * id matches case-insensitively, otherwise the id as typed.
     */
    public static String canonicalId(ChatConfig config, String modelId) {
        return canonicalId(config, modelId, null);
    }

    /** Canonicalization with an explicit fallback-store override (tests/embedders). */
    static String canonicalId(ChatConfig config, String modelId, java.nio.file.Path fallbackStorePath) {
        if (config == null || modelId == null || modelId.isBlank()) {
            return modelId == null ? "" : modelId.trim();
        }
        String candidate = modelId.trim();
        if (config.getModel() != null && config.getModel().trim().equalsIgnoreCase(candidate)) {
            return config.getModel().trim();
        }
        return ModelCatalogFallback.lookup(config.getProvider(), fallbackStorePath)
                .flatMap(recorded -> recorded.models().stream()
                        .filter(id -> id.equalsIgnoreCase(candidate))
                        .findFirst())
                .orElse(candidate);
    }

    private static Integer contextLimit(String provider, String modelId) {
        try {
            int context = new ModelContextResolver().resolveLimits(provider, modelId, null, 0, 0)
                    .contextWindow();
            return context > 0 ? context : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String recordedAge(String provider, java.nio.file.Path fallbackStorePath) {
        return ModelCatalogFallback.lookup(provider, fallbackStorePath)
                .map(recorded -> ModelCatalogFallback.ageLabel(recorded.recordedAt()))
                .orElse("");
    }

    private static String liveNote(String recordedAge) {
        boolean hasRecorded = recordedAge != null && !recordedAge.isBlank();
        String base = "Local listing: the provider's last-known-good catalog recorded "
                + (hasRecorded ? recordedAge : "by interactive use")
                + ". A live provider model list was not fetched (headless runs stay "
                + "offline and deterministic); run the interactive CLI /model picker for a live refresh.";
        return base;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ========================================================================
    // Vendor (provider) switching — the web mirror of the interactive picker's
    // vendor page. Vendors are user-facing keys (anthropic, openai, …), never
    // wire provider ids; ordering and labels reuse the interactive picker so
    // both UIs show the same choices. Read-only: no live auth probing happens
    // here; validity is surfaced at selection time.
    // ========================================================================

    /** One selectable vendor entry. */
    public record VendorEntry(String vendor, String display, String currentProvider) {
    }

    /**
     * Switchable vendors for a working directory — the same set the
     * interactive {@code /model} picker offers ({@code switchableProviders}),
 * i.e. the direct vendor order plus the session's current vendor when it is
     * otherwise unlisted (never the kompile runtime-owned vendors).
     */
    public static List<VendorEntry> vendors(ChatConfig config) {
        java.util.LinkedHashSet<String> vendorIds = new java.util.LinkedHashSet<>(
                SetupWizard.providerPickerOrder());
        String currentVendor = config == null ? null
                : SetupWizard.vendorForProvider(config.getProvider());
        if (currentVendor != null && !currentVendor.isBlank()
                && !"kompile".equalsIgnoreCase(currentVendor)
                && !"kompile-local".equalsIgnoreCase(currentVendor)) {
            vendorIds.add(currentVendor);
        }
        List<VendorEntry> vendors = new ArrayList<>();
        for (String vendor : vendorIds) {
            vendors.add(new VendorEntry(vendor, SetupWizard.vendorLabel(vendor),
                    vendor.equalsIgnoreCase(currentVendor) ? config.getProvider() : null));
        }
        return List.copyOf(vendors);
    }

    /** Canonical vendor id for a case-insensitive request, or null when absent. */
    public static String canonicalVendor(ChatConfig config, String requested) {
        if (requested == null || requested.isBlank()) return null;
        for (VendorEntry vendor : vendors(config)) {
            if (vendor.vendor().equalsIgnoreCase(requested.trim())) return vendor.vendor();
        }
        return null;
    }

    /**
     * Wire provider for a vendor using the session's credential setup: the
     * vendor itself when it is a chat provider, else the configured wire
     * provider when it belongs to that vendor, else null (unknown vendor).
     * Mirrors the picker's {@code resolveProviderForAuth(NONE)} default route.
     */
    public static String providerForVendor(ChatConfig config, String vendor) {
        if (vendor == null || vendor.isBlank()) return null;
        if (ChatProviderRegistry.find(vendor) != null) return vendor;
        String configured = config == null ? null : config.getProvider();
        if (configured != null && !configured.isBlank()
                && vendor.equalsIgnoreCase(SetupWizard.vendorForProvider(configured))) {
            return configured;
        }
        return null;
    }
}
