package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a chat started with a workflow team, the rows a delegated participant produces
 * (status bar, activity panel, inline blocks, web activity) name it the way the lead's
 * delegation line does: participant, role and bound model.
 */
@TemporaryUserHome
class SubagentRunnerWorkflowLabelTest {

    private static final String BRIEF =
            "You are participant 'worker' (role: implementer) of workflow team 'label-team'.";

    @TempDir Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearWorkflow() {
        WorkflowSessionContext.activate(null);
    }

    @Test
    void rowsNameTheParticipantItsRoleAndItsModel() {
        AgentConfig implementer = AgentConfig.builder("implementer").isSubagent(true)
                .roleName("implementer").build();
        assertEquals("worker (implementer on custom/worker-model (thinking: high))",
                DirectSubagentRunner.displayName(implementer, "worker",
                        new WorkflowTeam.ModelBinding("custom", "worker-model", "high")));
        assertEquals("worker (implementer)", DirectSubagentRunner.displayName(implementer, "worker", null));
        // Without a role the participant is named with its agent.
        assertEquals("worker (explore)",
                DirectSubagentRunner.displayName(AgentConfig.builder("explore").build(), "worker", null));
        // Outside a team a row keeps the agent's name.
        assertEquals("implementer", DirectSubagentRunner.displayName(implementer, null, null));
        assertEquals("implementer", DirectSubagentRunner.displayName(implementer, " ",
                new WorkflowTeam.ModelBinding("custom", "worker-model", null)));
    }

    @Test
    @Timeout(15)
    void directParticipantRowsCarryItsBindingAndTheChildRunsOnIt() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write((
                    "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":null}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "test", "lead-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            // On the chat's own route, so the participant keeps the chat's credential.
            WorkflowSessionContext.activate(snapshot(new WorkflowTeam.ModelBinding("custom", "worker-model",
                    null, config.getAuthenticationMethod(), config.getBaseUrl(), null)));
            PermissionService permissions = new PermissionService();
            ToolRegistry tools = new ToolRegistry(mapper);
            DirectSubagentRunner runner = new DirectSubagentRunner(config, mapper, tools, permissions,
                    new TerminalRenderer(false));
            List<String> rows = new CopyOnWriteArrayList<>();
            List<String> activity = new CopyOnWriteArrayList<>();
            runner.setLifecycleListener(recording(rows, activity));

            // TaskTool passes a bound participant's model as the child's override.
            String result = runner.runSubagent(worker().toBuilder().modelOverride("worker-model").build(),
                    "Implement the approved design", lead(tools, permissions));

            assertTrue(result.contains("done"), result);
            assertEquals(List.of("worker (implementer on custom/worker-model)"), rows);
            assertTrue(activity.stream().anyMatch(detail ->
                    detail.contains("Subagent: worker (implementer on custom/worker-model)")), activity.toString());
            JsonNode request = mapper.readTree(requests.get(0));
            assertEquals("worker-model", request.path("model").asText());
            assertTrue(request.path("messages").toString().contains(BRIEF), request.toString());
            assertEquals("lead-model", config.getModel());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void serverBackedParticipantRowsNameTheParticipant() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agents/chat/stream", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("event: chunk\ndata: \"done\"\n\n"
                    + "event: complete\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            // A server-backed chat refuses a bound participant, so this worker follows the lead.
            WorkflowSessionContext.activate(snapshot(null));
            PermissionService permissions = new PermissionService();
            ToolRegistry tools = new ToolRegistry(mapper);
            ServerSubagentRunner runner = new ServerSubagentRunner(
                    "http://127.0.0.1:" + server.getAddress().getPort(), tools, permissions, mapper,
                    new TerminalRenderer(false));
            List<String> rows = new CopyOnWriteArrayList<>();
            List<String> activity = new CopyOnWriteArrayList<>();
            runner.setLifecycleListener(recording(rows, activity));

            String result = runner.runSubagent(worker(), "Implement the approved design",
                    lead(tools, permissions));

            assertTrue(result.contains("done"), result);
            assertEquals(List.of("worker (implementer)"), rows);
            assertTrue(activity.stream().anyMatch(detail ->
                    detail.contains("Subagent: worker (implementer)")), activity.toString());
            assertTrue(mapper.readTree(requests.get(0)).path("systemPromptOverride").asText().contains(BRIEF));
        } finally {
            server.stop(0);
        }
    }

    /** What TaskTool launches when the lead delegates the team's "implement" purpose to an unbound worker. */
    private static AgentConfig worker() {
        return AgentConfig.builder("implementer").isSubagent(true).roleName("implementer")
                .workflowParticipant("worker").build();
    }

    private ToolContext lead(ToolRegistry tools, PermissionService permissions) {
        ToolContext lead = new ToolContext("workflow-lead", AgentConfig.builder("lead").build(),
                permissions, directory, tools);
        lead.setOutputConsumer(ignored -> {});
        return lead;
    }

    private static SubagentRunner.LifecycleListener recording(List<String> rows, List<String> activity) {
        return new SubagentRunner.LifecycleListener() {
            @Override public void onSubagentStart(String id, String type, String description) {
                rows.add(type);
            }
            @Override public void onSubagentActivity(String id, String summary, String detail) {
                activity.add(detail);
            }
            @Override public void onSubagentEnd(String id) {
            }
        };
    }

    /** The designer leads and delegates implementation to the worker, which runs on {@code workerModel}. */
    private static WorkflowTeamSnapshot snapshot(WorkflowTeam.ModelBinding workerModel) {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                List.of("read", "plan", "delegate"), List.of("worker")));
        participants.put("worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                List.of("read", "edit-assigned-files", "validate"), List.of(), workerModel));
        WorkflowTeam team = new WorkflowTeam("label-team", 1, "designer", participants,
                Map.of("implement", "worker"), new WorkflowTeam.Limits(2), WorkflowTeam.Gates.NONE);
        return new WorkflowTeamSnapshot(team, Map.of("designer", "architect", "worker", "implementer"), null);
    }
}
