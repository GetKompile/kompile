/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.source;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.cli.common.auth.IntegrationAdminCredential;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Authenticated JSON client for OAuth and note/source-sync control planes. */
final class SourceControlPlaneClient {

    private final KompileHttpClient routes;
    private final HttpClient transport;
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final String adminToken;

    SourceControlPlaneClient(KompileHttpClient routes) throws IOException {
        this(routes, IntegrationAdminCredential.loadFor(
                URI.create(routes.urlFor("/api/sync/connections"))));
    }

    SourceControlPlaneClient(KompileHttpClient routes, String adminToken) {
        this.routes = routes;
        this.adminToken = adminToken;
        this.transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    JsonNode oauthProviders() throws Exception { return get("/api/oauth/providers"); }
    JsonNode sourceTypes() throws Exception { return get("/api/unified-crawl/source-types"); }
    JsonNode factSheet(long factSheetId) throws Exception {
        return get("/api/fact-sheets/" + positive(factSheetId, "factSheetId"));
    }
    JsonNode browserLogin() throws Exception {
        return mutate("POST", "/api/channel-integrations/browser-sessions", null);
    }
    JsonNode oauthConnections() throws Exception { return get("/api/oauth/connections"); }
    JsonNode oauthSettings(String provider) throws Exception {
        return get(provider == null
                ? "/api/oauth/settings"
                : "/api/oauth/settings/" + provider(provider));
    }
    JsonNode oauthSetupInfo() throws Exception { return get("/api/oauth/settings/setup-info"); }
    JsonNode saveOAuthSettings(String provider, Map<String, Object> settings) throws Exception {
        return mutate("POST", "/api/oauth/settings/" + provider(provider), settings);
    }
    JsonNode validateOAuthSettings(String provider) throws Exception {
        return get("/api/oauth/settings/" + provider(provider) + "/validate");
    }
    JsonNode deleteOAuthSettings(String provider) throws Exception {
        return mutate("DELETE", "/api/oauth/settings/" + provider(provider), null);
    }
    JsonNode oauthStatus(String provider) throws Exception {
        return get("/api/oauth/" + provider(provider) + "/status");
    }
    JsonNode authorize(String provider) throws Exception {
        return get("/api/oauth/" + provider(provider) + "/authorize");
    }
    JsonNode refresh(String provider) throws Exception {
        return mutate("POST", "/api/oauth/" + provider(provider) + "/refresh", null);
    }
    JsonNode oauthHealth(String provider) throws Exception {
        return get("/api/oauth/" + provider(provider) + "/health");
    }

    /** Runtime OAuth credential handoff for local crawls (admin-token gated server-side). */
    JsonNode oauthCredential(String provider) throws Exception {
        return get("/api/oauth/" + provider(provider) + "/credential");
    }
    JsonNode disconnectOAuth(String provider) throws Exception {
        return mutate("DELETE", "/api/oauth/" + provider(provider), null);
    }
    JsonNode startSourceCrawl(Map<String, Object> request) throws Exception {
        return mutate("POST", "/api/unified-crawl/start", request);
    }

    JsonNode syncConnections(long factSheetId) throws Exception {
        return get("/api/sync/connections?factSheetId=" + positive(factSheetId, "factSheetId"));
    }
    JsonNode syncConnection(long id) throws Exception {
        return get("/api/sync/connections/" + positive(id, "connection id"));
    }
    JsonNode createSync(Map<String, Object> request) throws Exception {
        return mutate("POST", "/api/sync/connections", request);
    }
    JsonNode updateSync(long id, Map<String, Object> request) throws Exception {
        return mutate("PUT", "/api/sync/connections/" + positive(id, "connection id"), request);
    }
    JsonNode enableSync(long id) throws Exception { return action(id, "enable"); }
    JsonNode disableSync(long id) throws Exception { return action(id, "disable"); }
    JsonNode testSync(long id) throws Exception { return action(id, "test-auth"); }
    JsonNode triggerSync(long id) throws Exception { return action(id, "trigger"); }
    JsonNode pullSync(long id) throws Exception { return action(id, "pull"); }
    void deleteSync(long id) throws Exception {
        exchange("DELETE", "/api/sync/connections/" + positive(id, "connection id"), null);
    }

    private JsonNode action(long id, String action) throws Exception {
        return mutate("POST", "/api/sync/connections/" + positive(id, "connection id")
                + "/" + action, Map.of());
    }

    private JsonNode get(String path) throws Exception {
        return parse(exchange("GET", path, null));
    }

    private JsonNode mutate(String method, String path, Object body) throws Exception {
        return parse(exchange(method, path, body));
    }

    private JsonNode parse(String body) throws IOException {
        return body == null || body.isBlank() ? mapper.nullNode() : mapper.readTree(body);
    }

    private String exchange(String method, String path, Object body) throws Exception {
        URI uri = URI.create(routes.urlFor(path));
        requireSecure(uri);
        if (adminToken == null || adminToken.isBlank()) {
            throw new IllegalStateException("Integration administration token is unavailable. "
                    + "Start the project locally or set " + ChannelControlHeaders.TOKEN_ENVIRONMENT + ".");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/json")
                .header(ChannelControlHeaders.TOKEN_HEADER, adminToken);
        if (!"GET".equals(method)) {
            request.header(ChannelControlHeaders.REQUEST_HEADER, "1");
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)));
        }
        HttpResponse<String> response = transport.send(
                request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = response.body() == null ? "" : response.body().trim();
            if (error.length() > 500) error = error.substring(0, 500);
            throw new IOException("Source integration API returned HTTP " + response.statusCode()
                    + (error.isBlank() ? "" : ": " + error));
        }
        return response.body();
    }

    private static String provider(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException("Invalid source provider: " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static long positive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    private static void requireSecure(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) {
            throw new IllegalStateException("Refusing source administration over remote plaintext HTTP");
        }
    }
}
