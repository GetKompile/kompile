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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-session process history: the web chat starts a harness per message, so a
 * later run of a session lists, tails and stops what an earlier run of it launched.
 */
@DisabledOnOs(OS.WINDOWS)
class BackgroundProcessManagerSessionHistoryTest {

    private static final String SESSION = "history-session";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        // A project .kompile keeps the logs and the history inside the temp dir.
        project = temp.resolve("project");
        Files.createDirectories(project.resolve(".kompile"));
    }

    @Test
    void aLaterRunListsAndTailsWhatAnEarlierRunLaunched() throws Exception {
        ProcessEntry first;
        try (BackgroundProcessManager run = manager()) {
            assertEquals(0, run.enableSessionHistory());
            first = run.launch("echo history-output", "say it", project);
            awaitExit(first);
        }

        try (BackgroundProcessManager later = manager()) {
            assertEquals(1, later.restoreSessionHistory());
            ProcessEntry restored = later.get(first.getId());
            assertNotNull(restored);
            assertTrue(restored.isVirtual());
            assertEquals(ProcessKind.COMMAND, restored.getKind());
            assertEquals(ProcessState.COMPLETED, restored.getState());
            assertEquals(0, restored.getExitCode());
            assertEquals("echo history-output", restored.getCommand());
            assertEquals("say it", restored.getDescription());
            assertEquals(first.getPid(), restored.getPid());
            assertEquals(first.getStartTime(), restored.getStartTime());
            assertEquals(first.getEndTime(), restored.getEndTime());
            assertEquals(first.getOutputFile(), restored.getOutputFile());
            assertFalse(restored.isKillable());
            assertFalse(later.kill(first.getId()));
            assertTrue(later.readOutput(first.getId(), 10).contains("history-output"));
        }
    }

    @Test
    void aRunRecordsItsLaunchesAsTheyStartAndTheirFinalStatesWhenItCloses() throws Exception {
        long pid;
        try (BackgroundProcessManager run = manager()) {
            run.enableSessionHistory();
            ProcessEntry sleeper = run.launch("sleep 30", "long job", project);
            pid = sleeper.getPid();
            JsonNode started = onlyRecord();
            assertEquals(sleeper.getId(), started.path("id").asText());
            assertEquals("sleep 30", started.path("command").asText());
            assertEquals("long job", started.path("description").asText());
            assertEquals("RUNNING", started.path("state").asText());
            assertEquals(pid, started.path("pid").asLong());
            assertEquals(sleeper.getStartTime(), Instant.parse(started.path("startTime").asText()));
            assertEquals(ProcessHandle.of(pid).orElseThrow().info().startInstant().orElseThrow(),
                    Instant.parse(started.path("osStart").asText()));
            assertFalse(started.has("endTime"));
            assertFalse(started.has("exitCode"));
        }

        // Closing the run stops what it launched and records that.
        awaitDeath(pid);
        JsonNode closed = onlyRecord();
        assertEquals("KILLED", closed.path("state").asText());
        assertEquals(-1, closed.path("exitCode").asInt());
        assertTrue(closed.has("endTime"));
    }

    @Test
    void aRecordedRunningProcessThatIsGoneReadsAsKilledWithAnUnknownExit() throws Exception {
        Instant start = Instant.parse("2026-09-01T10:00:00Z");
        Instant lastOutput = Instant.parse("2026-09-01T10:05:00Z");
        Files.createDirectories(historyDir());
        Path log = historyDir().resolve("proc-007.log");
        Files.writeString(log, "partial output\n");
        Files.setLastModifiedTime(log, FileTime.from(lastOutput));
        ObjectNode root = history();
        // Above any Linux pid_max, so no live process can own it.
        record(root, "proc-007", "make build", "RUNNING", start)
                .put("pid", Integer.MAX_VALUE - 1).put("osStart", start.toString());
        // No log: the start time is the only bound on when it ended.
        record(root, "proc-008", "make test", "RUNNING", start.plusSeconds(1))
                .put("pid", Integer.MAX_VALUE - 1).put("osStart", start.toString());
        writeHistory(root);

        try (BackgroundProcessManager run = manager()) {
            assertEquals(2, run.restoreSessionHistory());
            ProcessEntry gone = run.get("proc-007");
            assertEquals(ProcessState.KILLED, gone.getState());
            assertNull(gone.getExitCode());
            assertEquals(BackgroundProcessManager.OUTLIVED_NOTE, gone.getMetadata().get("note"));
            assertEquals(lastOutput, gone.getEndTime());
            assertFalse(gone.isKillable());
            assertFalse(run.kill("proc-007"));
            assertTrue(run.readOutput("proc-007", 5).contains("partial output"));

            ProcessEntry noLog = run.get("proc-008");
            assertEquals(ProcessState.KILLED, noLog.getState());
            assertEquals(start.plusSeconds(1), noLog.getEndTime());
        }
        // Restoring writes nothing.
        assertTrue(Files.readString(historyFile()).contains("\"RUNNING\""));
    }

    @Test
    void aRecordNamesOnlyTheOsProcessItsStartTimeMatches() throws Exception {
        Process live = new ProcessBuilder("sleep", "30").start();
        try {
            Instant osStart = live.info().startInstant().orElseThrow();
            Instant start = Instant.now();
            ObjectNode root = history();
            // The PID now belongs to a process that started an hour after the recorded one.
            record(root, "proc-001", "sleep 30", "RUNNING", start)
                    .put("pid", live.pid()).put("osStart", osStart.minus(Duration.ofHours(1)).toString());
            // No start time: nothing proves the live PID is the recorded process.
            record(root, "proc-002", "sleep 30", "RUNNING", start.plusMillis(1)).put("pid", live.pid());
            record(root, "proc-003", "sleep 30", "RUNNING", start.plusMillis(2))
                    .put("pid", live.pid()).put("osStart", osStart.toString());
            writeHistory(root);

            try (BackgroundProcessManager run = manager()) {
                assertEquals(3, run.restoreSessionHistory());
                for (String reused : List.of("proc-001", "proc-002")) {
                    ProcessEntry entry = run.get(reused);
                    assertEquals(ProcessState.KILLED, entry.getState(), reused);
                    assertEquals(BackgroundProcessManager.OUTLIVED_NOTE, entry.getMetadata().get("note"), reused);
                    assertFalse(entry.isKillable(), reused);
                    assertFalse(run.kill(reused), reused);
                }
                assertTrue(live.isAlive(), "a reused PID's new owner is never signalled");

                ProcessEntry same = run.get("proc-003");
                assertEquals(ProcessState.RUNNING, same.getState());
                assertTrue(same.isVirtual());
                assertTrue(same.isKillable());
                assertTrue(run.kill("proc-003"));
                assertTrue(live.waitFor(5, TimeUnit.SECONDS), "the recorded process is stopped");
                assertEquals(ProcessState.KILLED, same.getState());
                assertEquals(-1, same.getExitCode());
                assertFalse(same.isKillable());
            }
        } finally {
            live.destroyForcibly();
        }
    }

    @Test
    void aProcessThatOutlivedItsRunStaysRunningUntilALaterRunStopsIt() throws Exception {
        // The harness that launched it was killed hard, so its record still says RUNNING.
        Process orphan = new ProcessBuilder("sleep", "30").start();
        try {
            ObjectNode root = history();
            record(root, "proc-001", "sleep 30", "RUNNING", Instant.now())
                    .put("pid", orphan.pid())
                    .put("osStart", orphan.info().startInstant().orElseThrow().toString());
            writeHistory(root);

            try (BackgroundProcessManager run = manager()) {
                assertEquals(1, run.enableSessionHistory());
                assertEquals(ProcessState.RUNNING, run.get("proc-001").getState());
            }
            // Closing a later run leaves a process an earlier run launched alone.
            assertTrue(orphan.isAlive());
            assertTrue(Files.readString(historyFile()).contains("\"RUNNING\""));

            try (BackgroundProcessManager run = manager()) {
                run.enableSessionHistory();
                assertTrue(run.kill("proc-001"));
                assertTrue(orphan.waitFor(5, TimeUnit.SECONDS));
            }

            try (BackgroundProcessManager run = manager()) {
                run.restoreSessionHistory();
                ProcessEntry recorded = run.get("proc-001");
                assertEquals(ProcessState.KILLED, recorded.getState());
                assertEquals(-1, recorded.getExitCode());
                assertNull(recorded.getMetadata().get("note"));
                assertNotNull(recorded.getEndTime());
            }
        } finally {
            orphan.destroyForcibly();
        }
    }

    @Test
    void unusableRecordsAreSkippedAndTheFirstOfTwoWithOneIdWins() throws Exception {
        Instant start = Instant.parse("2026-09-01T10:00:00Z");
        ObjectNode root = history();
        record(root, "bad id", "x", "COMPLETED", start);
        record(root, "proc-1x", "x", "COMPLETED", start);
        record(root, "proc-2", "x", "COMPLETED", start).remove("startTime");
        record(root, "proc-3", "x", "COMPLETED", start).put("startTime", "yesterday");
        record(root, "proc-4", "x", "DONE", start);
        record(root, "proc-5", "first", "COMPLETED", start).put("exitCode", 0);
        record(root, "proc-5", "second", "COMPLETED", start).put("exitCode", 0);
        ObjectNode failed = record(root, "proc-6", "make", "FAILED", start).put("exitCode", "3");
        failed.putObject("metadata").put("kept", "yes").put("dropped", 5);
        writeHistory(root);

        try (BackgroundProcessManager run = manager()) {
            assertEquals(2, run.restoreSessionHistory());
            assertEquals(List.of("proc-5", "proc-6"), ids(run));
            assertEquals("first", run.get("proc-5").getCommand());
            ProcessEntry noExit = run.get("proc-6");
            assertEquals(ProcessState.FAILED, noExit.getState());
            assertNull(noExit.getExitCode());
            assertEquals(start, noExit.getEndTime());
            assertEquals(Map.of("kept", "yes"), noExit.getMetadata());
        }
    }

    @Test
    void anUnreadableHistoryRestoresNothingAndIsReplacedByTheNextRecord() throws Exception {
        Files.createDirectories(historyDir());
        Files.writeString(historyFile(), "not json");
        try (BackgroundProcessManager run = manager()) {
            assertEquals(0, run.enableSessionHistory());
            awaitExit(run.launch("echo fresh", "fresh", project));
        }
        assertEquals("fresh", onlyRecord().path("description").asText());

        Files.writeString(historyFile(), "{\"version\":1}");
        try (BackgroundProcessManager run = manager()) {
            assertEquals(0, run.restoreSessionHistory());
        }
    }

    @Test
    void theHistoryKeepsTheNewestTwoHundredProcesses() throws Exception {
        Instant base = Instant.parse("2026-09-01T10:00:00Z");
        ObjectNode root = history();
        for (int i = 1; i <= 205; i++) {
            record(root, "proc-" + i, "echo " + i, "COMPLETED", base.plusSeconds(i)).put("exitCode", 0);
        }
        writeHistory(root);

        try (BackgroundProcessManager run = manager()) {
            assertEquals(205, run.enableSessionHistory());
        }
        JsonNode kept = JSON.readTree(Files.readString(historyFile())).path("processes");
        assertEquals(200, kept.size());
        assertEquals("proc-6", kept.get(0).path("id").asText());
        assertEquals("proc-205", kept.get(199).path("id").asText());
    }

    @Test
    void aLaunchNumbersAfterTheHighestRecordedIdEvenWithoutItsLog() throws Exception {
        Files.createDirectories(historyDir());
        Files.writeString(historyDir().resolve("proc-007.log"), "");
        ObjectNode root = history();
        record(root, "proc-41", "echo old", "COMPLETED", Instant.parse("2026-09-01T10:00:00Z")).put("exitCode", 0);
        writeHistory(root);

        try (BackgroundProcessManager run = manager()) {
            run.enableSessionHistory();
            ProcessEntry next = run.launch("echo next", "next", project);
            awaitExit(next);
            assertEquals("proc-042", next.getId());
        }
    }

    @Test
    void anUnchangedHistoryIsNotRewritten() throws Exception {
        try (BackgroundProcessManager run = manager()) {
            run.enableSessionHistory();
            awaitExit(run.launch("echo once", "once", project));
        }
        FileTime old = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(historyFile(), old);
        String before = Files.readString(historyFile());

        try (BackgroundProcessManager run = manager()) {
            assertEquals(1, run.enableSessionHistory());
        }
        assertEquals(before, Files.readString(historyFile()));
        assertEquals(old, Files.getLastModifiedTime(historyFile()));
    }

    @Test
    void nothingIsWrittenWithoutHistoryOrWithoutProcesses() throws Exception {
        try (BackgroundProcessManager run = manager()) {
            assertEquals(0, run.enableSessionHistory());
        }
        assertFalse(Files.exists(historyFile()), "an empty history is not written");

        try (BackgroundProcessManager run = manager()) {
            ProcessEntry entry = run.launch("echo untracked", "untracked", project);
            awaitExit(entry);
            assertTrue(Files.exists(entry.getOutputFile()));
        }
        assertFalse(Files.exists(historyFile()), "a run that did not enable history records nothing");
    }

    @Test
    void onlyCommandsTheSessionLaunchedAreRecorded() throws Exception {
        ProcessEntry launched;
        try (BackgroundProcessManager run = manager()) {
            run.enableSessionHistory();
            run.registerVirtual(ProcessKind.MCP, "mcp", "MCP tool bridge log", Map.of());
            run.upsertShared("shared-owner-proc-001", "make", "owner build", 42L, Instant.now(),
                    ProcessState.RUNNING, null, null, temp.resolve("owner.log"),
                    Map.of("ownerSessionId", "owner", "sharedProcessId", "proc-001"));
            launched = run.launch("echo mine", "mine", project);
            awaitExit(launched);
        }
        assertEquals(launched.getId(), onlyRecord().path("id").asText());
    }

    // -----------------------------------------------------------------

    private BackgroundProcessManager manager() {
        return new BackgroundProcessManager(SESSION, project);
    }

    private Path historyDir() {
        return project.resolve(".kompile").resolve("process-output").resolve(SESSION);
    }

    private Path historyFile() {
        return historyDir().resolve(BackgroundProcessManager.HISTORY_FILE);
    }

    private static ObjectNode history() {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.putArray("processes");
        return root;
    }

    private static ObjectNode record(ObjectNode root, String id, String command, String state, Instant start) {
        ObjectNode record = ((ArrayNode) root.get("processes")).addObject();
        record.put("id", id);
        record.put("command", command);
        record.put("state", state);
        record.put("startTime", start.toString());
        return record;
    }

    private void writeHistory(ObjectNode root) throws IOException {
        Files.createDirectories(historyDir());
        Files.writeString(historyFile(), JSON.writeValueAsString(root));
    }

    private JsonNode onlyRecord() throws IOException {
        JsonNode records = JSON.readTree(Files.readString(historyFile())).path("processes");
        assertEquals(1, records.size(), records.toString());
        return records.get(0);
    }

    private static List<String> ids(BackgroundProcessManager run) {
        List<String> ids = new ArrayList<>();
        for (ProcessEntry entry : run.listAll()) {
            ids.add(entry.getId());
        }
        ids.sort(null);
        return ids;
    }

    private static void awaitExit(ProcessEntry entry) throws InterruptedException {
        for (int attempts = 0; entry.isRunning() && attempts < 50; attempts++) {
            Thread.sleep(100);
        }
        assertFalse(entry.isRunning(), entry.getId() + " should have exited");
    }

    private static void awaitDeath(long pid) throws InterruptedException {
        for (int attempts = 0; alive(pid) && attempts < 50; attempts++) {
            Thread.sleep(100);
        }
        assertFalse(alive(pid), "process " + pid + " should be gone");
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
