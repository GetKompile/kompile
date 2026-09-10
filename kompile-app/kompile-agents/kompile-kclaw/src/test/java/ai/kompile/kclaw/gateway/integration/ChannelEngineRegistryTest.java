/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.service.AgentExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelEngineRegistryTest {

    @Test
    void exposesReadinessAndRejectsUnavailableEngine() {
        AgentExecutor executor = request -> AgentResponse.builder().success(true).response("ok").build();
        ChannelEngineRegistry registry = new ChannelEngineRegistry()
                .register(ChannelChatEngine.REACT, "ReAct", "tools", () -> executor,
                        () -> true, () -> "ready")
                .register(ChannelChatEngine.WEB_CHAT, "Web", "rag", () -> executor,
                        () -> false, () -> "chat service unavailable");

        assertSame(executor, registry.require(ChannelChatEngine.REACT));
        assertThrows(IllegalStateException.class,
                () -> registry.require(ChannelChatEngine.WEB_CHAT));
        var descriptors = registry.descriptors();
        assertTrue(descriptors.stream()
                .filter(item -> item.engine() == ChannelChatEngine.REACT)
                .findFirst().orElseThrow().available());
        assertFalse(descriptors.stream()
                .filter(item -> item.engine() == ChannelChatEngine.WEB_CHAT)
                .findFirst().orElseThrow().available());
        assertEquals("not installed", descriptors.stream()
                .filter(item -> item.engine() == ChannelChatEngine.KOMPILE_CLI)
                .findFirst().orElseThrow().status());
    }
}
