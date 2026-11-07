package ai.kompile.cli.main.chat;

import ai.kompile.utils.FormatUtils;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders a project's journaled token usage as a bucketed timeline plus a per-model breakdown. Model-free. */
final class TokenUsageTimelineReport {
    private static final int BAR_WIDTH = 24;
    private static final int MODEL_WIDTH = 40;
    private static final DateTimeFormatter HEADER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private TokenUsageTimelineReport() { }

    private enum Bucket {
        HOUR("hour", DateTimeFormatter.ofPattern("MM-dd HH:00", Locale.ROOT)),
        DAY("day", DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.ROOT)),
        WEEK("week", DateTimeFormatter.ofPattern("'wk of' yyyy-MM-dd", Locale.ROOT)),
        MONTH("month", DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT));

        final String label;
        final DateTimeFormatter format;
        Bucket(String label, DateTimeFormatter format) { this.label = label; this.format = format; }

        /** Coarsest-needed granularity that keeps the timeline at or under ~60 rows. */
        static Bucket forSpan(Duration span) {
            if (span.compareTo(Duration.ofDays(2)) <= 0) return HOUR;
            if (span.compareTo(Duration.ofDays(60)) <= 0) return DAY;
            if (span.compareTo(Duration.ofDays(420)) <= 0) return WEEK;
            return MONTH;
        }

        ZonedDateTime start(ZonedDateTime at) {
            return switch (this) {
                case HOUR -> at.truncatedTo(ChronoUnit.HOURS);
                case DAY -> at.truncatedTo(ChronoUnit.DAYS);
                case WEEK -> at.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTH -> at.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            };
        }

        ZonedDateTime next(ZonedDateTime start) {
            return switch (this) {
                case HOUR -> start.plusHours(1);
                case DAY -> start.plusDays(1);
                case WEEK -> start.plusWeeks(1);
                case MONTH -> start.plusMonths(1);
            };
        }
    }

    private static final class Totals {
        long requests, input, output, cacheRead, cacheWrite;
        final Set<String> sessions = new HashSet<>();
        void add(TokenUsageJournal.Entry entry) {
            requests++;
            input += entry.input();
            output += entry.output();
            cacheRead += entry.cacheRead();
            cacheWrite += entry.cacheWrite();
            sessions.add(entry.sessionId());
        }
        long total() { return input + output + cacheRead + cacheWrite; }
    }

    static String render(TokenUsageJournal.Scan scan, String projectDirectory, Instant from, Instant to, ZoneId zone) {
        Bucket bucket = Bucket.forSpan(Duration.between(from, to));
        Map<ZonedDateTime, Totals> timeline = new LinkedHashMap<>();
        for (ZonedDateTime at = bucket.start(from.atZone(zone)); at.toInstant().isBefore(to); at = bucket.next(at)) {
            timeline.put(at, new Totals());
        }
        Map<String, Totals> byModel = new LinkedHashMap<>();
        Map<String, String> providers = new LinkedHashMap<>();
        Totals all = new Totals();
        for (TokenUsageJournal.Entry entry : scan.entries()) {
            Totals slot = timeline.get(bucket.start(entry.at().atZone(zone)));
            if (slot != null) slot.add(entry);
            String model = modelLabel(entry);
            byModel.computeIfAbsent(model, ignored -> new Totals()).add(entry);
            if (entry.provider() != null) providers.putIfAbsent(model, entry.provider());
            all.add(entry);
        }

        StringBuilder text = new StringBuilder("Token usage · project ").append(projectDirectory)
                .append(" · ").append(HEADER.format(from.atZone(zone))).append(" → ").append(HEADER.format(to.atZone(zone)))
                .append(" (").append(zone.getId()).append(") · by ").append(bucket.label).append('\n');
        text.append("Coverage: ").append(all.requests).append(" requests across ").append(all.sessions.size())
                .append(" chats (").append(scan.sessionFiles()).append(" session journals scanned). Provider-reported")
                .append(" usage journaled per request; chats from before journaling and judge/supervisor calls are not counted.\n");
        if (scan.unreadableFiles() > 0) text.append("! ").append(scan.unreadableFiles()).append(" session journals could not be read\n");
        if (scan.malformedLines() > 0) text.append("! ").append(scan.malformedLines()).append(" malformed journal lines skipped\n");
        if (all.requests == 0) {
            text.append("No token usage recorded for this project in this window.\n");
            return text.append(usage()).toString();
        }

        long peak = timeline.values().stream().mapToLong(Totals::total).max().orElse(0);
        text.append("\nTimeline (total = input + output + cache read + cache write)\n");
        for (Map.Entry<ZonedDateTime, Totals> row : timeline.entrySet()) {
            Totals totals = row.getValue();
            int width = peak == 0 || totals.total() == 0 ? 0
                    : (int) Math.max(1, Math.round((double) totals.total() * BAR_WIDTH / peak));
            text.append(String.format(Locale.ROOT, "  %-16s %-" + BAR_WIDTH + "s %8s",
                    bucket.format.format(row.getKey()), width == 0 ? "·" : "█".repeat(width), compact(totals.total())));
            if (totals.requests > 0) {
                text.append("  in ").append(compact(totals.input)).append(" · out ").append(compact(totals.output))
                        .append(" · cache ").append(compact(totals.cacheRead + totals.cacheWrite))
                        .append(" · ").append(totals.requests).append(" req");
            }
            text.append('\n');
        }

        List<Map.Entry<String, Totals>> models = new ArrayList<>(byModel.entrySet());
        models.sort((a, b) -> Long.compare(b.getValue().total(), a.getValue().total()));
        int modelWidth = Math.min(MODEL_WIDTH, Math.max(5, models.stream().mapToInt(e -> e.getKey().length()).max().orElse(5)));
        String format = "  %-" + modelWidth + "s %-12s %6s %5s %9s %9s %9s %9s %9s %6s\n";
        text.append("\nPer model (all chats in this project)\n");
        text.append(String.format(Locale.ROOT, format, "MODEL", "PROVIDER", "REQ", "CHATS",
                "INPUT", "OUTPUT", "CACHE R", "CACHE W", "TOTAL", "SHARE"));
        for (Map.Entry<String, Totals> row : models) {
            Totals totals = row.getValue();
            text.append(String.format(Locale.ROOT, format, clip(row.getKey(), modelWidth),
                    clip(providers.getOrDefault(row.getKey(), "-"), 12), totals.requests, totals.sessions.size(),
                    compact(totals.input), compact(totals.output), compact(totals.cacheRead), compact(totals.cacheWrite),
                    compact(totals.total()), String.format(Locale.ROOT, "%.1f%%", 100.0 * totals.total() / Math.max(1, all.total()))));
        }
        text.append(String.format(Locale.ROOT, format, "TOTAL", "", all.requests, all.sessions.size(),
                compact(all.input), compact(all.output), compact(all.cacheRead), compact(all.cacheWrite),
                compact(all.total()), "100%"));
        return text.append(usage()).toString();
    }

    /** Project-wide grand total for a window, its split, and each model's share; heads /dashboard-tokens. */
    static String projectTotals(TokenUsageJournal.Scan scan, String projectDirectory, Instant from, Instant to, ZoneId zone) {
        Totals all = new Totals();
        Map<String, Totals> byModel = new LinkedHashMap<>();
        for (TokenUsageJournal.Entry entry : scan.entries()) {
            all.add(entry);
            byModel.computeIfAbsent(modelLabel(entry), ignored -> new Totals()).add(entry);
        }
        StringBuilder text = new StringBuilder("Project token usage · ").append(clip(projectDirectory, 240))
                .append(" · ").append(HEADER.format(from.atZone(zone))).append(" → ").append(HEADER.format(to.atZone(zone)))
                .append(" (").append(zone.getId()).append(")\n");
        if (scan.unreadableFiles() > 0) text.append("  ! ").append(scan.unreadableFiles()).append(" session journals could not be read\n");
        if (scan.malformedLines() > 0) text.append("  ! ").append(scan.malformedLines()).append(" malformed journal lines skipped\n");
        if (all.requests == 0) {
            text.append("  No token usage recorded for this project in this window.\n");
        } else {
            text.append(String.format(Locale.ROOT, "  Total %,d tokens (%s) · in %s · out %s · cache read %s · cache write %s",
                    all.total(), compact(all.total()), compact(all.input), compact(all.output),
                    compact(all.cacheRead), compact(all.cacheWrite)))
                    .append(" · ").append(all.requests).append(" requests across ").append(all.sessions.size()).append(" chats\n");
            List<Map.Entry<String, Totals>> models = new ArrayList<>(byModel.entrySet());
            models.sort((a, b) -> Long.compare(b.getValue().total(), a.getValue().total()));
            List<String> shares = new ArrayList<>();
            for (Map.Entry<String, Totals> row : models) {
                shares.add(clip(row.getKey(), MODEL_WIDTH) + " " + compact(row.getValue().total())
                        + String.format(Locale.ROOT, " (%.1f%%)", 100.0 * row.getValue().total() / Math.max(1, all.total())));
            }
            text.append("  By model: ").append(String.join(" · ", shares)).append('\n');
        }
        return text.append("  All chats in this project; per-model table and timeline: /dashboard-timeline tokens\n").toString();
    }

    static String usage() {
        return "Filters: /dashboard-timeline tokens [from:ISO to:ISO | last N hours|days|weeks] (default: last 7 days)";
    }

    private static String modelLabel(TokenUsageJournal.Entry entry) {
        if (entry.model() != null && !entry.model().isBlank()) return entry.model().strip();
        return entry.agent() != null && !entry.agent().isBlank()
                ? entry.agent().strip() + " (model not reported)" : "(model not reported)";
    }

    private static String compact(long value) { return FormatUtils.formatCompactNumber(value); }

    private static String clip(String value, int max) {
        String plain = value.replaceAll("[\\p{Cntrl}]", " ");
        return plain.length() <= max ? plain : plain.substring(0, Math.max(0, max - 1)) + "…";
    }
}
