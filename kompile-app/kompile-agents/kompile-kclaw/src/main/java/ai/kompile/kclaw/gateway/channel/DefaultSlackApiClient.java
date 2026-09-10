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
package ai.kompile.kclaw.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.gateway.channel.SlackApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class DefaultSlackApiClient implements SlackApiClient {

    private static final String API_BASE = "https://slack.com/api";

    private String botToken;
    private String appToken;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private final List<SlackMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private final java.util.concurrent.atomic.AtomicLong lifecycleGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile List<SlackUser> cachedUsers = List.of();
    private volatile long usersCacheExpiresNanos;

    private WebSocket socketModeWs;
    private ScheduledExecutorService reconnectExecutor;

    public DefaultSlackApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        this.objectMapper = JsonUtils.standardMapper();
    }

    public DefaultSlackApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** Validate bot and optional Socket Mode tokens before starting background consumers. */
    public boolean validateCredentials(String botToken, String appToken) {
        return validateToken("/auth.test", botToken)
                && (appToken == null || appToken.isBlank()
                        || validateToken("/apps.connections.open", appToken));
    }

    private boolean validateToken(String path, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + path))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    && objectMapper.readTree(response.body()).path("ok").asBoolean(false);
        } catch (Exception e) {
            log.warn("Slack credential validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void start(String botToken, String appToken) {
        long generation = lifecycleGeneration.incrementAndGet();
        this.botToken = botToken;
        this.appToken = appToken;
        this.cachedUsers = List.of();
        this.usersCacheExpiresNanos = 0L;
        this.running = true;
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slack-socketmode");
            t.setDaemon(true);
            return t;
        });

        if (appToken != null && !appToken.isBlank()) {
            connectSocketMode(generation);
            log.info("Slack API client started with Socket Mode");
        } else {
            log.info("Slack API client started (outbound only — no app token for Socket Mode)");
            notifyReady();
        }
    }

    @Override
    public void stop() {
        lifecycleGeneration.incrementAndGet();
        this.running = false;
        if (socketModeWs != null) {
            socketModeWs.sendClose(WebSocket.NORMAL_CLOSURE, "stopping");
            socketModeWs = null;
        }
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
            reconnectExecutor = null;
        }
        log.info("Slack API client stopped");
    }

    private void connectSocketMode(long generation) {
        try {
            // Request a Socket Mode WebSocket URL
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/apps.connections.open"))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + appToken)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (!running || lifecycleGeneration.get() != generation) return;
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                            if (Boolean.TRUE.equals(result.get("ok"))) {
                                String wsUrl = (String) result.get("url");
                                openSocketModeConnection(wsUrl, generation);
                            } else {
                                log.error("Slack apps.connections.open failed: {}", result.get("error"));
                                notifyError(new IllegalStateException(
                                        "Slack apps.connections.open failed: " + result.get("error")));
                                scheduleReconnect(generation);
                            }
                        } catch (Exception e) {
                            log.error("Failed to parse Socket Mode connection response", e);
                            notifyError(e);
                            scheduleReconnect(generation);
                        }
                    })
                    .exceptionally(err -> {
                        if (!running || lifecycleGeneration.get() != generation) return null;
                        log.error("Failed to request Socket Mode connection", err);
                        notifyError(err);
                        scheduleReconnect(generation);
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to initiate Socket Mode connection", e);
            notifyError(e);
            scheduleReconnect(generation);
        }
    }

    private void openSocketModeConnection(String wsUrl, long generation) {
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new SocketModeListener(generation))
                .whenComplete((ws, err) -> {
                    if (!running || lifecycleGeneration.get() != generation) {
                        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "stale generation");
                        return;
                    }
                    if (err != null) {
                        log.error("Failed to open Slack Socket Mode WebSocket", err);
                        notifyError(err);
                        scheduleReconnect(generation);
                    } else {
                        this.socketModeWs = ws;
                        log.info("Slack Socket Mode WebSocket connected");
                        notifyReady();
                    }
                });
    }

    private void scheduleReconnect(long generation) {
        if (!running || lifecycleGeneration.get() != generation
                || reconnectExecutor == null || reconnectExecutor.isShutdown()) return;
        reconnectExecutor.schedule(() -> connectSocketMode(generation), 5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void handleSocketModePayload(String text, long generation) {
        if (!running || lifecycleGeneration.get() != generation) return;
        try {
            Map<String, Object> envelope = objectMapper.readValue(text, Map.class);
            String type = (String) envelope.get("type");
            String envelopeId = (String) envelope.get("envelope_id");

            // Always acknowledge the envelope
            if (envelopeId != null && socketModeWs != null) {
                socketModeWs.sendText(
                        objectMapper.writeValueAsString(Map.of("envelope_id", envelopeId)),
                        true);
            }

            if ("events_api".equals(type)) {
                Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
                if (payload != null) {
                    Map<String, Object> event = (Map<String, Object>) payload.get("event");
                    if (event != null) {
                        handleSlackEvent(event);
                    }
                }
            } else if ("disconnect".equals(type)) {
                log.info("Slack Socket Mode disconnect request — reconnecting");
                scheduleReconnect(generation);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Slack Socket Mode payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSlackEvent(Map<String, Object> event) {
        String eventType = (String) event.get("type");
        if (eventType == null) return;

        switch (eventType) {
            case "message" -> {
                // Ignore bot messages and subtypes like message_changed
                if (event.containsKey("bot_id") || event.containsKey("subtype")) return;

                SlackMessage msg = new SlackMessage(
                        (String) event.get("ts"),
                        (String) event.get("channel"),
                        (String) event.get("user"),
                        null, // username resolved lazily
                        (String) event.get("text"),
                        (String) event.get("thread_ts"),
                        (String) event.get("subtype"),
                        (Map<String, Object>) event.get("files"),
                        null
                );
                notifyMessage(msg, false);
            }
            case "app_mention" -> {
                SlackMessage msg = new SlackMessage(
                        (String) event.get("ts"),
                        (String) event.get("channel"),
                        (String) event.get("user"),
                        null,
                        (String) event.get("text"),
                        (String) event.get("thread_ts"),
                        null,
                        null,
                        null
                );
                notifyMessage(msg, true);
            }
            default -> log.debug("Slack event: {}", eventType);
        }
    }

    private class SocketModeListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final long generation;

        private SocketModeListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!running || lifecycleGeneration.get() != generation) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "stale generation");
                return;
            }
            log.debug("Slack Socket Mode WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handleSocketModePayload(text, generation);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Slack Socket Mode closed: {} {}", statusCode, reason);
            if (running) scheduleReconnect(generation);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!running || lifecycleGeneration.get() != generation) return;
            log.error("Slack Socket Mode error", error);
            notifyError(error);
            if (running) scheduleReconnect(generation);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void sendMessage(String channelId, String text, String threadTs) {
        if (!running || botToken == null) {
            throw new IllegalStateException("Slack client is not running or configured");
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
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !objectMapper.readTree(response.body()).path("ok").asBoolean(false)) {
                throw new IllegalStateException("Slack rejected the message (HTTP "
                        + response.statusCode() + ")");
            }
        } catch (Exception e) {
            log.error("Failed to send Slack message to channel {}", channelId, e);
            throw e instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Failed to send Slack message", e);
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
                    .timeout(java.time.Duration.ofSeconds(30))
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
                    .timeout(java.time.Duration.ofSeconds(10))
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
                    .timeout(java.time.Duration.ofSeconds(30))
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
        long now = System.nanoTime();
        if (now < usersCacheExpiresNanos) return cachedUsers;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/users.list"))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + botToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");

                if (members != null) {
                    List<SlackUser> loaded = members.stream()
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
                    cachedUsers = loaded;
                    usersCacheExpiresNanos = now + TimeUnit.MINUTES.toNanos(5);
                    return loaded;
                }
            }
        } catch (Exception e) {
            log.error("Failed to get Slack users", e);
        }
        return cachedUsers;
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

    private void notifyError(Throwable error) {
        for (SlackMessageHandler handler : handlers) {
            try {
                handler.onError(error);
            } catch (Exception e) {
                log.error("Error in Slack error handler", e);
            }
        }
    }
}
