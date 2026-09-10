package ai.kompile.cli.main.chat.activity;

import ai.kompile.app.services.diffindex.DiffIndexEntry;
import ai.kompile.cli.main.chat.enforcer.JudgementRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationActivityServiceTest {
    private final ObjectMapper mapper = ai.kompile.cli.common.util.JsonUtils.standardMapper();

    @Test
    void composesExistingTranscriptAndMetricsWithPresenceAwareValues(@TempDir Path temp)
            throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = new ActivityIdentity("kompile", "session-1", "run-1",
                "actor-1", "", temp.toString());
        Files.createDirectories(storage.conversationsRoot());
        Files.writeString(storage.transcriptPath(identity),
                "──── Conversation: session-1 ────\n"
                        + "Started: 2026-09-01T00:00:00Z\nAgent: coder\n\n"
                        + "> Fix the build\n\n< Done\n\n", StandardCharsets.UTF_8);
        var metrics = mapper.createObjectNode();
        metrics.putObject("session").put("sessionId", "session-1")
                .put("started", "2026-09-01T00:00:00Z")
                .put("ended", "2026-09-01T00:01:00Z").put("durationSeconds", 60);
        metrics.putObject("tokens").put("total", 12);
        metrics.putObject("tools").put("totalErrors", 1);
        Files.writeString(storage.metricsPath(identity), mapper.writeValueAsString(metrics));

        ConversationActivityService service = new ConversationActivityService(storage,
                Clock.fixed(Instant.parse("2026-09-01T00:02:00Z"), ZoneOffset.UTC), null);

        ConversationActivitySummary summary = service.session(identity).orElseThrow();

        assertEquals("Fix the build", summary.goal());
        assertTrue(summary.tokens().known());
        assertEquals(12, summary.tokens().value());
        assertEquals(60_000L, summary.elapsed().value());
        assertEquals(1, summary.issues().value());
        assertEquals("UNKNOWN", summary.executionState(),
                "a metrics save timestamp is not proof of a clean session exit");
        assertFalse(summary.blocking().known(), "missing blocking evidence must remain unknown");
    }

    @Test
    void annotationsAndStableIdentitySurviveSummaryReload(@TempDir Path temp) {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("same-session", temp);
        ConversationActivityService service = new ConversationActivityService(storage,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC), null);

        service.annotateGoal(identity, "Ship the parser fix", "agent");
        service.annotateOutcome(identity, "ACHIEVED", "Agent claimed success", "agent", List.of());

        ConversationActivitySummary claimed = service.session(identity).orElseThrow();
        assertEquals("UNVERIFIED", claimed.outcome());
        assertEquals("AGENT_CLAIM", claimed.evidenceBasis());

        service.confirmOutcome(identity, "ACHIEVED", "User confirmed", "user", List.of());
        ConversationActivitySummary loaded = service.session(identity).orElseThrow();

        assertEquals("kompile:same-session", identity.key());
        assertEquals("Ship the parser fix", loaded.goal());
        assertEquals("ACHIEVED", loaded.outcome());
        assertEquals("USER_CONFIRMATION", loaded.evidenceBasis());
        assertEquals(3, loaded.annotations().size());
    }

    @Test
    void journalReadRecoversValidPrefixAndReportsTruncatedFinalRecord(@TempDir Path temp)
            throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("journal-session", temp);
        ConversationActivityService service = new ConversationActivityService(storage);
        service.record(identity, "session.start", "actor", "", null, Map.of("stage", "test"));
        service.record(identity, "operation.end", "actor", "op-1", null, Map.of());
        Files.writeString(storage.eventsPath(identity), "{\"schemaVersion\":1",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        ConversationActivityDetail detail = service.detail(identity, new ActivityReadBudget(16_384, 10, 10, 10));

        assertEquals(2, detail.events().size());
        assertTrue(detail.truncated());
        assertTrue(detail.malformedEvents() >= 1);
    }

    @Test
    void safeSourceRefsRejectTraversal(@TempDir Path temp) {
        assertThrows(Exception.class, () -> ActivitySourceRef.resolve("../outside.txt", temp));
    }

    @Test
    void adaptersKeepDiffFailuresOutOfSuccessfulEditTotals() {
        ActivityIdentity identity = ActivityIdentity.conversation("session", null);
        DiffIndexActivityAdapter adapter = new DiffIndexActivityAdapter(() -> List.of(
                new DiffIndexActivityAdapter.DiffObservation("1", "session", true, false, 2, 1, null),
                new DiffIndexActivityAdapter.DiffObservation("2", "session", false, false, 0, 0, null),
                new DiffIndexActivityAdapter.DiffObservation("3", "other", true, false, 4, 4, null)));

        ActivityEvidence evidence = adapter.read(identity, ActivityReadBudget.DEFAULT);

        assertEquals(1, evidence.edits().value());
        assertEquals(1, evidence.issues().value());
    }

    @Test
    void concurrentAnnotationWritersRetainBothUpdates(@TempDir Path temp) throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("concurrent", temp);
        ConversationActivityService first = new ConversationActivityService(storage);
        ConversationActivityService second = new ConversationActivityService(storage);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 20; i++) {
                final int index = i;
                pool.submit(() -> (index % 2 == 0 ? first : second)
                        .annotateGoal(identity, "goal-" + index, "user"));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        ConversationActivitySummary summary = new ConversationActivityService(storage)
                .session(identity).orElseThrow();
        assertEquals(20, summary.annotations().size());
    }

    @Test
    void repeatedInjectedReadsDoNotInflateAnnotationsOrEvidence(@TempDir Path temp) {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("repeat", temp);
        ActivityAnnotation annotation = ActivityAnnotation.goal("goal-1", "repeatable", "agent",
                Instant.parse("2026-09-01T00:00:00Z"));
        ActivitySourceRef source = ActivitySourceRef.logical("test", "source-1", "fixture");
        ActivityEvidence evidence = new ActivityEvidence("fixture", ActivityCoverage.COMPLETE,
                null, null, null, null, null, null, null, List.of(source), List.of(annotation),
                Map.of(), List.of());
        ConversationActivityService service = new ConversationActivityService(storage,
                Clock.systemUTC(), List.of(id -> (ignored, budget) -> evidence));

        service.session(identity);
        ConversationActivitySummary summary = service.session(identity).orElseThrow();

        assertEquals(1, summary.annotations().size());
        assertEquals(1, summary.evidence().size());
    }

    @Test
    void projectLimitIsAppliedAfterProjectFiltering(@TempDir Path temp) throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        Path projectA = temp.resolve("project-a").toAbsolutePath().normalize();
        Path projectB = temp.resolve("project-b").toAbsolutePath().normalize();
        ActivityIdentity identityA = ActivityIdentity.conversation("project-a-session", projectA);
        ActivityIdentity identityB = ActivityIdentity.conversation("project-b-session", projectB);
        Files.createDirectories(storage.conversationsRoot());
        Files.writeString(storage.transcriptPath(identityA),
                "Started: 2026-09-01T00:00:00Z\nCWD: " + projectA + "\n> A\n\n", StandardCharsets.UTF_8);
        Files.writeString(storage.transcriptPath(identityB),
                "Started: 2026-09-01T00:01:00Z\nCWD: " + projectB + "\n> B\n\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(storage.transcriptPath(identityB),
                FileTime.fromMillis(System.currentTimeMillis() + 2_000L));

        List<ConversationActivitySummary> summaries = new ConversationActivityService(storage)
                .projectSummaries(projectA, 1);

        assertEquals(1, summaries.size());
        assertEquals("project-a-session", summaries.get(0).identity().conversationId());
    }

    @Test
    void storageRejectsSymlinkedParentTraversal(@TempDir Path temp) throws Exception {
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Path linkedParent = temp.resolve("linked-parent");
        Files.createSymbolicLink(linkedParent, outside);

        assertThrows(IllegalArgumentException.class,
                () -> new ActivityStorage(linkedParent.resolve("conversations")));
    }

    @Test
    void storageEncodingAndDiscoveryKeepSanitizedIdsDistinct(@TempDir Path temp) throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity slash = ActivityIdentity.conversation("a/b", temp);
        ActivityIdentity underscore = ActivityIdentity.conversation("a_b", temp);
        Files.createDirectories(storage.conversationsRoot());
        Files.writeString(storage.transcriptPath(slash), "Started: 2026-09-01T00:00:00Z\n", StandardCharsets.UTF_8);
        Files.writeString(storage.transcriptPath(underscore), "Started: 2026-09-01T00:00:00Z\n", StandardCharsets.UTF_8);

        assertNotEquals(storage.transcriptPath(slash), storage.transcriptPath(underscore));
        assertEquals(2, storage.knownIdentities(10).size());
    }

    @Test
    void recorderSequenceContinuesAcrossRecorderRestart(@TempDir Path temp) throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = new ActivityIdentity("kompile", "restart", "run-7",
                "actor-7", "parent", temp.toString());
        new ActivityRecorder(storage).record(identity, "session.start", "actor-7", "", null, Map.of());
        new ActivityRecorder(storage).record(identity, "session.end", "actor-7", "", null, Map.of());

        List<ActivityEvent> events = new ActivityJournalReader(storage.eventsPath(identity))
                .read(ActivityReadBudget.DEFAULT).events();
        assertEquals(List.of(1L, 2L), events.stream().map(ActivityEvent::sequence).toList());
        assertEquals("run-7", events.get(0).runId());
        assertEquals("actor-7", events.get(0).actorId());
    }

    @Test
    void diffIndexEntriesWithoutResultProvenanceStayUnverified() {
        DiffIndexEntry entry = DiffIndexEntry.builder().id("diff-1").sessionId("session")
                .linesAdded(3).linesRemoved(2).diffType("edit").build();
        ActivityEvidence evidence = DiffIndexActivityAdapter.fromEntries(() -> List.of(entry))
                .read(ActivityIdentity.conversation("session", null), ActivityReadBudget.DEFAULT);

        assertEquals(0, evidence.edits().value());
        assertEquals(ActivityCoverage.PARTIAL, evidence.coverage());
        assertEquals("1", evidence.attributes().get("unknownResults"));
        assertEquals("3", evidence.attributes().get("indexedLinesAdded"));
    }

    @Test
    void corruptSummaryAndLargeMalformedJournalRemainBounded(@TempDir Path temp) throws Exception {
        ActivityStorage storage = new ActivityStorage(temp.resolve("conversations"));
        ActivityIdentity identity = ActivityIdentity.conversation("corrupt", temp);
        Files.createDirectories(storage.sidecarDirectory(identity));
        Files.writeString(storage.summaryPath(identity), "{not-json", StandardCharsets.UTF_8);
        ConversationActivitySummary summary = new ConversationActivityService(storage)
                .session(identity).orElseThrow();
        assertTrue(summary.warnings().stream().anyMatch(w -> w.contains("unreadable")));

        StringBuilder malformed = new StringBuilder();
        for (int i = 0; i < 50; i++) malformed.append("{bad-" + i + "}\n");
        Files.writeString(storage.eventsPath(identity), malformed.toString(), StandardCharsets.UTF_8);
        ActivityJournalReader.ReadResult result = new ActivityJournalReader(storage.eventsPath(identity))
                .read(new ActivityReadBudget(64 * 1024, 100, 10, 10));
        assertEquals(50, result.malformedRecords());
        assertTrue(result.warnings().size() <= 16);
    }

    @Test
    void judgementAdapterCountsOnlyObservedResultBlocks(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("judgements.jsonl");
        JudgementRecord blocked = JudgementRecord.builder()
                .timestamp("2026-09-01T00:00:00Z").sessionId("session")
                .phase("RESULT").status("BLOCKED").build();
        JudgementRecord recommendation = JudgementRecord.builder()
                .timestamp("2026-09-01T00:00:01Z").sessionId("session")
                .phase("JUDGE_TOOL").status("BLOCKED").build();
        Files.writeString(file, mapper.writeValueAsString(blocked) + "\n"
                + mapper.writeValueAsString(recommendation) + "\n", StandardCharsets.UTF_8);

        ActivityEvidence evidence = new JudgementActivityReader(file, temp)
                .read(ActivityIdentity.conversation("session", temp), ActivityReadBudget.DEFAULT);

        assertEquals("1", evidence.attributes().get("actualBlocked"));
        assertEquals(1, evidence.issues().value());
    }
}
