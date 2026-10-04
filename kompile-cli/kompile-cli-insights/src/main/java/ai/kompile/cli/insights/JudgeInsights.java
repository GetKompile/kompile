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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Judge decisions from the enforcer's session logs, {@code <sessions>/<id>/judgements.jsonl}:
 * how many verdicts flagged or stopped work, which tools drew flags, which turns were blocked,
 * and what the user overrode.
 *
 * <p>Each log is read from its end and only until its records are older than the window, so
 * a report over recent days does not parse months of history. Records are parsed by field
 * name; the record class lives with the enforcer and this library does not depend on it.</p>
 */
public final class JudgeInsights implements InsightSource {

    public static final String TOPIC = "judge";
    public static final String FILE_NAME = "judgements.jsonl";

    /** Phases whose compliant and stop fields hold a verdict; the log writes both on every record. */
    static final Set<String> VERDICT_PHASES =
            Set.of("JUDGE_TURN", "JUDGE_PARTIAL", "JUDGE_TOOL", "JUDGE_DIRECTION", "ATTEMPT");
    static final String RESULT = "RESULT";
    static final String OVERRIDE = "OVERRIDE";
    static final String CONTROL = "CONTROL";
    static final String SWAP = "SWAP";
    static final String BLOCKED = "BLOCKED";

    /** Two writers can append a record slightly out of time order. */
    private static final Duration ORDER_SLACK = Duration.ofMinutes(10);
    private static final int MAX_DETAIL = 100;
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    private final Path sessionsRoot;
    private final InsightsConfig config;
    /** Follows the panel's session log; replaced when the panel asks about another session. Guarded by this. */
    private LogFollower<Tally> follower;

    public JudgeInsights(Path sessionsRoot, InsightsConfig config) {
        this.sessionsRoot = sessionsRoot;
        this.config = config;
    }

