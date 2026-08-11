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

/** Radius gateway OAuth with browser PKCE and RFC 8628 device modes. */
final class RadiusOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "radius";
    static final String DEFAULT_GATEWAY = "https://radius.pi.dev";

    private static final String CLIENT_ID = "pi-gateway";
    private static final String SCOPE = "gateway offline_access";
    private static final String REDIRECT_URI = "http://127.0.0.1:1456/oauth/callback";
    private static final long REFRESH_SKEW_MILLIS = 60_000L;

    private final OAuthSupport.HttpTransport http;
    private final OAuthSupport.DeviceCodePoller poller;

    RadiusOAuthFlow() {
        this(OAuthSupport.defaultTransport(), new OAuthSupport.DeviceCodePoller());
    }

    RadiusOAuthFlow(
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
    public String displayName() {
        return "Radius";
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
        String gateway = normalizeGateway(options.gateway());
        String method = options.methodOr(defaultLoginMethod());
        return switch (method) {
            case "browser" -> loginBrowser(gateway, options.manual(), interaction);
            case "device", "device-code", "device_code" -> loginDevice(gateway, interaction);
            default -> throw new IOException("Unsupported Radius login method: " + method);
        };
    }

    private ManagedCredential loginBrowser(
            String gateway,
            boolean requestedManual,
            Interaction interaction) throws IOException, InterruptedException {
        OAuthSupport.Response discoveryResponse = http.send(new OAuthSupport.Request(
                "GET",
                URI.create(gateway + "/v1/oauth"),
                Map.of("Accept", "application/json"),
                null));
        OAuthSupport.requireSuccess(discoveryResponse, "Radius OAuth discovery");
        JsonNode discovery = OAuthSupport.json(discoveryResponse, "Radius OAuth discovery");
        URI authorizationEndpoint = OAuthSupport.trustedHttpUri(
                OAuthSupport.requiredText(
                        discovery, "authorizationEndpoint", "Radius OAuth discovery"),
                false,
                "Radius authorization");

        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = OAuthSupport.randomState();
        OAuthSupport.CallbackServer callback = null;
        boolean manual = requestedManual;
        if (!manual) {
            try {
                callback = OAuthSupport.CallbackServer.start(
                        "127.0.0.1", "127.0.0.1", 1456, "/oauth/callback", state);
            } catch (IOException e) {
                manual = true;
                interaction.info("Could not bind the Radius callback port; switching to manual code entry.");
            }
        }

        URI authorizeUri = OAuthSupport.uriWithQuery(authorizationEndpoint.toString(), Map.ofEntries(
                Map.entry("response_type", "code"),
                Map.entry("client_id", CLIENT_ID),
                Map.entry("redirect_uri", REDIRECT_URI),
                Map.entry("scope", SCOPE),
                Map.entry("code_challenge", pkce.challenge()),
                Map.entry("code_challenge_method", "S256"),
                Map.entry("handoff", "url"),
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
                throw new IOException("Radius login returned no authorization code");
            }
            return requestToken(gateway, Map.of(
                    "grant_type", "authorization_code",
                    "client_id", CLIENT_ID,
                    "redirect_uri", REDIRECT_URI,
                    "code", authorization.code(),
                    "code_verifier", pkce.verifier()), null);
        } finally {
            if (callback != null) {
                callback.close();
            }
        }
    }

    private ManagedCredential loginDevice(String gateway, Interaction interaction)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = postForm(
                gateway + "/v1/oauth/device",
                Map.of("client_id", CLIENT_ID, "scope", SCOPE));
        OAuthSupport.requireSuccess(response, "Radius device authorization");
        JsonNode body = OAuthSupport.json(response, "Radius device authorization");
        String deviceCode = OAuthSupport.requiredText(body, "device_code", "Radius device authorization");
        String userCode = OAuthSupport.requiredText(body, "user_code", "Radius device authorization");
        URI verificationUri = OAuthSupport.trustedHttpUri(
                OAuthSupport.requiredText(
                        body, "verification_uri", "Radius device authorization"),
                false,
                "Radius verification");
        int interval = OAuthSupport.optionalPositiveInt(body, "interval", 5);
        int expiresIn = Math.toIntExact(
                OAuthSupport.requiredPositiveLong(body, "expires_in", "Radius device authorization"));
        interaction.deviceCode(userCode, verificationUri, interval, expiresIn);

        return poller.poll(
                interval,
                expiresIn,
                false,
                () -> pollDeviceToken(gateway, deviceCode));
    }

    private OAuthSupport.PollResult<ManagedCredential> pollDeviceToken(
            String gateway,
            String deviceCode) throws IOException, InterruptedException {
        OAuthSupport.Response response = postForm(
                gateway + "/v1/oauth/token",
                Map.of(
                        "grant_type", OAuthSupport.DEVICE_CODE_GRANT,
                        "client_id", CLIENT_ID,
                        "device_code", deviceCode));
        if (response.success()) {
            return OAuthSupport.PollResult.complete(
                    parseToken(
                            OAuthSupport.json(response, "Radius device token"),
                            gateway,
                            "Radius device token",
                            null));
        }

        JsonNode body = OAuthSupport.json(response, "Radius device token");
        String error = OAuthSupport.optionalText(body, "error");
        return switch (error == null ? "" : error) {
            case "authorization_pending" -> OAuthSupport.PollResult.pending();
            case "slow_down" -> OAuthSupport.PollResult.slowDown(null);
            case "expired_token" -> OAuthSupport.PollResult.failed("Radius device authorization expired");
            case "access_denied" -> OAuthSupport.PollResult.failed("Radius device authorization was denied");
            default -> OAuthSupport.PollResult.failed(
                    "Radius device token request failed (HTTP " + response.status() + ")"
                            + (error == null ? "" : ": " + error));
        };
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        String gateway = normalizeGateway(credential.getMetadata("gateway"));
        return requestToken(gateway, Map.of(
                "grant_type", "refresh_token",
                "client_id", CLIENT_ID,
                "refresh_token", credential.getRefresh()), credential.getRefresh());
    }

    private ManagedCredential requestToken(
            String gateway,
            Map<String, String> fields,
            String previousRefreshToken) throws IOException, InterruptedException {
        OAuthSupport.Response response = postForm(gateway + "/v1/oauth/token", fields);
        OAuthSupport.requireSuccess(response, "Radius OAuth token request");
        return parseToken(
                OAuthSupport.json(response, "Radius OAuth token request"),
                gateway,
                "Radius OAuth token request",
                previousRefreshToken);
    }

    private OAuthSupport.Response postForm(String url, Map<String, String> fields)
            throws IOException, InterruptedException {
        return http.send(new OAuthSupport.Request(
                "POST",
                URI.create(url),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(fields)));
    }

    private ManagedCredential parseToken(
            JsonNode body,
            String gateway,
            String context,
            String previousRefreshToken) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("gateway", gateway);
        String scope = OAuthSupport.optionalText(body, "scope");
        if (scope != null) {
            metadata.put("scope", scope);
        }
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
                        REFRESH_SKEW_MILLIS),
                metadata);
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) throws IOException {
        String gateway = normalizeGateway(credential.getMetadata("gateway"));
        return RequestAuth.oauth(
                credential.getAccess(),
                gateway,
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }

    private String normalizeGateway(String raw) throws IOException {
        String value = raw == null || raw.isBlank() ? DEFAULT_GATEWAY : raw.trim();
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        return OAuthSupport.normalizeBaseUrl(value, "Radius gateway");
    }
}
