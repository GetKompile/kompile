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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub Copilot OAuth device flow, including GitHub Enterprise hosts. */
public final class GitHubCopilotOAuthFlow implements OAuthProviderFlow {
    static final String PROVIDER_ID = "github-copilot";

    private static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
    private static final String USER_AGENT = "GitHubCopilotChat/0.35.0";
    private static final String EDITOR_VERSION = "vscode/1.107.0";
    private static final String PLUGIN_VERSION = "copilot-chat/0.35.0";
    private static final String API_VERSION = "2026-06-01";
    private static final Pattern PROXY_ENDPOINT = Pattern.compile("(?:^|;)proxy-ep=([^;]+)");

    private final OAuthSupport.HttpTransport http;
    private final OAuthSupport.DeviceCodePoller poller;

    public GitHubCopilotOAuthFlow() {
        this(OAuthSupport.defaultTransport(), new OAuthSupport.DeviceCodePoller());
    }

    GitHubCopilotOAuthFlow(
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
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public String displayName() {
        return "GitHub Copilot";
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
            throw new IOException("Unsupported GitHub Copilot login method: " + method);
        }

        String enterpriseDomain = normalizeDomain(options.enterpriseDomain());
        String domain = enterpriseDomain == null ? "github.com" : enterpriseDomain;
        Endpoints endpoints = endpoints(domain);

        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                endpoints.deviceCode(),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded",
                        "User-Agent", USER_AGENT),
                OAuthSupport.form(Map.of(
                        "client_id", CLIENT_ID,
                        "scope", "read:user"))));
        OAuthSupport.requireSuccess(response, "GitHub device authorization");
        JsonNode body = OAuthSupport.json(response, "GitHub device authorization");
        String deviceCode = OAuthSupport.requiredText(body, "device_code", "GitHub device authorization");
        String userCode = OAuthSupport.requiredText(body, "user_code", "GitHub device authorization");
        URI verificationUri = OAuthSupport.trustedHttpUri(
                OAuthSupport.requiredText(body, "verification_uri", "GitHub device authorization"),
                false,
                "GitHub verification");
        int interval = OAuthSupport.optionalPositiveInt(body, "interval", 5);
        int expiresIn = Math.toIntExact(
                OAuthSupport.requiredPositiveLong(body, "expires_in", "GitHub device authorization"));

        interaction.deviceCode(userCode, verificationUri, interval, expiresIn);
        String githubToken = poller.poll(
                interval,
                expiresIn,
                true,
                () -> pollForGitHubToken(endpoints, deviceCode));
        interaction.info("Exchanging the GitHub token for a Copilot session token...");
        return exchangeCopilotToken(githubToken, enterpriseDomain);
    }

    private OAuthSupport.PollResult<String> pollForGitHubToken(
            Endpoints endpoints,
            String deviceCode) throws IOException, InterruptedException {
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST",
                endpoints.accessToken(),
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded",
                        "User-Agent", USER_AGENT),
                OAuthSupport.form(Map.of(
                        "client_id", CLIENT_ID,
                        "device_code", deviceCode,
                        "grant_type", OAuthSupport.DEVICE_CODE_GRANT))));
        JsonNode body = OAuthSupport.json(response, "GitHub device token");
        String access = OAuthSupport.optionalText(body, "access_token");
        if (response.success() && access != null) {
            return OAuthSupport.PollResult.complete(access);
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
        String description = OAuthSupport.optionalText(body, "error_description");
        return OAuthSupport.PollResult.failed(
                "GitHub device flow failed"
                        + (error == null ? "" : ": " + error)
                        + (description == null ? "" : ": " + description));
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential)
            throws IOException, InterruptedException {
        String enterpriseDomain = normalizeDomain(credential.getMetadata("enterpriseDomain"));
        return exchangeCopilotToken(credential.getRefresh(), enterpriseDomain);
    }

    private ManagedCredential exchangeCopilotToken(String githubToken, String enterpriseDomain)
            throws IOException, InterruptedException {
        String domain = enterpriseDomain == null ? "github.com" : enterpriseDomain;
        Endpoints endpoints = endpoints(domain);
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "GET",
                endpoints.copilotToken(),
                copilotHeaders("Bearer " + githubToken),
                null));
        OAuthSupport.requireSuccess(response, "GitHub Copilot token exchange");
        JsonNode body = OAuthSupport.json(response, "GitHub Copilot token exchange");
        String token = OAuthSupport.requiredText(body, "token", "GitHub Copilot token exchange");
        long expiresAtSeconds = OAuthSupport.requiredPositiveLong(
                body, "expires_at", "GitHub Copilot token exchange");
        long now = System.currentTimeMillis();
        if (expiresAtSeconds > Long.MAX_VALUE / 1000L || expiresAtSeconds * 1000L <= now) {
            throw new IOException("GitHub Copilot returned an invalid or expired token expiry");
        }
        long expires = expiresAtSeconds * 1000L;
        Map<String, String> metadata = new LinkedHashMap<>();
        if (enterpriseDomain != null) {
            metadata.put("enterpriseDomain", enterpriseDomain);
        }
        return ManagedCredential.oauth(token, githubToken, expires, metadata);
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) throws IOException {
        String enterpriseDomain = normalizeDomain(credential.getMetadata("enterpriseDomain"));
        String baseUrl = copilotBaseUrl(credential.getAccess(), enterpriseDomain);
        return RequestAuth.oauth(
                credential.getAccess(),
                baseUrl,
                copilotHeaders("Bearer " + credential.getAccess()));
    }

    private Map<String, String> copilotHeaders(String authorization) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", authorization);
        headers.put("User-Agent", USER_AGENT);
        headers.put("Editor-Version", EDITOR_VERSION);
        headers.put("Editor-Plugin-Version", PLUGIN_VERSION);
        headers.put("Copilot-Integration-Id", "vscode-chat");
        headers.put("X-GitHub-Api-Version", API_VERSION);
        return headers;
    }

    private String copilotBaseUrl(String token, String enterpriseDomain) throws IOException {
        Matcher matcher = PROXY_ENDPOINT.matcher(token);
        if (matcher.find()) {
            String apiHost = matcher.group(1).replaceFirst("^proxy\\.", "api.");
            return OAuthSupport.trustedHttpUri(
                    "https://" + apiHost, true, "GitHub Copilot API").toString();
        }
        if (enterpriseDomain != null) {
            return "https://copilot-api." + enterpriseDomain;
        }
        return "https://api.individual.githubcopilot.com";
    }

    private String normalizeDomain(String input) throws IOException {
        if (input == null || input.isBlank()) {
            return null;
        }
        String raw = input.trim();
        URI uri = OAuthSupport.trustedHttpUri(
                raw.contains("://") ? raw : "https://" + raw,
                true,
                "GitHub Enterprise");
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Invalid GitHub Enterprise domain");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private Endpoints endpoints(String domain) {
        return new Endpoints(
                URI.create("https://" + domain + "/login/device/code"),
                URI.create("https://" + domain + "/login/oauth/access_token"),
                URI.create("https://api." + domain + "/copilot_internal/v2/token"));
    }

    private record Endpoints(URI deviceCode, URI accessToken, URI copilotToken) {
    }
}
