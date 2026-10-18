/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider-level fast/priority request contract, separate from thinking/effort.
 * Never gate on model names: the provider validates model support, account
 * entitlement, billing, capacity and regional availability on each request.
 */
public record ProviderFastModeCapabilities(
        ObjectNode onRequest, ObjectNode offRequest, String sourceUrl, String notice) {
    private static final Map<String, ProviderFastModeCapabilities> CACHE = new ConcurrentHashMap<>();

    public ProviderFastModeCapabilities {
        onRequest = onRequest == null ? null : onRequest.deepCopy();
        offRequest = offRequest == null ? null : offRequest.deepCopy();
    }

    public boolean supports(String model) {
        return onRequest != null && model != null && !model.isBlank();
    }

    /** Requests are rebuilt each turn, so omitted off fields revert to provider defaults. */
    public void apply(ObjectNode request, boolean enabled) {
        ObjectNode fields = enabled ? onRequest : offRequest;
        if (fields != null) request.setAll(fields.deepCopy());
    }

    public static ProviderFastModeCapabilities none() {
        return new ProviderFastModeCapabilities(null, null, "", "");
    }

    public static ProviderFastModeCapabilities forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        // Resource descriptors own the contracts; no provider or model allowlist.
        if (!provider.matches("[a-z0-9][a-z0-9-]*")) return none();
        return CACHE.computeIfAbsent(provider, ProviderFastModeCapabilities::load);
    }

    private static ProviderFastModeCapabilities load(String provider) {
        String path = ProviderThinkingConfig.RESOURCE_ROOT + provider + ".json";
        try (InputStream input = ProviderFastModeCapabilities.class.getResourceAsStream(path)) {
            if (input == null) return none();
            JsonNode root = ai.kompile.cli.common.util.JsonUtils.standardMapper().readTree(input);
            if (root.path("schemaVersion").asInt() != 1
                    || !provider.equals(root.path("provider").asText())) return none();
            JsonNode fast = root.path("fastMode");
            if (!ProviderThinkingConfig.DOCUMENTED_FALLBACK.equals(
                    fast.path("metadataSource").asText())) return none();
            JsonNode source = fast.path("source");
            String url = source.path("url").asText("");
            String notice = source.path("notice").asText("");
            if (!url.startsWith("https://") || notice.isBlank()) return none();
            LocalDate.parse(source.path("verifiedAt").asText(""));
            JsonNode on = fast.path("onRequest");
            JsonNode off = fast.path("offRequest");
            if (!(on instanceof ObjectNode onFields) || onFields.isEmpty()
                    || !(off instanceof ObjectNode offFields)) return none();
            return new ProviderFastModeCapabilities(onFields, offFields, url, notice);
        } catch (Exception invalidResource) {
            // Missing or malformed contracts must not enable a paid feature.
            return none();
        }
    }
}
