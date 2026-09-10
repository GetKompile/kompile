/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable event read from the canonical per-agent conversation journal. */
public record ConversationEvent(
        int schemaVersion,
        UUID eventId,
        long sequence,
        Instant timestamp,
        ConversationEventType type,
        ConversationRole role,
        String content,
        Map<String, String> metadata,
        String idempotencyKey) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ConversationEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported conversation event schema version: " + schemaVersion);
        }
        Objects.requireNonNull(eventId, "eventId");
        if (sequence < 1) {
            throw new IllegalArgumentException("conversation event sequence must be positive");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        ConversationEventDraft validated = new ConversationEventDraft(
                type, role, content, metadata, idempotencyKey);
        type = validated.type();
        role = validated.role();
        content = validated.content();
        metadata = validated.metadata();
        idempotencyKey = validated.idempotencyKey();
    }

    public ConversationEvent(
            int schemaVersion,
            UUID eventId,
            long sequence,
            Instant timestamp,
            ConversationEventType type,
            ConversationRole role,
            String content,
            Map<String, String> metadata) {
        this(schemaVersion, eventId, sequence, timestamp, type, role, content, metadata, null);
    }

    ConversationEventDraft toDraft() {
        return new ConversationEventDraft(type, role, content, metadata, idempotencyKey);
    }
}
