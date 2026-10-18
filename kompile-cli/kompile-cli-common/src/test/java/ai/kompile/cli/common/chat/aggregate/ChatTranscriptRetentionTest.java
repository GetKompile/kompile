package ai.kompile.cli.common.chat.aggregate;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.*;

class ChatTranscriptRetentionTest {
    @TempDir Path home;

    private Path root() throws Exception {
        return Files.createDirectories(home.resolve("conversations"));
    }

    private void old(Path path) throws Exception {
        try (var files = Files.walk(path)) {
            for (Path file : files.toList()) Files.setAttribute(file, "basic:lastModifiedTime",
                    FileTime.from(Instant.now().minus(Duration.ofDays(100))), NOFOLLOW_LINKS);
        }
    }

    private Path session(String id) throws Exception {
        Path root = root();
        Files.writeString(root.resolve(id + ".txt"), "user\nassistant\n");
        Files.writeString(root.resolve(id + ".context.json"), "{\"events\":[]}");
        Path payload = Files.createDirectories(root.resolve(id).resolve("tool-results"));
        Files.writeString(payload.resolve("0001-read.txt"), "full result\n");
        old(root);
        return root;
    }

    private ChatTranscriptRetention retention() {
        return new ChatTranscriptRetention(ChatTranscriptRetention.Policy.DEFAULT);
    }

    @Test void dryRunCountsAllOwnedBytesWithoutWritingOrRemovingAnything() throws Exception {
        Path root = session("session-a");
        long expected = Files.size(root.resolve("session-a.txt"))
                + Files.size(root.resolve("session-a.context.json"))
                + Files.size(root.resolve("session-a/tool-results/0001-read.txt"));
        var result = retention().apply(root.toFile(), true);
        assertEquals(1, result.totalRetired());
        assertEquals(expected, result.archivedBytes());
        assertEquals(expected, result.hotBytesBefore());
        assertEquals(0, result.reclaimedBytes());
        assertTrue(Files.exists(root.resolve("session-a.txt")));
        assertFalse(Files.exists(root.resolve(".trash")));
        assertFalse(Files.exists(root.resolve(".retention.lock")));
        assertFalse(Files.exists(home.resolve("logs")));
    }

    @Test void archiveAndRestoreAllSidecarsWithoutFollowingNativeLinks() throws Exception {
        Path root = session("session-a");
        Path external = Files.writeString(home.resolve("provider-original.jsonl"), "provider secret");
        Path nativeDir = Files.createDirectories(root.resolve("session-a/claude-code"));
        Files.createSymbolicLink(nativeDir.resolve("native.jsonl"), external);
        Files.writeString(nativeDir.resolve("native.path"), external.toString());
        Files.createDirectories(root.resolve("tool-calls/details/session-a/invocation"));
        Files.writeString(root.resolve("tool-calls/session-a.jsonl"), "{\"sessionId\":\"session-a\"}\n");
        Files.writeString(root.resolve("tool-calls/details/session-a/invocation/output.txt"), "exact");
        Files.writeString(root.resolve("tool-calls/all-tool-calls.jsonl"), "shared retained\n");
        Files.createDirectories(root.resolve("kompile__session-a.activity"));
        Files.writeString(root.resolve("kompile__session-a.activity/activity-events.jsonl"), "activity");
        old(root);
        var result = retention().apply(root.toFile());
        assertEquals(1, result.totalRetired());
        assertFalse(Files.exists(root.resolve("session-a.txt")));
        assertFalse(Files.exists(root.resolve("session-a.context.json")));
        assertFalse(Files.exists(root.resolve("tool-calls/session-a.jsonl")));
        assertTrue(Files.exists(root.resolve("tool-calls/all-tool-calls.jsonl")));
        assertEquals("provider secret", Files.readString(external));
        assertTrue(Files.exists(root.resolve(".retired/session-a")));
        ChatTranscriptRetention.restore(root.toFile(), "session-a");
        assertTrue(Files.exists(root.resolve("session-a.context.json")));
        assertEquals("exact", Files.readString(root.resolve("tool-calls/details/session-a/invocation/output.txt")));
        assertTrue(Files.isSymbolicLink(root.resolve("session-a/claude-code/native.jsonl")));
        assertFalse(Files.exists(root.resolve(".retired/session-a")));
    }

