/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Registry of OAuth flows supported directly by the managed CLI. */
public final class OAuthProviderRegistry {
    private final Map<String, OAuthProviderFlow> flows;

    public OAuthProviderRegistry() {
        this(loadFlows());
    }

    OAuthProviderRegistry(Collection<OAuthProviderFlow> flows) {
        Map<String, OAuthProviderFlow> indexed = new LinkedHashMap<>();
        for (OAuthProviderFlow flow : flows) {
            String id = normalize(flow.providerId());
            if (indexed.put(id, flow) != null) {
                throw new IllegalArgumentException("Duplicate OAuth provider: " + id);
            }
        }
        this.flows = Map.copyOf(indexed);
    }

    public Optional<OAuthProviderFlow> find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(flows.get(normalize(providerId)));
    }

    public OAuthProviderFlow require(String providerId) throws IOException {
        return find(providerId).orElseThrow(() ->
                new IOException("Provider does not have a built-in OAuth flow: " + providerId));
    }

    public Collection<OAuthProviderFlow> flows() {
        return flows.values();
    }

    public boolean isOAuthOnly(String providerId) {
        return find(providerId).map(flow -> !flow.supportsApiKey()).orElse(false);
    }

    /**
     * Resolve the provider used to store OAuth credentials for a user-facing vendor.
     * OpenAI's API-key and ChatGPT subscription credentials intentionally use
     * different wire providers while sharing one vendor in the CLI menus.
     */
    public Optional<String> oauthProviderForVendor(String vendorId) {
        if (vendorId == null || vendorId.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(vendorId);
        return flows.values().stream()
                .filter(flow -> normalized.equals(normalize(flow.userFacingProviderId()))
                        || normalized.equals(normalize(flow.providerId())))
                .map(OAuthProviderFlow::providerId)
                .findFirst();
    }

    /** Whether the user-facing provider accepts an API-key credential. */
    public boolean supportsApiKey(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        boolean flowSupportsApiKey = flows.values().stream()
                .filter(flow -> normalize(providerId).equals(normalize(flow.providerId()))
                        || normalize(providerId).equals(normalize(flow.userFacingProviderId())))
                .anyMatch(OAuthProviderFlow::supportsApiKey);
        if (flowSupportsApiKey) {
            return true;
        }
        ai.kompile.cli.main.chat.config.ChatProvider provider =
                ai.kompile.cli.main.chat.config.ChatProviderRegistry.find(providerId);
        return provider != null && provider.supportsApiKey();
    }

    private static Collection<OAuthProviderFlow> loadFlows() {
        Map<String, OAuthProviderFlow> loaded = new LinkedHashMap<>();
        try {
            ServiceLoader.load(OAuthProviderFlow.class).stream()
                    .map(provider -> {
                        try {
                            return provider.get();
                        } catch (ServiceConfigurationError error) {
                            return null;
                        }
                    })
                    .filter(flow -> flow != null)
                    .forEach(flow -> {
                        String id = normalize(flow.providerId());
                        if (loaded.putIfAbsent(id, flow) != null) {
                            throw new IllegalArgumentException("Duplicate OAuth provider: " + id);
                        }
                    });
        } catch (ServiceConfigurationError ignored) {
            // Optional provider plugins may be absent or unavailable in a distribution.
        }
        return loaded.values();
    }

    private static String normalize(String providerId) {
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
