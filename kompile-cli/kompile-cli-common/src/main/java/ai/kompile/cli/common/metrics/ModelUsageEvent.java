/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Provider/runtime-reported usage from ONE model request whose execution is
 * attributable to a tool invocation (Task 7 wiring). Never synthesized from text
 * length or conversation totals. Fields describe exactly what the provider said,
 * including whether cache/reasoning tokens are INCLUDED in the base counts
 * (inclusion semantics vary per provider and must not be guessed).
 *
 * <p>Streaming providers may deliver cumulative totals per chunk; {@code cumulative}
 * marks which kind this event is so the report layer folds duplicates by
 * {@code providerRequestId} instead of double-summing.</p>
 */
public record ModelUsageEvent(
        String eventId,
        String invocationId,
        String provider,
        String modelId,
        String requestKind,
        Long inputTokens,
        Long outputTokens,
        Long reasoningTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Boolean reasoningIncludedInOutput,
        Boolean cacheIncludedInInput,
        boolean cumulative,
        Long totalTokens,
        Long latencyMs,
        Long epochMs,
        String providerRequestId,
        UsageReporting reporting) {

    public enum UsageReporting {
        /** Usage received from the provider response for this exact request. */
        PROVIDER_REPORTED,
        /** Received from a remote/forwarded peer; schema-validated, marked remote-reported. */
        REMOTE_REPORTED,
        /** Request completed but provider supplied no usage numbers. */
        NOT_REPORTED,
        /** Instrumentation not wired on this path. Unavailable — not zero. */
        UNINSTRUMENTED,
        /** Usage arrived after the synchronous response returned (persisted by correlation). */
        LATE
    }

    public ModelUsageEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId is required");
        }
    }

    public static ModelUsageEvent uninstrumented(String eventId, String invocationId) {
        return new ModelUsageEvent(eventId, invocationId, null, null, null,
                null, null, null, null, null, null, null, false, null, null, null, null,
                UsageReporting.UNINSTRUMENTED);
    }

    public ObjectNode toJsonNode(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("eventId", eventId);
        node.put("invocationId", invocationId);
        if (provider != null) node.put("provider", provider);
        if (modelId != null) node.put("modelId", modelId);
        if (requestKind != null) node.put("requestKind", requestKind);
        putNullableLong(node, "inputTokens", inputTokens);
        putNullableLong(node, "outputTokens", outputTokens);
        putNullableLong(node, "reasoningTokens", reasoningTokens);
        putNullableLong(node, "cacheReadTokens", cacheReadTokens);
        putNullableLong(node, "cacheWriteTokens", cacheWriteTokens);
        if (reasoningIncludedInOutput != null) node.put("reasoningIncludedInOutput", reasoningIncludedInOutput);
        if (cacheIncludedInInput != null) node.put("cacheIncludedInInput", cacheIncludedInInput);
        node.put("cumulative", cumulative);
        putNullableLong(node, "totalTokens", totalTokens);
        putNullableLong(node, "latencyMs", latencyMs);
        putNullableLong(node, "epochMs", epochMs);
        if (providerRequestId != null) node.put("providerRequestId", providerRequestId);
        node.put("reporting", reporting.name());
        return node;
    }

    public static ModelUsageEvent fromJsonNode(ObjectNode node) {
        UsageReporting reporting;
        try {
            reporting = UsageReporting.valueOf(node.path("reporting").asText("NOT_REPORTED"));
        } catch (IllegalArgumentException unknown) {
            reporting = UsageReporting.NOT_REPORTED;
        }
        return new ModelUsageEvent(
                node.path("eventId").asText(),
                node.path("invocationId").asText(),
                nullableText(node, "provider"),
                nullableText(node, "modelId"),
                nullableText(node, "requestKind"),
                nullableLong(node, "inputTokens"),
                nullableLong(node, "outputTokens"),
                nullableLong(node, "reasoningTokens"),
                nullableLong(node, "cacheReadTokens"),
                nullableLong(node, "cacheWriteTokens"),
                node.hasNonNull("reasoningIncludedInOutput") ? node.get("reasoningIncludedInOutput").asBoolean() : null,
                node.hasNonNull("cacheIncludedInInput") ? node.get("cacheIncludedInInput").asBoolean() : null,
                node.path("cumulative").asBoolean(false),
                nullableLong(node, "totalTokens"),
                nullableLong(node, "latencyMs"),
                nullableLong(node, "epochMs"),
                nullableText(node, "providerRequestId"),
                reporting);
    }

    private static void putNullableLong(ObjectNode node, String field, Long value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static Long nullableLong(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private static String nullableText(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
