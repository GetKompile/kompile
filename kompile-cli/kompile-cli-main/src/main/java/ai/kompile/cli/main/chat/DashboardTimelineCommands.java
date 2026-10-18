package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.activity.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Per-chat, model-free timeline navigation. Following is opt-in and owns no process controls. */
public final class DashboardTimelineCommands implements AutoCloseable {
    private static final String USAGE = "Usage: /timeline [session:ID] [run:ID] [kind:TYPE] "
            + "[outcome:STATUS] [from:ISO_INSTANT] [to:ISO_INSTANT] [limit:1..100] | next | inspect:EVENT_ID | follow | stop";
    private final ActivityStorage storage;
    private final SessionTimelineService service;
    private final Supplier<ActivityIdentity> currentIdentity;
    private final Consumer<String> output;
    private final TimelineJudgeEvidence judges;
    private final Supplier<List<String>> recordingWarnings;
    private final ReadCheck readCheck;
    @FunctionalInterface public interface ReadCheck { void check() throws Exception; }
    private SessionTimelineQuery query;
    private SessionTimelineCursor cursor;
    private List<ActivityEvent> displayed = List.of();
    private ScheduledExecutorService follower;
    private ScheduledFuture<?> followTask;
    private final Set<String> seen = new LinkedHashSet<>();
    private String followStamp;
    private boolean closed;

    public DashboardTimelineCommands(ActivityStorage storage, Supplier<ActivityIdentity> currentIdentity,
                                      Consumer<String> output) {
        this(storage, currentIdentity, output, List::of, () -> { });
    }

    public DashboardTimelineCommands(ActivityStorage storage, Supplier<ActivityIdentity> currentIdentity,
                                      Consumer<String> output, Supplier<List<String>> recordingWarnings,
                                      ReadCheck readCheck) {
        this.recordingWarnings = recordingWarnings;
        this.readCheck = readCheck;
        this.storage = storage;
        this.service = new SessionTimelineService(storage);
        this.currentIdentity = currentIdentity;
        this.output = output;
        this.judges = new TimelineJudgeEvidence(storage.conversationsRoot().getParent().resolve("sessions"));
    }

