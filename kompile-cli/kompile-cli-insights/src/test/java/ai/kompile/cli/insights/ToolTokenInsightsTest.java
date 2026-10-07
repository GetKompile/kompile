/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.insights;

import ai.kompile.cli.common.metrics.ModelUsageEvent;
import ai.kompile.cli.common.metrics.TokenMeasurement;
import ai.kompile.cli.common.metrics.ToolCallUsage;
import ai.kompile.cli.common.metrics.ToolCallUsageJournal;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** All journal input is synthetic and isolated under TempDir; no live journal or journal writer. */
class ToolTokenInsightsTest {
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();
    @TempDir Path dir;

    private static TokenMeasurement measured(long count) {
        return TokenMeasurement.measured(count, "returned-text", "fixture", "fixture-tokenizer", "v1");
    }

    private static ToolCallUsage call(String id, String session, String tool, String at, TokenMeasurement payload,
                                     boolean degraded, ModelUsageEvent... events) {
        long start = Instant.parse(at).toEpochMilli();
        return new ToolCallUsage(id, session, "requested_" + tool, tool, start, start + 25, 25L,
                ToolCallUsage.ExecutionOutcome.EXECUTED, ToolCallUsage.ResponseDisposition.DELIVERED, false,
                measured(2), payload, null, List.of(events), degraded, degraded ? "fixture degraded" : null);
    }

    private static ToolCallUsage call(String id, String session, String tool, String at, long count) {
        return call(id, session, tool, at, measured(count), false);
    }

    private static String line(ToolCallUsage call, long revision) throws IOException {
        ObjectNode node = call.toJsonNode(MAPPER);
        node.put("recordType", ToolCallUsageJournal.RECORD_TYPE);
        node.put("schemaVersion", ToolCallUsageJournal.SCHEMA_VERSION);
        node.put("revision", revision);
        return MAPPER.writeValueAsString(node);
    }

    private void journal(String... lines) throws IOException {
        Files.writeString(ToolCallUsageJournal.defaultJournalFile(dir), String.join("\n", lines) + "\n");
    }

    private InsightReport ask(InsightsConfig config, String question, String... sessions) throws IOException {
        return new ToolTokenInsights(dir, config).report(InsightsQuery.parse("tools", question,
                List.of(sessions), NOW, ZoneOffset.UTC, config.getDefaultWindowDays()));
    }

    private InsightReport ask(String question, String... sessions) throws IOException {
        return ask(CONFIG, question, sessions);
    }

    private static long total(InsightReport report, String field) {
        return report.getUsage().path("summary").path(field).asLong();
    }

    @Test
    void enrichesTitlesMetricsAndExactSelectedInvocationContent() throws Exception {
        Path conversations = Files.createDirectory(dir.resolve("conversations"));
        Path toolCalls = Files.createDirectory(conversations.resolve("tool-calls"));
        Files.writeString(conversations.resolve("s1.txt"), "Started: timestamp\n> Original prompt\n[title] Resume title\n");
        Files.writeString(conversations.resolve("s1.metrics.json"), "{\"tokens\":{\"input\":123,\"output\":45,\"total\":168},\"agentic\":{\"compactions\":2}}");
        var invocation = call("inv-selected", "s1", "read", "2026-10-03T10:00:00Z", 8);
        Files.writeString(ToolCallUsageJournal.defaultJournalFile(toolCalls), line(invocation, 1) + "\n");
        var context = new ai.kompile.cli.common.metrics.ToolInvocationContext("inv-selected", "s1", "transcript-resolved",
                null, "rpc-id", null, null, "read", "read", "codex", "mcp-stdio", 1000);
        var store = new ai.kompile.cli.common.metrics.ToolInvocationDetails(toolCalls);
        store.request(context, "{\"file_path\":\"test.java\"}");
        store.result(context, "recorded output", null, null);
        var query = InsightsQuery.parse("tools", "tokens session:s1 call:inv-selected", List.of(), NOW, ZoneOffset.UTC, 7);
        var report = new ToolTokenInsights(toolCalls, CONFIG).report(query).getUsage();
        assertEquals("Resume title", report.path("perSession").get(0).path("title").asText());
        assertEquals(2, report.path("selectedSession").path("sessionMetrics").path("agentic").path("compactions").asInt());
        var row = report.path("calls").get(0);
        assertEquals("Resume title", row.path("title").asText());
        assertEquals("recorded output", row.path("detail").path("output").path("text").asText());
        assertEquals("rpc-id", row.path("detail").path("context").path("clientRequestId").asText());
        Files.writeString(conversations.resolve("s1.metrics.json"), "{\"tokens\":{\"total\":200},\"agentic\":{\"compactions\":3}}");
        assertEquals(3, new ToolTokenInsights(toolCalls, CONFIG).report(query).getUsage()
                .path("selectedSession").path("sessionMetrics").path("agentic").path("compactions").asInt());
    }

