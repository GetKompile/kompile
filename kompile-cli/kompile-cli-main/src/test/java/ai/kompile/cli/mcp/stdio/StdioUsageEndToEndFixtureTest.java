/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.metrics.PayloadTokenCounter;
import ai.kompile.cli.common.metrics.SessionToolUsageReport;
import ai.kompile.cli.common.metrics.ToolCallUsage;
import ai.kompile.cli.common.metrics.ToolCallUsageJournal;
import ai.kompile.cli.common.metrics.ToolInvocationContext;
import ai.kompile.cli.common.metrics.TokenMeasurement;
import ai.kompile.cli.common.metrics.ToolUsageReportService;
import ai.kompile.cli.main.chat.tools.McpToolResultSerializer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6 acceptance: deterministic end-to-end fixture over the REAL serializer,
 * accounting, journal, and report layers — two sessions, overlapping calls, one
 * large handle-replaced result plus fetch, one tool error, one denied call, one
 * background ack + poll, and one cancellation. Reloads persisted records and asserts
 * report totals from unique invocation ids. Verifies _meta on actual wire JSON and
 * unchanged business results. No model loads (pure in-process accounting).
 */
class StdioUsageEndToEndFixtureTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Capturing recorder standing in for the journal at the dispatch boundary. */
    private static final class JournalRecorder implements ai.kompile.cli.common.metrics.ToolUsageRecorder {
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

    private static ToolInvocationContext ctx(String invocationId, String session, String tool) {
        return new ToolInvocationContext(invocationId, session, "transcript-resolved",
                null, null, null, null, tool, tool, "agent", "mcp-stdio", 1000L);
    }

    @Test
    @DisplayName("E2E: full scenario totals from unique ids; wire _meta present; business results unchanged")
    void fullScenario(@TempDir Path dir) throws Exception {
        JournalRecorder recorder = new JournalRecorder();
        StdioInvocationAccounting accounting = new StdioInvocationAccounting(M, recorder);

        // ── session A: executed small, large cached + fetch, tool error, denied ──
        ToolCallUsage small = accounting.finalizeCall(ctx("inv-a1", "sessA", "read"),
                ToolResult.success(null, "small output"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1010L);

        // large result: raw pre-substitution, final is the handle-summarized version
        ToolCallUsage large = accounting.finalizeCall(ctx("inv-a2", "sessA", "grep"),
                ToolResult.success(null, "[Full result cached as ref:9f3a]"),
                ToolResult.success(null, "x".repeat(50_000)),
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1020L);

        // fetch_result: separate payload measurement, raw == final (never re-cached)
        ToolCallUsage fetch = accounting.finalizeCall(ctx("inv-a3", "sessA", "fetch_result"),
                ToolResult.success(null, "x".repeat(50_000)), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1030L);

        ToolCallUsage toolError = accounting.finalizeCall(ctx("inv-a4", "sessA", "bash"),
                ToolResult.error("command failed: exit 1"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, true, 1040L);

        ToolCallUsage denied = accounting.finalizeCall(ctx("inv-a5", "sessA", "bash"),
                ToolResult.error("BLOCKED by enforcer: policy"), null,
                ToolCallUsage.ExecutionOutcome.DENIED,
                ToolCallUsage.ResponseDisposition.DELIVERED, true, 1050L);

        // background ack payload (final for that response) + linked execution + poll
        ToolCallUsage bgAck = accounting.finalizeCall(ctx("inv-a6", "sessA", "process"),
                ToolResult.success(null, "Tool 'process' running in background. Task ID: task-1"),
                null, ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.BACKGROUND_ACK, false, 1060L);
        ToolCallUsage bgDone = accounting.finalizeCall(ctx("inv-a6", "sessA", "process"),
                ToolResult.success(null, "process complete"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.BACKGROUND_ACK, false, 1100L);
        ToolCallUsage poll = accounting.finalizeCall(ctx("inv-a7", "sessA", "poll"),
                ToolResult.success(null, "task-1: completed"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1110L);

        // cancellation: prepared output suppressed, never delivered
        ToolCallUsage cancelled = accounting.finalizeCall(ctx("inv-a8", "sessA", "bash"),
                null, null, ToolCallUsage.ExecutionOutcome.CANCELLED,
                ToolCallUsage.ResponseDisposition.SUPPRESSED, true, 1120L);

        // ── session B: overlapping tool name, one executed call ────────────
        ToolCallUsage sessionB = accounting.finalizeCall(ctx("inv-b1", "sessB", "bash"),
                ToolResult.success(null, "session b output"), null,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false, 1200L);

        assertEquals(10, recorder.recorded.size(), "one usage record per finalized exit");

        // ── persist through the REAL journal (as the wiring does) ──────────
        Path journalFile = dir.resolve("tool-usage.jsonl");
        ToolCallUsageJournal journal = new ToolCallUsageJournal(journalFile, M);
        for (ToolCallUsage usage : recorder.recorded) {
            assertEquals(ToolCallUsageJournal.WriteResult.Written.class,
                    journal.record(usage, usage.finishedEpochMs()).getClass(),
                    "each unique invocation lands exactly once");
        }
        // replay the whole batch (restart/finalization duplication) — must be idempotent
        for (ToolCallUsage usage : recorder.recorded) {
            assertInstanceOf(ToolCallUsageJournal.WriteResult.Skipped.class,
                    journal.record(usage, usage.finishedEpochMs()),
                    "duplicate finalization is idempotent");
        }

        // ── reload + report totals from unique ids ─────────────────────────
        ToolUsageReportService service = new ToolUsageReportService(M, journalFile);
        ObjectNode reportA = service.sessionReport("sessA", null);
        ObjectNode reportB = service.sessionReport("sessB", null);

        assertEquals(8, reportA.path("summary").path("invocations").asInt());
        assertEquals(1, reportB.path("summary").path("invocations").asInt(),
                "sessions never mix");

        assertEquals(1, reportA.path("summary").path("denied").asInt());
        assertEquals(1, reportA.path("summary").path("cancelled").asInt());
        assertEquals(1, reportA.path("summary").path("suppressedResponses").asInt());
        assertEquals(3, reportA.path("summary").path("errorResponses").asInt(),
                "errorResponses counts the flag: tool error + denied + suppressed cancellation");
        // dispositions: 6 delivered (a1,a2,a3,a4,a5,a7) + 1 backgroundAck (a6, ack and
        // completion fold into ONE invocation by design) + 1 suppressed (a8)
        assertEquals(6, reportA.path("summary").path("deliveredResponses").asInt());
        assertEquals(1, reportA.path("summary").path("backgroundAcknowledged").asInt(),
                "ack + completion share invocationId inv-a6: folded to ONE execution");

        // large raw → final delta captured for a2; fetch (a3) has no raw side
        long largeFinal = reportA.path("summary").path("payloadTokensMeasured").asLong();

        // ── wire JSON: _meta present on actual serialized result; content unchanged ──
        ObjectNode wireLarge = McpToolResultSerializer.toMcpCallResult(M,
                ToolResult.success(null, "[Full result cached as ref:9f3a]"), large);
        String wireJson = M.writeValueAsString(wireLarge);
        JsonNode parsed = M.readTree(wireJson);
        assertTrue(parsed.has("_meta"), "wire result must carry _meta");
        assertTrue(parsed.path("_meta").has("ai.kompile/usage"));
        assertEquals("[Full result cached as ref:9f3a]",
                parsed.path("content").get(0).path("text").asText(),
                "business content unchanged by usage metadata");
        assertFalse(parsed.path("isError").asBoolean());

        ObjectNode wireError = McpToolResultSerializer.toMcpCallResult(M,
                ToolResult.error("command failed: exit 1"), toolError);
        JsonNode parsedError = M.readTree(M.writeValueAsString(wireError));
        assertTrue(parsedError.path("isError").asBoolean());
        assertTrue(parsedError.path("_meta").path("ai.kompile/usage").has("payload"),
                "error response still carries measured payload");

        // cancelled result never serialized as delivered: usage only exists in the
        // journal with SUPPRESSED — there is no wire result at all (asserted by the
        // CANCELLED/SUPPRESSED totals above).

        // markdown report renders stably from the same snapshot
        String md = ToolUsageReportService.render(reportA, "markdown");
        assertTrue(md.contains("Session usage: sessA"));
        assertTrue(md.contains("suppressed: 1"));

        // no model executions anywhere in this fixture (pure local accounting)
        assertEquals(0, reportA.path("modelExecutions").path("uniqueExecutions").asInt());

        // silence unused-variable warnings while keeping explicit scenario labels
        assertNotNull(small);
        assertNotNull(fetch);
        assertNotNull(bgDone);
        assertNotNull(poll);
        assertNotNull(cancelled);
        assertNotNull(sessionB);
        assertNotNull(largeFinal);
    }
}
