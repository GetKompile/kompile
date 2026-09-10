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
 * Notion OAuth for Kompile-managed local crawls.
 *
 * <p>Authorization-code flow with Basic client authentication. Notion exchanges the
 * code for a bot integration token that does not expire, represented as a
 * non-expiring OAuth credential with empty refresh. Requires an internal
 * integration's public client id + secret via {@code KOMPILE_NOTION_CLIENT_ID} /
 * {@code KOMPILE_NOTION_CLIENT_SECRET} (same registration the app-side handler
 * uses). The workspace id is stored as credential metadata.</p>
 */
public final class NotionOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "notion";
    static final String CLIENT_ID_ENV = "KOMPILE_NOTION_CLIENT_ID";
    static final String CLIENT_SECRET_ENV = "KOMPILE_NOTION_CLIENT_SECRET";

    private static final String AUTHORIZATION_ENDPOINT =
            "https://api.notion.com/v1/oauth/authorize";
    private static final String TOKEN_ENDPOINT =
            "https://api.notion.com/v1/oauth/token";

    private final OAuthSupport.HttpTransport http;
    private final String configuredClientId;
    private final String configuredClientSecret;

    public NotionOAuthFlow() {
        this(OAuthSupport.defaultTransport(), null, null);
    }

    NotionOAuthFlow(OAuthSupport.HttpTransport http) {
        this(http, null, null);
    }

    /** Test/distribution hook: pins credentials instead of reading the environment. */
    NotionOAuthFlow(OAuthSupport.HttpTransport http, String clientId, String clientSecret) {
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
        return "Notion";
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
            throw new IOException("Unsupported Notion login method: "
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

        URI authorizeUri = OAuthSupport.uriWithQuery(AUTHORIZATION_ENDPOINT, Map.of(
                "client_id", clientId,
                "redirect_uri", callbackUri.toString(),
                "response_type", "code",
                "owner", "user",
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
                throw new IOException("Notion login returned no authorization code");
            }
            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                    "POST",
                    URI.create(TOKEN_ENDPOINT),
                    Map.of(
                            "Accept", "application/json",
                            "Content-Type", "application/json",
                            "Authorization", "Basic " + basic),
                    OAuthSupport.MAPPER.writeValueAsString(Map.of(
                            "grant_type", "authorization_code",
                            "code", authorization.code(),
                            "redirect_uri", callbackUri.toString()))));
            OAuthSupport.requireSuccess(response, "Notion OAuth token request");
            JsonNode body = OAuthSupport.json(response, "Notion OAuth token request");
            String token = OAuthSupport.requiredText(body, "access_token",
                    "Notion OAuth token request");
            Map<String, String> metadata = new LinkedHashMap<>();
            String workspaceId = OAuthSupport.optionalText(body, "workspace_id");
            if (workspaceId != null) {
                metadata.put("workspaceId", workspaceId);
            }
            JsonNode workspaceName = body.path("workspace_name");
            if (workspaceName.isTextual() && !workspaceName.textValue().isBlank()) {
                metadata.put("workspaceName", workspaceName.textValue());
            }
            // Notion integration tokens do not expire and cannot be refreshed.
            return ManagedCredential.oauth(token, "", Long.MAX_VALUE, metadata);
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential) {
        // Non-expiring integration token: nothing to refresh.
        return credential;
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://api.notion.com/v1",
                Map.of(
                        "Authorization", "Bearer " + credential.getAccess(),
                        "Notion-Version", "2022-06-28"));
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
            throw new IOException("Notion OAuth requires a public integration client id and secret. "
                    + "Run `kompile auth login notion` and follow the prompt, or set "
                    + CLIENT_ID_ENV + " and " + CLIENT_SECRET_ENV
                    + " (notion.so/my-integrations > your integration > 'OAuth Domain & URIs').");
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
            throw new IOException("Notion OAuth requires the integration client secret. "
                    + "Run `kompile auth login notion` and follow the prompt, or set "
                    + CLIENT_SECRET_ENV + " (notion.so/my-integrations > OAuth client secret).");
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
