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

@ai.kompile.cli.main.chat.testing.TemporaryUserHome
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

    @Test void aTransientDeliveryFailureRetriesOnlyTheJournalEvent() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        var service = org.mockito.Mockito.spy(new ConversationActivityService(storage, List.of()));
        org.mockito.Mockito.doReturn(false).doCallRealMethod().when(service).recordConfirmed(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("test.milestone.recorded"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        try (TimelineLifecycle runtime = new TimelineLifecycle(service, "retry-owner", root, false)) {
            ToolResult result = new TestMilestoneTool().execute(request("record"), context("retry-owner"));
            assertFalse(result.isError());
            assertFalse(result.getMetadata().containsKey("testMilestoneWarnings"));
            String id = result.getMetadata().get("id").toString();
            org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2)).recordConfirmed(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("test.milestone.recorded"),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(id),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
            assertEquals(1, new ActivityJournalReader(storage.eventsPath(runtime.identity()))
                    .read(ActivityReadBudget.DEFAULT).events().stream()
                    .filter(event -> event.eventType().equals("test.milestone.recorded") && event.operationId().equals(id)).count());
            assertEquals(1, ai.kompile.cli.insights.TestMilestoneReader.readAll(
                    root.resolve(".kompile/test-milestones/milestones")).size());
        }
    }

    @Test void contendedJournalIsReportedWithoutLosingOrDuplicatingTheSavedMilestone() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        var service = new ConversationActivityService(storage, List.of());
        var history = new ai.kompile.cli.main.chat.ChatHistory("contended-owner");
        history.open(null, "coder", false, root);
        history.logUserMessage("Run tests");
        try (TimelineLifecycle runtime = new TimelineLifecycle(service, "contended-owner", root, false)) {
            Path journal = storage.eventsPath(runtime.identity());
            Path lockFile = journal.resolveSibling(journal.getFileName() + ".lock");
            ToolResult result;
            try (var channel = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                result = new TestMilestoneTool().execute(request("record"), context("contended-owner"));
                assertFalse(result.isError(), "The milestone itself was saved; do not request a duplicate record");
                assertNotNull(result.getMetadata().get("testMilestoneWarnings"));
                assertTrue(result.getOutput().contains("live harness delivery could not be recorded after retry"));
                assertEquals(2, service.recordingWarnings().stream()
                        .filter(text -> text.contains("bounded journal-lock wait")).count());
                assertTrue(new ActivityJournalReader(journal).read(ActivityReadBudget.DEFAULT).events().stream()
                        .noneMatch(event -> event.eventType().equals("test.milestone.recorded")));
            }
            String id = result.getMetadata().get("id").toString();
            assertTrue(history.readTranscript().contains("Test milestone " + id
                    + " was saved, but live harness delivery could not be recorded"));
            Path milestoneFile = root.resolve(".kompile/test-milestones/milestones/" + id + ".json");
            assertTrue(Files.isRegularFile(milestoneFile));
            // Once contention clears, retry delivery of the same saved run, not the tests/save.
            assertTrue(TimelineLifecycle.producer("contended-owner", "contended-owner", root)
                    .recordSavedConfirmed("test.milestone.recorded", id, milestoneFile, root,
                            java.util.Map.of("outcome", "passed")));
            assertEquals(1, new ActivityJournalReader(journal).read(ActivityReadBudget.DEFAULT).events().stream()
                    .filter(event -> event.eventType().equals("test.milestone.recorded") && event.operationId().equals(id)).count());
            assertEquals(1, ai.kompile.cli.insights.TestMilestoneReader.readAll(milestoneFile.getParent()).size());
        } finally { history.close(); }
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
