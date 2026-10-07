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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only, bounded token reporting over the shared invocation journal, not the legacy index. */
public final class ToolTokenInsights {
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final Comparator<ToolCallUsage> NEWEST = Comparator
            .comparingLong(ToolCallUsage::startedEpochMs).reversed()
            .thenComparing(ToolCallUsage::invocationId);
    private final Path file;
    private final Path toolCallsDir;
    private final InsightsConfig config;

    public ToolTokenInsights(Path toolCallsDir, InsightsConfig config) {
        this.toolCallsDir = toolCallsDir;
        this.file = toolCallsDir == null ? null : ToolCallUsageJournal.defaultJournalFile(toolCallsDir);
        this.config = config;
    }

    private record Revision(long number, ToolCallUsage usage, long position) {}

    private static final class Scan {
        final Map<String, Revision> calls = new LinkedHashMap<>();
        long lines, malformed, conflicts;
        boolean truncated;
    }

    private Scan scan() throws IOException {
        Scan scan = new Scan();
        scan.truncated = TailLines.newestFirst(file, Math.max(1, config.getMaxToolIndexBytes()),
                (bytes, offset, length) -> {
                    long position = scan.lines++;
                    try {
                        JsonNode parsed = MAPPER.readTree(bytes, offset, length);
                        if (!(parsed instanceof ObjectNode node)
                                || !ToolCallUsageJournal.RECORD_TYPE.equals(node.path("recordType").asText())
                                || !node.path("startedEpochMs").isIntegralNumber()
                                || !node.path("revision").isIntegralNumber()
                                || node.path("revision").asLong() < 0) {
                            scan.malformed++;
                            return true;
                        }
                        ToolCallUsage usage = ToolCallUsage.fromJsonNode(node);
                        long revision = node.path("revision").asLong();
                        Revision previous = scan.calls.get(usage.invocationId());
                        if (previous == null || revision > previous.number()) {
                            scan.calls.put(usage.invocationId(), new Revision(revision, usage, position));
                        } else if (revision == previous.number() && !usage.equals(previous.usage())) {
                            scan.conflicts++; // newest physical line wins ties, but never silently
                        }
                    } catch (RuntimeException | IOException malformed) {
                        scan.malformed++;
                    }
                    // Start times are not append order: revisions and background events can arrive late.
                    return true;
                });
        return scan;
    }

