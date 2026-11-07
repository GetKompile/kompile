package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The child transcript shown by the process viewer must be the same typed parts
 * the main chat renders — prose, reasoning, tool cards — not a flattened log.
 */
class DirectSubagentRunnerTypedTranscriptTest {

    @TempDir Path directory;

    @Test
    @Timeout(15)
    void childRunRecordsTypedPartsMatchingTheMainChatProjection() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = requests.getAndIncrement();
            // A real tool-use turn streams reasoning, then prose, then the tool call,
            // all in request 0 (finish_reason=tool_calls); request 1 closes the turn
            // after the tool result with prose only.
            String events;
            if (index == 0) {
                events = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking aloud\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"probe\","
                        + "\"arguments\":\"{\\\"n\\\":1}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n";
            } else {
                events = "data: {\"choices\":[{\"delta\":{\"content\":\"finished\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ChatConfig config = new ChatConfig("custom", "test", "model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setAutoCompactEnabled(false);
            config.setCompactionReserveTokens(100);
            ToolRegistry tools = new ToolRegistry(mapper);
            PermissionService permissions = new PermissionService();
            DirectSubagentRunner runner = new DirectSubagentRunner(config, mapper, tools,
                    permissions, new TerminalRenderer(false));
            tools.register(new ai.kompile.cli.main.chat.tools.CliTool() {
                public String id() { return "probe"; }
                public String description() { return "probe"; }
                public ObjectNode parameterSchema() { return mapper.createObjectNode().put("type", "object"); }
                public String permissionKey() { return "read"; }
                public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext context) {
                    return ToolResult.success("probe output");
                }
            });
            AgentConfig agent = AgentConfig.builder("worker").isSubagent(true).build();
            ToolContext parent = new ToolContext("typed-transcript-parent", agent,
                    permissions, directory, tools);
            parent.setOutputConsumer(ignored -> {});

            List<String> ids = new CopyOnWriteArrayList<>();
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) {
                    ids.add(id);
                }
                @Override public void onSubagentEnd(String id) {
                }
            });
            runner.runSubagent(agent, "Run the probe", parent);

            assertEquals(1, ids.size());
            String childId = ids.get(0);
            var transcript = runner.transcriptOf(childId);
            assertNotNull(transcript, "the runner must expose the child's typed transcript");
            assertNull(runner.transcriptOf("unknown-child"), "unknown ids have no transcript");

            List<ChatDisplayBlock> blocks = transcript.blocks(new TerminalRenderer(false));
            List<String> kinds = blocks.stream().map(ChatDisplayBlock::kind).toList();
            assertEquals(List.of("user", "thinking", "assistant", "tool", "assistant"), kinds,
                    "typed parts must mirror the main chat's block sequence");
            assertFalse(blocks.get(3).error());
            assertTrue(blocks.get(3).classic().contains("probe output"),
                    "the tool card carries its output");
            assertTrue(blocks.get(4).classic().contains("finished"));
            // The typed transcript is bounded the same way as the Claude path.
            transcript.finish();
        } finally {
            server.stop(0);
        }
    }
}
