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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessManagementCoordinationTest {

    @Test
    void naturalExitPublishesTerminalStateAndOwnerMetadata(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        CoordinationStateManager coordinator = new CoordinationStateManager(
                tempDir, "coord-owner", mapper, tempDir.resolve("system-coordination"));
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "coord-owner", tempDir);
        try {
            coordinator.registerAgent("run focused test", null, "codex", 0,
                    ProcessHandle.current().pid(), "tool-transcript", "architect");
            ProcessManagementTool tool = new ProcessManagementTool(processes, coordinator);
            PermissionService permissions = new PermissionService();
            permissions.setAutoApproveAll(true);
            ToolContext context = new ToolContext(
                    "tool-transcript",
                    AgentConfig.builder("coder").roleName("architect").build(),
                    permissions, tempDir, new ToolRegistry(mapper));
            ObjectNode launch = mapper.createObjectNode();
            launch.put("action", "launch");
            launch.put("command", "printf 'coordinated-process-complete\\n'");
            launch.put("description", "short coordinated process");

            ToolResult result = tool.execute(launch, context);
            assertFalse(result.isError(), result::getOutput);

            ProcessCoordEntry published = awaitTerminalProcess(coordinator, Duration.ofSeconds(5));
            assertEquals("COMPLETED", published.getState());
            assertEquals(0, published.getExitCode());
            assertNotNull(published.getEndedAt());
            assertEquals("codex", published.getAgentName());
            assertEquals("architect", published.getRoleName());
            assertEquals("command", published.getKind());
            assertTrue(published.getStartedAt().isBefore(published.getEndedAt())
                            || published.getStartedAt().equals(published.getEndedAt()),
                    "published lifecycle timestamps must be ordered");
        } finally {
            processes.close();
            coordinator.shutdown();
        }
    }

    @Test
    void terminalUpdateWaitsThroughBriefCoordinatorLockContention(@TempDir Path tempDir)
            throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        CoordinationStateManager coordinator = new CoordinationStateManager(
                tempDir, "contended-owner", mapper, tempDir.resolve("system-coordination"));
        try {
            coordinator.publishProcess(
                    "proc-contended", "fixture", "contended terminal update",
                    ProcessHandle.current().pid(), "RUNNING", null, "codex");
            Path lockFile = tempDir.resolve(".kompile/coordination/.coordinator.lock");
            CompletableFuture<Void> update;
            CountDownLatch updateStarted = new CountDownLatch(1);
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                update = CompletableFuture.runAsync(() -> {
                    updateStarted.countDown();
                    coordinator.updateProcessState(
                            "proc-contended", "COMPLETED", Instant.now(), 0);
                });
                assertTrue(updateStarted.await(1, TimeUnit.SECONDS));
                Thread.sleep(50L);
                assertFalse(update.isDone(),
                        "terminal publication should wait for brief lock contention");
            }
            update.get(2, TimeUnit.SECONDS);

            ProcessCoordEntry published = coordinator.queryProcesses().stream()
                    .filter(entry -> "proc-contended".equals(entry.getProcessId()))
                    .findFirst().orElseThrow();
            assertEquals("COMPLETED", published.getState());
            assertEquals(0, published.getExitCode());
            assertNotNull(published.getEndedAt());
        } finally {
            coordinator.shutdown();
        }
    }

    private static ProcessCoordEntry awaitTerminalProcess(
            CoordinationStateManager coordinator, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        ProcessCoordEntry last = null;
        while (Instant.now().isBefore(deadline)) {
            List<ProcessCoordEntry> entries = coordinator.queryProcesses();
            if (!entries.isEmpty()) {
                last = entries.get(0);
                if (last.isTerminalState()) return last;
            }
            Thread.sleep(20L);
        }
        assertNotNull(last, "process should have been published to coordination");
        assertTrue(last.isTerminalState(), "process did not publish a terminal state: " + last.getState());
        return last;
    }
}