    public InsightReport report(InsightsQuery query) throws IOException {
        Scan scan = scan();
        List<ToolCallUsage> folded = scan.calls.values().stream()
                .sorted(Comparator.comparingLong(Revision::position)).map(Revision::usage).toList();
        String tool = filter(query, "tool");
        if (tool == null) {
            Set<String> names = new TreeSet<>();
            folded.forEach(u -> names.add(toolName(u)));
            tool = query.matchSubject(names);
        }
        String callId = filter(query, "call");
        String session = filter(query, "session");
        String selectedTool = tool;
        List<ToolCallUsage> calls = folded.stream()
                .filter(u -> query.getWindow().contains(u.startedEpochMs()) && query.inScope(u.sessionId()))
                .filter(u -> session == null || session.equals(u.sessionId()))
                .filter(u -> selectedTool == null || selectedTool.equals(toolName(u)))
                .filter(u -> callId == null || callId.equals(u.invocationId()))
                .sorted(NEWEST).toList();
        int offset = offset(query);
        int limit = Math.max(1, config.getMaxRows());
        Instant earliest = calls.stream().map(u -> Instant.ofEpochMilli(u.startedEpochMs()))
                .min(Comparator.naturalOrder()).orElse(null);
        TimeBuckets buckets = TimeBuckets.of(query.getWindow(), earliest,
                config.getSparklineBuckets(), query.getZone());
        Counts summary = new Counts(buckets.size());
        Map<String, Counts> tools = new TreeMap<>();
        Map<String, Counts> sessions = new TreeMap<>();
        ObjectNode measurements = MAPPER.createObjectNode();
        ObjectNode argsBuckets = measurements.putObject("arguments");
        ObjectNode payloadBuckets = measurements.putObject("payload");
        for (ToolCallUsage u : calls) {
            int bucket = buckets.indexOf(u.startedEpochMs());
            summary.add(u, bucket);
            tools.computeIfAbsent(toolName(u), k -> new Counts(buckets.size())).add(u, bucket);
            sessions.computeIfAbsent(u.sessionId(), k -> new Counts(buckets.size())).add(u, bucket);
            measurementBucket(argsBuckets, u.argumentsMeasurement());
            measurementBucket(payloadBuckets, u.payloadMeasurement());
        }
        ObjectNode usage = MAPPER.createObjectNode();
        ToolInsightDetails enrichment = new ToolInsightDetails(toolCallsDir, config.getMaxBytesPerFile());
        if (session != null && query.inScope(session)) {
            usage.set("selectedSession", enrichment.session(session));
            usage.set("catalog", enrichment.catalog(session, query, tool, callId, offset, limit));
        }
        usage.set("summary", summary.json());
        usage.set("measurementBuckets", measurements);
        usage.put("offset", offset);
        usage.put("limit", limit);
        usage.put("totalCalls", calls.size());
        usage.put("hasMore", (long) offset + limit < calls.size());
        usage.put("truncated", scan.truncated);
        usage.put("malformedLines", scan.malformed);
        usage.put("conflictingRevisions", scan.conflicts);
        ArrayNode labels = usage.putArray("labels");
        buckets.labels().forEach(labels::add);

        Map<String, double[]> series = new LinkedHashMap<>();
        TextTable toolTable = new TextTable("tool", "calls", "args tokens", "payload tokens",
                "unmeasured", "partial", "trend").maxCell(Integer.MAX_VALUE);
        ArrayNode perTool = usage.putArray("perTool");
        for (Map.Entry<String, Counts> entry : ranked(tools, limit)) {
            Counts c = entry.getValue();
            double[] trend = c.trend();
            ObjectNode row = c.json();
            row.put("tool", entry.getKey());
            ArrayNode values = row.putArray("trend");
            for (double value : trend) Charts.addNumber(values, value);
            perTool.add(row);
            series.put(entry.getKey(), trend);
            toolTable.row(entry.getKey(), c.calls, c.arguments, c.payload, c.unmeasured,
                    c.partial, Sparkline.of(trend));
        }
        TextTable sessionTable = new TextTable("session", "calls", "args tokens", "payload tokens",
                "unmeasured", "partial").maxCell(Integer.MAX_VALUE);
        ArrayNode perSession = usage.putArray("perSession");
        for (Map.Entry<String, Counts> entry : ranked(sessions, limit)) {
            Counts c = entry.getValue();
            ObjectNode row = c.json();
            row.setAll(enrichment.session(entry.getKey()));
            perSession.add(row);
            sessionTable.row(entry.getKey(), c.calls, c.arguments, c.payload, c.unmeasured, c.partial);
        }
        TextTable callTable = new TextTable("invocation", "session", "tool", "start", "duration",
                "outcome", "disposition", "args status/count", "payload status/count")
                .maxCell(Integer.MAX_VALUE).left(1, 2, 3, 5, 6, 7, 8);
        ArrayNode details = usage.putArray("calls");
        for (ToolCallUsage u : calls.stream().skip(offset).limit(limit).toList()) {
            ObjectNode row = u.toJsonNode(MAPPER);
            row.put("tool", toolName(u));
            row.put("title", enrichment.session(u.sessionId()).path("title").asText());
            if (callId != null && callId.equals(u.invocationId())) {
                row.set("detail", enrichment.call(u.sessionId(), u.invocationId()));
                if (session == null) usage.set("selectedSession", enrichment.session(u.sessionId()));
            }
            details.add(row);
            callTable.row(u.invocationId(), u.sessionId(), toolName(u), Instant.ofEpochMilli(u.startedEpochMs()),
                    u.durationMs() == null ? "-" : Format.duration(u.durationMs()), u.outcome(), u.disposition(),
                    measurementText(u.argumentsMeasurement()), measurementText(u.payloadMeasurement()));
        }
        ObjectNode models = modelLedger(folded, calls, limit);
        usage.set("modelExecutions", models);
        String headline = "Tool tokens, " + query.getWindow().label() + ": " + summary.calls
                + " calls, " + summary.arguments + " measured arguments tokens, " + summary.payload
                + " measured payload tokens";
        StringBuilder text = new StringBuilder(headline).append('\n')
                .append("Payload tokens by tool (").append(buckets.unit()).append(")\n")
                .append(toolTable.render()).append("By session\n").append(sessionTable.render())
                .append("Newest calls, offset ").append(offset).append(", limit ").append(limit)
                .append(", total ").append(calls.size()).append('\n').append(callTable.render());
        if (callId != null && !calls.isEmpty()) {
            text.append("Selected call provenance and recorded content:\n")
                    .append(details.isEmpty() ? "No call on this page" : details.get(0).toPrettyString()).append('\n');
        }
        text.append("Model executions (separate ledger): ").append(models.path("uniqueExecutions").asLong())
                .append(" unique, input ").append(models.path("inputTokens").asLong())
                .append(", output ").append(models.path("outputTokens").asLong())
                .append(", cache read ").append(models.path("cacheReadTokens").asLong())
                .append(", cache write ").append(models.path("cacheWriteTokens").asLong())
                .append(", unreported ").append(models.path("unreportedExecutions").asLong()).append('\n');
        text.append("Totals include only fully MEASURED nonnull counts; partial counts are excluded. "
                + "Local arguments/payload tokens are not billed model tokens. "
                + "Model usage is never inferred from payload; cache/reasoning counts are separate, "
                + "not added to input/output. Chart gaps mean calls without a complete measurement.\n")
                .append("Coverage: ").append(summary.unmeasured).append(" unmeasured payload calls, ")
                .append(summary.partial).append(" partial payload calls, ").append(summary.degraded)
                .append(" degraded calls; ").append(scan.malformed).append(" malformed lines; ")
                .append(scan.conflicts).append(" conflicting equal revisions (newest line retained).\n");
        if (scan.truncated) text.append("Truncated journal: bounded newest-byte tail only; older calls/revisions "
                + "and model events may be missing. Totals describe only the scanned coverage.\n");
        text.append("Token coverage is usage-journal only; legacy calls without usage records are not included.\n");
        if (file == null || !Files.isRegularFile(file)) {
            text.append("No tool usage journal; legacy history has no token measurements.\n");
        }
        if (tools.size() > limit || sessions.size() > limit || calls.size() > details.size()) {
            text.append("Rows are limited; aggregates are computed before pagination/group limits.\n");
        }
        if (models.path("conflictingEvents").asLong() > 0) text.append("Conflicting model event versions: ")
                .append(models.path("conflictingEvents").asLong()).append("; latest epochMs retained, "
                        + "then last intra-invocation update/newest journal record for ties.\n");
        return InsightReport.builder().topic("tools").headline(headline)
                .text(config.appendWarning(text.toString()))
                .chart(Charts.line("Measured tool payload tokens", "tokens", buckets.labels(), series))
                .usage(usage).build();
    }

