/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 5 acceptance: aggregation completeness, scoping, pagination, restart reconstruction. */
class SessionToolUsageReportTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ToolCallUsage usage(String invocationId, String session, String tool,
                                       boolean error, long payloadTokens) {
        return new ToolCallUsage(invocationId, session, tool, tool,
                1000L, 1100L, 100L,
                error ? ToolCallUsage.ExecutionOutcome.EXECUTION_FAILED
                        : ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, error,
                null,
                payloadTokens >= 0
                        ? TokenMeasurement.measured(payloadTokens, "utf8-text",
                                PayloadTokenCounter.TEXT_METHOD, "est", "1")
                        : null,
                null, List.of(), payloadTokens < 0,
                payloadTokens < 0 ? "legacy record" : null);
    }

    @Test
    @DisplayName(">100 calls: totals aggregate the FULL snapshot while call rows stay bounded")
    void aggregateBeforeLimiting(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        for (int i = 0; i < 150; i++) {
            j.record(usage("inv-" + i, "sess-big", "read", i % 10 == 0, 10 + i), 1);
        }
        ToolUsageReportService service = new ToolUsageReportService(M, journal);
        ObjectNode report = service.sessionReport("sess-big", null);

        assertEquals(150, report.path("summary").path("invocations").asInt(),
                "summary covers the complete snapshot");
        assertEquals(1500 + 149 * 150 / 2, report.path("summary").path("payloadTokensMeasured").asLong(),
                "payload totals sum ALL measured records");

        ArrayNode details = service.sessionCallDetails("sess-big", 0, 50);
        assertEquals(50, details.size(), "detail rows bounded at requested limit");
    }

    @Test
    @DisplayName("two sessions never mix: scoped report excludes the other session's records")
    void sessionIsolation(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-a1", "sess-A", "read", false, 100), 1);
        j.record(usage("inv-b1", "sess-B", "bash", false, 200), 1);
        j.record(usage("inv-a2", "sess-A", "grep", true, 300), 1);

        ObjectNode reportA = new ToolUsageReportService(M, journal).sessionReport("sess-A", null);
        assertEquals(2, reportA.path("summary").path("invocations").asInt());
        assertEquals(400, reportA.path("summary").path("payloadTokensMeasured").asLong());
        ObjectNode reportB = new ToolUsageReportService(M, journal).sessionReport("sess-B", null);
        assertEquals(1, reportB.path("summary").path("invocations").asInt());
    }

    @Test
    @DisplayName("mixed methods and legacy records are bucketed, never silently merged")
    void mixedMethodBuckets(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        j.record(usage("inv-1", "s", "read", false, 100), 1);
        j.record(usage("inv-2", "s", "read", false, -1), 1); // legacy/unmeasured
        ObjectNode report = new ToolUsageReportService(M, journal).sessionReport("s", null);
        assertEquals(1, report.path("summary").path("payloadTokensUnmeasuredRecords").asInt());
        assertEquals(100, report.path("summary").path("payloadTokensMeasured").asLong(),
                "legacy records contribute nothing to measured totals");
        assertTrue(report.path("measurementBuckets").has("legacy/unmeasured"));
    }

    @Test
    @DisplayName("model executions fold duplicate eventIds; cumulative latest wins, never parent+child sum")
    void modelExecutionsFolded(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        ModelUsageEvent cumulative1 = new ModelUsageEvent("evt-1", "inv-m", "openai", "gpt",
                "generation", 50L, 20L, null, null, null, null, null, true, 70L, 0L, 0L,
                "req-1", ModelUsageEvent.UsageReporting.PROVIDER_REPORTED);
        ModelUsageEvent cumulative2 = new ModelUsageEvent("evt-1", "inv-m", "openai", "gpt",
                "generation", 120L, 60L, null, null, null, null, null, true, 180L, 0L, 0L,
                "req-1", ModelUsageEvent.UsageReporting.PROVIDER_REPORTED);
        ModelUsageEvent child = new ModelUsageEvent("evt-2", "inv-m", "openai", "gpt",
                "generation", 10L, 5L, null, null, null, null, null, false, 15L, 0L, 0L,
                "req-2", ModelUsageEvent.UsageReporting.PROVIDER_REPORTED);
        j.record(new ToolCallUsage("inv-m", "s", "task", "task", 1L, 2L, 1L,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false,
                null, TokenMeasurement.measured(5, "utf8-text", "m", "est", "1"),
                null, List.of(cumulative1, cumulative2, child), false, null), 1);

        ObjectNode report = new ToolUsageReportService(M, journal).sessionReport("s", null);
        ObjectNode models = (ObjectNode) report.path("modelExecutions");
        assertEquals(2, models.path("uniqueExecutions").asInt(), "evt-1 folded");
        assertEquals(130, models.path("inputTokens").asLong(),
                "cumulative latest (120) + child (10); NOT 50+120+10");
        assertEquals(65, models.path("outputTokens").asLong());
    }

    @Test
    @DisplayName("restart reconstruction: new service instance reads the same journal")
    void restartReconstruction(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal writer = new ToolCallUsageJournal(journal, M);
        writer.record(usage("inv-r1", "s", "read", false, 42), 1);

        // simulate restart: brand-new service over the same file
        ObjectNode report = new ToolUsageReportService(M, journal).sessionReport("s", null);
        assertEquals(1, report.path("summary").path("invocations").asInt());
        assertEquals(42, report.path("summary").path("payloadTokensMeasured").asLong());
    }

    @Test
    @DisplayName("valid empty response: zero sessions still render a complete report")
    void emptySessionReport(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ObjectNode report = new ToolUsageReportService(M, journal).sessionReport("nothing", null);
        assertEquals(0, report.path("summary").path("invocations").asInt());
        assertEquals(0, report.path("summary").path("payloadTokensMeasured").asLong());
        assertTrue(report.path("accountingHealth").path("complete").asBoolean());
        // markdown render is stable and non-empty
        String md = ToolUsageReportService.render(report, "markdown");
        assertTrue(md.contains("Session usage: nothing"));
        assertTrue(md.contains("Payload tokens (measured): 0"));
    }

    @Test
    @DisplayName("pagination offset skips; total unchanged")
    void pagination(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        for (int i = 0; i < 10; i++) {
            j.record(usage("inv-p" + i, "s", "read", false, 10), 1);
        }
        ToolUsageReportService service = new ToolUsageReportService(M, journal);
        assertEquals(4, service.sessionCallDetails("s", 6, 10).size());
        assertEquals(5, service.sessionCallDetails("s", 0, 5).size());
    }

    @Test
    @DisplayName("blank session rejected — never an implicit global total")
    void requiresSessionScope(@TempDir Path dir) {
        ToolUsageReportService service = new ToolUsageReportService(M, dir.resolve("u.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> service.sessionReport(null, null));
        assertThrows(IllegalArgumentException.class, () -> service.sessionReport(" ", null));
    }

    @Test
    @DisplayName("raw-to-returned delta reported per row when both sides measured")
    void rawDeltaPerRow(@TempDir Path dir) {
        Path journal = dir.resolve("usage.jsonl");
        ToolCallUsageJournal j = new ToolCallUsageJournal(journal, M);
        ToolCallUsage withRaw = new ToolCallUsage("inv-raw", "s", "grep", "grep",
                1L, 2L, 1L, ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false,
                null,
                TokenMeasurement.measured(10, "utf8-text", "m", "est", "1"),
                TokenMeasurement.measured(100, "utf8-text", "m", "est", "1"),
                List.of(), false, null);
        j.record(withRaw, 1);
        ArrayNode rows = new ToolUsageReportService(M, journal).sessionCallDetails("s", 0, 10);
        assertEquals(90, rows.get(0).path("rawToReturnedDeltaTokens").asLong());
    }
}
