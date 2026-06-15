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
package ai.kompile.gateway.core.gateway.channel;

import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.service.AgentExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared base class for channel adapters in both kompile-kclaw and kompile-openclaw.
 * Uses the {@link AgentExecutor} interface so the adapter does not depend on any
 * specific agent-service implementation.
 */
@Slf4j
public abstract class BaseChannelAdapter implements ChannelAdapter {

    protected final AgentExecutor agentExecutor;
    protected final Map<String, AdapterConfig> channelConfigs = new ConcurrentHashMap<>();
    protected volatile boolean running = false;

    protected BaseChannelAdapter(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    @Override
    public void start() {
        if (running) {
            log.warn("{} adapter already running", getChannelName());
            return;
        }
        running = true;
        doStart();
        log.info("{} adapter started", getChannelName());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        doStop();
        log.info("{} adapter stopped", getChannelName());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void updateConfig(AdapterConfig config) {
        channelConfigs.put(config.channelId(), config);
        log.info("Updated config for channel: {}", config.channelId());
    }

    protected abstract void doStart();

    protected abstract void doStop();

    protected MessageHandler createAgentHandler() {
        return (message, responder) -> {
            AdapterConfig config = channelConfigs.get(message.channelId());
            if (config == null || !config.enabled()) {
                log.warn("No config or disabled for channel: {}", message.channelId());
                return;
            }

            String sessionKey = buildSessionKey(config, message);
            String agentId = config.agentId();

            responder.typing();

            try {
                AgentRequest request = AgentRequest.builder()
                        .agentId(agentId)
                        .sessionKey(sessionKey)
                        .message(truncateMessage(message.content(), config.maxMessageLength()))
                        .metadata(Map.of(
                                "channel", getChannelName(),
                                "userId", message.userId(),
                                "userName", message.userName()
                        ))
                        .build();

                AgentResponse response = agentExecutor.execute(request);

                if (response.isSuccess()) {
                    responder.reply(OutgoingMessage.reply(response.getResponse(), message.messageId()));
                } else {
                    responder.replyError(response.getError() != null ? response.getError() : "An error occurred");
                }

            } catch (Exception e) {
                log.error("Error processing message from {}: {}", getChannelName(), message.messageId(), e);
                responder.replyError("Internal error processing your request");
            }
        };
    }

    protected String buildSessionKey(AdapterConfig config, IncomingMessage message) {
        String prefix = config.sessionKeyPrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = config.channelId() + ":";
        }
        return prefix + message.userId();
    }

    protected String truncateMessage(String content, int maxLength) {
        if (content == null) return "";
        if (maxLength <= 0 || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }
}
