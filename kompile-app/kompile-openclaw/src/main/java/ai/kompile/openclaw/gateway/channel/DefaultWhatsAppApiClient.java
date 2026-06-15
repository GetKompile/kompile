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

import ai.kompile.gateway.core.gateway.channel.WhatsAppApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class DefaultWhatsAppApiClient implements WhatsAppApiClient {

    private static final String API_BASE = "https://graph.facebook.com/v18.0";

    private String accessToken;
    private String phoneNumberId;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private final List<WhatsAppMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    public DefaultWhatsAppApiClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public DefaultWhatsAppApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(String accessToken, String phoneNumberId, String verifyToken) {
        this.accessToken = accessToken;
        this.phoneNumberId = phoneNumberId;
        this.running = true;
        log.info("WhatsApp API client started for phone number {}", phoneNumberId);
        notifyReady();
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
            log.warn("WhatsApp client not running or not configured");
            return;
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
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("Failed to send WhatsApp message to {}", to, e);
        }
    }

    @Override
    public void sendReply(String to, String text, String messageId) {
        if (!running || accessToken == null) return;

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "messaging_product", "whatsapp",
                    "recipient_type", "individual",
                    "to", to,
                    "type", "text",
                    "text", Map.of("body", text),
                    "context", Map.of("message_id", messageId)
            ));

            String url = API_BASE + "/" + phoneNumberId + "/messages";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("Failed to send WhatsApp reply", e);
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
