/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.insights;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tool calls from the tool-call index ({@code ~/.kompile/conversations/tool-calls/}): how often
 * each tool ran, how often it failed and how long it took.
 *
 * <p>Every recorder (the chat's own tools, the MCP server, agent passthrough) appends one line
 * per call to the combined index, in time order, and the same line to a per-session file. A
 * report reads the combined index from its end and stops at the window start; a report on the
 * current session reads only that session's files. A recorder that writes no durations at all
 * (an agent-side view of calls another recorder timed) is left out of the latency figures
 * instead of being counted as 0ms.</p>
 */
public final class ToolUsageInsights implements InsightSource {

    public static final String TOPIC = "tools";
    public static final String INDEX_FILE = "all-tool-calls.jsonl";
    static final String UNKNOWN = "(unknown)";

    /** Two recorders can append a call slightly out of time order. */
    private static final Duration ORDER_SLACK = Duration.ofMinutes(10);
    private static final int MAX_INPUT = 60;
    private static final int SESSION_PREFIX = 8;
    private static final String ALL = "*";
    private static final JsonFactory JSON = new JsonFactory();

    private final Path toolCallsDir;
    private final InsightsConfig config;
    /** Follows the panel's session file; replaced when the panel asks about another session. Guarded by this. */
    private LogFollower<Tally> follower;

    public ToolUsageInsights(Path toolCallsDir, InsightsConfig config) {
        this.toolCallsDir = toolCallsDir;
        this.config = config;
    }

    /** {@code ~/.kompile/conversations/tool-calls}, resolved per call so a test-isolated home applies. */
    public static Path defaultToolCallsDir() {
        return KompileHome.homeDirectory().toPath().resolve("conversations").resolve("tool-calls");
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String description() {
        return "tool calls: counts, errors and p50/p95 latency per tool, slowest calls, latest errors";
    }

    @Override
    public List<String> keywords() {
        return List.of("tool", "latency", "slow", "duration", "error", "fail", "call", "usage", "used", "p95",
                "mcp");
    }

    /** One indexed call. */
    record Call(Instant at, String tool, String source, String session, long durationMs, boolean error) {
    }

    /** A call kept as an example, with the recorder's one-line summary of its input. */
    record Example(Call call, String input) {
    }

    /** The fields a report needs from one index line. */
    record Line(Instant at, String tool, String source, String session, long durationMs, boolean error,
                String input) {
    }

    /** One session's calls for the live panel, kept up to date as its file grows. */
    static final class Tally {
        long calls;
        long errors;
        final Map<String, Long> byTool = new HashMap<>();
        /** 0ms calls per recorder; they are latency samples only from a recorder seen timing calls, as in the report. */
        final Map<String, Long> zeros = new HashMap<>();
        final Set<String> timedSources = new HashSet<>();
        long[] durations = new long[64];
        int durationCount;
        /** The oldest call counted, which names where a partial read starts. */
        Instant first;
        Line lastError;
        Line slowest;

        void add(Line line) {
            String source = line.source() == null || line.source().isBlank() ? UNKNOWN : line.source();
            calls++;
            byTool.merge(line.tool(), 1L, Long::sum);
            if (first == null || line.at().isBefore(first)) {
                first = line.at();
            }
            if (line.error()) {
                errors++;
                if (lastError == null || !line.at().isBefore(lastError.at())) {
                    lastError = line;
                }
            }
            if (line.durationMs() > 0) {
                timedSources.add(source);
                if (durationCount == durations.length) {
                    durations = Arrays.copyOf(durations, durationCount * 2);
                }
                durations[durationCount++] = line.durationMs();
                if (slowest == null || line.durationMs() > slowest.durationMs()) {
                    slowest = line;
                }
            } else if (line.durationMs() == 0) {
                zeros.merge(source, 1L, Long::sum);
            }
        }

        /** The latency samples, sorted: every positive duration, and the 0ms calls of recorders that time calls. */
        long[] samples() {
            int zeroCount = 0;
            for (Map.Entry<String, Long> entry : zeros.entrySet()) {
                zeroCount += timedSources.contains(entry.getKey()) ? entry.getValue().intValue() : 0;
            }
            long[] samples = new long[zeroCount + durationCount];
            System.arraycopy(durations, 0, samples, zeroCount, durationCount);
            Arrays.sort(samples, zeroCount, samples.length);
            return samples;
        }
    }

    private static final class Scan {
        final List<Call> calls = new ArrayList<>();
        final Set<String> timedSources = new HashSet<>();
        final Map<String, TopK<Example>> slowest = new HashMap<>();
        final Map<String, TopK<Example>> latestErrors = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        int files;
        long bytes;
        boolean truncated;
        Instant oldestRead;
        long unreadable;

        String intern(String value) {
            return value == null ? null : names.computeIfAbsent(value, v -> v);
        }
    }

    @Override
    public InsightReport report(InsightsQuery query) throws IOException {
        InsightsWindow window = query.getWindow();
        ZoneId zone = query.getZone();
        Scan scan = scan(query);

        Set<String> sources = new TreeSet<>();
        for (Call call : scan.calls) {
            sources.add(call.source());
        }
        String source = query.matchSubject(sources);
        List<Call> calls = source == null ? scan.calls
                : scan.calls.stream().filter(call -> source.equals(call.source())).toList();
        Set<String> tools = new TreeSet<>();
        for (Call call : calls) {
            tools.add(call.tool());
        }
        String tool = query.matchSubject(tools);
        boolean latency = query.mentions("slow", "latency", "p95", "p50", "duration");
        boolean errorsAsked = !latency && query.mentions("error", "fail");

        Instant earliest = calls.stream().map(Call::at).min(Comparator.naturalOrder()).orElse(null);
        TimeBuckets buckets = TimeBuckets.of(window, earliest, config.getSparklineBuckets(), zone);
        ToolStats all = new ToolStats(ALL, buckets.size());
        Map<String, ToolStats> byTool = new HashMap<>();
        for (Call call : calls) {
            int bucket = buckets.indexOf(call.at());
            boolean sample = call.durationMs() > 0
                    || (call.durationMs() == 0 && scan.timedSources.contains(call.source()));
            all.add(call, bucket, sample);
            byTool.computeIfAbsent(call.tool(), name -> new ToolStats(name, buckets.size())).add(call, bucket, sample);
        }
        all.finish();
        byTool.values().forEach(ToolStats::finish);

        String scope = source == null ? "" : " from " + source;
        StringBuilder text = new StringBuilder();
        String headline;
        ObjectNode chart = null;
        if (tool != null) {
            ToolStats stats = byTool.get(tool);
            headline = toolHeadline(tool + scope, window.label(), stats);
            text.append(headline).append('\n');
            if (stats != null) {
                appendTrend(text, stats, buckets);
                text.append('\n').append("Recorded by: ").append(sourceList(stats.sources, scan.timedSources))
                        .append('\n');
                appendExamples(text, "Slowest calls", scan.slowest.get("tool:" + tool), source, zone, true);
                appendExamples(text, "Latest errors", scan.latestErrors.get("tool:" + tool), source, zone, false);
                chart = latency && stats.samples.length > 0
                        ? latencyByBucket(tool, calls, scan.timedSources, buckets, window.label())
                        : volumeChart("Calls to " + tool + scope, stats, buckets, window.label());
            }
        } else {
            List<ToolStats> ranked = rank(byTool.values(), latency, errorsAsked);
            headline = overallHeadline(scope, window.label(), all, ranked, latency, errorsAsked);
            text.append(headline).append('\n');
            if (all.calls > 0) {
                appendTrend(text, all, buckets);
                TextTable table = new TextTable("tool", "calls", "errors", "p50", "p95", "max", "trend");
                for (ToolStats stats : ranked.subList(0, Math.min(ranked.size(), config.getMaxRows()))) {
                    table.row(stats.name, Format.count(stats.calls), Format.count(stats.errors), latencyText(stats.p50),
                            latencyText(stats.p95), latencyText(stats.max), Sparkline.of(stats.callsPer));
                }
                text.append('\n').append(table.render());
                if (ranked.size() > config.getMaxRows()) {
                    text.append("(+").append(ranked.size() - config.getMaxRows()).append(" more tools)\n");
                }
                text.append('\n').append("Recorded by: ").append(sourceList(all.sources, scan.timedSources))
                        .append('\n');
                String key = source == null ? ALL : "source:" + source;
                if (latency) {
                    appendExamples(text, "Slowest calls", scan.slowest.get(key), null, zone, true);
                } else if (errorsAsked) {
                    appendExamples(text, "Latest errors", scan.latestErrors.get(key), null, zone, false);
                }
                if (latency) {
                    chart = latencyByTool(ranked, scope, window.label());
                } else if (errorsAsked) {
                    chart = errorsByTool(ranked, scope, window.label());
                }
                if (chart == null) {
                    chart = volumeChart("Tool calls" + scope, all, buckets, window.label());
                }
            }
        }

        List<String> notes = notes(query, scan, zone);
        if (!notes.isEmpty()) {
            text.append('\n');
            notes.forEach(note -> text.append(note).append('\n'));
        }
        return InsightReport.builder().topic(TOPIC).headline(headline).text(text.toString()).chart(chart).build();
    }

    private static String toolHeadline(String subject, String label, ToolStats stats) {
        StringBuilder line = new StringBuilder("Tool ").append(subject).append(", ").append(label).append(": ");
        if (stats == null || stats.calls == 0) {
            return line.append("no calls recorded").toString();
        }
        line.append(Format.count(stats.calls, "call")).append(", ").append(Format.count(stats.errors, "error"))
                .append(" (").append(Format.percent(stats.errors, stats.calls)).append(')');
        if (stats.samples.length > 0) {
            line.append(", p50 ").append(latencyText(stats.p50)).append(", p95 ").append(latencyText(stats.p95))
                    .append(", max ").append(latencyText(stats.max));
        }
        return line.append(", in ").append(Format.count(stats.sessions.size(), "session")).toString();
    }

    /** @param ranked every tool in table order; the tool the headline names is the table's first */
    private static String overallHeadline(String scope, String label, ToolStats all, List<ToolStats> ranked,
                                          boolean latency, boolean errorsAsked) {
        StringBuilder line = new StringBuilder("Tools").append(scope).append(", ").append(label).append(": ");
        if (all.calls == 0) {
            return line.append("no tool calls recorded").toString();
        }
        line.append(Format.count(all.calls, "call")).append(" to ").append(Format.count(ranked.size(), "tool"))
                .append(", ").append(Format.count(all.errors, "error")).append(" (")
                .append(Format.percent(all.errors, all.calls)).append(')');
        if (all.samples.length > 0) {
            line.append(", median ").append(latencyText(all.p50));
        }
        line.append(", across ").append(Format.count(all.sessions.size(), "session"));
        if (latency) {
            ranked.stream().filter(stats -> stats.p95 >= 0).findFirst()
                    .ifPresent(slowest -> line.append("; slowest p95: ").append(slowest.name).append(' ')
                            .append(latencyText(slowest.p95)));
        } else if (errorsAsked) {
            ranked.stream().filter(stats -> stats.errors > 0).findFirst()
                    .ifPresent(worst -> line.append("; most errors: ").append(worst.name).append(" (")
                            .append(Format.count(worst.errors)).append(')'));
        }
        return line.toString();
    }

    private static List<ToolStats> rank(Iterable<ToolStats> stats, boolean latency, boolean errorsAsked) {
        List<ToolStats> ranked = new ArrayList<>();
        stats.forEach(ranked::add);
        Comparator<ToolStats> byCalls = Comparator.comparingLong((ToolStats s) -> s.calls).reversed();
        Comparator<ToolStats> order;
        if (latency) {
            order = Comparator.comparingLong((ToolStats s) -> s.p95).reversed().thenComparing(byCalls);
        } else if (errorsAsked) {
            order = Comparator.comparingLong((ToolStats s) -> s.errors).reversed()
                    .thenComparing(Comparator.comparingDouble(ToolStats::errorRate).reversed())
                    .thenComparing(byCalls);
        } else {
            order = byCalls;
        }
        ranked.sort(order.thenComparing(s -> s.name));
        return ranked;
    }

    private static void appendTrend(StringBuilder text, ToolStats stats, TimeBuckets buckets) {
        if (stats == null || stats.calls == 0) {
            return;
        }
        text.append('\n').append("calls   ").append(Sparkline.of(stats.callsPer)).append("  ").append(buckets.unit())
                .append(" from ").append(buckets.labels().get(0)).append(", peak ")
                .append(Format.count(Values.max(stats.callsPer))).append('\n')
                .append("errors  ").append(Sparkline.of(stats.errorsPer)).append("  peak ")
                .append(Format.count(Values.max(stats.errorsPer))).append('\n');
    }

    /** "mcp-stdio 41,004, emulated-passthrough 3,204 (no durations)", largest first. */
    private static String sourceList(Map<String, Long> sources, Set<String> timedSources) {
        List<String> parts = new ArrayList<>();
        sources.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(e -> parts.add(e.getKey() + " " + Format.count(e.getValue())
                        + (timedSources.contains(e.getKey()) ? "" : " (no durations)")));
        return String.join(", ", parts);
    }

    private static void appendExamples(StringBuilder text, String title, TopK<Example> examples, String source,
                                       ZoneId zone, boolean withDuration) {
        if (examples == null) {
            return;
        }
        List<Example> rows = examples.descending().stream()
                .filter(example -> source == null || source.equals(example.call().source()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        TextTable table = withDuration
                ? new TextTable("when", "took", "tool", "source", "session", "input").left(2, 3, 4, 5)
                : new TextTable("when", "tool", "source", "session", "input").left(1, 2, 3, 4);
        table.maxCell(MAX_INPUT);
        for (Example example : rows) {
            Call call = example.call();
            String session = call.session() == null ? ""
                    : call.session().substring(0, Math.min(SESSION_PREFIX, call.session().length()));
            if (withDuration) {
                table.row(Format.dateTime(call.at(), zone), latencyText(call.durationMs()), call.tool(), call.source(),
                        session, example.input());
            } else {
                table.row(Format.dateTime(call.at(), zone), call.tool(), call.source(), session, example.input());
            }
        }
        text.append('\n').append(title).append(":\n").append(table.render());
    }

    /**
     * "Tools: 148 calls, 3 errors · p50 120ms p95 2.1s · top: read 52, grep 31, edit 20", with the
     * latest error, or else the slowest call, as the detail. The panel follows one session, the
     * query's first, through that session's own file.
     */
    @Override
    public synchronized Panel.Line panelLine(InsightsQuery query) throws IOException {
        String session = query.sessionScoped() ? query.getSessionIds().iterator().next() : null;
        Path file = session == null ? null : sessionFile(session);
        if (file == null) {
            return null;
        }
        if (follower == null || !follower.file().equals(file)) {
            follower = new LogFollower<>(file, config.getMaxToolIndexBytes(), Tally::new,
                    (tally, buffer, offset, length) -> fold(tally, buffer, offset, length, session));
        }
        Tally tally = follower.read();
        StringBuilder text = new StringBuilder("Tools");
        if (follower.partial()) {
            text.append(tally.first == null ? " (newest " + Format.bytes(config.getMaxToolIndexBytes()) + ")"
                    : " (since " + Format.dateTime(tally.first, query.getZone()) + ")");
        }
        text.append(": ");
        if (tally.calls == 0) {
            return Panel.Line.of(text.append("no calls yet").toString());
        }
        text.append(Format.count(tally.calls, "call")).append(", ").append(Format.count(tally.errors, "error"));
        long[] samples = tally.samples();
        if (samples.length > 0) {
            text.append(" · p50 ").append(latencyText(Values.percentile(samples, 50)))
                    .append(" p95 ").append(latencyText(Values.percentile(samples, 95)));
        }
        List<String> top = new ArrayList<>();
        tally.byTool.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(3)
                .forEach(e -> top.add(Format.clamp(e.getKey(), 40) + " " + Format.count(e.getValue())));
        text.append(" · top: ").append(String.join(", ", top));

        String detail = null;
        if (tally.lastError != null) {
            detail = "↳ last error " + Format.ago(tally.lastError.at(), query.getNow()) + ": "
                    + Format.clamp(tally.lastError.tool(), 40) + inputText(tally.lastError);
        } else if (tally.slowest != null) {
            detail = "↳ slowest: " + Format.clamp(tally.slowest.tool(), 40) + " "
                    + latencyText(tally.slowest.durationMs()) + ", " + Format.ago(tally.slowest.at(), query.getNow())
                    + inputText(tally.slowest);
        }
        return new Panel.Line(text.toString(), detail, false);
    }

    @Override
    public List<Panel.Watch> panelWatches(InsightsQuery query) {
        Path file = query.sessionScoped() ? sessionFile(query.getSessionIds().iterator().next()) : null;
        return file != null && Files.isDirectory(file.getParent())
                ? List.of(new Panel.Watch(file.getParent(), file.getFileName().toString())) : List.of();
    }

    private static String inputText(Line line) {
        return line.input() == null || line.input().isBlank() ? "" : " — " + Format.clamp(line.input(), MAX_INPUT);
    }

    /** Folds one line of the session's file into the panel's tally, as the report counts it. */
    private static void fold(Tally tally, byte[] buffer, int offset, int length, String session) throws IOException {
        Line line = parse(buffer, offset, length);
        if (line != null && session.equals(line.session())) {
            tally.add(line);
        }
    }

    private List<String> notes(InsightsQuery query, Scan scan, ZoneId zone) {
        List<String> notes = new ArrayList<>();
        if (scan.files == 0) {
            if (query.sessionScoped()) {
                notes.add("No tool calls are indexed for this session (" + String.join(", ", query.getSessionIds())
                        + ").");
            } else {
                notes.add(toolCallsDir == null ? "No tool-call index is configured."
                        : "No tool-call index at " + toolCallsDir.resolve(INDEX_FILE) + ".");
            }
        }
        if (scan.truncated) {
            notes.add("Read the newest " + Format.bytes(config.getMaxToolIndexBytes()) + " of the "
                    + Format.bytes(scan.bytes) + " tool-call index; calls before "
                    + Format.dateTime(scan.oldestRead, zone) + " are not counted (maxToolIndexBytes in "
                    + InsightsConfig.FILE_NAME + ").");
        }
        if (scan.unreadable > 0) {
            notes.add(Format.count(scan.unreadable, "index line") + " could not be read.");
        }
        if (config.getWarning() != null) {
            notes.add(config.getWarning());
        }
        return notes;
    }

    private Scan scan(InsightsQuery query) throws IOException {
        Scan scan = new Scan();
        InsightsWindow window = query.getWindow();
        Instant stopBefore = window.allTime() ? null : window.start().minus(ORDER_SLACK);
        int keep = config.getMaxExamples();
        for (Path file : files(query)) {
            scan.files++;
            scan.bytes += Files.size(file);
            boolean cut = TailLines.newestFirst(file, config.getMaxToolIndexBytes(), (buffer, offset, length) -> {
                Line line;
                try {
                    line = parse(buffer, offset, length);
                } catch (JsonProcessingException malformed) {
                    scan.unreadable++;
                    return true;
                }
                if (line == null) {
                    scan.unreadable++;
                    return true;
                }
                if (stopBefore != null && line.at().isBefore(stopBefore)) {
                    return false;
                }
                scan.oldestRead = line.at();
                if (!window.contains(line.at()) || !query.inScope(line.session())) {
                    return true;
                }
                Call call = new Call(line.at(), scan.intern(line.tool()),
                        scan.intern(line.source() == null || line.source().isBlank() ? UNKNOWN : line.source()),
                        scan.intern(line.session()), line.durationMs(), line.error());
                scan.calls.add(call);
                Example example = new Example(call, line.input());
                if (call.durationMs() > 0) {
                    scan.timedSources.add(call.source());
                    offer(scan.slowest, example, keep, Comparator.comparingLong(e -> e.call().durationMs()));
                }
                if (call.error()) {
                    offer(scan.latestErrors, example, keep, Comparator.comparing(e -> e.call().at()));
                }
                return true;
            });
            scan.truncated |= cut;
        }
        return scan;
    }

    private static void offer(Map<String, TopK<Example>> examples, Example example, int keep,
                              Comparator<Example> order) {
        for (String key : List.of(ALL, "tool:" + example.call().tool(), "source:" + example.call().source())) {
            examples.computeIfAbsent(key, k -> new TopK<>(keep, order)).offer(example);
        }
    }

    /** The combined index, or the current session's own files when the report is scoped to it. */
    private List<Path> files(InsightsQuery query) {
        List<Path> files = new ArrayList<>();
        if (toolCallsDir == null) {
            return files;
        }
        if (!query.sessionScoped()) {
            Path index = toolCallsDir.resolve(INDEX_FILE);
            if (Files.isRegularFile(index)) {
                files.add(index);
            }
            return files;
        }
        for (String id : query.getSessionIds()) {
            Path file = sessionFile(id);
            if (file != null && Files.isRegularFile(file) && !files.contains(file)) {
                files.add(file);
            }
        }
        return files;
    }

    /** Session {@code id}'s own file in the index directory, or null when the id could name anything else. */
    private Path sessionFile(String id) {
        if (toolCallsDir == null || id.contains("/") || id.contains("\\") || id.contains("..")
                || id.indexOf('\0') >= 0) {
            return null;
        }
        Path root = toolCallsDir.toAbsolutePath().normalize();
        Path file = root.resolve(id + ".jsonl").normalize();
        return root.equals(file.getParent()) ? file : null;
    }

    /** The fields of one index line, or null when it has no time or no tool name. */
    static Line parse(byte[] buffer, int offset, int length) throws IOException {
        try (JsonParser parser = JSON.createParser(buffer, offset, length)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            String tool = null;
            String source = null;
            String session = null;
            String input = null;
            String timestamp = null;
            long duration = -1;
            Boolean isError = null;
            Boolean error = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "toolName" -> tool = text(parser, value);
                    case "source" -> source = text(parser, value);
                    case "sessionId" -> session = text(parser, value);
                    case "toolInputSummary" -> input = text(parser, value);
                    case "timestamp" -> timestamp = text(parser, value);
                    case "durationMs" -> duration = value != null && value.isNumeric() ? parser.getLongValue() : -1;
                    case "isError" -> isError = bool(value);
                    case "error" -> error = bool(value);
                    default -> {
                    }
                }
                // A nested value is skipped whole, whichever field holds it, so the next token is a field name.
                parser.skipChildren();
            }
            Instant at = Values.instant(timestamp);
            if (at == null || tool == null || tool.isBlank()) {
                return null;
            }
            boolean failed = isError != null ? isError : Boolean.TRUE.equals(error);
            return new Line(at, tool, source, session, duration, failed, input);
        }
    }

    /** A scalar field as text; null for JSON null and for a nested value, which the caller skips. */
    private static String text(JsonParser parser, JsonToken value) throws IOException {
        return value == null || value == JsonToken.VALUE_NULL || value.isStructStart() ? null : parser.getText();
    }

    private static Boolean bool(JsonToken value) {
        return value == JsonToken.VALUE_TRUE ? Boolean.TRUE : value == JsonToken.VALUE_FALSE ? Boolean.FALSE : null;
    }

    private static ObjectNode volumeChart(String title, ToolStats stats, TimeBuckets buckets, String label) {
        if (stats == null || stats.calls == 0) {
            return null;
        }
        Map<String, double[]> series = new LinkedHashMap<>();
        series.put("calls", Charts.toDoubles(stats.callsPer));
        series.put("errors", Charts.toDoubles(stats.errorsPer));
        return Charts.bar(title + " " + buckets.unit() + ", " + label, "calls", buckets.labels(), series);
    }

    private ObjectNode latencyByTool(List<ToolStats> ranked, String scope, String label) {
        List<ToolStats> timed = ranked.stream().filter(stats -> stats.samples.length > 0)
                .limit(config.getMaxRows()).toList();
        if (timed.isEmpty()) {
            return null;
        }
        double[] p50 = new double[timed.size()];
        double[] p95 = new double[timed.size()];
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < timed.size(); i++) {
            labels.add(timed.get(i).name);
            p50[i] = timed.get(i).p50;
            p95[i] = timed.get(i).p95;
        }
        Map<String, double[]> series = new LinkedHashMap<>();
        series.put("p50", p50);
        series.put("p95", p95);
        return Charts.bar("Slowest tools by p95" + scope + ", " + label, "ms", labels, series);
    }

    private ObjectNode errorsByTool(List<ToolStats> ranked, String scope, String label) {
        List<ToolStats> failing = ranked.stream().filter(stats -> stats.errors > 0)
                .limit(config.getMaxRows()).toList();
        if (failing.isEmpty()) {
            return null;
        }
        double[] errors = new double[failing.size()];
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < failing.size(); i++) {
            labels.add(failing.get(i).name);
            errors[i] = failing.get(i).errors;
        }
        return Charts.bar("Tool errors" + scope + ", " + label, "calls", labels, Map.of("errors", errors));
    }

    /** p50 and p95 of one tool per bucket; a bucket without timed calls is a gap. */
    private static ObjectNode latencyByBucket(String tool, List<Call> calls, Set<String> timedSources,
                                              TimeBuckets buckets, String label) {
        List<List<Long>> perBucket = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            perBucket.add(new ArrayList<>());
        }
        for (Call call : calls) {
            int bucket = buckets.indexOf(call.at());
            boolean sample = call.durationMs() > 0
                    || (call.durationMs() == 0 && timedSources.contains(call.source()));
            if (bucket >= 0 && sample && tool.equals(call.tool())) {
                perBucket.get(bucket).add(call.durationMs());
            }
        }
        double[] p50 = new double[buckets.size()];
        double[] p95 = new double[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            long[] sorted = Values.sorted(perBucket.get(i));
            p50[i] = sorted.length == 0 ? Double.NaN : Values.percentile(sorted, 50);
            p95[i] = sorted.length == 0 ? Double.NaN : Values.percentile(sorted, 95);
        }
        Map<String, double[]> series = new LinkedHashMap<>();
        series.put("p50", p50);
        series.put("p95", p95);
        return Charts.line("Latency of " + tool + " " + buckets.unit() + ", " + label, "ms", buckets.labels(),
                series);
    }

    private static String latencyText(long ms) {
        return ms < 0 ? "-" : Format.duration(ms);
    }

    /** Counts and durations of one tool, or of all tools. */
    private static final class ToolStats {
        final String name;
        final long[] callsPer;
        final long[] errorsPer;
        final Set<String> sessions = new HashSet<>();
        final Map<String, Long> sources = new TreeMap<>();
        long calls;
        long errors;
        long[] samples = new long[8];
        int sampleCount;
        long p50 = -1;
        long p95 = -1;
        long max = -1;

        ToolStats(String name, int buckets) {
            this.name = name;
            this.callsPer = new long[buckets];
            this.errorsPer = new long[buckets];
        }

        void add(Call call, int bucket, boolean sample) {
            calls++;
            errors += call.error() ? 1 : 0;
            if (bucket >= 0) {
                callsPer[bucket]++;
                errorsPer[bucket] += call.error() ? 1 : 0;
            }
            if (call.session() != null) {
                sessions.add(call.session());
            }
            sources.merge(call.source(), 1L, Long::sum);
            if (sample) {
                if (sampleCount == samples.length) {
                    samples = Arrays.copyOf(samples, sampleCount * 2);
                }
                samples[sampleCount++] = call.durationMs();
            }
        }

        void finish() {
            samples = Arrays.copyOf(samples, sampleCount);
            Arrays.sort(samples);
            p50 = Values.percentile(samples, 50);
            p95 = Values.percentile(samples, 95);
            max = samples.length == 0 ? -1 : samples[samples.length - 1];
        }

        double errorRate() {
            return calls == 0 ? 0 : (double) errors / calls;
        }
    }

    /** The {@code k} largest items under an order. */
    static final class TopK<T> {
        private final int k;
        private final Comparator<T> order;
        private final PriorityQueue<T> heap;

        TopK(int k, Comparator<T> order) {
            this.k = k;
            this.order = order;
            this.heap = new PriorityQueue<>(order);
        }

        void offer(T item) {
            if (k <= 0) {
                return;
            }
            if (heap.size() < k) {
                heap.add(item);
            } else if (order.compare(item, heap.peek()) > 0) {
                heap.poll();
                heap.add(item);
            }
        }

        List<T> descending() {
            List<T> items = new ArrayList<>(heap);
            items.sort(order.reversed());
            return items;
        }
    }
}
