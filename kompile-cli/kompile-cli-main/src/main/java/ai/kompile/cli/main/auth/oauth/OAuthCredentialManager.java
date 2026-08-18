/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.auth.OAuthCredentialLifecycle;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * App-owned OAuth orchestration: provider login, locked refresh, request auth,
 * and persistence in the canonical managed credential store.
 */
public final class OAuthCredentialManager {
    private static final long MINIMUM_VALIDITY_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final CredentialStore store;
    private final OAuthProviderRegistry registry;

    public OAuthCredentialManager(CredentialStore store, OAuthProviderRegistry registry) {
        if (store == null || registry == null) {
            throw new IllegalArgumentException("store and registry are required");
        }
        this.store = store;
        this.registry = registry;
    }

    public static OAuthCredentialManager create() {
        return new OAuthCredentialManager(
                CredentialStore.create(),
                new OAuthProviderRegistry());
    }

    /**
     * Legacy setup stored an OpenAI Codex subscription access token in the API-key
     * slot. Keep that credential selectable and resolve it with subscription headers.
     */
    public static boolean isLegacyOpenAiCodexApiKey(
            String providerId,
            ManagedCredential credential) {
        return "openai-codex".equalsIgnoreCase(providerId)
                && credential != null
                && credential.isApiKey()
                && OpenAiCodexOAuthFlow.isLegacyAccessToken(credential.getKey());
    }

    public ManagedCredential login(
            String providerId,
            OAuthProviderFlow.LoginOptions options,
            OAuthProviderFlow.Interaction interaction)
            throws IOException, InterruptedException {
        return login(providerId, null, true, options, interaction);
    }

    public ManagedCredential login(
            String providerId,
            String credentialName,
            boolean activate,
            OAuthProviderFlow.LoginOptions options,
            OAuthProviderFlow.Interaction interaction)
            throws IOException, InterruptedException {
        OAuthProviderFlow flow = registry.require(providerId);
        String method = options.methodOr(flow.defaultLoginMethod());
        boolean deviceAlias = "device-code".equals(method) || "device_code".equals(method);
        String registryMethod = deviceAlias ? "device" : method;
        if (!flow.supportsMethod(registryMethod)) {
            throw new IOException("Unsupported " + flow.displayName() + " login method: " + method);
        }
        ManagedCredential credential = flow.login(options, interaction);
        if (credential == null || !credential.isOAuth()) {
            throw new IOException("OAuth provider returned an invalid credential: " + providerId);
        }
        if (credentialName == null || credentialName.isBlank()) {
            store.put(providerId, credential);
        } else {
            store.put(providerId, credentialName, credential, activate);
        }
        return credential;
    }

    /**
     * Resolve API-key or OAuth request auth. OAuth refresh is serialized by the
     * store, so concurrent callers and processes do not rotate the same token.
     */
    public OAuthProviderFlow.RequestAuth resolve(String providerId) throws IOException {
        ManagedCredential credential = store.read(providerId);
        if (credential == null) {
            return null;
        }
        if (credential.isApiKey()) {
            String key = store.resolveApiKey(providerId);
            if (key == null) {
                return null;
            }
            if (isLegacyOpenAiCodexApiKey(providerId, credential)) {
                return OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(key);
            }
            return OAuthProviderFlow.RequestAuth.apiKey(key);
        }

        OAuthProviderFlow flow = registry.require(providerId);
        ManagedCredential valid = store.resolveOAuth(
                providerId,
                MINIMUM_VALIDITY_MILLIS,
                current -> {
                    try {
                        return flow.refresh(current);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(
                                "Interrupted while refreshing OAuth credential for " + providerId,
                                e);
                    }
                });
        if (valid == null) {
            return null;
        }
        return flow.toRequestAuth(valid);
    }

    public boolean logout(String providerId) throws IOException {
        var credentials = store.list(providerId);
        if (credentials.isEmpty()) {
            return false;
        }
        for (CredentialStore.CredentialInfo info : credentials) {
            revoke(providerId, store.read(providerId, info.credentialName()));
        }
        return store.delete(providerId);
    }

    public boolean logout(String providerId, String credentialName) throws IOException {
        ManagedCredential credential = store.read(providerId, credentialName);
        if (credential == null) {
            return false;
        }
        revoke(providerId, credential);
        return store.deleteCredential(providerId, credentialName);
    }

    public int logoutAll() throws IOException {
        var providers = store.list().stream()
                .map(CredentialStore.CredentialInfo::providerId)
                .distinct()
                .toList();
        int removed = 0;
        for (String providerId : providers) {
            int providerCredentialCount = store.list(providerId).size();
            if (logout(providerId)) {
                removed += providerCredentialCount;
            }
        }
        return removed;
    }

    private void revoke(String providerId, ManagedCredential credential) throws IOException {
        if (credential.isOAuth()) {
            OAuthProviderFlow flow = registry.require(providerId);
            OAuthCredentialLifecycle.revoke(credential, (accessToken, refreshToken) -> {
                try {
                    return flow.revoke(accessToken, refreshToken);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while revoking OAuth credential for " + providerId,
                            e);
                }
            });
        }
    }

    public OAuthProviderRegistry registry() {
        return registry;
    }
}
