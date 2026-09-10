/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelInternalAuthentication;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.service.AgentExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Routes channel turns through the authenticated internal web-chat conversation endpoint. */
public final class WebChatChannelExecutor implements AgentExecutor {

    @FunctionalInterface
    public interface InternalSigner {
        String sign(byte[] body, long timestamp, String nonce);
    }

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI chatEndpoint;
    private final Function<String, String> conversationIds;
    private final InternalSigner signer;

    public WebChatChannelExecutor(
            Function<String, String> conversationIds,
            InternalSigner signer) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                JsonUtils.standardMapper(),
                URI.create(KompileServiceEndpoints.urlForPath("/api/chat", null)),
                conversationIds,
                signer);
    }

    WebChatChannelExecutor(
            HttpClient client,
            ObjectMapper mapper,
            URI chatEndpoint,
            Function<String, String> conversationIds,
            InternalSigner signer) {
        this.client = client;
        this.mapper = mapper;
        String value = chatEndpoint.toString();
        this.chatEndpoint = URI.create(value.endsWith("/")
                ? value.substring(0, value.length() - 1) : value);
        this.conversationIds = conversationIds;
        this.signer = signer;
    }

    public boolean isAvailable() {
        try {
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long timestamp = Instant.now().getEpochSecond();
            String nonce = UUID.randomUUID().toString();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(chatEndpoint + "/channel/status"))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .header(ChannelInternalAuthentication.TIMESTAMP_HEADER,
                                    Long.toString(timestamp))
                            .header(ChannelInternalAuthentication.NONCE_HEADER, nonce)
                            .header(ChannelInternalAuthentication.SIGNATURE_HEADER,
                                    signer.sign(body, timestamp, nonce))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && mapper.readTree(response.body()).path("available").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        try {
            String conversationId = conversationIds.apply(request.getSessionKey());
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "conversationId", conversationId,
                    "message", request.getMessage()));
            long timestamp = Instant.now().getEpochSecond();
            String nonce = UUID.randomUUID().toString();
            String signature = signer.sign(body, timestamp, nonce);
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(chatEndpoint + "/channel"))
                            .timeout(Duration.ofMinutes(5))
                            .header("Content-Type", "application/json")
                            .header(ChannelInternalAuthentication.TIMESTAMP_HEADER,
                                    Long.toString(timestamp))
                            .header(ChannelInternalAuthentication.NONCE_HEADER, nonce)
                            .header(ChannelInternalAuthentication.SIGNATURE_HEADER, signature)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || json.path("error").isTextual()) {
                String error = json.path("error").asText(
                        "Web chat returned HTTP " + response.statusCode());
                return AgentResponse.error(error);
            }
            return AgentResponse.builder()
                    .success(true)
                    .response(json.path("answer").asText())
                    .sessionKey(request.getSessionKey())
                    .agentId(request.getAgentId())
                    .timestamp(Instant.now())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResponse.error("Web chat request was interrupted");
        } catch (Exception e) {
            return AgentResponse.error("Web chat is unavailable: " + e.getMessage());
        }
    }
}