    @Test
    void historicalCatalogCallsExposeExactInputWithoutInventingTokenUsage() throws Exception {
        Path conversations = Files.createDirectory(dir.resolve("conversations"));
        Path toolCalls = Files.createDirectory(conversations.resolve("tool-calls"));
        Files.writeString(conversations.resolve("s1.txt"), "> Real historical title\n");
        Files.writeString(toolCalls.resolve("s1.jsonl"), "{\"id\":\"s1-42\",\"sessionId\":\"s1\",\"toolName\":\"read\","
                + "\"timestamp\":\"2026-10-03T10:00:00Z\",\"toolInput\":\"full original input\",\"durationMs\":15}\n");
        var query = InsightsQuery.parse("tools", "tokens session:s1 call:s1-42", List.of(), NOW, ZoneOffset.UTC, 7);
        var usage = new ToolTokenInsights(toolCalls, CONFIG).report(query).getUsage();
        assertEquals("Real historical title", usage.path("selectedSession").path("title").asText());
        assertEquals("full original input", usage.path("catalog").path("calls").get(0).path("toolInput").asText());
        assertEquals(0, usage.path("summary").path("calls").asLong());
        assertTrue(usage.path("calls").isEmpty());
        assertFalse(usage.path("catalog").path("calls").get(0).path("detail").path("available").asBoolean());
        var context = new ai.kompile.cli.common.metrics.ToolInvocationContext("s1-42", "s1", "transcript-resolved",
                null, "provider-id", null, null, "read", "read", "codex", "local-chat", 1000);
        var details = new ai.kompile.cli.common.metrics.ToolInvocationDetails(toolCalls);
        details.request(context, "full original input");
        details.result(context, "returned", "unshortened", null);
        var refreshed = new ToolTokenInsights(toolCalls, CONFIG).report(query).getUsage();
        assertEquals("unshortened", refreshed.path("catalog").path("calls").get(0)
                .path("detail").path("rawOutput").path("text").asText());
        assertEquals(0, refreshed.path("summary").path("calls").asLong());
    }

    @Test
    void absentJournalDirectoryIsUnavailableNotAnException() throws IOException {
        InsightReport report = new ToolTokenInsights(null, CONFIG).report(InsightsQuery.parse(
                "tools", "tokens", List.of(), NOW, ZoneOffset.UTC, 7));
        assertEquals(0, total(report, "calls"));
        assertTrue(report.getText().contains("No tool usage journal"));
        assertTrue(report.getText().contains("legacy calls without usage records are not included"));
    }

    @Test
    void foldsHighestRevisionBeforeFilteringWithoutStoppingAtAnOldStartTime() throws IOException {
        ToolCallUsage winner = call("revised", "s1", "read", "2026-10-02T10:00:00Z", 50);
        ToolCallUsage lower = call("revised", "s2", "bash", "2026-09-01T10:00:00Z", 999);
        ToolCallUsage other = call("other", "s1", "read", "2026-10-01T10:00:00Z", 7);
        journal(line(winner, 4), line(other, 1), line(lower, 1));
        InsightReport report = ask("today");
        assertEquals(0, total(report, "calls"));
        report = ask("last 7 days session:s1 tool:read");
        assertEquals(2, total(report, "calls"));
        assertEquals(57, total(report, "payloadTokens"));
        assertEquals("revised", report.getUsage().path("calls").get(0).path("invocationId").asText());
        assertEquals(0, report.getUsage().path("malformedLines").asLong());
    }

    @Test
    void equalRevisionDuplicatesCollapseAndConflictingNewestLineWins() throws IOException {
        ToolCallUsage old = call("same", "s1", "read", "2026-10-02T10:00:00Z", 3);
        ToolCallUsage newest = call("same", "s1", "read", "2026-10-02T10:00:00Z", 8);
        journal(line(old, 2), line(newest, 2), line(newest, 2));
        InsightReport report = ask("all time");
        assertEquals(1, total(report, "calls"));
        assertEquals(8, total(report, "payloadTokens"));
        assertEquals(1, report.getUsage().path("conflictingRevisions").asLong());
        assertTrue(report.getText().contains("conflicting equal revisions"));
    }

