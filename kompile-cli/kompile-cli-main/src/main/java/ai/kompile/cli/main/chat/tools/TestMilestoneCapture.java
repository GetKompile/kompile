package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.cli.main.chat.ChatRepl.TimelineLifecycle;
import ai.kompile.cli.main.chat.TranscriptLogScope;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Harness-owned capture. A run is created only after command authorization, never from model prose. */
public final class TestMilestoneCapture implements AutoCloseable {
    private static final Map<Path, Set<TestMilestoneCapture>> ACTIVE = new HashMap<>();
    private final Path root;
    private final TestRunDetector.Plan plan;
    private final TestRunDetector detector;
    private final TestReportReader reports;
    private final Map<String, Object> revision;
    private final String executionId;
    private final String session;
    /** Non-null when the observed command ran inside a delegated subagent (task/multi_task/agent call). */
    private final String origin;
    private final String module;
    private final Instant started;
    private final Consumer<String> warning;
    private boolean finished;
    private volatile boolean parseFailed;
    private volatile boolean overlapping;

    private TestMilestoneCapture(String command, Path root, TestCaptureConfig config, TestRunDetector.Plan plan,
                                 String session, String executionId, Consumer<String> warning) {
        this(command, root, config, plan, session, executionId, null, warning);
    }

    private TestMilestoneCapture(String command, Path root, TestCaptureConfig config, TestRunDetector.Plan plan,
                                 String session, String executionId, String origin, Consumer<String> warning) {
        this.root = root;
        this.plan = plan;
        this.session = session;
        this.executionId = executionId;
        this.origin = origin == null || origin.isBlank() ? null : origin.trim();
        this.warning = warning;
        started = Instant.now();
        detector = new TestRunDetector(config, plan, command);
        revision = TestMilestoneStore.revision(plan.directory());
        module = module(root, plan.directory(), command);
        synchronized (ACTIVE) {
            Set<TestMilestoneCapture> runs = ACTIVE.computeIfAbsent(plan.directory(), ignored -> new HashSet<>());
            if (!runs.isEmpty()) { overlapping = true; runs.forEach(run -> run.overlapping = true); }
            runs.add(this);
        }
        try {
            reports = plan.explicit() ? new TestReportReader(plan.directory(), config, command, started, warning) : null;
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    public static TestMilestoneCapture start(String command, Path directory, String session, String executionId,
                                             Consumer<String> warning) {
        return start(command, directory, session, executionId, null, warning);
    }

    /** Overload tagging the milestone with the delegated execution surface that observed it. */
    public static TestMilestoneCapture start(String command, Path directory, String session, String executionId,
                                             String origin, Consumer<String> warning) {
        String transcript = TranscriptLogScope.currentTranscriptId();
        String owner = transcript == null || transcript.isBlank() ? session : transcript;
        Consumer<String> diagnostics = warning == null ? text -> System.err.println("Warning: " + text) : warning;
        Consumer<String> sink = text -> {
            publishCaptureFailure(owner, executionId, directory, text);
            diagnostics.accept(text);
        };
        try {
            Path root = TestCaptureConfig.root(directory);
            List<String> configurationWarnings = new ArrayList<>();
            TestCaptureConfig config = TestCaptureConfig.load(root, configurationWarnings::add);
            if (!configurationWarnings.isEmpty()) {
                TestRunDetector.Plan intended = TestRunDetector.plan(command, directory, TestCaptureConfig.defaults());
                // A broken observer must not turn pwd, compilation, or an arbitrary script into
                // a failed command. Only an explicit test invocation warrants capture enforcement.
                boolean testInvocation = intended != null && intended.explicit() && !intended.suppressed();
                for (String message : configurationWarnings) {
                    if (testInvocation) sink.accept(message);
                    else System.err.println("Warning: " + message);
                }
            }
            TestRunDetector.Plan plan = TestRunDetector.plan(command, directory, config);
            if (plan == null || plan.suppressed()) return null;
            return new TestMilestoneCapture(command, root, config, plan,
                    owner,
                    executionId == null ? UUID.randomUUID().toString() : executionId, origin, sink);
        } catch (RuntimeException e) {
            sink.accept("Automatic test capture could not start: " + e.getMessage());
            return null;
        }
    }

    public void accept(String line) {
        if (parseFailed) return;
        try { detector.accept(line); }
        catch (RuntimeException e) {
            parseFailed = true;
            warning.accept("Automatic test capture could not parse output: " + e.getMessage());
        }
    }

    /** Returns the saved run id, or null for build-only/unrecognized evidence. Idempotent per execution. */
    public synchronized String finish(Integer exitCode, boolean timedOut, boolean aborted, Path logReference) {
        if (finished) return null;
        finished = true;
        try {
            TestRunDetector.Result result = detector.result(exitCode, timedOut || aborted);
            if (parseFailed && result != null && !timedOut && !aborted) result = result.withOutcome("unknown");
            if (reports != null && plan.frameworks().size() == 1 && !overlapping && !timedOut && !aborted
                    && (result == null || !result.framework().equals("mixed") && Set.of("unknown", "execution_error", "passed", "failed").contains(result.outcome()))) {
                TestRunDetector.Result report = reports.fresh(plan.frameworks().iterator().next());
                if (report != null) {
                    if (result != null && result.passing() != null && report.passing() != null
                            && !result.passing().equals(report.passing())) result = result.withOutcome("unknown");
                    else result = report;
                }
            }
            if (result == null) return null;
            if (Boolean.TRUE.equals(result.passing()) && exitCode != null && exitCode != 0)
                result = result.withOutcome("unknown");
            Map<String, Object> milestone = new LinkedHashMap<>(revision);
            String id = TestMilestoneReader.newRunId();
            milestone.put("id", id);
            milestone.put("source", "harness");
            milestone.put("executionId", executionId);
            if (origin != null) milestone.put("origin", origin);
            milestone.put("framework", result.framework());
            milestone.put("outcome", timedOut ? "timed_out" : result.outcome());
            if (result.passing() != null && !timedOut && !aborted) milestone.put("passing", result.passing());
            milestone.put("basis", result.basis());
            milestone.put("module", module);
            milestone.put("startedAt", started.toString());
            milestone.put("timestamp", Instant.now().toString());
            milestone.put("durationMs", Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli()));
            milestone.put("executionDirectory", plan.directory().toString());
            if (exitCode != null) milestone.put("exitCode", exitCode);
            milestone.put("countUnit", result.unit());
            if (result.unit().equals("tests")) {
                put(milestone, "totalTests", result.total()); put(milestone, "passed", result.passed());
                put(milestone, "failed", result.failed()); put(milestone, "errors", result.errors()); put(milestone, "skipped", result.skipped());
            } else if (result.total() != null && result.passed() != null && result.failed() != null && result.skipped() != null) {
                milestone.put("observations", Map.of("total", result.total(), "passed", result.passed(), "failed", result.failed(), "skipped", result.skipped()));
            }
            if (overlapping) milestone.put("reportsIgnored", "overlapping executions share report paths");
            if (logReference != null) milestone.put("logReference", logReference.toString());
            if (reports != null && !reports.references().isEmpty()) milestone.put("reportReferences", reports.references());
            milestone.put("notes", "Automatically observed " + result.framework() + " execution: " + milestone.get("outcome"));
            TimelineLifecycle.ProducerContext producer = session == null || session.isBlank() ? null
                    : TimelineLifecycle.producer(session, session, root);
            TestMilestoneStore.save(root, milestone, producer, warning);
            return id;
        } catch (Exception e) {
            warning.accept("Automatic test milestone could not be saved: " + e.getMessage());
            return null;
        } finally {
            release();
        }
    }

    private static void publishCaptureFailure(String session, String executionId, Path directory, String message) {
        if (session == null || session.isBlank()) return;
        try {
            TimelineLifecycle.producer(session, session, directory).record("test.milestone.capture_failed",
                    executionId == null ? "capture" : executionId, Map.of("message", message));
            ai.kompile.cli.main.chat.ChatHistory.recordSystemEvent(session, "Test milestone capture warning: " + message);
        } catch (Exception ignored) {
            // The original warning still reaches the command result / diagnostic stream.
        }
    }

    private void release() {
        synchronized (ACTIVE) {
            Set<TestMilestoneCapture> runs = ACTIVE.get(plan.directory());
            if (runs != null) { runs.remove(this); if (runs.isEmpty()) ACTIVE.remove(plan.directory()); }
        }
    }

    @Override public void close() { finish(null, false, true, null); }
    private static void put(Map<String, Object> map, String key, Object value) { if (value != null) map.put(key, value); }
    private static String module(Path root, Path directory, String command) {
        String selected = null;
        for (List<String> t : TestRunDetector.segments(command)) {
            int i = t.indexOf("-pl");
            if (i >= 0 && i + 1 < t.size() && !t.get(i + 1).contains(",")) selected = t.get(i + 1).replaceFirst("^[:+]", "");
        }
        Map<String, Object> config = TestMilestoneReader.readConfig(root.resolve(TestMilestoneReader.STORE_DIR));
        String registered = null;
        int best = -1;
        if (config != null && config.get("modules") instanceof Map<?, ?> modules) {
            for (var entry : modules.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> definition) || !(definition.get("path") instanceof String path)) continue;
                Path modulePath = root.resolve(path).normalize();
                int score = directory.startsWith(modulePath) ? modulePath.getNameCount() : -1;
                if (selected != null && (selected.equals(entry.getKey()) || selected.equals(path)
                        || modulePath.getFileName() != null && selected.equals(modulePath.getFileName().toString()))) score = 10000;
                if (score > best) { best = score; registered = entry.getKey().toString(); }
            }
        }
        if (registered != null) return registered;
        if (selected != null) return selected;
        if (directory.startsWith(root) && !directory.equals(root)) return root.relativize(directory).toString();
        return directory.getFileName() == null ? "tests" : directory.getFileName().toString();
    }
}
