/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.catalog.remote;

import ai.kompile.staging.auth.AuthProviderChain;
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.http.SafeHttpTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteCatalogServiceTest {

    private static final byte[] EMPTY_CATALOG = (
            "{\"catalog_version\":\"1.0\",\"name\":\"local\",\"archives\":[]}")
            .getBytes(StandardCharsets.UTF_8);

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<SafeHttpTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        for (SafeHttpTransport transport : transports) {
            transport.close();
        }
    }

    @Test
    void preservesSignedQueryButRedactsItFromCatalogAndCacheStatus() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/catalog.json", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, 200, EMPTY_CATALOG);
        });
        server.start();

        RemoteCatalogService service = service(Map.of(), new StagingAssetLimits());
        String signedUrl = url(server, "/catalog.json?X-Amz-Signature=top-secret");

        RemoteCatalog catalog = service.getCatalogFromUrl(signedUrl, true);

        assertEquals("X-Amz-Signature=top-secret", receivedQuery.get());
        assertEquals(url(server, "/catalog.json"), catalog.getSourceUrl());
        Map<String, RemoteCatalogService.CacheStatus> status = service.getCacheStatus();
        assertTrue(status.containsKey(url(server, "/catalog.json")));
        assertFalse(status.toString().contains("top-secret"));
    }

    @Test
    void stripsAuthorizationWhenRedirectChangesOrigin() throws Exception {
        AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
        HttpServer target = server();
        target.createContext("/catalog.json", exchange -> {
            forwardedAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, EMPTY_CATALOG);
        });
        target.start();

        HttpServer source = server();
        source.createContext("/catalog.json", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location",
                    url(target, "/catalog.json?download-token=top-secret"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();

        RemoteCatalogService service = service(
                Map.of("Authorization", "Bearer top-secret"),
                new StagingAssetLimits());

        RemoteCatalog catalog = service.getCatalogFromUrl(
                url(source, "/catalog.json"), true);

        assertNull(forwardedAuthorization.get());
        assertEquals("local", catalog.getName());
    }

    @Test
    void rejectsCatalogAboveConfiguredByteLimit() throws Exception {
        HttpServer server = server();
        server.createContext("/catalog.json", exchange ->
                send(exchange, 200, new byte[64]));
        server.start();

        StagingAssetLimits limits = new StagingAssetLimits();
        limits.setConfigBytes(16);
        RemoteCatalogService service = service(Map.of(), limits);

        RemoteCatalog catalog = service.getCatalogFromUrl(
                url(server, "/catalog.json"), true);

        assertNull(catalog);
        assertTrue(service.getCacheStatus().isEmpty());
    }

    private RemoteCatalogService service(
            Map<String, String> authHeaders,
            StagingAssetLimits limits) {
        AuthProviderChain authProviderChain = mock(AuthProviderChain.class);
        when(authProviderChain.getAuthHeaders(anyString())).thenReturn(authHeaders);
        SafeHttpTransport transport = SafeHttpTransport.loopbackForTests();
        transports.add(transport);
        RemoteCatalogService service =
                new RemoteCatalogService(authProviderChain, limits, transport);
        ReflectionTestUtils.setField(service, "refreshInterval", "24h");
        return service;
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void send(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
