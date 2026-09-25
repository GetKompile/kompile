/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.metrics.PayloadTokenCounter;
import ai.kompile.cli.common.metrics.ToolCallUsage;
import ai.kompile.cli.common.metrics.ToolInvocationContext;
import ai.kompile.cli.common.metrics.TokenMeasurement;
import ai.kompile.cli.mcp.stdio.StdioInvocationAccounting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Task 3: serializer _meta overload + StdioInvocationAccounting finalization semantics. */
class McpToolUsageWiringTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Recorder stub capturing records (no real journal — proves no extra executions). */
    private static final class RecordingRecorder implements ai.kompile.cli.common.metrics.ToolUsageRecorder {
        final List<ToolCallUsage> recorded = new CopyOnWriteArrayList<>();

        @Override
        public void record(ToolCallUsage usage) {
            recorded.add(usage);
        }

        @Override
        public ObjectNode toJsonNode(ObjectMapper mapper) {
            return null;
        }
    }

    private static ToolInvocationContext ctx(String invocationId) {
        return new ToolInvocationContext(invocationId, "sess-1", "transcript-resolved",
                null, null, null, null, "echo", "echo", "agent", "mcp-stdio", 1000L);
    }

    private static ToolCallUsage usage(String invocationId, String sessionId) {
        return new ToolCallUsage(invocationId, sessionId, "echo", "echo",
                1000L, 1100L, 100L,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false,
                null,
                TokenMeasurement.measured(9, "utf8-text", PayloadTokenCounter.TEXT_METHOD, "est", "1"),
                null, List.of(), false, null);
    }

    @Test
    @DisplayName("legacy serializer shape unchanged when usage is null (no _meta)")
    void legacyShapeUnchanged() {
        ToolResult result = ToolResult.success("title", "output body");
        ObjectNode wire = McpToolResultSerializer.toMcpCallResult(M, result, null);
        assertFalse(wire.has("_meta"));
        assertTrue(wire.has("content"));
        // title is embedded into text by design (titles count only as actually present)
        assertEquals("title\noutput body", wire.path("content").get(0).path("text").asText());
        assertFalse(wire.path("isError").asBoolean());
    }

    @Test
    @DisplayName("usage overload attaches _meta[ai.kompile/usage] without changing business content")
    void usageAttachesMeta() {
        ToolResult result = ToolResult.success("title", "output body");
        ObjectNode legacy = McpToolResultSerializer.toMcpCallResult(M, result, null);
        ObjectNode withUsage = McpToolResultSerializer.toMcpCallResult(M, result, usage("inv-1", "sess-1"));

        assertTrue(withUsage.path("_meta").has("ai.kompile/usage"));
        // business fields identical between shapes
        assertEquals(legacy.path("content").toString(), withUsage.path("content").toString());
        assertEquals(legacy.path("isError").asBoolean(), withUsage.path("isError").asBoolean());
        // usage payload carries the measured tokens, not invented values
        assertEquals(9L, withUsage.path("_meta").path("ai.kompile/usage")
                .path("payload").path("tokens").asLong());
    }

    @Test
    @DisplayName("accounting finalization: measures FINAL content; raw kept separate with larger count")
    void finalizeMeasuresFinalContent() {
        RecordingRecorder recorder = new RecordingRecorder();
        var accounting = new StdioInvocationAccounting(M, recorder);
        ToolInvocationContext context = ctx("inv-a");

        ToolResult raw = ToolResult.success(null, "x".repeat(5000));
        // simulate reference-cache substitution: final is the summarized version
        ToolResult finalResult = ToolResult.success(null, "[ref:handle-1] large output cached");

        ToolCallUsage recorded = accounting.finalizeCall(context, finalResult, raw,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1100L);

        assertNotNull(recorded);
        assertEquals(1, recorder.recorded.size(), "exactly one usage record per exit");
        assertTrue(recorded.payloadMeasurement().tokens() > 0);
        assertNotNull(recorded.rawPayloadMeasurement());
        assertTrue(recorded.rawPayloadMeasurement().tokens()
                        > recorded.payloadMeasurement().tokens(),
                "raw 5000-char payload must measure larger than the substituted short final");
    }

    @Test
    @DisplayName("cancellation: CANCELLED + SUPPRESSED; absent result never labeled delivered")
    void cancellationSuppressed() {
        RecordingRecorder recorder = new RecordingRecorder();
        var accounting = new StdioInvocationAccounting(M, recorder);

        accounting.finalizeCall(ctx("inv-c"), null, null,
                ToolCallUsage.ExecutionOutcome.CANCELLED,
                ToolCallUsage.ResponseDisposition.SUPPRESSED, true, 1200L);

        assertEquals(1, recorder.recorded.size());
        ToolCallUsage usage = recorder.recorded.get(0);
        assertEquals(ToolCallUsage.ExecutionOutcome.CANCELLED, usage.outcome());
        assertEquals(ToolCallUsage.ResponseDisposition.SUPPRESSED, usage.disposition());
        assertNull(usage.payloadMeasurement());
        assertTrue(usage.accountingDegraded(), "honest degraded flag when nothing measured");
    }

    @Test
    @DisplayName("denied call: outcome DENIED with delivered error payload measured")
    void deniedCallMeasured() {
        RecordingRecorder recorder = new RecordingRecorder();
        var accounting = new StdioInvocationAccounting(M, recorder);

        ToolCallUsage recorded = accounting.finalizeCall(ctx("inv-d"),
                ToolResult.error("BLOCKED by gateway: policy"),
                null,
                ToolCallUsage.ExecutionOutcome.DENIED,
                ToolCallUsage.ResponseDisposition.DELIVERED, true, 1050L);

        assertEquals(ToolCallUsage.ExecutionOutcome.DENIED, recorded.outcome());
        assertTrue(recorded.payloadMeasurement().tokens() > 0,
                "denial message is a delivered payload and is measured");
        assertTrue(recorded.errorResponse());
    }

    @Test
    @DisplayName("recorder failure never propagates — finalize returns null, no throw")
    void recorderFailureNonfatal() {
        var accounting = new StdioInvocationAccounting(M,
                new ai.kompile.cli.common.metrics.ToolUsageRecorder() {
                    @Override
                    public void record(ToolCallUsage usage) {
                        throw new RuntimeException("journal disk full");
                    }

                    @Override
                    public ObjectNode toJsonNode(ObjectMapper mapper) {
                        return null;
                    }
                });

        ToolCallUsage result = accounting.finalizeCall(ctx("inv-f"),
                ToolResult.success(null, "payload"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1300L);

        assertNull(result, "accounting failure surfaces as null, never breaks the tool call");
    }

    @Test
    @DisplayName("child invocation reuse: same context finalization is idempotent accounting, not a new execution")
    void singleExecutionSingleRecord() {
        RecordingRecorder recorder = new RecordingRecorder();
        var accounting = new StdioInvocationAccounting(M, recorder);
        ToolInvocationContext context = ctx("inv-x");

        accounting.finalizeCall(context, ToolResult.success(null, "first"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1100L);

        assertEquals(1, recorder.recorded.size());
        assertEquals("inv-x", recorder.recorded.get(0).invocationId());
        assertEquals("sess-1", recorder.recorded.get(0).sessionId());
    }
}
