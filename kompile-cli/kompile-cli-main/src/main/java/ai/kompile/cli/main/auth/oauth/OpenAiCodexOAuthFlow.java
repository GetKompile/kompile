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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI Codex ChatGPT subscription OAuth with browser and headless device flows. */
public final class OpenAiCodexOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "openai-codex";

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTH_BASE_URL = "https://auth.openai.com";
    private static final String AUTHORIZE_URL = AUTH_BASE_URL + "/oauth/authorize";
    private static final String TOKEN_URL = AUTH_BASE_URL + "/oauth/token";
    private static final String DEVICE_USER_CODE_URL = AUTH_BASE_URL + "/api/accounts/deviceauth/usercode";
    private static final String DEVICE_TOKEN_URL = AUTH_BASE_URL + "/api/accounts/deviceauth/token";
    private static final String DEVICE_VERIFICATION_URL = AUTH_BASE_URL + "/codex/device";
    private static final String BROWSER_REDIRECT_URI = "http://localhost:1455/auth/callback";
    private static final String DEVICE_REDIRECT_URI = AUTH_BASE_URL + "/deviceauth/callback";
    private static final String SCOPE = "openid profile email offline_access";
    private static final String ACCOUNT_CLAIM = "https://api.openai.com/auth";
    private static final int DEVICE_TIMEOUT_SECONDS = 15 * 60;

    private final OAuthSupport.HttpTransport http;
    private final OAuthSupport.DeviceCodePoller poller;

    public OpenAiCodexOAuthFlow() {
        this(OAuthSupport.defaultTransport(), new OAuthSupport.DeviceCodePoller());
    }

    OpenAiCodexOAuthFlow(
            OAuthSupport.HttpTransport http,
            OAuthSupport.DeviceCodePoller poller) {
        this.http = http;
        this.poller = poller;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String userFacingProviderId() {
        return "openai";
    }

    @Override
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public String defaultBaseUrl() {
        return "https://chatgpt.com/backend-api";
    }

    @Override
    public String displayName() {
        return "OpenAI Codex (ChatGPT Plus/Pro)";
    }

    @Override
    public List<LoginMethod> loginMethods() {
        return List.of(
                new LoginMethod("browser", "Browser login"),
                new LoginMethod("device", "Device code login (headless)"));
    }

    @Override
    public String defaultLoginMethod() {
        return "browser";
    }

    @Override
    public ManagedCredential login(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException {
        String method = options.methodOr(defaultLoginMethod());
        return switch (method) {
            case "browser" -> loginBrowser(options, interaction);
            case "device", "device-code", "device_code" -> loginDevice(interaction);
            default -> throw new IOException("Unsupported OpenAI Codex login method: " + method);
        };
    }

    private ManagedCredential loginBrowser(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException {
        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = OAuthSupport.randomState();
        URI redirectUri = URI.create(BROWSER_REDIRECT_URI);
        OAuthSupport.CallbackServer callback = null;
        boolean manual = options.manual();

        if (!manual) {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        OAuthSupport.callbackHost(), "localhost", 1455, "/auth/callback", state);
            } catch (IOException e) {
                manual = true;
                interaction.info("Could not bind the OAuth callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(AUTHORIZE_URL, Map.ofEntries(
                Map.entry("response_type", "code"),
                Map.entry("client_id", CLIENT_ID),
                Map.entry("redirect_uri", redirectUri.toString()),
                Map.entry("scope", SCOPE),
                Map.entry("code_challenge", pkce.challenge()),
                Map.entry("code_challenge_method", "S256"),
                Map.entry("state", state),
                Map.entry("id_token_add_organizations", "true"),
                Map.entry("codex_cli_simplified_flow", "true"),
                Map.entry("originator", "kompile")));

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
                throw new IOException("OpenAI Codex login returned no authorization code");
            }
            interaction.info("Exchanging the authorization code for tokens...");
            return exchangeCode(authorization.code(), pkce.verifier(), redirectUri.toString());
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    private ManagedCredential loginDevice(Interaction interaction)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(DEVICE_USER_CODE_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/json"),
                OAuthSupport.MAPPER.writeValueAsString(Map.of("client_id", CLIENT_ID))));
        OAuthSupport.requireSuccess(response, "OpenAI Codex device authorization");
        JsonNode body = OAuthSupport.json(response, "OpenAI Codex device authorization");
        String deviceAuthId = OAuthSupport.requiredText(
                body, "device_auth_id", "OpenAI Codex device authorization");
        String userCode = OAuthSupport.requiredText(
                body, "user_code", "OpenAI Codex device authorization");
        int interval = OAuthSupport.optionalPositiveInt(body, "interval", 5);

        interaction.deviceCode(
                userCode,
                URI.create(DEVICE_VERIFICATION_URL),
                interval,
                DEVICE_TIMEOUT_SECONDS);

        DeviceAuthorization authorization = poller.poll(
                interval,
                DEVICE_TIMEOUT_SECONDS,
                false,
                () -> pollDevice(deviceAuthId, userCode));
        return exchangeCode(
                authorization.authorizationCode(),
                authorization.codeVerifier(),
                DEVICE_REDIRECT_URI);
    }

    private OAuthSupport.PollResult<DeviceAuthorization> pollDevice(
            String deviceAuthId,
            String userCode) throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(DEVICE_TOKEN_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/json"),
                OAuthSupport.MAPPER.writeValueAsString(Map.of(
                        "device_auth_id", deviceAuthId,
                        "user_code", userCode))));

        if (response.success()) {
            JsonNode body = OAuthSupport.json(response, "OpenAI Codex device token");
            return OAuthSupport.PollResult.complete(new DeviceAuthorization(
                    OAuthSupport.requiredText(body, "authorization_code", "OpenAI Codex device token"),
                    OAuthSupport.requiredText(body, "code_verifier", "OpenAI Codex device token")));
        }
        if (response.status() == 403 || response.status() == 404) {
            return OAuthSupport.PollResult.pending();
        }

        String error = null;
        try {
            JsonNode body = OAuthSupport.json(response, "OpenAI Codex device token");
            JsonNode errorNode = body.get("error");
            if (errorNode != null && errorNode.isTextual()) {
                error = errorNode.textValue();
            } else if (errorNode != null && errorNode.isObject()) {
                error = OAuthSupport.optionalText(errorNode, "code");
            }
        } catch (IOException ignored) {
            // The status code still provides a safe failure message.
        }
        if ("deviceauth_authorization_pending".equals(error)) {
            return OAuthSupport.PollResult.pending();
        }
        if ("slow_down".equals(error)) {
            return OAuthSupport.PollResult.slowDown(null);
        }
        return OAuthSupport.PollResult.failed(
                "OpenAI Codex device authorization failed (HTTP " + response.status() + ")");
    }

    private ManagedCredential exchangeCode(String code, String verifier, String redirectUri)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(Map.of(
                        "grant_type", "authorization_code",
                        "client_id", CLIENT_ID,
                        "code", code,
                        "code_verifier", verifier,
                        "redirect_uri", redirectUri))));
        return parseToken(response, "OpenAI Codex token exchange", null);
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_URL),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(Map.of(
                        "grant_type", "refresh_token",
                        "client_id", CLIENT_ID,
                        "refresh_token", credential.getRefresh()))));
        return parseToken(response, "OpenAI Codex token refresh", credential.getRefresh());
    }

    private ManagedCredential parseToken(
            OAuthSupport.Response response,
            String context,
            String previousRefreshToken) throws IOException {
        OAuthSupport.requireSuccess(response, context);
        JsonNode body = OAuthSupport.json(response, context);
        String access = OAuthSupport.requiredText(body, "access_token", context);
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        if (refresh == null) {
            refresh = previousRefreshToken;
        }
        if (refresh == null || refresh.isBlank()) {
            throw new IOException(context + " is missing 'refresh_token'");
        }
        long expiresIn = OAuthSupport.requiredPositiveLong(body, "expires_in", context);
        String accountId = extractAccountId(access);
        return ManagedCredential.oauth(
                access,
                refresh,
                OAuthSupport.expiryFromNow(expiresIn, 0L),
                Map.of("accountId", accountId));
    }

    private static String extractAccountId(String accessToken) throws IOException {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length != 3) {
                throw new IOException("OpenAI Codex access token is not a JWT");
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = OAuthSupport.MAPPER.readTree(decoded);
            JsonNode auth = payload.get(ACCOUNT_CLAIM);
            String accountId = auth == null ? null : OAuthSupport.optionalText(auth, "chatgpt_account_id");
            if (accountId == null) {
                throw new IOException("OpenAI Codex token carries no ChatGPT account id");
            }
            return accountId;
        } catch (IllegalArgumentException e) {
            throw new IOException("Could not decode OpenAI Codex access token", e);
        }
    }

    /**
     * Recognize access tokens written by the legacy chat setup as API keys.
     * They are still valid subscription credentials and must remain selectable.
     */
    static boolean isLegacyAccessToken(String accessToken) {
        try {
            extractAccountId(accessToken);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static RequestAuth toRequestAuthFromAccessToken(String accessToken) throws IOException {
        return requestAuth(accessToken, extractAccountId(accessToken));
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) throws IOException {
        String accountId = credential.getMetadata("accountId");
        if (accountId == null || accountId.isBlank()) {
            accountId = extractAccountId(credential.getAccess());
        }
        return requestAuth(credential.getAccess(), accountId);
    }

    private static RequestAuth requestAuth(String accessToken, String accountId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("chatgpt-account-id", accountId);
        headers.put("originator", "kompile");
        headers.put("OpenAI-Beta", "responses=experimental");
        return RequestAuth.oauth(
                accessToken,
                "https://chatgpt.com/backend-api",
                headers);
    }

    private record DeviceAuthorization(String authorizationCode, String codeVerifier) {
    }
}