    @Test
    void mixesSessionTimeExactToolAndCallFiltersAndSupportsNaturalToolSubjects() throws IOException {
        journal(line(call("wanted-full-invocation-id", "s1", "read", "2026-10-03T10:00:00Z", 8), 1),
                line(call("different-tool", "s1", "Read", "2026-10-03T10:00:00Z", 9), 1),
                line(call("different-session", "s2", "read", "2026-10-03T10:00:00Z", 10), 1),
                line(call("old", "s1", "read", "2026-10-02T10:00:00Z", 11), 1));
        InsightReport report = ask("session:s1 today tool:read call:wanted-full-invocation-id");
        assertEquals(1, total(report, "calls"));
        assertEquals(8, total(report, "payloadTokens"));
        assertTrue(report.getText().contains("wanted-full-invocation-id"));
        assertTrue(report.getText().contains("2026-10-03T10:00:00Z"));
        assertTrue(report.getText().contains("Selected call provenance"));
        assertTrue(report.getText().contains("fixture-tokenizer"));
        assertEquals(2, total(ask("this session today tool:read", "s1"), "calls") +
                total(ask("this session today tool:Read", "s1"), "calls"));
        assertEquals(2, total(ask("all time session:s1 tool:read"), "calls"));
        // Natural subjects use matchSubject's marked/plain-word rules, case-insensitively.
        assertEquals(4, total(ask("all time read the token report"), "calls"));
        assertEquals(1, total(ask("all time for read"), "calls")); // matchSubject chooses Read on this case tie
        assertEquals(0, total(ask("all time tool:READ"), "calls"));
        assertEquals(0, total(ask("all time call:missing"), "calls"));
    }

    @Test
    void paginationAndGroupedRowLimitsNeverChangeAggregatesAndTiesAreStable() throws IOException {
        journal(line(call("c", "z-session", "z-tool", "2026-10-02T10:00:00Z", 5), 1),
                line(call("b", "a-session", "a-tool", "2026-10-02T10:00:00Z", 5), 1),
                line(call("a", "a-session", "b-tool", "2026-10-02T10:00:00Z", 7), 1));
        InsightsConfig one = CONFIG.toBuilder().maxRows(1).build();
        InsightReport first = ask(one, "all time");
        InsightReport second = ask(one, "all time offset:1");
        assertEquals(first.getUsage().path("summary"), second.getUsage().path("summary"));
        assertEquals(17, total(first, "payloadTokens"));
        assertEquals(6, total(first, "argumentsTokens"));
        assertEquals(3, first.getUsage().path("totalCalls").asInt());
        assertEquals("a", first.getUsage().path("calls").get(0).path("invocationId").asText());
        assertEquals("b", second.getUsage().path("calls").get(0).path("invocationId").asText());
        assertTrue(first.getUsage().path("hasMore").asBoolean());
        assertFalse(ask(one, "all time offset:2").getUsage().path("hasMore").asBoolean());
        assertEquals(0, ask(one, "all time offset:50").getUsage().path("calls").size());
        assertEquals("b-tool", first.getUsage().path("perTool").get(0).path("tool").asText());
        assertEquals("a-session", first.getUsage().path("perSession").get(0).path("sessionId").asText());
        InsightReport all = ask("all time");
        assertEquals("a-tool", all.getUsage().path("perTool").get(1).path("tool").asText());
        assertEquals(1, first.getChart().path("series").size());
        assertTrue(first.getText().contains("aggregates are computed before"));
    }

