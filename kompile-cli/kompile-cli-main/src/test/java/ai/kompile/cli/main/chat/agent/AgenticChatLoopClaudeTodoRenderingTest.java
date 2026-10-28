package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatUiSession;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.McpToolResultSerializer;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopClaudeTodoRenderingTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"todowrite,false", "todowrite,true", "todoread,false", "todoread,true"})
    void providerTodoSnapshotReachesTranscriptAndInspector(String tool, boolean spilled) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        var ui = new ChatUiSession();
        var binding = ui.bind();
        Map<String, ChatDisplayBlock> blocks = Collections.synchronizedMap(new LinkedHashMap<>());
        List<ToolResult> completed = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(ignored -> { });
        ChatCompleter.setDisplayBlockOutput(block -> { blocks.put(block.key(), block); return true; });
        var mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, directory)) {
            String payload = McpToolResultSerializer.toMcpCallResult(mapper,
                    ToolResult.success("Updated task #2", "updated", Map.of("todos", List.of(
                            Map.of("id", "1", "subject", "Finished", "status", "completed"),
                            Map.of("id", "2", "subject", "Working", "status", "in_progress",
                                    "description", "detail ".repeat(9_000))))))
                    .path("structuredContent").toString();
            String output = payload;
            if (spilled) {
                Path saved = directory.resolve(".claude/projects/project/session/tool-results/mcp-kompile-"
                        + tool + "-123.txt");
                Files.createDirectories(saved.getParent());
                Files.writeString(saved, payload);
                output = "Error: result (" + payload.length() + " characters) exceeds maximum allowed tokens. "
                        + "Output has been saved to " + saved + ".\nFormat: JSON with schema: {output, metadata}\n";
            }
            var result = mapper.createObjectNode();
            result.put("type", "user");
            var block = result.putObject("message").putArray("content").addObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", "todo-call");
            block.putArray("content").addObject().put("type", "text").put("text", output);
            String input = tool.equals("todowrite")
                    ? "{\"action\":\"update\",\"task_id\":\"2\",\"status\":\"in_progress\"}" : "{}";
            FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("fake"), """
                    turn() {
                      say_init
                      emit '{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"todo-call","name":"mcp__kompile__%s","input":{}}}}'
                      emit '{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":%s}}}'
                      emit '{"type":"stream_event","event":{"type":"content_block_stop","index":1}}'
                      emit '%s'
                      say_text 'Task list updated.'
                      say_result
                    }
                    """.formatted(tool, mapper.writeValueAsString(input), result));
            fake.install(client, directory, "claude-todo-rendering");
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            loop.configureConversationSession("todo-rendering");
            loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
                @Override
                public void onToolStart(String callId, String name, String rawInput) { }
                @Override
                public void onToolComplete(String callId, String name, String rawInput, ToolResult result) {
                    assertEquals(input, rawInput);
                    completed.add(result);
                }
            });
            assertTrue(loop.chat("update tasks", "todo-rendering", "coder", "default", false)
                    .contains("Task list updated."));
            List<ChatDisplayBlock> todos = blocks.values().stream()
                    .filter(b -> b.kind().equals("todos") && !b.running()).toList();
            assertEquals(1, todos.size(), blocks.toString());
            var todo = todos.get(0);
            String classic = TerminalRenderer.printableToolOutput(todo.classic());
            assertTrue(classic.contains("Tasks [1/2]"), classic);
            assertTrue(classic.contains("▶ #2 Working [current]"), classic);
            assertFalse(classic.contains("exceeds maximum"), classic);
            assertFalse(classic.contains("\"metadata\""), classic);
            assertEquals("todos", todo.sections().get(0).kind());
            assertTrue(todo.sections().get(0).content().contains("▶ #2 Working [current]"));
            assertEquals(1, completed.size());
            assertTrue(completed.get(0).getMetadata().containsKey("todos"));
        } finally {
            ui.close();
            binding.close();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }
}
