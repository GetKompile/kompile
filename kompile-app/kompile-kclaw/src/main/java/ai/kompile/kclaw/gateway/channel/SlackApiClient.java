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

import java.util.List;
import java.util.Map;

public interface SlackApiClient {

    void start(String botToken, String appToken);

    void stop();

    boolean isRunning();

    void sendMessage(String channelId, String text, String threadTs);

    void sendEphemeral(String channelId, String userId, String text);

    void sendTyping(String channelId);

    void addMessageHandler(SlackMessageHandler handler);

    void removeMessageHandler(SlackMessageHandler handler);

    List<SlackChannel> getChannels();

    List<SlackUser> getUsers();

    record SlackChannel(
            String id,
            String name,
            boolean isPrivate,
            boolean isMember,
            String purpose
    ) {}

    record SlackUser(
            String id,
            String name,
            String realName,
            String profileImage,
            boolean isBot
    ) {}

    record SlackMessage(
            String ts,
            String channelId,
            String userId,
            String userName,
            String text,
            String threadTs,
            String subtype,
            Map<String, Object> files,
            Map<String, Object> metadata
    ) {}

    interface SlackMessageHandler {
        void onMessage(SlackMessage message);
        void onAppMention(SlackMessage message);
        void onReady();
        void onError(Throwable error);
    }
}
