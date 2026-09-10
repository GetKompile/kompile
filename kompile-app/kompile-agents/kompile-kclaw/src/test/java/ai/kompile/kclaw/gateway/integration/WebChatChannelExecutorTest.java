/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelInternalAuthentication;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.model.AgentRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebChatChannelExecutorTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesWebChatConversationIdForChannelSessionHistory() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat/channel/status", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            long timestamp = Long.parseLong(exchange.getRequestHeaders().getFirst(
                    ChannelInternalAuthentication.TIMESTAMP_HEADER));
            String nonce = exchange.getRequestHeaders().getFirst(
                    ChannelInternalAuthentication.NONCE_HEADER);
            assertTrue(ChannelInternalAuthentication.verify(
                    KEY, timestamp, nonce, body,
                    exchange.getRequestHeaders().getFirst(
                            ChannelInternalAuthentication.SIGNATURE_HEADER)));
            respond(exchange, 200, "{\"available\":true}");
        });
        server.createContext("/api/chat/channel", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requestBody.set(new String(body, StandardCharsets.UTF_8));
            long timestamp = Long.parseLong(exchange.getRequestHeaders().getFirst(
                    ChannelInternalAuthentication.TIMESTAMP_HEADER));
            String nonce = exchange.getRequestHeaders().getFirst(
                    ChannelInternalAuthentication.NONCE_HEADER);
            assertTrue(ChannelInternalAuthentication.verify(
                    KEY, timestamp, nonce, body,
                    exchange.getRequestHeaders().getFirst(
                            ChannelInternalAuthentication.SIGNATURE_HEADER)));
            respond(exchange, 200,
                    "{\"answer\":\"hello from web\"}");
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
        WebChatChannelExecutor executor = new WebChatChannelExecutor(
                HttpClient.newHttpClient(), JsonUtils.standardMapper(), endpoint,
                session -> ChannelInternalAuthentication.opaqueConversationId(KEY, session),
                (body, timestamp, nonce) -> ChannelInternalAuthentication.sign(
                        KEY, timestamp, nonce, body));

        assertTrue(executor.isAvailable());
        var response = executor.execute(AgentRequest.builder()
                .agentId("jarvis")
                .sessionKey("telegram:42")
                .message("hello")
                .build());

        assertTrue(response.isSuccess());
        assertEquals("hello from web", response.getResponse());
        var json = JsonUtils.standardMapper().readTree(requestBody.get());
        assertTrue(json.path("conversationId").asText().matches("channel-[A-Za-z0-9_-]{43}"));
        assertTrue(!json.path("conversationId").asText().contains("telegram"));
        assertEquals("hello", json.path("message").asText());
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
