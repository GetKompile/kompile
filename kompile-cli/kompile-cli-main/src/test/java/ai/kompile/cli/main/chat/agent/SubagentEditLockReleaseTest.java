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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import ai.kompile.cli.main.chat.workflow.WorkflowPolicy;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.EditLockEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An in-process subagent's edit locks are released the moment its run ends — on
 * completion, failure or cancel, for both the direct-model and the server-backed runner —
 * instead of lingering until their lease runs out. Only that subagent's locks go: the
 * session's own locks and a sibling agent's locks stay.
 */
@TemporaryUserHome
class SubagentEditLockReleaseTest {

    private static final String SESSION = "session-runner";
    private static final String AGENT_PREFIX = "implementer-";

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectMapper timeAware = JsonUtils.standardMapper();
    private final ToolRegistry tools = new ToolRegistry(mapper);
    private final PermissionService permissions = new PermissionService();
    private final AgentConfig agent = AgentConfig.builder("implementer").enabledTools(Set.of("*")).build();
    private Path project;
    private CoordinationStateManager coordinator;
    private String sessionLock;
    private String siblingLock;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(directory.resolve("project"));
        coordinator = new CoordinationStateManager(project, SESSION, mapper, directory.resolve("system-activity"));
        tools.register(new EditCoordinatorTool(coordinator));
        permissions.setAutoApproveAll(true);
        // Locks that must survive any subagent's end.
        sessionLock = coordinator.tryAcquireEditLock(abs("keep-session.txt"), "edit").getLockId();
        siblingLock = coordinator.tryAcquireEditLock(abs("keep-sibling.txt"), "edit", "sibling-agent").getLockId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        coordinator.shutdown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String abs(String relative) {
        return project.resolve(relative).toAbsolutePath().toString();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ToolContext parent(boolean supervised) {
        ToolContext parent = new ToolContext("parent", agent, permissions, project, tools);
        parent.setAutoApproveAll(true);
        parent.setOutputConsumer(ignored -> {});
        if (supervised) {
            // OFF lets the child's coordinator call run without validation gates.
            parent.setSubagentSupervision(new DirectSubagentSupervision.Contract(
                    new WorkflowController.ChildContract(WorkflowPolicy.off(), "", false),
                    "work", null, null, null, false, 1));
        }
        return parent;
    }

    /** Every lock file on disk, read without the coordinator lock. */
    private List<EditLockEntry> lockFiles() throws IOException {
        Path edits = coordinator.getProjectRoot().resolve(".kompile").resolve("coordination").resolve("edits");
        List<EditLockEntry> locks = new ArrayList<>();
        if (!Files.isDirectory(edits)) return locks;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(edits, "*.lock.json")) {
            for (Path file : files) {
                try {
                    locks.add(timeAware.readValue(file.toFile(), EditLockEntry.class));
                } catch (IOException concurrentlyReplaced) {
                    // An atomic rewrite in flight; the next poll sees it.
                }
            }
        }
        return locks;
    }

    private Set<String> lockIds() throws IOException {
        return lockFiles().stream().map(EditLockEntry::getLockId).collect(Collectors.toSet());
    }

    private List<EditLockEntry> subagentLocks() throws IOException {
        return lockFiles().stream()
                .filter(lock -> lock.getOwnerAgent() != null && lock.getOwnerAgent().startsWith(AGENT_PREFIX))
                .collect(Collectors.toList());
    }

