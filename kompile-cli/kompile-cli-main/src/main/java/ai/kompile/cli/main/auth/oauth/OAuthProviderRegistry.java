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
import java.util.Set;

/** Registry of OAuth flows supported directly by the managed CLI. */
public final class OAuthProviderRegistry {
    private static final Set<String> OAUTH_ONLY = Set.of(
            OpenAiCodexOAuthFlow.PROVIDER_ID,
            GitHubCopilotOAuthFlow.PROVIDER_ID);

    private final Map<String, OAuthProviderFlow> flows;

    public OAuthProviderRegistry() {
        this(defaultFlows());
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
        return providerId != null && OAUTH_ONLY.contains(normalize(providerId));
    }

    private static Collection<OAuthProviderFlow> defaultFlows() {
        return java.util.List.of(
                new OpenAiCodexOAuthFlow(),
                new AnthropicOAuthFlow(),
                new GitHubCopilotOAuthFlow(),
                new XaiOAuthFlow(),
                new OpenRouterOAuthFlow(),
                new RadiusOAuthFlow());
    }

    private static String normalize(String providerId) {
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
