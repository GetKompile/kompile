package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.deflection.*;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
@Timeout(20)
class AgenticChatLoopDeflectionReceiptTest {
    @TempDir Path directory;
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private enum Mode { EXECUTED, PROPOSED, NATIVE, DETACHED }

    @Test
    void completedDiagnosticCountsOnFinalResponseButNeverOnTheNextTurn() {
        try (Fixture fixture = fixture(Mode.EXECUTED)) {
            String id = "receipts-" + UUID.randomUUID();
            fixture.loop.chat("Fix the failing test", id, "coder", "default", false);
            assertEquals(1, fixture.executions.get());
            assertEquals(0, fixture.monitor.getCorrectionsThisTurn(), "completed diagnostics are receipts");
            fixture.loop.chat("Fix a different failure", id, "coder", "default", false);
            assertEquals(1, fixture.monitor.getCorrectionsThisTurn(), "old-turn receipts must not authorize a new claim");
        }
    }

    @Test
    void proposedDiagnosticsDoNotExcuseDeflectionBeforeExecution() {
        try (Fixture fixture = fixture(Mode.PROPOSED)) {
            fixture.loop.chat("Fix the failing test", "proposed-" + UUID.randomUUID(), "coder", "default", false);
            assertEquals(0, fixture.executions.get(), "the rejected proposal was never executed");
            assertEquals(1, fixture.monitor.getCorrectionsThisTurn());
        }
    }

    @Test
    void nativeProviderCompletionCountsAsExecutedEvidence() {
        try (Fixture fixture = fixture(Mode.NATIVE)) {
            String id = "native-receipts-" + UUID.randomUUID();
            fixture.loop.chat("Fix the failing test", id, "coder", "default", false);
            assertEquals(0, fixture.monitor.getCorrectionsThisTurn());
            fixture.loop.chat("Fix a different failure", id, "coder", "default", false);
            assertEquals(1, fixture.monitor.getCorrectionsThisTurn());
        }
    }

    @Test
    void detachedAcknowledgementAndLateCompletionNeverCountAsCurrentDiagnostics() {
        try (Fixture fixture = fixture(Mode.DETACHED)) {
            String id = "detached-receipts-" + UUID.randomUUID();
            fixture.loop.chat("Fix the failing test", id, "coder", "default", false);
            assertEquals(1, fixture.executions.get());
            assertEquals(1L, fixture.client.completed.getCount(), "tool remains blocked after its acknowledgement");
            assertEquals(1, fixture.monitor.getCorrectionsThisTurn(), "still-running is not investigation evidence");
            // The next request releases the old tool and waits for its real
            // completion before returning the new claim: evidence must not leak.
            fixture.loop.chat("Fix a different failure", id, "coder", "default", false);
            assertEquals(0L, fixture.client.completed.getCount());
            assertEquals(1, fixture.monitor.getCorrectionsThisTurn(), "old completion cannot excuse a new-turn claim");
        }
    }

    private Fixture fixture(Mode mode) {
        ScriptedClient client = new ScriptedClient(mode);
        var executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(MAPPER);
        tools.register(new CliTool() {
            public String id() { return "bash"; }
            public String description() { return "fake diagnostic; never runs a shell"; }
            public com.fasterxml.jackson.databind.JsonNode parameterSchema() {
                return MAPPER.createObjectNode().put("type", "object");
            }
            public String permissionKey() { return "read"; }
            public boolean isBackgroundable() { return mode == Mode.DETACHED; }
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext context) {
                executions.incrementAndGet();
                if (mode == Mode.DETACHED) {
                    assertTrue(context.requestSelfBackground());
                    try {
                        assertTrue(client.releaseTool.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return ToolResult.error("interrupted");
                    }
                }
                return ToolResult.success("diagnostic completed with a failing test");
            }
        });
        PermissionService permissions = new PermissionService();
        permissions.allowAll();
        AgenticChatLoop loop = new AgenticChatLoop(null, MAPPER, tools, permissions,
                new AgentRegistry(), directory, client, null);
        DeflectionMonitor monitor = new DeflectionMonitor(
                new DeflectionDetector(DeflectionPatternLibrary.defaults(), DeflectionDetector.Options.defaults()),
                new DeflectionMonitor.Options("pattern", false, 0.7, 2, 0, true), null);
        loop.setDeflectionMonitor(monitor, 2);
        if (mode == Mode.DETACHED) {
            loop.setSelfBackgroundRequest(() -> loop.requestBackgroundActiveTurn(ignored -> { },
                    () -> { }, result -> client.completed.countDown(), () -> { }));
        }
        return new Fixture(loop, monitor, client, executions);
    }

    private record Fixture(AgenticChatLoop loop, DeflectionMonitor monitor,
                           ScriptedClient client, AtomicInteger executions) implements AutoCloseable {
        public void close() {
            client.releaseTool.countDown();
            loop.cancelDetachedInvocations();
            client.close();
        }
    }

    private static final class ScriptedClient extends DirectLlmClient {
        private final Mode mode;
        private int calls;
        private final java.util.concurrent.CountDownLatch releaseTool = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        ScriptedClient(Mode mode) {
            super(new ChatConfig("custom", null, "receipt-test", "http://unused.invalid"), MAPPER);
            this.mode = mode;
        }
        @Override public StreamResult streamChat(String user, String system, ArrayNode tools,
                List<ToolCallResultInput> results, String model, List<AttachmentInput> attachments) {
            calls++;
            StreamResult result = new StreamResult();
            result.text = "Not my error.";
            if (calls == 1 && mode != Mode.NATIVE) {
                if (mode != Mode.PROPOSED) result.text = "Investigating now.";
                ToolCallOutput call = new ToolCallOutput();
                call.id = "diagnostic";
                call.name = "bash";
                call.arguments = MAPPER.createObjectNode().put("command", "mvn test -Dtest=FailureTest");
                result.toolCalls.add(call);
            } else if (calls == 1) {
                var activity = getProviderActivityListener();
                assertNotNull(activity);
                activity.onToolStart("native-diagnostic", "bash", "{\"command\":\"mvn test\"}");
                activity.onToolComplete("native-diagnostic", "bash", "a test failed", 1, true);
            }
            if (mode == Mode.DETACHED && calls == 4) {
                releaseTool.countDown();
                try {
                    assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            int lastDeflecting = mode == Mode.EXECUTED ? 3 : mode == Mode.NATIVE ? 2 : mode == Mode.DETACHED ? 4 : 1;
            if (calls > lastDeflecting || (mode == Mode.DETACHED && calls == 3)) {
                result.text = "I will investigate and fix the failure.";
            }
            if (getOutputConsumer() != null) getOutputConsumer().accept(result.text);
            return result;
        }
    }
}
