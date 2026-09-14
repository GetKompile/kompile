package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class WebHarnessControlsTest {
    @TempDir Path directory;
    private static ByteArrayInputStream bytes(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
    private static String frame(String action) { return "{\"version\":1,\"requestId\":\"r1\",\"action\":\"" + action + "\"}"; }

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
                if (event.type() == HeadlessRunEvent.Type.CONTROL && event.data().path("ok").asBoolean()) acknowledged.countDown();
                if (event.type() == HeadlessRunEvent.Type.TURN_COMPLETE && event.data().path("text").asText().equals("parent-released"))
                    completion.get().accept(ToolResult.success("completed-output"));
            });
            assertEquals("final-after-wakeup", result);
            assertEquals(2, calls.size());
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
