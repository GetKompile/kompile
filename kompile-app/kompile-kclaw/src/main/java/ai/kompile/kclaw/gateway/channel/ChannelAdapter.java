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

public interface ChannelAdapter {

    String getChannelName();

    void start();

    void stop();

    boolean isRunning();

    AdapterConfig getAdapterConfig();

    void updateConfig(AdapterConfig config);

    record AdapterConfig(
            String channelId,
            String agentId,
            boolean enabled,
            String sessionKeyPrefix,
            int maxMessageLength,
            boolean allowFileUploads,
            boolean allowVoiceMessages
    ) {
        public static AdapterConfig defaults(String channelId, String agentId) {
            return new AdapterConfig(
                    channelId, agentId, true,
                    channelId + ":", 4000, false, false
            );
        }
    }

    record IncomingMessage(
            String messageId,
            String userId,
            String userName,
            String content,
            String channelId,
            long timestamp,
            String replyToId,
            java.util.Map<String, Object> metadata
    ) {}

    record OutgoingMessage(
            String content,
            String replyToId,
            java.util.List<String> attachments
    ) {
        public static OutgoingMessage text(String content) {
            return new OutgoingMessage(content, null, null);
        }

        public static OutgoingMessage reply(String content, String replyToId) {
            return new OutgoingMessage(content, replyToId, null);
        }
    }

    interface MessageHandler {
        void handle(IncomingMessage message, MessageResponder responder);
    }

    interface MessageResponder {
        void reply(OutgoingMessage message);
        void replyError(String error);
        void typing();
    }
}
