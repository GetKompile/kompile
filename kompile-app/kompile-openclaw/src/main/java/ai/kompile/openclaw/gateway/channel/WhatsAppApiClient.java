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

import java.util.List;
import java.util.Map;

public interface WhatsAppApiClient {

    void start(String accessToken, String phoneNumberId, String verifyToken);

    void stop();

    boolean isRunning();

    void sendTextMessage(String to, String text);

    void sendReply(String to, String text, String messageId);

    void markAsRead(String messageId);

    void addMessageHandler(WhatsAppMessageHandler handler);

    void removeMessageHandler(WhatsAppMessageHandler handler);

    record WhatsAppMessage(
            String id,
            String from,
            String fromName,
            String text,
            long timestamp,
            String messageId,
            String messageType,
            Map<String, Object> media,
            Map<String, Object> location
    ) {}

    interface WhatsAppMessageHandler {
        void onMessage(WhatsAppMessage message);
        void onStatusUpdate(String messageId, String status, String recipientId);
        void onReady();
        void onError(Throwable error);
    }
}
