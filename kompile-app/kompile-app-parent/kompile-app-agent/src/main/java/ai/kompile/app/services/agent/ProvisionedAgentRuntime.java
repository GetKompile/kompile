/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services.agent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provider-neutral capability for one app-main-owned provisioned agent runtime.
 *
 * <p>The caller supplies only the canonical provisioned-agent UUID, an opaque external
 * conversation key, and turn data. The implementation binds the local owner and all storage
 * locations. Tool arguments are deliberately selector-free: no owner, agent, graph, fact-sheet,
 * knowledge-base, or path selector can be supplied by a model.</p>
 */
public interface ProvisionedAgentRuntime {

    int MAX_QUERY_CHARACTERS = 16_384;
    int MAX_HTTP_BODY_BYTES = 512 * 1_024;
    int MAX_CONVERSATION_KEY_BYTES = 4_096;
    int MAX_EVENT_CONTENT_BYTES = 64 * 1_024;
    int MAX_METADATA_ENTRIES = 8;
    int MAX_METADATA_KEY_BYTES = 128;
    int MAX_METADATA_VALUE_BYTES = 512;
    int MAX_IDEMPOTENCY_KEY_BYTES = 256;
    int MAX_AUTOMATIC_CONTEXT_CHARACTERS = 8_192;
    int MAX_CONTEXT_HISTORY_EVENTS = 32;
    int MAX_CONTEXT_EVENT_CONTENT_BYTES = 1_024;
    int MAX_CONTEXT_METADATA_ENTRIES = 4;
    int MAX_CONTEXT_METADATA_VALUE_BYTES = 64;
    int MAX_TOOL_RESULT_CHARACTERS = 24_000;

    Pattern REVISION = Pattern.compile("[0-9a-f]{64}");

    RuntimeContext prepare(PrepareRequest request);

    CanonicalEvent append(AppendEventsRequest request);

    ToolExecutionResult executeTool(ToolExecutionRequest request);

    record PrepareRequest(
            String provisionedAgentId,
            String externalConversationKey,
            String query) {
        public PrepareRequest {
            provisionedAgentId = canonicalAgentId(provisionedAgentId);
            externalConversationKey = boundedConversationKey(externalConversationKey);
            query = requiredBounded(query, "query", MAX_QUERY_CHARACTERS);
        }
    }

    record RuntimeContext(
            String provisionedAgentId,
            String externalConversationKey,
            String automaticContext,
            String graphRevision,
            List<CanonicalEvent> history,
            long totalHistoryEvents,
            boolean historyTruncated,
            ToolDescriptor privateGraphTool) {
        public RuntimeContext {
            provisionedAgentId = canonicalAgentId(provisionedAgentId);
            externalConversationKey = boundedConversationKey(externalConversationKey);
            automaticContext = automaticContext == null ? "" : automaticContext;
            if (automaticContext.length() > MAX_AUTOMATIC_CONTEXT_CHARACTERS) {
                throw new IllegalArgumentException("automatic context exceeds runtime limit");
            }
            graphRevision = revision(graphRevision);
            history = history == null ? List.of() : List.copyOf(history);
            if (history.size() > MAX_CONTEXT_HISTORY_EVENTS) {
                throw new IllegalArgumentException("projected history exceeds runtime event limit");
            }
            for (CanonicalEvent event : history) {
                if (utf8Length(event.content()) > MAX_CONTEXT_EVENT_CONTENT_BYTES) {
                    throw new IllegalArgumentException(
                            "projected history event content exceeds runtime limit");
                }
                if (event.metadata().size() > MAX_CONTEXT_METADATA_ENTRIES) {
                    throw new IllegalArgumentException(
                            "projected history event metadata exceeds runtime entry limit");
                }
                event.metadata().forEach((key, value) -> {
                    if (utf8Length(value) > MAX_CONTEXT_METADATA_VALUE_BYTES) {
                        throw new IllegalArgumentException(
                                "projected history event metadata value exceeds runtime limit");
                    }
                });
            }
            if (totalHistoryEvents < history.size()) {
                throw new IllegalArgumentException("totalHistoryEvents is smaller than projected history");
            }
            if (historyTruncated != (totalHistoryEvents > history.size())) {
                throw new IllegalArgumentException("historyTruncated must report omitted events");
            }
            Objects.requireNonNull(privateGraphTool, "privateGraphTool");
        }
    }

    record CanonicalEvent(
            long sequence,
            Instant timestamp,
            EventKind kind,
            EventRole role,
            String content,
            Map<String, String> metadata,
            String idempotencyKey) {
        public CanonicalEvent {
            if (sequence < 1) {
                throw new IllegalArgumentException("event sequence must be positive");
            }
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(role, "role");
            content = boundedEventContent(content);
            metadata = boundedMetadata(metadata);
            idempotencyKey = optionalIdempotencyKey(idempotencyKey);
        }

        public CanonicalEvent(
                long sequence,
                Instant timestamp,
                EventKind kind,
                EventRole role,
                String content,
                Map<String, String> metadata) {
            this(sequence, timestamp, kind, role, content, metadata, null);
        }
    }

