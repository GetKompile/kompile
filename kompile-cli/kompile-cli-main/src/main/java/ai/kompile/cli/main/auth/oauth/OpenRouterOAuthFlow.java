/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter PKCE flow. OpenRouter exchanges the code for a permanent API key,
 * represented as a non-expiring OAuth credential.
 */
final class OpenRouterOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "openrouter";

    private static final String AUTHORIZE_URL = "https://openrouter.ai/auth";
    private static final String TOKEN_URL = "https://openrouter.ai/api/v1/auth/keys";

    private final OAuthSupport.HttpTransport http;

    OpenRouterOAuthFlow() {
        this(OAuthSupport.defaultTransport());
    }

    OpenRouterOAuthFlow(OAuthSupport.HttpTransport http) {
        this.http = http;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "OpenRouter OAuth";
    }

    @Override
    public List<LoginMethod> loginMethods() {
        return List.of(new LoginMethod("browser", "Browser login"));
    }

    @Override
    public String defaultLoginMethod() {
        return "browser";
    }

    @Override
    public ManagedCredential login(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException {
        String method = options.methodOr(defaultLoginMethod());
        if (!"browser".equals(method)) {
            throw new IOException("Unsupported OpenRouter login method: " + method);
        }

        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String callbackPath = "/oauth/callback/" + OAuthSupport.randomPathToken();
        OAuthSupport.CallbackServer callback = null;
        URI callbackUri;
        boolean manual = options.manual();

        if (manual) {
            callbackUri = URI.create("http://127.0.0.1:1456" + callbackPath);
        } else {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        OAuthSupport.callbackHost(),
                        OAuthSupport.callbackHost(),
                        0,
                        callbackPath,
                        null);
                callbackUri = callback.redirectUri();
            } catch (IOException e) {
                manual = true;
                callbackUri = URI.create("http://127.0.0.1:1456" + callbackPath);
                interaction.info("Could not bind a callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(AUTHORIZE_URL, Map.of(
                "callback_url", callbackUri.toString(),
                "code_challenge", pkce.challenge(),
                "code_challenge_method", "S256"));
        interaction.authorizationUrl(
                authorizeUri,
                manual
                        ? "Complete sign-in, then paste the final redirect URL or authorization code."
                        : "Complete sign-in in your browser; the local callback will finish automatically.");

        try {
            OAuthSupport.AuthorizationInput authorization = manual
                    ? OAuthSupport.parseAuthorizationInput(
                            interaction.prompt("Paste the authorization code or final redirect URL:"))
                    : callback.await(Duration.ofMinutes(5));
            if (authorization.code() == null || authorization.code().isBlank()) {
                throw new IOException("OpenRouter login returned no authorization code");
            }
            interaction.info("Exchanging the authorization code for an OpenRouter API key...");
            return exchangeCode(authorization.code(), pkce.verifier());
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    private ManagedCredential exchangeCode(String code, String verifier)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/json"),
                OAuthSupport.MAPPER.writeValueAsString(Map.of(
                        "code", code,
                        "code_verifier", verifier,
                        "code_challenge_method", "S256"))));
        OAuthSupport.requireSuccess(response, "OpenRouter OAuth key exchange");
        JsonNode body = OAuthSupport.json(response, "OpenRouter OAuth key exchange");
        return ManagedCredential.oauth(
                OAuthSupport.requiredText(body, "key", "OpenRouter OAuth key exchange"),
                "",
                Long.MAX_VALUE);
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential) {
        return credential;
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://openrouter.ai/api/v1",
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }
}