    /**
     * Where the enforcer writes the logs, {@code ~/.kompile/sessions}, for a reader without the
     * enforcer on its classpath, such as the chat app. Resolved per call, so a test-isolated
     * {@code user.home} applies.
     */
    public static Path defaultSessionsRoot() {
        return Path.of(System.getProperty("user.home"), ".kompile", "sessions");
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String description() {
        return "judge verdicts: flags, stops, blocked turns, overrides, most-flagged tools";
    }

    @Override
    public List<String> keywords() {
        return List.of("judge", "block", "flag", "refus", "overrid", "violation", "verdict", "approv",
                "enforcer", "stop");
    }

    /** One record the report counts. */
    record Entry(Instant at, String session, String phase, String mode, boolean compliant, boolean stop,
                 String severity, String tool, String status, long latencyMs, String detail) {

        boolean verdict() {
            return VERDICT_PHASES.contains(phase);
        }

        boolean flagged() {
            return verdict() && !compliant;
        }

        /** "tool", "turn", "partial", "direction", "attempt". */
        String kind() {
            return phase.toLowerCase(Locale.ROOT).replaceFirst("^judge_", "");
        }
    }

    private static final class Scan {
        final List<Entry> entries = new ArrayList<>();
        int logsFound;
        int logsRead;
        int truncated;
        int unreadable;
    }

    private static final class Counts {
        long checks;
        long flagged;
        long stopped;
        final List<Long> latencies = new ArrayList<>();

        void add(Entry entry) {
            checks++;
            if (entry.flagged()) {
                flagged++;
            }
            if (entry.stop()) {
                stopped++;
            }
            if ("llm".equals(entry.mode()) && entry.latencyMs() > 0) {
                latencies.add(entry.latencyMs());
            }
        }
    }

    /** One session's counts for the live panel, kept up to date as its log grows. */
    static final class Tally {
        long verdicts;
        long flagged;
        long stops;
        long blocked;
        long overrides;
        /** The oldest record counted, which names where a partial read starts. */
        Instant first;
        Entry lastFlag;

        void add(Entry entry) {
            if (first == null || entry.at().isBefore(first)) {
                first = entry.at();
            }
            if (entry.verdict()) {
                verdicts++;
                stops += entry.stop() ? 1 : 0;
                if (entry.flagged()) {
                    flagged++;
                    if (lastFlag == null || !entry.at().isBefore(lastFlag.at())) {
                        lastFlag = entry;
                    }
                }
            } else if (RESULT.equals(entry.phase())) {
                blocked += BLOCKED.equals(entry.status()) ? 1 : 0;
            } else if (OVERRIDE.equals(entry.phase())) {
                overrides++;
            }
        }
    }

    @Override
    public InsightReport report(InsightsQuery query) throws IOException {
        Scan scan = scan(query);
        Set<String> tools = new TreeSet<>();
        for (Entry entry : scan.entries) {
            if (entry.tool() != null && !entry.tool().isBlank()) {
                tools.add(entry.tool());
            }
        }
        String tool = query.matchSubject(tools);
        List<Entry> entries = new ArrayList<>(tool == null ? scan.entries
                : scan.entries.stream().filter(entry -> tool.equals(entry.tool())).toList());
        entries.sort(Comparator.comparing(Entry::at).reversed());

        InsightsWindow window = query.getWindow();
        ZoneId zone = query.getZone();
        Instant earliest = entries.isEmpty() ? null : entries.get(entries.size() - 1).at();
        TimeBuckets buckets = TimeBuckets.of(window, earliest, config.getSparklineBuckets(), zone);
        long[] checksPer = new long[buckets.size()];
        long[] flaggedPer = new long[buckets.size()];
        long[] stoppedPer = new long[buckets.size()];

        Counts all = new Counts();
        long blocked = 0;
        long swaps = 0;
        Map<String, Counts> byKind = new TreeMap<>();
        Map<String, Counts> byTool = new HashMap<>();
        Map<String, Long> results = new TreeMap<>();
        Map<String, Long> overrides = new TreeMap<>();
        Map<String, Long> controls = new TreeMap<>();
        Set<String> sessions = new HashSet<>();
        List<Entry> flags = new ArrayList<>();
        for (Entry entry : entries) {
            sessions.add(entry.session());
            if (entry.verdict()) {
                all.add(entry);
                byKind.computeIfAbsent(entry.kind(), k -> new Counts()).add(entry);
                if (entry.tool() != null && !entry.tool().isBlank()) {
                    byTool.computeIfAbsent(entry.tool(), k -> new Counts()).add(entry);
                }
                int bucket = buckets.indexOf(entry.at());
                if (bucket >= 0) {
                    checksPer[bucket]++;
                    flaggedPer[bucket] += entry.flagged() ? 1 : 0;
                    stoppedPer[bucket] += entry.stop() ? 1 : 0;
                }
                if (entry.flagged() && flags.size() < config.getMaxExamples()) {
                    flags.add(entry);
                }
            } else if (RESULT.equals(entry.phase())) {
                results.merge(Format.words(entry.status()), 1L, Long::sum);
                blocked += BLOCKED.equals(entry.status()) ? 1 : 0;
            } else if (OVERRIDE.equals(entry.phase())) {
                overrides.merge(Format.words(entry.status()), 1L, Long::sum);
            } else if (CONTROL.equals(entry.phase())) {
                controls.merge(Format.words(entry.status()), 1L, Long::sum);
            } else if (SWAP.equals(entry.phase())) {
                swaps++;
            }
        }
        long overrideCount = overrides.values().stream().mapToLong(Long::longValue).sum();

        String headline = headline(tool == null ? "Judge" : "Judge on " + tool, window.label(), all, blocked,
                overrideCount, sessions.size(), scan);
        StringBuilder text = new StringBuilder(headline).append('\n');
        if (all.checks > 0) {
            text.append('\n')
                    .append("verdicts  ").append(Sparkline.of(checksPer)).append("  ").append(buckets.unit())
                    .append(" from ").append(buckets.labels().get(0))
                    .append(", peak ").append(Format.count(Values.max(checksPer))).append('\n')
                    .append("flagged   ").append(Sparkline.of(flaggedPer))
                    .append("  peak ").append(Format.count(Values.max(flaggedPer))).append('\n');

            TextTable kinds = new TextTable("judged", "verdicts", "flagged", "stopped", "p50", "p95");
            byKind.forEach((kind, counts) -> {
                long[] sorted = Values.sorted(counts.latencies);
                kinds.row(kind, Format.count(counts.checks), Format.count(counts.flagged),
                        Format.count(counts.stopped), latency(Values.percentile(sorted, 50)),
                        latency(Values.percentile(sorted, 95)));
            });
            text.append('\n').append(kinds.render());

            if (tool == null && !byTool.isEmpty()) {
                TextTable toolTable = new TextTable("tool", "checks", "flagged", "stopped");
                byTool.entrySet().stream()
                        .sorted(Comparator.<Map.Entry<String, Counts>>comparingLong(e -> e.getValue().flagged)
                                .thenComparingLong(e -> e.getValue().checks).reversed()
                                .thenComparing(Map.Entry::getKey))
                        .limit(config.getMaxRows())
                        .forEach(e -> toolTable.row(e.getKey(), Format.count(e.getValue().checks),
                                Format.count(e.getValue().flagged), Format.count(e.getValue().stopped)));
                text.append('\n').append(toolTable.render());
                if (byTool.size() > config.getMaxRows()) {
                    text.append("(+").append(byTool.size() - config.getMaxRows()).append(" more tools)\n");
                }
            }
        }

        List<String> outcomes = new ArrayList<>();
        addOutcome(outcomes, "Turn results", results);
        addOutcome(outcomes, "Overrides", overrides);
        addOutcome(outcomes, "Judge controls", controls);
        if (swaps > 0) {
            outcomes.add("Judge model swaps: " + Format.count(swaps));
        }
        if (!outcomes.isEmpty()) {
            text.append('\n');
            outcomes.forEach(line -> text.append(line).append('\n'));
        }

        if (!flags.isEmpty()) {
            TextTable latest = new TextTable("when", "judged", "tool", "severity", "reason")
                    .left(1, 2, 3, 4).maxCell(MAX_DETAIL);
            for (Entry flag : flags) {
                latest.row(Format.dateTime(flag.at(), zone), flag.kind(), flag.tool() == null ? "" : flag.tool(),
                        flag.severity() == null ? "" : flag.severity(), Format.clamp(flag.detail(), MAX_DETAIL));
            }
            text.append("\nLatest flags:\n").append(latest.render());
        }

        List<String> notes = notes(query, scan);
        if (!notes.isEmpty()) {
            text.append('\n');
            notes.forEach(note -> text.append(note).append('\n'));
        }

        ObjectNode chart = null;
        if (all.checks > 0) {
            Map<String, double[]> series = new LinkedHashMap<>();
            series.put("flagged", Charts.toDoubles(flaggedPer));
            series.put("stopped", Charts.toDoubles(stoppedPer));
            chart = Charts.bar((tool == null ? "Judge flags " : "Judge flags on " + tool + " ") + buckets.unit()
                    + ", " + window.label(), "verdicts", buckets.labels(), series);
        }
        return InsightReport.builder().topic(TOPIC).headline(headline).text(text.toString()).chart(chart).build();
    }

    private static String headline(String subject, String label, Counts all, long blocked, long overrides,
                                   int sessions, Scan scan) {
        StringBuilder line = new StringBuilder(subject).append(", ").append(label).append(": ");
        if (all.checks == 0 && blocked == 0 && overrides == 0) {
            line.append("no judge verdicts");
            line.append(scan.logsRead == 0 ? " (no session log was written in this range)"
                    : " in " + Format.count(scan.logsRead, "session log"));
            return line.toString();
        }
        line.append(Format.count(all.checks, "verdict")).append(" in ").append(Format.count(sessions, "session"));
        if (all.checks > 0) {
            line.append(", ").append(Format.count(all.flagged)).append(" flagged (")
                    .append(Format.percent(all.flagged, all.checks)).append(')');
        }
        if (all.stopped > 0) {
            line.append(", ").append(Format.count(all.stopped, "stop verdict"));
        }
        if (blocked > 0) {
            line.append(", ").append(Format.count(blocked, "turn")).append(" blocked");
        }
        if (overrides > 0) {
            line.append(", ").append(Format.count(overrides, "override"));
        }
        return line.toString();
    }

    /**
     * "Judge: 2 flagged of 12 verdicts, 1 stop, 1 override · last flag 3m ago: bash (high)", with
     * the flag's reason as the detail. The panel follows one session, the query's first.
     */
    @Override
    public synchronized Panel.Line panelLine(InsightsQuery query) throws IOException {
        Path dir = panelSessionDir(query);
        if (dir == null) {
            return null;
        }
        Path log = dir.resolve(FILE_NAME);
        if (follower == null || !follower.file().equals(log)) {
            follower = new LogFollower<>(log, config.getMaxBytesPerFile(), Tally::new, JudgeInsights::fold);
        }
        Tally tally = follower.read();
        StringBuilder text = new StringBuilder("Judge");
        if (follower.partial()) {
            text.append(tally.first == null ? " (newest " + Format.bytes(config.getMaxBytesPerFile()) + ")"
                    : " (since " + Format.dateTime(tally.first, query.getZone()) + ")");
        }
        text.append(": ");
        if (tally.verdicts == 0 && tally.blocked == 0 && tally.overrides == 0) {
            return Panel.Line.of(text.append("no verdicts yet").toString());
        }
        text.append(Format.count(tally.flagged)).append(" flagged of ").append(Format.count(tally.verdicts, "verdict"));
        if (tally.stops > 0) {
            text.append(", ").append(Format.count(tally.stops, "stop"));
        }
        if (tally.blocked > 0) {
            text.append(", ").append(Format.count(tally.blocked, "turn")).append(" blocked");
        }
        if (tally.overrides > 0) {
            text.append(", ").append(Format.count(tally.overrides, "override"));
        }
        Entry flag = tally.lastFlag;
        if (flag == null) {
            return Panel.Line.of(text.toString());
        }
        text.append(" · last flag ").append(Format.ago(flag.at(), query.getNow())).append(": ")
                .append(Format.clamp(flag.tool() == null || flag.tool().isBlank() ? flag.kind() : flag.tool(), 40));
        if (flag.severity() != null && !flag.severity().isBlank()) {
            text.append(" (").append(Format.clamp(flag.severity().toLowerCase(Locale.ROOT), 20)).append(')');
        }
        String detail = flag.detail() == null || flag.detail().isBlank() ? null
                : "↳ " + Format.clamp(flag.detail(), MAX_DETAIL);
        return new Panel.Line(text.toString(), detail, false);
    }

    /** The session's log, or its directory's creation while the session has none. */
    @Override
    public List<Panel.Watch> panelWatches(InsightsQuery query) {
        Path dir = panelSessionDir(query);
        if (dir == null) {
            return List.of();
        }
        if (Files.isDirectory(dir)) {
            return List.of(new Panel.Watch(dir, FILE_NAME));
        }
        Path root = dir.getParent();
        return Files.isDirectory(root) ? List.of(new Panel.Watch(root, dir.getFileName().toString())) : List.of();
    }

    private Path panelSessionDir(InsightsQuery query) {
        return query.sessionScoped() ? sessionDir(query.getSessionIds().iterator().next()) : null;
    }

    private List<String> notes(InsightsQuery query, Scan scan) {
        List<String> notes = new ArrayList<>();
        if (query.sessionScoped() && scan.logsFound == 0) {
            notes.add("No judge log for this session (" + String.join(", ", query.getSessionIds()) + ").");
        }
        if (scan.logsFound > scan.logsRead) {
            notes.add("Read the " + Format.count(scan.logsRead) + " most recently written of "
                    + Format.count(scan.logsFound) + " session logs (maxSessions in " + InsightsConfig.FILE_NAME + ").");
        }
        if (scan.truncated > 0) {
            notes.add(Format.count(scan.truncated, "session log") + " longer than "
                    + Format.bytes(config.getMaxBytesPerFile())
                    + (scan.truncated == 1 ? " was read from the end only; its" : " were read from the end only; their")
                    + " older records are not counted.");
        }
        if (scan.unreadable > 0) {
            notes.add(Format.count(scan.unreadable, "session log") + " could not be read.");
        }
        if (config.getWarning() != null) {
            notes.add(config.getWarning());
        }
        return notes;
    }

    private Scan scan(InsightsQuery query) throws IOException {
        InsightsWindow window = query.getWindow();
        Instant stopBefore = window.allTime() ? null : window.start().minus(ORDER_SLACK);
        List<Path> logs = logs(query);
        Scan scan = new Scan();
        scan.logsFound = logs.size();
        for (Path log : logs.subList(0, Math.min(logs.size(), config.getMaxSessions()))) {
            String sessionDir = log.getParent().getFileName().toString();
            scan.logsRead++;
            try {
                boolean cut = TailLines.newestFirst(log, config.getMaxBytesPerFile(), (buffer, offset, length) -> {
                    JsonNode node;
                    try {
                        node = MAPPER.readTree(buffer, offset, length);
                    } catch (IOException malformed) {
                        return true;
                    }
                    if (node == null || !node.isObject()) {
                        return true;
                    }
                    Instant at = Values.instant(node.path("timestamp").asText(null));
                    if (at == null) {
                        return true;
                    }
                    if (stopBefore != null && at.isBefore(stopBefore)) {
                        return false;
                    }
                    if (window.contains(at)) {
                        Entry entry = entry(node, at, sessionDir);
                        if (entry != null) {
                            scan.entries.add(entry);
                        }
                    }
                    return true;
                });
                scan.truncated += cut ? 1 : 0;
            } catch (IOException unreadable) {
                scan.unreadable++;
            }
        }
        return scan;
    }

    /** Logs written during the window, most recently written first. */
    private List<Path> logs(InsightsQuery query) throws IOException {
        Set<Path> dirs = new LinkedHashSet<>();
        if (query.sessionScoped()) {
            for (String id : query.getSessionIds()) {
                Path dir = sessionDir(id);
                if (dir != null) {
                    dirs.add(dir);
                }
            }
        } else if (sessionsRoot != null && Files.isDirectory(sessionsRoot)) {
            try (Stream<Path> listing = Files.list(sessionsRoot)) {
                listing.forEach(dirs::add);
            }
        }
        Instant start = query.getWindow().allTime() ? null : query.getWindow().start();
        Map<Path, Instant> written = new HashMap<>();
        for (Path dir : dirs) {
            Path log = dir.resolve(FILE_NAME);
            try {
                if (!Files.isRegularFile(log)) {
                    continue;
                }
                Instant modified = Files.getLastModifiedTime(log).toInstant();
                if (start == null || !modified.isBefore(start)) {
                    written.put(log, modified);
                }
            } catch (IOException vanished) {
                // Removed between the listing and the stat.
            }
        }
        List<Path> logs = new ArrayList<>(written.keySet());
        logs.sort(Comparator.comparing((Path log) -> written.get(log)).reversed());
        return logs;
    }

    /** The directory of session {@code id}, or null when the id could name anything else. */
    private Path sessionDir(String id) {
        if (sessionsRoot == null || id.contains("/") || id.contains("\\") || id.contains("..")
                || id.indexOf('\0') >= 0) {
            return null;
        }
        Path root = sessionsRoot.toAbsolutePath().normalize();
        Path dir = root.resolve(id).normalize();
        return root.equals(dir.getParent()) ? dir : null;
    }

    /** Folds one log line into the panel's tally; the follower skips a line this throws on. */
    private static void fold(Tally tally, byte[] buffer, int offset, int length) throws IOException {
        JsonNode node = MAPPER.readTree(buffer, offset, length);
        if (node == null || !node.isObject()) {
            return;
        }
        Instant at = Values.instant(node.path("timestamp").asText(null));
        Entry entry = at == null ? null : entry(node, at, null);
        if (entry != null) {
            tally.add(entry);
        }
    }

    private static Entry entry(JsonNode node, Instant at, String sessionDir) {
        String phase = node.path("phase").asText("");
        if (!VERDICT_PHASES.contains(phase) && !RESULT.equals(phase) && !OVERRIDE.equals(phase)
                && !CONTROL.equals(phase) && !SWAP.equals(phase)) {
            return null;
        }
        boolean compliant = node.path("compliant").asBoolean(true);
        String detail = null;
        if ((VERDICT_PHASES.contains(phase) && !compliant) || OVERRIDE.equals(phase)) {
            JsonNode violations = node.path("violations");
            detail = violations.isArray() && !violations.isEmpty()
                    ? violations.get(0).asText() : text(node, "reasoning");
        }
        String session = text(node, "sessionId");
        return new Entry(at, session != null ? session : sessionDir, phase, text(node, "judgeMode"), compliant,
                node.path("stop").asBoolean(false), text(node, "severity"), text(node, "toolName"),
                text(node, "status"), node.path("latencyMs").asLong(0), detail);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void addOutcome(List<String> lines, String label, Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> parts.add(Format.count(e.getValue()) + " " + e.getKey()));
        lines.add(label + ": " + String.join(", ", parts));
    }

    private static String latency(long ms) {
        return ms < 0 ? "-" : Format.duration(ms);
    }
}
