package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code kompile chat --workflow <team>} run headless, the way the web and scripts
 * drive it: the team banner goes to stderr, stdout stays one JSON event per line,
 * and the session event names the team. The resume, which is how every later web
 * turn runs, restores the team from the session and announces it again.
 */
@TemporaryUserHome
class ChatCommandWorkflowSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final AtomicInteger modelRequests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        WorkflowSessionContext.activate(null);
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(60)
    void aHeadlessSessionStartedWithATeamAnnouncesItAndItsResumeRestoresIt(@TempDir Path project) throws Exception {
        server = fakeModel();
        // A saved project chat keeps the run off any provider key in the environment.
        new ChatConfig("custom", null, "lead-model", "http://127.0.0.1:" + server.getAddress().getPort())
                .save(ChatConfig.Scope.PROJECT, project);
        assertTrue(WorkflowTeamStore.save(project, team(), true));
        RoleManager roles = new RoleManager(project);
        if (roles.getRole("architect") == null) roles.createRole("architect", "Architect", "d", "workflow", "design");
        if (roles.getRole("implementer") == null) {
            roles.createRole("implementer", "Implementer", "d", "workflow", "implement");
        }
        String sessionId = UUID.randomUUID().toString();

        Run started = run("--working-dir", project.toString(), "--session-id", sessionId,
                "--workflow", "session-team", "--output-format", "stream-json", "design the change");

        assertEquals(0, started.exitCode(), started.toString());
        JsonNode session = started.events().get(0);
        assertEquals("session", session.path("type").asText(), started.toString());
        assertEquals("session-team", session.path("workflow").path("name").asText(), session.toString());
        assertEquals(List.of("designer", "worker"), ids(session.path("workflow")));
        assertEquals("result", started.events().get(started.events().size() - 1).path("type").asText());
        assertTrue(started.stderr().contains("Workflow: session-team (v1)"), started.stderr());
        assertFalse(started.stdout().contains("Workflow: session-team"), started.stdout());

        // The next turn is a new process: nothing carries the team but the session.
        WorkflowSessionContext.activate(null);
        Run resumed = run("--working-dir", project.toString(), "--resume", sessionId,
                "--output-format", "stream-json", "now implement it");

        assertEquals(0, resumed.exitCode(), resumed.toString());
        JsonNode resumedSession = resumed.events().get(0);
        assertEquals("session-team", resumedSession.path("workflow").path("name").asText(), resumedSession.toString());
        assertEquals("designer", resumedSession.path("workflow").path("lead").asText());
        assertTrue(resumed.stderr().contains("Workflow team restored for this session: session-team"),
                resumed.stderr());
        assertFalse(resumed.stdout().contains("Workflow team restored"), resumed.stdout());
        assertEquals(2, modelRequests.get());
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exitCode;
        try {
            exitCode = new CommandLine(new ChatCommand()).execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** One headless invocation; {@link #events()} fails unless every stdout line is JSON. */
    private record Run(int exitCode, String stdout, String stderr) {
        List<JsonNode> events() {
            List<JsonNode> events = new ArrayList<>();
            for (String line : stdout.lines().filter(line -> !line.isBlank()).toList()) {
                events.add(assertDoesNotThrow(() -> MAPPER.readTree(line), line));
            }
            assertFalse(events.isEmpty(), toString());
            return events;
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
    private static WorkflowTeam team() {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put("designer", new WorkflowTeam.Participant("designer", "architect", "cli",
                List.of("read", "plan", "delegate"), List.of("worker")));
        participants.put("worker", new WorkflowTeam.Participant("worker", "implementer", "cli",
                List.of("read", "edit-assigned-files", "validate"), List.of()));
        return new WorkflowTeam("session-team", 1, "designer", participants, Map.of("implement", "worker"),
                new WorkflowTeam.Limits(2), new WorkflowTeam.Gates("approved-design", null));
    }

    private static List<String> ids(JsonNode workflow) {
        List<String> ids = new ArrayList<>();
        workflow.path("participants").forEach(participant -> ids.add(participant.path("id").asText()));
        return ids;
    }
}
