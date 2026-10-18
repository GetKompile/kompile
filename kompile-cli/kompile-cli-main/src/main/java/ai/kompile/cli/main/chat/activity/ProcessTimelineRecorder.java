package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.LifecycleEvent;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.LifecycleListener;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Writes transition metadata only to the existing durable activity journal.
 * No output-line listener, command text, environment, description, or metadata is copied.
 * No event backlog is held in memory; journal retention remains ActivityRecorder's concern.
 */
public final class ProcessTimelineRecorder implements LifecycleListener {
    @FunctionalInterface
    private interface Sink {
        void record(ActivityIdentity identity, String type, String actor, String operation,
                    ActivitySourceRef source, Map<String, String> attributes);
    }

    private final ActivityIdentity identity;
    private final Path logRoot;
    private final Sink sink;

    public ProcessTimelineRecorder(ActivityIdentity identity, ActivityRecorder recorder, Path logRoot) {
        this(identity, logRoot, Objects.requireNonNull(recorder, "recorder")::record);
    }

    public ProcessTimelineRecorder(ActivityIdentity identity, ConversationActivityService service, Path logRoot) {
        this(identity, logRoot, Objects.requireNonNull(service, "service")::record);
    }

    private ProcessTimelineRecorder(ActivityIdentity identity, Path logRoot, Sink sink) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (identity.runId().isBlank()) throw new IllegalArgumentException("Explicit runId required");
        this.logRoot = Objects.requireNonNull(logRoot, "logRoot");
        this.sink = sink;
    }

    @Override
    public synchronized void onLifecycleEvent(LifecycleEvent event) {
        Objects.requireNonNull(event, "event");
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("processId", bounded(event.id()));
        attrs.put("state", event.state().name());
        attrs.put("kind", event.kind().name());
        attrs.put("restored", Boolean.toString(event.restored()));
        attrs.put("observedAt", event.observedAt().toString());
        if (event.startTime() != null) attrs.put("startTime", event.startTime().toString());
        if (event.endTime() != null) attrs.put("endTime", event.endTime().toString());
        attrs.put("exitCodeKnown", Boolean.toString(event.exitCode() != null));
        if (event.exitCode() != null) attrs.put("exitCode", event.exitCode().toString());
        String outcome = "unknown";
        if (event.transition() == ai.kompile.cli.main.chat.tools.BackgroundProcessManager.LifecycleTransition.TERMINAL
                && event.exitCode() != null) {
            outcome = event.state() == ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessState.KILLED
                    ? "cancelled" : event.exitCode() == 0 ? "succeeded" : "failed";
        } else if (event.transition() == ai.kompile.cli.main.chat.tools.BackgroundProcessManager.LifecycleTransition.CANCEL_REFUSED) {
            outcome = "refused";
        }
        attrs.put("outcome", outcome);
        ActivitySourceRef ref = ActivitySourceRef.file(identity.source(), bounded(event.id()),
                "process-log", event.logReference(), logRoot);
        if (!ref.path().isBlank()) attrs.put("logReference", ref.path());
        String type = switch (event.transition()) {
            case STARTED -> "process.started";
            case RETAINED -> "process.retained";
            case TERMINAL -> "process.terminal";
            case OUTCOME_UNKNOWN -> "process.outcome_unknown";
            case CANCEL_REQUESTED -> "process.cancel_requested";
            case CANCEL_REFUSED -> "process.cancel_refused";
        };
        sink.record(identity, type, identity.actorId(), bounded(event.id()), ref, Map.copyOf(attrs));
    }

    private static String bounded(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.length() <= 240 ? clean : clean.substring(0, 240);
    }
}
