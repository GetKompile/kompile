package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticChatLoopQueueSteeringTest {

    @TempDir
    Path workingDirectory;

    @Test
    void queuedMessageIsSentBetweenToolsAndSupersedesUnstartedCalls() {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(objectMapper);
        AtomicInteger firstToolRuns = new AtomicInteger();
        AtomicInteger secondToolRuns = new AtomicInteger();
        tools.register(countingTool("first_tool", firstToolRuns, objectMapper));
        tools.register(countingTool("second_tool", secondToolRuns, objectMapper));

        ScriptedDirectClient client = new ScriptedDirectClient(objectMapper);
        AgentRegistry agents = new AgentRegistry();
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), agents,
                workingDirectory, client, null);

        AtomicInteger boundaryPolls = new AtomicInteger();
        loop.setQueuedMessageSupplier(() -> boundaryPolls.incrementAndGet() == 2
                ? AgenticChatLoop.QueuedInput.immediate("change direction now") : null);

        String response = loop.chat(
                "start the work", "queue-steering-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(1, firstToolRuns.get(), "the first tool should finish before steering");
        assertEquals(0, secondToolRuns.get(), "queued guidance should supersede the next tool");
        assertEquals(List.of("start the work", "change direction now"), client.messages);
        assertEquals(2, client.secondRequestToolResults.size(),
                "every provider tool-call ID must receive a result");
        assertFalse(client.secondRequestToolResults.get(0).isError);
        assertTrue(client.secondRequestToolResults.get(1).isError);
        assertTrue(client.secondRequestToolResults.get(1).output.contains("superseded"));
        assertEquals("steered response", response);
    }

    @Test
    void interruptAtToolBoundaryDoesNotLoseClaimedQueuedMessage() {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(objectMapper);
        tools.register(countingTool("first_tool", new AtomicInteger(), objectMapper));
        tools.register(countingTool("second_tool", new AtomicInteger(), objectMapper));

        ScriptedDirectClient client = new ScriptedDirectClient(objectMapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        AtomicBoolean cancelled = new AtomicBoolean();
        loop.setCancelSignal(cancelled);
        Deque<String> queued = new ArrayDeque<>(List.of("run this next"));
        AtomicInteger boundaryPolls = new AtomicInteger();
        loop.setQueuedMessageSupplier(() -> {
            if (boundaryPolls.incrementAndGet() != 2) return null;
            String claimed = queued.pollFirst();
            cancelled.set(true);
            return new AgenticChatLoop.QueuedInput(
                    claimed, () -> true, () -> queued.addFirst(claimed));
        });

        loop.chat("start the work", "queue-interrupt-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(List.of("run this next"), List.copyOf(queued),
                "an interrupt after claim but before provider dispatch must restore the queued prompt");
        assertEquals(List.of("start the work"), client.messages,
                "the restored prompt must be left for the next owner, not sent by the cancelled one");
    }

    @Test
    void backgroundedTurnRoutesStreamingOutputToRetainedSink() {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        ScriptedDirectClient client = ScriptedDirectClient.finalResponseOnly(objectMapper);
        AgentRegistry agents = new AgentRegistry();
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), agents, workingDirectory, client, null);
        StringBuilder retained = new StringBuilder();

        loop.backgroundActiveTurn(retained::append);
        String response = loop.chat(
                "run in background", "background-output-" + UUID.randomUUID(),
                "coder", "default", false);

        assertTrue(loop.isOutputBackgrounded());
        assertEquals("background response", response);
        assertTrue(retained.toString().contains("background response"));
        loop.clearBackgroundOutput();
        assertFalse(loop.isOutputBackgrounded());
    }

    @Test
    void onlyBlockingTaskToolPhaseIsBackgroundable() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        ToolRegistry tools = new ToolRegistry(objectMapper);
        tools.register(new CliTool() {
            @Override
            public String id() { return "task"; }

            @Override
            public String description() { return "blocking subagent fixture"; }

            @Override
            public JsonNode parameterSchema() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() { return "read"; }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                taskStarted.countDown();
                try {
                    releaseTask.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
                return ToolResult.success("subagent complete");
            }
        });

        AtomicInteger requestCount = new AtomicInteger();
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("custom", null, "task-phase-test", "http://unused.invalid"),
                objectMapper) {
            @Override
            public StreamResult streamChat(
                    String userMessage,
                    String systemPrompt,
                    ArrayNode toolDefs,
                    List<ToolCallResultInput> toolResults,
                    String modelOverride,
                    List<AttachmentInput> attachments) {
                StreamResult result = new StreamResult();
                if (requestCount.getAndIncrement() == 0) {
                    ToolCallOutput call = new ToolCallOutput();
                    call.id = "call-task";
                    call.name = "task";
                    call.arguments = objectMapper.createObjectNode();
                    result.toolCalls.add(call);
                } else {
                    result.text = "done";
                }
                return result;
            }
        };
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        AtomicInteger eligibilityChanges = new AtomicInteger();
        loop.setBackgroundEligibilityListener(eligibilityChanges::incrementAndGet);

        assertFalse(loop.isBlockingSubagentInvocationActive(),
                "model thinking is not backgroundable");
        CompletableFuture<String> response = CompletableFuture.supplyAsync(() -> loop.chat(
                "delegate work", "task-phase-" + UUID.randomUUID(),
                "coder", "default", false));
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
            assertTrue(loop.isBlockingSubagentInvocationActive(),
                    "TaskTool must publish the Ctrl+B-eligible phase");
        } finally {
            releaseTask.countDown();
        }
        assertEquals("done", response.get(5, TimeUnit.SECONDS));
        assertFalse(loop.isBlockingSubagentInvocationActive(),
                "eligibility must clear when TaskTool returns");
        assertEquals(2, eligibilityChanges.get(),
                "the UI must be notified on both entry and exit");
    }

    @Test
    void remindersAreAppliedAtEachUserPromptBoundary() {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        ScriptedDirectClient client = ScriptedDirectClient.finalResponseOnly(objectMapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), new AgentRegistry(), workingDirectory, client, null);
        loop.setReminderManager(ReminderManager.inMemory(
                List.of("Keep project context"), List.of("Run focused tests")));

        loop.chat("implement the feature", "reminder-boundary-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(1, client.messages.size());
        String outbound = client.messages.get(0);
        assertTrue(outbound.startsWith("<kompile_reminders>"));
        assertTrue(outbound.indexOf("[project] Keep project context")
                < outbound.indexOf("[session] Run focused tests"));
        assertTrue(outbound.endsWith("implement the feature"));
    }

    @Test
    void reminderIntervalSkipsUnduePromptsWithinTheLoop() {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        ReminderManager reminders = ReminderManager.inMemory(
                List.of("Stay on plan"), List.of());
        reminders.handleCommand(ReminderManager.Scope.PROJECT, "interval 2");

        // Fresh loop per turn: the send boundary ticks the shared manager, so the
        // sequence 1..3 shows inject / skip / inject across loop instances.
        List<String> outbound = new ArrayList<>();
        for (String task : List.of("first task", "second task", "third task")) {
            ScriptedDirectClient client = ScriptedDirectClient.finalResponseOnly(objectMapper);
            AgenticChatLoop loop = new AgenticChatLoop(
                    null, objectMapper, new ToolRegistry(objectMapper),
                    new PermissionService(), new AgentRegistry(), workingDirectory, client, null);
            loop.setReminderManager(reminders);
            loop.chat(task, "reminder-interval-" + UUID.randomUUID(),
                    "coder", "default", false);
            assertEquals(1, client.messages.size());
            outbound.add(client.messages.get(0));
        }

        assertTrue(outbound.get(0).startsWith("<kompile_reminders>"));
        assertEquals("second task", outbound.get(1));
        assertTrue(outbound.get(2).startsWith("<kompile_reminders>"));
    }

    @Test
    void toolsStartedAfterBackgroundingNeverCreateForegroundTranscriptBlocks() {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(objectMapper);
        tools.register(countingTool("first_tool", new AtomicInteger(), objectMapper));
        tools.register(countingTool("second_tool", new AtomicInteger(), objectMapper));
        ScriptedDirectClient client = new ScriptedDirectClient(objectMapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        StringBuilder retained = new StringBuilder();
        var foregroundBlocks = new ConcurrentHashMap<String, String>();

        ChatCompleter.setTranscriptBlockOutput((key, content) -> {
            foregroundBlocks.put(key, content);
            return true;
        });
        try {
            loop.backgroundActiveTurn(retained::append);
            loop.chat("run tools in background", "background-tools-" + UUID.randomUUID(),
                    "coder", "default", false);

            assertTrue(foregroundBlocks.isEmpty(),
                    "a detached turn must not briefly publish new tool blocks to the main transcript");
            assertTrue(retained.toString().contains("First Tool"));
            assertTrue(retained.toString().contains("Second Tool"));
        } finally {
            loop.clearBackgroundOutput();
            ChatCompleter.setTranscriptBlockOutput(null);
        }
    }

    private static CliTool countingTool(
            String name, AtomicInteger counter, ObjectMapper objectMapper) {
        return new CliTool() {
            @Override
            public String id() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public JsonNode parameterSchema() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() {
                return "read";
            }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                counter.incrementAndGet();
                return ToolResult.success(name + " complete");
            }
        };
    }

    private static final class ScriptedDirectClient extends DirectLlmClient {
        private final ObjectMapper objectMapper;
        private final boolean finalOnly;
        private final List<String> messages = new ArrayList<>();
        private List<ToolCallResultInput> secondRequestToolResults = List.of();

        private ScriptedDirectClient(ObjectMapper objectMapper) {
            this(objectMapper, false);
        }

        private ScriptedDirectClient(ObjectMapper objectMapper, boolean finalOnly) {
            super(new ChatConfig("custom", null, "queue-steering-test", "http://unused.invalid"),
                    objectMapper);
            this.objectMapper = objectMapper;
            this.finalOnly = finalOnly;
        }

        static ScriptedDirectClient finalResponseOnly(ObjectMapper objectMapper) {
            return new ScriptedDirectClient(objectMapper, true);
        }

        @Override
        public StreamResult streamChat(
                String userMessage,
                String systemPrompt,
                ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults,
                String modelOverride,
                List<AttachmentInput> attachments) {
            messages.add(userMessage);
            if (!finalOnly && messages.size() == 1) {
                StreamResult result = new StreamResult();
                result.toolCalls.add(toolCall("call-1", "first_tool"));
                result.toolCalls.add(toolCall("call-2", "second_tool"));
                return result;
            }

            secondRequestToolResults = copyResults(toolResults);
            String text = finalOnly ? "background response" : "steered response";
            Consumer<String> output = getOutputConsumer();
            if (output != null) {
                output.accept(text);
            }
            StreamResult result = new StreamResult();
            result.text = text;
            return result;
        }

        private ToolCallOutput toolCall(String id, String name) {
            ToolCallOutput call = new ToolCallOutput();
            call.id = id;
            call.name = name;
            call.arguments = objectMapper.createObjectNode();
            return call;
        }

        private static List<ToolCallResultInput> copyResults(List<ToolCallResultInput> results) {
            if (results == null) {
                return List.of();
            }
            List<ToolCallResultInput> copy = new ArrayList<>();
            for (ToolCallResultInput result : results) {
                copy.add(new ToolCallResultInput(
                        result.callId, result.name, result.output, result.isError));
            }
            return copy;
        }
    }
}