    @Test
    void measuredZeroUnavailablePartialAndMeasuredNullStayDistinctInTotalsAndTrends() throws IOException {
        TokenMeasurement partial = TokenMeasurement.partial(900, "returned-text", "fixture", "fixture-tokenizer", "v1", "bounded");
        TokenMeasurement unavailable = TokenMeasurement.unavailable("binary", "fixture", "fixture-tokenizer", "v1", "unsupported");
        TokenMeasurement measuredNull = new TokenMeasurement(null, TokenMeasurement.MeasurementStatus.MEASURED,
                "returned-text", "fixture", "fixture-tokenizer", "v1", "missing count");
        journal(line(call("zero", "s1", "read", "2026-10-01T10:00:00Z", 0), 1),
                line(call("partial", "s1", "read", "2026-10-02T10:00:00Z", partial, true), 1),
                line(call("missing", "s1", "read", "2026-10-02T11:00:00Z", unavailable, false), 1),
                line(call("null", "s1", "bash", "2026-10-03T09:00:00Z", null, false), 1),
                line(call("measured-null", "s1", "bash", "2026-10-03T10:00:00Z", measuredNull, false), 1));
        InsightReport report = ask("last 7 days");
        assertEquals(0, total(report, "payloadTokens"));
        assertEquals(3, total(report, "unmeasuredPayloadCalls"));
        assertEquals(1, total(report, "partialPayloadCalls"));
        assertEquals(1, total(report, "degradedCalls"));
        JsonNode readTrend = report.getUsage().path("perTool").get(1).path("trend");
        TimeBuckets buckets = TimeBuckets.of(InsightsQuery.parse(null, "last 7 days", List.of(), NOW,
                ZoneOffset.UTC, 7).getWindow(), null, CONFIG.getSparklineBuckets(), ZoneOffset.UTC);
        int zero = buckets.indexOf(Instant.parse("2026-10-01T10:00:00Z"));
        int missing = buckets.indexOf(Instant.parse("2026-10-02T10:00:00Z"));
        assertTrue(readTrend.get(zero).isNumber());
        assertEquals(0, readTrend.get(zero).asLong());
        assertTrue(readTrend.get(missing).isNull());
        assertEquals(readTrend, report.getChart().path("series").get(1).path("values"));
        assertTrue(report.getText().contains("partial counts are excluded"));
        assertTrue(report.getUsage().path("measurementBuckets").path("payload").has("absent"));
    }

    @Test
    void perToolTrendsSumOnlyFullMeasurementsAndAreNotCallCounts() throws IOException {
        journal(line(call("a", "s1", "read", "2026-10-01T10:00:00Z", 15), 1),
                line(call("b", "s1", "read", "2026-10-01T11:00:00Z", 5), 1),
                line(call("c", "s2", "bash", "2026-10-02T10:00:00Z", 30), 1));
        InsightReport report = ask("all time");
        assertEquals("line", report.getChart().path("kind").asText());
        assertEquals("bash", report.getUsage().path("perTool").get(0).path("tool").asText());
        JsonNode read = report.getUsage().path("perTool").get(1);
        assertEquals(2, read.path("calls").asInt());
        assertEquals(20, read.path("trend").get(0).asLong());
        assertEquals(20, read.path("payloadTokens").asLong());
        assertEquals(report.getUsage().path("labels"), report.getChart().path("labels"));
        assertTrue(report.getText().contains("█"));
    }

    @Test
    void partialArgumentsAreExcludedWithoutRemovingPayloadTotals() throws IOException {
        ObjectNode partialArgs = call("partial-args", "s1", "read", "2026-10-02T10:00:00Z", 5).toJsonNode(MAPPER);
        partialArgs.set("arguments", TokenMeasurement.partial(100, "arguments", "fixture", "fixture", "v1", "bounded").toJsonNode(MAPPER));
        partialArgs.put("recordType", "usage");
        partialArgs.put("revision", 1);
        journal(MAPPER.writeValueAsString(partialArgs));
        InsightReport report = ask("all time");
        assertEquals(0, total(report, "argumentsTokens"));
        assertEquals(5, total(report, "payloadTokens"));
        assertTrue(report.getText().contains("PARTIAL/100"));
    }

    @Test
    void missingJournalIsEmptyAndDoesNotReadLegacyIndex() throws IOException {
        Files.writeString(dir.resolve("tool-calls.jsonl"), "{\"toolName\":\"read\",\"tokens\":9999}\n");
        InsightReport report = ask("all time");
        assertEquals(0, total(report, "calls"));
        assertFalse(report.getUsage().path("truncated").asBoolean());
        assertEquals(0, report.getUsage().path("calls").size());
        assertEquals(0, report.getChart().path("series").size());
        assertTrue(report.getText().contains("No tool usage journal"));
    }

