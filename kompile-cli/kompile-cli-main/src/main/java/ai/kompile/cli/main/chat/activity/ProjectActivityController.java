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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.activity;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Owns bounded background refresh and exposes the latest immutable dashboard view. */
public final class ProjectActivityController implements ProjectActivityView, AutoCloseable {

    static final long VISIBLE_REFRESH_MILLIS = 750L;
    static final long HIDDEN_REFRESH_MILLIS = 2_000L;

    private final Supplier<AgentActivitySnapshot> source;
    private final AgentActivityRenderer renderer;
    private final String currentSessionId;
    private final IntSupplier terminalWidth;
    private final long visibleRefreshMillis;
    private final long hiddenRefreshMillis;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean immediateRefreshQueued = new AtomicBoolean(false);

    private volatile AgentActivitySnapshot snapshot = AgentActivitySnapshot.empty(
            AgentActivitySnapshotService.DEFAULT_RECENT_TOOL_WINDOW);
    private volatile Runnable changeListener = () -> { };
    private volatile boolean visible;
    private volatile String filter = "";
    private volatile String refreshError = "";
    private volatile String materialState = "";

    // Historical browser state is deliberately separate from the live snapshot. A
    // redraw never reindexes or loads transcript/diff payloads for every row.
    private volatile ConversationActivityService conversationActivityService;
    private volatile ActivityIdentity activityIdentity;
    private volatile java.nio.file.Path activityProjectRoot;
    private volatile BrowserMode browserMode = BrowserMode.LIVE;
    private volatile List<ConversationActivitySummary> browserRows = List.of();
    private volatile int browserOffset;
    private volatile String browserFilter = "";
    private volatile ConversationActivitySummary browserSession;
    private volatile ConversationActivityDetail browserDetail;
    private volatile TranscriptActivityReader.TranscriptInspection browserTranscript;

    private enum BrowserMode { LIVE, PROJECT, GLOBAL, SESSION, DETAIL, TRANSCRIPT }

    public ProjectActivityController(AgentActivitySnapshotService source,
                                     String currentSessionId,
                                     IntSupplier terminalWidth) {
        this(source::capture, new AgentActivityRenderer(), currentSessionId, terminalWidth,
                VISIBLE_REFRESH_MILLIS, HIDDEN_REFRESH_MILLIS,
                newExecutor());
    }

