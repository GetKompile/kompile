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

import ai.kompile.gateway.core.gateway.channel.DiscordApiClient;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class DefaultDiscordApiClient implements DiscordApiClient {

    private static final String API_BASE = "https://discord.com/api/v10";
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final int GATEWAY_DISPATCH = 0;
    private static final int GATEWAY_HEARTBEAT = 1;
    private static final int GATEWAY_IDENTIFY = 2;
    private static final int GATEWAY_HELLO = 10;
    private static final int GATEWAY_HEARTBEAT_ACK = 11;
    // Intent flags: GUILDS (1<<0) | GUILD_MESSAGES (1<<9) | MESSAGE_CONTENT (1<<15)
    private static final int INTENTS = (1 << 0) | (1 << 9) | (1 << 15);

    private String botToken;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private final List<DiscordMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    private WebSocket gatewayWs;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile int lastSequence = -1;
    private volatile boolean heartbeatAcked = true;

    public DefaultDiscordApiClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = JsonUtils.standardMapper();
    }

    public DefaultDiscordApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(String botToken) {
        this.botToken = botToken;
        this.running = true;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discord-heartbeat");
            t.setDaemon(true);
            return t;
        });
        connectGateway();
        log.info("Discord API client started — connecting to Gateway");
    }

    @Override
    public void stop() {
        this.running = false;
        if (gatewayWs != null) {
            gatewayWs.sendClose(WebSocket.NORMAL_CLOSURE, "stopping");
            gatewayWs = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        log.info("Discord API client stopped");
    }

    private void connectGateway() {
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(GATEWAY_URL), new GatewayListener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("Failed to connect to Discord Gateway", err);
                        notifyError(err);
                        scheduleReconnect();
                    } else {
                        this.gatewayWs = ws;
                        log.debug("Discord Gateway WebSocket connected");
                    }
                });
    }

    private void scheduleReconnect() {
        if (!running || heartbeatExecutor == null || heartbeatExecutor.isShutdown()) return;
        heartbeatExecutor.schedule(this::connectGateway, 5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void handleGatewayPayload(String text) {
        try {
            Map<String, Object> payload = objectMapper.readValue(text, Map.class);
            if (!(payload.get("op") instanceof Number opNum)) return;
            int op = opNum.intValue();
            Object d = payload.get("d");
            if (payload.get("s") instanceof Number seqNum) {
                lastSequence = seqNum.intValue();
            }

            switch (op) {
                case GATEWAY_HELLO -> {
                    if (!(d instanceof Map<?,?> rawData)) break;
                    Map<String, Object> data = (Map<String, Object>) rawData;
                    if (!(data.get("heartbeat_interval") instanceof Number hbNum)) break;
                    long heartbeatInterval = hbNum.longValue();
                    startHeartbeating(heartbeatInterval);
                    sendIdentify();
                }
                case GATEWAY_HEARTBEAT_ACK -> heartbeatAcked = true;
                case GATEWAY_DISPATCH -> handleDispatch((String) payload.get("t"), (Map<String, Object>) d);
                default -> log.debug("Discord Gateway op={}", op);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Discord Gateway payload", e);
        }
    }

    private void startHeartbeating(long intervalMs) {
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) return;
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!heartbeatAcked) {
                log.warn("Discord Gateway heartbeat not ACKed — reconnecting");
                if (gatewayWs != null) gatewayWs.sendClose(WebSocket.NORMAL_CLOSURE, "zombie");
                scheduleReconnect();
                return;
            }
            heartbeatAcked = false;
            String seq = lastSequence >= 0 ? String.valueOf(lastSequence) : "null";
            try {
                gatewayWs.sendText("{\"op\":1,\"d\":" + seq + "}", true);
            } catch (Exception e) {
                log.debug("Failed to send heartbeat", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendIdentify() {
        try {
            Map<String, Object> identify = Map.of(
                    "op", GATEWAY_IDENTIFY,
                    "d", Map.of(
                            "token", botToken,
                            "intents", INTENTS,
                            "properties", Map.of(
                                    "os", "linux",
                                    "browser", "kclaw",
                                    "device", "kclaw"
                            )
                    )
            );
            gatewayWs.sendText(objectMapper.writeValueAsString(identify), true);
        } catch (Exception e) {
            log.error("Failed to send IDENTIFY to Discord Gateway", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleDispatch(String eventType, Map<String, Object> data) {
        if (data == null) return;
        switch (eventType) {
            case "READY" -> {
                log.info("Discord Gateway READY");
                notifyReady();
            }
            case "MESSAGE_CREATE" -> {
                Map<String, Object> author = (Map<String, Object>) data.get("author");
                if (author == null || Boolean.TRUE.equals(author.get("bot"))) return;

                DiscordUser user = new DiscordUser(
                        (String) author.get("id"),
                        (String) author.get("username"),
                        (String) author.getOrDefault("discriminator", "0"),
                        (String) author.get("avatar"),
                        false
                );

                List<String> attachmentUrls = new ArrayList<>();
                List<Map<String, Object>> attachments = (List<Map<String, Object>>) data.get("attachments");
                if (attachments != null) {
                    for (Map<String, Object> a : attachments) {
                        attachmentUrls.add((String) a.get("url"));
                    }
                }

                String refId = null;
                Map<String, Object> ref = (Map<String, Object>) data.get("message_reference");
                if (ref != null) refId = (String) ref.get("message_id");

                DiscordMessage msg = new DiscordMessage(
                        (String) data.get("id"),
                        (String) data.get("channel_id"),
                        user,
                        (String) data.get("content"),
                        System.currentTimeMillis(),
                        refId,
                        attachmentUrls
                );
                notifyMessage(msg);
            }
            default -> log.debug("Discord event: {}", eventType);
        }
    }

    private class GatewayListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("Discord Gateway WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handleGatewayPayload(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Discord Gateway closed: {} {}", statusCode, reason);
            if (running) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Discord Gateway error", error);
            notifyError(error);
            if (running) scheduleReconnect();
        }
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
