/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache license, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure, shared session tool-usage report. Aggregates a COMPLETE scoped snapshot
 * (all usage records for one session) BEFORE any row limiting — call-detail rows are
 * a bounded VIEW of the totals, never the total source.
 *
 * <p>Separate ledgers: payload (local deterministic measurement) vs model executions
 * (provider-reported, folded by eventId; cumulative duplicates never double-summed).
 * Unknown/partial/legacy coverage is reported explicitly — missing history is never
 * backfilled or silently mixed with measured totals.</p>
 */
public final class SessionToolUsageReport {

    private SessionToolUsageReport() {
    }

    /** Build the full report for one session from its complete usage snapshot. */
    public static ObjectNode build(ObjectMapper mapper,
                                   String sessionId,
                                   List<ToolCallUsage> usages,
                                   List<String> knownMalformed) {
        ObjectNode report = mapper.createObjectNode();
        report.put("sessionId", sessionId);
        report.put("asOfEpochMs", System.currentTimeMillis());
        report.put("recordCount", usages.size());

        // ── summary ─────────────────────────────────────────────────────
        long calls = usages.size();
        long errors = usages.stream().filter(ToolCallUsage::errorResponse).count();
        long cancelled = usages.stream()
                .filter(u -> u.outcome() == ToolCallUsage.ExecutionOutcome.CANCELLED).count();
        long denied = usages.stream()
                .filter(u -> u.outcome() == ToolCallUsage.ExecutionOutcome.DENIED).count();
        long failed = usages.stream()
                .filter(u -> u.outcome() == ToolCallUsage.ExecutionOutcome.EXECUTION_FAILED).count();
        long delivered = usages.stream()
                .filter(u -> u.disposition() == ToolCallUsage.ResponseDisposition.DELIVERED).count();
        long suppressed = usages.stream()
                .filter(u -> u.disposition() == ToolCallUsage.ResponseDisposition.SUPPRESSED).count();
        long backgroundAcks = usages.stream()
                .filter(u -> u.disposition() == ToolCallUsage.ResponseDisposition.BACKGROUND_ACK).count();
        long degraded = usages.stream().filter(ToolCallUsage::accountingDegraded).count();

        long payloadTokens = usages.stream()
                .map(ToolCallUsage::payloadMeasurement)
                .filter(m -> m != null && m.isMeasured())
                .mapToLong(TokenMeasurement::tokens)
                .sum();
        long partialPayloadRecords = usages.stream()
                .map(ToolCallUsage::payloadMeasurement)
                .filter(m -> m != null && m.status() == TokenMeasurement.MeasurementStatus.PARTIAL)
                .count();
        long unmeasuredPayloadRecords = usages.stream()
                .filter(u -> u.payloadMeasurement() == null
                        || u.payloadMeasurement().status()
                        == TokenMeasurement.MeasurementStatus.UNAVAILABLE)
                .count();
        long rawPayloadTokens = usages.stream()
                .map(ToolCallUsage::rawPayloadMeasurement)
                .filter(m -> m != null && m.isMeasured())
                .mapToLong(TokenMeasurement::tokens)
                .sum();

        ObjectNode summary = report.putObject("summary");
        summary.put("invocations", calls);
        summary.put("errorResponses", errors);
        summary.put("cancelled", cancelled);
        summary.put("denied", denied);
        summary.put("executionFailures", failed);
        summary.put("deliveredResponses", delivered);
        summary.put("suppressedResponses", suppressed);
        summary.put("backgroundAcknowledged", backgroundAcks);
        summary.put("payloadTokensMeasured", payloadTokens);
        summary.put("rawPayloadTokensMeasured", rawPayloadTokens);
        summary.put("payloadTokensUnmeasuredRecords", unmeasuredPayloadRecords);
        summary.put("payloadTokensPartialRecords", partialPayloadRecords);
        summary.put("accountingDegradedRecords", degraded);

        // response duration aggregates over records that carry one
        var durations = usages.stream()
                .map(ToolCallUsage::durationMs)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sorted()
                .boxed()
                .toList();
        if (!durations.isEmpty()) {
            summary.put("durationMsMin", durations.get(0));
            summary.put("durationMsMax", durations.get(durations.size() - 1));
            summary.put("durationMsAvg",
                    durations.stream().mapToLong(Long::longValue).average().orElse(0));
        }

        // ── measurement method buckets ──────────────────────────────────
        Map<String, Long> byMethod = usages.stream()
                .map(ToolCallUsage::payloadMeasurement)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(
                        m -> m.tokenizerId() + "/" + m.tokenizerVersion()
                                + ":" + m.method() + ":" + m.representation(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        ObjectNode buckets = report.putObject("measurementBuckets");
        byMethod.forEach(buckets::put);
        long legacyRecords = usages.stream()
                .filter(u -> !u.hasPayloadMeasurement() && !u.accountingDegraded()).count();
        buckets.put("legacy/unmeasured", legacyRecords);

        // ── per-tool breakdown ──────────────────────────────────────────
        ArrayNode perTool = report.putArray("perTool");
        usages.stream()
                .collect(Collectors.groupingBy(
                        u -> u.resolvedToolName() != null ? u.resolvedToolName() : "unknown",
                        LinkedHashMap::new,
                        Collectors.toList()))
                .values()
                .stream()
                .sorted((a, b) -> Integer.compare(b.size(), a.size()))
                .forEach(records -> {
                    ObjectNode toolNode = perTool.addObject();
                    toolNode.put("tool", records.get(0).resolvedToolName());
                    toolNode.put("invocations", records.size());
                    toolNode.put("errors", records.stream()
                            .filter(ToolCallUsage::errorResponse).count());
                    toolNode.put("payloadTokensMeasured", records.stream()
                            .map(ToolCallUsage::payloadMeasurement)
                            .filter(m -> m != null && m.isMeasured())
                            .mapToLong(TokenMeasurement::tokens)
                            .sum());
                });

        // ── provider/model execution ledger (SEPARATE from payload) ─────
        ObjectNode models = report.putObject("modelExecutions");
        Map<String, List<ModelUsageEvent>> byEvent = usages.stream()
                .flatMap(u -> u.modelExecutions().stream())
                .collect(Collectors.groupingBy(ModelUsageEvent::eventId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        // fold: unique eventId once; for cumulative streams take the LAST (largest totals)
        long modelInput = 0;
        long modelOutput = 0;
        long unreportedExecutions = 0;
        for (List<ModelUsageEvent> eventVersions : byEvent.values()) {
            ModelUsageEvent latest = eventVersions.get(eventVersions.size() - 1);
            if (latest.reporting() == ModelUsageEvent.UsageReporting.NOT_REPORTED
                    || latest.reporting() == ModelUsageEvent.UsageReporting.UNINSTRUMENTED) {
                unreportedExecutions++;
                continue;
            }
            if (latest.inputTokens() != null) {
                modelInput += latest.inputTokens();
            }
            if (latest.outputTokens() != null) {
                modelOutput += latest.outputTokens();
            }
        }
        models.put("uniqueExecutions", byEvent.size());
        models.put("providerReportedExecutions", byEvent.size() - unreportedExecutions);
        models.put("unreportedExecutions", unreportedExecutions);
        models.put("inputTokens", modelInput);
        models.put("outputTokens", modelOutput);
        models.put("note", "model totals are NEVER derived from payload size and are "
                + "kept separate from the host conversation ledger");

        // ── accounting health ───────────────────────────────────────────
        ObjectNode health = report.putObject("accountingHealth");
        health.put("malformedJournalLines", knownMalformed == null ? 0 : knownMalformed.size());
        health.put("degradedRecords", degraded);
        health.put("complete", degraded == 0
                && (knownMalformed == null || knownMalformed.isEmpty()));

        return report;
    }

    /** Bounded call-detail VIEW over the already-aggregated snapshot. */
    public static ArrayNode callDetails(ObjectMapper mapper, List<ToolCallUsage> usages,
                                        int limit, int offset) {
        ArrayNode arr = mapper.createArrayNode();
        usages.stream()
                .skip(offset)
                .limit(limit)
                .forEach(u -> {
                    ObjectNode row = mapper.createObjectNode();
                    row.put("invocationId", u.invocationId());
                    row.put("tool", u.resolvedToolName());
                    row.put("outcome", u.outcome().name());
                    row.put("disposition", u.disposition().name());
                    row.put("error", u.errorResponse());
                    if (u.durationMs() != null) {
                        row.put("durationMs", u.durationMs());
                    }
                    if (u.payloadMeasurement() != null) {
                        row.put("payloadTokens", u.payloadMeasurement().tokens());
                        row.put("payloadStatus", u.payloadMeasurement().status().name());
                    }
                    if (u.rawPayloadMeasurement() != null
                            && u.rawPayloadMeasurement().tokens() != null
                            && u.payloadMeasurement() != null
                            && u.payloadMeasurement().tokens() != null) {
                        row.put("rawToReturnedDeltaTokens",
                                u.rawPayloadMeasurement().tokens()
                                        - u.payloadMeasurement().tokens());
                    }
                    arr.add(row);
                });
        return arr;
    }
}
