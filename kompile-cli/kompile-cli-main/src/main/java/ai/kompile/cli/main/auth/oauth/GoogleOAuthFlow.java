/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, 2.0.
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
 * Google OAuth for Kompile-managed local crawls (Gmail, Drive, Docs, Workspace).
 *
 * <p>Browser PKCE with {@code access_type=offline} and {@code prompt=consent} so the
 * stored credential carries a refresh token. Google desktop/loopback clients do not use
 * a client secret, but a registered Google Cloud OAuth client id is required; supply it
 * with {@code KOMPILE_GOOGLE_CLIENT_ID}. Scopes mirror the app-side
 * {@code GoogleOAuthHandler} defaults (drive/gmail read + profile) so a locally stored
 * credential grants the same read surface as a server-side connection.</p>
 */
public final class GoogleOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "google";
    static final String CLIENT_ID_ENV = "KOMPILE_GOOGLE_CLIENT_ID";

    private static final String AUTHORIZATION_ENDPOINT =
            "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT =
            "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT =
            "https://oauth2.googleapis.com/revoke";
    static final String DEFAULT_SCOPES =
            "https://www.googleapis.com/auth/drive.readonly"
                    + " https://www.googleapis.com/auth/gmail.readonly"
                    + " email profile";


    private final OAuthSupport.HttpTransport http;
    private final String configuredClientId;

    public GoogleOAuthFlow() {
        this(OAuthSupport.defaultTransport(), null);
    }

    GoogleOAuthFlow(OAuthSupport.HttpTransport http) {
        this(http, null);
    }

    /** Test/distribution hook: pins the client id instead of reading the environment. */
    GoogleOAuthFlow(OAuthSupport.HttpTransport http, String configuredClientId) {
        this.http = http;
        this.configuredClientId = configuredClientId;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Google (Gmail / Drive read-only)";
    }

    @Override
    public boolean supportsApiKey() {
        return false;
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
            throw new IOException("Unsupported Google login method: " + method);
        }
        String clientId = clientId();
        String scopes = System.getenv("KOMPILE_GOOGLE_SCOPES");
        if (scopes == null || scopes.isBlank()) {
            scopes = DEFAULT_SCOPES;
        }

        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = OAuthSupport.randomState();
        OAuthSupport.CallbackServer callback = null;
        boolean manual = options.manual();
        URI callbackUri;
        if (manual) {
            callbackUri = URI.create("http://127.0.0.1:1456/oauth/callback");
        } else {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        "127.0.0.1",
                        OAuthSupport.callbackHost(),
                        0,
                        "/oauth/callback",
                        state);
                callbackUri = callback.redirectUri();
            } catch (IOException e) {
                manual = true;
                callbackUri = URI.create("http://127.0.0.1:1456/oauth/callback");
                interaction.info("Could not bind a callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(AUTHORIZATION_ENDPOINT, Map.ofEntries(
                Map.entry("client_id", clientId),
                Map.entry("redirect_uri", callbackUri.toString()),
                Map.entry("response_type", "code"),
                Map.entry("scope", scopes),
                Map.entry("code_challenge", pkce.challenge()),
                Map.entry("code_challenge_method", "S256"),
                Map.entry("state", state),
                // Access type offline + consent forces a refresh token even when the
                // user has previously approved this client.
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
                throw new IOException("Google login returned no authorization code");
            }
            return requestToken(Map.of(
                    "grant_type", "authorization_code",
                    "client_id", clientId,
                    "code", authorization.code(),
                    "code_verifier", pkce.verifier(),
                    "redirect_uri", redirectForExchange(callbackUri)), null);
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
        return requestToken(Map.of(
                "grant_type", "refresh_token",
                "client_id", clientId,
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
                "https://www.googleapis.com",
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }

    private ManagedCredential requestToken(Map<String, String> fields, ManagedCredential previous)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_ENDPOINT),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(fields)));
        OAuthSupport.requireSuccess(response, "Google OAuth token request");
        JsonNode body = OAuthSupport.json(response, "Google OAuth token request");
        String refreshToken = OAuthSupport.optionalText(body, "refresh_token");
        if (refreshToken == null && previous != null) {
            refreshToken = previous.getRefresh();
        }
        String access = OAuthSupport.requiredText(body, "access_token", "Google OAuth token request");
        long expires = OAuthSupport.requiredPositiveLong(body, "expires_in", "Google OAuth token request");
        Map<String, String> metadata = OAuthCredentialIdentity.tokenMetadata(PROVIDER_ID, body, previous);
        metadata.put("clientId", fields.get("client_id"));
        if (refreshToken == null || refreshToken.isBlank()) {
            // Initial consent still requires a refresh token; refresh responses may omit it.
            throw new IOException(
                    "Google OAuth did not return a refresh token; re-run the login and approve access");
        }
        return ManagedCredential.oauth(access, refreshToken,
                OAuthSupport.expiryFromNow(expires, 0L), metadata);
    }

    private String clientId() throws IOException {
        if (configuredClientId != null && !configuredClientId.isBlank()) {
            return configuredClientId.trim();
        }
        String stored = storedClientField("clientId");
        if (stored != null) {
            return stored;
        }
        String configured = System.getenv(CLIENT_ID_ENV);
        if (configured == null || configured.isBlank()) {
            throw new IOException("Google OAuth requires a registered OAuth client id. "
                    + "Run `kompile auth login google` and follow the prompt, or set "
                    + CLIENT_ID_ENV + " (Google Cloud console > APIs & Services > Credentials, "
                    + "type 'Desktop app'; loopback redirect http://127.0.0.1 is allowed automatically).");
        }
        return configured.trim();
    }

    /** Reads one field from the local oauth-clients store; absent store yields null. */
    String storedClientField(String field) {
        try {
            OAuthClientSettings.ClientCredentials credentials =
                    OAuthClientSettings.create().read(PROVIDER_ID);
            if (credentials == null) {
                return null;
            }
            return switch (field) {
                case "clientId" -> credentials.clientId();
                case "clientSecret" -> credentials.clientSecret();
                case "tenantId" -> credentials.tenantId();
                default -> null;
            };
        } catch (IOException unreadable) {
            return null; // fall back to the environment without masking the real error
        }
    }

    private static String redirectForExchange(URI callbackUri) {
        // Google requires the identical redirect_uri value used in the authorization request.
        return callbackUri.toString();
    }
}
