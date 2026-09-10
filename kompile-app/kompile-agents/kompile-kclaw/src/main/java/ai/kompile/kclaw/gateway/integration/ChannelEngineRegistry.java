/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.gateway.core.service.AgentExecutor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Runtime registry for the chat engines selectable by channel connections. */
public final class ChannelEngineRegistry {

    private final Map<ChannelChatEngine, Registration> registrations =
            new EnumMap<>(ChannelChatEngine.class);

    public ChannelEngineRegistry register(
            ChannelChatEngine engine,
            String displayName,
            String description,
            Supplier<AgentExecutor> executor,
            BooleanSupplier available,
            Supplier<String> status) {
        Registration registration = new Registration(
                displayName, description, executor, available, status);
        if (registrations.putIfAbsent(engine, registration) != null) {
            throw new IllegalStateException("Duplicate channel chat engine: " + engine);
        }
        return this;
    }

    public AgentExecutor require(ChannelChatEngine engine) {
        Registration registration = registrations.get(engine);
        if (registration == null || !registration.available().getAsBoolean()) {
            throw new IllegalStateException("Channel chat engine is unavailable: " + engine);
        }
        AgentExecutor executor = registration.executor().get();
        if (executor == null) {
            throw new IllegalStateException("Channel chat engine is unavailable: " + engine);
        }
        return executor;
    }

    public List<ChannelEngineDescriptor> descriptors() {
        return java.util.Arrays.stream(ChannelChatEngine.values())
                .map(engine -> descriptor(engine, registrations.get(engine)))
                .toList();
    }

    private static ChannelEngineDescriptor descriptor(
            ChannelChatEngine engine, Registration registration) {
        if (registration == null) {
            return new ChannelEngineDescriptor(
                    engine, engine.name(), "Not installed",
                    engine == ChannelChatEngine.REACT,
                    engine == ChannelChatEngine.KOMPILE_CLI,
                    false, "not installed");
        }
        boolean available;
        String status;
        try {
            available = registration.available().getAsBoolean()
                    && registration.executor().get() != null;
            status = available ? "ready" : registration.status().get();
        } catch (RuntimeException e) {
            available = false;
            status = e.getMessage();
        }
        return new ChannelEngineDescriptor(
                engine,
                registration.displayName(),
                registration.description(),
                engine == ChannelChatEngine.REACT,
                engine == ChannelChatEngine.KOMPILE_CLI,
                available,
                status == null ? (available ? "ready" : "unavailable") : status);
    }

    private record Registration(
            String displayName,
            String description,
            Supplier<AgentExecutor> executor,
            BooleanSupplier available,
            Supplier<String> status) {
        private Registration {
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(description);
            Objects.requireNonNull(executor);
            Objects.requireNonNull(available);
            Objects.requireNonNull(status);
        }
    }
}
