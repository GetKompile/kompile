/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Token usage attributed to ONE MCP tool invocation, split into the two ledgers the
 * contract requires kept separate:
 *
 * <ul>
 *   <li>{@link #payload} — local deterministic measurement of what the tool actually
 *       returned to the client (post compression / reference substitution, pre usage
 *       metadata). Also optional {@code rawPayload} comparison when a pre-compression
 *       snapshot was cheaply available.</li>
 *   <li>{@link #modelExecutions} — provider-reported usage from model requests whose
 *       execution is attributable to this invocation (Task 7). Absent when no model
 *       ran or usage was not reported; NEVER derived from payload size.</li>
 * </ul>
 *
 * <p>Response disposition records what actually happened to the response at the
 * delivery boundary — a prepared-but-cancelled response is {@code SUPPRESSED}, not
 * delivered.</p>
 */
public record ToolCallUsage(
        String invocationId,
        String sessionId,
        String requestedToolName,
        String resolvedToolName,

        long startedEpochMs,
        Long finishedEpochMs,
        Long durationMs,

        ExecutionOutcome outcome,
        ResponseDisposition disposition,
        boolean errorResponse,

        TokenMeasurement argumentsMeasurement,
        TokenMeasurement payloadMeasurement,
        TokenMeasurement rawPayloadMeasurement,
        List<ModelUsageEvent> modelExecutions,

        /** true when accounting itself failed/degraded and totals under-report. */
        boolean accountingDegraded,
        String accountingNote) {

    public enum ExecutionOutcome {
        /** Tool executed and returned normally (including an in-band error ToolResult). */
        EXECUTED,
        /** Denied before execution: permission policy, gateway BLOCK, enforcer BLOCK. */
        DENIED,
        /** Arguments rewritten by gateway/enforcer, then executed with effective args. */
        REWRITTEN,
        /** No such tool. */
        UNKNOWN_TOOL,
        /** Execution threw (mapped to a protocol error or error ToolResult). */
        EXECUTION_FAILED,
        /** Cancellation observed before or during execution; response suppressed. */
        CANCELLED
    }

    public enum ResponseDisposition {
        /** Response written to the client transport. */
        DELIVERED,
        /** Response prepared but suppressed (cancelled request): never sent. */
        SUPPRESSED,
        /** Background acknowledgment payload — final for THIS response; later execution
         *  events link to this invocation via taskRef, not as new executions. */
        BACKGROUND_ACK,
        /** Response never reached a delivery boundary (crash, process exit). */
        UNDELIVERED
    }

    public ToolCallUsage {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        modelExecutions = modelExecutions == null ? List.of() : List.copyOf(modelExecutions);
    }

    public static ToolCallUsage minimal(ToolInvocationContext ctx, ExecutionOutcome outcome,
                                        ResponseDisposition disposition, boolean errorResponse,
                                        long finishedEpochMs) {
        return new ToolCallUsage(
                ctx.invocationId(), ctx.sessionId(),
                ctx.requestedToolName(), ctx.resolvedToolName(),
                ctx.startedEpochMs(), finishedEpochMs,
                finishedEpochMs - ctx.startedEpochMs(),
                outcome, disposition, errorResponse,
                null, null, null, List.of(), true,
                "recorded without payload measurement");
    }

    public boolean hasPayloadMeasurement() {
        return payloadMeasurement != null && payloadMeasurement.status()
                != TokenMeasurement.MeasurementStatus.UNAVAILABLE;
    }

    public ObjectNode toJsonNode(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("invocationId", invocationId);
        node.put("sessionId", sessionId);
        node.put("requestedToolName", requestedToolName);
        node.put("resolvedToolName", resolvedToolName);
        node.put("startedEpochMs", startedEpochMs);
        if (finishedEpochMs != null) node.put("finishedEpochMs", finishedEpochMs);
        if (durationMs != null) node.put("durationMs", durationMs);
        node.put("outcome", outcome.name());
        node.put("disposition", disposition.name());
        node.put("errorResponse", errorResponse);
        node.set("arguments", argumentsMeasurement == null
                ? mapper.nullNode() : argumentsMeasurement.toJsonNode(mapper));
        node.set("payload", payloadMeasurement == null
                ? mapper.nullNode() : payloadMeasurement.toJsonNode(mapper));
        node.set("rawPayload", rawPayloadMeasurement == null
                ? mapper.nullNode() : rawPayloadMeasurement.toJsonNode(mapper));
        ArrayNode models = node.putArray("modelExecutions");
        for (ModelUsageEvent e : modelExecutions) {
            models.add(e.toJsonNode(mapper));
        }
        node.put("accountingDegraded", accountingDegraded);
        if (accountingNote != null) node.put("accountingNote", accountingNote);
        return node;
    }

    public static ToolCallUsage fromJsonNode(ObjectNode node) {
        TokenMeasurement args = node.hasNonNull("arguments")
                ? TokenMeasurement.fromJsonNode((ObjectNode) node.get("arguments")) : null;
        TokenMeasurement payload = node.hasNonNull("payload")
                ? TokenMeasurement.fromJsonNode((ObjectNode) node.get("payload")) : null;
        TokenMeasurement raw = node.hasNonNull("rawPayload")
                ? TokenMeasurement.fromJsonNode((ObjectNode) node.get("rawPayload")) : null;
        var models = new java.util.ArrayList<ModelUsageEvent>();
        if (node.get("modelExecutions") != null && node.get("modelExecutions").isArray()) {
            node.get("modelExecutions").forEach(m -> {
                if (m != null && m.isObject()) {
                    models.add(ModelUsageEvent.fromJsonNode((ObjectNode) m));
                }
            });
        }
        return new ToolCallUsage(
                node.path("invocationId").asText(),
                node.path("sessionId").asText(),
                node.path("requestedToolName").asText(null),
                node.path("resolvedToolName").asText(null),
                node.path("startedEpochMs").asLong(0),
                node.hasNonNull("finishedEpochMs") ? node.get("finishedEpochMs").asLong() : null,
                node.hasNonNull("durationMs") ? node.get("durationMs").asLong() : null,
                parseEnum(node.path("outcome").asText(null), ExecutionOutcome.EXECUTED),
                parseEnum(node.path("disposition").asText(null), ResponseDisposition.UNDELIVERED),
                node.path("errorResponse").asBoolean(false),
                args, payload, raw, models,
                node.path("accountingDegraded").asBoolean(false),
                node.hasNonNull("accountingNote") ? node.get("accountingNote").asText() : null);
    }

    private static <T extends Enum<T>> T parseEnum(String name, T fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), name);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    /** Wire payload for {@code _meta["ai.kompile/usage"]}: payload-only, never args or model details. */
    public ObjectNode toWireMetaNode(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("invocationId", invocationId);
        ObjectNode payload = node.putObject("payload");
        if (payloadMeasurement != null) {
            payload.set("tokens", mapper.valueToTree(payloadMeasurement.tokens()));
            payload.put("status", payloadMeasurement.status().name());
            payload.put("method", payloadMeasurement.method());
            payload.put("tokenizer", payloadMeasurement.tokenizerId() + "/"
                    + payloadMeasurement.tokenizerVersion());
        } else {
            payload.put("status", "UNAVAILABLE");
        }
        node.put("outcome", outcome.name());
        node.put("disposition", disposition.name());
        return node;
    }
}
