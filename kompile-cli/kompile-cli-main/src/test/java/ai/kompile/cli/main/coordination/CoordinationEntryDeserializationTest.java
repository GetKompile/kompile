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

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the silent coordination heartbeat failure.
 *
 * <p>{@link EditLockEntry}, {@link AgentEntry} and {@link ProcessCoordEntry} were Lombok
 * {@code @Data} with field-level {@code @JsonProperty} and an all-args constructor but no
 * no-arg constructor and no {@code @JsonCreator}, so Jackson threw
 * {@code "no Creators, like default constructor, exist"} on every read — failing the
 * coordination heartbeat (and edit-lock/agent/process queries) silently every 30s.
 * {@code @NoArgsConstructor} restores property-based deserialization. Uses the same
 * {@link JsonUtils#standardMapper()} the coordination manager uses.</p>
 */
class CoordinationEntryDeserializationTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void editLockEntryRoundTrips() throws Exception {
        EditLockEntry e = new EditLockEntry("lock1", "sess1", "coder",
                "src/A.java", "/abs/src/A.java", "write", Instant.now(), 300);

        EditLockEntry back = mapper.readValue(mapper.writeValueAsString(e), EditLockEntry.class);

        assertEquals("lock1", back.getLockId());
        assertEquals("sess1", back.getSessionId());
        assertEquals("/abs/src/A.java", back.getAbsolutePath());
        assertEquals(300, back.getTtlSeconds());
    }

    @Test
    void agentEntryRoundTrips() throws Exception {
        AgentEntry e = new AgentEntry("sess1", "coder", "agent", "parent", 1,
                "do things", "/wd", 1234L, Instant.now(), 60);
        e.setToolSessionId("transcript-1");
        e.setRoleName("architect");

        AgentEntry back = mapper.readValue(mapper.writeValueAsString(e), AgentEntry.class);

        assertEquals("sess1", back.getSessionId());
        assertEquals("transcript-1", back.getToolSessionId());
        assertEquals("coder", back.getAgentName());
        assertEquals("architect", back.getRoleName());
        assertEquals(1234L, back.getPid());
    }

    @Test
    void processCoordEntryRoundTrips() throws Exception {
        ProcessCoordEntry e = new ProcessCoordEntry("proc1", "sess1", "coder",
                "run x", "desc", 4321L, "RUNNING", Instant.now(), "/out.log", 120);
        e.setRoleName("coder");
        e.setKind("command");

        ProcessCoordEntry back =
                mapper.readValue(mapper.writeValueAsString(e), ProcessCoordEntry.class);

        assertEquals("proc1", back.getProcessId());
        assertEquals(4321L, back.getPid());
        assertEquals("RUNNING", back.getState());
        assertEquals("coder", back.getRoleName());
        assertEquals("command", back.getKind());
        assertNull(back.getEndedAt());
        assertNull(back.getExitCode());
    }

    @Test
    void corruptCoordinationRecordIsQuarantinedOnce(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager =
                new CoordinationStateManager(tempDir, "quarantine-test", mapper);
        List<String> alerts = new ArrayList<>();
        Runnable cleanup = manager.installWarningSink(alerts::add);
        try {
            Path processes = Files.createDirectories(
                    tempDir.resolve(".kompile/coordination/processes"));
            Path corrupt = processes.resolve("broken-proc.proc.json");
            Files.writeString(corrupt, "");

            assertTrue(manager.queryProcesses().isEmpty());
            assertFalse(Files.exists(corrupt));
            Path quarantineDir = tempDir.resolve(".kompile/coordination/corrupt");
            try (var files = Files.list(quarantineDir)) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString()
                        .startsWith("broken-proc.proc.json.")));
            }
            assertEquals(1, alerts.size());
            assertTrue(alerts.get(0).contains("quarantined as"));

            alerts.clear();
            assertTrue(manager.queryProcesses().isEmpty());
            assertTrue(alerts.isEmpty(), "a quarantined record must not warn on every poll");
        } finally {
            cleanup.run();
            manager.shutdown();
        }
    }

    @Test
    void heartbeatFailureUsesInstalledWarningSink(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager =
                new CoordinationStateManager(tempDir, "heartbeat-alert", mapper);
        List<String> alerts = new ArrayList<>();
        Runnable cleanup = manager.installWarningSink(alerts::add);
        try {
            manager.registerAgent("test heartbeat", null, "tester", 0, 123L);
            Path agentFile = tempDir.resolve(".kompile/coordination/agents")
                    .resolve("heartbeat-alert.agent.json");
            Files.writeString(agentFile, "");

            invokeHeartbeat(manager);

            assertEquals(1, alerts.size());
            assertTrue(alerts.get(0).startsWith("[Coordination] Heartbeat error:"));
        } finally {
            cleanup.run();
            manager.shutdown();
        }
    }

    @Test
    void heartbeatAtomicallyReplacesReadableState(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager =
                new CoordinationStateManager(tempDir, "atomic-heartbeat", mapper);
        List<String> alerts = new ArrayList<>();
        Runnable cleanup = manager.installWarningSink(alerts::add);
        try {
            manager.registerAgent("test heartbeat", null, "tester", 0, 123L);
            Path agentsDir = tempDir.resolve(".kompile/coordination/agents");
            Path agentFile = agentsDir.resolve("atomic-heartbeat.agent.json");
            AgentEntry before = mapper.readValue(agentFile.toFile(), AgentEntry.class);
            Thread.sleep(2L);
            invokeHeartbeat(manager);

            AgentEntry entry = mapper.readValue(agentFile.toFile(), AgentEntry.class);
            assertEquals("atomic-heartbeat", entry.getSessionId());
            assertTrue(entry.getLastHeartbeat().isAfter(before.getLastHeartbeat()));
            assertTrue(alerts.isEmpty());
            try (var files = Files.list(agentsDir)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        } finally {
            cleanup.run();
            manager.shutdown();
        }
    }

    @Test
    void busyCoordinatorLockNeverBlocksChatFacingOperations(@TempDir Path tempDir) throws Exception {
        CoordinationStateManager manager =
                new CoordinationStateManager(tempDir, "non-blocking", mapper);
        List<String> alerts = new ArrayList<>();
        Runnable cleanup = manager.installWarningSink(alerts::add);
        Path coordinationDir = tempDir.resolve(".kompile/coordination");
        Path lockFile = coordinationDir.resolve(".coordinator.lock");
        Files.createDirectories(coordinationDir);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                EditLockResult result = manager.tryAcquireEditLock(
                        tempDir.resolve("chat.txt").toAbsolutePath().toString(), "edit");
                assertTrue(result.isAcquired(), "coordination contention must fail open");

                String dashboard = manager.statusDashboard();
                assertTrue(dashboard.contains("ACTIVE AGENTS (0)"));
                assertTrue(dashboard.contains("ACTIVE EDITS (0)"));
                assertTrue(dashboard.contains("ACTIVE PROCESSES (0)"));
            });
            assertTrue(alerts.stream().anyMatch(message -> message.contains("Coordinator lock is busy")));
        } finally {
            cleanup.run();
            manager.shutdown();
        }
    }

    @Test
    void sessionPresenceRegistrationDoesNotDependOnGlobalCoordinatorLock(@TempDir Path tempDir)
            throws Exception {
        CoordinationStateManager manager =
                new CoordinationStateManager(tempDir, "presence", mapper);
        Path coordinationDir = tempDir.resolve(".kompile/coordination");
        Path lockFile = coordinationDir.resolve(".coordinator.lock");
        Files.createDirectories(coordinationDir);
        try {
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                        manager.registerAgent("presence task", null, "codex", 0, 123L));
                assertTrue(Files.isRegularFile(coordinationDir.resolve("agents/presence.agent.json")));
            }
            assertEquals(1, manager.queryAgents().size());
            assertEquals("presence", manager.queryAgents().get(0).getSessionId());
        } finally {
            manager.shutdown();
        }
    }

    private static void invokeHeartbeat(CoordinationStateManager manager) throws Exception {
        Method method = CoordinationStateManager.class.getDeclaredMethod("heartbeat");
        method.setAccessible(true);
        method.invoke(manager);
    }
}
