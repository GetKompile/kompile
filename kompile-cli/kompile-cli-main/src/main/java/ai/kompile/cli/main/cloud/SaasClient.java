/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.util.HttpConstants;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Lightweight HTTP client for the kompile-saas REST API.
 *
 * <p>This client is standalone (no Quarkus/MicroProfile dependencies) and uses only
 * the JDK built-in {@link HttpClient} together with Jackson for JSON
 * serialization/deserialization. It mirrors the essential operations of the
 * {@code KompileSaasClient} available in the kompile-saas server module but is
 * designed to be bundled with the kompile CLI.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * SaasClient client = new SaasClient("https://api.kompile.ai", null);
 * client.login("alice", "s3cr3t");
 * JsonNode jobs = client.get("/api/upload");
 * }</pre>
 */
public class SaasClient {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    /** Bearer token — may be null until {@link #login} is called. */
    private String token;

    /**
     * Creates a new {@code SaasClient}.
     *
     * @param baseUrl base URL of the kompile-saas instance (trailing slash is stripped)
     * @param token   optional pre-existing bearer token; may be {@code null}
     */
    public SaasClient(String baseUrl, String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Authenticates against {@code POST /api/users/login}.
     *
     * <p>On success the response token is extracted and stored internally so
     * subsequent calls to {@link #get}, {@link #post}, and {@link #delete} are
     * automatically authenticated.</p>
     *
     * @param username the account username
     * @param password the account password
     * @return the full response {@link JsonNode}
     * @throws IOException if the request fails or the server returns an error
     */
    public JsonNode login(String username, String password) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("username", username);
        body.put("password", password);

        String responseBody = postNoAuth("/api/users/login", body.toString());
        JsonNode node = MAPPER.readTree(responseBody);

        // Extract token — the saas API may return it under "token" or "accessToken"
        String extracted = null;
        if (node.has("token") && !node.get("token").isNull()) {
            extracted = node.get("token").asText(null);
        } else if (node.has("accessToken") && !node.get("accessToken").isNull()) {
            extracted = node.get("accessToken").asText(null);
        }
        if (extracted != null && !extracted.isEmpty()) {
            this.token = extracted;
        }
        return node;
    }

    /**
     * Registers a new account via {@code POST /api/users/register}.
     *
     * @param username        desired username
     * @param email           account e-mail address
     * @param password        desired password
     * @param confirmPassword must match {@code password}
     * @return the full response {@link JsonNode}
     * @throws IOException if the request fails or the server returns an error
     */
    public JsonNode register(String username, String email,
                             String password, String confirmPassword) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("userName", username);
        body.put("email", email);
        body.put("password", password);
        body.put("confirmPassword", confirmPassword);

        String responseBody = postNoAuth("/api/users/register", body.toString());
        return MAPPER.readTree(responseBody);
    }

    /**
     * Performs an authenticated {@code GET} request.
     *
     * @param path server-relative path (e.g. {@code "/api/upload"})
     * @return parsed response body as {@link JsonNode}
     * @throws IOException if the request fails or the server returns an error
     */
    public JsonNode get(String path) throws IOException {
        HttpRequest request = newAuthRequest(path)
                .GET()
                .build();
        String body = send(request, "GET " + path);
        return MAPPER.readTree(body);
    }

    /**
     * Performs an authenticated {@code POST} request with a JSON body.
     *
     * @param path     server-relative path
     * @param jsonBody request body as a JSON string
     * @return parsed response body as {@link JsonNode}
     * @throws IOException if the request fails or the server returns an error
     */
    public JsonNode post(String path, String jsonBody) throws IOException {
        HttpRequest request = newAuthRequest(path)
                .header(HttpConstants.CONTENT_TYPE, HttpConstants.APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        String body = send(request, "POST " + path);
        return MAPPER.readTree(body);
    }

    /**
     * Performs an authenticated {@code DELETE} request.
     *
     * @param path server-relative path
     * @return parsed response body as {@link JsonNode}
     * @throws IOException if the request fails or the server returns an error
     */
    public JsonNode delete(String path) throws IOException {
        HttpRequest request = newAuthRequest(path)
                .DELETE()
                .build();
        String body = send(request, "DELETE " + path);
        return MAPPER.readTree(body);
    }

    /**
     * Returns the current bearer token, or {@code null} if not yet authenticated.
     *
     * @return the bearer token string, or {@code null}
     */
    public String getToken() {
        return token;
    }

    /**
     * Returns {@code true} if a non-empty bearer token is present.
     *
     * @return {@code true} when authenticated
     */
    public boolean isAuthenticated() {
        return token != null && !token.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Sends a {@code POST} request <em>without</em> an {@code Authorization} header.
     * Used for login and register endpoints that must be accessible anonymously.
     *
     * @param path endpoint path
     * @param body JSON body string
     * @return raw response body string
     * @throws IOException on transport or HTTP error
     */
    private String postNoAuth(String path, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header(HttpConstants.CONTENT_TYPE, HttpConstants.APPLICATION_JSON)
                .header(HttpConstants.ACCEPT, HttpConstants.APPLICATION_JSON)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request, "POST " + path);
    }

    /**
     * Builds an {@link HttpRequest.Builder} pre-configured with the
     * {@code Authorization: Bearer <token>} header and a 60-second read timeout.
     *
     * @param path server-relative path
     * @return builder ready for method chaining
     */
    private HttpRequest.Builder newAuthRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header(HttpConstants.ACCEPT, HttpConstants.APPLICATION_JSON)
                .timeout(Duration.ofSeconds(60));
        if (token != null && !token.isEmpty()) {
            builder.header(HttpConstants.AUTHORIZATION, HttpConstants.BEARER_PREFIX + token);
        }
        return builder;
    }

    /**
     * Sends an {@link HttpRequest}, enforces status-code semantics, and returns the
     * raw response body string.
     *
     * <ul>
     *   <li>HTTP 401 — throws with a hint to run {@code kompile cloud login}</li>
     *   <li>HTTP 403 — throws with a "Forbidden" message</li>
     *   <li>Any other non-2xx status — throws with the status code and body</li>
     * </ul>
     *
     * @param request the fully-built {@link HttpRequest}
     * @param opName  human-readable operation name used in error messages
     * @return the response body as a string (may be empty for 204 No Content)
     * @throws IOException on transport failure or non-2xx HTTP status
     */
    private String send(HttpRequest request, String opName) throws IOException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted: " + opName, e);
        }

        int status = response.statusCode();

        if (status == 401) {
            throw new IOException(
                    "Authentication required for " + opName
                    + ". Run 'kompile cloud login' first.");
        }
        if (status == 403) {
            throw new IOException(
                    "Forbidden: you do not have permission to perform " + opName + ".");
        }
        if (status < 200 || status >= 300) {
            throw new IOException(
                    "Request failed [" + status + "] for " + opName
                    + ": " + response.body());
        }

        return response.body() == null ? "" : response.body();
    }
}
