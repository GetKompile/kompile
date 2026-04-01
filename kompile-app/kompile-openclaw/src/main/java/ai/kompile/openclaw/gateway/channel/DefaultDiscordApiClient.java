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
package ai.kompile.openclaw.gateway.channel;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class DefaultDiscordApiClient implements DiscordApiClient {

    private static final String API_BASE = "https://discord.com/api/v10";
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    private String botToken;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private final List<DiscordMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    public DefaultDiscordApiClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public DefaultDiscordApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(String botToken) {
        this.botToken = botToken;
        this.running = true;
        log.info("Discord API client started");
        notifyReady();
    }

    @Override
    public void stop() {
        this.running = false;
        log.info("Discord API client stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void sendMessage(String channelId, String content) {
        if (!running || botToken == null) {
            log.warn("Discord client not running or not configured");
            return;
        }

        String url = API_BASE + "/channels/" + channelId + "/messages";

        try {
            String body = objectMapper.writeValueAsString(Map.of("content", content));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("Failed to send Discord message to channel {}", channelId, e);
        }
    }

    @Override
    public void sendTyping(String channelId) {
        if (!running || botToken == null) return;

        String url = API_BASE + "/channels/" + channelId + "/typing";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bot " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Failed to send typing indicator", e);
        }
    }

    @Override
    public void addMessageHandler(DiscordMessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(DiscordMessageHandler handler) {
        handlers.remove(handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DiscordGuild> getGuilds() {
        if (!running || botToken == null) return List.of();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/users/@me/guilds"))
                    .header("Authorization", "Bot " + botToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<Map<String, Object>> guilds = objectMapper.readValue(response.body(), List.class);
                return guilds.stream()
                        .map(g -> new DiscordGuild(
                                (String) g.get("id"),
                                (String) g.get("name"),
                                (String) g.get("icon")
                        ))
                        .toList();
            }
        } catch (Exception e) {
            log.error("Failed to get Discord guilds", e);
        }
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DiscordChannel> getChannels(String guildId) {
        if (!running || botToken == null) return List.of();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/guilds/" + guildId + "/channels"))
                    .header("Authorization", "Bot " + botToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<Map<String, Object>> channels = objectMapper.readValue(response.body(), List.class);
                return channels.stream()
                        .filter(c -> "0".equals(c.get("type")) || "5".equals(c.get("type")))
                        .map(c -> new DiscordChannel(
                                (String) c.get("id"),
                                guildId,
                                (String) c.get("name"),
                                (String) c.get("type"),
                                ((Number) c.getOrDefault("position", 0)).intValue()
                        ))
                        .toList();
            }
        } catch (Exception e) {
            log.error("Failed to get Discord channels for guild {}", guildId, e);
        }
        return List.of();
    }

    public void notifyMessage(DiscordMessage message) {
        for (DiscordMessageHandler handler : handlers) {
            try {
                handler.onMessage(message);
            } catch (Exception e) {
                log.error("Error in Discord message handler", e);
            }
        }
    }

    private void notifyReady() {
        for (DiscordMessageHandler handler : handlers) {
            try {
                handler.onReady();
            } catch (Exception e) {
                log.error("Error in Discord ready handler", e);
            }
        }
    }

    private void notifyError(Throwable error) {
        for (DiscordMessageHandler handler : handlers) {
            try {
                handler.onError(error);
            } catch (Exception e) {
                log.error("Error in Discord error handler", e);
            }
        }
    }
}
