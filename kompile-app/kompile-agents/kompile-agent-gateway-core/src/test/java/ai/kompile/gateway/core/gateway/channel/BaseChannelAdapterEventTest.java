/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.gateway.core.gateway.channel;

import ai.kompile.gateway.core.service.AgentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies the previously-cut wire: an inbound channel message published a
 * {@link ChannelMessageReceivedEvent} so {@code GraphUpdateChannelBridge} can drive
 * graph-update pipelines. Uses the shared dispatch path in {@link BaseChannelAdapter}.
 */
class BaseChannelAdapterEventTest {

    /** Minimal concrete adapter exercising the base dispatch path. */
    static class TestAdapter extends BaseChannelAdapter {
        TestAdapter(AgentExecutor executor) {
            super(executor);
        }

        @Override
        public String getChannelName() {
            return "test";
        }

        @Override
        protected void doStart() {
        }

        @Override
        protected void doStop() {
        }

        @Override
        public AdapterConfig getAdapterConfig() {
            return null;
        }
    }

    private ChannelAdapter.IncomingMessage message() {
        return new ChannelAdapter.IncomingMessage(
                "m1", "u1", "User One", "hello graph", "c1", 0L, null, Map.of());
    }

    @Test
    void inboundMessage_publishesChannelMessageReceivedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TestAdapter adapter = new TestAdapter(mock(AgentExecutor.class));
        adapter.setEventPublisher(publisher);

        adapter.createAgentHandler().handle(message(), mock(ChannelAdapter.MessageResponder.class));

        verify(publisher).publishEvent(any(ChannelMessageReceivedEvent.class));
    }

    @Test
    void inboundMessage_withoutPublisher_isNoOp() {
        TestAdapter adapter = new TestAdapter(mock(AgentExecutor.class));
        // No publisher wired (plain/test setup) — must not throw.
        adapter.createAgentHandler().handle(message(), mock(ChannelAdapter.MessageResponder.class));
    }
}
