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
 */
class GroundingBackendClient {

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
     * @return the status code and response body
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    GroundingResponse post(String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + path, new HttpEntity<>(jsonBody, headers), String.class);
        return new GroundingResponse(resp.getStatusCode().value(),
                resp.getBody() != null ? resp.getBody() : "");
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
     * @return the status code and response body
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = rt.postForEntity(
                baseUrl + path, new HttpEntity<>(jsonBody, headers), String.class);
        return new GroundingResponse(resp.getStatusCode().value(),
                resp.getBody() != null ? resp.getBody() : "");
    }

    /**
     * POST a file as {@code multipart/form-data} to {@code baseUrl + path} — used by
     * {@code graph_import} to upload a {@code .kgraph} to {@code /api/graph/unified/import}.
     *
     * @param path        API path, e.g. {@code /api/graph/unified/import}
     * @param file        the file to upload under the {@code file} part
     * @param factSheetId optional {@code factSheetId} form field (null to omit)
     * @return the status code and response body
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
        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl + path, new HttpEntity<>(body, headers), String.class);
        return new GroundingResponse(resp.getStatusCode().value(),
                resp.getBody() != null ? resp.getBody() : "");
    }

    /**
     * GET {@code baseUrl + path} with an Accept: application/json header.
     *
     * <p>Query parameters must be embedded in {@code path} already
     * (e.g. {@code /api/mebn/query?nodeId=n1&maxDepth=3}).</p>
     *
     * @param path API path with any query parameters already appended
     * @return the status code and response body
     * @throws org.springframework.web.client.RestClientException on transport errors
     */
    GroundingResponse get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return new GroundingResponse(resp.getStatusCode().value(),
                resp.getBody() != null ? resp.getBody() : "");
    }

    /** DELETE {@code baseUrl + path}, preserving the response for tool-led destructive controls. */
    GroundingResponse delete(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        return new GroundingResponse(resp.getStatusCode().value(),
                resp.getBody() != null ? resp.getBody() : "");
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
     * @return the response body bytes, or an empty array if the body was null
     * @throws org.springframework.web.client.RestClientException on transport errors or non-2xx status
     */
    byte[] getBytes(String path) {
        ResponseEntity<byte[]> resp = restTemplate.getForEntity(baseUrl + path, byte[].class);
        return resp.getBody() != null ? resp.getBody() : new byte[0];
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
}
