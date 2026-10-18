package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.main.chat.ChatRepl.TimelineLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatTimelineLifecycleTest {
    @TempDir Path root;

    private ConversationActivityService service(ActivityStorage storage) {
        return new ConversationActivityService(storage, List.of());
    }

    @Test void freshRunsKeepConversationAndCloseExactlyOnce() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        TimelineLifecycle first = new TimelineLifecycle(service(storage), "stable", root, false);
        first.close();
        first.close();
        try (TimelineLifecycle resumed = new TimelineLifecycle(service(storage), "stable", root, true)) {
            assertEquals(first.identity().key(), resumed.identity().key());
            assertNotEquals(first.identity().runId(), resumed.identity().runId());
            List<ActivityEvent> events = new ActivityJournalReader(storage.eventsPath(resumed.identity()))
                    .read(ActivityReadBudget.DEFAULT).events();
            assertEquals(List.of("run.started", "run.closed", "run.started", "run.resumed"),
                    events.stream().map(ActivityEvent::eventType).toList());
            assertEquals("existing-transcript-observed", events.get(3).attributes().get("basis"));
            assertEquals("not-asserted", events.get(3).attributes().get("restoration"));
        }
    }

    @Test void toolAndTurnPayloadsCarryOnlyBoundedMetadataAndCallbackIds() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        try (TimelineLifecycle runtime = new TimelineLifecycle(service(storage), "tools", root, false)) {
            runtime.toolStarted("call-1", "bash");
            runtime.toolFinished("call-1", "bash", false);
            runtime.toolDenied("call-2", "write");
            String turn = runtime.turnStarted("dispatch-owner");
            runtime.turnFinished(turn);
            List<ActivityEvent> events = new ActivityJournalReader(storage.eventsPath(runtime.identity()))
                    .read(ActivityReadBudget.DEFAULT).events();
            assertEquals("call-1", events.get(1).operationId());
            assertEquals("succeeded", events.get(2).attributes().get("outcome"));
            assertEquals("denied", events.get(3).attributes().get("outcome"));
            assertEquals(turn, events.get(5).operationId());
            assertEquals("released", events.get(5).attributes().get("state"));
            assertFalse(events.get(5).attributes().containsKey("outcome"));
            assertTrue(events.stream().allMatch(event -> event.runId().equals(runtime.identity().runId())));
            assertTrue(events.stream().noneMatch(event -> event.attributes().containsKey("input")));
        }
    }

    @Test void failedAndMissingToolResultsDoNotClaimSuccessOrFinishAfterClose() throws Exception {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        TimelineLifecycle runtime = new TimelineLifecycle(service(storage), "failures", root, false);
        runtime.toolFinished("failed-call", "bash", true);
        runtime.toolFinished("unknown-call", "bash", null);
        runtime.close();
        runtime.toolFinished("late-call", "bash", false);
        List<ActivityEvent> events = new ActivityJournalReader(storage.eventsPath(runtime.identity()))
                .read(ActivityReadBudget.DEFAULT).events();
        assertEquals(4, events.size());
        assertEquals("failed", events.get(1).attributes().get("outcome"));
        assertEquals("unknown", events.get(2).attributes().get("outcome"));
        assertEquals("run.closed", events.get(3).eventType());
    }

    @Test void crossSessionAndAmbiguousContextsCannotBorrowRunOrActor() {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        try (TimelineLifecycle runtime = new TimelineLifecycle(service(storage), "actor", root, false)) {
            assertEquals(runtime.identity().runId(),
                    TimelineLifecycle.producer("actor", "actor", root).identity().runId());
            var historical = TimelineLifecycle.producer("history", "actor", root).identity();
            assertEquals("history", historical.conversationId());
            assertEquals("", historical.runId());
            assertEquals("", historical.actorId());
            try (TimelineLifecycle duplicate = new TimelineLifecycle(service(storage), "actor", root, true)) {
                assertEquals("", TimelineLifecycle.producer("actor", "actor", root).identity().runId());
            }
            assertEquals("", TimelineLifecycle.producer("actor", "actor", root.resolve("other"))
                    .identity().runId());
        }
        assertEquals("", TimelineLifecycle.producer("actor", "actor", root).identity().runId());
    }

    @Test void milestoneMetadataIsRecorderEvidenceNotTestProcessOwnership() {
        ActivityStorage storage = new ActivityStorage(root.resolve("conversations"));
        try (TimelineLifecycle runtime = new TimelineLifecycle(service(storage), "recorder", root, false)) {
            Map<String, Object> milestone = new LinkedHashMap<>(Map.of("passing", true, "id", "old"));
            TimelineLifecycle.producer("recorder", "recorder", root).annotateMilestone(milestone);
            assertEquals("recorder", milestone.get("sessionId"));
            assertEquals(runtime.identity().runId(), milestone.get("runId"));
            assertEquals("recording-session-context", milestone.get("correlationBasis"));
            assertEquals("old", milestone.get("id"));
            assertFalse(milestone.containsKey("pid"));
            assertFalse(milestone.containsKey("invocationId"));
        }
    }
}
