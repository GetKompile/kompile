package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
                ? "change direction now" : null);

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
