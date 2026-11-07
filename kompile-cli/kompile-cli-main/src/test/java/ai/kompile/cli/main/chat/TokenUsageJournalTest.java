package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageJournalTest {
    @TempDir Path root;

    @Test void metricsJournalEachRequestWithTheModelActiveAtThatTime() throws Exception {
        Path conversations = root.resolve("conversations");
        Path project = root.resolve("project");
        ChatSessionMetrics metrics = new ChatSessionMetrics("chat-1");
        metrics.setAgentName("coder");
        metrics.setProvider("anthropic");
        metrics.setModel("model-a");
        metrics.enableUsageJournal(TokenUsageJournal.pathFor(conversations, "chat-1"), project);
        metrics.recordTokenUsage(100, 20, 5, 1);
        metrics.recordTokenUsage(0, 0, 0, 0); // nothing reported: nothing journaled
        metrics.setModel("model-b");
        metrics.recordTokenUsage(7, 3, 0, 0);

        var scan = TokenUsageJournal.read(conversations, project.toString(),
                Instant.now().minus(Duration.ofMinutes(1)), Instant.now().plusSeconds(1));
        List<TokenUsageJournal.Entry> entries = scan.entries();
        assertEquals(2, entries.size());
        assertEquals("model-a", entries.get(0).model());
        assertEquals(126, entries.get(0).total());
        assertEquals("coder", entries.get(0).agent());
        assertEquals("anthropic", entries.get(0).provider());
        assertEquals("model-b", entries.get(1).model());
        assertEquals(10, entries.get(1).total());
        assertEquals(1, scan.sessionFiles());
    }

    @Test void metricsWithoutAJournalWriteNothing() {
        Path conversations = root.resolve("conversations");
        new ChatSessionMetrics("chat-2").recordTokenUsage(10, 10, 0, 0);
        assertFalse(Files.exists(TokenUsageJournal.pathFor(conversations, "chat-2")));
    }

    @Test void readKeepsOnlyThisProjectAndWindowAndCountsMalformedLines() throws Exception {
        Path conversations = root.resolve("conversations");
        Instant now = Instant.now();
        String project = root.resolve("project").toString();
        Path journal = TokenUsageJournal.pathFor(conversations, "chat-3");
        TokenUsageJournal.append(journal, entry(now.minus(Duration.ofDays(1)), "chat-3", project, "m"));
        TokenUsageJournal.append(journal, entry(now.minus(Duration.ofDays(9)), "chat-3", project, "m"));
        TokenUsageJournal.append(journal, entry(now.minus(Duration.ofHours(1)), "chat-3", root.resolve("other").toString(), "m"));
        Files.writeString(journal, "{not json\n", StandardOpenOption.APPEND);

        var scan = TokenUsageJournal.read(conversations, project + "/./", now.minus(Duration.ofDays(7)), now);
        assertEquals(1, scan.entries().size());
        assertEquals(1, scan.malformedLines());
    }

    @Test void journalsUntouchedSinceTheWindowStartAreNotOpened() throws Exception {
        Path conversations = root.resolve("conversations");
        Instant now = Instant.now();
        String project = root.resolve("project").toString();
        Path stale = TokenUsageJournal.pathFor(conversations, "old-chat");
        TokenUsageJournal.append(stale, entry(now.minus(Duration.ofDays(30)), "old-chat", project, "m"));
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofDays(30))));

        var scan = TokenUsageJournal.read(conversations, project, now.minus(Duration.ofDays(7)), now);
        assertEquals(0, scan.sessionFiles());
        assertTrue(scan.entries().isEmpty());
    }

    static TokenUsageJournal.Entry entry(Instant at, String session, String project, String model) {
        return new TokenUsageJournal.Entry(at, session, project, "coder", "anthropic", model, 1000, 200, 50, 0);
    }
}
