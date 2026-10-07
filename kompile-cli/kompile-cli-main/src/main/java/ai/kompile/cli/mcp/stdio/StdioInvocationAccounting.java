/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.metrics.PayloadTokenCounter;
import ai.kompile.cli.common.metrics.ToolCallUsage;
import ai.kompile.cli.common.metrics.ToolInvocationContext;
import ai.kompile.cli.common.metrics.TokenMeasurement;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Per-invocation accounting helper for the stdio dispatch loop. Small on purpose:
 * counting and finalization live here, not in the command.
 *
 * <p>One {@link ToolInvocationContext} per logical server invocation, created BEFORE
 * gateway/enforcer checks. Counting measures the FINAL returned content (after
 * reference-cache substitution, before usage metadata is attached); an optional raw
 * snapshot taken before substitution is kept separate. Usage is finalized on every
 * dispatch exit — executed, denied, unknown tool, rewritten, thrown, background ack,
 * and cancellation (which records SUPPRESSED disposition).</p>
 */
public final class StdioInvocationAccounting {

    private final PayloadTokenCounter counter;
    private final ObjectMapper mapper;
    private final ai.kompile.cli.common.metrics.ToolUsageRecorder recorder;
    private ai.kompile.cli.common.metrics.ToolInvocationDetails details;
    private final Map<String, TokenMeasurement> arguments = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, TokenMeasurement> eldest) {
                    return size() > 2048;
                }
            });

    public StdioInvocationAccounting withDetails(ai.kompile.cli.common.metrics.ToolInvocationDetails store) {
        this.details = store;
        return this;
    }

    /** Capture real request arguments and transport correlation before any policy rewrite. */
    public ToolInvocationContext begin(String sessionId, String parentInvocationId, String requestedToolName,
                                       String resolvedToolName, String agentName, String source,
                                       com.fasterxml.jackson.databind.JsonNode requestArguments, String clientRequestId) {
        ToolInvocationContext base = begin(sessionId, parentInvocationId, requestedToolName, resolvedToolName, agentName, source);
        ToolInvocationContext ctx = new ToolInvocationContext(base.invocationId(), base.sessionId(),
                base.sessionProvenance(), base.parentInvocationId(), clientRequestId, null, null,
                base.requestedToolName(), base.resolvedToolName(), base.agentName(), base.source(), base.startedEpochMs());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = requestArguments == null || !requestArguments.isObject()
                ? Map.of() : mapper.convertValue(requestArguments, Map.class);
        arguments.put(ctx.invocationId(), counter.measureArgumentsJsonV1(value, mapper));
        if (details != null) {
            try { details.request(ctx, mapper.writeValueAsString(value)); }
            catch (Exception e) { System.err.println("[MCP] detail recording failure: " + e.getMessage()); }
        }
        return ctx;
    }

    public StdioInvocationAccounting(ObjectMapper mapper,
                                     ai.kompile.cli.common.metrics.ToolUsageRecorder recorder) {
        this(mapper, recorder, new PayloadTokenCounter());
    }

    public StdioInvocationAccounting(ObjectMapper mapper,
                                     ai.kompile.cli.common.metrics.ToolUsageRecorder recorder,
                                     PayloadTokenCounter counter) {
        this.mapper = mapper;
        this.recorder = recorder;
        this.counter = counter;
    }

    /** Open an invocation for one tools/call request. */
    public ToolInvocationContext begin(String sessionId,
                                       String parentInvocationId,
                                       String requestedToolName,
                                       String resolvedToolName,
                                       String agentName,
                                       String source) {
        long now = System.currentTimeMillis();
        return new ToolInvocationContext(
                ToolInvocationContext.newInvocationId(),
                sessionId,
                ToolInvocationContext.PROVENANCE_TRANSCRIPT_RESOLVED,
                parentInvocationId,
                null,
                null,
                null,
                requestedToolName,
                resolvedToolName,
                agentName,
                source,
                now);
    }

    /**
     * Finalize usage for one exit and hand it to the recorder. Never throws.
     *
     * @return the finalized usage (for wire projection via the serializer), or null
     *         when accounting is unavailable for this exit
     */
    public ai.kompile.cli.common.metrics.ToolCallUsage finalizeCall(ToolInvocationContext ctx,
                             ToolResult result,
                             ToolResult rawResultBeforeReferenceSubstitution,
                             ToolCallUsage.ExecutionOutcome outcome,
                             ToolCallUsage.ResponseDisposition disposition,
                             boolean errorResponse,
                             Long finishedEpochMs) {
        return finalizeCall(ctx, result, rawResultBeforeReferenceSubstitution, outcome,
                disposition, errorResponse, finishedEpochMs, null);
    }

    /**
     * Overload accepting model-execution events collected via {@link
     * ai.kompile.cli.common.metrics.ModelUsageScope} during execution (Task 7).
     * Events are provider-reported; payload measurement stays a separate ledger.
     */
    public ai.kompile.cli.common.metrics.ToolCallUsage finalizeCall(ToolInvocationContext ctx,
                             ToolResult result,
                             ToolResult rawResultBeforeReferenceSubstitution,
                             ToolCallUsage.ExecutionOutcome outcome,
                             ToolCallUsage.ResponseDisposition disposition,
                             boolean errorResponse,
                             Long finishedEpochMs,
                             java.util.List<ai.kompile.cli.common.metrics.ModelUsageEvent> modelEvents) {
        return finalizeCall(ctx, result, rawResultBeforeReferenceSubstitution, outcome, disposition,
                errorResponse, finishedEpochMs, modelEvents, true);
    }

    /** An ACK is transport content, not the eventual execution result; it must not overwrite that result. */
    public ToolCallUsage finalizeBackgroundAcknowledgement(ToolInvocationContext ctx, ToolResult result, Long finishedEpochMs) {
        return finalizeCall(ctx, result, null, ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.BACKGROUND_ACK, false, finishedEpochMs, null, false);
    }

    private ToolCallUsage finalizeCall(ToolInvocationContext ctx, ToolResult result, ToolResult rawResultBeforeReferenceSubstitution,
            ToolCallUsage.ExecutionOutcome outcome, ToolCallUsage.ResponseDisposition disposition,
            boolean errorResponse, Long finishedEpochMs, List<ai.kompile.cli.common.metrics.ModelUsageEvent> modelEvents,
            boolean recordContent) {
        try {
            TokenMeasurement payload = null;
            TokenMeasurement raw = null;
            String structured = null;
            if (result != null && result.getMetadata() != null && !result.getMetadata().isEmpty()) {
                // business metadata is structured content on the wire; measured separately
                try {
                    structured = mapper.writeValueAsString(result.getMetadata());
                } catch (Exception ignored) {
                    structured = null;
                    // metadata serialization failure degrades to text-only measurement
                }
            }
            if (result != null && result.getOutput() != null) {
                payload = counter.measureMcpTextFieldsV1(result.getOutput(), structured, mapper);
            } else if (result != null) {
                // measured empty: null output measured as explicit zero
                payload = counter.measureMcpTextFieldsV1("", structured, mapper);
            }
            if (rawResultBeforeReferenceSubstitution != null
                    && rawResultBeforeReferenceSubstitution.getOutput() != null
                    && result != null
                    && rawResultBeforeReferenceSubstitution.getOutput() != result.getOutput()) {
                raw = counter.measureMcpTextFieldsV1(
                        rawResultBeforeReferenceSubstitution.getOutput(), null, mapper);
            }
            ToolCallUsage usage = new ToolCallUsage(
                    ctx.invocationId(), ctx.sessionId(),
                    ctx.requestedToolName(), ctx.resolvedToolName(),
                    ctx.startedEpochMs(), finishedEpochMs,
                    finishedEpochMs != null ? finishedEpochMs - ctx.startedEpochMs() : null,
                    outcome, disposition, errorResponse,
                    arguments.getOrDefault(ctx.invocationId(), TokenMeasurement.unavailable(
                            "json", "args-json-v1", "unknown", "unknown", "Request arguments not captured")),
                    payload, raw,
                    modelEvents == null ? List.of() : modelEvents,
                    payload == null,
                    payload == null ? "no payload measurement available" : null);
            if (details != null && recordContent) {
                try {
                    details.result(ctx, result == null ? null : result.getOutput(),
                            rawResultBeforeReferenceSubstitution == null ? null : rawResultBeforeReferenceSubstitution.getOutput(),
                            structured);
                } catch (Exception e) { System.err.println("[MCP] detail recording failure: " + e.getMessage()); }
            }
            if (recorder != null) {
                recorder.record(usage);
            }
            return usage;
        } catch (Exception accountingFailure) {
            // never break the tool call: accounting failures degrade, never retry
            System.err.println("[MCP] usage accounting failure: "
                    + String.valueOf(accountingFailure.getMessage()));
            return null;
        }
    }
}
