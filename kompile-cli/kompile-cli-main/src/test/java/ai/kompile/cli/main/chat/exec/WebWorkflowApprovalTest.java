package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatCommand;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gate approved from the browser. The web's first run starts the session with its
 * team, exactly as the chat app invokes the harness. Between runs no harness holds the
 * team, so an approval is a one-shot {@code workflowApprove} input that records the gate
 * for the session; the next run restores the team with it. An approval never reaches
 * the model, and one that cannot apply records nothing.
 */
@TemporaryUserHome
class WebWorkflowApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final InputStream originalIn = System.in;
    private final AtomicInteger modelRequests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
        WorkflowSessionContext.activate(null);
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(90)
    void aGateApprovedBetweenRunsIsWhatTheNextRunRestores(@TempDir Path project) throws Exception {
        String sessionId = startTeamSession(project);
        assertEquals(Set.of(), WorkflowSessionContext.satisfiedGates(sessionId));

        Run approval = approve(project, sessionId, "");

        assertEquals(0, approval.exitCode(), approval.toString());
        JsonNode command = approval.event("command");
        assertEquals("/workflow approve", command.path("command").asText());
        assertEquals("COMPLETED", command.path("status").asText(), command.toString());
        assertEquals("Approved gate 'approved-design' for workflow 'session-team'.", command.path("text").asText());
        assertEquals("workflow", command.path("data").path("menu").asText(), command.toString());
        assertEquals("session-team", command.path("data").path("workflow").asText());
        assertEquals("approved-design", command.path("data").path("gate").asText());
        assertEquals(List.of("approved-design"), strings(command.path("data").path("approved")));
        assertEquals(Set.of("approved-design"), WorkflowSessionContext.satisfiedGates(sessionId));
        assertEquals(1, modelRequests.get(), "an approval never reaches the model");

        // The next turn is a new process: only the session carries the team and its approval.
        WorkflowSessionContext.activate(null);
        Run resumed = webRun(project, "now implement it", sessionId, "--resume", sessionId);

        assertEquals(0, resumed.exitCode(), resumed.toString());
        JsonNode workflow = resumed.event("session").path("workflow");
        assertEquals("session-team", workflow.path("name").asText(), resumed.toString());
        assertEquals(List.of("approved-design"), strings(workflow.path("gates").path("approved")), workflow.toString());
        assertEquals(2, modelRequests.get());
    }

    @Test
    @Timeout(90)
    void anApprovalWithNothingLeftToApproveOrAnUnknownGateIsRefused(@TempDir Path project) throws Exception {
        String sessionId = startTeamSession(project);
        // Gate names match as the terminal's /workflow approve matches them.
        Run named = approve(project, sessionId, " Approved-Design ");
        assertEquals(0, named.exitCode(), named.toString());
        assertEquals("approved-design", named.event("command").path("data").path("gate").asText());

        Run again = approve(project, sessionId, "");
        assertEquals(2, again.exitCode(), again.toString());
        JsonNode refusal = again.event("command");
        assertEquals("INVALID", refusal.path("status").asText());
        assertEquals("Every gate of workflow 'session-team' is already approved.", refusal.path("text").asText());

        Run unknown = approve(project, sessionId, "shipped");
        assertEquals(2, unknown.exitCode(), unknown.toString());
        assertEquals("Workflow 'session-team' has no gate 'shipped'. Its gates: implementation 'approved-design'.",
                unknown.event("command").path("text").asText());
        assertEquals(Set.of("approved-design"), WorkflowSessionContext.satisfiedGates(sessionId));
        assertEquals(1, modelRequests.get());
    }

    @Test
    @Timeout(60)
    void aSessionWithoutATeamHasNothingToApprove(@TempDir Path project) throws Exception {
        setUp(project);
        String sessionId = UUID.randomUUID().toString();

        Run approval = approve(project, sessionId, "");

        assertEquals(2, approval.exitCode(), approval.toString());
        JsonNode refusal = approval.event("command");
        assertEquals("INVALID", refusal.path("status").asText());
        assertEquals("This session has no workflow team.", refusal.path("text").asText());
        assertTrue(refusal.path("data").isMissingNode(), refusal.toString());
        assertFalse(Files.exists(WorkflowSessionContext.sessionPath(sessionId)));
        assertEquals(0, modelRequests.get());
    }

    @Test
    @Timeout(90)
    void aTeamEditedSinceTheSessionStartedCannotBeApproved(@TempDir Path project) throws Exception {
        String sessionId = startTeamSession(project);
        // A saved change bumps the team's version, which the session no longer matches.
        assertTrue(WorkflowTeamStore.save(project, team(3), true));

        Run approval = approve(project, sessionId, "");

        assertEquals(2, approval.exitCode(), approval.toString());
        String refusal = approval.event("command").path("text").asText();
        assertTrue(refusal.startsWith("Workflow 'session-team' changed since this session started"), refusal);
        assertEquals(Set.of(), WorkflowSessionContext.satisfiedGates(sessionId));
    }

    @Test
    void anApprovalCarriesAGateNameAndNothingElse() {
        assertEquals("approved-design",
                WebChatInput.parse("{\"version\":1,\"sessionId\":\"s\",\"workflowApprove\":\"  approved-design \"}")
                        .workflowApprove());
        assertEquals("", WebChatInput.parse("{\"version\":1,\"rawInput\":\"\",\"workflowApprove\":\"\"}")
                .workflowApprove());
        String longest = "g".repeat(WebChatInput.MAX_GATE_LENGTH);
        assertEquals(longest, WebChatInput.parse("{\"version\":1,\"workflowApprove\":\"" + longest + "\"}")
                .workflowApprove());
        assertNull(WebChatInput.parse("{\"version\":1,\"rawInput\":\"hello\"}").workflowApprove());
        for (String invalid : List.of(
                "{\"version\":1,\"rawInput\":\"hello\",\"workflowApprove\":\"\"}",
                "{\"version\":1,\"configQuery\":true,\"workflowApprove\":\"\"}",
                "{\"version\":1,\"workflowApprove\":7}",
                "{\"version\":1,\"workflowApprove\":null}",
                "{\"version\":1,\"workflowApprove\":\"approved\\tdesign\"}",
                "{\"version\":1,\"workflowApprove\":\"" + longest + "g\"}")) {
            assertThrows(IllegalArgumentException.class, () -> WebChatInput.parse(invalid), invalid);
        }
    }

    /**
     * The web's first run of a session, which starts it with the team the chat was handed;
     * returns the session id. The team is then dropped, as the run's process would exit.
     */
    private String startTeamSession(Path project) throws Exception {
        setUp(project);
        String sessionId = UUID.randomUUID().toString();
        Run started = webRun(project, "design the change", sessionId,
                "--session-id", sessionId, "--workflow=session-team");
        assertEquals(0, started.exitCode(), started.toString());
        assertEquals("session-team", started.event("session").path("workflow").path("name").asText(),
                started.toString());
        WorkflowSessionContext.activate(null);
        return sessionId;
    }

    private void setUp(Path project) throws Exception {
        server = fakeModel();
        // A saved project chat keeps every run off any provider key in the environment.
        new ChatConfig("custom", null, "lead-model", "http://127.0.0.1:" + server.getAddress().getPort())
                .save(ChatConfig.Scope.PROJECT, project);
        assertTrue(WorkflowTeamStore.save(project, team(2), true));
        RoleManager roles = new RoleManager(project);
        if (roles.getRole("architect") == null) roles.createRole("architect", "Architect", "d", "workflow", "design");
        if (roles.getRole("implementer") == null) {
            roles.createRole("implementer", "Implementer", "d", "workflow", "implement");
        }
    }

    /** One web turn, invoked as the chat app invokes it: the input is the first stdin line. */
    private Run webRun(Path project, String rawInput, String sessionId, String... sessionArgs) {
        List<String> args = new ArrayList<>(List.of("--output-format", "stream-json", "--input-format", "web-json",
                "--web-controls", "--local", "--working-dir", project.toString()));
        args.addAll(List.of(sessionArgs));
        args.add("-");
        ObjectNode input = MAPPER.createObjectNode().put("version", 1).put("rawInput", rawInput)
                .put("sessionId", sessionId);
        return run(input.toString() + "\n", args.toArray(String[]::new));
    }

    /** The chat app's one-shot approval between runs. */
    private Run approve(Path project, String sessionId, String gate) {
        ObjectNode input = MAPPER.createObjectNode().put("version", 1).put("rawInput", "")
                .put("sessionId", sessionId).put("workflowApprove", gate);
        return run(input.toString(), "--output-format", "stream-json", "--input-format", "web-json",
                "--working-dir", project.toString(), "--timeout", "30");
    }

    private Run run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exitCode;
        try {
            exitCode = new CommandLine(new ChatCommand()).execute(args);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** One headless invocation; every stdout line must be a JSON event. */
    private record Run(int exitCode, String stdout, String stderr) {
        JsonNode event(String type) {
            for (String line : stdout.lines().filter(line -> !line.isBlank()).toList()) {
                JsonNode event = assertDoesNotThrow(() -> MAPPER.readTree(line), line);
                if (type.equals(event.path("type").asText())) return event;
            }
            return fail("No " + type + " event: " + this);
        }
    }

    private HttpServer fakeModel() throws Exception {
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                modelRequests.incrementAndGet();
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"team answer\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        fake.start();
        return fake;
    }

    /** The designer leads and delegates implementation to the worker once the design is approved. */
    private static WorkflowTeam team(int maxConcurrentWorkers) {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                List.of("read", "plan", "delegate"), List.of("worker")));
        participants.put("worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                List.of("read", "edit-assigned-files", "validate"), List.of()));
        return new WorkflowTeam("session-team", 1, "designer", participants, Map.of("implement", "worker"),
                new WorkflowTeam.Limits(maxConcurrentWorkers), new WorkflowTeam.Gates("approved-design", null));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