    @Test
    void malformedAndTornLinesAreDiagnosedWhileByteBudgetDisclosesPartialCoverage() throws IOException {
        String old = line(call("old", "s1", "read", "2026-10-01T10:00:00Z", 50), 1);
        String newest = line(call("new", "s1", "read", "2026-10-02T10:00:00Z", 7), 1);
        String torn = "{\"recordType\":\"usage\"";
        journal(old, "{not json", "[]", newest, torn);
        InsightReport all = ask("all time");
        assertEquals(3, all.getUsage().path("malformedLines").asLong());
        assertEquals(57, total(all, "payloadTokens"));
        long budget = (newest + "\n" + torn + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        InsightsConfig bounded = CONFIG.toBuilder().maxToolIndexBytes(budget).build();
        InsightReport tail = ask(bounded, "all time");
        assertEquals(7, total(tail, "payloadTokens"));
        assertTrue(tail.getUsage().path("truncated").asBoolean());
        assertTrue(tail.getText().contains("older calls/revisions"));
        assertEquals(1, tail.getUsage().path("malformedLines").asLong());
    }

    private static ModelUsageEvent event(String id, String invocation, Long epoch, long input, long output) {
        return new ModelUsageEvent(id, invocation, "fixture-provider", "fixture-model", "text",
                input, output, 2L, 3L, 4L, true, true, true, input + output, 10L, epoch, "request-" + id,
                ModelUsageEvent.UsageReporting.PROVIDER_REPORTED);
    }

    @Test
    void deduplicatesProviderEventsGloballyByLatestEpochNotParentChildOrderOrPayload() throws IOException {
        ModelUsageEvent latest = event("shared", "child", 300L, 100, 20);
        ModelUsageEvent stale = event("shared", "child", 100L, 1000, 2000);
        ToolCallUsage child = call("child", "child-session", "chat", "2026-10-01T10:00:00Z", measured(7), false, latest);
        ToolCallUsage parent = call("parent", "parent-session", "task", "2026-10-02T10:00:00Z", measured(9), false, stale,
                ModelUsageEvent.uninstrumented("missing", "parent"));
        journal(line(child, 1), line(parent, 1));
        InsightReport report = ask("all time");
        JsonNode models = report.getUsage().path("modelExecutions");
        assertEquals(2, models.path("uniqueExecutions").asLong());
        assertEquals(100, models.path("inputTokens").asLong());
        assertEquals(20, models.path("outputTokens").asLong());
        assertEquals(3, models.path("cacheReadTokens").asLong());
        assertEquals(4, models.path("cacheWriteTokens").asLong());
        assertEquals(2, models.path("reasoningTokens").asLong());
        assertEquals(1, models.path("unreportedExecutions").asLong());
        assertEquals(1, models.path("unreportedCounts").path("inputTokens").asLong());
        assertEquals(16, total(report, "payloadTokens"));
        // Even a parent-only view uses the global latest version of its linked event.
        JsonNode scoped = ask("all time session:parent-session tool:task").getUsage().path("modelExecutions");
        assertEquals(100, scoped.path("inputTokens").asLong());
        assertEquals(300, scoped.path("events").get(0).path("epochMs").asLong());
        JsonNode page = ask(CONFIG.toBuilder().maxRows(1).build(), "all time offset:1").getUsage().path("modelExecutions");
        assertEquals(100, page.path("inputTokens").asLong());
        assertEquals(1, page.path("events").size());
        assertTrue(page.path("hasMore").asBoolean());
    }

    @Test
    void latestCumulativeUpdateWinsEqualAndAbsentTimestampsWithoutParentChildDoubleCounting() throws IOException {
        for (Long timestamp : java.util.Arrays.asList(200L, null)) {
            ModelUsageEvent initial = event("shared", "child", timestamp, 50, 5);
            ModelUsageEvent updated = event("shared", "child", timestamp, 120, 12);
            journal(line(call("parent", "parent-session", "task", "2026-10-01T10:00:00Z",
                            measured(4), false, initial), 1),
                    line(call("child", "child-session", "chat", "2026-10-02T10:00:00Z",
                            measured(7), false, initial, updated), 1));
            JsonNode ledger = ask("all time session:parent-session").getUsage().path("modelExecutions");
            assertEquals(1, ledger.path("uniqueExecutions").asInt());
            assertEquals(120, ledger.path("inputTokens").asLong());
            assertEquals(12, ledger.path("outputTokens").asLong());
            assertTrue(ledger.path("conflictingEvents").asLong() > 0);
        }
    }

    @Test
    void rejectsNegativeOrInvalidOffsets() {
        assertThrows(IllegalArgumentException.class, () -> ask("offset:-1"));
        assertThrows(IllegalArgumentException.class, () -> ask("offset:abc"));
        assertThrows(IllegalArgumentException.class, () -> ask("offset:9999999999999999999"));
    }
}
