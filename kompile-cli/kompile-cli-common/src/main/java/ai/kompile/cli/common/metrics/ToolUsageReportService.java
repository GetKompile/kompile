/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared report service: loads the scoped usage snapshot from a usage journal and
 * delegates aggregation to the pure {@link SessionToolUsageReport}. Used by the CLI
 * catalog tool (and reusable by the app UI) so totals are computed in exactly ONE
 * place. The reporting call takes an asOf snapshot and EXCLUDES its own in-progress
 * invocation — the report response itself is recorded normally afterward.
 */
public final class ToolUsageReportService {

    private final ObjectMapper mapper;
    private final Path journalFile;

    public ToolUsageReportService(ObjectMapper mapper, Path journalFile) {
        this.mapper = mapper;
        this.journalFile = journalFile.toAbsolutePath().normalize();
    }

    /**
     * Build the report for one session.
     *
     * @param sessionId    required scope (rejects blank — never an implicit global total)
     * @param excludeInvocationId invocation to exclude (the report call itself), may be null
     */
    public ObjectNode sessionReport(String sessionId, String excludeInvocationId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required: reports are session-scoped");
        }
        ToolCallUsageJournal journal = new ToolCallUsageJournal(journalFile, mapper);
        ToolCallUsageJournal.FoldedSnapshot snapshot = journal.snapshot();
        List<ToolCallUsage> scoped = snapshot.usages().stream()
                .filter(u -> sessionId.equals(u.sessionId()))
                .filter(u -> excludeInvocationId == null
                        || !excludeInvocationId.equals(u.invocationId()))
                .toList();
        return SessionToolUsageReport.build(mapper, sessionId, scoped, snapshot.malformedLines());
    }

    /** Bounded call-detail view for one session (offset/limit over the full snapshot). */
    public ArrayNode sessionCallDetails(String sessionId, int offset, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        ToolCallUsageJournal journal = new ToolCallUsageJournal(journalFile, mapper);
        ToolCallUsageJournal.FoldedSnapshot snapshot = journal.snapshot();
        List<ToolCallUsage> scoped = snapshot.usages().stream()
                .filter(u -> sessionId.equals(u.sessionId()))
                .toList();
        return SessionToolUsageReport.callDetails(mapper, scoped, limit, offset);
    }

    /** Wire the report into the catalog action response (json or markdown). */
    public static String render(ObjectNode report, String format) {
        if ("markdown".equalsIgnoreCase(format)) {
            return renderMarkdown(report);
        }
        return report.toString(); // json default
    }

    private static String renderMarkdown(ObjectNode report) {
        StringBuilder sb = new StringBuilder();
        ObjectNode summary = (ObjectNode) report.path("summary");
        sb.append("**Session usage: ").append(report.path("sessionId").asText()).append("**\n\n");
        sb.append("Invocations: ").append(summary.path("invocations").asInt())
                .append(" · delivered: ").append(summary.path("deliveredResponses").asInt())
                .append(" · suppressed: ").append(summary.path("suppressedResponses").asInt())
                .append(" · background acks: ").append(summary.path("backgroundAcknowledged").asInt())
                .append("\n");
        sb.append("Payload tokens (measured): ").append(summary.path("payloadTokensMeasured").asLong());
        if (summary.path("rawPayloadTokensMeasured").asLong() > 0) {
            sb.append(" · raw (pre-substitution): ")
                    .append(summary.path("rawPayloadTokensMeasured").asLong());
        }
        sb.append("\nUnmeasured/partial records: ")
                .append(summary.path("payloadTokensUnmeasuredRecords").asInt()).append(" / ")
                .append(summary.path("payloadTokensPartialRecords").asInt())
                .append(" · accounting degraded: ")
                .append(summary.path("accountingDegradedRecords").asInt())
                .append("\n");
        sb.append("Model executions (provider-reported, separate ledger): ")
                .append(report.path("modelExecutions").path("providerReportedExecutions").asInt())
                .append(" (in=").append(report.path("modelExecutions").path("inputTokens").asLong())
                .append(", out=").append(report.path("modelExecutions").path("outputTokens").asLong())
                .append(", unreported=").append(report.path("modelExecutions").path("unreportedExecutions").asInt())
                .append(")\n");
        sb.append("Accounting complete: ")
                .append(report.path("accountingHealth").path("complete").asBoolean())
                .append("\n");
        return sb.toString();
    }
}
