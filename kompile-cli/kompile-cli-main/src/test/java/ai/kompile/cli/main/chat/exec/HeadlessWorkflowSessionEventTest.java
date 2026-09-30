package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A headless run started with a workflow team announces the team in its session
 * event, which the web forwards as {@code harness_session}: the lead first, each
 * participant's role, model, capabilities and delegation edges, the routing, and
 * the gates with those already approved.
 */
@TemporaryUserHome
class HeadlessWorkflowSessionEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearWorkflow() {
        WorkflowSessionContext.activate(null);
    }

    @Test
    void aRunWithoutATeamAnnouncesNone() {
        assertNull(HeadlessAgentRunner.workflowJson(chat("http://127.0.0.1:1"), mapper));
    }

    @Test
    void theTeamIsAnnouncedLeadFirstWithModelsRoutingAndGates() throws Exception {
        WorkflowSessionContext.activate(snapshot());
        WorkflowSessionContext.current().enforcement().satisfyGate("approved-design");

        JsonNode workflow = mapper.readTree(HeadlessAgentRunner.workflowJson(chat("http://127.0.0.1:1"), mapper));

        assertEquals("review-team", workflow.path("name").asText());
        assertEquals(3, workflow.path("version").asInt());
        assertEquals("designer", workflow.path("lead").asText());
        JsonNode participants = workflow.path("participants");
        // Declared worker, designer, reviewer: the lead is listed first, the rest keep their order.
        assertEquals(List.of("designer", "worker", "reviewer"), field(participants, "id"));
        assertEquals(List.of("architect", "implementer", "reviewer"), field(participants, "role"));
        assertEquals(List.of("the chat's model (custom/lead-model (thinking: low))",
                        "custom/worker-model (thinking: high)",
                        "the lead's model (custom/lead-model (thinking: low))"),
                field(participants, "model"));
        assertEquals(List.of("read", "plan", "delegate"), strings(participants.get(0).path("capabilities")));
        assertEquals(List.of("worker", "reviewer"), strings(participants.get(0).path("delegatesTo")));
        assertEquals(List.of("read", "edit-assigned-files", "validate"),
                strings(participants.get(1).path("capabilities")));
        assertFalse(participants.get(1).has("delegatesTo"));
        assertFalse(participants.get(2).has("delegatesTo"));
        assertEquals("worker", workflow.path("routing").path("implement").asText());
        assertEquals("reviewer", workflow.path("routing").path("review").asText());
        JsonNode gates = workflow.path("gates");
        assertEquals("approved-design", gates.path("implementationRequires").asText());
        assertEquals("review-passed", gates.path("completionRequires").asText());
        assertEquals(List.of("approved-design"), strings(gates.path("approved")));
        assertEquals(2, workflow.path("maxConcurrentWorkers").asInt());
        // Models appear by label only: no endpoint or credential reaches the event.
        assertFalse(workflow.toString().contains("127.0.0.1"), workflow.toString());
    }

    @Test
    void theSessionEventCarriesTheTeamAsAnObject() throws Exception {
        WorkflowSessionContext.activate(snapshot());
        String team = HeadlessAgentRunner.workflowJson(chat("http://127.0.0.1:1"), mapper);

        JsonNode session = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.started("s", "lead-model", "/w", Map.of("workflow", team)).withSequence(1)));

        assertEquals("session", session.path("type").asText());
        assertTrue(session.path("workflow").isObject(), session.toString());
        assertEquals("designer", session.path("workflow").path("lead").asText());
    }

    @Test
    @Timeout(30)
    void aJsonRunStartedWithATeamAnnouncesItAndKeepsStdoutMachineReadable(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"team answer\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        PrintStream previousOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            WorkflowSessionContext.activate(snapshot());
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "design the change", "workflow-json-" + UUID.randomUUID(), false, null, null,
                    HeadlessAgentRunner.OutputMode.JSON, project, 0, null, null, null,
                    null, chat("http://127.0.0.1:" + server.getAddress().getPort()), null,
                    false, false, null, false);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);

            System.setOut(previousOut);
            assertEquals(0, result.exitCode(), result.text());
            List<JsonNode> lines = new ArrayList<>();
            for (String line : stdout.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList()) {
                lines.add(assertDoesNotThrow(() -> mapper.readTree(line), line));
            }
            assertEquals("session", lines.get(0).path("type").asText(), lines.toString());
            JsonNode workflow = lines.get(0).path("workflow");
            assertEquals("review-team", workflow.path("name").asText(), lines.get(0).toString());
            assertEquals(List.of("designer", "worker", "reviewer"), field(workflow.path("participants"), "id"));
            assertEquals("result", lines.get(lines.size() - 1).path("type").asText(), lines.toString());
        } finally {
            System.setOut(previousOut);
            server.stop(0);
        }
    }

    private static ChatConfig chat(String baseUrl) {
        ChatConfig config = new ChatConfig("custom", null, "lead-model", baseUrl);
        config.setThinking("low");
        return config;
    }

    /**
     * The designer leads and delegates to a worker bound to its own model and to an
     * unbound reviewer. The lead is declared second so the announcement has to move it first.
     */
    private static WorkflowTeamSnapshot snapshot() {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                List.of("read", "edit-assigned-files", "validate"), List.of(),
                new WorkflowTeam.ModelBinding("custom", "worker-model", "high")));
        participants.put("designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                List.of("read", "plan", "delegate"), List.of("worker", "reviewer")));
        participants.put("reviewer", new WorkflowTeam.Participant("reviewer", "reviewer", "cli",
                List.of("read", "validate"), List.of()));
        Map<String, String> routing = new LinkedHashMap<>();
        routing.put("implement", "worker");
        routing.put("review", "reviewer");
        WorkflowTeam team = new WorkflowTeam("review-team", 3, "designer", participants, routing,
                new WorkflowTeam.Limits(2), new WorkflowTeam.Gates("approved-design", "review-passed"));
        return new WorkflowTeamSnapshot(team,
                Map.of("worker", "implementer", "designer", "architect", "reviewer", "reviewer"), null);
    }

    private static List<String> field(JsonNode array, String name) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(name).asText()));
        return values;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
