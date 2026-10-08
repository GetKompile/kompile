/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatProviderRegistry;
import ai.kompile.cli.main.chat.config.ModelCatalogSelection;
import ai.kompile.cli.main.chat.config.ModelContextResolver;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscoveryHttp;
import ai.kompile.cli.main.chat.config.SetupWizard;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The model catalog every web surface reads (new-chat setup, the session {@code /model} menu,
 * vendor-scoped lists, native session menus) — the interactive {@code ChatRepl} picker's own flow,
 * so the browser and the terminal list the same models.
 *
 * <p>Listing is live per provider route: {@link SetupWizard#modelDiscovery} (provider HTTP catalog,
 * Claude Code's catalog, the Codex app-server, kompile-local's installed models) with one retry on a
 * transport-grade failure, then {@link ModelCatalogSelection#listForPicker}, which records a verified
 * live list and falls back to the last-known-good catalog only when live discovery fails. Native
 * passthrough agents list through the agent CLI's own model command or its provider route.</p>
 *
 * <p>Explicit selections use {@link ModelCatalogSelection#decisionFor}, the decision
 * {@code ChatRepl.applyModelSelection} applies.</p>
 */
public final class WebModelCatalog {

    /** One selectable catalog entry. */
    public record Entry(String id, String display, Integer contextLimit) {
    }

    /** Catalog snapshot for one provider route. */
    public record Listing(
            String provider,
            String currentModel,
            List<Entry> entries,
            boolean liveListingAvailable,
            String note,
            ModelDiscovery.Result discovery) {
        public Listing {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** Outcome of validating an explicit {@code /model <id>} selection. */
    public enum Selection {
        /** Accepted by the live catalog decision (listed, unlisted-but-authoritative, fallback or manual). */
        KNOWN,
        /** Rejected: no verified source accepts the id. */
        UNKNOWN
    }

    private static final Function<ChatConfig, ModelDiscovery.Result> LIVE = WebModelCatalog::liveDiscovery;
    private static volatile Function<ChatConfig, ModelDiscovery.Result> discovery = LIVE;

    private WebModelCatalog() {
    }

    /** Live model discovery for the config's route — the same call the terminal picker makes. */
    public static ModelDiscovery.Result discover(ChatConfig config) {
        return discovery.apply(config);
    }

    /**
     * Discovery with a key typed into setup but not yet saved — the setup wizard's model page,
     * which lists with the transient key before anything is persisted.
     */
    public static ModelDiscovery.Result discover(ChatConfig config, String transientApiKey) {
        if (transientApiKey == null || transientApiKey.isBlank() || discovery != LIVE || config == null
                || "passthrough".equals(config.getChatMode()) || emptyToNull(config.getProvider()) == null) {
            return discover(config);
        }
        String provider = config.getProvider();
        ModelDiscovery.Result result = SetupWizard.modelDiscovery(provider, transientApiKey, config);
        return ModelCatalogSelection.transientFailure(result)
                ? SetupWizard.refreshModelDiscovery(provider, transientApiKey, config) : result;
    }

    /** Test seam: replace live discovery; null restores it. */
    public static void useDiscovery(Function<ChatConfig, ModelDiscovery.Result> replacement) {
        discovery = replacement == null ? LIVE : replacement;
    }

    private static ModelDiscovery.Result liveDiscovery(ChatConfig config) {
        if (config == null) {
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNSUPPORTED,
                    "No chat configuration found; run `kompile chat --setup`.", List.of());
        }
        if ("passthrough".equals(config.getChatMode())) {
            return ModelDiscoveryHttp.discoverPassthroughAgent(config.getPassthroughAgent());
        }
        String provider = config.getProvider();
        if (provider == null || provider.isBlank()) {
            return ModelDiscovery.Result.failure(ModelDiscovery.Status.UNSUPPORTED,
                    "No provider is configured; run `kompile chat --setup`.", List.of());
        }
        ModelDiscovery.Result result = SetupWizard.modelDiscovery(provider, null, config);
        return ModelCatalogSelection.transientFailure(result)
                ? SetupWizard.refreshModelDiscovery(provider, null, config) : result;
    }

    /** Listing for the effective config. */
    public static Listing listing(ChatConfig config) {
        return listing(config, null);
    }

    /**
     * Listing with an explicit last-known-good store override; {@code null} uses the default
     * {@code ~/.kompile/cache/model-catalogs.json} location.
     */
    static Listing listing(ChatConfig config, Path fallbackStorePath) {
        return listing(config, discover(config), fallbackStorePath);
    }

    /** Listing from an already-run discovery, so one request never discovers twice. */
    static Listing listing(ChatConfig config, ModelDiscovery.Result result, Path fallbackStorePath) {
        String provider = config == null ? "" : emptyToNull(config.getProvider());
        String current = config == null ? null : emptyToNull(config.getModel());
        ModelCatalogSelection.CatalogList catalog =
                ModelCatalogSelection.listForPicker(result, provider, fallbackStorePath);
        List<Entry> entries = new ArrayList<>();
        for (String id : catalog.models()) {
            entries.add(new Entry(id, id, contextLimit(provider, id)));
        }
        String note = !catalog.banner().isBlank() ? catalog.banner()
                : entries.isEmpty() ? result.message() : "";
        return new Listing(provider == null ? "" : provider, current, entries,
                !catalog.fromFallback() && !entries.isEmpty(), note, result);
    }

    /** Validate one explicit model id against a fresh live discovery for the config's route. */
    public static Selection validate(ChatConfig config, String modelId) {
        return validate(config, modelId, discover(config), null);
    }

    static Selection validate(ChatConfig config, String modelId, Path fallbackStorePath) {
        return validate(config, modelId, discover(config), fallbackStorePath);
    }

    /** {@code ChatRepl.applyModelSelection}'s decision against an already-run discovery. */
    static Selection validate(ChatConfig config, String modelId, ModelDiscovery.Result result,
                              Path fallbackStorePath) {
        if (config == null || modelId == null || modelId.isBlank()) {
            return Selection.UNKNOWN;
        }
        String candidate = modelId.trim();
        if (candidate.equalsIgnoreCase(emptyToNull(config.getModel()) == null
                ? "" : config.getModel().trim())) {
            return Selection.KNOWN;
        }
        return ModelCatalogSelection.decisionFor(result, config.getProvider(), candidate, fallbackStorePath)
                == ModelCatalogSelection.SelectionDecision.UNKNOWN ? Selection.UNKNOWN : Selection.KNOWN;
    }

    /**
     * Canonical id for a selection: the catalog's exact casing when the id matches
     * case-insensitively, otherwise the id as given.
     */
    static String canonicalId(ChatConfig config, String modelId, ModelDiscovery.Result result,
                              Path fallbackStorePath) {
        if (modelId == null || modelId.isBlank()) {
            return modelId == null ? "" : modelId.trim();
        }
        String candidate = modelId.trim();
        if (config != null && config.getModel() != null && config.getModel().trim().equalsIgnoreCase(candidate)) {
            return config.getModel().trim();
        }
        String provider = config == null ? null : config.getProvider();
        return ModelCatalogSelection.listForPicker(result, provider, fallbackStorePath).models().stream()
                .filter(id -> id.equalsIgnoreCase(candidate))
                .findFirst()
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

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ========================================================================
    // Vendor (provider) switching — the web mirror of the interactive picker's
    // vendor page. Vendors are user-facing keys (anthropic, openai, …), never
    // wire provider ids; ordering and labels reuse the interactive picker so
    // both UIs show the same choices.
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
        LinkedHashSet<String> vendorIds = new LinkedHashSet<>(
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

    /** A native framework the browser can run: a registry key with the managed one-turn contract. */
    public static boolean webFramework(String framework) {
        return framework != null && ChatConfig.getPassthroughAgents().containsKey(framework)
                && SubprocessAgentRunner.supportsHeadless(framework);
    }

    /**
     * Whether {@code vendor} names a native framework for this chat rather than a standard vendor. A key
     * both kinds share (OpenCode) means the kind the chat runs now.
     */
    public static boolean frameworkVendor(ChatConfig config, String vendor) {
        if (!webFramework(vendor)) return false;
        return "passthrough".equals(config == null ? null : config.getChatMode())
                || canonicalVendor(config, vendor) == null;
    }

    /**
     * The {@code vendors} chips of every model menu: the standard vendors and the installed native
     * frameworks in one list, so any chat can switch to any of them. The chat's own vendor or framework
     * is marked current; a key both kinds share is listed once, as the kind the chat runs now.
     */
    public static void putVendors(ObjectNode data, ChatConfig config) {
        boolean nativeRoute = config != null && "passthrough".equals(config.getChatMode());
        String agent = nativeRoute ? config.getPassthroughAgent() : null;
        ArrayNode out = data.putArray("vendors");
        Set<String> listed = new HashSet<>();
        // A native chat has no standard vendor of its own; its leftover provider fields mark nothing current.
        for (VendorEntry vendor : vendors(nativeRoute ? null : config)) {
            if (nativeRoute && webFramework(vendor.vendor())) continue;
            ObjectNode entry = out.addObject().put("vendor", vendor.vendor());
            if (vendor.display() != null && !vendor.display().equals(vendor.vendor())) entry.put("display", vendor.display());
            if (!nativeRoute && config != null && config.getProvider() != null
                    && config.getProvider().equalsIgnoreCase(vendor.currentProvider())) entry.put("current", true);
            listed.add(vendor.vendor());
        }
        for (Map.Entry<String, String> framework : ChatConfig.getPassthroughAgents().entrySet()) {
            String key = framework.getKey();
            boolean own = key.equals(agent);
            // Installed frameworks only; the chat's own stays listed even if it was removed.
            if (listed.contains(key) || (!own && (!webFramework(key)
                    || SubprocessAgentRunner.resolveAgentBinary(key) == null))) continue;
            ObjectNode entry = out.addObject().put("vendor", key).put("display", framework.getValue())
                    .put("framework", true);
            if (own) entry.put("current", true);
        }
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

    /**
     * The config a vendor switch lands on, built like the interactive picker's
     * candidate: LLM settings carried, cross-provider secrets and base URL dropped.
     */
    public static ChatConfig vendorCandidate(ChatConfig config, String wireProvider, String model) {
        ChatConfig candidate = new ChatConfig();
        if (config != null) candidate.applyLlmSettingsFrom(config);
        candidate.setProvider(wireProvider);
        candidate.setModel(model);
        candidate.setThinking(null);
        boolean sameProvider = config != null && wireProvider.equalsIgnoreCase(config.getProvider());
        candidate.setFastMode(sameProvider && config.isFastMode() && candidate.supportsFastMode());
        if (!sameProvider) {
            candidate.setApiKey(null);
            candidate.setBaseUrl(null);
            candidate.setAuthenticationMethod(ChatConfig.authenticationMethodAfterProviderSwitch(wireProvider));
        }
        return candidate;
    }
}
