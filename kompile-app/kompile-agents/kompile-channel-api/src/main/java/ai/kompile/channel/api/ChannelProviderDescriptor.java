/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.util.List;
import java.util.Set;

/** Describes one server-installed channel provider without exposing provider implementation types. */
public record ChannelProviderDescriptor(
        String id,
        String displayName,
        String description,
        Set<Capability> capabilities,
        List<Field> settings,
        List<Field> secrets) {

    public ChannelProviderDescriptor {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        settings = settings == null ? List.of() : List.copyOf(settings);
        secrets = secrets == null ? List.of() : List.copyOf(secrets);
    }

    public enum Capability {
        INBOUND,
        OUTBOUND,
        POLLING,
        SOCKET,
        WEBHOOK
    }

    public enum FieldType {
        STRING,
        INTEGER,
        BOOLEAN,
        STRING_LIST,
        LONG_LIST
    }

    /** A descriptor-driven field rendered by both the CLI and the web console. */
    public record Field(
            String name,
            String label,
            FieldType type,
            boolean required,
            Object defaultValue,
            String description,
            String environmentHint) {
    }
}