    /** Polls until the running subagent's lock is on disk (the tool runs on the runner thread). */
    private List<EditLockEntry> awaitSubagentLock() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<EditLockEntry> locks = subagentLocks();
        while (locks.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            locks = subagentLocks();
        }
        return locks;
    }

    private void assertHeldBySubagent(List<EditLockEntry> during, String file, String subagentId) {
        assertEquals(1, during.size(), during::toString);
        EditLockEntry held = during.get(0);
        assertEquals(SESSION, held.getSessionId());
        assertEquals(subagentId, held.getOwnerAgent(), "the lock is owned by the subagent's identity");
        assertEquals(abs(file), held.getAbsolutePath());
        assertNotNull(held.getLeaseExpiresAt(), "a subagent lock carries a lease");
    }

    private void assertOnlySurvivorsRemain() throws IOException {
        assertEquals(List.of(), subagentLocks(), "the ended subagent's locks are released at once");
        assertEquals(Set.of(sessionLock, siblingLock), lockIds(),
                "the session's own lock and a sibling agent's lock are untouched");
    }

    /** Records the subagent id and whether its locks were already gone when its end was announced. */
    private SubagentRunner.LifecycleListener listener(AtomicReference<String> id, AtomicBoolean releasedBeforeEnd) {
        return new SubagentRunner.LifecycleListener() {
            @Override
            public void onSubagentStart(String subagentId, String type, String description) {
                id.set(subagentId);
            }

            @Override
            public void onSubagentEnd(String subagentId) {
                try {
                    releasedBeforeEnd.set(subagentLocks().isEmpty());
                } catch (IOException e) {
                    releasedBeforeEnd.set(false);
                }
            }
        };
    }

    // ── Direct-model runner ──────────────────────────────────────────────────

    private String registerEditDelta(String file) {
        ObjectNode arguments = mapper.createObjectNode()
                .put("action", "register_edit")
                .put("file_path", file);
        ObjectNode delta = mapper.createObjectNode();
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", 0).put("id", "call").put("type", "function");
        call.putObject("function").put("name", "edit_coordinator").put("arguments", arguments.toString());
        return delta.toString();
    }

    private static void answer(HttpExchange exchange, String delta) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":" + delta
                + ",\"finish_reason\":null}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    /**
     * The model registers {@code file} through edit_coordinator, then (second request) the
     * test snapshots the locks, runs {@code duringRun}, and lets the model finish.
     */
    private void directModel(String file, List<List<EditLockEntry>> seen, Runnable duringRun) {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = requests.getAndIncrement();
            if (index == 0) {
                answer(exchange, registerEditDelta(file));
                return;
            }
            if (index == 1) {
                // The tool call has run; snapshot once (a client retry must not re-run this).
                seen.add(subagentLocks());
                duringRun.run();
            }
            try {
                answer(exchange, "{\"content\":\"done\"}");
            } catch (IOException cancelledClientGone) {
                exchange.close();
            }
        });
        server.start();
    }

    @Test
    @Timeout(30)
    void directSubagentCompletionReleasesItsLocks() throws Exception {
        List<List<EditLockEntry>> seen = new CopyOnWriteArrayList<>();
        directModel("src/A.java", seen, () -> {});
        DirectSubagentRunner runner = new DirectSubagentRunner(
                new ChatConfig("custom", "test", "model", baseUrl()), mapper, tools, permissions,
                new TerminalRenderer(false));
        AtomicReference<String> id = new AtomicReference<>();
        AtomicBoolean releasedBeforeEnd = new AtomicBoolean();
        runner.setLifecycleListener(listener(id, releasedBeforeEnd));

        String result = runner.runSubagent(agent, "Lock src/A.java", parent(true));

        assertTrue(result.contains("done"), result);
        assertEquals(1, seen.size());
        assertTrue(id.get().startsWith(AGENT_PREFIX), id.get());
        assertHeldBySubagent(seen.get(0), "src/A.java", id.get());
        assertOnlySurvivorsRemain();
        assertTrue(releasedBeforeEnd.get(), "released before the end is announced");
    }

    @Test
    @Timeout(30)
    void directSubagentCancelReleasesItsLocks() throws Exception {
        List<List<EditLockEntry>> seen = new CopyOnWriteArrayList<>();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<DirectSubagentRunner> runnerRef = new AtomicReference<>();
        directModel("src/C.java", seen, () -> cancelled.set(runnerRef.get().cancel(id.get())));
        DirectSubagentRunner runner = new DirectSubagentRunner(
                new ChatConfig("custom", "test", "model", baseUrl()), mapper, tools, permissions,
                new TerminalRenderer(false));
        runnerRef.set(runner);
        AtomicBoolean releasedBeforeEnd = new AtomicBoolean();
        runner.setLifecycleListener(listener(id, releasedBeforeEnd));

        try {
            runner.runSubagent(agent, "Lock src/C.java", parent(true));
        } catch (Exception cancelledRun) {
            // A cancelled run may end by throwing; either way it has ended.
        }

        assertTrue(cancelled.get(), "the run was cancelled while it held the lock");
        assertEquals(1, seen.size());
        assertHeldBySubagent(seen.get(0), "src/C.java", id.get());
        assertOnlySurvivorsRemain();
        assertTrue(releasedBeforeEnd.get(), "released before the end is announced");
    }

    // ── Server-backed runner ─────────────────────────────────────────────────

    /** How the server-backed subagent's stream should end once its lock is on disk. */
    private enum Ending { COMPLETE, DROP, CANCEL }

    private void serverAgent(String file, Ending ending, List<List<EditLockEntry>> seen,
                             AtomicReference<ServerSubagentRunner> runner, AtomicReference<String> id) {
        server.createContext("/api/agents/chat/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            ObjectNode toolCall = mapper.createObjectNode().put("name", "edit_coordinator");
            toolCall.putObject("arguments").put("action", "register_edit").put("file_path", file);
            body.write(("event: tool_call\ndata: " + toolCall + "\n\n").getBytes(StandardCharsets.UTF_8));
            body.flush();
            seen.add(awaitSubagentLock());
            try {
                switch (ending) {
                    case COMPLETE -> body.write(("event: chunk\ndata: \"done\"\n\n"
                            + "event: complete\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8));
                    case DROP -> { /* close without a terminal event: the run fails */ }
                    case CANCEL -> runner.get().cancel(id.get());
                }
                exchange.close();
            } catch (IOException clientGone) {
                exchange.close();
            }
        });
        server.start();
    }

    private ServerSubagentRunner serverRunner(AtomicReference<ServerSubagentRunner> ref,
                                              AtomicReference<String> id, AtomicBoolean releasedBeforeEnd) {
        ServerSubagentRunner runner = new ServerSubagentRunner(baseUrl(), tools, permissions, mapper,
                new TerminalRenderer(false));
        runner.setLifecycleListener(listener(id, releasedBeforeEnd));
        ref.set(runner);
        return runner;
    }

    @Test
    @Timeout(30)
    void serverSubagentCompletionReleasesItsLocks() throws Exception {
        List<List<EditLockEntry>> seen = new CopyOnWriteArrayList<>();
        AtomicReference<ServerSubagentRunner> ref = new AtomicReference<>();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicBoolean releasedBeforeEnd = new AtomicBoolean();
        serverAgent("src/B.java", Ending.COMPLETE, seen, ref, id);

        String result = serverRunner(ref, id, releasedBeforeEnd).runSubagent(agent, "Lock src/B.java", parent(false));

        assertTrue(result.contains("done"), result);
        assertEquals(1, seen.size());
        assertHeldBySubagent(seen.get(0), "src/B.java", id.get());
        assertOnlySurvivorsRemain();
        assertTrue(releasedBeforeEnd.get(), "released before the end is announced");
    }

    @Test
    @Timeout(30)
    void serverSubagentFailureReleasesItsLocks() throws Exception {
        List<List<EditLockEntry>> seen = new CopyOnWriteArrayList<>();
        AtomicReference<ServerSubagentRunner> ref = new AtomicReference<>();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicBoolean releasedBeforeEnd = new AtomicBoolean();
        serverAgent("src/D.java", Ending.DROP, seen, ref, id);
        ServerSubagentRunner runner = serverRunner(ref, id, releasedBeforeEnd);

        IOException failure = assertThrows(IOException.class,
                () -> runner.runSubagent(agent, "Lock src/D.java", parent(false)));

        assertTrue(failure.getMessage().contains("terminal event"), failure.getMessage());
        assertEquals(1, seen.size());
        assertHeldBySubagent(seen.get(0), "src/D.java", id.get());
        assertOnlySurvivorsRemain();
        assertTrue(releasedBeforeEnd.get(), "released before the end is announced");
    }

    @Test
    @Timeout(30)
    void serverSubagentCancelReleasesItsLocks() throws Exception {
        List<List<EditLockEntry>> seen = new CopyOnWriteArrayList<>();
        AtomicReference<ServerSubagentRunner> ref = new AtomicReference<>();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicBoolean releasedBeforeEnd = new AtomicBoolean();
        serverAgent("src/E.java", Ending.CANCEL, seen, ref, id);

        String result = serverRunner(ref, id, releasedBeforeEnd).runSubagent(agent, "Lock src/E.java", parent(false));

        assertTrue(result.contains("aborted"), result);
        assertEquals(1, seen.size());
        assertHeldBySubagent(seen.get(0), "src/E.java", id.get());
        assertOnlySurvivorsRemain();
        assertTrue(releasedBeforeEnd.get(), "released before the end is announced");
    }
}
