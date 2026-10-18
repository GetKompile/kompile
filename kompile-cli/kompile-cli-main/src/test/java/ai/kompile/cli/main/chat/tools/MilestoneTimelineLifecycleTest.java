package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatRepl.TimelineLifecycle;
import ai.kompile.cli.main.chat.activity.*;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MilestoneTimelineLifecycleTest {
    @TempDir Path root;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private ToolContext context(String session) {
        return new ToolContext(session, AgentConfig.builder("coder").enabledTools(Set.of("*")).build(),
                new PermissionService(), root, new ToolRegistry(mapper));
    }

    private ObjectNode request(String action) {
        // Explicit non-hash commit/branch prevent git subprocesses in these focused tests.
        return mapper.createObjectNode().put("action", action).put("commit", "not-recorded")
                .put("branch", "fixture").put("total_tests", 2).put("passed", 2);
    }

    @Test void newSavedMilestoneCarriesRecorderIdentityAndEmitsEvidence() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        try (TimelineLifecycle runtime = new TimelineLifecycle(new ConversationActivityService(storage, List.of()),
                "milestone-owner", root, false)) {
            ToolResult result = new TestMilestoneTool().execute(request("record"), context("milestone-owner"));
            assertFalse(result.isError());
            String id = result.getMetadata().get("id").toString();
            var saved = mapper.readTree(root.resolve(".kompile/test-milestones/milestones/" + id + ".json").toFile());
            assertEquals("milestone-owner", saved.path("sessionId").asText());
            assertEquals(runtime.identity().runId(), saved.path("runId").asText());
            assertEquals("recording-session-context", saved.path("correlationBasis").asText());
            assertTrue(saved.path("passing").asBoolean());
            assertFalse(saved.has("pid"));
            assertFalse(saved.has("invocationId"));
            ActivityEvent event = new ActivityJournalReader(storage.eventsPath(runtime.identity()))
                    .read(ActivityReadBudget.DEFAULT).events().stream()
                    .filter(value -> value.eventType().equals("test.milestone.recorded")).findFirst().orElseThrow();
            assertEquals(id, event.operationId());
            assertTrue(event.source().available());
            assertEquals(id, event.source().identifier());
            assertEquals("passed", event.attributes().get("outcome"));
            assertEquals("recorder-not-test-process", event.attributes().get("attribution"));
        }
    }

    @Test void failedWriteDoesNotRecordMilestoneEvidence() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        try (TimelineLifecycle runtime = new TimelineLifecycle(new ConversationActivityService(storage, List.of()),
                "failed-owner", root, false)) {
            Files.createDirectories(root.resolve(".kompile/test-milestones"));
            Files.writeString(root.resolve(".kompile/test-milestones/milestones"), "not a directory");
            assertTrue(new TestMilestoneTool().execute(request("fail"), context("failed-owner")).isError());
            assertTrue(new ActivityJournalReader(storage.eventsPath(runtime.identity()))
                    .read(ActivityReadBudget.DEFAULT).events().stream()
                    .noneMatch(event -> event.eventType().equals("test.milestone.recorded")));
        }
    }
}
