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
package ai.kompile.gateway.core.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class DefaultTelegramApiClient implements TelegramApiClient {

    private static final String API_BASE = "https://api.telegram.org/bot";

    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DefaultTelegramApiClient(String botToken) {
        this(botToken, HttpClient.newHttpClient(), JsonUtils.standardMapper());
    }

    public DefaultTelegramApiClient(String botToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this.botToken = botToken;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<TelegramUpdate> getUpdates(int offset, int timeout) {
        String url = API_BASE + botToken + "/getUpdates?offset=" + offset + "&timeout=" + timeout;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseUpdates(response.body());
            } else {
                log.error("Telegram API returned status: {}", response.statusCode());
                return List.of();
            }
        } catch (Exception e) {
            log.error("Failed to get Telegram updates", e);
            return List.of();
        }
    }

    @Override
    public void sendMessage(String chatId, String text) {
        String url = API_BASE + botToken + "/sendMessage";

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", Long.parseLong(chatId),
                    "text", text
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("Failed to send Telegram message to chat {}", chatId, e);
        }
    }

    @Override
    public void sendChatAction(String chatId, String action) {
        String url = API_BASE + botToken + "/sendChatAction";

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", Long.parseLong(chatId),
                    "action", action
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Failed to send chat action", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<TelegramUpdate> parseUpdates(String json) {
        List<TelegramUpdate> updates = new ArrayList<>();

        try {
            Map<String, Object> response = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");

            if (result != null) {
                for (Map<String, Object> item : result) {
                    updates.add(parseUpdate(item));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Telegram updates", e);
        }

        return updates;
    }

    @SuppressWarnings("unchecked")
    private TelegramUpdate parseUpdate(Map<String, Object> item) {
        int updateId = item.get("update_id") instanceof Number n ? n.intValue() : 0;
        Map<String, Object> msg = (Map<String, Object>) item.get("message");

        TelegramMessage message = null;
        if (msg != null) {
            message = parseMessage(msg);
        }

        return new TelegramUpdate(updateId, message);
    }

    @SuppressWarnings("unchecked")
    private TelegramMessage parseMessage(Map<String, Object> msg) {
        String messageId = String.valueOf(msg.get("message_id"));
        String text = (String) msg.get("text");
        Number dateNum = (Number) msg.get("date");
        long date = dateNum != null ? dateNum.longValue() : 0L;

        Map<String, Object> fromMap = (Map<String, Object>) msg.get("from");
        TelegramUser from = fromMap != null ? parseUser(fromMap) : null;

        Map<String, Object> chatMap = (Map<String, Object>) msg.get("chat");
        TelegramChat chat = chatMap != null ? parseChat(chatMap) : null;

        return new TelegramMessage(messageId, from, chat, text, date);
    }

    private TelegramUser parseUser(Map<String, Object> from) {
        return new TelegramUser(
                from.get("id") instanceof Number n ? n.longValue() : -1L,
                (String) from.get("username"),
                (String) from.get("first_name"),
                (String) from.get("last_name")
        );
    }

    private TelegramChat parseChat(Map<String, Object> chat) {
        return new TelegramChat(
                chat.get("id") instanceof Number n ? n.longValue() : -1L,
                (String) chat.get("type"),
                (String) chat.get("title")
        );
    }
}
