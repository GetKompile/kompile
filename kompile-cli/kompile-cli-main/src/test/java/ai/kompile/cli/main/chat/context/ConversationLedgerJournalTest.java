package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ConversationLedgerJournalTest {
    private static Path root() {
        return KompileHome.homeDirectory().toPath().resolve("conversations");
    }
    private static String id() { return "journal-" + UUID.randomUUID(); }
    private static Path journal(String id) { return root().resolve(id + ".context/journal.jsonl"); }
    private static ConversationLedger ledger(String id) {
        ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
        ledger.configureSession(id);
        return ledger;
    }
    @Test
    void archiveRestoreRoundTripsIncrementalJournalAndAttachmentsWithoutLegacyFallback() throws Exception {
        String id = id();
        ConversationLedger ledger = ledger(id);
        byte[] bytes = new byte[]{1, 2, 3};
        var attachment = ledger.storeAttachment("image.png", "image/png", true, bytes);
        ledger.append(CompactionService.ConversationEntry.user("retained"));
        try (var paths = Files.walk(root().resolve(id + ".context"))) {
            for (Path path : paths.toList()) Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(
                    Instant.now().minus(java.time.Duration.ofDays(100))));
        }
        var retention = new ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention(
                ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention.Policy.DEFAULT);
        assertEquals(1, retention.apply(root().toFile()).totalRetired());
        assertThrows(IllegalStateException.class, () -> ledger(id));
        ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention.restore(root().toFile(), id);
        var resumed = ledger(id);
        assertEquals("retained", resumed.snapshot().allEvents().get(0).content());
        assertArrayEquals(bytes, resumed.readAttachment(attachment));
    }

    private static ConversationLedger.Event event(long sequence, String content) {
        return new ConversationLedger.Event(sequence, CompactionService.ConversationEntry.user("type").type,
                "user", content, null, null, Instant.EPOCH, List.of());
    }
    private static void assertPrefix(byte[] before, byte[] after) {
        assertTrue(after.length > before.length);
        assertArrayEquals(before, Arrays.copyOf(after, before.length));
    }

    @Test
    void schemaOneMigratesOncePreservingOriginalsOpaquePayloadAndAttachments() throws Exception {
        String id = id();
        Files.createDirectories(root());
        var mapper = JsonUtils.standardMapper();
        byte[] image = "legacy image bytes".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        var attachment = new CompactionService.Attachment("old.png", "image/png", true, digest, image.length);
        var event = new ConversationLedger.Event(1, CompactionService.ConversationEntry.user("type").type,
                "user", "old request", null, null, Instant.EPOCH, List.of(attachment));
        JsonNode opaque = mapper.readTree("{\"encrypted_content\":\"opaque\",\"vendor\":{\"array\":[1,true,null]}}");
        var checkpoint = new ConversationLedger.CompactionCheckpoint(1, "summary", "native",
                "provider", "model", 100, 10, opaque, Instant.EPOCH);
        ObjectNode state = mapper.valueToTree(new ConversationLedger.State(1, 2, List.of(event), checkpoint));
        state.putObject("futureExtension").put("keep", "untouched");
        Path legacy = root().resolve(id + ".context.json");
        byte[] original = mapper.writeValueAsBytes(state);
        Files.write(legacy, original);
        var binding = new ConversationLedger.NativeSession("claude-cli", "native-old", 2, "digest", Instant.EPOCH);
        Path nativeFile = root().resolve(id + ".native-session.json");
        byte[] nativeBytes = mapper.writeValueAsBytes(binding);
        Files.write(nativeFile, nativeBytes);
        Path blob = Files.createDirectories(root().resolve(id + ".attachments")).resolve(digest);
        Files.write(blob, image);

        ConversationLedger migrated = ledger(id);
        assertEquals(2, migrated.snapshot().version());
        assertEquals(opaque, migrated.snapshot().checkpoint().nativePayload());
        assertEquals("native-old", migrated.resumableNativeSession("claude-cli").sessionId());
        assertArrayEquals(image, migrated.readAttachment(migrated.snapshot().allEvents().get(0).attachments().get(0)));
        JsonNode seedRow = mapper.readTree(Files.readAllLines(journal(id)).get(0));
        JsonNode seed = mapper.readTree(seedRow.path("payload").asText());
        assertEquals("untouched", seed.path("state").path("futureExtension").path("keep").asText());
        byte[] prefix = Files.readAllBytes(journal(id));
        migrated.forgetNativeSession("claude-cli");
        migrated.append(CompactionService.ConversationEntry.assistant("new response"));
        assertPrefix(prefix, Files.readAllBytes(journal(id)));
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertArrayEquals(nativeBytes, Files.readAllBytes(nativeFile));
        assertArrayEquals(image, Files.readAllBytes(blob));
        ConversationLedger resumed = ledger(id);
        assertEquals(3, resumed.snapshot().version());
        assertEquals(2, resumed.snapshot().allEvents().size());
        assertNull(resumed.resumableNativeSession("claude-cli"), "old sidecar is not consulted after migration");
        assertEquals(opaque, resumed.snapshot().checkpoint().nativePayload());
    }

    @Test
    void appendsCheckpointsAndNativeBindingsNeverRewriteTheJournalPrefix() throws Exception {
        String id = id();
        ConversationLedger ledger = ledger(id);
        ledger.append(CompactionService.ConversationEntry.user("historical request"));
        byte[] first = Files.readAllBytes(journal(id));
        ledger.append(CompactionService.ConversationEntry.assistant("historical response"));
        byte[] second = Files.readAllBytes(journal(id));
        assertPrefix(first, second);
        var mapper = JsonUtils.standardMapper();
        JsonNode eventRow = mapper.readTree(Files.readAllLines(journal(id)).get(3));
        JsonNode eventPayload = mapper.readTree(eventRow.path("payload").asText());
        assertEquals("historical response", eventPayload.path("content").asText());
        assertFalse(eventPayload.has("events"), "an append contains only the new event, not a history snapshot");
        assertTrue(ledger.commitNativeCompaction(2, 1, "summary", "native", "provider", "model", 100, 10,
                JsonUtils.standardMapper().readTree("{\"encrypted\":\"opaque\"}")));
        byte[] third = Files.readAllBytes(journal(id));
        assertPrefix(second, third);
        JsonNode checkpointRow = mapper.readTree(Files.readAllLines(journal(id)).get(4));
        assertEquals("checkpoint", checkpointRow.path("kind").asText());
        assertFalse(checkpointRow.path("payload").asText().contains("historical request"));
        ledger.recordNativeSession("claude-cli", "native-3", "instructions");
        assertPrefix(third, Files.readAllBytes(journal(id)));
        assertEquals(6, Files.readAllLines(journal(id)).size(), "two migration rows and four incremental rows");
        ConversationLedger resumed = ledger(id);
        assertEquals(3, resumed.snapshot().version());
        assertEquals(2, resumed.snapshot().allEvents().size());
        assertEquals(3, resumed.resumableNativeSession("claude-cli").syncedVersion());
    }

    @Test
    void onlyAnUnterminatedEofFragmentIsRecovered() throws Exception {
        String id = id();
        ConversationLedger original = ledger(id);
        original.append(CompactionService.ConversationEntry.user("committed"));
        byte[] prefix = Files.readAllBytes(journal(id));
        Files.writeString(journal(id), "{\"schemaVersion\":2,\"unfinished\":", StandardOpenOption.APPEND);
        ConversationLedger resumed = ledger(id);
        assertArrayEquals(prefix, Files.readAllBytes(journal(id)));
        assertEquals(1, resumed.snapshot().version());
        resumed.append(CompactionService.ConversationEntry.assistant("after recovery"));
        assertEquals(2, ledger(id).snapshot().allEvents().size());
        assertThrows(IllegalStateException.class, () -> original.append(CompactionService.ConversationEntry.user("stale")));
    }

    @Test
    void completeTrailingCorruptionAndMidstreamCorruptionFailClosed() throws Exception {
        for (boolean middle : List.of(false, true)) {
            String id = id();
            ConversationLedger ledger = ledger(id);
            ledger.append(CompactionService.ConversationEntry.user("first"));
            ledger.append(CompactionService.ConversationEntry.assistant("second"));
            if (middle) {
                List<String> lines = Files.readAllLines(journal(id));
                lines.set(2, lines.get(2).replace("first", "altered")); // Keep valid JSON; checksum must fail.
                Files.writeString(journal(id), String.join("\n", lines) + "\n");
            } else {
                Files.writeString(journal(id), "{broken record}\n", StandardOpenOption.APPEND);
            }
            byte[] corrupt = Files.readAllBytes(journal(id));
            ConversationLedger blocked = new ConversationLedger(JsonUtils.standardMapper());
            assertThrows(IllegalStateException.class, () -> blocked.configureSession(id));
            assertThrows(IllegalStateException.class, () -> blocked.append(CompactionService.ConversationEntry.user("must not reset")));
            assertArrayEquals(corrupt, Files.readAllBytes(journal(id)));
        }
    }

    @Test
    void failedWriteTruncatesAndForcesRollbackBeforeRetry() throws Exception {
        String id = id();
        AtomicBoolean fail = new AtomicBoolean(true);
        var journal = new ConversationLedgerJournal(JsonUtils.standardMapper(), root(), id, () -> {
            if (fail.getAndSet(false)) throw new IOException("injected force failure after write");
        });
        journal.open();
        byte[] prefix = Files.readAllBytes(journal(id));
        assertThrows(IOException.class, () -> journal.append(0, 1, "event", event(1, "not committed")));
        assertArrayEquals(prefix, Files.readAllBytes(journal(id)));
        assertTrue(journal.isCurrent(0));
        journal.append(0, 1, "event", event(1, "committed on retry"));
        assertEquals("committed on retry", ledger(id).snapshot().allEvents().get(0).content());
    }

    @Test
    void leaseFailureLeavesAllInMemoryStateAndVersionUnchanged() throws Exception {
        String id = id();
        ConversationLedger ledger = ledger(id);
        ledger.append(CompactionService.ConversationEntry.user("first"));
        ledger.recordNativeSession("claude-cli", "original", "digest");
        ConversationLedger.Snapshot before = ledger.snapshot();
        byte[] prefix = Files.readAllBytes(journal(id));
        try (FileChannel channel = FileChannel.open(journal(id).getParent().resolve("writer.lock"), StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertThrows(IllegalStateException.class, () -> ledger.append(CompactionService.ConversationEntry.user("failed")));
            assertFalse(ledger.commitCompaction(1, 1, "failed", "generic", "provider", "model", 100, 10));
            ledger.recordNativeSession("claude-cli", "failed-binding", "digest");
            ledger.forgetNativeSession("claude-cli");
            assertEquals(before.version(), ledger.snapshot().version());
            assertEquals(before.allEvents(), ledger.snapshot().allEvents());
            assertEquals(before.checkpoint(), ledger.snapshot().checkpoint());
            assertArrayEquals(prefix, Files.readAllBytes(journal(id)));
        }
        assertEquals("original", ledger.resumableNativeSession("claude-cli").sessionId());
        assertEquals(2, ledger.append(CompactionService.ConversationEntry.assistant("retry")).sequence());
    }

    @Test
    void staleInstancesCannotAppendCompactOrRebindAtTheSameConversationVersion() throws Exception {
        String id = id();
        ConversationLedger writer = ledger(id);
        writer.append(CompactionService.ConversationEntry.user("request"));
        ConversationLedger stale = ledger(id);
        writer.recordNativeSession("claude-cli", "accurate", "digest");
        stale.recordNativeSession("claude-cli", "inaccurate", "digest");
        assertNull(stale.resumableNativeSession("claude-cli"));
        assertThrows(IllegalStateException.class, () -> stale.append(CompactionService.ConversationEntry.assistant("lost update")));
        assertFalse(stale.commitCompaction(1, 1, "stale", "generic", "provider", "model", 100, 10));
        assertEquals(1, stale.snapshot().version());
        assertEquals("accurate", ledger(id).resumableNativeSession("claude-cli").sessionId());
        ConversationLedger oldNative = ledger(id);
        writer.append(CompactionService.ConversationEntry.assistant("response"));
        assertNull(oldNative.resumableNativeSession("claude-cli"), "disk version, not just local version, governs resume");
        assertEquals(2, ledger(id).snapshot().allEvents().size());
    }

    @Test
    void retiredSessionAndExclusiveRetentionLeaseCannotBeResurrected() throws Exception {
        String id = id();
        ConversationLedger ledger = ledger(id);
        byte[] prefix = Files.readAllBytes(journal(id));
        try (FileChannel channel = FileChannel.open(root().resolve(".retention.lock"), StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertThrows(IllegalStateException.class, () -> ledger.append(CompactionService.ConversationEntry.user("blocked")));
        }
        Files.writeString(Files.createDirectories(root().resolve(".retired")).resolve(id), ".trash/owned");
        assertThrows(IllegalStateException.class, () -> ledger.append(CompactionService.ConversationEntry.user("retired")));
        assertThrows(IllegalStateException.class, () -> ledger(id));
        assertArrayEquals(prefix, Files.readAllBytes(journal(id)));
    }

    @Test
    void malformedLegacyStateIsNeverMigratedOrReset() throws Exception {
        for (boolean badSequence : List.of(false, true)) {
            String id = id();
            Files.createDirectories(root());
            var state = new ConversationLedger.State(1, badSequence ? 1 : 0,
                    List.of(event(badSequence ? 2 : 1, "legacy")), null);
            Path legacy = root().resolve(id + ".context.json");
            byte[] bytes = JsonUtils.standardMapper().writeValueAsBytes(state);
            Files.write(legacy, bytes);
            assertThrows(IllegalStateException.class, () -> ledger(id));
            assertFalse(Files.exists(journal(id)));
            assertArrayEquals(bytes, Files.readAllBytes(legacy));
        }
    }

    @Test
    void validChecksumsDoNotBypassSequenceOrVersionValidation() throws Exception {
        for (boolean sequence : List.of(false, true)) {
            String id = id();
            ConversationLedger ledger = ledger(id);
            ledger.append(CompactionService.ConversationEntry.user("original"));
            var mapper = JsonUtils.standardMapper();
            List<String> lines = Files.readAllLines(journal(id));
            ObjectNode row = (ObjectNode) mapper.readTree(lines.get(2));
            if (sequence) {
                ObjectNode payload = (ObjectNode) mapper.readTree(row.path("payload").asText());
                payload.put("sequence", 2);
                row.put("payload", mapper.writeValueAsString(payload));
            } else row.put("version", 2);
            String checksumInput = "2\n" + row.path("revision").asLong() + "\n"
                    + row.path("version").asLong() + "\n" + row.path("kind").asText() + "\n"
                    + row.path("payload").asText();
            row.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(checksumInput.getBytes(StandardCharsets.UTF_8))));
            lines.set(2, mapper.writeValueAsString(row));
            Files.writeString(journal(id), String.join("\n", lines) + "\n");
            byte[] invalid = Files.readAllBytes(journal(id));
            assertThrows(IllegalStateException.class, () -> ledger(id));
            assertArrayEquals(invalid, Files.readAllBytes(journal(id)));
        }
    }

    @Test
    void missingCommittedJournalNeverFallsBackToThePreservedLegacySnapshot() throws Exception {
        String id = id();
        Files.createDirectories(root());
        Path legacy = root().resolve(id + ".context.json");
        byte[] original = JsonUtils.standardMapper().writeValueAsBytes(
                new ConversationLedger.State(1, 1, List.of(event(1, "old snapshot")), null));
        Files.write(legacy, original);
        ConversationLedger migrated = ledger(id);
        migrated.append(CompactionService.ConversationEntry.assistant("new history"));
        Files.delete(journal(id));
        assertThrows(IllegalStateException.class, () -> ledger(id));
        assertThrows(IllegalStateException.class, () -> migrated.append(CompactionService.ConversationEntry.user("must not reset")));
        assertFalse(Files.exists(journal(id)));
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }
}
