/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.OAuthCredentialIdentity;
import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.auth.OAuthCredentialLifecycle;

import java.io.IOException;
import java.util.Objects;
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
            return store.put(providerId, credential);
        } else {
            return store.put(providerId, credentialName, credential, activate);
        }
    }

    /**
     * Resolve API-key or OAuth request auth. OAuth refresh is serialized by the
     * store, so concurrent callers and processes do not rotate the same token.
     */
    public OAuthProviderFlow.RequestAuth resolve(String providerId) throws IOException {
        return resolve(providerId, null);
    }

    /** Filter the chosen auth route before attempting any OAuth network refresh. */
    public OAuthProviderFlow.RequestAuth resolve(String providerId, String requiredType) throws IOException {
        return resolve(providerId, requiredType, null);
    }

    public OAuthProviderFlow.RequestAuth resolve(String providerId, String requiredType, String name) throws IOException {
        return diagnose(CredentialFailure.Operation.STORE, () -> resolveSelected(providerId, requiredType, name));
    }

    private OAuthProviderFlow.RequestAuth resolveSelected(String providerId, String requiredType, String name) throws IOException {
        // Capture the active name once: a concurrent global switch must not mix
        // one account's auth type with another account's token or refresh.
        String selectedName = name == null ? store.activeCredentialName(providerId) : name;
        ManagedCredential credential = selectedName == null ? null : store.read(providerId, selectedName);
        if (name != null && credential == null) throw new IOException("Selected credential no longer exists");
        if (credential == null) {
            return null;
        }
        if (requiredType != null && !requiredType.equals(credential.getType())
                && !(ManagedCredential.OAUTH.equals(requiredType)
                && isLegacyOpenAiCodexApiKey(providerId, credential))) return null;
        if (credential.isApiKey()) {
            String key = store.resolveApiKey(providerId, selectedName, System::getenv);
            if (key == null) {
                return null;
            }
            if (isLegacyOpenAiCodexApiKey(providerId, credential)) {
                return diagnose(CredentialFailure.Operation.REQUEST_AUTH,
                        () -> OpenAiCodexOAuthFlow.toRequestAuthFromAccessToken(key));
            }
            return OAuthProviderFlow.RequestAuth.apiKey(key);
        }

        OAuthProviderFlow flow = registry.require(providerId);
        CredentialStore.SelectedCredential selected = store.resolveOAuthSelection(
                providerId, selectedName,
                MINIMUM_VALIDITY_MILLIS,
                current -> refreshValidated(providerId, flow, current, true));
        ManagedCredential valid = selected == null ? null : selected.credential();
        if (valid == null || !valid.isOAuth()) {
            return null;
        }
        return diagnose(CredentialFailure.Operation.REQUEST_AUTH, () -> {
            OAuthCredentialLifecycle.requireUnexpired(valid, System.currentTimeMillis());
            return flow.toRequestAuth(valid).withCredential(selected.name(),
                    OAuthCredentialIdentity.identityKey(providerId, valid));
        });
    }

    /**
     * Refresh a stored OAuth credential after the provider rejected the access token.
     *
     * <p>The rejected token is compared while holding the credential-store lock. If
     * another process has already rotated it, that newer credential is reused instead
     * of refreshing twice. API keys and missing credentials are deliberately left
     * unchanged because an identical retry cannot repair them.</p>
     */
    public OAuthProviderFlow.RequestAuth refreshAfterUnauthorized(
            String providerId, String rejectedAccessToken) throws IOException {
        return refreshAfterUnauthorized(providerId, rejectedAccessToken, null, null);
    }

    public OAuthProviderFlow.RequestAuth refreshAfterUnauthorized(
            String providerId, OAuthProviderFlow.RequestAuth rejected) throws IOException {
        if (rejected == null || !rejected.oauth() || rejected.credentialName() == null) return null;
        return refreshAfterUnauthorized(providerId, rejected.token(),
                rejected.credentialName(), rejected.credentialIdentity());
    }

    private OAuthProviderFlow.RequestAuth refreshAfterUnauthorized(
            String providerId, String rejectedAccessToken, String expectedName, String expectedIdentity)
            throws IOException {
        return refreshAfterUnauthorized(providerId, rejectedAccessToken, expectedName, expectedIdentity, false);
    }

    public OAuthProviderFlow.RequestAuth refreshNamedAfterUnauthorized(
            String providerId, OAuthProviderFlow.RequestAuth rejected) throws IOException {
        if (rejected == null || !rejected.oauth() || rejected.credentialName() == null) return null;
        return refreshAfterUnauthorized(providerId, rejected.token(), rejected.credentialName(),
                rejected.credentialIdentity(), true);
    }

    private OAuthProviderFlow.RequestAuth refreshAfterUnauthorized(String providerId, String rejectedAccessToken,
            String expectedName, String expectedIdentity, boolean named) throws IOException {
        if (providerId == null || providerId.isBlank()
                || rejectedAccessToken == null || rejectedAccessToken.isBlank()) {
            return null;
        }
        OAuthProviderFlow flow = registry.require(providerId);
        CredentialStore.CredentialUpdater updater = current -> {
            if (current == null || !current.isOAuth()) {
                return current;
            }
            String identity = OAuthCredentialIdentity.identityKey(providerId, current);
            if (expectedIdentity != null && !expectedIdentity.equals(identity)) {
                throw new IOException("OAuth account changed while the request was in flight");
            }
            if (!Objects.equals(current.getAccess(), rejectedAccessToken)) {
                if (expectedName != null && expectedIdentity == null) {
                    throw new IOException("Cannot verify identity of the replacement OAuth credential");
                }
                OAuthCredentialLifecycle.requireUnexpired(current, System.currentTimeMillis());
                return current;
            }
            if (!current.hasRefreshToken()) return current;
            return refreshValidated(providerId, flow, current, false);
        };
        ManagedCredential valid = diagnose(CredentialFailure.Operation.STORE,
                () -> named ? store.modifyNamed(providerId, expectedName, updater)
                        : store.modify(providerId, expectedName, updater));
        return valid != null && valid.isOAuth()
                && !Objects.equals(valid.getAccess(), rejectedAccessToken)
                ? diagnose(CredentialFailure.Operation.REQUEST_AUTH, () -> {
                    OAuthCredentialLifecycle.requireUnexpired(valid, System.currentTimeMillis());
                    return flow.toRequestAuth(valid).withCredential(expectedName,
                            OAuthCredentialIdentity.identityKey(providerId, valid));
                }) : null;
    }

    private ManagedCredential refreshValidated(String providerId, OAuthProviderFlow flow,
                                                ManagedCredential current, boolean proactive) throws IOException {
        return diagnose(CredentialFailure.Operation.REFRESH, () -> {
            String identity = OAuthCredentialIdentity.identityKey(providerId, current);
            ManagedCredential refreshed = OAuthCredentialIdentity.normalize(providerId,
                    OAuthCredentialLifecycle.refresh(current, value -> refreshWithRecovery(flow, value, proactive)));
            OAuthCredentialLifecycle.requireUnexpired(refreshed, System.currentTimeMillis());
            if (identity != null && !identity.equals(OAuthCredentialIdentity.identityKey(providerId, refreshed))) {
                throw new IOException("OAuth refresh returned a different account");
            }
            return refreshed;
        });
    }

    /**
     * Runs inside the store's cross-process lock. Never retry the whole chat or reselect
     * an account. Only known pre-submission failures are eligible for one retry.
     * A proactive refresh may retain the same still-live token; a rejected one may not.
     */
    private ManagedCredential refreshWithRecovery(
            OAuthProviderFlow flow, ManagedCredential current, boolean proactive) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                return flow.refresh(current);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw CredentialFailure.during(CredentialFailure.Operation.REFRESH, new IOException(e));
            } catch (IOException e) {
                CredentialFailure failure = CredentialFailure.classify(e);
                if (Thread.currentThread().isInterrupted() || failure.kind() == CredentialFailure.Kind.INTERRUPTED) {
                    Thread.currentThread().interrupt();
                    throw CredentialFailure.during(CredentialFailure.Operation.REFRESH,
                            new IOException(new InterruptedException()));
                }
                boolean temporary = failure.kind() == CredentialFailure.Kind.TEMPORARY;
                if (proactive && (temporary || failure.kind() == CredentialFailure.Kind.RATE_LIMITED)
                        && !current.expiresWithin(30_000L, System.currentTimeMillis())) {
                    return current;
                }
                // EOF, read timeouts and gateway errors may follow successful token rotation.
                // Do not replay a possibly consumed refresh token without a provider replay contract.
                boolean safeToRetry = temporary && switch (failure.reason()) {
                    case DNS, CONNECT, CONNECT_TIMEOUT -> true;
                    default -> false;
                };
                if (!safeToRetry || attempt >= 1) {
                    throw CredentialFailure.during(CredentialFailure.Operation.REFRESH, e);
                }
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw CredentialFailure.during(CredentialFailure.Operation.REFRESH,
                            new IOException(interrupted));
                }
            }
        }
    }

    private static <T> T diagnose(CredentialFailure.Operation operation, IoOperation<T> action) throws IOException {
        try {
            return action.run();
        } catch (IOException e) {
            throw CredentialFailure.during(operation, e);
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
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
