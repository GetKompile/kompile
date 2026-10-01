/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * RestTemplate-based HTTP client for the ask_graph_* grounding tools.
 *
 * <p>Supports two construction paths:</p>
 * <ul>
 *   <li>Production: {@link #GroundingBackendClient(String)} — creates its own RestTemplate
 *       with sensible connect/read timeouts.</li>
 *   <li>Testing: {@link #GroundingBackendClient(String, RestTemplate)} — accepts an injected
 *       RestTemplate so a {@code MockRestServiceServer} can be bound to it without any
 *       real HTTP server running.</li>
 * </ul>
 *
 * <p>{@link #isAvailable()} returns {@code true} only when a non-blank base URL was
 * supplied at construction time — no port probing, no global static state.</p>
 *
 * <p>An HTTP error status is a value, not an exception: every method returns the status
 * and the server's body for 4xx/5xx exactly as for 2xx, so a tool can put the server's own
 * explanation in front of the model. Only transport failures (refused connection, timeout)
 * throw.</p>
 */
class GroundingBackendClient {

    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final boolean injected;

    /** Production constructor — creates its own RestTemplate with default timeouts. */
    GroundingBackendClient(String baseUrl) {
        this.baseUrl = normalise(baseUrl);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(35_000);
        this.restTemplate = createNativeSafeRestTemplate(factory);
        this.injected = false;
    }

    /**
     * Visible for testing — lets a {@code MockRestServiceServer} bind to the
     * underlying template without a real HTTP server running.
     */
    GroundingBackendClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = normalise(baseUrl);
        this.restTemplate = restTemplate;
        this.injected = true;
    }

    /**
     * Returns {@code true} when a non-blank base URL was supplied at construction time.
     * Never probes ports or reads global state.
     */
    boolean isAvailable() {
        return baseUrl != null;
    }

    /**
     * POST {@code jsonBody} to {@code baseUrl + path} with JSON content/accept headers.
     *
     * @param path     API path, e.g. {@code /api/kb-grounding/verify}
     * @param jsonBody serialised JSON request body
     * @return the status code and response body, error statuses included
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    GroundingResponse post(String path, String jsonBody) {
        return exchange(restTemplate, HttpMethod.POST, path, jsonEntity(jsonBody));
    }

    /**
     * POST {@code jsonBody} to {@code baseUrl + path} using a per-call read timeout.
     *
     * <p>When a {@link RestTemplate} was injected at construction time (test path), the call
     * is routed through that template so a {@code MockRestServiceServer} can intercept it.
     * Otherwise, a fresh {@link RestTemplate} is built for this call with the supplied
     * {@code readTimeout} so the shared template is never mutated.</p>
     *
     * @param path        API path, e.g. {@code /api/unified-crawl/single-source}
     * @param jsonBody    serialised JSON request body
     * @param readTimeout per-call read timeout; connect timeout is always 5 000 ms
     * @return the status code and response body, error statuses included
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    GroundingResponse post(String path, String jsonBody, Duration readTimeout) {
        RestTemplate rt;
        if (injected) {
            rt = this.restTemplate;
        } else {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout((int) readTimeout.toMillis());
            rt = createNativeSafeRestTemplate(factory);
        }
        return exchange(rt, HttpMethod.POST, path, jsonEntity(jsonBody));
    }

    /**
     * POST a file as {@code multipart/form-data} to {@code baseUrl + path} — used by
     * {@code graph_import} to upload a {@code .kgraph} to {@code /api/graph/unified/import}.
     *
     * @param path        API path, e.g. {@code /api/graph/unified/import}
     * @param file        the file to upload under the {@code file} part
     * @param factSheetId optional {@code factSheetId} form field (null to omit)
     * @return the status code and response body, error statuses included
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    GroundingResponse postMultipartFile(
            String path, Path file, Long factSheetId, boolean requireManaged) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));
        if (factSheetId != null) {
            body.add("factSheetId", factSheetId.toString());
        }
        if (requireManaged) {
            body.add("requireManaged", "true");
        }
        return exchange(restTemplate, HttpMethod.POST, path, new HttpEntity<>(body, headers));
    }

    /**
     * GET {@code baseUrl + path} with an Accept: application/json header.
     *
     * <p>Query parameters must be embedded in {@code path} already
     * (e.g. {@code /api/mebn/query?nodeId=n1&maxDepth=3}).</p>
     *
     * @param path API path with any query parameters already appended
     * @return the status code and response body, error statuses included
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    GroundingResponse get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return exchange(restTemplate, HttpMethod.GET, path, new HttpEntity<>(headers));
    }

    /** DELETE {@code baseUrl + path}, preserving the response for tool-led destructive controls. */
    GroundingResponse delete(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return exchange(restTemplate, HttpMethod.DELETE, path, new HttpEntity<>(headers));
    }

    /**
     * GET {@code baseUrl + path} returning the raw response body as a byte array — used by
     * {@code graph_export} to download a {@code .kgraph} binary from
     * {@code /api/graph/unified/export}.
     *
     * <p>Query parameters must be embedded in {@code path} already
     * (e.g. {@code /api/graph/unified/export?factSheetId=42}).</p>
     *
     * @param path API path with any query parameters already appended
     * @return the status code and body bytes (empty when the body was null); on an error
     *         status the bytes are the server's error body, never content
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    BinaryResponse getBytes(String path) {
        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(baseUrl + path, byte[].class);
            return new BinaryResponse(resp.getStatusCode().value(),
                    resp.getBody() != null ? resp.getBody() : new byte[0]);
        } catch (RestClientResponseException e) {
            return new BinaryResponse(e.getStatusCode().value(), e.getResponseBodyAsByteArray());
        }
    }

    /**
     * The server's own explanation in an error body: its {@code message}, else its
     * {@code error}, else the body itself cut to 200 characters.
     */
    static String errorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode json = ERROR_MAPPER.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null && !msg.isBlank()) return msg;
            msg = json.path("error").asText(null);
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Exception ignored) {
            // Not JSON: report the body as sent.
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private GroundingResponse exchange(RestTemplate rt, HttpMethod method, String path, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> resp = rt.exchange(baseUrl + path, method, entity, String.class);
            return new GroundingResponse(resp.getStatusCode().value(),
                    resp.getBody() != null ? resp.getBody() : "");
        } catch (RestClientResponseException e) {
            // Spring's default error handler turns 4xx/5xx into exceptions. The status and body
            // are the answer here; the body is read as UTF-8 because JSON error responses carry
            // no charset and Spring's fallback is ISO-8859-1.
            return new GroundingResponse(e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8));
        }
    }

    private static HttpEntity<String> jsonEntity(String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(jsonBody, headers);
    }

    /** Visible for testing — allows a {@code MockRestServiceServer} to bind. */
    RestTemplate getRestTemplate() {
        return restTemplate;
    }

    /**
     * Build only the converters used by this string/byte/multipart client.
     *
     * <p>The default {@link RestTemplate} converter set asks Spring to discover every
     * optional Jackson module on the classpath. In a native image that reflective
     * discovery attempts to instantiate {@code jackson-module-kotlin} and can abort MCP
     * tool initialization before the server publishes any tools. These endpoints already
     * exchange serialized JSON strings, so a Jackson converter is neither needed nor
     * desirable here.</p>
     */
    private static RestTemplate createNativeSafeRestTemplate(SimpleClientHttpRequestFactory factory) {
        RestTemplate template = new RestTemplate(List.of(
                new ByteArrayHttpMessageConverter(),
                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new FormHttpMessageConverter()));
        template.setRequestFactory(factory);
        return template;
    }

    private static String normalise(String url) {
        if (url == null || url.isBlank()) return null;
        return url.replaceAll("/+$", "");
    }

    /**
     * Minimal response wrapper mirroring the relevant subset of
     * {@code java.net.http.HttpResponse}: status code and body string.
     */
    record GroundingResponse(int statusCode, String body) {}

    /** Status code and raw body bytes of a binary download. */
    record BinaryResponse(int statusCode, byte[] body) {
        boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
