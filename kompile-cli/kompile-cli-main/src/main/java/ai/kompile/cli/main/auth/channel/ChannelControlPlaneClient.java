/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelBrowserLoginView;
import ai.kompile.channel.api.ChannelConnectionUpdate;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.channel.api.ChannelTestResult;
import ai.kompile.channel.api.TelegramDiagnosticsView;
import ai.kompile.channel.api.TelegramPairingApprovalRequest;
import ai.kompile.channel.api.TelegramPairingStartView;
import ai.kompile.channel.api.TelegramPairingView;
import ai.kompile.channel.api.TelegramWebhookInfoView;
import ai.kompile.cli.common.auth.IntegrationAdminCredential;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Typed authenticated client for the admin-owned channel integration control plane. */
public final class ChannelControlPlaneClient {

    static final String ROOT = "/api/channel-integrations";

    private final KompileHttpClient routes;
    private final HttpClient transport;
    private final ObjectMapper mapper;
    private final String adminToken;

    ChannelControlPlaneClient(KompileHttpClient routes) throws IOException {
        this(routes, IntegrationAdminCredential.loadFor(URI.create(routes.urlFor(ROOT))));
    }

    /** Routed by default; an explicit URL pins the admin control plane. */
    public ChannelControlPlaneClient(String baseUrl) throws IOException {
        this(baseUrl == null || baseUrl.isBlank()
                ? KompileHttpClient.routed()
                : new KompileHttpClient(baseUrl));
    }

