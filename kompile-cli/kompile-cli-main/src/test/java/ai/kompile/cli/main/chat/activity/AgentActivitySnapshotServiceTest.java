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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ToolCallRecord;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentActivitySnapshotServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final List<CoordinationStateManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managers.forEach(CoordinationStateManager::shutdown);
    }

    @Test
    void correlatesExplicitToolSessionsAndSeparatesUnreachableAndOrphanOwners()
            throws Exception {
        Files.writeString(tempDir.resolve("kompile.project.json"), "{}", StandardCharsets.UTF_8);
        Instant now = Instant.now();
        long currentPid = ProcessHandle.current().pid();

        CoordinationStateManager viewer = manager("viewer");
        CoordinationStateManager active = manager("coord-active");
        active.registerAgent("Implement dashboard", null, "codex", 0, currentPid,
                "tool-active", "architect");
        active.publishProcess("proc-001", "mvn test", "Run dashboard tests",
                currentPid, "RUNNING", tempDir.resolve("active.log").toString(),
                "codex", "architect", "command", now, null, null);

        Process exited = new ProcessBuilder("sh", "-c", "exit 0").start();
        assertTrue(exited.waitFor(5, TimeUnit.SECONDS));
        CoordinationStateManager unreachable = manager("coord-dead");
        unreachable.registerAgent("Interrupted task", null, "claude", 0, exited.pid(),
                "tool-dead", "reviewer");

        CoordinationStateManager orphan = manager("coord-orphan");
        orphan.publishProcess("proc-001", "sleep 30", "Detached build",
                currentPid, "RUNNING", tempDir.resolve("orphan.log").toString(),
                "qwen", "coder", "command", now, null, null);

        Path toolDir = Files.createDirectories(tempDir.resolve("tool-calls"));
        writeCalls(toolDir.resolve("tool-active.jsonl"), List.of(
                record("recent", "tool-active", "read", now.minusSeconds(30)),
                record("old", "tool-active", "grep", now.minus(Duration.ofMinutes(8)))));

        AgentActivitySnapshotService service = new AgentActivitySnapshotService(
                viewer,
                new ToolCallTailReader(toolDir, mapper, 64 * 1024, 256 * 1024, 32),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(5));

        AgentActivitySnapshot snapshot = service.capture(10);

        assertEquals(3, snapshot.agents().size());
        assertEquals(1, snapshot.confirmedAgentCount());
        assertEquals(1, snapshot.unreachableAgentCount());
        assertEquals(1, snapshot.orphanOwnerCount());
        assertEquals(2, snapshot.runningProcessCount());
        assertEquals(1, snapshot.recentToolCount());

        AgentActivitySnapshot.AgentActivity correlated = snapshot.agents().stream()
                .filter(agent -> agent.coordinationSessionId().equals("coord-active"))
                .findFirst().orElseThrow();
        assertEquals("tool-active", correlated.toolSessionId());
        assertEquals("codex", correlated.agentName());
        assertEquals("architect", correlated.roleName());
        assertEquals(List.of("read"), correlated.recentTools().stream()
                .map(AgentActivitySnapshot.ToolActivity::toolName).toList());
        assertEquals("coord-active/proc-001", correlated.processes().get(0).key());

        AgentActivitySnapshot.AgentActivity orphaned = snapshot.agents().stream()
                .filter(AgentActivitySnapshot.AgentActivity::orphaned)
                .findFirst().orElseThrow();
        assertEquals("coord-orphan", orphaned.coordinationSessionId());
        assertTrue(orphaned.toolSessionId().isBlank(),
                "orphan tool identity must not be guessed from timestamps or provider name");
        assertFalse(orphaned.processes().isEmpty());
    }

    @Test
    void snapshotRetainsOnlyRedactedBoundedToolSummary() throws Exception {
        Files.writeString(tempDir.resolve("kompile.project.json"), "{}", StandardCharsets.UTF_8);
        Instant now = Instant.now();
        CoordinationStateManager viewer = manager("summary-viewer");
        CoordinationStateManager active = manager("summary-agent");
        active.registerAgent("Inspect token=task-secret safely", null, "codex", 0,
                ProcessHandle.current().pid(), "summary-tools", "reviewer");
        active.publishProcess("proc-secret", "run --password process-secret", "credential desc-secret",
                ProcessHandle.current().pid(), "RUNNING", tempDir.resolve("summary.log").toString(),
                "codex", "reviewer", "command", now, null, null);

        Path toolDir = Files.createDirectories(tempDir.resolve("tool-calls"));
        ToolCallRecord secret = new ToolCallRecord(
                "secret-call", "summary-tools", "webfetch",
                "{\"api_key\":\"raw-secret\",\"body\":\"" + "x".repeat(4_000) + "\"}",
                "api_key=summary-secret Authorization: Bearer bearer-secret "
                        + "y".repeat(1_000),
                now.minusSeconds(1), "mcp", "coder", false, 4L,
                "web", tempDir.toString());
        writeCalls(toolDir.resolve("summary-tools.jsonl"), List.of(secret));
        AgentActivitySnapshotService service = new AgentActivitySnapshotService(
                viewer,
                new ToolCallTailReader(toolDir, mapper, 16 * 1024, 16 * 1024, 8),
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));

        AgentActivitySnapshot.AgentActivity retainedAgent = service.capture(8).agents().stream()
                .filter(agent -> agent.coordinationSessionId().equals("summary-agent"))
                .findFirst().orElseThrow();
        AgentActivitySnapshot.ToolActivity retained = retainedAgent.recentTools().get(0);

        assertFalse(retained.inputSummary().contains("summary-secret"));
        assertFalse(retained.inputSummary().contains("bearer-secret"));
        assertTrue(retained.inputSummary().contains("<redacted>"));
        assertTrue(retained.inputSummary().length() <= ActivityToolText.MAX_SUMMARY_CHARS);
        assertFalse(retainedAgent.task().contains("task-secret"));
        assertFalse(retainedAgent.processes().get(0).command().contains("process-secret"));
        assertFalse(retainedAgent.processes().get(0).description().contains("desc-secret"));
        assertTrue(retainedAgent.task().contains("<redacted>"));
        assertTrue(retainedAgent.processes().get(0).command().contains("<redacted>"));
        assertTrue(retainedAgent.processes().get(0).description().contains("<redacted>"));
        assertTrue(Arrays.stream(AgentActivitySnapshot.ToolActivity.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("toolInput")),
                "raw tool arguments must not be representable in a dashboard snapshot");
    }

    private CoordinationStateManager manager(String sessionId) {
        CoordinationStateManager manager = new CoordinationStateManager(tempDir, sessionId, mapper);
        managers.add(manager);
        return manager;
    }

    private ToolCallRecord record(String id, String sessionId, String tool, Instant timestamp) {
        return new ToolCallRecord(id, sessionId, tool, "{}", "safe summary", timestamp,
                "test", "coder", false, 12L, "general", tempDir.toString());
    }

    private void writeCalls(Path file, List<ToolCallRecord> records) throws Exception {
        StringBuilder content = new StringBuilder();
        for (ToolCallRecord record : records) {
            content.append(mapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
