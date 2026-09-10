/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Selection policy shared by the setup wizard, the in-session provider/model
 * picker, and {@code /model <id>}.
 *
 * <p>Live provider discovery is authoritative. When a live request cannot
 * populate a list (timeout, outage, rate limit), callers may still proceed
 * using the provider's last-known-good catalog — but only with explicit
 * labeling of the list's age and the live failure, and only for statuses that
 * indicate a transport problem rather than a deliberately empty catalog.</p>
 *
 * <p>Recording of verified live lists happens ONLY here — the interactive
 * chokepoints — never inside non-interactive discovery, so test/CI fixture
 * catalogs cannot pollute the persisted store.</p>
 */
public final class ModelCatalogSelection {
    private ModelCatalogSelection() {
    }

    /** Outcome of resolving a picker list from a discovery result. */
    public record CatalogList(
            List<String> models,
            boolean fromFallback,
            String banner,
            ModelDiscovery.Result live) {
        public CatalogList {
            models = models == null ? List.of() : List.copyOf(models);
            banner = banner == null ? "" : banner;
        }
    }

    /** Decision for an explicitly typed {@code /model <id>} request. */
    public enum SelectionDecision {
        /** The id is in the live list — accept. */
        LIVE_LIST,
        /** The id is absent from the live list but in the recorded catalog — accept with a note. */
        FALLBACK_LIST,
        /** The id was typed manually while the live list was unusable — accept with a note. */
        MANUAL_ENTRY,
        /** The id is absent from both the live list and the recorded catalog — reject. */
        UNKNOWN
    }

    /** Picker list for the default fallback store location. */
    public static CatalogList listForPicker(ModelDiscovery.Result discovery, String provider) {
        return listForPicker(discovery, provider, null);
    }

    /**
     * Resolve the model list the picker should render.
     *
     * @param storePath fallback store override; {@code null} uses the default
     *                  {@code ~/.kompile/cache/model-catalogs.json} location
     */
    public static CatalogList listForPicker(
            ModelDiscovery.Result discovery, String provider, Path storePath) {
        List<String> live = SetupWizard.modelOptions(provider, discovery, null);
        if (!live.isEmpty()) {
            // Interactive-only recording point: a verified live list becomes the
            // provider's last known good catalog. Non-interactive discovery never
            // writes the store, so fixture catalogs cannot pollute it.
            ModelCatalogFallback.record(provider, live,
                    discovery.attemptedEndpoints().isEmpty()
                            ? null : discovery.attemptedEndpoints()
                            .get(discovery.attemptedEndpoints().size() - 1),
                    storePath);
            return new CatalogList(live, false, "", discovery);
        }
        if (!fallbackEligible(discovery.status())) {
            return new CatalogList(List.of(), false, "", discovery);
        }
        Optional<ModelCatalogFallback.RecordedCatalog> recorded =
                ModelCatalogFallback.lookup(provider, storePath);
        if (recorded.isEmpty() || recorded.get().models().isEmpty()) {
            return new CatalogList(List.of(), false, "", discovery);
        }
        String age = ModelCatalogFallback.ageLabel(recorded.get().recordedAt());
        String banner = "Live discovery failed ("
                + statusLabel(discovery.status()) + "). Showing the last known good "
                + providerLabel(provider) + " catalog from " + age + "."
                + (discovery.message().isBlank()
                ? "" : " Live error: " + discovery.message());
        return new CatalogList(recorded.get().models(), true, banner, discovery);
    }

    /** Explicit {@code /model <id>} decision against the default fallback store. */
    public static SelectionDecision decisionFor(
            ModelDiscovery.Result discovery, String provider, String modelId) {
        return decisionFor(discovery, provider, modelId, null);
    }

    /**
     * @param storePath fallback store override; {@code null} uses the default location
     */
    public static SelectionDecision decisionFor(
            ModelDiscovery.Result discovery, String provider, String modelId, Path storePath) {
        if (modelId == null || modelId.isBlank()) {
            return SelectionDecision.UNKNOWN;
        }
        if (discovery == null) {
            return SelectionDecision.UNKNOWN;
        }
        String id = modelId.trim();
        boolean inLive = discovery.models().stream()
                .map(LiveModelDiscovery.Model::id)
                .anyMatch(candidate -> candidate.equalsIgnoreCase(id));
        if (inLive) {
            return SelectionDecision.LIVE_LIST;
        }
        if (discovery.status() == ModelDiscovery.Status.SUCCESS && discovery.hasModels()) {
            // An authoritative live list that simply does not contain the id.
            return SelectionDecision.UNKNOWN;
        }
        if (ModelCatalogFallback.knows(provider, id, storePath)) {
            return SelectionDecision.FALLBACK_LIST;
        }
        if (discovery.status() == ModelDiscovery.Status.SUCCESS_EMPTY
                || discovery.status() == ModelDiscovery.Status.UNSUPPORTED) {
            return SelectionDecision.UNKNOWN;
        }
        return SelectionDecision.MANUAL_ENTRY;
    }

    /** Short note to print when a non-live selection is accepted. */
    public static String noteFor(SelectionDecision decision, String modelId, String provider) {
        return switch (decision) {
            case LIVE_LIST -> "";
            case FALLBACK_LIST -> modelId + " matched the last known good "
                    + providerLabel(provider)
                    + " catalog; the live list could not be verified this time.";
            case MANUAL_ENTRY -> modelId + " was accepted without a verified "
                    + "catalog entry; the provider will validate it on the next request.";
            case UNKNOWN -> "";
        };
    }

    static boolean fallbackEligible(ModelDiscovery.Status status) {
        return status == ModelDiscovery.Status.TIMEOUT
                || status == ModelDiscovery.Status.UNAVAILABLE
                || status == ModelDiscovery.Status.RATE_LIMITED;
    }

    static String statusLabel(ModelDiscovery.Status status) {
        return status == null ? "unavailable"
                : status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String providerLabel(String provider) {
        String label = SetupWizard.vendorLabel(provider);
        return label == null || label.isBlank() ? String.valueOf(provider) : label;
    }
}
