package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.activity.*;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class TestMilestoneDisplayTest {
    @TempDir Path root;

    @Test void automaticAndMcpRecordsReachLiveCardsAndSavedTranscriptWithoutModelCooperation() throws Exception {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var service = new ConversationActivityService(storage, List.of());
        var cards = new ArrayList<ChatDisplayBlock>();
        var history = new ChatHistory("milestone-chat");
        history.open(null, "coder", false, root);
        history.logUserMessage("Run tests");
        try (var runtime = new ChatRepl.TimelineLifecycle(service, "milestone-chat", root, false);
             var display = new TestMilestoneDisplay(storage, runtime.identity(), new TerminalRenderer(false), cards::add)) {
            var context = new ToolContext("milestone-chat", AgentConfig.builder("coder").enabledTools(Set.of("*")).build(),
                    new PermissionService(), root, new ToolRegistry(JsonUtils.standardMapper()));
            var request = JsonUtils.standardMapper().createObjectNode().put("action", "record")
                    .put("commit", "not-recorded").put("branch", "fixture").put("passed", 2).put("total_tests", 2);
            assertFalse(new TestMilestoneTool().execute(request, context).isError());
            try (var run = TestMilestoneCapture.start("pytest", root, "milestone-chat", "execution", text -> fail(text))) {
                run.accept("3 passed in 0.1s");
                assertNotNull(run.finish(0, false, false, null));
            }
            display.poll();
            assertEquals(2, cards.size());
            assertTrue(cards.get(0).summary().contains("manual (agent-reported)"));
            assertTrue(cards.get(1).summary().contains("automatic"));
            assertTrue(cards.get(1).summary().contains("passed=3"));
            assertFalse(cards.get(1).error());
            assertTrue(cards.stream().allMatch(card -> !card.sections().isEmpty()));
            display.poll();
            assertEquals(2, cards.size(), "A redraw must not replay a consumed milestone");
            String transcript = history.readTranscript();
            assertEquals(1, transcript.lines().filter(line -> line.startsWith("[system] Test milestone recorded:")).count());
            assertEquals(1, transcript.lines().filter(line -> line.startsWith("[system] Automatic test milestone recorded:")).count());
            assertTrue(transcript.contains("basis=agent-reported"));
            assertEquals(1, history.readTurns().size(), "Milestone evidence is not a fabricated model turn");
        } finally { history.close(); }
    }

    @Test void independentWriterIsObservedButOtherSessionsAndNonMilestoneEventsAreNot() throws Exception {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("owner", root);
        var cards = new ArrayList<ChatDisplayBlock>();
        try (var display = new TestMilestoneDisplay(storage, identity, new TerminalRenderer(false), cards::add)) {
            display.poll();
            var writer = new ConversationActivityService(storage, List.of());
            writer.record(identity, "tool.finished", "", "tool", null, Map.of());
            writer.record(ActivityIdentity.conversation("other", root), "test.milestone.recorded", "", "foreign", null, Map.of());
            writer.record(identity, "test.milestone.recorded", "", "manual-id", null,
                    Map.of("source", "manual", "outcome", "failed", "failed", "1"));
            display.poll();
            assertEquals(1, cards.size());
            assertEquals("test-milestone:manual-id", cards.get(0).key());
            assertTrue(cards.get(0).error());
            assertTrue(cards.get(0).summary().contains("failed=1"));
            display.close();
            writer.record(identity, "test.milestone.recorded", "", "late", null, Map.of());
            display.poll();
            assertEquals(1, cards.size(), "A closed chat must never receive later cards");
        }
    }

    @Test void separateMcpJvmPublishesToTheSameHarnessJournal() throws Exception {
        var storage = new ActivityStorage();
        var identity = ActivityIdentity.conversation("mcp-parent", root);
        var cards = new ArrayList<ChatDisplayBlock>();
        try (var display = new TestMilestoneDisplay(storage, identity, new TerminalRenderer(false), cards::add)) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Duser.home=" + System.getProperty("user.home"), "-cp", System.getProperty("java.class.path"),
                    McpWriter.class.getName(), root.toString()).inheritIO().start();
            try {
                assertTrue(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "MCP writer did not exit");
                assertEquals(0, child.exitValue());
            } finally { if (child.isAlive()) child.destroyForcibly(); }
            display.poll();
            assertEquals(1, cards.size());
            assertTrue(cards.get(0).summary().contains("manual (agent-reported)"));
            assertTrue(cards.get(0).summary().contains("passed=2"));
        }
    }

    /** Test fixture with the same independent-JVM persistence boundary as the stdio tool server. */
    public static class McpWriter {
        public static void main(String[] args) throws Exception {
            var context = new ToolContext("mcp-parent", AgentConfig.builder("coder").enabledTools(Set.of("*")).build(),
                    new PermissionService(), Path.of(args[0]), new ToolRegistry(JsonUtils.standardMapper()));
            var request = JsonUtils.standardMapper().createObjectNode().put("action", "record")
                    .put("commit", "not-recorded").put("branch", "fixture").put("passed", 2).put("total_tests", 2);
            ToolResult result = new TestMilestoneTool().execute(request, context);
            if (result.isError()) throw new IllegalStateException(result.getOutput());
        }
    }

    @Test void captureFailuresAreHarnessWarningsNotPassingCards() throws Exception {
        var storage = new ActivityStorage(root.resolve("conversations"));
        var identity = ActivityIdentity.conversation("capture-owner", root);
        var cards = new ArrayList<ChatDisplayBlock>();
        var writer = new ConversationActivityService(storage, List.of());
        writer.record(identity, "test.milestone.capture_failed", "", "failed-capture", null,
                Map.of("message", "Automatic test milestone could not be saved"));
        try (var display = new TestMilestoneDisplay(storage, identity, new TerminalRenderer(false), cards::add)) {
            display.poll();
            assertEquals(1, cards.size());
            assertTrue(cards.get(0).error());
            assertTrue(cards.get(0).summary().contains("could not be saved"));
            assertFalse(cards.get(0).summary().contains("passed"));
        }
    }
}
