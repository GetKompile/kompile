/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.staging.http;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reusable HTTP transport whose DNS policy is enforced by the socket-opening
 * connection manager.
 */
@Component
public final class SafeHttpTransport implements AutoCloseable {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie");

    private final CloseableHttpClient client;

    public SafeHttpTransport() {
        this(new PublicAddressDnsResolver());
    }

    SafeHttpTransport(DnsResolver resolver) {
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(Objects.requireNonNull(resolver, "resolver"))
                        .setMaxConnTotal(20)
                        .setMaxConnPerRoute(5)
                        .build();
        client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    /**
     * Creates a loopback-capable transport exclusively for package-scoped
     * downloader tests that use an in-process HTTP server.
     */
    public static SafeHttpTransport loopbackForTests() {
        return new SafeHttpTransport(new PublicAddressDnsResolver(
                org.apache.hc.client5.http.SystemDefaultDnsResolver.INSTANCE,
                true));
    }

    /**
     * Execute a GET or HEAD request, following redirects only after validating
     * the next URI. Sensitive headers never cross an origin boundary.
     */
    public Response execute(
            URI initialUri,
            String method,
            Map<String, String> headers,
            int connectTimeoutMillis,
            int responseTimeoutMillis,
            int maxRedirects) throws IOException {
        if (connectTimeoutMillis <= 0 || responseTimeoutMillis <= 0) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("Maximum redirects cannot be negative");
        }

        String normalizedMethod = method == null
                ? ""
                : method.trim().toUpperCase(Locale.ROOT);
        if (!"GET".equals(normalizedMethod) && !"HEAD".equals(normalizedMethod)) {
            throw new IllegalArgumentException(
                    "Safe staging transport supports only GET and HEAD");
        }

        URI current = validateRemoteUri(initialUri);
        Map<String, String> currentHeaders =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            currentHeaders.putAll(headers);
        }
        currentHeaders.remove("Host");
        currentHeaders.remove("Content-Length");

        Set<URI> visited = new HashSet<>();
        for (int redirects = 0; ; redirects++) {
            if (!visited.add(current)) {
                throw new IOException("Remote redirect loop detected");
            }

            HttpUriRequestBase request =
                    new HttpUriRequestBase(normalizedMethod, current);
            request.setConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMillis))
                    .setResponseTimeout(Timeout.ofMilliseconds(responseTimeoutMillis))
                    .setRedirectsEnabled(false)
                    .build());
            for (Map.Entry<String, String> header : currentHeaders.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    request.setHeader(header.getKey(), header.getValue());
                }
            }

            CloseableHttpResponse response = client.execute(request);
            int status = response.getCode();
            if (!isRedirect(status)) {
                return new Response(current, response);
            }

            Header locationHeader = response.getFirstHeader("Location");
            String location = locationHeader == null ? null : locationHeader.getValue();
            response.close();
            if (redirects >= maxRedirects) {
                throw new IOException("Remote redirect limit exceeded");
            }
            if (location == null || location.isBlank()) {
                throw new IOException("Remote redirect omitted Location");
            }

            URI next;
            try {
                next = validateRemoteUri(current.resolve(new URI(location)));
            } catch (IllegalArgumentException | URISyntaxException e) {
                throw new IOException("Remote redirect Location is invalid", e);
            }
            if ("https".equalsIgnoreCase(current.getScheme())
                    && !"https".equalsIgnoreCase(next.getScheme())) {
                throw new IOException("Remote redirect attempted an HTTPS downgrade");
            }
            if (!sameOrigin(current, next)) {
                currentHeaders.entrySet().removeIf(entry ->
                        SENSITIVE_HEADERS.contains(
                                entry.getKey().toLowerCase(Locale.ROOT)));
            }
            current = next;
        }
    }

    public static URI validateRemoteUri(URI candidate) {
        if (candidate == null || !candidate.isAbsolute()) {
            throw new IllegalArgumentException("Remote URI must be absolute");
        }
        String scheme = candidate.getScheme() == null
                ? ""
                : candidate.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Remote URI scheme must be http or https");
        }
        if (candidate.getHost() == null
                || candidate.getRawUserInfo() != null
                || candidate.getRawFragment() != null
                || candidate.getPort() < -1
                || candidate.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "Remote URI must have a host and no credentials or fragment");
        }
        return candidate.normalize();
    }

    public static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    public static String safeUriForDiagnostics(URI uri) {
        if (uri == null) {
            return "<unknown>";
        }
        try {
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null).toString();
        } catch (URISyntaxException ignored) {
            return "<invalid-remote-uri>";
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isRedirect(int status) {
        return status == 301
                || status == 302
                || status == 303
                || status == 307
                || status == 308;
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        client.close();
    }

    /**
     * Streaming response. Callers must close it after consuming the body.
     */
    public static final class Response implements AutoCloseable {
        private final URI uri;
        private final CloseableHttpResponse response;

        private Response(URI uri, CloseableHttpResponse response) {
            this.uri = uri;
            this.response = response;
        }

        public URI uri() {
            return uri;
        }

        public int statusCode() {
            return response.getCode();
        }

        public long contentLength() {
            HttpEntity entity = response.getEntity();
            return entity == null ? -1 : entity.getContentLength();
        }

        public String header(String name) {
            Header value = response.getFirstHeader(name);
            return value == null ? null : value.getValue();
        }

        public InputStream body() throws IOException {
            HttpEntity entity = response.getEntity();
            return entity == null ? InputStream.nullInputStream() : entity.getContent();
        }

        @Override
        public void close() throws IOException {
            response.close();
        }
    }
}
