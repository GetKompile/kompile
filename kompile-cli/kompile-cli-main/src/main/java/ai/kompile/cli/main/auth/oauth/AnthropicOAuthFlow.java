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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic Claude Pro/Max browser PKCE OAuth flow. */
final class AnthropicOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "anthropic";

    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final String REDIRECT_URI = "http://localhost:53692/callback";
    private static final String SCOPES =
            "org:create_api_key user:profile user:inference user:sessions:claude_code "
                    + "user:mcp_servers user:file_upload";
    private static final long REFRESH_SKEW_MILLIS = 5 * 60 * 1000L;

    private final OAuthSupport.HttpTransport http;

    AnthropicOAuthFlow() {
        this(OAuthSupport.defaultTransport());
    }

    AnthropicOAuthFlow(OAuthSupport.HttpTransport http) {
        this.http = http;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Anthropic (Claude Pro/Max)";
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
            throw new IOException("Unsupported Anthropic login method: " + method);
        }

        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = pkce.verifier();
        OAuthSupport.CallbackServer callback = null;
        boolean manual = options.manual();

        if (!manual) {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        OAuthSupport.callbackHost(), "localhost", 53692, "/callback", state);
            } catch (IOException e) {
                manual = true;
                interaction.info("Could not bind the Anthropic callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(AUTHORIZE_URL, Map.of(
                "code", "true",
                "client_id", CLIENT_ID,
                "response_type", "code",
                "redirect_uri", REDIRECT_URI,
                "scope", SCOPES,
                "code_challenge", pkce.challenge(),
                "code_challenge_method", "S256",
                "state", state));
        interaction.authorizationUrl(
                authorizeUri,
                manual
                        ? "Complete sign-in, then paste the final redirect URL or authorization code."
                        : "Complete sign-in in your browser; the local callback will finish automatically.");

        try {
            OAuthSupport.AuthorizationInput authorization;
            if (manual) {
                authorization = OAuthSupport.parseAuthorizationInput(
                        interaction.prompt("Paste the authorization code or final redirect URL:"));
                if (authorization.state() != null && !state.equals(authorization.state())) {
                    throw new IOException("OAuth state mismatch");
                }
            } else {
                authorization = callback.await(Duration.ofMinutes(5));
            }
            if (authorization.code() == null || authorization.code().isBlank()) {
                throw new IOException("Anthropic login returned no authorization code");
            }
            String returnedState = authorization.state() == null ? state : authorization.state();
            interaction.info("Exchanging the authorization code for tokens...");
            return exchangeCode(
                    authorization.code(),
                    returnedState,
                    pkce.verifier());
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    private ManagedCredential exchangeCode(String code, String state, String verifier)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("client_id", CLIENT_ID);
        body.put("code", code);
        body.put("state", state);
        body.put("redirect_uri", REDIRECT_URI);
        body.put("code_verifier", verifier);
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/json"),
                OAuthSupport.MAPPER.writeValueAsString(body)));
        return parseToken(response, "Anthropic token exchange", null);
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/json"),
                OAuthSupport.MAPPER.writeValueAsString(Map.of(
                        "grant_type", "refresh_token",
                        "client_id", CLIENT_ID,
                        "refresh_token", credential.getRefresh()))));
        return parseToken(response, "Anthropic token refresh", credential.getRefresh());
    }

    private ManagedCredential parseToken(
            OAuthSupport.Response response,
            String context,
            String previousRefreshToken) throws IOException {
        OAuthSupport.requireSuccess(response, context);
        JsonNode body = OAuthSupport.json(response, context);
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        if (refresh == null) {
            refresh = previousRefreshToken;
        }
        if (refresh == null || refresh.isBlank()) {
            throw new IOException(context + " is missing 'refresh_token'");
        }
        return ManagedCredential.oauth(
                OAuthSupport.requiredText(body, "access_token", context),
                refresh,
                OAuthSupport.expiryFromNow(
                        OAuthSupport.requiredPositiveLong(body, "expires_in", context),
                        REFRESH_SKEW_MILLIS));
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + credential.getAccess());
        headers.put("anthropic-beta", "claude-code-20250219,oauth-2025-04-20");
        headers.put("anthropic-dangerous-direct-browser-access", "true");
        headers.put("user-agent", "kompile-cli");
        headers.put("x-app", "cli");
        return RequestAuth.oauth(credential.getAccess(), null, headers);
    }
}
