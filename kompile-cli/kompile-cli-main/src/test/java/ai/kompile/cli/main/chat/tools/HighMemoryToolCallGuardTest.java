/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighMemoryToolCallGuardTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final List<CoordinationStateManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managers.forEach(CoordinationStateManager::shutdown);
    }

    @Test
    void classifierRecognizesBuildTestCrawlAndReadOnlyLifecycle() {
        assertEquals(HighMemoryToolCallGuard.ActivityKind.BUILD,
                HighMemoryToolCallGuard.classifyCommand(
                        "/home/user/mvn/bin/mvn -DskipTests package").kind());
        assertEquals(HighMemoryToolCallGuard.ActivityKind.TEST,
                HighMemoryToolCallGuard.classifyCommand("./gradlew test").kind());
        assertEquals(HighMemoryToolCallGuard.ActivityKind.CRAWL,
                HighMemoryToolCallGuard.classify("crawl_documents",
                        mapper.createObjectNode().put("async", true)).kind());
        assertEquals(null, HighMemoryToolCallGuard.classify("crawl_control",
                mapper.createObjectNode().put("operation", "status")));
        assertEquals(null, HighMemoryToolCallGuard.classify("crawl_source",
                mapper.createObjectNode().put("dryRun", true)));
        assertEquals(null, HighMemoryToolCallGuard.classifyCommand("git status"));
    }

    @Test
    void crawlDryRunBypassesCapacityAndSystemLane() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        AtomicInteger capacityChecks = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        HighMemoryToolCallGuard guard = guard(mine, (wait, observer) -> {
            capacityChecks.incrementAndGet();
            return new HighMemoryToolCallGuard.CapacityCheck(
                    false, "REJECTED", "should not be sampled", false, 0L, 1, Map.of());
        });
        CliTool preview = guard.wrap(tool("crawl_source", ignored -> {
            executions.incrementAndGet();
            return ToolResult.success("preview");
        }));

        ToolResult result = preview.execute(
                mapper.createObjectNode().put("dryRun", true), context(mine));

        assertFalse(result.isError());
        assertEquals(1, executions.get());
        assertEquals(0, capacityChecks.get());
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void activeSystemReservationBlocksBeforeDelegateExecutes() throws Exception {
        CoordinationStateManager peer = manager("peer", tempDir.resolve("project-b"));
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project-a"));
        assertTrue(peer.reserveHighMemoryActivity(
                "peer-agent", "CRAWL", "crawl_documents", "peer crawl").admitted());
        AtomicInteger executions = new AtomicInteger();
        CliTool guarded = guard(mine, admittedCapacity()).wrap(
                tool("bash", ignored -> {
                    executions.incrementAndGet();
                    return ToolResult.success("should not run");
                }));

        ToolResult result = guarded.execute(
                mapper.createObjectNode().put("command", "mvn test"), context(mine));

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("before any work started"));
        assertEquals(0, executions.get());
    }

    @Test
    void registeredPeerProcessBlocksBeforeReservation() throws Exception {
        CoordinationStateManager peer = manager("peer", tempDir.resolve("project"));
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        peer.publishProcess("proc-peer", "mvn test", "peer tests",
                ProcessHandle.current().pid(), "RUNNING", null, "peer-agent");
        AtomicInteger executions = new AtomicInteger();
        CliTool guarded = guard(mine, admittedCapacity()).wrap(
                tool("bash", ignored -> {
                    executions.incrementAndGet();
                    return ToolResult.success("should not run");
                }));

        ToolResult result = guarded.execute(
                mapper.createObjectNode().put("command", "mvn package"), context(mine));

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("proc-peer"));
        assertEquals(0, executions.get());
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void capacityRejectionReleasesReservationWithoutExecuting() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        AtomicInteger executions = new AtomicInteger();
        HighMemoryToolCallGuard.CapacityCheck rejected = new HighMemoryToolCallGuard.CapacityCheck(
                false, "REJECTED", "available RAM 1024 MB is below 8192 MB",
                false, 0L, 1, Map.of("availableRamMb", 1024L));
        CliTool guarded = guard(mine, (wait, observer) -> rejected).wrap(
                tool("bash", ignored -> {
                    executions.incrementAndGet();
                    return ToolResult.success("should not run");
                }));

        ToolResult result = guarded.execute(
                mapper.createObjectNode().put("command", "mvn test"), context(mine));

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("available RAM 1024 MB"));
        assertEquals(0, executions.get());
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void constrainedAsyncCrawlKeepsReservationAndDelegatesToInnerAdmission() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger waitRequests = new AtomicInteger();
        HighMemoryToolCallGuard.CapacityCheck constrained =
                new HighMemoryToolCallGuard.CapacityCheck(
                        false, "CONSTRAINED", "available RAM is temporarily low",
                        false, 0L, 1, Map.of("availableRamMb", 1024L));
        CliTool guarded = guard(mine, (wait, observer) -> {
            if (wait) waitRequests.incrementAndGet();
            return constrained;
        }).wrap(tool("crawl_documents", ignored -> {
            executions.incrementAndGet();
            return ToolResult.success("queued", "queued", Map.of(
                    "jobId", "local-inner-wait", "terminal", false));
        }));

        ToolResult result = guarded.execute(
                mapper.createObjectNode().put("async", true), context(mine));

        assertFalse(result.isError());
        assertEquals(1, executions.get());
        assertEquals(0, waitRequests.get(), "outer gate must not delay async job creation");
        assertEquals(1, mine.queryHighMemoryActivities().size());
    }

    @Test
    void successfulSynchronousToolCarriesEvidenceAndReleasesLane() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        AtomicInteger executions = new AtomicInteger();
        CliTool guarded = guard(mine, admittedCapacity()).wrap(
                tool("bash", ignored -> {
                    executions.incrementAndGet();
                    return ToolResult.success("exit 0", "ok", Map.of("exitCode", 0));
                }));

        ToolResult result = guarded.execute(
                mapper.createObjectNode().put("command", "mvn -DskipTests package"),
                context(mine));

        assertFalse(result.isError());
        assertEquals(1, executions.get());
        assertNotNull(result.getMetadata().get("resourcePreflight"));
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void backgroundProcessKeepsLaneUntilTerminalProcessState() throws Exception {
        Path project = tempDir.resolve("project");
        CoordinationStateManager mine = manager("mine", project);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("processId", "proc-7");
        metadata.put("pid", ProcessHandle.current().pid());
        CliTool guarded = guard(mine, admittedCapacity()).wrap(
                tool("process", ignored -> ToolResult.success("launched", "started", metadata)));
        ObjectNode params = mapper.createObjectNode().put("action", "launch")
                .put("command", "mvn test");

        ToolResult result = guarded.execute(params, context(mine));

        assertFalse(result.isError());
        assertEquals(1, mine.queryHighMemoryActivities().size());
        mine.updateProcessState("proc-7", "COMPLETED", Instant.now(), 0);
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void terminalCrawlLifecycleReleasesAsyncJobReservation() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        HighMemoryToolCallGuard guard = guard(mine, admittedCapacity());
        CliTool start = guard.wrap(tool("crawl_documents", ignored ->
                ToolResult.success("queued", "queued", Map.of(
                        "jobId", "local-crawl-42", "terminal", false))));

        ToolResult queued = start.execute(
                mapper.createObjectNode().put("async", true), context(mine));
        assertFalse(queued.isError());
        assertEquals(1, mine.queryHighMemoryActivities().size());

        CliTool resultTool = guard.wrap(tool("crawl_result", ignored ->
                ToolResult.success("crawl result", "done", Map.of(
                        "jobId", "local-crawl-42", "terminal", true,
                        "status", "COMPLETED"))));
        ToolResult completed = resultTool.execute(
                mapper.createObjectNode().put("jobId", "local-crawl-42"), context(mine));

        assertFalse(completed.isError());
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void failedAndNonterminalCancelKeepCrawlReservation() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        HighMemoryToolCallGuard guard = guard(mine, admittedCapacity());
        CliTool start = guard.wrap(tool("crawl_documents", ignored ->
                ToolResult.success("queued", "queued", Map.of(
                        "jobId", "local-crawl-cancel", "terminal", false))));
        start.execute(mapper.createObjectNode().put("async", true), context(mine));

        CliTool failedCancel = guard.wrap(tool("crawl_control", ignored ->
                ToolResult.error("cancel rejected")));
        ToolResult failed = failedCancel.execute(mapper.createObjectNode()
                .put("operation", "cancel").put("jobId", "local-crawl-cancel"), context(mine));
        assertTrue(failed.isError());
        assertEquals(1, mine.queryHighMemoryActivities().size());

        CliTool cancelling = guard.wrap(tool("crawl_control", ignored ->
                ToolResult.success("cancelling", "cancelling", Map.of(
                        "jobId", "local-crawl-cancel", "terminal", false,
                        "status", "CANCELLING"))));
        cancelling.execute(mapper.createObjectNode()
                .put("operation", "cancel").put("jobId", "local-crawl-cancel"), context(mine));
        assertEquals(1, mine.queryHighMemoryActivities().size());

        CliTool terminal = guard.wrap(tool("crawl_control", ignored ->
                ToolResult.success("cancelled", "cancelled", Map.of(
                        "jobId", "local-crawl-cancel", "terminal", true,
                        "status", "CANCELLED"))));
        terminal.execute(mapper.createObjectNode()
                .put("operation", "status").put("jobId", "local-crawl-cancel"), context(mine));
        assertTrue(mine.queryHighMemoryActivities().isEmpty());
    }

    @Test
    void blockedLaunchRegistersDeduplicatedWatchAndWakesAfterCrossProjectRelease() throws Exception {
        CoordinationStateManager peer = manager("peer", tempDir.resolve("other-project"));
        CoordinationStateManager mine = manager("mine", tempDir.resolve("my-project"));
        String activity = peer.reserveHighMemoryActivity("peer", "BUILD", "process", "build").activity().getActivityId();
        List<String> notifications = new ArrayList<>();
        mine.activityWaits().setWakeHandler("mine", notifications::add);
        AtomicInteger executions = new AtomicInteger();
        CliTool guarded = guard(mine, admittedCapacity()).wrap(tool("process", ignored -> {
            executions.incrementAndGet();
            return ToolResult.success("finished");
        }));
        ObjectNode params = mapper.createObjectNode().put("action", "launch").put("command", "mvn test");
        ToolResult blocked = guarded.execute(params, context(mine));
        ToolResult again = guarded.execute(params, context(mine));
        assertEquals(blocked.getMetadata().get("resourceWaitId"), again.getMetadata().get("resourceWaitId"));
        assertEquals(true, blocked.getMetadata().get("wakeSupported"));
        mine.activityWaits().checkNow();
        assertTrue(notifications.isEmpty());
        peer.releaseHighMemoryActivity(activity);
        mine.activityWaits().checkNow();
        mine.activityWaits().checkNow();
        assertEquals(1, notifications.size());
        assertTrue(notifications.get(0).contains("retry the blocked tool through normal admission"));
        assertEquals(0, executions.get(), "notification must not execute the blocked request");
        assertTrue(mine.queryHighMemoryActivities().isEmpty(), "readiness never retains a lane");
        // Capacity may be lost again before the agent retries; admission remains authoritative.
        peer.reserveHighMemoryActivity("peer", "BUILD", "process", "next build");
        assertTrue(guarded.execute(params, context(mine)).isError());
        assertEquals(0, executions.get());
    }

    @Test
    void watchWaitsForBothPeerProcessAndRamWithoutBlockingToolWorker() throws Exception {
        CoordinationStateManager peer = manager("peer", tempDir.resolve("project"));
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        peer.publishProcess("peer-build", "mvn package", "build", ProcessHandle.current().pid(), "RUNNING", null, "peer");
        java.util.concurrent.atomic.AtomicBoolean capacity = new java.util.concurrent.atomic.AtomicBoolean(false);
        AtomicInteger notifications = new AtomicInteger();
        mine.activityWaits().setWakeHandler("mine", ignored -> notifications.incrementAndGet());
        HighMemoryToolCallGuard guard = guard(mine, (wait, observer) -> {
            assertFalse(wait, "watch-based admission must not park a launch worker");
            return new HighMemoryToolCallGuard.CapacityCheck(capacity.get(), "sample", "RAM", false, 0, 1, Map.of());
        });
        ToolResult blocked = guard.wrap(tool("bash", ignored -> ToolResult.success("done")))
                .execute(mapper.createObjectNode().put("command", "mvn test"), context(mine));
        String id = blocked.getMetadata().get("resourceWaitId").toString();
        mine.activityWaits().checkNow();
        peer.updateProcessState("peer-build", "COMPLETED", Instant.now(), 0);
        mine.activityWaits().checkNow();
        assertEquals(0, notifications.get());
        capacity.set(true);
        mine.activityWaits().checkNow();
        assertEquals(1, notifications.get());
        assertEquals(ai.kompile.cli.main.coordination.ActivityWaitRegistry.State.READY,
                mine.activityWaits().get("mine", id).state());
    }

    @Test
    void externalClientCanHoldWaitAndCancelWithoutWakePromise() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        var wait = mine.activityWaits().watch("mine", "build", "BUILD", "mvn test", () -> true);
        assertFalse(wait.wakeSupported());
        EditCoordinatorTool tool = new EditCoordinatorTool(mine);
        mine.activityWaits().checkNow();
        ToolResult ready = tool.execute(mapper.createObjectNode().put("action", "wait_for_activity")
                .put("wait_id", wait.waitId()), context(mine));
        assertEquals(true, ready.getMetadata().get("ready"));
        assertFalse(ready.isError());
        ToolResult cancelled = tool.execute(mapper.createObjectNode().put("action", "cancel_activity_wait")
                .put("wait_id", wait.waitId()), context(mine));
        assertEquals("CANCELLED", cancelled.getMetadata().get("state"));
        assertTrue(tool.execute(mapper.createObjectNode().put("action", "wait_for_activity")
                .put("wait_id", "missing"), context(mine)).isError());
        assertTrue(tool.parameterSchema().path("properties").path("action").path("enum").toString()
                .contains("watch_activity"));
    }

    @Test
    void dryPreflightNeverRegistersAWatch() throws Exception {
        CoordinationStateManager peer = manager("peer", tempDir.resolve("peer-project"));
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        peer.reserveHighMemoryActivity("peer", "BUILD", "process", "build");
        assertTrue(guard(mine, admittedCapacity()).inspect("build", "test", context(mine)).isError());
        assertTrue(mine.activityWaits().list("mine").isEmpty());
    }

    @Test
    void configuredSmallTestRunsAlongsideHeavyPeerWithoutCapacityProbe() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        CoordinationStateManager peer = manager("peer", tempDir.resolve("peer"));
        peer.reserveHighMemoryActivity("peer", "BUILD", "process", "heavy build");
        assertTrue(ResourcePolicy.command(mine.getProjectRoot(), "set " + ResourcePolicyTest.smallTestPolicy()).contains("saved"));
        CliTool guarded = guard(mine, (wait, observer) -> {
            throw new AssertionError("small test must not check heavy capacity");
        }).wrap(tool("bash", ignored -> ToolResult.success("ran")));
        ToolResult result = guarded.execute(mapper.createObjectNode().put("command", "mvn test -Dtest=SmallTest"), context(mine));
        assertFalse(result.isError());
        assertNotNull(result.getMetadata().get("resourceClassification"));
        assertTrue(mine.queryHighMemoryActivities().stream().noneMatch(a -> "mine".equals(a.getSessionId())));
    }

    @Test
    void publishedLowProcessRetainsClassAfterPolicyChangesAndDoesNotBlock() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        ObjectNode policy = ResourcePolicyTest.smallTestPolicy();
        ((ObjectNode) policy.path("rules").get(0)).put("tool", "process");
        assertTrue(ResourcePolicy.command(mine.getProjectRoot(), "set " + policy).contains("saved"));
        mine.publishProcess("small", "mvn test -Dtest=SmallTest", "small tests", ProcessHandle.current().pid(), "RUNNING", null, "mine");
        assertTrue(ResourcePolicy.command(mine.getProjectRoot(),
                "set {\"defaultClass\":\"high\",\"unknownShellClass\":\"high\",\"rules\":[]}").contains("saved"));
        assertEquals("low", mine.queryProcesses().get(0).getResourceClass());
        ToolResult result = guard(mine, admittedCapacity()).wrap(tool("bash", ignored -> ToolResult.success("ran")))
                .execute(mapper.createObjectNode().put("command", "mvn package"), context(mine));
        assertFalse(result.isError());
    }

    @Test
    void argumentAwarePreflightAndWatchObservePolicyChanges() throws Exception {
        CoordinationStateManager mine = manager("mine", tempDir.resolve("project"));
        CoordinationStateManager peer = manager("peer", tempDir.resolve("peer"));
        peer.reserveHighMemoryActivity("peer", "BUILD", "process", "heavy");
        var guard = guard(mine, admittedCapacity());
        var args = mapper.createObjectNode().put("command", "mvn test -Dtest=SmallTest");
        assertTrue(guard.inspectToolCall("bash", args, context(mine)).isError());
        var wait = guard.watchToolCall("bash", args, context(mine));
        assertTrue(ResourcePolicy.command(mine.getProjectRoot(), "set " + ResourcePolicyTest.smallTestPolicy()).contains("saved"));
        assertFalse(guard.inspectToolCall("bash", args, context(mine)).isError());
        var ready = mine.activityWaits().await(mine.getSessionId(), wait.waitId(), 5_000L, () -> false);
        assertEquals(ai.kompile.cli.main.coordination.ActivityWaitRegistry.State.READY, ready.state());
    }

    private CoordinationStateManager manager(String session, Path project) throws Exception {
        Files.createDirectories(project);
        // The built-in policy keeps admission off; these tests exercise the guarded
        // lane, so opt this test project in explicitly.
        Path policyDir = project.resolve(".kompile");
        Files.createDirectories(policyDir);
        Files.writeString(policyDir.resolve("resource-policy.json"),
                "{\"defaultClass\":\"high\",\"unknownShellClass\":\"high\"}");
        CoordinationStateManager manager = new CoordinationStateManager(
                project, session, mapper, tempDir.resolve("system-state"));
        managers.add(manager);
        return manager;
    }

    private ToolContext context(CoordinationStateManager manager) {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        AgentConfig agent = AgentConfig.builder("test-agent").roleName("coder").build();
        return new ToolContext(manager.getSessionId(), agent, permissions,
                manager.getProjectRoot(), new ToolRegistry(mapper));
    }

    private HighMemoryToolCallGuard guard(
            CoordinationStateManager manager, HighMemoryToolCallGuard.CapacityProbe capacity) {
        return new HighMemoryToolCallGuard(manager, capacity, mapper);
    }

    private static HighMemoryToolCallGuard.CapacityProbe admittedCapacity() {
        return (wait, observer) -> new HighMemoryToolCallGuard.CapacityCheck(
                true, "ADMITTED", null, false, 0L, 1,
                Map.of("availableRamMb", 64_000L));
    }

    private CliTool tool(String id, Function<JsonNode, ToolResult> execution) {
        return new CliTool() {
            @Override public String id() { return id; }
            @Override public String description() { return "test " + id; }
            @Override public JsonNode parameterSchema() { return mapper.createObjectNode(); }
            @Override public String permissionKey() { return id; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                return execution.apply(params);
            }
        };
    }
}