    public synchronized String command(String arguments) {
        if (closed) return "Timeline view is closed.";
        String args = arguments == null ? "" : arguments.strip();
        try {
            if (args.equals("stop")) { stopFollow(); return "Timeline follow stopped."; }
            if (args.equals("next")) {
                stopFollow();
                if (cursor == null) return "No older journal page. Run /timeline to refresh recent evidence.";
                return render(query, cursor, false);
            }
            if (args.startsWith("inspect:")) { readCheck.check(); return inspect(args.substring(8)); }
            if (args.equals("follow")) {
                stopFollow();
                if (query == null) query = SessionTimelineQuery.recent(requireIdentity());
                String first = render(query, null, true);
                seen.clear();
                displayed.forEach(event -> seen.add(event.eventId()));
                followStamp = stamp(query);
                follower = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "kompile-session-timeline");
                    thread.setDaemon(true);
                    return thread;
                });
                followTask = follower.scheduleWithFixedDelay(this::followTick, 2, 2, TimeUnit.SECONDS);
                return first + "\nFollowing this view; /timeline stop closes follow mode.";
            }
            stopFollow();
            query = parse(args);
            return render(query, null, true);
        } catch (Exception failure) {
            return "Timeline unavailable: " + clean(failure.getMessage(), 200) + "\n" + USAGE;
        }
    }

    private ActivityIdentity requireIdentity() {
        ActivityIdentity identity = currentIdentity.get();
        if (identity == null) throw new IllegalStateException("Session identity is not ready");
        return identity;
    }

    private SessionTimelineQuery parse(String args) {
        ActivityIdentity identity = requireIdentity();
        Instant relativeFrom = null;
        var window = java.util.regex.Pattern.compile("(?:^|\\s)last ([0-9]+) (minutes?|hours?|days?|weeks?)$").matcher(args);
        if (window.find()) {
            long count = Long.parseLong(window.group(1));
            if (count < 1) throw new IllegalArgumentException("Time window must be positive");
            long unit = window.group(2).startsWith("minute") ? 60 : window.group(2).startsWith("hour") ? 3600
                    : window.group(2).startsWith("day") ? 86400 : 604800;
            relativeFrom = Instant.now().minusSeconds(Math.multiplyExact(count, unit));
            args = args.substring(0, window.start()).strip();
        }
        Map<String, String> selectors = new LinkedHashMap<>();
        for (String part : args.isBlank() ? new String[0] : args.split("\\s+")) {
            int colon = part.indexOf(':');
            if (colon < 1 || colon == part.length() - 1) throw new IllegalArgumentException("Invalid selector: " + part);
            String key = part.substring(0, colon);
            if (!Set.of("session", "run", "kind", "outcome", "from", "to", "limit").contains(key))
                throw new IllegalArgumentException("Unknown selector: " + key);
            if (selectors.putIfAbsent(key, part.substring(colon + 1)) != null)
                throw new IllegalArgumentException("Duplicate selector: " + key);
        }
        if (selectors.containsKey("session") && !identity.conversationId().equals(selectors.get("session")))
            identity = ActivityIdentity.conversation(selectors.get("session"), null);
        int limit = Integer.parseInt(selectors.getOrDefault("limit", "50"));
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        if (relativeFrom != null && selectors.containsKey("from"))
            throw new IllegalArgumentException("Use either from: or a relative time window, not both");
        return new SessionTimelineQuery(identity, selectors.get("run"), selectors.get("kind"),
                selectors.get("outcome"), relativeFrom != null ? relativeFrom : instant(selectors.get("from")),
                instant(selectors.get("to")), limit, 256 * 1024);
    }

    private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }

    private String render(SessionTimelineQuery requested, SessionTimelineCursor continuation, boolean withJudges)
            throws Exception {
        readCheck.check(); // Also rechecked on every opt-in follow refresh.
        SessionTimelinePage page = service.page(requested, continuation);
        cursor = page.nextCursor();
        List<ActivityEvent> rows = new ArrayList<>(page.events());
        List<String> warnings = new ArrayList<>(recordingWarnings.get());
        warnings.addAll(page.warnings());
        if (withJudges) {
            var evidence = judges.recent(requested);
            rows.addAll(evidence.events());
            warnings.addAll(evidence.warnings());
        }
        rows.sort(java.util.Comparator.comparing(ActivityEvent::timestamp).thenComparing(ActivityEvent::eventId));
        // The combined view keeps its own row cap; journal pagination is independent of supplemental evidence.
        if (rows.size() > requested.limit()) {
            // Preserve journal rows: they have already advanced the journal cursor.
            Set<String> journalIds = new LinkedHashSet<>();
            page.events().forEach(event -> journalIds.add(event.eventId()));
            int room = Math.max(0, requested.limit() - page.events().size());
            List<ActivityEvent> supplemental = rows.stream().filter(event -> !journalIds.contains(event.eventId())).toList();
            rows = new ArrayList<>(page.events());
            rows.addAll(supplemental.subList(Math.max(0, supplemental.size() - room), supplemental.size()));
            rows.sort(java.util.Comparator.comparing(ActivityEvent::timestamp).thenComparing(ActivityEvent::eventId));
            warnings.add("Supplemental judge rows clipped; use kind:judge for recent judge evidence.");
        }
        displayed = List.copyOf(rows);
        StringBuilder text = new StringBuilder("Timeline · ").append(clean(requested.identity().conversationId(), 100))
                .append(" · journal coverage: ").append(page.coverage()).append('\n');
        warnings.stream().distinct().limit(12).forEach(warning -> text.append("! ").append(clean(warning, 240)).append('\n'));
        if (rows.isEmpty()) text.append("No matching retained events; missing history is not proof of inactivity.\n");
        for (ActivityEvent event : rows) text.append(row(event)).append('\n');
        if (cursor != null) text.append("/timeline next — older journal evidence\n");
        text.append("/timeline inspect:EVENT_ID — retained source reference (never executes it)");
        return text.toString();
    }

    private String inspect(String id) {
        ActivityEvent event = displayed.stream().filter(row -> row.eventId().equals(id)).findFirst().orElse(null);
        if (event == null) return "Event is not in the displayed page; navigate to its page first.";
        StringBuilder text = new StringBuilder(row(event)).append("\nRun: ").append(clean(event.runId(), 100));
        if (event.source() != null) text.append("\nEvidence (availability at recording): ").append(event.source().available() ? "retained" : "unavailable")
                .append(" · ").append(clean(event.source().kind(), 80)).append(" · ")
                .append(clean(event.source().path(), 1024)).append("\nReference only; no command is executed.");
        else text.append("\nNo retained source reference.");
        return text.toString();
    }

    private static String row(ActivityEvent event) {
        String at = event.timestamp().equals(Instant.EPOCH) ? "time unknown" : event.timestamp().toString();
        String detail = event.attributes().getOrDefault("outcome", "unknown");
        for (String key : List.of("tool", "module", "state", "phase", "durationMs", "totalTests", "passed", "failed",
                "action", "before", "after", "processId", "exitCode", "exitCodeKnown")) {
            String value = event.attributes().get(key);
            if (value != null && !value.isBlank()) detail += " · " + key + "=" + clean(value, 60);
        }
        return clean(at, 40) + " · " + clean(event.actorId(), 60) + " · " + clean(event.eventType(), 80)
                + " · " + clean(detail, 360) + " · id:" + clean(event.eventId(), 160);
    }

    private synchronized void followTick() {
        if (closed || followTask == null) return;
        try {
            readCheck.check();
            String currentStamp = stamp(query);
            if (currentStamp.equals(followStamp)) return;
            followStamp = currentStamp;
            String report = render(query, null, true);
            boolean changed = displayed.stream().anyMatch(event -> !seen.contains(event.eventId()));
            if (changed) output.accept(report);
            displayed.forEach(event -> seen.add(event.eventId()));
            while (seen.size() > 200) seen.remove(seen.iterator().next());
        } catch (Exception failure) {
            output.accept("Timeline follow stopped: " + clean(failure.getMessage(), 200));
            stopFollow();
        }
    }

    private String stamp(SessionTimelineQuery request) throws Exception {
        Path journal = storage.eventsPath(request.identity());
        Path sessions = storage.conversationsRoot().getParent().resolve("sessions");
        Path judge = ActivitySourceRef.resolve(request.identity().conversationId() + "/judgements.jsonl", sessions);
        ActivitySourceRef.resolve(journal.toString(), storage.conversationsRoot());
        return fileStamp(journal) + ":" + fileStamp(judge);
    }

    private static String fileStamp(Path file) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "missing";
        return Files.size(file) + ":" + Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized boolean isFollowing() { return followTask != null; }

    private void stopFollow() {
        if (followTask != null) followTask.cancel(false);
        if (follower != null) follower.shutdownNow();
        followTask = null;
        follower = null;
        seen.clear();
    }

    @Override public synchronized void close() { stopFollow(); displayed = List.of(); cursor = null; closed = true; }

    private static String clean(String value, int max) {
        String plain = ai.kompile.utils.AnsiConstants.stripAnsi(value == null ? "" : value)
                .replaceAll("[\\p{Cntrl}]", " ").replaceAll("[ \\t]+", " ").strip();
        return plain.length() <= max ? plain : plain.substring(0, Math.max(0, max - 1)) + "…";
    }
}
