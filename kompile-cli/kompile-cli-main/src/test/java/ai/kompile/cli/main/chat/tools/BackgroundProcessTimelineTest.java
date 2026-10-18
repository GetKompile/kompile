package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.activity.ActivityEvent;
import ai.kompile.cli.main.chat.activity.ActivityIdentity;
import ai.kompile.cli.main.chat.activity.ActivityRecorder;
import ai.kompile.cli.main.chat.activity.ActivityStorage;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class BackgroundProcessTimelineTest {
    private static final String SESSION = "process-timeline-session";
    @TempDir Path temp;
    private Path project;
    private ActivityStorage storage;
    private ActivityRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        project = temp.resolve("project");
        Files.createDirectories(project.resolve(".kompile"));
        storage = new ActivityStorage(temp.resolve("conversations"));
        recorder = new ActivityRecorder(storage);
    }

    private ActivityIdentity identity(String run) {
        return new ActivityIdentity("kompile", SESSION, run, "owner", "", project.toString());
    }

    private BackgroundProcessManager manager() {
        return new BackgroundProcessManager(SESSION, project);
    }

    private List<ActivityEvent> events() throws Exception {
        Path journal = storage.eventsPath(identity("ignored"));
        if (!Files.exists(journal)) return List.of();
        List<ActivityEvent> result = new ArrayList<>();
        for (String line : Files.readAllLines(journal)) {
            if (!line.isBlank()) result.add(JsonUtils.standardMapper().readValue(line, ActivityEvent.class));
        }
        return result;
    }

    @Test
    void launchAndActualExitAreDurableWithoutOutputLineOrSecretRecords() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            manager.bindTimeline(identity("launch-run"), recorder);
            CompletableFuture<ProcessEntry> exit = new CompletableFuture<>();
            manager.addExitListener(exit::complete);
            ProcessEntry entry = manager.launch("printf 'secret-output\\n'; exit 7", "secret-description", project);
            exit.get(5, TimeUnit.SECONDS);
            List<ActivityEvent> events = events();
            assertEquals(List.of("process.started", "process.terminal"),
                    events.stream().map(ActivityEvent::eventType).toList());
            assertEquals("RUNNING", events.get(0).attributes().get("state"));
            assertEquals("false", events.get(0).attributes().get("exitCodeKnown"));
            ActivityEvent terminal = events.get(1);
            assertEquals("FAILED", terminal.attributes().get("state"));
            assertEquals("7", terminal.attributes().get("exitCode"));
            assertEquals(entry.getStartTime().toString(), terminal.attributes().get("startTime"));
            assertNotNull(terminal.attributes().get("endTime"));
            assertEquals(entry.getOutputFile().toString(), terminal.source().path());
            assertTrue(terminal.source().available());
            assertTrue(events.stream().allMatch(event -> event.runId().equals("launch-run")
                    && event.identity().conversationId().equals(SESSION)));
            String journal = Files.readString(storage.eventsPath(identity("launch-run")));
            assertFalse(journal.contains("secret-output"));
            assertFalse(journal.contains("secret-description"));
            assertFalse(manager.kill(entry.getId()));
            assertEquals(List.of("process.cancel_requested", "process.cancel_refused"),
                    events().subList(2, 4).stream().map(ActivityEvent::eventType).toList());
        }
        // A new recorder/reader sees the same durable records after manager teardown.
        assertEquals(4, events().size());
        assertTrue(recorder.warnings().isEmpty());
    }

    @Test
    void acceptedCancellationDoesNotInventTerminalBeforeActualExit() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            manager.bindTimeline(identity("cancel-run"), recorder);
            CompletableFuture<ProcessEntry> exit = new CompletableFuture<>();
            manager.addExitListener(exit::complete);
            ProcessEntry entry = manager.launch("sleep 30", "sleeper", project);
            assertEquals(List.of("process.started"), events().stream().map(ActivityEvent::eventType).toList());
            assertTrue(manager.kill(entry.getId()));
            exit.get(5, TimeUnit.SECONDS);
            List<ActivityEvent> events = events();
            assertEquals(List.of("process.started", "process.cancel_requested", "process.terminal"),
                    events.stream().map(ActivityEvent::eventType).toList());
            assertEquals("RUNNING", events.get(1).attributes().get("state"));
            assertEquals("KILLED", events.get(2).attributes().get("state"));
            assertEquals("true", events.get(2).attributes().get("exitCodeKnown"));
            assertNotEquals("-1", events.get(2).attributes().get("exitCode"));
            assertEquals(-1, entry.getExitCode()); // legacy process semantics unchanged
        }
    }

    @Test
    void shutdownRecordsActualOutcomeOnceDespiteExitCallbackRace() throws Exception {
        BackgroundProcessManager manager = manager();
        try (manager) {
            manager.bindTimeline(identity("shutdown-run"), recorder);
            manager.launch("sleep 30", "sleeper", project);
        }
        List<ActivityEvent> events = events();
        assertEquals(1, events.stream().filter(event -> event.eventType().equals("process.started")).count());
        assertEquals(1, events.stream().filter(event -> event.eventType().equals("process.cancel_requested")).count());
        List<ActivityEvent> terminals = events.stream()
                .filter(event -> event.eventType().equals("process.terminal")).toList();
        assertEquals(1, terminals.size());
        assertEquals("true", terminals.get(0).attributes().get("exitCodeKnown"));
        assertEquals("KILLED", terminals.get(0).attributes().get("state"));
    }

    @Test
    void sharedMirrorRefusalLeavesOwnerStateUnchanged() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            manager.bindTimeline(identity("mirror-run"), recorder);
            ProcessEntry mirror = manager.upsertShared("shared-001", "secret-command", "secret-label",
                    123456789L, Instant.now(), ProcessState.RUNNING, null, null,
                    temp.resolve("foreign.log"), Map.of("secret", "do-not-copy"));
            assertFalse(manager.kill(mirror.getId()));
            assertFalse(manager.killByPid(mirror.getPid()));
            assertEquals(ProcessState.RUNNING, mirror.getState());
            List<ActivityEvent> events = events();
            assertEquals(List.of("process.retained", "process.cancel_requested", "process.cancel_refused",
                    "process.cancel_requested", "process.cancel_refused"),
                    events.stream().map(ActivityEvent::eventType).toList());
            assertTrue(events.stream().noneMatch(event -> event.eventType().equals("process.terminal")));
            assertTrue(events.get(0).source().path().isBlank()); // foreign log cannot escape manager root
            assertFalse(Files.readString(storage.eventsPath(identity("mirror-run"))).contains("secret"));
        }
    }

    @Test
    void restoredSnapshotIsRetainedNotLaunchedInNewRun() throws Exception {
        String id;
        try (BackgroundProcessManager first = manager()) {
            first.enableSessionHistory();
            CompletableFuture<ProcessEntry> exit = new CompletableFuture<>();
            first.addExitListener(exit::complete);
            id = first.launch("exit 0", "previous", project).getId();
            exit.get(5, TimeUnit.SECONDS);
        }
        try (BackgroundProcessManager later = manager()) {
            later.bindTimeline(identity("restored-run"), recorder);
            assertEquals(1, later.restoreSessionHistory());
            assertFalse(later.kill(id));
            List<ActivityEvent> events = events();
            assertEquals(List.of("process.retained", "process.cancel_requested", "process.cancel_refused"),
                    events.stream().map(ActivityEvent::eventType).toList());
            assertEquals("true", events.get(0).attributes().get("restored"));
            assertEquals("COMPLETED", events.get(0).attributes().get("state"));
            assertEquals("restored-run", events.get(0).runId());
        }
    }

    @Test
    void lateBindingAndVirtualCompletionDoNotSynthesizeLaunchOrDuplicateExit() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            ProcessEntry entry = manager.registerVirtual(ProcessKind.JUDGE, "secret", "secret", Map.of());
            manager.bindTimeline(identity("late-run"), recorder);
            for (int i = 0; i < 100; i++) manager.appendVirtualOutput(entry.getId(), "secret-output");
            assertTrue(manager.complete(entry.getId()));
            assertFalse(manager.complete(entry.getId()));
            assertEquals(List.of("process.retained", "process.terminal"),
                    events().stream().map(ActivityEvent::eventType).toList());
            assertEquals("0", events().get(1).attributes().get("exitCode"));
        }
    }

    @Test
    void lateBindingRetainsOnlyBoundedNewestSnapshots() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            for (int i = 0; i < 205; i++) {
                manager.registerVirtual(ProcessKind.JUDGE, "secret", "secret", Map.of());
            }
            manager.bindTimeline(identity("bounded-run"), recorder);
            assertEquals(200, events().size());
            assertTrue(events().stream().allMatch(event -> event.eventType().equals("process.retained")));
        }
    }

    @Test
    void stalePidStartSnapshotHasUnknownOutcomeAndCannotSignalReusedPid() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            var record = JsonUtils.standardMapper().createObjectNode();
            record.put("id", "proc-001");
            record.put("pid", ProcessHandle.current().pid());
            record.put("startTime", Instant.EPOCH.toString());
            record.put("osStart", Instant.EPOCH.toString());
            record.put("state", "RUNNING");
            var history = JsonUtils.standardMapper().createObjectNode();
            history.putArray("processes").add(record);
            Files.createDirectories(manager.getOutputDir());
            Files.writeString(manager.getOutputDir().resolve(BackgroundProcessManager.HISTORY_FILE),
                    JsonUtils.standardMapper().writeValueAsString(history));
            manager.bindTimeline(identity("stale-run"), recorder);
            assertEquals(1, manager.restoreSessionHistory());
            assertFalse(manager.kill("proc-001"));
            assertTrue(ProcessHandle.current().isAlive());
            ActivityEvent retained = events().get(0);
            assertEquals("process.retained", retained.eventType());
            assertEquals("true", retained.attributes().get("restored"));
            assertEquals("false", retained.attributes().get("exitCodeKnown"));
            assertFalse(retained.attributes().containsKey("exitCode"));
            assertTrue(events().stream().noneMatch(event -> event.eventType().equals("process.terminal")));
        }
    }

    @Test
    void bindingRequiresExplicitStableSessionAndRunAndDetachesWithoutStopping() throws Exception {
        try (BackgroundProcessManager manager = manager()) {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.bindTimeline(identity(""), recorder));
            assertThrows(IllegalArgumentException.class, () -> manager.bindTimeline(
                    new ActivityIdentity("kompile", "different", "run", "", "", project.toString()), recorder));
            AutoCloseable binding = manager.bindTimeline(identity("bound-run"), recorder);
            assertThrows(IllegalStateException.class, () -> manager.bindTimeline(identity("other-run"), recorder));
            ProcessEntry entry = manager.registerVirtual(ProcessKind.JUDGE, "secret", "secret", Map.of());
            binding.close();
            assertThrows(IllegalStateException.class, () -> manager.bindTimeline(identity("other-run"), recorder));
            assertTrue(entry.isRunning());
            assertTrue(manager.complete(entry.getId()));
            assertEquals(1, events().size());
        }
    }
}
