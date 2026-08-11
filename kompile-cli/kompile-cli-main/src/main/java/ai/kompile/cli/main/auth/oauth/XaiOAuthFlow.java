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
import java.util.List;
import java.util.Map;

/** xAI SuperGrok/X subscription OAuth device-code flow. */
final class XaiOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "xai";

    private static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    private static final String SCOPE =
            "openid profile email offline_access grok-cli:access api:access";
    private static final URI DEVICE_CODE_URL =
            URI.create("https://auth.x.ai/oauth2/device/code");
    private static final URI TOKEN_URL =
            URI.create("https://auth.x.ai/oauth2/token");
    private static final long REFRESH_SKEW_MILLIS = 5 * 60 * 1000L;
    private static final int DEFAULT_LIFETIME_SECONDS = 3600;

    private final OAuthSupport.HttpTransport http;
    private final OAuthSupport.DeviceCodePoller poller;

    XaiOAuthFlow() {
        this(OAuthSupport.defaultTransport(), new OAuthSupport.DeviceCodePoller());
    }

    XaiOAuthFlow(
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
        return "xAI (SuperGrok/X subscription)";
    }

    @Override
    public List<LoginMethod> loginMethods() {
        return List.of(new LoginMethod("device", "Device code login"));
    }

    @Override
    public String defaultLoginMethod() {
        return "device";
    }

    @Override
    public ManagedCredential login(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException {
        String method = options.methodOr(defaultLoginMethod());
        if (!"device".equals(method) && !"device-code".equals(method) && !"device_code".equals(method)) {
            throw new IOException("Unsupported xAI login method: " + method);
        }

        OAuthSupport.Response response = postForm(DEVICE_CODE_URL, Map.of(
                "client_id", CLIENT_ID,
                "scope", SCOPE,
                "referrer", "kompile"));
        OAuthSupport.requireSuccess(response, "xAI device authorization");
        JsonNode body = OAuthSupport.json(response, "xAI device authorization");
        String deviceCode = OAuthSupport.requiredText(body, "device_code", "xAI device authorization");
        String userCode = OAuthSupport.requiredText(body, "user_code", "xAI device authorization");
        String verification = OAuthSupport.optionalText(body, "verification_uri_complete");
        if (verification == null) {
            verification = OAuthSupport.requiredText(
                    body, "verification_uri", "xAI device authorization");
        }
        URI verificationUri = OAuthSupport.trustedHttpUri(
                verification, true, "xAI verification");
        int interval = OAuthSupport.optionalPositiveInt(body, "interval", 5);
        int expiresIn = Math.toIntExact(
                OAuthSupport.requiredPositiveLong(body, "expires_in", "xAI device authorization"));
        interaction.deviceCode(userCode, verificationUri, interval, expiresIn);

        return poller.poll(
                interval,
                expiresIn,
                true,
                () -> pollToken(deviceCode));
    }

    private OAuthSupport.PollResult<ManagedCredential> pollToken(String deviceCode)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = postForm(TOKEN_URL, Map.of(
                "grant_type", OAuthSupport.DEVICE_CODE_GRANT,
                "client_id", CLIENT_ID,
                "device_code", deviceCode));
        JsonNode body = OAuthSupport.json(response, "xAI device token");
        if (response.success()) {
            return OAuthSupport.PollResult.complete(
                    parseToken(body, null, "xAI device token"));
        }

        String error = OAuthSupport.optionalText(body, "error");
        if ("authorization_pending".equals(error)) {
            return OAuthSupport.PollResult.pending();
        }
        if ("slow_down".equals(error)) {
            JsonNode interval = body.get("interval");
            return OAuthSupport.PollResult.slowDown(
                    interval != null && interval.canConvertToInt() ? interval.intValue() : null);
        }
        if ("access_denied".equals(error) || "authorization_denied".equals(error)) {
            return OAuthSupport.PollResult.failed("xAI device authorization was denied");
        }
        if ("expired_token".equals(error)) {
            return OAuthSupport.PollResult.failed("xAI device code expired");
        }
        return OAuthSupport.PollResult.failed(
                "xAI device token polling failed (HTTP " + response.status() + ")"
                        + (error == null ? "" : ": " + error));
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        OAuthSupport.Response response = postForm(TOKEN_URL, Map.of(
                "grant_type", "refresh_token",
                "client_id", CLIENT_ID,
                "refresh_token", credential.getRefresh()));
        OAuthSupport.requireSuccess(response, "xAI token refresh");
        return parseToken(
                OAuthSupport.json(response, "xAI token refresh"),
                credential.getRefresh(),
                "xAI token refresh");
    }

    private OAuthSupport.Response postForm(URI uri, Map<String, String> fields)
            throws IOException, InterruptedException {
        return http.send(new OAuthSupport.Request(
                "POST",
                uri,
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(fields)));
    }

    private ManagedCredential parseToken(
            JsonNode body,
            String previousRefreshToken,
            String context) throws IOException {
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        if (refresh == null) {
            refresh = previousRefreshToken;
        }
        if (refresh == null || refresh.isBlank()) {
            throw new IOException(context + " is missing 'refresh_token'");
        }
        long expiresIn = body.has("expires_in")
                ? OAuthSupport.requiredPositiveLong(body, "expires_in", context)
                : DEFAULT_LIFETIME_SECONDS;
        return ManagedCredential.oauth(
                OAuthSupport.requiredText(body, "access_token", context),
                refresh,
                OAuthSupport.expiryFromNow(expiresIn, REFRESH_SKEW_MILLIS));
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        return RequestAuth.oauth(
                credential.getAccess(),
                "https://api.x.ai/v1",
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }
}
