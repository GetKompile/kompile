/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;

import ai.kompile.cli.main.chat.config.AttachmentInput;
import ai.kompile.cli.main.chat.config.ConnectivityEvent;
import ai.kompile.cli.main.chat.config.FailureKind;
import ai.kompile.cli.main.chat.config.ProviderActivityListener;
import ai.kompile.cli.main.chat.config.ReplayedToolCallInput;
import ai.kompile.cli.main.chat.config.TokenCountResult;
import ai.kompile.cli.main.chat.config.ToolCallOutput;
import ai.kompile.cli.main.chat.config.ToolCallResultInput;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ExitPlanModeTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External behavior packet: compile against the pre-refactor target/classes first,
 * then run the identical assertions after recompiling production. No driver types,
 * real provider requests, process tools, or implementation-specific state shapes.
 * Ledger/abort inspection follows the existing history/overflow test reflection pattern.
 */
@TemporaryUserHome
@Timeout(15)
class AgenticChatLoopDriverParityTest {
    @TempDir Path workingDirectory;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void successiveTurnsHaveFreshCorrectionsObjectivesResponsesAndPendingResults() {
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool("probe", "read", context -> ToolResult.success("observed")));
        ScriptedClient client = client(
                calls(call("probe-1", "probe")), text("draft one"), text("fixed one"),
                text("draft two"), text("fixed two"));
        AgenticChatLoop loop = loop(tools, client, new PermissionService());
        RecordingEvaluator evaluator = new RecordingEvaluator();
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("revise drafts", 1, false), 1);
        String session = session();

        String first = loop.chat("first objective", session, "coder", "default", false);
        String second = loop.chat("second objective", session, "coder", "default", false);

        assertEquals(List.of(1, 2, 1, 2), evaluator.attempts,
                "a correction spent in the first chat must not exhaust the next chat");
        assertEquals(List.of("first objective", "first objective",
                "second objective", "second objective"), evaluator.objectives);
        assertTrue(first.contains("fixed one"));
        assertTrue(second.contains("fixed two"));
        assertFalse(second.contains("draft one"));
        assertFalse(second.contains("fixed one"));
        assertEquals(5, client.requests.size());
        assertEquals(List.of("probe-1"), ids(client.requests.get(1).results()));
        assertTrue(client.requests.get(2).results().isEmpty(), "correction replaces pending results");
        assertEquals("second objective", client.requests.get(3).message());
        assertTrue(client.requests.get(3).results().isEmpty(), "new turn must not resend old tool results");
        assertTrue(client.requests.get(4).results().isEmpty());
    }

    @Test
    void batchExecutionOrderAndSuccessUnknownDeniedAndThrownResultIdsReachFollowup() {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger deniedBody = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool("first", "read", context -> {
            trace.add("tool:first");
            assertEquals(workingDirectory, context.getWorkingDirectory());
            assertFalse(context.isAborted());
            return ToolResult.success("first output");
        }));
        tools.register(tool("denied", "parity-denied", context -> {
            deniedBody.incrementAndGet();
            return ToolResult.success("must not execute");
        }));
        tools.register(tool("throws", "read", context -> {
            trace.add("tool:throws");
            throw new ToolExecutionException("scripted failure");
        }));
        tools.register(tool("last", "read", context -> {
            trace.add("tool:last");
            return ToolResult.success("last output");
        }));
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("parity-denied", PermissionService.PermissionLevel.DENY);
        ScriptedClient client = new ScriptedClient(trace,
                calls(call("id-first", "first"), call("id-missing", "unregistered"),
                        call("id-denied", "denied"), call("id-throws", "throws"), call("id-last", "last")),
                text("batch complete"));
        AgenticChatLoop loop = loop(tools, client, permissions);

        assertEquals("batch complete", loop.chat("run the batch", session(), "coder", "default", false));

        assertEquals(List.of("model:1", "tool:first", "tool:throws", "tool:last", "model:2"), trace);
        assertEquals(0, deniedBody.get());
        assertEquals(2, client.requests.size());
        Request followup = client.requests.get(1);
        assertNull(followup.message(), "ordinary tool followup has no invented user prompt");
        assertEquals(List.of("id-first", "id-missing", "id-denied", "id-throws", "id-last"),
                ids(followup.results()));
        assertEquals(List.of("first", "unregistered", "denied", "throws", "last"),
                followup.results().stream().map(result -> result.name).toList());
        assertEquals(List.of(false, true, true, true, false),
                followup.results().stream().map(result -> result.isError).toList());
        assertEquals("first output", followup.results().get(0).output);
        assertEquals("Unknown tool: unregistered", followup.results().get(1).output);
        assertTrue(followup.results().get(2).output.contains("Permission denied: parity-denied"));
        assertTrue(followup.results().get(3).output.contains("scripted failure"));
        assertEquals("last output", followup.results().get(4).output);
    }

    @Test
    void productApprovalPausesBeforeQueueClaimAndDeniesAllLaterBatchTools() throws Exception {
        AtomicBoolean approvalReturned = new AtomicBoolean();
        AtomicInteger laterRuns = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool("approval", "read", context -> {
            approvalReturned.set(true);
            return new ToolResult("approval required", "pending content decision",
                    Map.of("productApprovalRequired", true), true);
        }));
        tools.register(tool("later", "read", context -> {
            laterRuns.incrementAndGet();
            return ToolResult.success("must not execute");
        }));
        ScriptedClient client = client(calls(call("approval-1", "approval"),
                call("later-1", "later"), call("later-2", "later")));
        AgenticChatLoop loop = loop(tools, client, new PermissionService());
        Deque<String> queue = new ArrayDeque<>(List.of("genuine next request"));
        AtomicInteger polls = new AtomicInteger();
        loop.setQueuedMessageSupplier(() -> {
            polls.incrementAndGet();
            return approvalReturned.get() ? AgenticChatLoop.QueuedInput.immediate(queue.removeFirst()) : null;
        });

        String output = loop.chat("prepare the content", session(), "coder", "default", false);

        assertTrue(output.contains("Waiting for human content approval"));
        assertEquals(1, client.requests.size(), "approval must not cause an automatic provider followup");
        assertEquals(0, laterRuns.get());
        assertEquals(1, polls.get(), "only the safe boundary before the first tool may poll");
        assertEquals(List.of("genuine next request"), List.copyOf(queue));
        var entries = ledger(loop).snapshot().activeEntries();
        assertEquals(List.of("approval-1", "later-1", "later-2"), entries.stream()
                .filter(entry -> entry.type == CompactionService.EntryType.TOOL_CALL)
                .map(entry -> entry.toolCallId).toList());
        var results = entries.stream()
                .filter(entry -> entry.type == CompactionService.EntryType.TOOL_RESULT).toList();
        assertEquals(List.of("approval-1", "later-1", "later-2"),
                results.stream().map(entry -> entry.toolCallId).toList());
        assertTrue(results.get(0).content.contains("pending content decision"));
        assertTrue(results.get(1).content.contains("waiting for human content approval"));
        assertTrue(results.get(2).content.contains("waiting for human content approval"));
    }

    @Test
    void approvedPlanExitDoesNotClaimQueueOrRunLaterPlanToolsAndExecutionStartsFresh() {
        AtomicInteger reads = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool("read", "read", context -> {
            reads.incrementAndGet();
            return ToolResult.success("execution evidence");
        }));
        ToolCallOutput exit = call("exit-1", "exit_plan_mode");
        exit.arguments = mapper.createObjectNode().put("plan_summary", "inspect once");
        ScriptedClient client = client(calls(exit, call("skipped-plan-read", "read")),
                calls(call("execution-read", "read")), text("execution complete"));
        AgenticChatLoop loop = loop(tools, client, new PermissionService());
        loop.setPlanningMode(true);
        ExitPlanModeTool exitTool = (ExitPlanModeTool) tools.get("exit_plan_mode");
        Deque<String> queue = new ArrayDeque<>(List.of("next owner request"));
        List<Integer> pollRequestNumbers = new ArrayList<>();
        loop.setQueuedMessageSupplier(() -> {
            pollRequestNumbers.add(client.requests.size());
            // A bad post-exit poll would steal this input. Execution has its own owner.
            return client.requests.size() == 1 && exitTool.isPlanApproved()
                    ? AgenticChatLoop.QueuedInput.immediate(queue.removeFirst()) : null;
        });
        try {
            String output = loop.chat("make a plan", session(), "coder", "default", false);

            assertTrue(output.contains("--- Execution ---"));
            assertTrue(output.contains("execution complete"));
            assertEquals(1, reads.get(), "the read after exit must run only in execution, never in planning");
            assertEquals(List.of(1, 2, 2, 3), pollRequestNumbers,
                    "exit stops before the next plan-tool and post-batch queue boundaries");
            assertEquals(List.of("next owner request"), List.copyOf(queue));
            assertEquals(3, client.requests.size());
            assertTrue(client.requests.get(1).message().startsWith("Execute the plan you just created."));
            assertTrue(client.requests.get(1).results().isEmpty(), "execution is not a planning tool followup");
            assertEquals(List.of("execution-read"), ids(client.requests.get(2).results()));
            assertFalse(exitTool.isPlanApproved(), "the planning signal is consumed before execution");
            // Do not invent a result for skipped-plan-read here: old-loop replay repair
            // owns dangling envelopes; this test observes dispatch and phase boundaries.
        } finally {
            loop.setPlanningMode(false);
            loop.setQueuedMessageSupplier(null);
        }
    }

    @Test
    void rejectedQueueAcceptanceRestoresExactlyOnceWithoutDispatchOrDurableUserEntry() throws Exception {
        ScriptedClient client = client(text("before claim"));
        AgenticChatLoop loop = loop(new ToolRegistry(mapper), client, new PermissionService());
        ClaimedPrompt prompt = new ClaimedPrompt();
        loop.setQueuedMessageSupplier(() -> prompt.claim(() -> false));

        assertEquals("before claim", loop.chat("original", session(), "coder", "default", false));

        assertEquals(1, prompt.claims.get());
        assertEquals(1, prompt.accepts.get());
        assertEquals(1, prompt.restores.get());
        assertEquals(List.of(ClaimedPrompt.CONTENT), List.copyOf(prompt.queue));
        assertEquals(1, client.requests.size());
        assertFalse(userMessages(loop).contains(ClaimedPrompt.CONTENT));
        assertAbortReleased(loop);
    }

    @Test
    void cancellationAfterQueueClaimRestoresExactlyOnceBeforeAcceptanceOrNextRequest() throws Exception {
        ScriptedClient client = client(text("before cancel"));
        AgenticChatLoop loop = loop(new ToolRegistry(mapper), client, new PermissionService());
        AtomicBoolean cancelled = new AtomicBoolean();
        loop.setCancelSignal(cancelled);
        ClaimedPrompt prompt = new ClaimedPrompt();
        loop.setQueuedMessageSupplier(() -> {
            AgenticChatLoop.QueuedInput claim = prompt.claim(() -> true);
            cancelled.set(true);
            return claim;
        });
        try {
            loop.chat("original", session(), "coder", "default", false);

            assertEquals(1, prompt.claims.get());
            assertEquals(0, prompt.accepts.get(), "cancellation wins before the next dispatch boundary");
            assertEquals(1, prompt.restores.get());
            assertEquals(List.of(ClaimedPrompt.CONTENT), List.copyOf(prompt.queue));
            assertEquals(1, client.requests.size());
            assertFalse(userMessages(loop).contains(ClaimedPrompt.CONTENT));
            assertAbortReleased(loop);
        } finally {
            cancelled.set(false);
            loop.setCancelSignal(null);
            loop.setQueuedMessageSupplier(null);
        }
    }

    @Test
    void schemaObservationFailureWithClaimInFlightRestoresQueueAndReleasesAbortState() throws Exception {
        AtomicBoolean failSchema = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("schema observation failed");
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(new CliTool() {
            public String id() { return "read"; }
            public String description() { return "schema observation fixture"; }
            public String permissionKey() { return "read"; }
            public JsonNode parameterSchema() {
                if (failSchema.get()) throw failure;
                return mapper.createObjectNode().put("type", "object");
            }
            public ToolResult execute(JsonNode params, ToolContext context) {
                throw new AssertionError("schema fixture is never dispatched");
            }
        });
        ScriptedClient client = client(text("before schema refresh"));
        AgenticChatLoop loop = loop(tools, client, new PermissionService());
        ClaimedPrompt prompt = new ClaimedPrompt();
        loop.setQueuedMessageSupplier(() -> {
            AgenticChatLoop.QueuedInput claim = prompt.claim(() -> true);
            // Tool definitions are observed afresh before accepting the pending claim.
            failSchema.set(true);
            return claim;
        });
        try {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> loop.chat("original", session(), "coder", "default", false)));

            assertEquals(1, prompt.claims.get());
            assertEquals(0, prompt.accepts.get());
            assertEquals(1, prompt.restores.get());
            assertEquals(List.of(ClaimedPrompt.CONTENT), List.copyOf(prompt.queue));
            assertEquals(1, client.requests.size());
            assertFalse(userMessages(loop).contains(ClaimedPrompt.CONTENT));
            assertAbortReleased(loop);
        } finally {
            failSchema.set(false);
            loop.setQueuedMessageSupplier(null);
        }
    }

    @Test
    void acceptedSteeringChangesReviewObjectiveButInternalCorrectionDoesNot() throws Exception {
        AtomicInteger firstRuns = new AtomicInteger();
        AtomicInteger laterRuns = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool("first", "read", context -> {
            firstRuns.incrementAndGet();
            return ToolResult.success("first finished");
        }));
        tools.register(tool("later", "read", context -> {
            laterRuns.incrementAndGet();
            return ToolResult.success("must not execute");
        }));
        ScriptedClient client = client(calls(call("first-1", "first"), call("later-1", "later")),
                text("draft for steering"), text("fixed for steering"));
        AgenticChatLoop loop = loop(tools, client, new PermissionService());
        RecordingEvaluator evaluator = new RecordingEvaluator();
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("follow accepted user steering", 1, false), 1);
        ClaimedPrompt prompt = new ClaimedPrompt();
        loop.setQueuedMessageSupplier(() -> firstRuns.get() == 1 && !prompt.queue.isEmpty()
                ? prompt.claim(() -> true) : null);

        String output = loop.chat("original objective", session(), "coder", "default", false);

        assertTrue(output.contains("fixed for steering"));
        assertEquals(1, firstRuns.get());
        assertEquals(0, laterRuns.get());
        assertEquals(1, prompt.claims.get());
        assertEquals(1, prompt.accepts.get());
        assertEquals(0, prompt.restores.get(), "accepted input is owned by the provider, not restored");
        assertTrue(prompt.queue.isEmpty());
        assertEquals(List.of(ClaimedPrompt.CONTENT, ClaimedPrompt.CONTENT), evaluator.objectives);
        assertEquals(List.of(1, 2), evaluator.attempts);
        assertEquals(ClaimedPrompt.CONTENT, client.requests.get(1).message());
        assertEquals(List.of("first-1", "later-1"), ids(client.requests.get(1).results()));
        assertFalse(client.requests.get(1).results().get(0).isError);
        assertTrue(client.requests.get(1).results().get(1).isError);
        assertTrue(client.requests.get(1).results().get(1).output.contains("superseded"));
        assertTrue(client.requests.get(2).message().contains("produce a compliant response"));
        assertTrue(client.requests.get(2).results().isEmpty());
        assertEquals(1, userMessages(loop).stream().filter(ClaimedPrompt.CONTENT::equals).count(),
                "only acceptance records the queued prompt as a durable user input");
        assertAbortReleased(loop);
    }

    private AgenticChatLoop loop(ToolRegistry tools, ScriptedClient client, PermissionService permissions) {
        return new AgenticChatLoop(null, mapper, tools, permissions, new AgentRegistry(),
                workingDirectory, client, null, new SkillRegistry());
    }

    private CliTool tool(String name, String permission, ToolBody body) {
        return new CliTool() {
            public String id() { return name; }
            public String description() { return "parity fixture " + name; }
            public JsonNode parameterSchema() { return mapper.createObjectNode().put("type", "object"); }
            public String permissionKey() { return permission; }
            public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
                context.checkPermission(permission, "parity fixture " + name);
                return body.execute(context);
            }
        };
    }

    @FunctionalInterface
    private interface ToolBody {
        ToolResult execute(ToolContext context) throws ToolExecutionException;
    }

    private ToolCallOutput call(String id, String name) {
        ToolCallOutput call = new ToolCallOutput();
        call.id = id;
        call.name = name;
        call.arguments = mapper.createObjectNode();
        return call;
    }

    private static DirectLlmClient.StreamResult calls(ToolCallOutput... calls) {
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        Collections.addAll(result.toolCalls, calls);
        return result;
    }

    private static DirectLlmClient.StreamResult text(String text) {
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        result.text = text;
        return result;
    }

    private ScriptedClient client(DirectLlmClient.StreamResult... replies) {
        return new ScriptedClient(new ArrayList<>(), replies);
    }

    private static String session() { return "driver-parity-" + UUID.randomUUID(); }

    private static List<String> ids(List<ToolCallResultInput> results) {
        return results.stream().map(result -> result.callId).toList();
    }

    // Same narrow loop-ledger inspection used by HistoryReplay and ProductApprovalPause.
    private static ConversationLedger ledger(AgenticChatLoop loop) throws Exception {
        Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
        field.setAccessible(true);
        return (ConversationLedger) field.get(loop);
    }

    private static List<String> userMessages(AgenticChatLoop loop) throws Exception {
        return ledger(loop).snapshot().activeEntries().stream()
                .filter(entry -> entry.type == CompactionService.EntryType.USER)
                .map(entry -> entry.content).toList();
    }

    private static void assertAbortReleased(AgenticChatLoop loop) throws Exception {
        Field field = AgenticChatLoop.class.getDeclaredField("activeToolAbortSignal");
        field.setAccessible(true);
        assertNull(((AtomicReference<?>) field.get(loop)).get(), "no completed owner retains an active abort signal");
    }

    private static final class ClaimedPrompt {
        static final String CONTENT = "report findings only; do not edit";
        final Deque<String> queue = new ArrayDeque<>(List.of(CONTENT));
        final AtomicInteger claims = new AtomicInteger();
        final AtomicInteger accepts = new AtomicInteger();
        final AtomicInteger restores = new AtomicInteger();

        AgenticChatLoop.QueuedInput claim(java.util.function.BooleanSupplier acceptance) {
            claims.incrementAndGet();
            String content = queue.removeFirst();
            return new AgenticChatLoop.QueuedInput(content, () -> {
                accepts.incrementAndGet();
                return acceptance.getAsBoolean();
            }, () -> {
                restores.incrementAndGet();
                queue.addFirst(content);
            });
        }
    }

    private static final class RecordingEvaluator implements EnforcerEvaluator {
        final List<String> objectives = new ArrayList<>();
        final List<Integer> attempts = new ArrayList<>();

        public EnforcerDecision evaluate(String objective, String output, EnforcerPolicy policy, int attempt) {
            objectives.add(objective);
            attempts.add(attempt);
            return output.contains("fixed") ? EnforcerDecision.pass("revised")
                    : EnforcerDecision.stop(List.of("draft must be revised"), "scripted draft rejection");
        }
        public boolean isAvailable() { return true; }
        public String describe() { return "parity scripted evaluator"; }
    }

    private record Request(String message, List<ToolCallResultInput> results) { }

    private final class ScriptedClient extends DirectLlmClient {
        final List<Request> requests = new ArrayList<>();
        private final List<StreamResult> replies;
        private final List<String> trace;

        ScriptedClient(List<String> trace, StreamResult... replies) {
            super(chatConfig(), mapper);
            this.trace = trace;
            this.replies = List.of(replies);
        }

        @Override
        public StreamResult streamChat(String user, String system, ArrayNode definitions,
                                       List<ToolCallResultInput> results, String model,
                                       List<AttachmentInput> attachments) {
            List<ToolCallResultInput> copy = results == null ? List.of() : results.stream()
                    .map(result -> new ToolCallResultInput(result.callId, result.name, result.output, result.isError))
                    .toList();
            requests.add(new Request(user, copy));
            trace.add("model:" + requests.size());
            if (requests.size() > replies.size()) {
                throw new AssertionError("Unexpected provider request " + requests.size() + ": " + user);
            }
            return replies.get(requests.size() - 1);
        }
    }

    private static ChatConfig chatConfig() {
        ChatConfig config = new ChatConfig("custom", null, "driver-parity-test", "http://unused.invalid");
        config.setContextWindowTokens(128_000); // No model discovery or context probe is needed.
        config.setDefaultMemory(false);
        return config;
    }
}
