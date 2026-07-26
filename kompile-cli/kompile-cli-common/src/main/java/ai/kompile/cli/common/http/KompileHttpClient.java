/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.common.http;

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Lightweight HTTP client wrapper for Kompile REST API communication.
 * Provides JSON request/response helpers and common error handling.
 *
 * <p>Two modes. A client built with an explicit base URL sends every request there — the caller
 * named the server. A client built by {@link #routed()} has no fixed base and resolves each
 * request's host from its path through {@link KompileServiceEndpoints}, which is what lets one
 * command talk to the admin console, {@code kompile-app-chat} and {@code kompile-app-crawl-manager}
 * without every call site learning the persona split.</p>
 */
public class KompileHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Fixed base URL, or {@code null} in routed mode (resolve per request path). */
    private final String baseUrl;

    public KompileHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = newHttpClient();
        this.objectMapper = JsonUtils.standardMapper();
    }

    /** Routed mode — see {@link #routed()}. */
    private KompileHttpClient() {
        this.baseUrl = null;
        this.httpClient = newHttpClient();
        this.objectMapper = JsonUtils.standardMapper();
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * A client with no fixed host: every request routes by path to the persona process that owns
     * the contract. Use this whenever the user did NOT name a server — a single default host is a
     * wrong answer for two thirds of the API surface once app-main is admin-only.
     */
    public static KompileHttpClient routed() {
        return new KompileHttpClient();
    }

    /** True when this client resolves its host per request instead of using one fixed base URL. */
    public boolean isRouted() {
        return baseUrl == null;
    }

    /**
     * The fixed base URL, or — in routed mode — the admin console's, since that is where an
     * unrouted path resolves. Display only: build request URLs with {@link #urlFor(String)},
     * which is the one that gets a crawl SSE stream to the crawl manager.
     */
    public String getBaseUrl() {
        return baseUrl != null ? baseUrl : KompileServiceEndpoints.resolve(KompileService.ADMIN).baseUrl();
    }

    /** Absolute URL for {@code path}: the fixed base in pinned mode, the owning service when routed. */
    public String urlFor(String path) {
        if (baseUrl != null) {
            return baseUrl + path;
        }
        return KompileServiceEndpoints.urlForPath(path, null);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Sends a GET request and deserializes the response.
     */
    public <T> T get(String path, Class<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), responseType);
    }

    /**
     * Sends a GET request and deserializes to a generic type.
     */
    public <T> T get(String path, TypeReference<T> typeRef) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), typeRef);
    }

    /**
     * Sends a GET request and returns raw string response.
     */
    public String getString(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return response.body();
    }

    /**
     * Sends a POST request with JSON body and deserializes the response.
     */
    public <T> T post(String path, Object body, Class<T> responseType) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        if (responseType == Void.class || response.body() == null || response.body().isBlank()) {
            return null;
        }
        return objectMapper.readValue(response.body(), responseType);
    }

    /**
     * Sends a POST request with JSON body and returns raw string.
     */
    public String postString(String path, Object body) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return response.body();
    }

    /**
     * Sends a POST request with no body.
     */
    public String postEmpty(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return response.body();
    }

    /**
     * Sends a PUT request with JSON body and returns raw string.
     */
    public String putString(String path, Object body) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return response.body();
    }

    /**
     * Sends a PUT request with JSON body.
     */
    public <T> T put(String path, Object body, Class<T> responseType) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        if (responseType == Void.class || response.body() == null || response.body().isBlank()) {
            return null;
        }
        return objectMapper.readValue(response.body(), responseType);
    }

    /**
     * Sends a DELETE request.
     */
    public String delete(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Accept", "application/json")
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return response.body();
    }

    /**
     * Checks if the service is reachable by hitting the health endpoint.
     *
     * <p>In routed mode there is no single service to check, so this reports whether <i>any</i>
     * kompile process is up. A per-request failure then names the persona that was down, which is
     * a better error than refusing to run because the admin console happens to be stopped.</p>
     */
    public boolean isHealthy() {
        if (baseUrl == null) {
            for (KompileService service : KompileService.values()) {
                if (isHealthy(KompileServiceEndpoints.resolve(service).baseUrl())) {
                    return true;
                }
            }
            return false;
        }
        return isHealthy(baseUrl);
    }

    /** Reachability of one named service, independent of this client's mode. */
    public boolean isHealthy(String serviceBaseUrl) {
        // Generated kompile apps do not ship Spring Boot Actuator, so /actuator/health 404s.
        // Probe it first (for instances that DO expose it), then fall back to a lightweight,
        // always-present backend endpoint so `kompile app ...` works against any running instance.
        return probeOk(serviceBaseUrl + "/actuator/health") || probeOk(serviceBaseUrl + "/api/setup/status");
    }

    private boolean probeOk(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Uploads a file via multipart POST.
     */
    public String uploadFile(String path, Path filePath) throws IOException, InterruptedException {
        return uploadMultipart(path, Map.of("file", filePath), null);
    }

    /**
     * Sends a multipart upload with one or more named file parts plus optional
     * text form fields. Useful for endpoints like {@code /api/graph/io/import}
     * that take both files and parameters.
     */
    public String uploadMultipart(String path,
                                  Map<String, Path> files,
                                  Map<String, String> formFields) throws IOException, InterruptedException {
        String boundary = "----KompileBoundary" + System.currentTimeMillis();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (formFields != null) {
            for (Map.Entry<String, String> e : formFields.entrySet()) {
                if (e.getValue() == null) continue;
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        if (files != null) {
            for (Map.Entry<String, Path> e : files.entrySet()) {
                if (e.getValue() == null) continue;
                String fileName = e.getValue().getFileName().toString();
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"; filename=\""
                        + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.write(Files.readAllBytes(e.getValue()));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return response.body();
    }

    /**
     * Sends a GET request and writes the raw response body to {@code outputFile}.
     * Returns the {@code Content-Disposition} header (or null) so callers can
     * derive a default filename when needed.
     */
    public String downloadToFile(String path, Path outputFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlFor(path)))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            String body = response.body() != null ? new String(response.body(), StandardCharsets.UTF_8) : "";
            throw new IOException("HTTP " + response.statusCode() + ": " + body);
        }
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(outputFile, response.body());
        return response.headers().firstValue("Content-Disposition").orElse(null);
    }

    private void checkResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() >= 400) {
            String body = response.body() != null ? response.body() : "";
            throw new IOException("HTTP " + response.statusCode() + ": " + body);
        }
    }

    /**
     * Creates a client using auto-discovery or explicit URL.
     *
     * @param urlOverride explicit URL override, or null to use default
     * @param defaultPort default port to try on localhost
     * @return configured HTTP client
     */
    public static KompileHttpClient create(String urlOverride, int defaultPort) {
        if (urlOverride != null && !urlOverride.isBlank()) {
            return new KompileHttpClient(urlOverride);
        }
        return new KompileHttpClient("http://localhost:" + defaultPort);
    }
}