    ProjectActivityController(Supplier<AgentActivitySnapshot> source,
                              AgentActivityRenderer renderer,
                              String currentSessionId,
                              IntSupplier terminalWidth,
                              long visibleRefreshMillis,
                              long hiddenRefreshMillis,
                              ScheduledExecutorService executor) {
        this.source = Objects.requireNonNull(source, "source");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.currentSessionId = currentSessionId == null ? "" : currentSessionId;
        this.terminalWidth = Objects.requireNonNull(terminalWidth, "terminalWidth");
        this.visibleRefreshMillis = requirePositive(visibleRefreshMillis, "visibleRefreshMillis");
        this.hiddenRefreshMillis = requirePositive(hiddenRefreshMillis, "hiddenRefreshMillis");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void start(Runnable listener) {
        if (closed.get()) return;
        changeListener = listener == null ? () -> { } : listener;
        if (started.compareAndSet(false, true)) {
            scheduleNext(0L);
        }
    }

    @Override
    public String compactStatus() {
        if (browserMode != BrowserMode.LIVE) {
            return "history " + browserMode.name().toLowerCase(java.util.Locale.ROOT)
                    + (browserRows.isEmpty() ? " · no rows" : " · " + browserRows.size() + " rows");
        }
        String status = renderer.compactStatus(snapshot);
        return refreshError.isBlank() ? status : status + " · stale";
    }

    @Override
    public String title() {
        if (browserMode != BrowserMode.LIVE) {
            return browserTitle();
        }
        return renderer.title(filter);
    }

    @Override
    public String content() {
        if (browserMode != BrowserMode.LIVE) {
            return browserContent();
        }
        int reportedWidth = terminalWidth.getAsInt();
        int width = Math.max(24, Math.min(200, reportedWidth > 0 ? reportedWidth : 100));
        String rendered = renderer.render(snapshot, filter, currentSessionId, width);
        if (refreshError.isBlank()) return rendered;
        String warning = "  ⚠ Last refresh failed; showing previous snapshot: " + refreshError;
        if (warning.length() > width) warning = warning.substring(0, width - 1) + "…";
        return rendered + "\n\n" + warning;
    }

    @Override
    public boolean hasLiveWork() {
        return browserMode == BrowserMode.LIVE && snapshot.hasActivity();
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void show(String filter) {
        this.filter = filter == null ? "" : filter.strip();
        this.visible = true;
        if (browserMode != BrowserMode.LIVE) {
            this.browserFilter = this.filter;
            refreshBrowser();
            return;
        }
        refresh();
    }

    @Override
    public void hide() {
        visible = false;
    }

    @Override
    public void refresh() {
        if (browserMode != BrowserMode.LIVE) {
            refreshBrowser();
            return;
        }
        if (closed.get() || !started.get()
                || !immediateRefreshQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    captureAndNotify();
                } finally {
                    immediateRefreshQueued.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            immediateRefreshQueued.set(false);
        }
    }

    public AgentActivitySnapshot snapshot() {
        return snapshot;
    }

    /** Configure the shared read-only conversation browser used by both chat modes. */
    public void setConversationActivityBrowser(ConversationActivityService service,
                                               ActivityIdentity identity,
                                               java.nio.file.Path projectRoot) {
        this.conversationActivityService = service;
        this.activityIdentity = identity;
        this.activityProjectRoot = projectRoot == null ? null
                : projectRoot.toAbsolutePath().normalize();
        if (service != null && identity != null) service.setCurrentSession(identity);
    }

    public boolean isHistoricalBrowserVisible() {
        return browserMode != BrowserMode.LIVE;
    }

    /** Execute a bounded read-only browser command and return the rendered view. */
    public synchronized String openConversationActivity(String command) {
        ConversationActivityService service = conversationActivityService;
        if (service == null) return "Activity history unavailable: reader is not configured";
        String input = command == null ? "" : command.strip();
        String[] parts = input.isBlank() ? new String[]{"project", ""} : input.split("\\s+", 2);
        String action = parts[0].toLowerCase(java.util.Locale.ROOT);
        String value = parts.length > 1 ? parts[1].strip() : "";
        try {
            switch (action) {
                case "project" -> {
                    browserMode = BrowserMode.PROJECT;
                    browserOffset = 0;
                    browserFilter = value;
                    browserSession = null;
                    browserDetail = null;
                    browserTranscript = null;
                    refreshBrowser();
                }
                case "global", "history", "outcomes" -> {
                    browserMode = BrowserMode.GLOBAL;
                    browserOffset = 0;
                    browserFilter = value;
                    browserSession = null;
                    browserDetail = null;
                    browserTranscript = null;
                    refreshBrowser();
                }
                case "session", "open" -> {
                    ActivityIdentity selected = identityFor(value);
                    browserSession = service.session(selected).orElse(
                            ConversationActivitySummary.empty(selected));
                    activityIdentity = selected;
                    browserDetail = null;
                    browserTranscript = null;
                    browserMode = BrowserMode.SESSION;
                }
                case "detail", "enter" -> {
                    ActivityIdentity selected = browserSession == null
                            ? identityFor(value) : browserSession.identity();
                    browserSession = service.session(selected).orElse(
                            ConversationActivitySummary.empty(selected));
                    browserDetail = service.detail(selected, ActivityReadBudget.DEFAULT);
                    activityIdentity = selected;
                    browserTranscript = null;
                    browserMode = BrowserMode.DETAIL;
                }
                case "transcript" -> openTranscript(service, value);
                case "next" -> page(1);
                case "previous", "prev" -> page(-1);
                case "search" -> {
                    browserFilter = value;
                    browserOffset = 0;
                    if (browserMode == BrowserMode.LIVE || browserMode == BrowserMode.SESSION
                            || browserMode == BrowserMode.DETAIL || browserMode == BrowserMode.TRANSCRIPT) {
                        browserMode = BrowserMode.GLOBAL;
                    }
                    refreshBrowser();
                }
                case "confirm" -> {
                    ActivityIdentity selected = browserSession == null
                            ? identityFor("") : browserSession.identity();
                    String[] confirmation = value.split("\\s+", 2);
                    String status = confirmation.length == 0 ? "UNVERIFIED" : confirmation[0];
                    String text = confirmation.length > 1 ? confirmation[1] : "User confirmed outcome";
                    service.confirmOutcome(selected, status, text, "user", List.of());
                    browserSession = service.session(selected).orElseThrow();
                    browserMode = BrowserMode.SESSION;
                }
                case "annotate" -> {
                    ActivityIdentity selected = browserSession == null
                            ? identityFor("") : browserSession.identity();
                    service.annotateOutcome(selected, value, value, "user", List.of());
                    browserSession = service.session(selected).orElseThrow();
                    browserMode = BrowserMode.SESSION;
                }
                case "close", "hide", "off" -> closeBrowser();
                default -> {
                    return "Usage: /activity [session <id> | project | global | transcript <path>"
                            + " | detail | search <text> | next | previous | confirm <status> <text> | close]";
                }
            }
        } catch (Exception failure) {
            return "Activity history unavailable: " + ActivityToolText.boundedClean(
                    failure.getMessage(), 240);
        }
        return browserContent();
    }

    public void closeBrowser() {
        browserMode = BrowserMode.LIVE;
        browserRows = List.of();
        browserSession = null;
        browserDetail = null;
        browserTranscript = null;
        browserFilter = "";
        browserOffset = 0;
    }

    String filter() {
        return filter;
    }

    String refreshError() {
        return refreshError;
    }

    void captureNow() {
        captureAndNotify();
    }

    private void scheduleNext(long delayMillis) {
        if (closed.get()) return;
        try {
            executor.schedule(() -> {
                if (closed.get()) return;
                captureAndNotify();
                scheduleNext(visible ? visibleRefreshMillis : hiddenRefreshMillis);
            }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Close can race the recursive reschedule after a capture.
        }
    }

    private void captureAndNotify() {
        if (closed.get()) return;
        String previousError = refreshError;
        boolean changed = false;
        try {
            AgentActivitySnapshot next = source.get();
            if (next != null) {
                String nextMaterialState = materialState(next);
                changed = !nextMaterialState.equals(materialState);
                snapshot = next;
                materialState = nextMaterialState;
            }
            refreshError = "";
        } catch (RuntimeException e) {
            refreshError = oneLine(e.getMessage());
        }
        boolean errorChanged = !previousError.equals(refreshError);
        if (!visible && !changed && !errorChanged) return;
        try {
            changeListener.run();
        } catch (RuntimeException ignored) {
            // A closing terminal must not terminate the refresh scheduler.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        visible = false;
        closeBrowser();
        changeListener = () -> { };
        executor.shutdownNow();
    }

    private void refreshBrowser() {
        ConversationActivityService service = conversationActivityService;
        if (service == null || browserMode == BrowserMode.LIVE) return;
        if (browserMode == BrowserMode.PROJECT || browserMode == BrowserMode.GLOBAL) {
            List<ConversationActivitySummary> rows = browserMode == BrowserMode.PROJECT
                    ? service.projectSummaries(activityProjectRoot, browserOffset, 50)
                    : service.globalSummaries(browserOffset, 50);
            String query = browserFilter == null ? "" : browserFilter.strip().toLowerCase(java.util.Locale.ROOT);
            if (!query.isBlank()) {
                rows = rows.stream().filter(row -> searchable(row).contains(query)).toList();
            }
            browserRows = List.copyOf(rows);
        }
    }

    private void page(int direction) {
        if (browserMode != BrowserMode.PROJECT && browserMode != BrowserMode.GLOBAL) return;
        int next = Math.max(0, browserOffset + direction * 50);
        if (direction > 0) {
            ConversationActivityService service = conversationActivityService;
            List<ConversationActivitySummary> probe = browserMode == BrowserMode.PROJECT
                    ? service.projectSummaries(activityProjectRoot, next, 1)
                    : service.globalSummaries(next, 1);
            if (probe.isEmpty()) return;
        }
        browserOffset = next;
        refreshBrowser();
    }

    private void openTranscript(ConversationActivityService service, String value) throws java.io.IOException {
        if (value.isBlank()) throw new java.io.IOException("Usage: /activity transcript <path>");
        java.nio.file.Path requested = java.nio.file.Path.of(value).toAbsolutePath().normalize();
        List<java.nio.file.Path> roots = new ArrayList<>();
        roots.add(service.storage().conversationsRoot());
        if (activityProjectRoot != null) roots.add(activityProjectRoot);
        java.io.IOException last = null;
        for (java.nio.file.Path root : roots) {
            try {
                browserTranscript = service.inspectTranscript(requested, root, ActivityReadBudget.DEFAULT);
                browserMode = BrowserMode.TRANSCRIPT;
                browserSession = null;
                browserDetail = null;
                return;
            } catch (java.io.IOException failure) {
                last = failure;
            }
        }
        throw last == null ? new java.io.IOException("Transcript path is not permitted") : last;
    }

    private ActivityIdentity identityFor(String token) {
        String value = token == null ? "" : token.strip();
        if (value.isBlank() && activityIdentity != null) return activityIdentity;
        int separator = value.indexOf(':');
        if (separator > 0 && separator + 1 < value.length()) {
            return new ActivityIdentity(value.substring(0, separator), value.substring(separator + 1),
                    "", "", "", activityProjectRoot == null ? "" : activityProjectRoot.toString());
        }
        return ActivityIdentity.conversation(value, activityProjectRoot);
    }

    private String browserTitle() {
        return switch (browserMode) {
            case PROJECT -> "Activity · project";
            case GLOBAL -> "Activity · global history";
            case SESSION -> "Activity · session " + (browserSession == null
                    ? "unknown" : browserSession.identity().conversationId());
            case DETAIL -> "Activity · detail " + (browserDetail == null
                    ? "unknown" : browserDetail.summary().identity().conversationId());
            case TRANSCRIPT -> "Activity · transcript (read-only)";
            default -> renderer.title(filter);
        };
    }

    private String browserContent() {
        return switch (browserMode) {
            case PROJECT, GLOBAL -> renderRows(browserRows, browserMode, browserOffset, browserFilter);
            case SESSION -> renderSummary(browserSession);
            case DETAIL -> renderDetail(browserDetail);
            case TRANSCRIPT -> renderTranscript(browserTranscript);
            default -> "";
        };
    }

    private static String renderRows(List<ConversationActivitySummary> rows, BrowserMode mode,
                                     int offset, String filter) {
        StringBuilder out = new StringBuilder();
        out.append("  ").append(mode == BrowserMode.PROJECT ? "Project" : "Global")
                .append(" activity · read-only · rows ").append(offset + 1).append("-")
                .append(offset + rows.size()).append("\n");
        if (filter != null && !filter.isBlank()) out.append("  filter: ")
                .append(ActivityToolText.boundedClean(filter, 120)).append("\n");
        if (rows.isEmpty()) {
            out.append("  (no retained activity rows; unavailable data is not reported as zero)\n");
        } else {
            int index = offset + 1;
            for (ConversationActivitySummary row : rows) {
                out.append("  [").append(index++).append("] ")
                        .append(ActivityToolText.boundedClean(row.identity().key(), 100))
                        .append(" · ").append(ActivityToolText.boundedClean(row.title(), 72))
                        .append(" · outcome=").append(row.outcome())
                        .append(" (").append(row.evidenceBasis()).append(")")
                        .append(" · elapsed=").append(metric(row.elapsed()))
                        .append(" · tokens=").append(metric(row.tokens()))
                        .append(" · edits=").append(metric(row.edits())).append("\n");
            }
        }
        out.append("  PageUp/PageDown or /activity next|previous · /activity session <id>");
        return out.toString();
    }

    public static String renderSummary(ConversationActivitySummary summary) {
        if (summary == null) return "  Activity summary unavailable";
        StringBuilder out = new StringBuilder();
        out.append("Goal: ").append(ActivityToolText.boundedClean(summary.goal(), 1_000)).append("\n")
                .append("Outcome: ").append(summary.outcome()).append(" | ")
                .append(summary.evidenceBasis()).append("\n")
                .append("Execution: ").append(summary.executionState()).append("\n")
                .append("Elapsed: ").append(metric(summary.elapsed()))
                .append(" | blocking: ").append(metric(summary.blocking())).append("\n")
                .append("Tokens: ").append(metric(summary.tokens()))
                .append(" | changes: ").append(metric(summary.edits()))
                .append(" | issues: ").append(metric(summary.issues())).append("\n")
                .append("Session: ").append(ActivityToolText.boundedClean(summary.identity().key(), 160)).append("\n");
        if (!summary.coverage().isEmpty()) {
            out.append("Coverage: ");
            summary.coverage().forEach((source, coverage) -> out.append(source).append("=")
                    .append(coverage).append(" "));
            out.append("\n");
        }
        if (!summary.evidence().isEmpty()) {
            out.append("Evidence:\n");
            summary.evidence().forEach(ref -> out.append("  - ")
                    .append(ActivityToolText.boundedClean(ref.namespace(), 40)).append(":")
                    .append(ActivityToolText.boundedClean(ref.identifier(), 120))
                    .append(" [").append(ref.available() ? "available" : "unavailable").append("]")
                    .append(ref.path().isBlank() ? "" : " -> " + ActivityToolText.boundedClean(ref.path(), 180))
                    .append("\n"));
        }
        if (!summary.annotations().isEmpty()) {
            out.append("Annotations:\n");
            summary.annotations().forEach(annotation -> out.append("  - ")
                    .append(annotation.kind()).append(" ").append(annotation.status())
                    .append(" by ").append(ActivityToolText.boundedClean(annotation.actorId(), 80))
                    .append(": ").append(ActivityToolText.boundedClean(annotation.text(), 320)).append("\n"));
        }
        summary.warnings().forEach(warning -> out.append("Warning: ")
                .append(ActivityToolText.boundedClean(warning, 240)).append("\n"));
        out.append("Read-only. Use /activity detail for retained events; /activity confirm only records explicit user confirmation.");
        return out.toString();
    }

    public static String renderDetail(ConversationActivityDetail detail) {
        if (detail == null) return "  Activity detail unavailable";
        StringBuilder out = new StringBuilder(renderSummary(detail.summary())).append("\n\nEvents:\n");
        if (detail.events().isEmpty()) out.append("  (no retained lifecycle events)\n");
        detail.events().forEach(event -> out.append("  ").append(event.sequence()).append(" ")
                .append(event.timestamp()).append(" ").append(event.eventType())
                .append(event.operationId().isBlank() ? "" : " op=" + event.operationId())
                .append(event.attributes().isEmpty() ? "" : " " + event.attributes()).append("\n"));
        if (!detail.details().isEmpty()) {
            out.append("\nRecorded evidence details:\n");
            detail.details().forEach(source -> out.append(ActivityToolText.boundedClean(source, 8_000))
                    .append("\n"));
        }
        if (detail.malformedEvents() > 0 || detail.truncated()) {
            out.append("Coverage: ").append(detail.truncated() ? "PARTIAL/truncated" : "PARTIAL")
                    .append("; malformed events=").append(detail.malformedEvents()).append("\n");
        }
        detail.warnings().forEach(warning -> out.append("Warning: ")
                .append(ActivityToolText.boundedClean(warning, 240)).append("\n"));
        return out.toString().stripTrailing();
    }

    public static String renderTranscript(TranscriptActivityReader.TranscriptInspection transcript) {
        if (transcript == null) return "  Transcript unavailable";
        StringBuilder out = new StringBuilder("Read-only transcript\n");
        if (!transcript.title().isBlank()) out.append("Title: ")
                .append(ActivityToolText.boundedClean(transcript.title(), 300)).append("\n");
        if (!transcript.agent().isBlank()) out.append("Agent: ")
                .append(ActivityToolText.boundedClean(transcript.agent(), 120)).append("\n");
        transcript.turns().forEach(turn -> out.append("\n").append(turn.role()).append(":\n")
                .append(ActivityToolText.boundedClean(turn.content(), 4_000)).append("\n"));
        if (transcript.truncated()) out.append("\nCoverage: PARTIAL (read bound reached)\n");
        return out.toString().stripTrailing();
    }

    private static String metric(ActivityMetric metric) {
        if (metric == null || !metric.known()) return "UNAVAILABLE";
        return Long.toString(metric.value()) + (metric.estimated() ? " (estimated)" : "");
    }

    private static String searchable(ConversationActivitySummary summary) {
        return (summary.identity().key() + " " + summary.title() + " " + summary.goal() + " "
                + summary.outcome() + " " + summary.evidenceBasis()).toLowerCase(java.util.Locale.ROOT);
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "project-activity-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        String line = value.replace('\n', ' ').replace('\r', ' ').strip();
        return line.length() <= 160 ? line : line.substring(0, 159) + "…";
    }

    private static String materialState(AgentActivitySnapshot snapshot) {
        StringBuilder state = new StringBuilder();
        append(state, snapshot.recentToolWindow());
        snapshot.warnings().forEach(warning -> append(state, warning));
        for (AgentActivitySnapshot.AgentActivity agent : snapshot.agents()) {
            append(state, agent.coordinationSessionId());
            append(state, agent.toolSessionId());
            append(state, agent.agentName());
            append(state, agent.roleName());
            append(state, agent.agentType());
            append(state, agent.parentSessionId());
            append(state, agent.depth());
            append(state, agent.task());
            append(state, agent.pid());
            append(state, agent.startedAt());
            append(state, agent.registered());
            append(state, agent.processAlive());
            append(state, agent.malformedToolLines());
            for (AgentActivitySnapshot.ProcessActivity process : agent.processes()) {
                append(state, process.ownerSessionId());
                append(state, process.processId());
                append(state, process.agentName());
                append(state, process.roleName());
                append(state, process.kind());
                append(state, process.command());
                append(state, process.description());
                append(state, process.pid());
                append(state, process.state());
                append(state, process.startedAt());
                append(state, process.endedAt());
                append(state, process.exitCode());
                append(state, process.outputFile());
                append(state, process.running());
                append(state, process.pidAlive());
            }
            for (var tool : agent.recentTools()) {
                append(state, tool.id());
                append(state, tool.timestamp());
                append(state, tool.toolName());
                append(state, tool.inputSummary());
                append(state, tool.error());
                append(state, tool.durationMs());
            }
        }
        return state.toString();
    }

    private static void append(StringBuilder state, Object value) {
        String text = String.valueOf(value);
        state.append(text.length()).append(':').append(text).append('|');
    }
}
