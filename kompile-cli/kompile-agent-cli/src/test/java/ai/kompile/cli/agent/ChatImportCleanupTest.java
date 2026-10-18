package ai.kompile.cli.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ChatImportCleanupTest {
    @TempDir Path home;
    private String originalHome;
    private Path conversations;

    @BeforeEach void setUp() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        conversations = Files.createDirectories(home.resolve(".kompile/conversations"));
        Path transcript = Files.writeString(conversations.resolve("old.txt"), "> prompt\n< response\n");
        Files.setLastModifiedTime(transcript, FileTime.from(Instant.now().minus(Duration.ofDays(100))));
    }

    @AfterEach void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private int cleanup(String... args) {
        var command = new CommandLine(new ChatImportCommand.CleanupCmd());
        command.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
        return command.execute(args);
    }

    @Test void defaultCleanupArchivesAndRestoreMakesTranscriptAvailable() {
        assertEquals(0, cleanup());
        assertFalse(Files.exists(conversations.resolve("old.txt")));
        assertTrue(Files.exists(conversations.resolve(".retired/old")));
        assertEquals(0, cleanup("--restore", "old"));
        assertTrue(Files.exists(conversations.resolve("old.txt")));
        assertFalse(Files.exists(conversations.resolve(".retired/old")));
    }

    @Test void dryRunDoesNotCreateAnyArchiveOrLease() {
        assertEquals(0, cleanup("--dry-run"));
        assertTrue(Files.exists(conversations.resolve("old.txt")));
        assertFalse(Files.exists(conversations.resolve(".retention.lock")));
        assertFalse(Files.exists(conversations.resolve(".trash")));
    }

    @Test void pinAndUnpinControlRetirement() {
        assertEquals(0, cleanup("--pin", "old"));
        assertEquals(0, cleanup());
        assertTrue(Files.exists(conversations.resolve("old.txt")));
        assertEquals(0, cleanup("--unpin", "old"));
        assertEquals(0, cleanup());
        assertFalse(Files.exists(conversations.resolve("old.txt")));
    }

    @Test void localOnlyMutuallyExclusiveAndInvalidOperationsFailBeforeMutation() {
        assertNotEquals(0, cleanup("--pin", "old", "--unpin", "old"));
        assertNotEquals(0, cleanup("--remote", "--pin", "old"));
        assertNotEquals(0, cleanup("--pin", "../outside"));
        assertNotEquals(0, cleanup("--purge-trash", "--grace-days", "0"));
        assertTrue(Files.exists(conversations.resolve("old.txt")));
        assertFalse(Files.exists(conversations.resolve("old.pinned")));
    }

    @Test void compactionIsExplicitDryRunnableAndOnlyTargetsPurgedSessions() throws Exception {
        Path calls = Files.createDirectories(conversations.resolve("tool-calls")).resolve("all-tool-calls.jsonl");
        String records = "{\"sessionId\":\"gone\"}\n{\"sessionId\":\"live\"}\n";
        Files.writeString(calls, records);
        Files.writeString(Files.createDirectories(conversations.resolve(".purged")).resolve("gone"), "receipt");
        assertEquals(0, cleanup("--compact-journals", "--dry-run"));
        assertEquals(records, Files.readString(calls));
        assertEquals(0, cleanup("--compact-journals"));
        assertEquals("{\"sessionId\":\"live\"}\n", Files.readString(calls));
        assertNotEquals(0, cleanup("--compact-journals", "--purge-trash"));
        assertNotEquals(0, cleanup("--remote", "--compact-journals"));
        assertTrue(Files.exists(conversations.resolve("old.txt")));
    }

    @Test void corruptRegistryIsReportedAsFailureRatherThanSuccessfulCleanup() throws Exception {
        Path registry = Files.createDirectories(home.resolve(".kompile/sessions")).resolve("registry.json");
        Files.writeString(registry, "broken");
        assertEquals(1, cleanup());
        assertTrue(Files.exists(conversations.resolve("old.txt")));
    }
}