    private static final class Counts {
        long calls, arguments, payload, unmeasured, partial, degraded;
        final long[] bucketCalls, bucketMeasured, bucketTokens;
        Counts(int buckets) {
            bucketCalls = new long[buckets];
            bucketMeasured = new long[buckets];
            bucketTokens = new long[buckets];
        }
        void add(ToolCallUsage u, int bucket) {
            calls++;
            arguments += measured(u.argumentsMeasurement()) ? u.argumentsMeasurement().tokens() : 0;
            TokenMeasurement p = u.payloadMeasurement();
            if (measured(p)) payload += p.tokens();
            else if (p != null && p.status() == TokenMeasurement.MeasurementStatus.PARTIAL) partial++;
            else unmeasured++;
            if (u.accountingDegraded()) degraded++;
            if (bucket >= 0) {
                bucketCalls[bucket]++;
                if (measured(p)) {
                    bucketMeasured[bucket]++;
                    bucketTokens[bucket] += p.tokens();
                }
            }
        }
        double[] trend() {
            double[] values = new double[bucketCalls.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = bucketCalls[i] > 0 && bucketMeasured[i] == 0 ? Double.NaN : bucketTokens[i];
            }
            return values;
        }
        ObjectNode json() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("calls", calls);
            node.put("argumentsTokens", arguments);
            node.put("payloadTokens", payload);
            node.put("unmeasuredPayloadCalls", unmeasured);
            node.put("partialPayloadCalls", partial);
            node.put("degradedCalls", degraded);
            return node;
        }
    }

    private static boolean measured(TokenMeasurement m) {
        return m != null && m.isMeasured() && m.tokens() != null;
    }

    private static String toolName(ToolCallUsage u) {
        if (u.resolvedToolName() != null && !u.resolvedToolName().isBlank()) return u.resolvedToolName();
        return u.requestedToolName() == null || u.requestedToolName().isBlank() ? "unknown" : u.requestedToolName();
    }

    private static String measurementText(TokenMeasurement m) {
        return m == null ? "UNAVAILABLE/-" : m.status() + "/" + (m.tokens() == null ? "-" : m.tokens());
    }

    private static List<Map.Entry<String, Counts>> ranked(Map<String, Counts> counts, int limit) {
        return counts.entrySet().stream().sorted(Comparator
                .<Map.Entry<String, Counts>>comparingLong(e -> e.getValue().payload).reversed()
                .thenComparing(Map.Entry::getKey)).limit(limit).toList();
    }

    private static String filter(InsightsQuery query, String name) {
        Matcher matcher = Pattern.compile("(?:^|\\s)" + name + ":([^\\s]+)").matcher(query.getQuestion());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int offset(InsightsQuery query) {
        String value = filter(query, "offset");
        if (value == null) return 0;
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("offset must be a nonnegative integer");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("offset exceeds the supported integer range", invalid);
        }
    }

    private static void measurementBucket(ObjectNode buckets, TokenMeasurement m) {
        String key = m == null ? "absent" : m.status() + ":" + m.tokenizerId() + "/"
                + m.tokenizerVersion() + ":" + m.method() + ":" + m.representation();
        buckets.put(key, buckets.path(key).asLong() + 1);
    }

    private static long eventTime(ModelUsageEvent e) {
        return e.epochMs() == null ? Long.MIN_VALUE : e.epochMs();
    }

    private static ObjectNode modelLedger(List<ToolCallUsage> folded, List<ToolCallUsage> selected, int limit) {
        // Deduplicate globally BEFORE tool/session filtering: parent and child can carry the same event.
        Map<String, ModelUsageEvent> events = new LinkedHashMap<>();
        long conflicts = 0;
        for (ToolCallUsage u : folded) {
            // Within one invocation, cumulative updates are appended in arrival order. For equal
            // (including absent) timestamps the last update wins. Across invocations, the folded
            // journal is newest-first, so retain the newest record when timestamps tie.
            Map<String, ModelUsageEvent> local = new LinkedHashMap<>();
            for (ModelUsageEvent e : u.modelExecutions()) {
                ModelUsageEvent old = local.get(e.eventId());
                if (old != null && eventTime(old) == eventTime(e) && !old.equals(e)) conflicts++;
                if (old == null || eventTime(e) >= eventTime(old)) local.put(e.eventId(), e);
            }
            for (ModelUsageEvent e : local.values()) {
                ModelUsageEvent old = events.get(e.eventId());
                if (old != null && eventTime(old) == eventTime(e) && !old.equals(e)) conflicts++;
                if (old == null || eventTime(e) > eventTime(old)) events.put(e.eventId(), e);
            }
        }
        Set<String> selectedIds = new TreeSet<>();
        selected.forEach(u -> u.modelExecutions().forEach(e -> selectedIds.add(e.eventId())));
        List<ModelUsageEvent> scoped = events.values().stream().filter(e -> selectedIds.contains(e.eventId()))
                .sorted(Comparator.comparingLong(ToolTokenInsights::eventTime).reversed()
                        .thenComparing(ModelUsageEvent::eventId)).toList();
        ObjectNode ledger = MAPPER.createObjectNode();
        ledger.put("uniqueExecutions", scoped.size());
        ledger.put("conflictingEvents", conflicts);
        long unreported = 0;
        String[] fields = {"inputTokens", "outputTokens", "reasoningTokens", "cacheReadTokens", "cacheWriteTokens"};
        long[] totals = new long[fields.length], missing = new long[fields.length];
        for (ModelUsageEvent e : scoped) {
            boolean absent = e.reporting() == ModelUsageEvent.UsageReporting.NOT_REPORTED
                    || e.reporting() == ModelUsageEvent.UsageReporting.UNINSTRUMENTED;
            if (absent) unreported++;
            Long[] values = {e.inputTokens(), e.outputTokens(), e.reasoningTokens(), e.cacheReadTokens(), e.cacheWriteTokens()};
            for (int i = 0; i < fields.length; i++) {
                if (absent || values[i] == null) missing[i]++;
                else totals[i] += values[i];
            }
        }
        ledger.put("unreportedExecutions", unreported);
        ledger.put("providerReportedExecutions", scoped.size() - unreported);
        ObjectNode unreportedFields = ledger.putObject("unreportedCounts");
        for (int i = 0; i < fields.length; i++) {
            ledger.put(fields[i], totals[i]);
            unreportedFields.put(fields[i], missing[i]);
        }
        ArrayNode details = ledger.putArray("events");
        scoped.stream().limit(limit).forEach(e -> details.add(e.toJsonNode(MAPPER)));
        ledger.put("hasMore", scoped.size() > limit);
        ledger.put("note", "Separate provider-reported ledger; deduplicated globally by eventId/latest epochMs; "
                + "ties use last intra-invocation update, then newest journal record. "
                + "No payload-to-model inference; cache/reasoning inclusion flags remain provider-defined.");
        return ledger;
    }
}
