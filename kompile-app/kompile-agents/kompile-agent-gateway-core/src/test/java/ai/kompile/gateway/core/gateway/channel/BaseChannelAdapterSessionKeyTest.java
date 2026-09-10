/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import ai.kompile.gateway.core.model.AgentResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BaseChannelAdapterSessionKeyTest {

    @Test
    void isolatesOneUsersHistoryAcrossProviderConversations() {
        FixtureAdapter adapter = new FixtureAdapter();
        ChannelAdapter.AdapterConfig config = new ChannelAdapter.AdapterConfig(
                "*", "jarvis", true, "telegram-ops:", 16000, false, false);
        ChannelAdapter.IncomingMessage privateChat = message("42", "7");
        ChannelAdapter.IncomingMessage groupChat = message("-1009", "7");

        assertEquals("telegram-ops:42:7", adapter.session(config, privateChat));
        assertNotEquals(adapter.session(config, privateChat), adapter.session(config, groupChat));
    }

    @Test
    void providerConversationKeySeparatesThreadsInOneChannel() {
        FixtureAdapter adapter = new FixtureAdapter();
        ChannelAdapter.AdapterConfig config = ChannelAdapter.AdapterConfig.defaults("*", "jarvis");
        ChannelAdapter.IncomingMessage first = new ChannelAdapter.IncomingMessage(
                "1", "7", "Alice", "one", "C01", 1L, null,
                Map.of("conversation_key", "C01:thread-1"));
        ChannelAdapter.IncomingMessage second = new ChannelAdapter.IncomingMessage(
                "2", "7", "Alice", "two", "C01", 2L, null,
                Map.of("conversation_key", "C01:thread-2"));

        assertNotEquals(adapter.session(config, first), adapter.session(config, second));
    }

    private static ChannelAdapter.IncomingMessage message(String channelId, String userId) {
        return new ChannelAdapter.IncomingMessage(
                "message", userId, "Alice", "hello", channelId,
                System.currentTimeMillis(), null, Map.of());
    }

    private static final class FixtureAdapter extends BaseChannelAdapter {
        private FixtureAdapter() {
            super(request -> AgentResponse.builder().success(true).response("ok").build());
        }

        private String session(AdapterConfig config, IncomingMessage message) {
            return buildSessionKey(config, message);
        }

        @Override public String getChannelName() { return "fixture"; }
        @Override public AdapterConfig getAdapterConfig() { return null; }
        @Override protected void doStart() { markReady(); }
        @Override protected void doStop() { }
        @Override public DeliveryResult send(String target, String content) {
            return DeliveryResult.accepted("ok");
        }
    }
}
