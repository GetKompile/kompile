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

import ai.kompile.gateway.core.gateway.channel.SlackApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class DefaultSlackApiClient implements SlackApiClient {

    private static final String API_BASE = "https://slack.com/api";

    private String botToken;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private final List<SlackMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    public DefaultSlackApiClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public DefaultSlackApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(String botToken, String appToken) {
        this.botToken = botToken;
        this.running = true;
        log.info("Slack API client started");
        notifyReady();
    }

    @Override
    public void stop() {
        this.running = false;
        log.info("Slack API client stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void sendMessage(String channelId, String text, String threadTs) {
        if (!running || botToken == null) {
            log.warn("Slack client not running or not configured");
            return;
        }

        try {
            Map<String, Object> bodyMap = new java.util.HashMap<>();
            bodyMap.put("channel", channelId);
            bodyMap.put("text", text);
            if (threadTs != null && !threadTs.isEmpty()) {
                bodyMap.put("thread_ts", threadTs);
            }

            String body = objectMapper.writeValueAsString(bodyMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/chat.postMessage"))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("Failed to send Slack message to channel {}", channelId, e);
        }
    }

    @Override
    public void sendEphemeral(String channelId, String userId, String text) {
        if (!running || botToken == null) return;

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "channel", channelId,
                    "user", userId,
                    "text", text
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/chat.postEphemeral"))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("Failed to send ephemeral Slack message", e);
        }
    }

    @Override
    public void sendTyping(String channelId) {
        if (!running || botToken == null) return;

        try {
            String body = objectMapper.writeValueAsString(Map.of("channel", channelId));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/conversations.typing"))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Failed to send typing indicator", e);
        }
    }

    @Override
    public void addMessageHandler(SlackMessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(SlackMessageHandler handler) {
        handlers.remove(handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SlackChannel> getChannels() {
        if (!running || botToken == null) return List.of();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/conversations.list?types=public_channel,private_channel"))
                    .header("Authorization", "Bearer " + botToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");

                if (channels != null) {
                    return channels.stream()
                            .map(c -> new SlackChannel(
                                    (String) c.get("id"),
                                    (String) c.get("name"),
                                    Boolean.TRUE.equals(c.get("is_private")),
                                    Boolean.TRUE.equals(c.get("is_member")),
                                    (String) c.get("purpose")
                            ))
                            .toList();
                }
            }
        } catch (Exception e) {
            log.error("Failed to get Slack channels", e);
        }
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SlackUser> getUsers() {
        if (!running || botToken == null) return List.of();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/users.list"))
                    .header("Authorization", "Bearer " + botToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");

                if (members != null) {
                    return members.stream()
                            .filter(m -> !Boolean.TRUE.equals(m.get("deleted")))
                            .map(m -> {
                                Map<String, Object> profile = (Map<String, Object>) m.get("profile");
                                return new SlackUser(
                                        (String) m.get("id"),
                                        (String) m.get("name"),
                                        (String) m.get("real_name"),
                                        profile != null ? (String) profile.get("image_48") : null,
                                        Boolean.TRUE.equals(m.get("is_bot"))
                                );
                            })
                            .toList();
                }
            }
        } catch (Exception e) {
            log.error("Failed to get Slack users", e);
        }
        return List.of();
    }

    public void notifyMessage(SlackMessage message, boolean isMention) {
        for (SlackMessageHandler handler : handlers) {
            try {
                if (isMention) {
                    handler.onAppMention(message);
                } else {
                    handler.onMessage(message);
                }
            } catch (Exception e) {
                log.error("Error in Slack message handler", e);
            }
        }
    }

    private void notifyReady() {
        for (SlackMessageHandler handler : handlers) {
            try {
                handler.onReady();
            } catch (Exception e) {
                log.error("Error in Slack ready handler", e);
            }
        }
    }
}
