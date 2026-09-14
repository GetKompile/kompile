package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent-initiated backgrounding is deterministic, not timing-dependent: the
 * eligibility flag is published before the tool worker starts, so a
 * requestSelfBackground() call from inside a running `task` tool always
 * detaches. The model receives the placeholder while the tool is still
 * blocked, and the real result reaches the completion consumer afterwards.
 */
class AgentInitiatedBackgroundTransferTest {

    @TempDir
    Path workingDirectory;

    @Test
    void nonBackgroundableToolRequestIsRefusedWithoutHijackingThePhase() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        AtomicBoolean requestAccepted = new AtomicBoolean(false);
        CountDownLatch secondProviderRequest = new CountDownLatch(1);
        AtomicReference<List<DirectLlmClient.ToolCallResultInput>> secondRequestToolResults =
                new AtomicReference<>();

        ToolRegistry tools = new ToolRegistry(objectMapper);
        // Default CliTool: isBackgroundable() == false (a read/grep-style tool).
        tools.register(new CliTool() {
            @Override
            public String id() { return "read"; }

            @Override
            public String description() { return "non-backgroundable fixture"; }

            @Override
            public JsonNode parameterSchema() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() { return "read"; }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                requestAccepted.set(context.requestSelfBackground());
                return ToolResult.success("read result");
            }
        });

        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("custom", null, "self-background-refusal", "http://unused.invalid"),
                objectMapper) {
            private int requests = 0;

            @Override
            public StreamResult streamChat(
                    String userMessage,
                    String systemPrompt,
                    ArrayNode toolDefs,
                    List<ToolCallResultInput> toolResults,
                    String modelOverride,
                    List<AttachmentInput> attachments) {
                if (requests++ == 0) {
                    StreamResult result = new StreamResult();
                    ToolCallOutput call = new ToolCallOutput();
                    call.id = "call-read";
                    call.name = "read";
                    call.arguments = objectMapper.createObjectNode();
                    result.toolCalls.add(call);
                    return result;
                }
                secondRequestToolResults.set(toolResults);
                secondProviderRequest.countDown();
                StreamResult result = new StreamResult();
                result.text = "final response";
                return result;
            }
        };

        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.setSelfBackgroundRequest(() -> loop.requestBackgroundActiveTurn(
                ignored -> { }, () -> { }, ignored -> { }, () -> { }));

        assertEquals("final response", loop.chat("try to background a read",
                "self-bg-refusal-" + UUID.randomUUID(), "coder", "default", false));

        // The request must be refused (tool not backgroundable) and the model
        // must receive the REAL synchronous result, never a placeholder.
        assertFalse(requestAccepted.get());
        List<DirectLlmClient.ToolCallResultInput> results = secondRequestToolResults.get();
        assertEquals(1, results.size());
        assertEquals("read result", results.get(0).output);
    }

    @Test
    void bashFixtureToolSelfBackgroundsThroughTheGeneralizedPhase() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch transferClaimed = new CountDownLatch(1);
        CountDownLatch completionDelivered = new CountDownLatch(1);
        CountDownLatch secondProviderRequest = new CountDownLatch(1);
        AtomicBoolean requestAccepted = new AtomicBoolean(false);
        AtomicReference<String> completedResult = new AtomicReference<>();
        AtomicReference<List<DirectLlmClient.ToolCallResultInput>> secondRequestToolResults =
                new AtomicReference<>();

        ToolRegistry tools = new ToolRegistry(objectMapper);
        // A bash-shaped long command: isBackgroundable()==true like BashTool,
        // keeps the worker blocked after the self-background request.
        CliTool bashLike = new CliTool() {
            @Override
            public String id() { return "bash"; }

            @Override
            public String description() { return "bash-shaped backgroundable fixture"; }

            @Override
            public boolean isBackgroundable() { return true; }

            @Override
            public JsonNode parameterSchema() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() { return "read"; }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                requestAccepted.set(context.requestSelfBackground());
                toolStarted.countDown();
                try {
                    transferClaimed.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
                return ToolResult.success("build exit 0");
            }
        };
        tools.register(bashLike);

        StringBuilder retained = new StringBuilder();
        CountDownLatch releaseTool = new CountDownLatch(1);

        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("custom", null, "self-background-bash", "http://unused.invalid"),
                objectMapper) {
            private int requests = 0;

            @Override
            public StreamResult streamChat(
                    String userMessage,
                    String systemPrompt,
                    ArrayNode toolDefs,
                    List<ToolCallResultInput> toolResults,
                    String modelOverride,
                    List<AttachmentInput> attachments) {
                if (requests++ == 0) {
                    StreamResult result = new StreamResult();
                    ToolCallOutput call = new ToolCallOutput();
                    call.id = "call-bash";
                    call.name = "bash";
                    call.arguments = objectMapper.createObjectNode();
                    result.toolCalls.add(call);
                    return result;
                }
                secondRequestToolResults.set(toolResults);
                secondProviderRequest.countDown();
                StreamResult result = new StreamResult();
                result.text = "final response";
                return result;
            }
        };

        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.setSelfBackgroundRequest(() -> loop.requestBackgroundActiveTurn(
                retained::append,
                transferClaimed::countDown,
                result -> {
                    completedResult.set(result.getOutput());
                    completionDelivered.countDown();
                },
                () -> { }));

        CompletableFuture<String> response = CompletableFuture.supplyAsync(() -> loop.chat(
                "run the build in background", "self-bg-bash-" + UUID.randomUUID(),
                "coder", "default", false));

        try {
            assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
            assertTrue(requestAccepted.get(),
                    "a bash-phase request must be accepted under the generalized eligibility");
            assertTrue(transferClaimed.await(5, TimeUnit.SECONDS));
            assertTrue(secondProviderRequest.await(5, TimeUnit.SECONDS));
            List<DirectLlmClient.ToolCallResultInput> results = secondRequestToolResults.get();
            assertTrue(results.get(0).output.contains("still running in the background"),
                    () -> "expected placeholder tool result, got: " + results.get(0).output);

            releaseTool.countDown();
            assertEquals("final response", response.get(5, TimeUnit.SECONDS));
            assertTrue(completionDelivered.await(5, TimeUnit.SECONDS));
            assertTrue(completedResult.get().contains("build exit 0"),
                    () -> "unexpected completed result: " + completedResult.get());
        } finally {
            releaseTool.countDown();
            loop.clearBackgroundOutput();
        }
    }

    @Test
    void selfBackgroundRequestFromRunningTaskToolDetachesDeterministically() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch transferClaimed = new CountDownLatch(1);
        CountDownLatch completionDelivered = new CountDownLatch(1);
        CountDownLatch secondProviderRequest = new CountDownLatch(1);
        AtomicBoolean requestAccepted = new AtomicBoolean(false);
        AtomicReference<String> completedResult = new AtomicReference<>();
        AtomicReference<List<DirectLlmClient.ToolCallResultInput>> secondRequestToolResults =
                new AtomicReference<>();

        ToolRegistry tools = new ToolRegistry(objectMapper);
        tools.register(new CliTool() {
            @Override
            public String id() { return "task"; }

            @Override
            public boolean isBackgroundable() { return true; }

            @Override
            public String description() { return "self-backgrounding fixture"; }

            @Override
            public JsonNode parameterSchema() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() { return "read"; }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                // Mirrors TaskTool: request the transfer at invocation time,
                // then keep the parent's tool call blocked on real work.
                requestAccepted.set(context.requestSelfBackground());
                toolStarted.countDown();
                try {
                    transferClaimed.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
                return ToolResult.success("real subagent result");
            }
        });

        StringBuilder retained = new StringBuilder();
        CountDownLatch releaseTool = new CountDownLatch(1);

        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("custom", null, "self-background-test", "http://unused.invalid"),
                objectMapper) {
            private int requests = 0;

            @Override
            public StreamResult streamChat(
                    String userMessage,
                    String systemPrompt,
                    ArrayNode toolDefs,
                    List<ToolCallResultInput> toolResults,
                    String modelOverride,
                    List<AttachmentInput> attachments) {
                if (requests++ == 0) {
                    StreamResult result = new StreamResult();
                    ToolCallOutput call = new ToolCallOutput();
                    call.id = "call-task";
                    call.name = "task";
                    call.arguments = objectMapper.createObjectNode();
                    result.toolCalls.add(call);
                    return result;
                }
                secondRequestToolResults.set(toolResults);
                secondProviderRequest.countDown();
                StreamResult result = new StreamResult();
                result.text = "final response";
                return result;
            }
        };

        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        // Minimal production-shaped hook: registers the transfer exactly like
        // ChatMessageHandler.requestBackground does via the same loop API.
        loop.setSelfBackgroundRequest(() -> loop.requestBackgroundActiveTurn(
                retained::append,
                transferClaimed::countDown,
                result -> {
                    completedResult.set(result.getOutput());
                    completionDelivered.countDown();
                },
                () -> { }));

        CompletableFuture<String> response = CompletableFuture.supplyAsync(() -> {
            try {
                return loop.chat("delegate in background", "self-bg-" + UUID.randomUUID(),
                        "coder", "default", false);
            } catch (RuntimeException e) {
                throw e;
            }
        });

        try {
            assertTrue(toolStarted.await(5, TimeUnit.SECONDS),
                    "the task tool must start before assertions");
            assertTrue(requestAccepted.get(),
                    "the self-background request must be accepted while the tool runs");
            assertTrue(transferClaimed.await(5, TimeUnit.SECONDS),
                    "the dispatch poll must claim the registered transfer");

            // The placeholder reaches the model while the fixture tool is still blocked.
            assertTrue(secondProviderRequest.await(5, TimeUnit.SECONDS),
                    "the loop must continue to the next provider request after detaching");
            List<DirectLlmClient.ToolCallResultInput> results = secondRequestToolResults.get();
            assertEquals(1, results.size());
            assertFalse(results.get(0).isError);
            assertTrue(results.get(0).output.contains("still running in the background"),
                    () -> "expected placeholder tool result, got: " + results.get(0).output);

            // Finish the work; the real result must arrive exactly once.
            releaseTool.countDown();
            assertEquals("final response", response.get(5, TimeUnit.SECONDS));
            assertTrue(completionDelivered.await(5, TimeUnit.SECONDS),
                    "the detached completion consumer must receive the real result");
            assertTrue(completedResult.get().contains("real subagent result"),
                    () -> "unexpected completed result: " + completedResult.get());
        } finally {
            releaseTool.countDown();
            loop.clearBackgroundOutput();
        }
    }
}
