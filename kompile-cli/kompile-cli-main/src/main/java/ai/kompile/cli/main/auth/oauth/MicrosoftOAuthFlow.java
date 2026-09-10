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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Entra ID OAuth for Kompile-managed local crawls (OneDrive, IMAP XOAUTH2).
 *
 * <p>Browser PKCE plus RFC 8628 device-code login (public clients carry no secret;
 * Azure honours PKCE for both). The tenant defaults to {@code common} and can be
 * narrowed with {@code KOMPILE_MICROSOFT_TENANT_ID} or per-login via the enterprise
 * domain option. Scopes mirror the app-side {@code MicrosoftOAuthHandler} defaults
 * (Files.Read, IMAP access, offline_access) so a locally stored credential grants the
 * same read surface as a server-side connection. Supply the Azure app registration's
 * client id with {@code KOMPILE_MICROSOFT_CLIENT_ID}.</p>
 */
public final class MicrosoftOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "microsoft";
    static final String CLIENT_ID_ENV = "KOMPILE_MICROSOFT_CLIENT_ID";
    static final String TENANT_ID_ENV = "KOMPILE_MICROSOFT_TENANT_ID";

    private static final String AUTHORIZATION_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
    private static final String TOKEN_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String DEVICE_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/devicecode";
    static final String DEFAULT_TENANT = "common";
    static final String DEFAULT_SCOPES =
            "Files.Read User.Read https://outlook.office365.com/IMAP.AccessAsUser.All offline_access";

    private static final String REDIRECT_PATH = "/oauth/callback";

    private final OAuthSupport.HttpTransport http;
    private final OAuthSupport.DeviceCodePoller poller;
    private final String configuredClientId;

    public MicrosoftOAuthFlow() {
        this(OAuthSupport.defaultTransport(), new OAuthSupport.DeviceCodePoller(), null);
    }

    MicrosoftOAuthFlow(OAuthSupport.HttpTransport http, OAuthSupport.DeviceCodePoller poller) {
        this(http, poller, null);
    }

    /** Test/distribution hook: pins the client id instead of reading the environment. */
    MicrosoftOAuthFlow(
            OAuthSupport.HttpTransport http,
            OAuthSupport.DeviceCodePoller poller,
            String configuredClientId) {
        this.http = http;
        this.poller = poller;
        this.configuredClientId = configuredClientId;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Microsoft (OneDrive / Outlook IMAP)";
    }

    @Override
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public List<LoginMethod> loginMethods() {
        return List.of(
                new LoginMethod("browser", "Browser login"),
                new LoginMethod("device", "Device code login"));
    }

    @Override
    public String defaultLoginMethod() {
        return "browser";
    }

    @Override
    public ManagedCredential login(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException {
        String tenant = tenant(options.enterpriseDomain());
        return switch (options.methodOr(defaultLoginMethod())) {
            case "browser" -> loginBrowser(tenant, options.manual(), interaction);
            case "device", "device-code", "device_code" -> loginDevice(tenant, interaction);
            default -> throw new IOException("Unsupported Microsoft login method: "
                    + options.methodOr(defaultLoginMethod()));
        };
    }

    private ManagedCredential loginBrowser(String tenant, boolean requestedManual,
            Interaction interaction) throws IOException, InterruptedException {
        String clientId = clientId();
        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = OAuthSupport.randomState();
        OAuthSupport.CallbackServer callback = null;
        boolean manual = requestedManual;
        URI callbackUri;
        if (manual) {
            callbackUri = URI.create("http://127.0.0.1:1456" + REDIRECT_PATH);
        } else {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        "127.0.0.1",
                        OAuthSupport.callbackHost(),
                        0,
                        REDIRECT_PATH,
                        state);
                callbackUri = callback.redirectUri();
            } catch (IOException e) {
                manual = true;
                callbackUri = URI.create("http://127.0.0.1:1456" + REDIRECT_PATH);
                interaction.info("Could not bind a callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(
                AUTHORIZATION_TEMPLATE.formatted(tenant), Map.ofEntries(
                        Map.entry("client_id", clientId),
                        Map.entry("redirect_uri", callbackUri.toString()),
                        Map.entry("response_type", "code"),
                        Map.entry("scope", DEFAULT_SCOPES),
                        Map.entry("code_challenge", pkce.challenge()),
                        Map.entry("code_challenge_method", "S256"),
                        Map.entry("state", state)));
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
                throw new IOException("Microsoft login returned no authorization code");
            }
            return tokenRequest(tenant, Map.of(
                    "grant_type", "authorization_code",
                    "client_id", clientId,
                    "code", authorization.code(),
                    "code_verifier", pkce.verifier(),
                    "redirect_uri", callbackUri.toString()), null);
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    private ManagedCredential loginDevice(String tenant, Interaction interaction)
            throws IOException, InterruptedException {
        String clientId = clientId();
        OAuthSupport.Response response = formPost(
                DEVICE_TEMPLATE.formatted(tenant),
                Map.of("client_id", clientId, "scope", DEFAULT_SCOPES));
        OAuthSupport.requireSuccess(response, "Microsoft device authorization");
        JsonNode body = OAuthSupport.json(response, "Microsoft device authorization");
        String deviceCode = OAuthSupport.requiredText(body, "device_code",
                "Microsoft device authorization");
        String userCode = OAuthSupport.requiredText(body, "user_code",
                "Microsoft device authorization");
        URI verificationUri = OAuthSupport.trustedHttpUri(
                OAuthSupport.requiredText(body, "verification_uri",
                        "Microsoft device authorization"),
                false,
                "Microsoft verification");
        int interval = OAuthSupport.optionalPositiveInt(body, "interval", 5);
        int expiresIn = Math.toIntExact(
                OAuthSupport.requiredPositiveLong(body, "expires_in",
                        "Microsoft device authorization"));
        interaction.deviceCode(userCode, verificationUri, interval, expiresIn);

        ManagedCredential credential = poller.poll(interval, expiresIn, false,
                () -> pollDeviceToken(tenant, clientId, deviceCode));
        return withTenantMetadata(credential, tenant);
    }

    private OAuthSupport.PollResult<ManagedCredential> pollDeviceToken(
            String tenant, String clientId, String deviceCode)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = formPost(
                TOKEN_TEMPLATE.formatted(tenant), Map.of(
                        "grant_type", OAuthSupport.DEVICE_CODE_GRANT,
                        "client_id", clientId,
                        "device_code", deviceCode));
        if (response.success()) {
            return OAuthSupport.PollResult.complete(parseToken(
                    OAuthSupport.json(response, "Microsoft device token"),
                    "Microsoft device token", clientId, null));
        }
        JsonNode body = OAuthSupport.json(response, "Microsoft device token");
        String error = OAuthSupport.optionalText(body, "error");
        return switch (error == null ? "" : error) {
            case "authorization_pending" -> OAuthSupport.PollResult.pending();
            case "slow_down" -> OAuthSupport.PollResult.slowDown(null);
            case "expired_token" -> OAuthSupport.PollResult.failed(
                    "Microsoft device authorization expired");
            case "access_denied" -> OAuthSupport.PollResult.failed(
                    "Microsoft device authorization was denied");
            default -> OAuthSupport.PollResult.failed(
                    "Microsoft device token request failed (HTTP " + response.status() + ")"
                            + (error == null ? "" : ": " + error));
        };
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        String tenant = credential.getMetadata("tenant");
        if (tenant == null || tenant.isBlank()) {
            tenant = tenant(null);
        }
        String clientId = credential.getMetadata("clientId");
        if (clientId == null || clientId.isBlank()) {
            clientId = clientId();
        }
        return tokenRequest(tenant, Map.of(
                "grant_type", "refresh_token",
                "client_id", clientId,
                "refresh_token", credential.getRefresh()), credential);
    }

    @Override
    public boolean revoke(String accessToken, String refreshToken) {
        // Entra ID has no per-token revocation endpoint compatible with public clients;
        // removing the local credential is sufficient.
        return true;
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://graph.microsoft.com",
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }

    private ManagedCredential tokenRequest(
            String tenant, Map<String, String> fields, ManagedCredential previous)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = formPost(TOKEN_TEMPLATE.formatted(tenant), fields);
        OAuthSupport.requireSuccess(response, "Microsoft OAuth token request");
        ManagedCredential credential = parseToken(
                OAuthSupport.json(response, "Microsoft OAuth token request"),
                "Microsoft OAuth token request", fields.get("client_id"), previous);
        return withTenantMetadata(credential, tenant);
    }

    private ManagedCredential parseToken(
            JsonNode body, String context, String clientId, ManagedCredential previous)
            throws IOException {
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        if (refresh == null && previous != null) {
            refresh = previous.getRefresh();
        }
        if (refresh == null || refresh.isBlank()) {
            throw new IOException(context + " is missing 'refresh_token'");
        }
        Map<String, String> metadata = OAuthCredentialIdentity.tokenMetadata(PROVIDER_ID, body, previous);
        metadata.put("clientId", clientId);
        return ManagedCredential.oauth(
                OAuthSupport.requiredText(body, "access_token", context),
                refresh,
                OAuthSupport.expiryFromNow(
                        OAuthSupport.requiredPositiveLong(body, "expires_in", context),
                        0L),
                metadata);
    }

    private ManagedCredential withTenantMetadata(ManagedCredential credential, String tenant) {
        Map<String, String> metadata = new LinkedHashMap<>(credential.getMetadata());
        metadata.put("tenant", tenant);
        return ManagedCredential.oauth(credential.getAccess(), credential.getRefresh(),
                credential.getExpires(), metadata);
    }

    private OAuthSupport.Response formPost(String url, Map<String, String> fields)
            throws IOException, InterruptedException {
        return http.send(new OAuthSupport.Request(
                "POST",
                URI.create(url),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(fields)));
    }

    private String tenant(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String stored = storedClientField("tenantId");
        if (stored != null) {
            return stored;
        }
        String configured = System.getenv(TENANT_ID_ENV);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return DEFAULT_TENANT;
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
            throw new IOException("Microsoft OAuth requires an Azure app registration client id. "
                    + "Run `kompile auth login microsoft` and follow the prompt, or set "
                    + CLIENT_ID_ENV + " (Azure portal > App registrations > New registration, "
                    + "'Public client/native', enable 'Allow public client flows', "
                    + "mobile/desktop redirect http://127.0.0.1).");
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
