package ai.kompile.cli.main.chat.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class TimelineJudgeEvidenceTest {
    @TempDir Path root;

    private Path log() throws Exception {
        Path log = root.resolve("SessionCase/judgements.jsonl");
        Files.createDirectories(log.getParent());
        return log;
    }

    private String row(String session, String time, String status) {
        return "{\"sessionId\":\"" + session + "\",\"timestamp\":\"" + time
                + "\",\"phase\":\"RESULT\",\"status\":\"" + status
                + "\",\"judgeRawResponse\":\"secret raw response\"}\n";
    }

    @Test void projectsOnlyExactSessionWithHonestCorrelationAndNoRawPayload() throws Exception {
        Files.writeString(log(), row("SessionCase", "2026-10-10T10:00:00Z", "BLOCKED")
                + row("other", "2026-10-10T10:01:00Z", "ACCEPTED"));
        var query = SessionTimelineQuery.recent(ActivityIdentity.conversation("SessionCase", root));
        var result = new TimelineJudgeEvidence(root).recent(query);
        assertEquals(1, result.events().size());
        var event = result.events().get(0);
        assertEquals("judge.recorded", event.eventType());
        assertEquals("", event.runId());
        assertEquals("unknown", event.attributes().get("correlation"));
        assertFalse(event.toString().contains("secret"));
        assertTrue(event.source().available());
    }

    @Test void partialLineIsNotInventedAndArrivesOnNextRead() throws Exception {
        String record = row("SessionCase", "2026-10-10T10:00:00Z", "ACCEPTED");
        Files.writeString(log(), record.stripTrailing());
        var evidence = new TimelineJudgeEvidence(root);
        var query = SessionTimelineQuery.recent(ActivityIdentity.conversation("SessionCase", root));
        assertTrue(evidence.recent(query).events().isEmpty());
        Files.writeString(log(), "\n", StandardOpenOption.APPEND);
        assertEquals(1, evidence.recent(query).events().size());
    }

    @Test void boundAndMissingRunAssociationRemainExplicit() throws Exception {
        Files.writeString(log(), row("SessionCase", "2026-10-10T10:00:00Z", "ACCEPTED")
                + row("SessionCase", "2026-10-10T10:01:00Z", "BLOCKED"));
        var identity = ActivityIdentity.conversation("SessionCase", root);
        var result = new TimelineJudgeEvidence(root).recent(new SessionTimelineQuery(
                identity, null, "judge", null, null, null, 1, 65536));
        assertEquals("BLOCKED", result.events().get(0).attributes().get("outcome"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("limited")));
        assertTrue(new TimelineJudgeEvidence(root).recent(new SessionTimelineQuery(
                identity, "run-never-known", null, null, null, null, 50, 65536)).events().isEmpty());
    }

    @Test void rejectsTraversalInsteadOfReadingAnotherSessionRoot() {
        var result = new TimelineJudgeEvidence(root).recent(SessionTimelineQuery.recent(
                ActivityIdentity.conversation("../outside", root)));
        assertTrue(result.events().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Invalid")));
    }
}