    ChannelControlPlaneClient(KompileHttpClient routes, String adminToken) {
        this.routes = routes;
        this.adminToken = adminToken;
        this.mapper = JsonUtils.standardMapper();
        this.transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<ChannelProviderDescriptor> providers() throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", ROOT + "/providers", null),
                new TypeReference<List<ChannelProviderDescriptor>>() { });
    }

    public ChannelBrowserLoginView browserLogin() throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", ROOT + "/browser-sessions", null),
                ChannelBrowserLoginView.class);
    }

    public ChannelProviderAuthView providerAuth(String providerId)
            throws IOException, InterruptedException {
        String normalized = provider(providerId);
        return mapper.readValue(exchange("GET", ROOT + "/providers/" + normalized + "/auth", null),
                ChannelProviderAuthView.class);
    }

    public JsonNode initiateOAuthLogin(String providerId)
            throws IOException, InterruptedException {
        return mapper.readTree(exchange("GET", "/api/oauth/" + provider(providerId)
                + "/authorize?purpose=channel", null));
    }

    List<ChannelEngineDescriptor> engines() throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", ROOT + "/engines", null),
                new TypeReference<List<ChannelEngineDescriptor>>() { });
    }

    public List<ChannelConnectionView> connections() throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", ROOT + "/connections", null),
                new TypeReference<List<ChannelConnectionView>>() { });
    }

    public ChannelConnectionView connection(String name) throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", connectionPath(name), null), ChannelConnectionView.class);
    }

    /** Runtime credential handoff for a single named connection (admin-token gated server-side). */
    public ChannelCredentialView credential(String name) throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", connectionPath(name) + "/credential", null),
                ChannelCredentialView.class);
    }

    ChannelConnectionView create(ChannelConnectionRequest request) throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", ROOT + "/connections", request), ChannelConnectionView.class);
    }

    ChannelConnectionView update(String name, ChannelConnectionUpdate update)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("PUT", connectionPath(name), update), ChannelConnectionView.class);
    }

    ChannelConnectionView enable(String name) throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", connectionPath(name) + "/enable", null),
                ChannelConnectionView.class);
    }

    ChannelConnectionView disable(String name) throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", connectionPath(name) + "/disable", null),
                ChannelConnectionView.class);
    }

    ChannelTestResult test(String name, ChannelTestRequest request)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", connectionPath(name) + "/test", request),
                ChannelTestResult.class);
    }

    public ChannelTestResult deliver(String name, ChannelTestRequest request)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", connectionPath(name) + "/deliver", request),
                ChannelTestResult.class);
    }

    TelegramPairingStartView startTelegramPairing(String name)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", telegramPath(name) + "/pairings", null),
                TelegramPairingStartView.class);
    }

    TelegramPairingView telegramPairing(String name, String pairingId)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", pairingPath(name, pairingId), null),
                TelegramPairingView.class);
    }

    ChannelConnectionView approveTelegramPairing(
            String name, String pairingId, long expectedChatId)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("POST", pairingPath(name, pairingId) + "/approve",
                        new TelegramPairingApprovalRequest(expectedChatId)),
                ChannelConnectionView.class);
    }

    void cancelTelegramPairing(String name, String pairingId)
            throws IOException, InterruptedException {
        exchange("DELETE", pairingPath(name, pairingId), null);
    }

    TelegramDiagnosticsView telegramDiagnostics(String name)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", telegramPath(name) + "/diagnostics", null),
                TelegramDiagnosticsView.class);
    }

    TelegramWebhookInfoView telegramWebhook(String name)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("GET", telegramPath(name) + "/webhook", null),
                TelegramWebhookInfoView.class);
    }

    TelegramWebhookInfoView deleteTelegramWebhook(String name, boolean dropPendingUpdates)
            throws IOException, InterruptedException {
        return mapper.readValue(exchange("DELETE", telegramPath(name)
                        + "/webhook?dropPendingUpdates=" + dropPendingUpdates, null),
                TelegramWebhookInfoView.class);
    }

    void disconnect(String name) throws IOException, InterruptedException {
        exchange("DELETE", connectionPath(name), null);
    }

    private String exchange(String method, String path, Object body)
            throws IOException, InterruptedException {
        URI uri = URI.create(routes.urlFor(path));
        requireSecure(uri);
        if (adminToken == null || adminToken.isBlank()) {
            throw new IllegalStateException(
                    "Channel administration token is unavailable. Start the admin process locally or set "
                            + ChannelControlHeaders.TOKEN_ENVIRONMENT + ".");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header(ChannelControlHeaders.TOKEN_HEADER, adminToken);
        if (isMutation(method)) {
            request.header(ChannelControlHeaders.REQUEST_HEADER, "1");
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }

        HttpResponse<String> response = transport.send(
                request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // A mutation body can contain channel secrets or outbound message text. Do not
            // include any server response body in an exception for such requests: an upstream
            // proxy/provider may echo the submitted payload, and callers print this message.
            String responseBody = body == null && response.body() != null
                    ? response.body().trim() : "";
            if (responseBody.length() > 500) {
                responseBody = responseBody.substring(0, 500);
            }
            throw new IOException("Channel API returned HTTP " + response.statusCode()
                    + (responseBody.isEmpty() ? "" : ": " + responseBody));
        }
        return response.body() == null ? "" : response.body();
    }

    private String connectionPath(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")) {
            throw new IllegalArgumentException("Invalid channel connection name: " + name);
        }
        return ROOT + "/connections/" + name.toLowerCase(Locale.ROOT);
    }

    private static String provider(String providerId) {
        if (providerId == null || !providerId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException("Invalid channel provider: " + providerId);
        }
        return providerId.toLowerCase(Locale.ROOT);
    }

    private String telegramPath(String name) {
        return connectionPath(name) + "/telegram";
    }

    private String pairingPath(String name, String pairingId) {
        try {
            return telegramPath(name) + "/pairings/" + java.util.UUID.fromString(pairingId);
        } catch (RuntimeException invalidPairingId) {
            throw new IllegalArgumentException("Invalid Telegram pairing id: " + pairingId);
        }
    }

    /** The admin control plane base URL, for display in setup/summary output. */
    public String adminUrl() {
        return routes.urlFor(ROOT);
    }

    /** Channel requests may use plaintext HTTP only over an exact loopback host. */
    void requireSecureControlPlane() {
        requireSecure(URI.create(routes.urlFor(ROOT)));
    }

    private static void requireSecure(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme()) || isLoopback(uri.getHost())) {
            return;
        }
        throw new IllegalStateException(
                "Refusing channel administration over non-loopback HTTP: "
                        + uri.getScheme() + "://" + uri.getAuthority());
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }
}
