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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that processes published to shared coordination state by OTHER
 * sessions (the MCP process tool runs in its own JVM) appear in the local
 * activity panel's process manager with live output tails — and that local
 * session publishes are never mirrored twice.
 */
class SharedProcessMirrorTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path workDir;

    private CoordinationStateManager coordination;
    private BackgroundProcessManager processes;
    private SharedProcessMirror mirror;
    private Path ownerLog;
    private final CopyOnWriteArrayList<String> changes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        coordination = new CoordinationStateManager(workDir, "local-chat", MAPPER);
        processes = new BackgroundProcessManager("local-chat", workDir);
        processes.addChangeListener(() -> changes.add("change"));
        mirror = new SharedProcessMirror(processes, coordination, "local-chat");
        ownerLog = workDir.resolve("owner-build.log");
    }

    private final java.util.Map<String, CoordinationStateManager> owners =
            new java.util.HashMap<>();

    @AfterEach
    void tearDown() {
        mirror.close();
        processes.close();
        owners.values().forEach(CoordinationStateManager::shutdown);
        owners.clear();
        coordination.shutdown();
    }

    @Test
    void foreignLaunchAppearsWithLiveTailAndRefreshesOnGrowth() throws Exception {
        publish("mcp-stdio-1", "proc-001", "mvn -o test", "Maven test run", "RUNNING", 0);
        writeLog("building module 1\n");

        mirror.pollOnce();

        assertTrue(mirror.pollFailure().isEmpty(),
                "mirror poll must succeed: " + mirror.pollFailure());
        BackgroundProcessManager.ProcessEntry entry = sharedMirror("proc-001");
        assertNotNull(entry, "foreign process must be mirrored locally");
        assertTrue(entry.isRunning());
        assertEquals("mcp-stdio-1", entry.getMetadata().get("ownerSessionId"));
        assertEquals("coordination", entry.getMetadata().get("source"));
        assertTrue(entry.getMetadata().get("ownerAgent").contains("codex"),
                "owner agent should be surfaced: " + entry.getMetadata());
        assertEquals("building module 1", lastOutputLine(entry),
                "mirror must expose the owner's log content");

        int changesAfterFirst = changes.size();
        assertTrue(changesAfterFirst > 0, "first appearance must fire a change");

        writeLog("building module 1\nbuilding module 2\n");
        mirror.pollOnce();

        assertEquals("building module 2", lastOutputLine(entry),
                "live tail must follow output growth");
        assertTrue(changes.size() > changesAfterFirst,
                "output growth must fire a change so an open view repaints");
    }

    @Test
    void ownSessionPublishesAreNeverMirrored() {
        publish("local-chat", "proc-777", "local echo", "My own launch", "RUNNING", 0);

        mirror.pollOnce();

        assertTrue(processes.listAll().stream()
                        .filter(entry -> entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED)
                        .noneMatch(entry -> "proc-777".equals(entry.getMetadata().get("sharedProcessId"))),
                "own-session processes must not be double-rendered as mirrors");
    }

    @Test
    void ownerTerminalStateWinsAndVanishedEntriesArePruned() throws Exception {
        publish("mcp-stdio-2", "proc-002", "long job", "Long job", "RUNNING", 0);
        writeLog("working\n");
        mirror.pollOnce();
        BackgroundProcessManager.ProcessEntry entry = sharedMirror("proc-002");
        assertNotNull(entry);
        assertTrue(entry.isRunning());

        // Kill must refuse: the OS PID belongs to the owner session.
        assertFalse(processes.kill(entry.getId()), "mirror kill must be refused");
        assertTrue(entry.isRunning());

        // A real terminal owner publication wins over the local RUNNING mirror.
        publish("mcp-stdio-2", "proc-002", "long job", "Long job", "COMPLETED", 0);
        mirror.pollOnce();
        assertEquals(BackgroundProcessManager.ProcessState.COMPLETED, entry.getState());

        // Coordination eviction removes the mirror (without touching the owner log).
        Files.deleteIfExists(processFile("mcp-stdio-2", "proc-002"));
        mirror.pollOnce();
        assertTrue(processes.listAll().stream()
                        .filter(candidate -> candidate.getKind() == BackgroundProcessManager.ProcessKind.SHARED)
                        .noneMatch(candidate -> candidate.getId().equals(entry.getId())),
                "vanished coordination entries must prune their mirrors");
        assertTrue(Files.exists(ownerLog), "pruning must never delete the owner's log");
    }

    @Test
    void missingOutputFileAndGarbagePathAreTolerated() {
        publish("mcp-stdio-3", "proc-003", "no log", "No log yet", "RUNNING", 0);
        publish("mcp-stdio-3", "proc-004", "garbage", "Bad output path", "RUNNING", 0);

        mirror.pollOnce();

        BackgroundProcessManager.ProcessEntry noLog = sharedMirror("proc-003");
        assertNotNull(noLog);
        assertTrue(noLog.isRunning(), "entry without a log is still trackable");
        BackgroundProcessManager.ProcessEntry garbage = sharedMirror("proc-004");
        assertNotNull(garbage, "unreadable output path must not abort mirroring");
    }

    @Test
    void pollAfterCloseIsANoOp() {
        mirror.pollOnce();
        mirror.close();
        mirror.pollOnce(); // after close: no-op, no exception
        assertTrue(true, "reached");
    }

    private BackgroundProcessManager.ProcessEntry sharedMirror(String sharedProcessId) {
        return processes.listAll().stream()
                .filter(entry -> entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED)
                .filter(entry -> sharedProcessId.equals(entry.getMetadata().get("sharedProcessId")))
                .findFirst()
                .orElse(null);
    }

    private String lastOutputLine(BackgroundProcessManager.ProcessEntry entry) {
        String output = processes.readOutput(entry.getId(), 1);
        if (output == null || output.isBlank()) return "";
        return output.substring(output.lastIndexOf('\n') + 1).strip();
    }

    private void publish(String ownerSession, String processId, String command,
                         String description, String state, int exitCode) {
        // The owner coordinator must STAY ALIVE: shutdown() deletes every
        // sessionId-*.proc.json entry, which mirrors real sessions (they publish
        // and then keep running).
        CoordinationStateManager owner = owners.computeIfAbsent(ownerSession,
                session -> new CoordinationStateManager(workDir, session, MAPPER));
        owner.publishProcess(processId, command, description,
                0L, state, outputFileFor(processId).toString(), "codex");
        if (!"RUNNING".equals(state)) {
            owner.updateProcessState(processId, state, Instant.now(), exitCode);
        }
    }

    private Path outputFileFor(String processId) {
        // Deterministic per-process paths: proc-004 gets a nonexistent path on
        // purpose (constructible as a Path, but never a regular file).
        return "proc-004".equals(processId)
                ? workDir.resolve("missing-dir/never-created.log").toAbsolutePath()
                : ownerLog;
    }

    private void writeLog(String content) throws Exception {
        Files.write(ownerLog, content.getBytes(StandardCharsets.UTF_8));
    }

    private Path processFile(String ownerSession, String processId) {
        return workDir.resolve(".kompile/coordination/processes")
                .resolve(ownerSession + "-" + processId + ".proc.json");
    }
}
