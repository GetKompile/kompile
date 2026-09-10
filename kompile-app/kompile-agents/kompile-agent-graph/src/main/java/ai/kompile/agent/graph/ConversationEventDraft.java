/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, provider-neutral event input. Identity, sequence, and time are assigned by the store. */
public record ConversationEventDraft(
        ConversationEventType type,
        ConversationRole role,
        String content,
        Map<String, String> metadata,
        String idempotencyKey) {

    public static final int MAX_CONTENT_BYTES = 1024 * 1024;
    public static final int MAX_METADATA_ENTRIES = 64;
    public static final int MAX_METADATA_KEY_BYTES = 128;
    public static final int MAX_METADATA_VALUE_BYTES = 4096;
    public static final int MAX_IDEMPOTENCY_KEY_BYTES = 256;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]*");

    public ConversationEventDraft {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (utf8Length(content) > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "conversation event content exceeds " + MAX_CONTENT_BYTES + " UTF-8 bytes");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (metadata != null) {
            if (metadata.size() > MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException(
                        "conversation event metadata exceeds " + MAX_METADATA_ENTRIES + " entries");
            }
            metadata.forEach((key, value) -> {
                Objects.requireNonNull(key, "metadata key");
                Objects.requireNonNull(value, "metadata value");
                if (key.isBlank() || utf8Length(key) > MAX_METADATA_KEY_BYTES) {
                    throw new IllegalArgumentException(
                            "conversation metadata key must be non-blank and at most "
                                    + MAX_METADATA_KEY_BYTES + " UTF-8 bytes");
                }
                if (utf8Length(value) > MAX_METADATA_VALUE_BYTES) {
                    throw new IllegalArgumentException(
                            "conversation metadata value exceeds " + MAX_METADATA_VALUE_BYTES
                                    + " UTF-8 bytes");
                }
                copy.put(key, value);
            });
        }
        metadata = Map.copyOf(copy);
        idempotencyKey = optionalIdempotencyKey(idempotencyKey);
    }

    public ConversationEventDraft(
            ConversationEventType type,
            ConversationRole role,
            String content,
            Map<String, String> metadata) {
        this(type, role, content, metadata, null);
    }

    public ConversationEventDraft(
            ConversationEventType type,
            ConversationRole role,
            String content) {
        this(type, role, content, Map.of(), null);
    }

    public ConversationEventDraft withIdempotencyKey(String key) {
        return new ConversationEventDraft(type, role, content, metadata, key);
    }

    static String requiredIdempotencyKey(String value) {
        String validated = optionalIdempotencyKey(value);
        if (validated == null) {
            throw new IllegalArgumentException("conversation event idempotencyKey is required");
        }
        return validated;
    }

    private static String optionalIdempotencyKey(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()
                || utf8Length(value) > MAX_IDEMPOTENCY_KEY_BYTES
                || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "conversation event idempotencyKey must use 1-"
                            + MAX_IDEMPOTENCY_KEY_BYTES
                            + " ASCII letters, digits, '.', '_', ':', or '-'");
        }
        return value;
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
