/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-specific OAuth protocol implementation. Credential persistence and
 * refresh serialization remain owned by the CLI.
 */
public interface OAuthProviderFlow {
    String providerId();

    /** User-facing vendor id when the credential wire id is an alias. */
    default String userFacingProviderId() {
        return providerId();
    }

    /** Provider-owned request base URL, when the OAuth wire id has one. */
    default String defaultBaseUrl() {
        return null;
    }

    /** Whether this OAuth flow's vendor also accepts API-key credentials. */
    default boolean supportsApiKey() {
        return true;
    }

    String displayName();

    List<LoginMethod> loginMethods();

    String defaultLoginMethod();

    ManagedCredential login(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException;

    ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException;

    /**
     * Revoke provider tokens before removing the local credential. The shared
     * lifecycle supplies a normalized nullable refresh token.
     */
    default boolean revoke(String accessToken, String refreshToken)
            throws IOException, InterruptedException {
        return true;
    }

    RequestAuth toRequestAuth(ManagedCredential credential) throws IOException;

    default boolean supportsMethod(String method) {
        return loginMethods().stream().anyMatch(candidate -> candidate.id().equals(method));
    }

    record LoginMethod(String id, String label) {
        public LoginMethod {
            if (id == null || id.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("OAuth login method id and label are required");
            }
            id = id.trim().toLowerCase(Locale.ROOT);
        }
    }

    record LoginOptions(
            String method,
            boolean manual,
            String enterpriseDomain,
            String gateway) {
        public LoginOptions {
            method = method == null || method.isBlank()
                    ? null
                    : method.trim().toLowerCase(Locale.ROOT);
        }

        public String methodOr(String fallback) {
            return method != null ? method : fallback;
        }
    }

    /**
     * Request-time auth derived from a valid stored OAuth credential.
     */
    record RequestAuth(
            String token,
            String baseUrl,
            Map<String, String> headers,
            boolean oauth) {
        public RequestAuth {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Resolved token must not be blank");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public static RequestAuth apiKey(String token) {
            return new RequestAuth(token, null, Map.of(), false);
        }

        public static RequestAuth oauth(String token, String baseUrl, Map<String, String> headers) {
            return new RequestAuth(token, baseUrl, headers, true);
        }
    }

    /**
     * UI-neutral callbacks used by browser, manual-code, and device-code flows.
     */
    interface Interaction {
        void info(String message);

        void authorizationUrl(URI url, String instructions);

        void deviceCode(
                String userCode,
                URI verificationUri,
                Integer intervalSeconds,
                Integer expiresInSeconds);

        String prompt(String message) throws IOException;
    }
}
