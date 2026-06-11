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

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SlackMessageResponder implements ChannelAdapter.MessageResponder {

    private final SlackApiClient apiClient;
    private final String channelId;
    private final String threadTs;

    public SlackMessageResponder(SlackApiClient apiClient, String channelId, String threadTs) {
        this.apiClient = apiClient;
        this.channelId = channelId;
        this.threadTs = threadTs;
    }

    @Override
    public void reply(ChannelAdapter.OutgoingMessage message) {
        apiClient.sendMessage(channelId, message.content(), threadTs);
    }

    @Override
    public void replyError(String error) {
        apiClient.sendMessage(channelId, "Error: " + error, threadTs);
    }

    @Override
    public void typing() {
        apiClient.sendTyping(channelId);
    }
}
