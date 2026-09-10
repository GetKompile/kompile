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
import ai.kompile.gateway.core.gateway.channel.ChannelMessageChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
public class DefaultWhatsAppApiClient implements WhatsAppApiClient {

    private static final String API_BASE = "https://graph.facebook.com/v18.0";

    private String accessToken;
    private String phoneNumberId;
    private String verifyToken;
    private String appSecret;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private final List<WhatsAppMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    public DefaultWhatsAppApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        this.objectMapper = JsonUtils.standardMapper();
    }

    public DefaultWhatsAppApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** Validate access to the configured business phone before accepting the connection. */
    public boolean validateCredentials(String accessToken, String phoneNumberId) {
        if (accessToken == null || accessToken.isBlank()
                || phoneNumberId == null || phoneNumberId.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/" + phoneNumberId + "?fields=id"))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.warn("WhatsApp credential validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void start(String accessToken, String phoneNumberId, String verifyToken, String appSecret) {
        this.accessToken = accessToken;
        this.phoneNumberId = phoneNumberId;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
        this.running = true;
        log.info("WhatsApp API client started for phone number {}", phoneNumberId);
        notifyReady();
    }

    /**
     * Verifies the webhook challenge from Meta's webhook verification request.
     * Returns the challenge string if verification succeeds, null otherwise.
     */
    @Override
    public String verifyWebhook(String mode, String token, String challenge) {
        if ("subscribe".equals(mode) && constantTimeEquals(verifyToken, token)) {
            log.info("WhatsApp webhook verified");
            return challenge;
        }
        log.warn("WhatsApp webhook verification failed: mode={}, tokenMatch={}", mode, verifyToken != null && verifyToken.equals(token));
        return null;
    }

    @Override
    public boolean verifyWebhookSignature(byte[] payload, String signature) {
        if (payload == null || signature == null || appSecret == null || appSecret.isBlank()
                || !signature.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            log.warn("Could not validate WhatsApp webhook signature", e);
            return false;
        }
    }

    /**
     * Processes an incoming webhook payload from Meta's WhatsApp Business API.
     */
    @SuppressWarnings("unchecked")
    public void processWebhookPayload(Map<String, Object> body) {
        if (!running) return;

        try {
            List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entry");
            if (entries == null) return;

            for (Map<String, Object> entry : entries) {
                List<Map<String, Object>> changes = (List<Map<String, Object>>) entry.get("changes");
                if (changes == null) continue;

                for (Map<String, Object> change : changes) {
                    Map<String, Object> value = (Map<String, Object>) change.get("value");
                    if (value == null) continue;

                    // Handle incoming messages
                    List<Map<String, Object>> messages = (List<Map<String, Object>>) value.get("messages");
                    if (messages != null) {
                        Map<String, Object> contacts = null;
                        List<Map<String, Object>> contactList = (List<Map<String, Object>>) value.get("contacts");
                        if (contactList != null && !contactList.isEmpty()) {
                            contacts = contactList.get(0);
                        }

                        for (Map<String, Object> msg : messages) {
                            String fromName = contacts != null ?
                                    ((Map<String, Object>) contacts.getOrDefault("profile", Map.of()))
                                            .getOrDefault("name", "").toString() : "";

                            String textContent = "";
                            Map<String, Object> textObj = (Map<String, Object>) msg.get("text");
                            if (textObj != null) {
                                textContent = (String) textObj.get("body");
                            }

                            WhatsAppMessage waMsg = new WhatsAppMessage(
                                    (String) msg.get("id"),
                                    (String) msg.get("from"),
                                    fromName,
                                    textContent,
                                    msg.get("timestamp") != null ?
                                            Long.parseLong(msg.get("timestamp").toString()) * 1000 :
                                            System.currentTimeMillis(),
                                    (String) msg.get("id"),
                                    (String) msg.get("type"),
                                    (Map<String, Object>) msg.get("image"),
                                    (Map<String, Object>) msg.get("location")
                            );

                            notifyMessage(waMsg);
                        }
                    }

                    // Handle status updates
                    List<Map<String, Object>> statuses = (List<Map<String, Object>>) value.get("statuses");
                    if (statuses != null) {
                        for (Map<String, Object> status : statuses) {
                            notifyStatusUpdate(
                                    (String) status.get("id"),
                                    (String) status.get("status"),
                                    (String) status.get("recipient_id")
                            );
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process WhatsApp webhook payload", e);
        }
    }

    public String getVerifyToken() {
        return verifyToken;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void stop() {
        this.running = false;
        log.info("WhatsApp API client stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void sendTextMessage(String to, String text) {
        if (!running || accessToken == null) {
            throw new IllegalStateException("WhatsApp client is not running or configured");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "messaging_product", "whatsapp",
                    "recipient_type", "individual",
                    "to", to,
                    "type", "text",
                    "text", Map.of("body", text)
            ));

            String url = API_BASE + "/" + phoneNumberId + "/messages";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("WhatsApp rejected the message (HTTP "
                        + response.statusCode() + ")");
            }
        } catch (Exception e) {
            log.error("Failed to send WhatsApp message to {}", to, e);
            throw e instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Failed to send WhatsApp message", e);
        }
    }

    @Override
    public void sendReply(String to, String text, String messageId) {
        if (!running || accessToken == null) {
            throw new IllegalStateException("WhatsApp client is not running or configured");
        }

        try {
            String url = API_BASE + "/" + phoneNumberId + "/messages";
            for (String chunk : ChannelMessageChunker.split(text, 4096)) {
                String body = objectMapper.writeValueAsString(Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", to,
                        "type", "text",
                        "text", Map.of("body", chunk),
                        "context", Map.of("message_id", messageId)
                ));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<Void> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("WhatsApp rejected the reply (HTTP "
                            + response.statusCode() + ")");
                }
            }
        } catch (Exception e) {
            log.error("Failed to send WhatsApp reply", e);
            throw e instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Failed to send WhatsApp reply", e);
        }
    }

    @Override
    public void markAsRead(String messageId) {
        if (!running || accessToken == null) return;

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "messaging_product", "whatsapp",
                    "status", "read",
                    "message_id", messageId
            ));

            String url = API_BASE + "/" + phoneNumberId + "/messages";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Failed to mark message as read", e);
        }
    }

    @Override
    public void addMessageHandler(WhatsAppMessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(WhatsAppMessageHandler handler) {
        handlers.remove(handler);
    }

    public void notifyMessage(WhatsAppMessage message) {
        for (WhatsAppMessageHandler handler : handlers) {
            try {
                handler.onMessage(message);
            } catch (Exception e) {
                log.error("Error in WhatsApp message handler", e);
            }
        }
    }

    public void notifyStatusUpdate(String messageId, String status, String recipientId) {
        for (WhatsAppMessageHandler handler : handlers) {
            try {
                handler.onStatusUpdate(messageId, status, recipientId);
            } catch (Exception e) {
                log.error("Error in WhatsApp status handler", e);
            }
        }
    }

    private void notifyReady() {
        for (WhatsAppMessageHandler handler : handlers) {
            try {
                handler.onReady();
            } catch (Exception e) {
                log.error("Error in WhatsApp ready handler", e);
            }
        }
    }
}
