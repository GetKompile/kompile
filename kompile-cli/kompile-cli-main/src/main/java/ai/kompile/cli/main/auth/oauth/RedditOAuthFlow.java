/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reddit OAuth for Kompile-managed local crawls.
 *
 * <p>Authorization-code flow with HTTP Basic client authentication and
 * {@code duration=permanent} so the stored credential carries a refresh token.
 * Requires a Reddit "web app" client id + secret via {@code KOMPILE_REDDIT_CLIENT_ID} /
 * {@code KOMPILE_REDDIT_CLIENT_SECRET} (the same registration the app-side handler
 * uses). Scopes mirror the app-side handler (identity read).</p>
 */
public final class RedditOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "reddit";
    static final String CLIENT_ID_ENV = "KOMPILE_REDDIT_CLIENT_ID";
    static final String CLIENT_SECRET_ENV = "KOMPILE_REDDIT_CLIENT_SECRET";

    private static final String AUTHORIZATION_ENDPOINT =
            "https://www.reddit.com/api/v1/authorize";
    private static final String TOKEN_ENDPOINT =
            "https://www.reddit.com/api/v1/access_token";
    private static final String REVOKE_ENDPOINT =
            "https://www.reddit.com/api/v1/revoke_token";
    static final String DEFAULT_SCOPES = "identity read";


    private final OAuthSupport.HttpTransport http;
    private final String configuredClientId;
    private final String configuredClientSecret;

    public RedditOAuthFlow() {
        this(OAuthSupport.defaultTransport(), null, null);
    }

    RedditOAuthFlow(OAuthSupport.HttpTransport http) {
        this(http, null, null);
    }

    /** Test/distribution hook: pins credentials instead of reading the environment. */
    RedditOAuthFlow(OAuthSupport.HttpTransport http, String clientId, String clientSecret) {
        this.http = http;
        this.configuredClientId = clientId;
        this.configuredClientSecret = clientSecret;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Reddit";
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
        if (!"browser".equals(options.methodOr(defaultLoginMethod()))) {
            throw new IOException("Unsupported Reddit login method: "
                    + options.methodOr(defaultLoginMethod()));
        }
        String clientId = clientId();
        String clientSecret = clientSecret();

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
                Map.entry("scope", DEFAULT_SCOPES),
                Map.entry("state", state),
                Map.entry("duration", "permanent")));
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
                throw new IOException("Reddit login returned no authorization code");
            }
            return tokenRequest(Map.of(
                    "grant_type", "authorization_code",
                    "code", authorization.code(),
                    "redirect_uri", callbackUri.toString()), clientId, clientSecret, null);
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
        return tokenRequest(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", credential.getRefresh()), clientId, clientSecret(), credential);
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
                Map.of(
                        "Content-Type", "application/x-www-form-urlencoded",
                        "Authorization", "Basic " + basic()),
                OAuthSupport.form(Map.of(
                        "token", token,
                        "token_type_hint", accessToken == null || accessToken.isBlank()
                                ? "refresh_token" : "access_token"))));
        return true;
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://oauth.reddit.com",
                Map.of(
                        "Authorization", "Bearer " + credential.getAccess(),
                        "User-Agent", "Kompile/0.1 (Reddit source integration)"));
    }

    private ManagedCredential tokenRequest(
            Map<String, String> fields, String clientId, String clientSecret, ManagedCredential previous)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_ENDPOINT),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded",
                        "Authorization", "Basic " + basic(clientId, clientSecret)),
                OAuthSupport.form(fields)));
        OAuthSupport.requireSuccess(response, "Reddit OAuth token request");
        JsonNode body = OAuthSupport.json(response, "Reddit OAuth token request");
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        if (refresh == null && previous != null) {
            refresh = previous.getRefresh();
        }
        String access = OAuthSupport.requiredText(body, "access_token",
                "Reddit OAuth token request");
        long expiresIn = OAuthSupport.requiredPositiveLong(body, "expires_in",
                "Reddit OAuth token request");
        Map<String, String> metadata = new LinkedHashMap<>(
                previous == null ? Map.of() : previous.getMetadata());
        metadata.put("clientId", clientId);
        String scope = OAuthSupport.optionalText(body, "scope");
        if (scope != null) {
            metadata.put("scope", scope);
        }
        if (refresh == null || refresh.isBlank()) {
            throw new IOException("Reddit OAuth did not return a refresh token; "
                    + "the authorization request must use duration=permanent");
        }
        return ManagedCredential.oauth(access, refresh,
                OAuthSupport.expiryFromNow(expiresIn, 0L), metadata);
    }

    private String basic() throws IOException {
        return basic(clientId(), clientSecret());
    }

    private static String basic(String clientId, String clientSecret) {
        return Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
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
            throw new IOException("Reddit OAuth requires a web app client id and secret. "
                    + "Run `kompile auth login reddit` and follow the prompt, or set "
                    + CLIENT_ID_ENV + " and " + CLIENT_SECRET_ENV
                    + " (reddit.com/prefs/apps > create 'web app', redirect "
                    + "http://127.0.0.1).");
        }
        return configured.trim();
    }

    private String clientSecret() throws IOException {
        if (configuredClientSecret != null && !configuredClientSecret.isBlank()) {
            return configuredClientSecret.trim();
        }
        String stored = storedClientField("clientSecret");
        if (stored != null) {
            return stored;
        }
        String configured = System.getenv(CLIENT_SECRET_ENV);
        if (configured == null || configured.isBlank()) {
            throw new IOException("Reddit OAuth requires the web app client secret. "
                    + "Run `kompile auth login reddit` and follow the prompt, or set "
                    + CLIENT_SECRET_ENV + " (reddit.com/prefs/apps > your web app's secret).");
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
}
