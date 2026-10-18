package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.context.ReviewedContextRetirement;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ReviewedCompactTest {
    @TempDir Path working;
    private final ObjectMapper json = JsonUtils.newStandardMapper();
    private record Fixture(AgenticChatLoop loop, DirectLlmClient client, ConversationLedger ledger) { }

    private Fixture fixture(boolean recovery) throws Exception {
        var registry = new ToolRegistry(json);
        if (recovery) registry.register(new FetchResultTool(new ToolResultReferenceCache(working)));
        var client = new DirectLlmClient(new ChatConfig("custom", null, "review-test", "http://unused.invalid"), json);
        var loop = new AgenticChatLoop(null, json, registry, new PermissionService(), new AgentRegistry(), working, client, null);
        String session = "review-command-" + java.util.UUID.randomUUID();
        loop.configureConversationSession(session);
        var ledger = (ConversationLedger) field(loop, "conversationLedger");
        var archive = new DurableToolResultStore(KompileHome.homeDirectory().toPath().resolve("conversations"));
        String raw = "actual raw output\n".repeat(300);
        String handle = archive.store(session, "bash", raw, Map.of());
        var outcome = new CompactionService.ToolOutcome(true, true, "{\"command\":\"effective program\"}",
                handle, ReviewedContextRetirement.sha256(raw), raw.length());
        ledger.append(CompactionService.ConversationEntry.user("old scope " + "constraints ".repeat(500)));
        ledger.append(CompactionService.ConversationEntry.toolCall("bash", "program", "{\"command\":\"requested program\"}"));
        ledger.append(CompactionService.ConversationEntry.toolResult("bash", "program", "old result", outcome));
        ledger.append(CompactionService.ConversationEntry.assistant("old conclusion"));
        ledger.append(CompactionService.ConversationEntry.user("keep latest request exact 😀"));
        loop.rebuildDirectHistoryForProviderSwitch();
        return new Fixture(loop, client, ledger);
    }

    private ObjectNode approval(String prepared) throws Exception {
        String prefix = "/compact reviewed ";
        String line = prepared.substring(prepared.indexOf(prefix) + prefix.length()).split("\n", 2)[0];
        return ((ObjectNode) json.readTree(line)).put("review", "Reviewed failed program and all evidence; scope closed with no continuing dependencies.");
    }

    @Test void explicitReviewRetiresOlderPairsAndPreservesNewestExchange() throws Exception {
        var f = fixture(true);
        var before = f.ledger.snapshot();
        String wireBefore = field(f.client, "conversationHistory").toString();
        String prepared = f.loop.reviewedCompact("prepare");
        assertUnchanged(before, f.ledger.snapshot());
        assertEquals(wireBefore, field(f.client, "conversationHistory").toString());
        assertTrue(prepared.contains("Inspect the exact candidate"));
        assertTrue(f.loop.reviewedCompact(approval(prepared).toString()).startsWith("Reviewed scope retired"));
        assertEquals(before.allEvents(), f.ledger.snapshot().allEvents());
        assertEquals("reviewed-prefix-v1", f.ledger.snapshot().checkpoint().strategy());
        assertEquals(2, f.ledger.snapshot().activeEntries().size());
        assertEquals("keep latest request exact 😀", f.ledger.snapshot().activeEntries().get(1).content);
        String wire = field(f.client, "conversationHistory").toString();
        assertFalse(wire.contains("tool_calls"));
        assertTrue(wire.contains("keep latest request exact"));
        assertThrows(Exception.class, () -> f.loop.reviewedCompact(approval(prepared).toString()));
    }

    @Test void staleOrCancelledApprovalLeavesLedgerAndProviderHistoryUnchanged() throws Exception {
        var f = fixture(true);
        var approval = approval(f.loop.reviewedCompact("prepare"));
        f.ledger.append(CompactionService.ConversationEntry.assistant("new activity"));
        var stale = f.ledger.snapshot();
        String wire = field(f.client, "conversationHistory").toString();
        assertThrows(IllegalArgumentException.class, () -> f.loop.reviewedCompact(approval.toString()));
        assertUnchanged(stale, f.ledger.snapshot());
        assertEquals(wire, field(f.client, "conversationHistory").toString());
        var fresh = approval(f.loop.reviewedCompact("prepare"));
        Field cancel = AgenticChatLoop.class.getDeclaredField("cancelSignal");
        cancel.setAccessible(true); cancel.set(f.loop, new AtomicBoolean(true));
        assertThrows(IllegalStateException.class, () -> f.loop.reviewedCompact(fresh.toString()));
        assertUnchanged(stale, f.ledger.snapshot());
        assertEquals(wire, field(f.client, "conversationHistory").toString());
    }

    @Test void duplicateJsonKeysAndTrailingObjectsCannotAuthorizeRetirement() throws Exception {
        var f = fixture(true);
        var approval = approval(f.loop.reviewedCompact("prepare"));
        var before = f.ledger.snapshot();
        String duplicate = approval.toString().replace("\"closed\":true", "\"closed\":false,\"closed\":true");
        assertThrows(java.io.IOException.class, () -> f.loop.reviewedCompact(duplicate));
        assertThrows(java.io.IOException.class, () -> f.loop.reviewedCompact(approval + " {}"));
        assertUnchanged(before, f.ledger.snapshot());
    }

    @Test void unavailableRecoveryCannotRetireContext() throws Exception {
        var f = fixture(false);
        var before = f.ledger.snapshot();
        assertThrows(IllegalArgumentException.class, () -> f.loop.reviewedCompact("prepare"));
        assertUnchanged(before, f.ledger.snapshot());
    }

    @Test void encryptedProviderItemsRemainUntouched() throws Exception {
        var f = fixture(true);
        @SuppressWarnings("unchecked") var history = (List<ObjectNode>) field(f.client, "conversationHistory");
        history.add(json.createObjectNode().put("type", "reasoning").put("encrypted_content", "opaque"));
        String wire = history.toString();
        var before = f.ledger.snapshot();
        assertThrows(IllegalArgumentException.class, () -> f.loop.reviewedCompact("prepare"));
        assertEquals(wire, history.toString()); assertUnchanged(before, f.ledger.snapshot());
    }

    @Test void executionEvidenceCapturesOnlyKnownTerminalOperationsAndActualFlags() throws Exception {
        var f = fixture(true);
        var method = AgenticChatLoop.class.getDeclaredMethod("captureToolOutcome", AgenticChatLoop.ToolCallRequest.class, ToolResult.class);
        method.setAccessible(true);
        var call = new AgenticChatLoop.ToolCallRequest();
        call.id = "capture"; call.name = "bash"; call.arguments = json.createObjectNode().put("command", "effective ".repeat(200));
        var result = new ToolResult("bash", "keyword-free raw response", Map.of("exitCode", 1), true);
        var outcome = (CompactionService.ToolOutcome) method.invoke(f.loop, call, result);
        assertTrue(outcome.error()); assertTrue(outcome.terminal());
        assertEquals(1, outcome.exitCode());
        for (Map<String, Object> metadata : List.<Map<String, Object>>of(
                Map.of("exitCode", -1), Map.of("exitCode", 0, "timedOut", true), Map.of("exitCode", 0, "aborted", true))) {
            assertFalse(((CompactionService.ToolOutcome) method.invoke(f.loop, call,
                    new ToolResult("bash", "partial", metadata, true))).terminal());
        }
        assertEquals(call.arguments.toString(), outcome.executedArguments());
        var archive = new DurableToolResultStore(KompileHome.homeDirectory().toPath().resolve("conversations"));
        assertTrue(archive.verify(outcome.rawResultHandle(), outcome.rawDigest(), outcome.rawChars()));
        call.name = "task";
        assertFalse(((CompactionService.ToolOutcome) method.invoke(f.loop, call, result)).terminal());
        call.name = "bash"; ((ObjectNode) call.arguments).put("background", true);
        assertFalse(((CompactionService.ToolOutcome) method.invoke(f.loop, call, result)).terminal());
        ((ObjectNode) call.arguments).remove("background");
        assertFalse(((CompactionService.ToolOutcome) method.invoke(f.loop, call,
                ToolResult.success("bash", "not yet terminal", Map.of()))).terminal());
    }

    private static void assertUnchanged(ConversationLedger.Snapshot expected, ConversationLedger.Snapshot actual) {
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.checkpoint(), actual.checkpoint());
        assertEquals(expected.allEvents(), actual.allEvents());
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
