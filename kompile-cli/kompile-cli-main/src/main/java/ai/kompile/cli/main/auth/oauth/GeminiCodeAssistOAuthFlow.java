/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.OAuthCredentialIdentity;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini subscription OAuth — the "Login with Google" flow Google Code
 * Assist performs (Google AI Pro/Ultra). Browser PKCE with a local loopback
 * callback, {@code access_type=offline} and {@code prompt=consent}, so every
 * login stores a refresh token and Google's account chooser lets the user pick
 * which Google account to authorize — signing in again with another account
 * simply stores that account's credential.
 *
 * <p>Kompile ships no client registration. {@code kompile auth login gemini} asks
 * for a Google "Desktop app" OAuth client once and keeps it in
 * {@code ~/.kompile/config/oauth-clients.json}; {@code KOMPILE_GEMINI_CLIENT_ID} /
 * {@code KOMPILE_GEMINI_CLIENT_SECRET} supply one instead.</p>
 */
public final class GeminiCodeAssistOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "gemini";
    static final String CLIENT_ID_ENV = "KOMPILE_GEMINI_CLIENT_ID";
    static final String CLIENT_SECRET_ENV = "KOMPILE_GEMINI_CLIENT_SECRET";

    private static final String AUTHORIZATION_ENDPOINT =
            "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT =
            "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT =
            "https://oauth2.googleapis.com/revoke";
    private static final String USERINFO_ENDPOINT =
            "https://www.googleapis.com/oauth2/v1/userinfo";
    private static final String MANUAL_REDIRECT_URI =
            "http://127.0.0.1:1457/oauth2callback";
    static final String SCOPES =
            "https://www.googleapis.com/auth/cloud-platform"
                    + " https://www.googleapis.com/auth/userinfo.email"
                    + " https://www.googleapis.com/auth/userinfo.profile";

    private final OAuthSupport.HttpTransport http;

    public GeminiCodeAssistOAuthFlow() {
        this(OAuthSupport.defaultTransport());
    }

    GeminiCodeAssistOAuthFlow(OAuthSupport.HttpTransport http) {
        this.http = http;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Google Gemini (Code Assist / Google AI subscription)";
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
            throw new IOException("Unsupported Google Gemini login method: " + method);
        }
        String clientId = clientId();
        String clientSecret = clientSecret();

        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = OAuthSupport.randomState();
        OAuthSupport.CallbackServer callback = null;
        boolean manual = options.manual();
        URI callbackUri;
        if (manual) {
            callbackUri = URI.create(MANUAL_REDIRECT_URI);
        } else {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        "127.0.0.1",
                        OAuthSupport.callbackHost(),
                        0,
                        "/oauth2callback",
                        state);
                callbackUri = callback.redirectUri();
            } catch (IOException e) {
                manual = true;
                callbackUri = URI.create(MANUAL_REDIRECT_URI);
                interaction.info("Could not bind a callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(AUTHORIZATION_ENDPOINT, Map.ofEntries(
                Map.entry("client_id", clientId),
                Map.entry("redirect_uri", callbackUri.toString()),
                Map.entry("response_type", "code"),
                Map.entry("scope", SCOPES),
                Map.entry("code_challenge", pkce.challenge()),
                Map.entry("code_challenge_method", "S256"),
                Map.entry("state", state),
                // Access type offline + consent forces a refresh token even when
                // this client was approved before, and consent re-shows Google's
                // account chooser so a second account is one sign-in away.
                Map.entry("access_type", "offline"),
                Map.entry("prompt", "consent")));
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
                throw new IOException("Google Gemini login returned no authorization code");
            }
            return requestToken(clientId, clientSecret, Map.of(
                    "grant_type", "authorization_code",
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "code", authorization.code(),
                    "code_verifier", pkce.verifier(),
                    "redirect_uri", callbackUri.toString()), null);
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        String clientId = credential.getMetadata("clientId");
        if (clientId == null || clientId.isBlank()) {
            clientId = clientId();
        }
        String clientSecret = clientSecret();
        return requestToken(clientId, clientSecret, Map.of(
                "grant_type", "refresh_token",
                "client_id", clientId,
                "client_secret", clientSecret,
                "refresh_token", credential.getRefresh()), credential);
    }

    @Override
    public boolean revoke(String accessToken, String refreshToken)
            throws IOException, InterruptedException {
        String token = accessToken == null || accessToken.isBlank()
                ? refreshToken
                : accessToken;
        if (token == null || token.isBlank()) {
            return true;
        }
        http.send(new OAuthSupport.Request(
                "POST",
                URI.create(REVOKE_ENDPOINT),
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(Map.of("token", token))));
        // Google returns 200 even for already-invalid tokens; any outcome clears locally.
        return true;
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://cloudcode-pa.googleapis.com",
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }

    private ManagedCredential requestToken(
            String clientId, String clientSecret, Map<String, String> fields,
            ManagedCredential previous)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_ENDPOINT),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(fields)));
        OAuthSupport.requireSuccess(response, "Google Gemini OAuth token request");
        JsonNode body = OAuthSupport.json(response, "Google Gemini OAuth token request");
        String refreshToken = OAuthSupport.optionalText(body, "refresh_token");
        if (refreshToken == null && previous != null) {
            refreshToken = previous.getRefresh();
        }
        String access = OAuthSupport.requiredText(body, "access_token", "Google Gemini OAuth token request");
        long expires = OAuthSupport.requiredPositiveLong(body, "expires_in", "Google Gemini OAuth token request");
        if (refreshToken == null || refreshToken.isBlank()) {
            // Initial consent still requires a refresh token; refresh responses may omit it.
            throw new IOException(
                    "Google Gemini OAuth did not return a refresh token; re-run the login and approve access");
        }
        Map<String, String> metadata = OAuthCredentialIdentity.tokenMetadata(PROVIDER_ID, body, previous);
        metadata.put("clientId", clientId);
        String email = fetchAccountEmail(access);
        if (email != null) {
            metadata.put("email", email);
        }
        return OAuthCredentialIdentity.normalize(PROVIDER_ID, ManagedCredential.oauth(
                access, refreshToken, OAuthSupport.expiryFromNow(expires, 0L), metadata));
    }

    /** Best-effort account label for the credential list; never fails the login. */
    private String fetchAccountEmail(String accessToken) {
        try {
            OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                    "GET",
                    URI.create(USERINFO_ENDPOINT),
                    Map.of(
                            "Authorization", "Bearer " + accessToken,
                            "Accept", "application/json"),
                    ""));
            if (!response.success()) {
                return null;
            }
            JsonNode body = OAuthSupport.json(response, "Google account lookup");
            String email = OAuthSupport.optionalText(body, "email");
            return email == null || email.isBlank() ? null : email;
        } catch (IOException | InterruptedException lookupFailure) {
            if (lookupFailure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String clientId() throws IOException {
        String stored = storedClientField(true);
        if (stored != null) {
            return stored;
        }
        String configured = System.getenv(CLIENT_ID_ENV);
        if (configured == null || configured.isBlank()) {
            throw new IOException("Google Gemini login needs an OAuth client registration. "
                    + "Run `kompile auth login gemini` and follow the prompt, or set "
                    + CLIENT_ID_ENV + " and " + CLIENT_SECRET_ENV
                    + " (Google Cloud console > Credentials > OAuth client ID > Desktop app).");
        }
        return configured.trim();
    }

    private static String clientSecret() throws IOException {
        String stored = storedClientField(false);
        if (stored != null) {
            return stored;
        }
        String configured = System.getenv(CLIENT_SECRET_ENV);
        if (configured == null || configured.isBlank()) {
            throw new IOException("Google Gemini login needs the OAuth client secret. "
                    + "Run `kompile auth login gemini` and follow the prompt, or set "
                    + CLIENT_SECRET_ENV + ".");
        }
        return configured.trim();
    }

    /** The client id or secret from the local oauth-clients store; absent store yields null. */
    private static String storedClientField(boolean clientId) {
        try {
            OAuthClientSettings.ClientCredentials credentials =
                    OAuthClientSettings.create().read(PROVIDER_ID);
            if (credentials == null) {
                return null;
            }
            String value = clientId ? credentials.clientId() : credentials.clientSecret();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (IOException unreadable) {
            return null; // fall back to the environment without masking the real error
        }
    }
}
