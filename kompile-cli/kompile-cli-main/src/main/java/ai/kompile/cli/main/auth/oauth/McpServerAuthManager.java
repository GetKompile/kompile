/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OAuth 2.1 client for arbitrary custom MCP servers.
 *
 * <p>Reuses the existing managed credential machinery ({@link CredentialStore},
 * {@link ManagedCredential}, {@link OAuthSupport}) so MCP server tokens live in the
 * same locked, POSIX-private {@code ~/.kompile/auth.json} store as provider logins,
 * with the same serialized refresh path.</p>
 *
 * <p>Flow per MCP authorization spec: resource metadata discovery
 * (RFC 9728), authorization server metadata (RFC 8414), optional dynamic client
 * registration (RFC 7591), then browser Authorization Code + PKCE (S256).</p>
 */
public final class McpServerAuthManager {
    public static final String PROVIDER_PREFIX = "mcp-server-";
    private static final long REFRESH_SKEW_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final CredentialStore store;
    private final OAuthSupport.HttpTransport http;
    private final String callbackHost;
    private final boolean httpsOnly;

    public McpServerAuthManager(CredentialStore store) {
        this(store, OAuthSupport.defaultTransport(), OAuthSupport.callbackHost(), true);
    }

    McpServerAuthManager(CredentialStore store, OAuthSupport.HttpTransport http,
                         String callbackHost, boolean httpsOnly) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.http = java.util.Objects.requireNonNull(http, "http");
        this.callbackHost = callbackHost == null || callbackHost.isBlank()
                ? "127.0.0.1" : callbackHost.trim();
        this.httpsOnly = httpsOnly;
    }

    public static String providerIdFor(String serverName) {
        return PROVIDER_PREFIX + serverName.toLowerCase(Locale.ROOT);
    }

    public boolean hasCredential(String serverName) throws IOException {
        return store.read(providerIdFor(serverName)) != null;
    }

    /** True when a stored token exists and is not past its expiry (with skew). */
    public boolean hasValidCredential(String serverName) throws IOException {
        ManagedCredential credential = store.read(providerIdFor(serverName));
        return credential != null && credential.isOAuth()
                && credential.getExpires() > System.currentTimeMillis() + REFRESH_SKEW_MILLIS;
    }

    /**
     * Resolve an access token for tool calls, refreshing with the stored refresh
     * token when the access token is near expiry. Returns null when not logged in.
     */
    public String resolveAccessToken(String serverName) throws IOException {
        String providerId = providerIdFor(serverName);
        ManagedCredential credential = store.read(providerId);
        if (credential == null || !credential.isOAuth()) {
            return null;
        }
        if (credential.getExpires() > System.currentTimeMillis() + REFRESH_SKEW_MILLIS) {
            return credential.getAccess();
        }
        String refresh = credential.getRefresh();
        if (refresh == null || refresh.isBlank()) {
            return credential.getAccess();
        }
        String registrationEndpoint = credential.getMetadata("registrationEndpoint");
        String clientId = credential.getMetadata("clientId");
        if (clientId == null || clientId.isBlank() || registrationEndpoint == null) {
            return credential.getAccess();
        }
        try {
            OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                    "POST",
                    URI.create(registrationEndpoint),
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    OAuthSupport.form(Map.of(
                            "grant_type", "refresh_token",
                            "client_id", clientId,
                            "refresh_token", refresh))));
            OAuthSupport.requireSuccess(response, serverName + " token refresh");
            JsonNode body = OAuthSupport.json(response, serverName + " token refresh");
            String access = OAuthSupport.requiredText(body, "access_token",
                    serverName + " token refresh");
            String nextRefresh = OAuthSupport.optionalText(body, "refresh_token");
            if (nextRefresh == null || nextRefresh.isBlank()) nextRefresh = refresh;
            long expires = body.has("expires_in")
                    ? OAuthSupport.expiryFromNow(
                            OAuthSupport.requiredPositiveLong(body, "expires_in",
                                    serverName + " token refresh"),
                            REFRESH_SKEW_MILLIS)
                    : Long.MAX_VALUE;
            ManagedCredential refreshed = credential(serverName, access, nextRefresh,
                    expires, clientId, registrationEndpoint);
            store.put(providerId, refreshed);
            return access;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while refreshing MCP token for "
                    + serverName, e);
        }
    }

    /**
     * Run the browser Authorization Code + PKCE flow against the server's
     * authorization metadata. Discovers metadata relative to the server URL
     * (falling back to {@code /.well-known/oauth-authorization-server} at the
     * URL origin), registers a client when the metadata advertises a
     * registration endpoint, and stores the resulting tokens.
     */
    public ManagedCredential login(String serverName, String serverUrl,
                                   String scopeOverride, boolean manual,
                                   java.io.PrintStream out)
            throws IOException, InterruptedException {
        JsonNode metadata = discoverAuthorizationMetadata(serverUrl);
        String authorizationEndpoint = text(metadata, "authorization_endpoint");
        String tokenEndpoint = text(metadata, "token_endpoint");
        if (authorizationEndpoint == null || tokenEndpoint == null) {
            throw new IOException("Authorization server metadata for " + serverUrl
                    + " did not include authorization/token endpoints");
        }
        String registrationEndpoint = text(metadata, "registration_endpoint");
        String scope = scopeOverride != null && !scopeOverride.isBlank()
                ? scopeOverride.trim()
                : text(metadata, "scopes_supported") == null
                        ? "mcp:read mcp:write"
                        : String.join(" ", stringList(metadata, "scopes_supported"));

        String clientId;
        String clientSecret = null;
        String stored = null;
        ManagedCredential existing = store.read(providerIdFor(serverName));
        if (existing != null && existing.getMetadata("clientId") != null) {
            clientId = existing.getMetadata("clientId");
            stored = registrationEndpoint;
        } else if (registrationEndpoint != null) {
            String previewRedirect = manual
                    ? "http://127.0.0.1:1456" + "/mcp/oauth/callback/register"
                    : "http://" + callbackHost + ":0/mcp/oauth/callback/register";
            ObjectNode registration = registerClient(
                    registrationEndpoint, serverName, previewRedirect, scope);
            clientId = text(registration, "client_id");
            clientSecret = text(registration, "client_secret");
            if (clientId == null || clientId.isBlank()) {
                throw new IOException("Dynamic client registration for " + serverName
                        + " returned no client_id");
            }
        } else {
            throw new IOException("Server " + serverUrl + " advertises no registration_endpoint"
                    + " and no client is registered for '" + serverName
                    + "'. Register a client manually or use --bearer-token-env-var.");
        }

        OAuthSupport.Pkce pkce = OAuthSupport.generatePkce();
        String state = OAuthSupport.randomState();
        String callbackPath = "/mcp/oauth/callback/" + OAuthSupport.randomPathToken();
        String redirectUri;
        OAuthSupport.CallbackServer callback = null;
        if (manual) {
            redirectUri = "http://127.0.0.1:1456" + callbackPath;
        } else {
            callback = OAuthSupport.CallbackServer.start(callbackHost, callbackHost,
                    0, callbackPath, state);
            redirectUri = callback.redirectUri().toString();
        }
        try {
            URI authorizeUri = OAuthSupport.uriWithQuery(authorizationEndpoint, mapOf(
                    "response_type", "code",
                    "client_id", clientId,
                    "redirect_uri", redirectUri,
                    "scope", scope,
                    "state", state,
                    "code_challenge", pkce.challenge(),
                    "code_challenge_method", "S256"));
            out.println("Authorize Kompile to access MCP server '" + serverName + "':");
            out.println(authorizeUri);
            if (manual) {
                out.print("Paste the redirected URL or authorization code: ");
                out.flush();
                String line = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))
                        .readLine();
                OAuthSupport.AuthorizationInput input =
                        OAuthSupport.parseAuthorizationInput(line);
                return exchangeAndStore(serverName, tokenEndpoint, clientId, clientSecret,
                        input.code(), pkce.verifier(), redirectUri, registrationEndpoint, stored);
            }
            OAuthSupport.AuthorizationInput authorization =
                    callback.await(java.time.Duration.ofMinutes(5));
            return exchangeAndStore(serverName, tokenEndpoint, clientId, clientSecret,
                    authorization.code(), pkce.verifier(), redirectUri,
                    registrationEndpoint, stored);
        } finally {
            if (callback != null) callback.close();
        }
    }

    public boolean logout(String serverName) throws IOException {
        String providerId = providerIdFor(serverName);
        if (store.read(providerId) == null) return false;
        return store.delete(providerId);
    }

    private ManagedCredential exchangeAndStore(
            String serverName, String tokenEndpoint, String clientId, String clientSecret,
            String code, String verifier, String redirectUri, String registrationEndpoint,
            String fallbackRegistrationEndpoint) throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("client_id", clientId);
        fields.put("code", code);
        fields.put("code_verifier", verifier);
        fields.put("redirect_uri", redirectUri);
        if (clientSecret != null && !clientSecret.isBlank()) {
            fields.put("client_secret", clientSecret);
        }
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST", URI.create(tokenEndpoint),
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                OAuthSupport.form(fields)));
        OAuthSupport.requireSuccess(response, serverName + " token exchange");
        JsonNode body = OAuthSupport.json(response, serverName + " token exchange");
        String access = OAuthSupport.requiredText(body, "access_token",
                serverName + " token exchange");
        String refresh = OAuthSupport.optionalText(body, "refresh_token");
        long expires = body.has("expires_in")
                ? OAuthSupport.expiryFromNow(OAuthSupport.requiredPositiveLong(
                        body, "expires_in", serverName + " token exchange"), REFRESH_SKEW_MILLIS)
                : Long.MAX_VALUE;
        ManagedCredential credential = credential(serverName, access,
                refresh == null ? "" : refresh, expires, clientId,
                registrationEndpoint != null ? registrationEndpoint : fallbackRegistrationEndpoint);
        store.put(providerIdFor(serverName), credential);
        return credential;
    }

    private JsonNode discoverAuthorizationMetadata(String serverUrl) throws IOException {
        // Try the well-known at the server origin first, then under the URL path,
        // per RFC 9728 resource metadata followed by RFC 8414 authorization metadata.
        URI uri = URI.create(serverUrl);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String[] candidates = {
                origin + "/.well-known/oauth-authorization-server",
                origin + "/.well-known/openid-configuration",
                origin + (uri.getRawPath() == null ? "" : stripTrailingSlash(uri.getRawPath()))
                        + "/.well-known/oauth-authorization-server"
        };
        IOException lastFailure = null;
        for (String candidate : candidates) {
            try {
                OAuthSupport.Response response = http.send(
                        new OAuthSupport.Request("GET", URI.create(candidate), Map.of(), null));
                if (response.success()) {
                    JsonNode body = OAuthSupport.json(response, "authorization metadata");
                    if (body.has("authorization_endpoint")) return body;
                    // Resource metadata (RFC 9728) points at the authorization server.
                    String authorizationServer = text(body, "authorization_servers") != null
                            ? firstString(body, "authorization_servers") : null;
                    if (authorizationServer != null) {
                        OAuthSupport.Response asResponse = http.send(new OAuthSupport.Request(
                                "GET",
                                URI.create(stripTrailingSlash(authorizationServer)
                                        + "/.well-known/oauth-authorization-server"),
                                Map.of(), null));
                        if (asResponse.success()) {
                            return OAuthSupport.json(asResponse, "authorization metadata");
                        }
                    }
                }
            } catch (IOException e) {
                lastFailure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during MCP metadata discovery", e);
            }
        }
        throw new IOException("Could not discover OAuth metadata for " + serverUrl
                + (lastFailure == null ? "" : ": " + lastFailure.getMessage()), lastFailure);
    }

    private ObjectNode registerClient(String registrationEndpoint, String serverName,
                                      String redirectUri, String scope)
            throws IOException, InterruptedException {
        ObjectNode request = OAuthSupport.MAPPER.createObjectNode();
        request.put("client_name", "Kompile CLI (" + serverName + ")");
        request.putArray("redirect_uris").add(redirectUri);
        request.put("grant_types", "authorization_code refresh_token");
        request.put("response_types", "code");
        request.put("token_endpoint_auth_method", "none");
        request.put("scope", scope);
        OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                "POST", URI.create(registrationEndpoint),
                Map.of("Content-Type", "application/json"),
                OAuthSupport.MAPPER.writeValueAsString(request)));
        OAuthSupport.requireSuccess(response, serverName + " dynamic client registration");
        JsonNode body = OAuthSupport.json(response, serverName + " client registration");
        return (ObjectNode) body;
    }

    private static ManagedCredential credential(
            String serverName, String access, String refresh, long expires,
            String clientId, String registrationEndpoint) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (clientId != null) metadata.put("clientId", clientId);
        if (registrationEndpoint != null) metadata.put("registrationEndpoint", registrationEndpoint);
        return ManagedCredential.oauth(access, refresh, expires, metadata);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") && value.length() > 1
                ? value.substring(0, value.length() - 1) : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : null;
    }

    private static String firstString(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value != null && value.isArray() && !value.isEmpty()
                && value.get(0).isTextual()) {
            return value.get(0).asText();
        }
        return null;
    }

    private static java.util.List<String> stringList(JsonNode node, String field) {
        java.util.List<String> values = new java.util.ArrayList<>();
        JsonNode value = node == null ? null : node.get(field);
        if (value != null && value.isArray()) {
            value.forEach(entry -> {
                if (entry.isTextual()) values.add(entry.asText());
            });
        }
        return values;
    }

    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
