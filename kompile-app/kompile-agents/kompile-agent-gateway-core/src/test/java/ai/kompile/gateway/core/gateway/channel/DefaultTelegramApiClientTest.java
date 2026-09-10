/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTelegramApiClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void reportsTypedRateLimitWithoutLeakingToken() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/botsecret-token/getUpdates", exchange -> respond(exchange, 429,
                "{\"ok\":false,\"error_code\":429,"
                        + "\"description\":\"retry secret-token later\","
                        + "\"parameters\":{\"retry_after\":4}}"));
        server.start();
        DefaultTelegramApiClient client = client();

        TelegramApiClient.TelegramApiException error = assertThrows(
                TelegramApiClient.TelegramApiException.class,
                () -> client.getUpdates(0L, 100, 0, java.util.List.of("message")));

        assertEquals(429, error.errorCode());
        assertEquals(4, error.retryAfterSeconds());
        assertFalse(error.getMessage().contains("secret-token"));
        assertTrue(error.getMessage().contains("[redacted]"));
    }

    @Test
    void webhookViewReturnsOnlyHostAndCounts() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/botsecret-token/getWebhookInfo", exchange -> respond(exchange, 200,
                "{\"ok\":true,\"result\":{"
                        + "\"url\":\"https://hooks.example.test/private/path?token=x\","
                        + "\"pending_update_count\":3}}"));
        server.start();

        var info = client().getWebhookInfo();

        assertTrue(info.configured());
        assertEquals("hooks.example.test", info.redactedHost());
        assertEquals(3, info.pendingUpdateCount());
    }

    private DefaultTelegramApiClient client() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/botsecret-token";
        return new DefaultTelegramApiClient(
                "secret-token", base, HttpClient.newHttpClient(), JsonUtils.standardMapper());
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