    @Test void listingIsMovedBeforePayloadsToAvoidAdvertisingPartialCrashState() throws Exception {
        Path root = session("crash-safe");
        retention().apply(root.toFile());
        Path archive = root.resolve(Files.readString(root.resolve(".retired/crash-safe")));
        var manifest = JsonUtils.standardMapper().readTree(archive.resolve("manifest.json").toFile());
        assertEquals("crash-safe.txt", manifest.path("paths").get(0).asText());
        assertFalse(Files.exists(root.resolve("crash-safe.txt")));
    }

    @Test void pinAndLiveRegistryPidProtectSessions() throws Exception {
        Path root = session("pinned");
        session("live");
        ChatTranscriptRetention.setPinned(root.toFile(), "pinned", true);
        Files.createDirectories(home.resolve("sessions"));
        JsonUtils.standardMapper().writeValue(home.resolve("sessions/registry.json").toFile(),
                Map.of("sessions", List.of(Map.of("kompileSessionId", "live", "pid", ProcessHandle.current().pid()))));
        var result = retention().apply(root.toFile());
        assertEquals(0, result.totalRetired());
        assertEquals(2, result.protectedSessions());
        assertTrue(Files.exists(root.resolve("live.txt")));
        assertTrue(Files.exists(root.resolve("pinned.txt")));
    }

