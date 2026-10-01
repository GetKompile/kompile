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

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationProcessStateTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void deadRunningProcessBecomesLostImmediately(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager = manager(tempDir, "owner-dead");
        Process exited = new ProcessBuilder("sh", "-c", "exit 0").start();
        assertTrue(exited.waitFor(5, TimeUnit.SECONDS));
        try {
            manager.publishProcess("proc-001", "finished command", "already exited",
                    exited.pid(), "RUNNING", tempDir.resolve("proc.log").toString(), "codex");

            ProcessCoordEntry reconciled = only(manager.queryProcesses());
            assertEquals("LOST", reconciled.getState());
            assertNotNull(reconciled.getEndedAt());
            assertNull(reconciled.getExitCode(), "a missing owner cannot invent an exit code");
            assertTrue(reconciled.getTtlSeconds() > 120,
                    "terminal diagnostics should remain briefly visible");

            ProcessCoordEntry persisted = mapper.readValue(
                    processFile(tempDir, "owner-dead", "proc-001").toFile(),
                    ProcessCoordEntry.class);
            assertEquals("LOST", persisted.getState());
            assertNotNull(persisted.getEndedAt());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void heartbeatDoesNotKeepTerminalProcessFreshForever(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager = manager(tempDir, "owner-terminal");
        try {
            Instant endedAt = Instant.now().minusSeconds(5);
            manager.publishProcess("proc-002", "done", "completed command",
                    0L, "COMPLETED", tempDir.resolve("done.log").toString(),
                    "codex", "coder", "command",
                    endedAt.minusSeconds(1), endedAt, 0);
            Path file = processFile(tempDir, "owner-terminal", "proc-002");
            ProcessCoordEntry before = mapper.readValue(file.toFile(), ProcessCoordEntry.class);

            invokeHeartbeat(manager);

            ProcessCoordEntry after = mapper.readValue(file.toFile(), ProcessCoordEntry.class);
            assertEquals(before.getLastHeartbeat(), after.getLastHeartbeat());
            assertEquals(endedAt, after.getEndedAt());
            assertEquals(0, after.getExitCode());
            assertEquals("COMPLETED", after.getState());
            assertEquals(Duration.ofSeconds(1), after.getDuration(),
                    "terminal process duration must stop at endedAt");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void liveProcessIsNotEvictedOnlyBecauseItsHeartbeatIsOld(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager = manager(tempDir, "owner-orphan");
        Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        try {
            manager.publishProcess("proc-003", "sleep 30", "live orphan",
                    process.pid(), "RUNNING", tempDir.resolve("live.log").toString(), "codex");
            Path file = processFile(tempDir, "owner-orphan", "proc-003");
            ProcessCoordEntry stale = mapper.readValue(file.toFile(), ProcessCoordEntry.class);
            stale.setLastHeartbeat(Instant.now().minusSeconds(60));
            stale.setTtlSeconds(1);
            mapper.writeValue(file.toFile(), stale);

            ProcessCoordEntry retained = only(manager.queryProcesses());
            assertEquals("RUNNING", retained.getState());
            assertEquals(process.pid(), retained.getPid());
            assertFalse(retained.isTerminalState());
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            manager.shutdown();
        }
    }

    @Test
    void updateAfterShutdownDoesNotResurrectProcFileOrWarn(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager = manager(tempDir, "owner-shutdown");
        List<String> warnings = new CopyOnWriteArrayList<>();
        manager.installWarningSink(warnings::add);
        manager.publishProcess("proc-004", "sleep 30", "killed by shutdown",
                ProcessHandle.current().pid(), "RUNNING",
                tempDir.resolve("shutdown.log").toString(), "codex");
        Path file = processFile(tempDir, "owner-shutdown", "proc-004");
        assertTrue(Files.exists(file));

        manager.shutdown();
        assertFalse(Files.exists(file), "shutdown() must delete the proc file");

        manager.updateProcessState("proc-004", "KILLED", Instant.now(), -1);

        assertFalse(Files.exists(file), "a late update must not resurrect a deleted proc file");
        assertTrue(warnings.isEmpty(), "a late update after shutdown must skip quietly: " + warnings);

        // The outer Files.exists() fast path above already covers the "file is gone" case on
        // its own. Republish through the public API (publishProcess never checks the shutdown
        // flag) so the file exists again, then isolate the in-lock "shutdown ||" re-check in
        // updateProcessState: a shutdown manager must still refuse to mutate the entry even
        // though the file is present and the fast path would let it through.
        manager.publishProcess("proc-004", "sleep 30", "republished after shutdown",
                ProcessHandle.current().pid(), "RUNNING",
                tempDir.resolve("shutdown.log").toString(), "codex");
        assertTrue(Files.exists(file), "republish via the public API must recreate the proc file");

        manager.updateProcessState("proc-004", "KILLED", Instant.now(), -1);

        ProcessCoordEntry stillRunning = mapper.readValue(file.toFile(), ProcessCoordEntry.class);
        assertEquals("RUNNING", stillRunning.getState(),
                "a shutdown manager must not mutate an entry even when the file exists");
        assertTrue(warnings.isEmpty(),
                "a late update after shutdown must skip quietly even with the file present: " + warnings);
    }

    private CoordinationStateManager manager(Path workDir, String sessionId) {
        return new CoordinationStateManager(workDir, sessionId, mapper);
    }

    private static Path processFile(Path root, String sessionId, String processId) {
        return root.resolve(".kompile/coordination/processes")
                .resolve(sessionId + "-" + processId + ".proc.json");
    }

    private static ProcessCoordEntry only(List<ProcessCoordEntry> entries) {
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    private static void invokeHeartbeat(CoordinationStateManager manager) throws Exception {
        Method method = CoordinationStateManager.class.getDeclaredMethod("heartbeat");
        method.setAccessible(true);
        method.invoke(manager);
    }
}
