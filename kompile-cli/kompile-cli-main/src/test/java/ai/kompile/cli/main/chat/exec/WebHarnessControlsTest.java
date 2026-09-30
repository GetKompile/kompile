package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
@TemporaryUserHome
class WebHarnessControlsTest {
    @TempDir Path directory;
    private static ByteArrayInputStream bytes(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
    private static String frame(String action) { return "{\"version\":1,\"requestId\":\"r1\",\"action\":\"" + action + "\"}"; }
    private static String command(String requestId, String text) {
        return "{\"version\":1,\"requestId\":\"" + requestId + "\",\"action\":\"command\",\"text\":\"" + text + "\"}";
    }
    private static String approve(String requestId, String gate) {
        return "{\"version\":1,\"requestId\":\"" + requestId + "\",\"action\":\"workflow_approve\""
                + (gate == null ? "" : ",\"text\":\"" + gate + "\"") + "}";
    }

    @AfterEach void noTeamOutlivesATest() { WorkflowSessionContext.activate(null); }

    @Test void strictProtocolRejectsUnknownTypesFieldsAndTrailingData() {
        assertEquals("background", WebHarnessControls.parse(frame("background")).action());
        for (String s : List.of("[]", "null", frame("launch"), frame("background") + " {}",
                frame("background").replace("1,", "1.0,"), frame("background").replace("1,", "4294967297,"),
                frame("background").replace("\"r1\"", "null"),
                frame("background").replace("}", ",\"command\":\"sh\"}"),
                frame("background").replace("}", ",\"version\":1}"), frame("process_kill"),
                frame("background").replace("}", ",\"text\":\"unexpected\"}"),
                frame("input").replace("}", ",\"text\":\" /model secret\"}"),
                frame("input").replace("}", ",\"text\":\"" + "x".repeat(32769) + "\"}"))) {
            assertThrows(IllegalArgumentException.class, () -> WebHarnessControls.parse(s), s.substring(0, Math.min(100, s.length())));
        }
    }

    @Test void firstLineDoesNotReadAheadAndPlainPromptStillConsumesEof() throws Exception {
        String initial = "{\"version\":1,\"rawInput\":\"hello\"}";
        var input = bytes(initial + "\n" + frame("process_list") + "\n");
        var controls = new WebHarnessControls(input);
        assertEquals(initial, controls.readInitialInput());
        assertEquals(frame("process_list"), WebHarnessControls.readLine(input, 65536));
        assertNull(WebHarnessControls.readLine(input, 65536));
        String pretty = "{\n\"version\":1,\n\"rawInput\":\"hello\"\n}";
        assertEquals("hello", WebChatInput.parse(PromptResolver.resolve(List.of(), bytes(pretty))).rawInput());
    }

    @Test void readerBoundsBytesAndRejectsMalformedUtf8() throws Exception {
        assertEquals("abcd", WebHarnessControls.readLine(bytes("abcd\nnext"), 4));
        assertThrows(IOException.class, () -> WebHarnessControls.readLine(bytes("abcde\n"), 4));
        assertThrows(IOException.class, () -> WebHarnessControls.readLine(new ByteArrayInputStream(new byte[]{(byte)0xc3, 10}), 4));
    }

    @Test void queuedInputRunsSeriallyAndBackgroundIsRejectedWhileThinking() throws Exception {
        var loop = mock(AgenticChatLoop.class);
        var events = new ArrayList<HeadlessRunEvent>();
        var prompts = new ArrayList<String>();
        var acknowledged = new CountDownLatch(2);
        String input = frame("background") + "\n" + "{\"version\":1,\"requestId\":\"r2\",\"action\":\"input\",\"text\":\"next\"}\n";
        try (var processes = new BackgroundProcessManager("web-controls-queue", directory);
             var controls = new WebHarnessControls(bytes(input))) {
            String result = controls.run(loop, processes, "s", "first", 4000, new AtomicBoolean(), prompt -> {
                prompts.add(prompt);
                if (prompt.equals("first")) assertTrue(acknowledged.await(2, TimeUnit.SECONDS));
                return "reply:" + prompt;
            }, (action, id) -> { throw new AssertionError("No process execution expected"); }, event -> {
                events.add(event);
                if (event.type() == HeadlessRunEvent.Type.CONTROL) acknowledged.countDown();
            });
            assertEquals("reply:next", result);
            assertEquals(List.of("first", "next"), prompts);
            var starts = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.TURN_STARTED).toList();
            assertEquals(List.of(1L, 2L), starts.stream().map(e -> e.data().path("turnId").asLong()).toList());
            assertEquals("initial", starts.get(0).data().path("source").asText());
            assertEquals("", starts.get(0).data().path("text").asText(), "never expose initial harness decorations");
            assertEquals("user", starts.get(1).data().path("source").asText());
            assertEquals("next", starts.get(1).data().path("text").asText());
            assertFalse(events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.CONTROL).findFirst().orElseThrow().data().path("ok").asBoolean());
            verify(loop, never()).requestBackgroundActiveTurn(any(), any(), any(), any());
            verify(loop).cancelDetachedInvocations();
        }
    }

    @Test void fastExitRetainsOutputAndWakesParentBeforeFinalResult() throws Exception {
        var prompts = new ArrayList<String>();
        var events = new ArrayList<HeadlessRunEvent>();
        try (var processes = new BackgroundProcessManager("web-controls-fast", directory);
             var controls = new WebHarnessControls(bytes(""))) {
            String result = controls.run(mock(AgenticChatLoop.class), processes, "s", "launch", 4000, new AtomicBoolean(), prompt -> {
                prompts.add(prompt);
                if (prompt.equals("launch")) processes.launchMonitored("printf 'fast-exit-evidence\\n'", "fast", directory, "");
                return "response";
            }, (action, id) -> ToolResult.success(processes.readOutput(id, 50)), events::add);
            assertEquals("response", result);
            assertEquals(2, prompts.size());
            assertTrue(prompts.get(1).contains("fast-exit-evidence"));
            var last = events.get(events.size() - 1).data();
            assertFalse(last.path("turnActive").asBoolean());
            assertEquals("COMPLETED", last.path("processes").get(0).path("state").asText());
            assertTrue(last.path("processes").get(0).path("output").asText().contains("fast-exit-evidence"));
        }
    }

    @Test void foreignProcessesNeverReachExecutorAndOwnedOutputUsesPolicyBoundary() throws Exception {
        var seen = new ArrayList<String>();
        var events = new ArrayList<HeadlessRunEvent>();
        var latch = new CountDownLatch(2);
        try (var processes = new BackgroundProcessManager("web-controls-owner", directory)) {
            var owned = processes.launchMonitored("printf 'owned\\n'", "owned", directory, "");
            String first = "{\"version\":1,\"requestId\":\"r1\",\"action\":\"process_output\",\"targetId\":\"foreign\"}";
            String second = first.replace("r1", "r2").replace("foreign", owned.getId());
            try (var controls = new WebHarnessControls(bytes(first + "\n" + second + "\n"))) {
                controls.run(mock(AgenticChatLoop.class), processes, "s", "first", 4000, new AtomicBoolean(), prompt -> {
                    assertTrue(latch.await(2, TimeUnit.SECONDS)); return "done";
                }, (action, id) -> { seen.add(id); return ToolResult.error("policy denied"); }, e -> {
                    events.add(e); if (e.type() == HeadlessRunEvent.Type.CONTROL) latch.countDown();
                });
            }
            assertFalse(seen.isEmpty());
            assertTrue(seen.stream().allMatch(owned.getId()::equals));
            assertEquals(2, events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.CONTROL && !e.data().path("ok").asBoolean()).count());
        }
    }

    @Test void realBlockingWorkerDetachesAndCompletesWithoutPretendTurnDetach() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var registry = new ToolRegistry(mapper);
        var agents = new AgentRegistry();
        var permission = new PermissionService();
        var loop = new AgenticChatLoop(null, mapper, registry, permission, agents, directory, null, null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var detached = new CountDownLatch(1);
        var completed = new CompletableFuture<ToolResult>();
        CliTool tool = mock(CliTool.class);
        when(tool.execute(any(), any())).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return ToolResult.success("retained-result"); });
        var eligible = AgenticChatLoop.class.getDeclaredMethod("setBackgroundableToolPhase", boolean.class);
        eligible.setAccessible(true);
        var execute = AgenticChatLoop.class.getDeclaredMethod("executeToolInterruptibly", CliTool.class,
                com.fasterxml.jackson.databind.JsonNode.class, ToolContext.class, String.class, Function.class);
        execute.setAccessible(true);
        assertFalse(loop.requestBackgroundActiveTurn(s -> {}, () -> {}, r -> {}, () -> {}));
        eligible.invoke(loop, true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var parent = executor.submit(() -> (ToolResult) execute.invoke(loop, tool, mapper.createObjectNode(),
                    new ToolContext("s", agents.getDefault(), permission, directory, registry), "task", (Function<ToolResult, Path>) result -> null));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(loop.requestBackgroundActiveTurn(s -> {}, detached::countDown, completed::complete, () -> fail("unexpected rejection")));
            assertTrue(detached.await(2, TimeUnit.SECONDS));
            assertTrue(parent.get(2, TimeUnit.SECONDS).getOutput().contains("still running"));
            assertFalse(completed.isDone());
            release.countDown();
            assertEquals("retained-result", completed.get(2, TimeUnit.SECONDS).getOutput());
        } finally { release.countDown(); loop.cancelDetachedInvocations(); executor.shutdownNow(); }
    }

    @Test void eligibilityLossRejectsPendingRequestBeforeNextInvocation() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper), new PermissionService(),
                new AgentRegistry(), directory, null, null);
        var eligible = AgenticChatLoop.class.getDeclaredMethod("setBackgroundableToolPhase", boolean.class);
        eligible.setAccessible(true);
        eligible.invoke(loop, true);
        var rejected = new AtomicBoolean();
        assertTrue(loop.requestBackgroundActiveTurn(s -> {}, () -> fail("not detached"), r -> {}, () -> rejected.set(true)));
        eligible.invoke(loop, false);
        assertTrue(rejected.get());
        eligible.invoke(loop, true);
        assertTrue(loop.requestBackgroundActiveTurn(s -> {}, () -> {}, r -> {}, () -> {}));
        eligible.invoke(loop, false);
    }

    @Test void liveDetachAcknowledgesOnlyCallbackAndWaitsForCompletionWakeup() throws Exception {
        var loop = mock(AgenticChatLoop.class);
        when(loop.isBackgroundableToolPhaseActive()).thenReturn(true);
        var calls = new ArrayList<String>();
        var events = new ArrayList<HeadlessRunEvent>();
        var acknowledged = new CountDownLatch(1);
        var completion = new AtomicReference<Consumer<ToolResult>>();
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        when(loop.requestBackgroundActiveTurn(any(), any(), any(), any())).thenAnswer(call -> {
            completion.set(call.getArgument(2));
            ((Runnable) call.getArgument(1)).run();
            return true;
        });
        try (var processes = new BackgroundProcessManager("web-controls-detach", directory);
             var controls = new WebHarnessControls(pipe)) {
            String result = controls.run(loop, processes, "s", "first", 4000, new AtomicBoolean(), prompt -> {
                calls.add(prompt);
                if (prompt.equals("first")) {
                    writer.write((frame("background") + "\n").getBytes(StandardCharsets.UTF_8)); writer.flush();
                    assertTrue(acknowledged.await(2, TimeUnit.SECONDS));
                    return "parent-released";
                }
                assertTrue(prompt.contains("completed-output"));
                return "final-after-wakeup";
            }, (action, id) -> ToolResult.error("unused"), event -> {
                events.add(event);
                if (event.type() == HeadlessRunEvent.Type.CONTROL && event.data().path("ok").asBoolean()) acknowledged.countDown();
                if (event.type() == HeadlessRunEvent.Type.TURN_COMPLETE && event.data().path("text").asText().equals("parent-released"))
                    completion.get().accept(ToolResult.success("\033[33mcompleted-output\033[0m"));
            });
            assertEquals("final-after-wakeup", result);
            assertEquals(2, calls.size());
            // The model reads the detached result verbatim, as in the CLI; the browser gets it unstyled.
            assertTrue(calls.get(1).contains("\033[33mcompleted-output"), calls.get(1));
            var tasks = events.get(events.size() - 1).data().path("tasks");
            assertTrue(StreamSupport.stream(tasks.spliterator(), false)
                    .anyMatch(task -> task.path("output").asText().contains("completed-output")), tasks.toString());
            events.forEach(e -> assertNoEscape(e.data(), e.type().name()));
        } finally { writer.close(); }
    }

    @Test void timeoutCancelsForegroundAndClosesReader() throws Exception {
        var loop = mock(AgenticChatLoop.class);
        var interrupted = new CountDownLatch(1);
        var closed = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() { closed.set(true); }
        };
        try (var processes = new BackgroundProcessManager("web-controls-timeout", directory);
             var controls = new WebHarnessControls(input)) {
            assertNull(controls.run(loop, processes, "s", "wait", 100, new AtomicBoolean(), prompt -> {
                try { new CountDownLatch(1).await(); return "unreachable"; }
                finally { interrupted.countDown(); }
            }, (action, id) -> ToolResult.error("unused"), event -> {}));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(closed.get());
            verify(loop).cancelActiveTurn();
            verify(loop).cancelDetachedInvocations();
        }
    }

    @Test void ownedKillUsesNativeLifecycleAndProducesCompletionWakeup() throws Exception {
        var events = new ArrayList<HeadlessRunEvent>();
        try (var processes = new BackgroundProcessManager("web-controls-kill", directory)) {
            var owned = processes.launchMonitored("sleep 30", "kill me", directory, "");
            String input = "{\"version\":1,\"requestId\":\"r1\",\"action\":\"process_kill\",\"targetId\":\"" + owned.getId() + "\"}\n";
            var tool = new ProcessManagementTool(processes);
            var mapper = JsonUtils.standardMapper();
            var permission = new PermissionService();
            permission.setAutoApproveAll(true);
            var context = new ToolContext("s", new AgentRegistry().getDefault(), permission, directory, new ToolRegistry(mapper));
            try (var controls = new WebHarnessControls(bytes(input))) {
                controls.run(mock(AgenticChatLoop.class), processes, "s", "initial", 4000, new AtomicBoolean(), p -> "done",
                        (action, id) -> tool.execute(mapper.createObjectNode().put("action", action).put("process_id", id), context), events::add);
            }
            assertEquals(BackgroundProcessManager.ProcessState.KILLED, owned.getState());
            assertTrue(events.stream().anyMatch(e -> e.type() == HeadlessRunEvent.Type.CONTROL && e.data().path("ok").asBoolean()));
        }
    }

    @Test void targetedChildCancellationLeavesParentRunning() throws Exception {
        var runner = mock(ai.kompile.cli.main.chat.agent.SubagentRunner.class);
        var lifecycle = new AtomicReference<ai.kompile.cli.main.chat.agent.SubagentRunner.LifecycleListener>();
        doAnswer(c -> { lifecycle.set(c.getArgument(0)); return null; }).when(runner).setLifecycleListener(any());
        when(runner.canCancel("child")).thenReturn(true);
        when(runner.cancel("child")).thenReturn(true);
        var loop = mock(AgenticChatLoop.class);
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        var ack = new CountDownLatch(1);
        try (var processes = new BackgroundProcessManager("child-cancel", directory);
             var controls = new WebHarnessControls(pipe)) {
            assertEquals("parent continues", controls.run(loop, processes, "s", "initial", 4000, new AtomicBoolean(), prompt -> {
                lifecycle.get().onSubagentStart("child", "coder", "test");
                writer.write("{\"version\":1,\"requestId\":\"r\",\"action\":\"subagent_cancel\",\"targetId\":\"child\"}\n".getBytes(StandardCharsets.UTF_8));
                writer.flush();
                assertTrue(ack.await(2, TimeUnit.SECONDS));
                return "parent continues";
            }, (a, id) -> ToolResult.error("unused"), e -> {
                if (e.type() == HeadlessRunEvent.Type.CONTROL) {
                    assertTrue(e.data().path("ok").asBoolean());
                    ack.countDown();
                }
            }, runner));
            verify(loop, never()).cancelActiveTurn();
            verify(runner, atLeastOnce()).cancel("child");
        } finally { writer.close(); }
    }

    @Test void childControlsAreStrictlyTargetedAndRejectSlashInput() {
        String base = "{\"version\":1,\"requestId\":\"r\",\"action\":\"subagent_input\",\"targetId\":\"child\",\"text\":\"continue\"}";
        assertEquals("child", WebHarnessControls.parse(base).targetId());
        assertThrows(IllegalArgumentException.class, () -> WebHarnessControls.parse(base.replace(",\"targetId\":\"child\"", "")));
        assertThrows(IllegalArgumentException.class, () -> WebHarnessControls.parse(base.replace("continue", " /model x")));
        assertThrows(IllegalArgumentException.class, () -> WebHarnessControls.parse(base.replace("subagent_input", "subagent_cancel")));
    }

    @Test void commandFramesCarryOnlySlashText() {
        String base = command("r", "/processes");
        assertEquals("/processes", WebHarnessControls.parse(base).text());
        for (String s : List.of(command("r", "hello"), base.replace(",\"text\":\"/processes\"", ""),
                base.replace("}", ",\"targetId\":\"p1\"}"), frame("input").replace("}", ",\"text\":\"/processes\"}"))) {
            assertThrows(IllegalArgumentException.class, () -> WebHarnessControls.parse(s), s);
        }
    }

    @Test void gateApprovalsNameAGateOrNone() {
        String base = approve("r", null);
        assertEquals("", WebHarnessControls.parse(base).text());
        assertEquals("approved-design", WebHarnessControls.parse(approve("r", "approved-design")).text());
        for (String s : List.of(base.replace("}", ",\"targetId\":\"p1\"}"), approve("r", " "),
                base.replace("}", ",\"text\":7}"), approve("r", "approved\\tdesign"), approve("r", "g".repeat(257)))) {
            assertThrows(IllegalArgumentException.class, () -> WebHarnessControls.parse(s), s.substring(0, Math.min(100, s.length())));
        }
    }

    @Test void liveGateApprovalsReachTheRunsTeamAndTheSessionsLaterRuns() throws Exception {
        // The class shares one temporary home, so the team records its gates under its own session id.
        WorkflowSessionContext.start("s-team", new WorkflowTeamSnapshot(gatedTeam(), Map.of(), null));
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        var replies = new ConcurrentHashMap<String, JsonNode>();
        var answered = new CountDownLatch(4);
        try (var processes = new BackgroundProcessManager("web-controls-workflow", directory);
             var controls = new WebHarnessControls(pipe)) {
            assertEquals("done", controls.run(mock(AgenticChatLoop.class), processes, "s", "first", 4000,
                    new AtomicBoolean(), prompt -> {
                        writer.write((String.join("\n", approve("a1", null), approve("a2", "shipped"),
                                approve("a3", null), approve("a4", null)) + "\n").getBytes(StandardCharsets.UTF_8));
                        writer.flush();
                        assertTrue(answered.await(3, TimeUnit.SECONDS));
                        return "done";
                    }, (action, id) -> { throw new AssertionError("No process execution expected"); }, event -> {
                        if (event.type() == HeadlessRunEvent.Type.CONTROL) {
                            replies.put(event.data().path("requestId").asText(), event.data());
                            answered.countDown();
                        }
                    }));
        } finally { writer.close(); }

        JsonNode design = replies.get("a1");
        assertTrue(design.path("ok").asBoolean(), design.toString());
        assertEquals("Approved gate 'approved-design' for workflow 'gated-team'.", design.path("message").asText());
        assertEquals("approved-design", design.path("gate").asText());
        assertEquals("[\"approved-design\"]", design.path("approved").toString());
        assertFalse(replies.get("a2").path("ok").asBoolean());
        assertEquals("Workflow 'gated-team' has no gate 'shipped'. Its gates: implementation 'approved-design', "
                + "completion 'reviewed'.", replies.get("a2").path("message").asText());
        // With no gate named, the approval goes to the gate that blocks next.
        assertEquals("reviewed", replies.get("a3").path("gate").asText(), replies.get("a3").toString());
        assertEquals("[\"approved-design\",\"reviewed\"]", replies.get("a3").path("approved").toString());
        assertFalse(replies.get("a4").path("ok").asBoolean());
        assertEquals("Every gate of workflow 'gated-team' is already approved.", replies.get("a4").path("message").asText());
        // The run's team enforces the approvals at once, and the session records them for its next run.
        assertTrue(WorkflowSessionContext.current().enforcement().completionGateSatisfied());
        assertEquals(Set.of("approved-design", "reviewed"), WorkflowSessionContext.satisfiedGates("s-team"));
    }

    @Test void aRunWithoutATeamHasNoGateToApprove() throws Exception {
        var replies = new CopyOnWriteArrayList<JsonNode>();
        var answered = new CountDownLatch(1);
        try (var processes = new BackgroundProcessManager("web-controls-no-team", directory);
             var controls = new WebHarnessControls(bytes(approve("a1", "approved-design") + "\n"))) {
            controls.run(mock(AgenticChatLoop.class), processes, "s", "first", 4000, new AtomicBoolean(), prompt -> {
                assertTrue(answered.await(2, TimeUnit.SECONDS));
                return "done";
            }, (action, id) -> { throw new AssertionError("No process execution expected"); }, event -> {
                if (event.type() == HeadlessRunEvent.Type.CONTROL) { replies.add(event.data()); answered.countDown(); }
            });
        }
        assertEquals(1, replies.size());
        assertFalse(replies.get(0).path("ok").asBoolean());
        assertEquals("This session has no workflow team.", replies.get(0).path("message").asText());
        assertFalse(Files.exists(WorkflowSessionContext.sessionPath("s")));
    }

    /** A designer leads a worker; implementation waits for the design, completion for its review. */
    private static WorkflowTeam gatedTeam() {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                List.of("read", "plan", "delegate"), List.of("worker")));
        participants.put("worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                List.of("read", "edit-assigned-files", "validate"), List.of()));
        return new WorkflowTeam("gated-team", 1, "designer", participants, Map.of("implement", "worker"),
                new WorkflowTeam.Limits(2), new WorkflowTeam.Gates("approved-design", "reviewed"));
    }

    @Test void liveCommandsAnswerFromTheRunningHarness() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var permission = new PermissionService();
        permission.setAutoApproveAll(true);
        var context = new ToolContext("s", new AgentRegistry().getDefault(), permission, directory, new ToolRegistry(mapper));
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        var replies = new ConcurrentHashMap<String, JsonNode>();
        var answered = new CountDownLatch(5);
        var calls = new ArrayList<String>();
        var events = new ArrayList<HeadlessRunEvent>();
        try (var processes = new BackgroundProcessManager("web-controls-live", directory);
             var controls = new WebHarnessControls(pipe)) {
            var shared = processes.upsertShared("shared-1", "make build", "peer build", -1L, Instant.now(),
                    BackgroundProcessManager.ProcessState.RUNNING, null, null, null, Map.of("ownerAgent", "codex"));
            var tool = new ProcessManagementTool(processes);
            controls.setCommandResolver(raw -> { throw new AssertionError("answered by the harness, not resolved: " + raw); });
            controls.setInitialDisplay("what I typed");
            assertEquals("done", controls.run(mock(AgenticChatLoop.class), processes, "s", "DECORATED what I typed", 4000,
                    new AtomicBoolean(), prompt -> {
                        writer.write((String.join("\n", command("c1", "/processes"), command("c2", "/jobs"),
                                command("c3", "/process-kill shared-1"), command("c4", "/process-output missing"),
                                command("c5", "/model other")) + "\n").getBytes(StandardCharsets.UTF_8));
                        writer.flush();
                        assertTrue(answered.await(3, TimeUnit.SECONDS));
                        return "done";
                    }, (action, id) -> {
                        calls.add(action + ":" + id);
                        var args = mapper.createObjectNode().put("action", action);
                        if (id != null) args.put("process_id", id);
                        return tool.execute(args, context);
                    }, event -> {
                        events.add(event);
                        if (event.type() == HeadlessRunEvent.Type.CONTROL) {
                            replies.put(event.data().path("requestId").asText(), event.data());
                            answered.countDown();
                        }
                    }));

            String panel = replies.get("c1").path("message").asText();
            assertTrue(replies.get("c1").path("ok").asBoolean(), panel);
            assertTrue(panel.startsWith("Processes & Subagents") && panel.contains("[shared-1] peer build"), panel);
            assertFalse(panel.contains("\u001B"), "browser text carries no ANSI");
            String jobs = replies.get("c2").path("message").asText();
            assertTrue(jobs.contains("Active") && jobs.contains("what I typed") && !jobs.contains("DECORATED"), jobs);
            assertFalse(replies.get("c3").path("ok").asBoolean());
            assertTrue(replies.get("c3").path("message").asText().contains("only its owning session can stop it"));
            assertEquals(BackgroundProcessManager.ProcessState.RUNNING, shared.getState());
            assertEquals("Process not found: missing", replies.get("c4").path("message").asText());
            assertTrue(replies.get("c5").path("deferred").asBoolean());
            assertFalse(replies.get("c5").path("ok").asBoolean());
            // The panel passes the process policy; unknown ids never reach it.
            assertEquals(List.of("list:null", "kill:shared-1"), calls);

            JsonNode row = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.ACTIVITY)
                    .flatMap(e -> StreamSupport.stream(e.data().path("processes").spliterator(), false))
                    .filter(p -> p.path("id").asText().equals("shared-1")).findFirst().orElseThrow();
            assertEquals("shared", row.path("kind").asText());
            assertFalse(row.path("killable").asBoolean());
            assertEquals("codex", row.path("owner").asText());
            assertFalse(row.has("output"), "another session's output is read on request, not streamed");
        } finally { writer.close(); }
    }

    @Test void liveSkillQueuesItsExpansionButShowsWhatWasTyped() throws Exception {
        String expansion = "<skill name=\"review\">\nreview now\n</skill>";
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        var prompts = new CopyOnWriteArrayList<String>();
        var resolved = new ArrayList<String>();
        var replies = new ConcurrentHashMap<String, JsonNode>();
        var answered = new CountDownLatch(2);
        var events = new ArrayList<HeadlessRunEvent>();
        try (var processes = new BackgroundProcessManager("web-controls-skill", directory);
             var controls = new WebHarnessControls(pipe)) {
            controls.setCommandResolver(raw -> {
                resolved.add(raw);
                return new WebCommandResolver.Resolution(WebCommandResolver.Status.MODEL_INPUT, "", "", expansion);
            });
            controls.run(mock(AgenticChatLoop.class), processes, "s", "first", 4000, new AtomicBoolean(), prompt -> {
                prompts.add(prompt);
                if (prompt.equals("first")) {
                    writer.write((command("c1", "/review now") + "\n" + command("c2", "/jobs") + "\n").getBytes(StandardCharsets.UTF_8));
                    writer.flush();
                    assertTrue(answered.await(3, TimeUnit.SECONDS));
                }
                return "reply";
            }, (action, id) -> ToolResult.success("listed"), event -> {
                events.add(event);
                if (event.type() == HeadlessRunEvent.Type.CONTROL) {
                    replies.put(event.data().path("requestId").asText(), event.data());
                    answered.countDown();
                }
            });
            assertEquals(List.of("first", expansion), prompts);
            assertEquals(List.of("/review now"), resolved);
            assertTrue(replies.get("c1").path("ok").asBoolean());
            assertTrue(replies.get("c1").path("queued").asBoolean());
            String jobs = replies.get("c2").path("message").asText();
            assertTrue(jobs.contains("Queue (1 pending)") && jobs.contains("/review now") && !jobs.contains("<skill"), jobs);
            var starts = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.TURN_STARTED).toList();
            assertEquals("/review now", starts.get(1).data().path("text").asText());
        } finally { writer.close(); }
    }

    @Test void retainedFollowupKeepsRunAliveUntilCompletionIsDelivered() throws Exception {
        var runner = mock(ai.kompile.cli.main.chat.agent.SubagentRunner.class);
        var lifecycle = new AtomicReference<ai.kompile.cli.main.chat.agent.SubagentRunner.LifecycleListener>();
        var completion = new AtomicReference<BiConsumer<String, String>>();
        doAnswer(c -> { lifecycle.set(c.getArgument(0)); return null; }).when(runner).setLifecycleListener(any());
        doAnswer(c -> { completion.set(c.getArgument(0)); return null; }).when(runner).setAsyncCompletionListener(any());
        var childRunning = new AtomicBoolean();
        when(runner.hasPendingWork("child")).thenAnswer(c -> childRunning.get());
        when(runner.canSendMessage("child")).thenReturn(true);
        when(runner.sendMessage("child", "follow up")).thenAnswer(c -> { childRunning.set(true); return true; });
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        var accepted = new CountDownLatch(2);
        var calls = new ArrayList<String>();
        var events = new ArrayList<HeadlessRunEvent>();
        var loop = mock(AgenticChatLoop.class);
        try (var processes = new BackgroundProcessManager("child-controls", directory);
             var controls = new WebHarnessControls(pipe)) {
            String result = controls.run(loop, processes, "s", "initial", 4000, new AtomicBoolean(), prompt -> {
                calls.add(prompt);
                if (prompt.equals("initial")) {
                    lifecycle.get().onSubagentStart("child", "coder", "retained child");
                    lifecycle.get().onSubagentStatus("child", "completed");
                    lifecycle.get().onSubagentEnd("child");
                    String valid = "{\"version\":1,\"requestId\":\"r2\",\"action\":\"subagent_input\",\"targetId\":\"child\",\"text\":\"follow up\"}\n";
                    writer.write((valid.replace("r2", "r1").replace("child", "foreign") + valid).getBytes(StandardCharsets.UTF_8));
                    writer.flush();
                    assertTrue(accepted.await(2, TimeUnit.SECONDS));
                    return "parent released";
                }
                assertTrue(prompt.contains("child completion evidence"));
                return "reviewed child";
            }, (a, id) -> ToolResult.error("unused"), e -> {
                events.add(e);
                if (e.type() == HeadlessRunEvent.Type.CONTROL) accepted.countDown();
                if (e.type() == HeadlessRunEvent.Type.TURN_COMPLETE && e.data().path("text").asText().equals("parent released")) {
                    completion.get().accept("child", "child completion evidence");
                    childRunning.set(false);
                }
            }, runner);
            assertEquals("reviewed child", result);
            assertEquals(2, calls.size());
            verify(runner, never()).sendMessage(eq("foreign"), anyString());
            verify(runner).sendMessage("child", "follow up");
            verify(loop, never()).cancelActiveTurn();
            assertEquals(1, events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.CONTROL && !e.data().path("ok").asBoolean()).count());
            assertTrue(events.get(events.size() - 1).data().path("subagents").get(0).path("canSend").asBoolean());
            verify(runner).setLifecycleListener(null);
            verify(runner).setAsyncCompletionListener(null);
        } finally { writer.close(); }
    }

    @Test void browserEventsDropTerminalStylingWhileTheModelReadsOutputVerbatim() throws Exception {
        var runner = mock(SubagentRunner.class);
        var lifecycle = new AtomicReference<SubagentRunner.LifecycleListener>();
        doAnswer(c -> { lifecycle.set(c.getArgument(0)); return null; }).when(runner).setLifecycleListener(any());
        var pipe = new PipedInputStream();
        var writer = new PipedOutputStream(pipe);
        var prompts = new CopyOnWriteArrayList<String>();
        var replies = new ConcurrentHashMap<String, JsonNode>();
        var answered = new CountDownLatch(2);
        var events = new ArrayList<HeadlessRunEvent>();
        try (var processes = new BackgroundProcessManager("web-controls-ansi", directory);
             var controls = new WebHarnessControls(pipe)) {
            var styled = processes.launchMonitored("printf '\\033[32mprocess-evidence\\033[0m\\n'", "styled", directory, "");
            String requests = frame("process_output").replace("}", ",\"targetId\":\"" + styled.getId() + "\"}")
                    + "\n" + command("c2", "/process-output " + styled.getId()) + "\n";
            controls.run(mock(AgenticChatLoop.class), processes, "s", "first", 4000, new AtomicBoolean(), prompt -> {
                prompts.add(prompt);
                if (prompt.equals("first")) {
                    lifecycle.get().onSubagentStart("child", "coder", "styled child");
                    lifecycle.get().onSubagentOutput("child", "\033[1mchild-evidence\033[0m\n");
                } else {
                    // The wakeup turn starts once the process is terminal, so its output is complete.
                    writer.write(requests.getBytes(StandardCharsets.UTF_8));
                    writer.flush();
                    assertTrue(answered.await(3, TimeUnit.SECONDS));
                }
                return "reply";
            }, (action, id) -> ToolResult.success(processes.readOutput(id, 50)), event -> {
                events.add(event);
                if (event.type() == HeadlessRunEvent.Type.CONTROL) {
                    replies.put(event.data().path("requestId").asText(), event.data());
                    answered.countDown();
                }
            }, runner);

            assertEquals(2, prompts.size());
            assertTrue(prompts.get(1).contains("\033[32mprocess-evidence"), "the model reads output verbatim, as in the CLI");
            for (String id : List.of("r1", "c2")) {
                JsonNode reply = replies.get(id);
                assertTrue(reply.path("ok").asBoolean() && reply.path("message").asText().contains("process-evidence"), reply.toString());
            }
            assertEquals(replies.get("r1").path("message").asText(), replies.get("r1").path("output").asText());
            var system = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.TURN_STARTED
                    && e.data().path("source").asText().equals("system")).findFirst().orElseThrow();
            assertTrue(system.data().path("text").asText().contains("process-evidence"));
            var last = events.get(events.size() - 1).data();
            assertTrue(last.path("processes").get(0).path("output").asText().contains("process-evidence"), last.toString());
            assertTrue(last.path("subagents").get(0).path("output").asText().contains("child-evidence"), last.toString());
            events.forEach(e -> assertNoEscape(e.data(), e.type().name()));
        } finally { writer.close(); }
    }

    private static void assertNoEscape(JsonNode node, String where) {
        if (node.isTextual()) assertFalse(node.asText().contains("\033"), where + " carries terminal styling: " + node.asText());
        node.forEach(child -> assertNoEscape(child, where));
    }

    @Test void nonterminalWireEventsCarryDataWithoutResultVocabulary() throws Exception {
        var mapper = JsonUtils.standardMapper();
        for (var type : List.of(HeadlessRunEvent.Type.CONTROL, HeadlessRunEvent.Type.ACTIVITY, HeadlessRunEvent.Type.TURN_STARTED, HeadlessRunEvent.Type.TURN_COMPLETE)) {
            var event = new HeadlessRunEvent(7, type, "s", "", "", "", "", true, 0, 0, "", Map.of(), mapper.createObjectNode().put("text", "done"));
            var json = mapper.readTree(ExecJsonEvents.event(mapper, event));
            assertEquals(type.name().toLowerCase(Locale.ROOT), json.path("type").asText());
            assertEquals("done", json.path("data").path("text").asText());
            assertEquals(7, json.path("seq").asLong());
            assertFalse(json.has("exit"));
        }
    }
}