    @Test void existingWriterLockProtectsEvenWithoutRegistry() throws Exception {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        Path root = session(id);
        Path directory = Files.createDirectories(home.resolve("logs/transcripts").resolve(id));
        try (FileChannel channel = FileChannel.open(directory.resolve("writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            var result = retention().apply(root.toFile());
            assertEquals(0, result.totalRetired());
            assertEquals(1, result.protectedSessions());
            assertTrue(Files.exists(root.resolve(id + ".txt")));
        }
    }

    @Test void recentSidecarPreventsAgePruningOfOldTranscript() throws Exception {
        Path root = session("recent");
        Files.writeString(root.resolve("recent.context.json"), "new events");
        assertEquals(0, retention().apply(root.toFile(), true).totalRetired());
    }

    @Test void countsArePerSourceNamespaceAndSizeIncludesPayloads() throws Exception {
        Path root = session("claude__a");
        session("codex__b");
        var count = new ChatTranscriptRetention(new ChatTranscriptRetention.Policy(Duration.ZERO, 0, 1));
        assertEquals(0, count.apply(root.toFile(), true).totalRetired());
        session("claude__c");
        assertEquals(1, count.apply(root.toFile(), true).deletedByCount());
        var size = new ChatTranscriptRetention(new ChatTranscriptRetention.Policy(Duration.ZERO, 1, 0));
        assertEquals(3, size.apply(root.toFile(), true).deletedBySize());
    }

    @Test void orphanContextAndResultSessionAreRetired() throws Exception {
        Path root = session("orphan");
        Files.delete(root.resolve("orphan.txt"));
        assertEquals(1, retention().apply(root.toFile()).totalRetired());
        assertFalse(Files.exists(root.resolve("orphan.context.json")));
    }

    @Test void incrementalLedgerDirectoryIsOwnedEvenWithoutReadableTranscript() throws Exception {
        Path root = root();
        Path journal = Files.createDirectories(root.resolve("incremental.context"));
        Files.writeString(journal.resolve("journal.jsonl"), "seed\nready\nevent\n");
        Files.writeString(journal.resolve("committed"), "marker");
        old(root);
        assertEquals(1, retention().apply(root.toFile()).totalRetired());
        assertFalse(Files.exists(journal));
        ChatTranscriptRetention.restore(root.toFile(), "incremental");
        assertEquals("seed\nready\nevent\n", Files.readString(journal.resolve("journal.jsonl")));
    }

    @Test void interruptedArchiveIsRecoveredOnNextCleanup() throws Exception {
        Path root = session("pending");
        Path archive = Files.createDirectories(root.resolve(".trash/pending/run"));
        Path archivedFiles = Files.createDirectories(archive.resolve("files"));
        Files.move(root.resolve("pending.context.json"), archivedFiles.resolve("pending.context.json"));
        JsonUtils.standardMapper().writeValue(archive.resolve("manifest.json").toFile(),
                Map.of("sessionId", "pending", "complete", false, "retiredAt", 1,
                        "paths", List.of("pending.context.json", "pending", "pending.txt")));
        retention().apply(root.toFile());
        assertFalse(Files.exists(root.resolve("pending.txt")));
        assertTrue(Files.exists(root.resolve(".retired/pending")));
        assertTrue(JsonUtils.standardMapper().readTree(archive.resolve("manifest.json").toFile()).path("complete").asBoolean());
    }

    @Test void purgeRequiresGraceAndNeverDeletesLinkedNativeOriginal() throws Exception {
        Path root = session("purge");
        Path original = Files.writeString(home.resolve("native.jsonl"), "keep");
        Files.createSymbolicLink(root.resolve("purge/tool-results/native.jsonl"), original);
        old(root);
        retention().apply(root.toFile());
        Path archive = root.resolve(Files.readString(root.resolve(".retired/purge")));
        Path manifestPath = archive.resolve("manifest.json");
        assertEquals(0, ChatTranscriptRetention.purgeTrash(root.toFile(), Duration.ofDays(7), false));
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) JsonUtils.standardMapper().readTree(manifestPath.toFile());
        manifest.put("retiredAt", Instant.now().minus(Duration.ofDays(10)).toEpochMilli());
        JsonUtils.standardMapper().writeValue(manifestPath.toFile(), manifest);
        long preview = ChatTranscriptRetention.purgeTrash(root.toFile(), Duration.ofDays(7), true);
        assertTrue(preview > 0);
        assertTrue(Files.exists(archive));
        assertEquals(preview, ChatTranscriptRetention.purgeTrash(root.toFile(), Duration.ofDays(7), false));
        assertFalse(Files.exists(archive));
        assertTrue(Files.exists(root.resolve(".purged/purge")));
        assertEquals("keep", Files.readString(original));
        assertThrows(IllegalArgumentException.class,
                () -> ChatTranscriptRetention.purgeTrash(root.toFile(), Duration.ZERO, true));
    }

    @Test void restoreRefusesToOverwriteNewSessionData() throws Exception {
        Path root = session("restore");
        retention().apply(root.toFile());
        Files.writeString(root.resolve("restore.txt"), "new");
        assertThrows(java.io.IOException.class, () -> ChatTranscriptRetention.restore(root.toFile(), "restore"));
        assertEquals("new", Files.readString(root.resolve("restore.txt")));
    }

    @Test void unsafeIdsSymlinkRootAndCorruptRegistryFailClosed() throws Exception {
        Path root = session("safe");
        assertThrows(IllegalArgumentException.class,
                () -> ChatTranscriptRetention.setPinned(root.toFile(), "../outside", true));
        Path link = home.resolve("linked");
        Files.createSymbolicLink(link, root);
        assertEquals(0, retention().apply(link.toFile()).totalRetired());
        Files.createDirectories(home.resolve("sessions"));
        Files.writeString(home.resolve("sessions/registry.json"), "broken JSON");
        assertEquals(0, retention().apply(root.toFile()).totalRetired());
        assertTrue(Files.exists(root.resolve("safe.txt")));
        assertThrows(IllegalArgumentException.class, () -> ChatTranscriptRetention.Policy.of(-1, 1, 1));
    }
}
