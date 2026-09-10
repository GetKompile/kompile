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
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Atlassian OAuth 2.0 (3LO) for Kompile-managed local crawls (Jira Cloud, Confluence).
 *
 * <p>Authorization-code + PKCE flow with HTTP Basic client authentication. Scopes
 * mirror the app-side handler (Confluence content/space read, Jira work/user read,
 * offline_access). On login the accessible-resources call resolves the first
 * accessible cloud id, stored as credential metadata so the Jira loader's
 * {@code accessToken + cloudId} contract is satisfied without asking again.
 * Requires an OAuth 2.0 (3LO) app via {@code KOMPILE_ATLASSIAN_CLIENT_ID} /
 * {@code KOMPILE_ATLASSIAN_CLIENT_SECRET}.</p>
 */
public final class AtlassianOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "atlassian";
    static final String CLIENT_ID_ENV = "KOMPILE_ATLASSIAN_CLIENT_ID";
    static final String CLIENT_SECRET_ENV = "KOMPILE_ATLASSIAN_CLIENT_SECRET";

    private static final String AUTHORIZATION_ENDPOINT =
            "https://auth.atlassian.com/authorize";
    private static final String TOKEN_ENDPOINT =
            "https://auth.atlassian.com/oauth/token";
    private static final String RESOURCES_ENDPOINT =
            "https://api.atlassian.com/oauth/token/accessible-resources";
    static final String DEFAULT_SCOPES =
            "read:confluence-content.all read:confluence-space.summary"
                    + " read:jira-work read:jira-user offline_access";


    private final OAuthSupport.HttpTransport http;
    private final String configuredClientId;
    private final String configuredClientSecret;

    public AtlassianOAuthFlow() {
        this(OAuthSupport.defaultTransport(), null, null);
    }

    AtlassianOAuthFlow(OAuthSupport.HttpTransport http) {
        this(http, null, null);
    }

    /** Test/distribution hook: pins credentials instead of reading the environment. */
    AtlassianOAuthFlow(
            OAuthSupport.HttpTransport http,
            String clientId,
            String clientSecret) {
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
        return "Atlassian (Jira / Confluence)";
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
            throw new IOException("Unsupported Atlassian login method: "
                    + options.methodOr(defaultLoginMethod()));
        }
        String clientId = clientId();

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
                Map.entry("scope", DEFAULT_SCOPES),
                Map.entry("code_challenge", pkce.challenge()),
                Map.entry("code_challenge_method", "S256"),
                Map.entry("state", state),
                Map.entry("audience", "api.atlassian.com"),
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
                throw new IOException("Atlassian login returned no authorization code");
            }
            ManagedCredential credential = tokenRequest(Map.of(
                    "grant_type", "authorization_code",
                    "client_id", clientId,
                    "code", authorization.code(),
                    "code_verifier", pkce.verifier(),
                    "redirect_uri", callbackUri.toString()), null);
            return withCloudIdMetadata(credential);
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
                "client_id", clientId,
                "refresh_token", credential.getRefresh()), credential);
    }

    @Override
    public boolean revoke(String accessToken, String refreshToken) {
        // Atlassian 3LO has no user-token revocation endpoint for public clients;
        // removing the local credential is sufficient.
        return true;
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://api.atlassian.com",
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }

    /** Resolves the first accessible site's cloud id into credential metadata. */
    private ManagedCredential withCloudIdMetadata(ManagedCredential credential)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "GET",
                URI.create(RESOURCES_ENDPOINT),
                Map.of(
                        "Accept", "application/json",
                        "Authorization", "Bearer " + credential.getAccess()),
                null));
        if (!response.success()) {
            return credential; // cloud id can still be supplied per crawl via --set cloudId
        }
        JsonNode resources;
        try {
            resources = OAuthSupport.MAPPER.readTree(response.body());
        } catch (IOException malformed) {
            return credential; // metadata enrichment is best-effort
        }
        if (resources == null || !resources.isArray() || resources.isEmpty()) {
            return credential;
        }
        JsonNode first = resources.get(0);
        String cloudId = first.path("id").asText(null);
        if (cloudId == null || cloudId.isBlank()) {
            return credential;
        }
        Map<String, String> metadata = new java.util.LinkedHashMap<>(credential.getMetadata());
        metadata.put("cloudId", cloudId);
        String siteName = first.path("name").asText(null);
        if (siteName != null && !siteName.isBlank()) {
            metadata.put("cloudName", siteName);
        }
        return ManagedCredential.oauth(credential.getAccess(), credential.getRefresh(),
                credential.getExpires(), metadata);
    }

    private ManagedCredential tokenRequest(Map<String, String> fields, ManagedCredential previous)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                URI.create(TOKEN_ENDPOINT),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded",
                        "Authorization", "Basic " + basic(fields.get("client_id"))),
                OAuthSupport.form(fields)));
        OAuthSupport.requireSuccess(response, "Atlassian OAuth token request");
        JsonNode body = OAuthSupport.json(response, "Atlassian OAuth token request");
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        if (refresh == null && previous != null) {
            refresh = previous.getRefresh();
        }
        String access = OAuthSupport.requiredText(body, "access_token",
                "Atlassian OAuth token request");
        long expiresIn = OAuthSupport.requiredPositiveLong(body, "expires_in",
                "Atlassian OAuth token request");
        if (refresh == null || refresh.isBlank()) {
            throw new IOException("Atlassian OAuth did not return a refresh token; "
                    + "offline_access scope is required");
        }
        Map<String, String> metadata = new java.util.LinkedHashMap<>(
                previous == null ? Map.of() : previous.getMetadata());
        metadata.put("clientId", fields.get("client_id"));
        String scope = OAuthSupport.optionalText(body, "scope");
        if (scope != null) {
            metadata.put("scope", scope);
        }
        return ManagedCredential.oauth(access, refresh,
                OAuthSupport.expiryFromNow(expiresIn, 0L), metadata);
    }

    private String basic(String clientId) throws IOException {
        return java.util.Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            throw new IOException("Atlassian OAuth requires an OAuth 2.0 (3LO) app. "
                    + "Run `kompile auth login atlassian` and follow the prompt, or set "
                    + CLIENT_ID_ENV + " and " + CLIENT_SECRET_ENV
                    + " (developer.atlassian.com > your app > Authorization, callback "
                    + "http://127.0.0.1, add the read scopes + offline_access).");
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
            throw new IOException("Atlassian OAuth requires the 3LO app client secret. "
                    + "Run `kompile auth login atlassian` and follow the prompt, or set "
                    + CLIENT_SECRET_ENV
                    + " (developer.atlassian.com > your app > Authorization > client secret).");
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
