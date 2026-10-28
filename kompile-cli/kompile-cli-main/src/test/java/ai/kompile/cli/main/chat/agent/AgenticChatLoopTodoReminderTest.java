package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.workflow.TodoAutopilot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Loop-level proof that harness todo reminders actually reach mid-loop provider requests. */
@TemporaryUserHome
class AgenticChatLoopTodoReminderTest {
    @TempDir Path directory;

    @Test
    void projectCanDisableAutopilotAtStartupWithoutExplicitReload() throws Exception {
        var config = new ai.kompile.cli.main.chat.enforcer.EnforcerConfig();
        config.setTodoAutopilotEnabled(false);
        config.save(directory);
        ObjectMapper mapper = JsonUtils.standardMapper();
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                new ai.kompile.cli.main.chat.permission.PermissionService(), new AgentRegistry(),
                directory, new ReminderClient(mapper), null);
        assertFalse(loop.todoAutopilot().isEnabled());
    }

    @Test
    void projectReminderIntervalAppliesAtStartupWithoutExplicitReload() throws Exception {
        var config = new ai.kompile.cli.main.chat.enforcer.EnforcerConfig();
        config.setTodoAutopilotEnabled(true);
        config.setTodoAutopilotReminderEveryToolCalls(2);
        config.save(directory);
        ObjectMapper mapper = JsonUtils.standardMapper();
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                new ai.kompile.cli.main.chat.permission.PermissionService(), new AgentRegistry(),
                directory, new ReminderClient(mapper), null);
        var todos = List.of(new TodoWriteTool.TodoItem("1", "task", "", "in_progress", "medium"));
        loop.todoAutopilot().beginTurn();
        loop.todoAutopilot().recordTool("noop");
        assertNull(loop.todoAutopilot().nextReminder(todos));
        loop.todoAutopilot().recordTool("noop");
        assertNotNull(loop.todoAutopilot().nextReminder(todos));
    }

    @Test
    void reminderIsSentMidLoopAndCapped() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(new TodoWriteTool());
        tools.register(new ai.kompile.cli.main.chat.tools.CliTool() {
            public String id() { return "noop"; }
            public String description() { return "read-only test fixture"; }
            public com.fasterxml.jackson.databind.JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            public String permissionKey() { return "read"; }
            public ai.kompile.cli.main.chat.tools.ToolResult execute(
                    com.fasterxml.jackson.databind.JsonNode params,
                    ai.kompile.cli.main.chat.tools.ToolContext context) {
                return ai.kompile.cli.main.chat.tools.ToolResult.success("observed");
            }
        });
        ReminderClient client = new ReminderClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, tools,
                new ai.kompile.cli.main.chat.permission.PermissionService(),
                new AgentRegistry(), directory, client, null);
        String session = "todo-reminder-" + UUID.randomUUID();
        loop.todoAutopilot().configure(true, 1);
        List<ai.kompile.cli.main.chat.render.ChatDisplayBlock> notifications = new ArrayList<>();
        try (var ui = new ai.kompile.cli.main.chat.ChatUiSession(); var ignored = ui.bind()) {
            ai.kompile.cli.main.chat.ChatCompleter.setDisplayBlockOutput(block -> {
                if ("notification".equals(block.kind())) notifications.add(block);
                return true;
            });
            String output = loop.chat("Work the plan", session, "coder", "default", false);
            assertTrue(output.contains("Done"));
            // Interval 1, default cap 5: every noop batch (requests 2, 3, 4) fires one
            // reminder; the text-only request 5 breaks before the reminder tail, so the
            // turn ends with exactly three.
            assertEquals(3, client.reminders.size(), "expected exactly three harness reminders");
            assertEquals(client.reminders.size(), notifications.size());
            for (int i = 0; i < notifications.size(); i++) {
                assertTrue(notifications.get(i).summary().contains("Agent notification · Todo status"));
                assertEquals(client.reminders.get(i), notifications.get(i).sections().get(0).content());
            }
            assertTrue(client.reminders.get(0).contains("<todo-status source=\"harness\">"));
            assertTrue(client.reminders.get(0).contains("[in_progress] #1 Implement and validate"),
                    "the reminder must reflect the harness auto-start");
            assertFalse(client.reminders.get(0).contains("No task is in_progress"));
            assertTrue(TodoWriteTool.getTodos(session, directory).get(0).status.equals("in_progress"),
                    "harness auto-start must mark the first pending task in_progress");
        } finally {
            client.close();
        }
    }

    private static final class ReminderClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        int calls;
        final List<String> reminders = new ArrayList<>();

        ReminderClient(ObjectMapper mapper) {
            super(new ChatConfig("custom", null, "todo-reminder-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
        }

        @Override
        public StreamResult streamChat(String user, String system, ArrayNode defs,
                                       List<ToolCallResultInput> results, String model,
                                       List<AttachmentInput> attachments) {
            calls++;
            if (TodoAutopilot.isHarnessReminder(user)) reminders.add(user);
            StreamResult result = new StreamResult();
            result.text = calls == 1 ? "Planning" : "Done";
            if (calls == 1) {
                ToolCallOutput plan = new ToolCallOutput();
                plan.id = "plan-call";
                plan.name = "todowrite";
                var args = mapper.createObjectNode();
                args.put("action", "set").putArray("todos").addObject()
                        .put("id", "1").put("subject", "Implement and validate")
                        .put("status", "pending");
                plan.arguments = args;
                result.toolCalls.add(plan);
            } else if (calls <= 4) {
                // One read-only call per batch for calls 2-4; call 5 is text-only and ends
                // the turn, so the fixture always terminates.
                ToolCallOutput read = new ToolCallOutput();
                read.id = "read-" + calls;
                read.name = "noop";
                read.arguments = mapper.createObjectNode().put("index", calls);
                result.toolCalls.add(read);
            }
            if (getOutputConsumer() != null) getOutputConsumer().accept(result.text);
            return result;
        }
    }
}