    record EventDraft(
            EventKind kind,
            EventRole role,
            String content,
            Map<String, String> metadata,
            String idempotencyKey) {
        public EventDraft {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(role, "role");
            content = boundedEventContent(content);
            metadata = boundedMetadata(metadata);
            idempotencyKey = optionalIdempotencyKey(idempotencyKey);
        }

        public EventDraft(
                EventKind kind,
                EventRole role,
                String content,
                Map<String, String> metadata) {
            this(kind, role, content, metadata, null);
        }

        public EventDraft(EventKind kind, EventRole role, String content) {
            this(kind, role, content, Map.of(), null);
        }

        public EventDraft withIdempotencyKey(String key) {
            return new EventDraft(kind, role, content, metadata, key);
        }
    }

    record AppendEventsRequest(
            String provisionedAgentId,
            String externalConversationKey,
            EventDraft event) {
        public AppendEventsRequest {
            provisionedAgentId = canonicalAgentId(provisionedAgentId);
            externalConversationKey = boundedConversationKey(externalConversationKey);
            event = Objects.requireNonNull(event, "event");
            requiredIdempotencyKey(event.idempotencyKey());
        }
    }

    record ToolDescriptor(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean mutationCapable) {
        public ToolDescriptor {
            name = requiredBounded(name, "tool name", 128);
            description = requiredBounded(description, "tool description", 2_048);
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    record ToolExecutionRequest(
            String provisionedAgentId,
            Map<String, Object> arguments) {
        public ToolExecutionRequest {
            provisionedAgentId = canonicalAgentId(provisionedAgentId);
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            if (arguments.isEmpty() || arguments.size() > 32) {
                throw new IllegalArgumentException("tool arguments must contain between 1 and 32 entries");
            }
        }
    }

    record ToolExecutionResult(String result, String graphRevision) {
        public ToolExecutionResult {
            Objects.requireNonNull(result, "result");
            if (result.length() > MAX_TOOL_RESULT_CHARACTERS) {
                throw new IllegalArgumentException("tool result exceeds runtime limit");
            }
            graphRevision = revision(graphRevision);
        }
    }

    enum EventKind {
        MESSAGE,
        CONTEXT,
        TOOL_CALL,
        TOOL_RESULT,
        ERROR,
        CANCELLED,
        COMPACTION,
        MIGRATION
    }

    enum EventRole {
        USER,
        ASSISTANT,
        SYSTEM,
        CONTEXT,
        TOOL
    }

    final class RuntimeException extends java.lang.RuntimeException {
        private final int statusCode;

        public RuntimeException(String message) {
            this(500, message, null);
        }

        public RuntimeException(int statusCode, String message) {
            this(statusCode, message, null);
        }

        public RuntimeException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    static String canonicalTurnId(String value) {
        return canonicalUuid(value, "turnId");
    }

    private static String canonicalAgentId(String value) {
        return canonicalUuid(value, "provisionedAgentId");
    }

    private static String canonicalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(
                        field + " must use canonical lowercase UUID form");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null
                    && invalid.getMessage().startsWith(field)) {
                throw invalid;
            }
            throw new IllegalArgumentException(field + " is not a canonical UUID", invalid);
        }
    }

    private static String boundedConversationKey(String value) {
        Objects.requireNonNull(value, "externalConversationKey");
        if (value.isBlank() || utf8Length(value) > MAX_CONVERSATION_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "externalConversationKey must be non-blank and at most "
                            + MAX_CONVERSATION_KEY_BYTES + " UTF-8 bytes");
        }
        return value;
    }

    private static String boundedEventContent(String value) {
        Objects.requireNonNull(value, "event content");
        if (utf8Length(value) > MAX_EVENT_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "event content exceeds " + MAX_EVENT_CONTENT_BYTES + " UTF-8 bytes");
        }
        return value;
    }

    private static String optionalIdempotencyKey(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()
                || utf8Length(value) > MAX_IDEMPOTENCY_KEY_BYTES
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("invalid event idempotencyKey");
        }
        return value;
    }

    private static String requiredIdempotencyKey(String value) {
        String validated = optionalIdempotencyKey(value);
        if (validated == null) {
            throw new IllegalArgumentException("event idempotencyKey is required");
        }
        return validated;
    }

    private static Map<String, String> boundedMetadata(Map<String, String> value) {
        Map<String, String> metadata = value == null ? Map.of() : Map.copyOf(value);
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("event metadata exceeds " + MAX_METADATA_ENTRIES + " entries");
        }
        metadata.forEach((key, item) -> {
            Objects.requireNonNull(key, "metadata key");
            Objects.requireNonNull(item, "metadata value");
            if (key.isBlank() || utf8Length(key) > MAX_METADATA_KEY_BYTES) {
                throw new IllegalArgumentException("invalid event metadata key");
            }
            if (utf8Length(item) > MAX_METADATA_VALUE_BYTES) {
                throw new IllegalArgumentException("event metadata value exceeds runtime limit");
            }
        });
        return metadata;
    }

    private static String requiredBounded(String value, String field, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return value;
    }

    private static String revision(String value) {
        if (value == null || !REVISION.matcher(value).matches()) {
            throw new IllegalArgumentException("graphRevision must be a lowercase SHA-256 value");
        }
        return value;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
