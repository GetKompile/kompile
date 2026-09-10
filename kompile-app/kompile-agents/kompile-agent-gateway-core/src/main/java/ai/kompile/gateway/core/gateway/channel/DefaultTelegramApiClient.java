/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Token-redacting, typed Telegram Bot API client. */
@Slf4j
public class DefaultTelegramApiClient implements TelegramApiClient {

    private static final String API_ROOT = "https://api.telegram.org/bot";

    private final String botToken;
    private final String apiBase;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DefaultTelegramApiClient(String botToken) {
        this(botToken,
                API_ROOT + botToken,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                JsonUtils.standardMapper());
    }

    public DefaultTelegramApiClient(
            String botToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this(botToken, API_ROOT + botToken, httpClient, objectMapper);
    }

    DefaultTelegramApiClient(
            String botToken, String apiBase, HttpClient httpClient, ObjectMapper objectMapper) {
        this.botToken = botToken;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** Validate and return the bot identity synchronously. */
    @Override
    public TelegramBotIdentity getMe() {
        JsonNode result = get("/getMe", Duration.ofSeconds(15));
        return new TelegramBotIdentity(
                result.path("id").asLong(),
                result.path("username").asText(null),
                result.path("first_name").asText(null));
    }

    public boolean validateCredentials() {
        try {
            return getMe().id() > 0;
        } catch (RuntimeException e) {
            log.warn("Telegram credential validation failed ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public TelegramWebhookInfo getWebhookInfo() {
        JsonNode result = get("/getWebhookInfo", Duration.ofSeconds(15));
        String url = result.path("url").asText("");
        String host = "";
        if (!url.isBlank()) {
            try {
                host = URI.create(url).getHost();
            } catch (RuntimeException ignored) {
                host = "configured";
            }
        }
        return new TelegramWebhookInfo(
                !url.isBlank(),
                host == null ? "configured" : host,
                result.path("pending_update_count").asInt(0),
                capped(result.path("last_error_message").asText(null)));
    }

    @Override
    public void deleteWebhook(boolean dropPendingUpdates) {
        post("/deleteWebhook", Map.of("drop_pending_updates", dropPendingUpdates),
                Duration.ofSeconds(30));
    }

    @Override
    public List<TelegramUpdate> getUpdates(
            long offset, int limit, int timeout, List<String> allowedUpdates) {
        JsonNode result = post("/getUpdates", Map.of(
                "offset", offset,
                "limit", Math.max(1, Math.min(100, limit)),
                "timeout", Math.max(0, timeout),
                "allowed_updates", allowedUpdates == null ? List.of("message") : allowedUpdates),
                Duration.ofSeconds(Math.max(5, timeout + 5L)));
        return parseUpdates(result);
    }

    @Override
    public void sendMessage(String chatId, String text) {
        Object target = chatTarget(chatId);
        for (String chunk : ChannelMessageChunker.split(text, 4096)) {
            post("/sendMessage", Map.of("chat_id", target, "text", chunk),
                    Duration.ofSeconds(30));
        }
    }

    @Override
    public void sendChatAction(String chatId, String action) {
        try {
            post("/sendChatAction", Map.of("chat_id", chatTarget(chatId), "action", action),
                    Duration.ofSeconds(10));
        } catch (RuntimeException e) {
            log.debug("Failed to send Telegram chat action ({})", e.getClass().getSimpleName());
        }
    }

    List<TelegramUpdate> parseUpdates(JsonNode result) {
        List<TelegramUpdate> updates = new ArrayList<>();
        if (result == null || !result.isArray()) return updates;
        for (JsonNode item : result) {
            JsonNode message = item.path("message");
            if (message.isMissingNode() || message.isNull()) {
                message = item.path("edited_message");
            }
            updates.add(new TelegramUpdate(
                    item.path("update_id").asLong(),
                    message.isObject() ? parseMessage(message) : null));
        }
        return List.copyOf(updates);
    }

    private TelegramMessage parseMessage(JsonNode message) {
        JsonNode from = message.path("from");
        JsonNode chat = message.path("chat");
        TelegramUser user = from.isObject()
                ? new TelegramUser(
                        from.path("id").asLong(-1),
                        from.path("username").asText(null),
                        from.path("first_name").asText(null),
                        from.path("last_name").asText(null))
                : null;
        TelegramChat telegramChat = chat.isObject()
                ? new TelegramChat(
                        chat.path("id").asLong(-1),
                        chat.path("type").asText(null),
                        chat.path("title").asText(null))
                : null;
        return new TelegramMessage(
                message.path("message_id").asText(),
                user,
                telegramChat,
                message.path("text").asText(null),
                message.path("date").asLong(0),
                message.has("message_thread_id")
                        ? message.path("message_thread_id").asLong() : null);
    }

    private JsonNode get(String path, Duration timeout) {
        return exchange(HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(timeout)
                .GET()
                .build());
    }

    private JsonNode post(String path, Object body, Duration timeout) {
        try {
            return exchange(HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build());
        } catch (TelegramApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TelegramApiException(
                    "Telegram request could not be encoded", 0, 0, null);
        }
    }

    private JsonNode exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !envelope.path("ok").asBoolean(false)) {
                throw apiError(response.statusCode(), envelope);
            }
            return envelope.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException("Telegram request was interrupted", 0, 0, null);
        } catch (TelegramApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TelegramApiException(
                    "Telegram request failed (" + e.getClass().getSimpleName() + ")",
                    0, 0, null);
        }
    }

    private TelegramApiException apiError(int httpStatus, JsonNode envelope) {
        int errorCode = envelope.path("error_code").asInt(httpStatus);
        Integer retryAfter = envelope.path("parameters").has("retry_after")
                ? envelope.path("parameters").path("retry_after").asInt()
                : null;
        String description = capped(envelope.path("description").asText("Telegram rejected the request"));
        if (description != null && botToken != null) {
            description = description.replace(botToken, "[redacted]");
        }
        return new TelegramApiException(description, httpStatus, errorCode, retryAfter);
    }

    private static Object chatTarget(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("Telegram chat target is required");
        }
        String value = chatId.trim();
        if (value.matches("-?[0-9]+")) return Long.parseLong(value);
        if (value.matches("@[A-Za-z0-9_]{5,32}")) return value;
        throw new IllegalArgumentException("Invalid Telegram chat id or username");
    }

    private static String capped(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
