package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
class ConversationLedgerTest {

    @Test
    void checkpointIsTransactionalDurableAndKeepsRawEvents() throws Exception {
        String sessionId = "ledger-" + UUID.randomUUID();
        Path stateFile = contextFile(sessionId);
        try {
            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(sessionId);
            ledger.append(CompactionService.ConversationEntry.user("old request"));
            ledger.append(CompactionService.ConversationEntry.assistant("old response"));
            ledger.append(CompactionService.ConversationEntry.user("recent request"));

            ConversationLedger.Snapshot before = ledger.snapshot();
            long covered = before.coveredThroughForPrefix(2);
            assertTrue(ledger.commitCompaction(
                    before.version(), covered, "portable summary", "generic",
                    "openai", "gpt-test", 1200, 200));

            ConversationLedger.Snapshot compacted = ledger.snapshot();
            assertEquals(3, compacted.allEvents().size(),
                    "compaction must never delete the canonical audit events");
            assertEquals(2, compacted.activeEntries().size());
            assertTrue(compacted.activeEntries().get(0).content.contains("portable summary"));
            assertEquals("recent request", compacted.activeEntries().get(1).content);

            ConversationLedger restored = new ConversationLedger(JsonUtils.standardMapper());
            restored.configureSession(sessionId);
            assertEquals(compacted.activeEntries().size(), restored.snapshot().activeEntries().size());
            assertEquals("portable summary", restored.snapshot().checkpoint().summary());
        } finally {
            Files.deleteIfExists(stateFile);
        }
    }

    @Test
    void staleSnapshotCannotOverwriteNewEvents() throws Exception {
        String sessionId = "ledger-cas-" + UUID.randomUUID();
        Path stateFile = contextFile(sessionId);
        try {
            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(sessionId);
            ledger.append(CompactionService.ConversationEntry.user("first"));
            ConversationLedger.Snapshot stale = ledger.snapshot();
            ledger.append(CompactionService.ConversationEntry.assistant("new concurrent event"));

            assertFalse(ledger.commitCompaction(
                    stale.version(), stale.coveredThroughForPrefix(1), "stale summary",
                    "generic", "anthropic", "claude-test", 100, 20));
            assertEquals(2, ledger.snapshot().activeEntries().size());
        } finally {
            Files.deleteIfExists(stateFile);
        }
    }

    @Test
    void nativePayloadAndPortableSummarySurviveRestart() throws Exception {
        String sessionId = "ledger-native-" + UUID.randomUUID();
        Path stateFile = contextFile(sessionId);
        try {
            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(sessionId);
            ledger.append(CompactionService.ConversationEntry.user("long request"));
            ConversationLedger.Snapshot snapshot = ledger.snapshot();

            assertTrue(ledger.commitNativeCompaction(
                    snapshot.version(), snapshot.coveredThroughForPrefix(1),
                    "portable fallback", "native-openai-responses",
                    "openai-codex", "gpt-test", 10_000, 2_000,
                    JsonUtils.standardMapper().readTree(
                            "{\"type\":\"compaction\",\"encrypted_content\":\"opaque\"}")));

            ConversationLedger restored = new ConversationLedger(JsonUtils.standardMapper());
            restored.configureSession(sessionId);
            assertEquals("opaque", restored.snapshot().checkpoint()
                    .nativePayload().path("encrypted_content").asText());
            assertTrue(restored.snapshot().activeEntries().get(0).content
                    .contains("portable fallback"));
        } finally {
            Files.deleteIfExists(stateFile);
        }
    }

    @Test
    void recordedNativeSessionIsResumableOnlyWhileTheConversationIsUnchanged() throws Exception {
        String sessionId = "ledger-native-session-" + UUID.randomUUID();
        Path stateFile = contextFile(sessionId);
        Path sidecar = stateFile.resolveSibling(sessionId + ".native-session.json");
        try {
            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(sessionId);
            assertNull(ledger.resumableNativeSession("claude-cli"), "nothing recorded yet");
            ledger.append(CompactionService.ConversationEntry.user("request"));
            ledger.append(CompactionService.ConversationEntry.assistant("response"));
            ledger.recordNativeSession("claude-cli", "native-1", "digest-1");
            assertTrue(Files.exists(sidecar), "the session must outlive this process");

            // A later process continues the session that holds this conversation.
            ConversationLedger restarted = new ConversationLedger(JsonUtils.standardMapper());
            restarted.configureSession(sessionId);
            ConversationLedger.NativeSession saved = restarted.resumableNativeSession("claude-cli");
            assertNotNull(saved);
            assertEquals("native-1", saved.sessionId());
            assertEquals("digest-1", saved.instructionsDigest());
            assertNull(restarted.resumableNativeSession("opencode"), "another transport's session");

            // A turn that session did not see (another route, a failed turn) ends it,
            // in this process and the next.
            restarted.append(CompactionService.ConversationEntry.user("asked on another route"));
            assertNull(restarted.resumableNativeSession("claude-cli"));
            ConversationLedger later = new ConversationLedger(JsonUtils.standardMapper());
            later.configureSession(sessionId);
            assertNull(later.resumableNativeSession("claude-cli"));
        } finally {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(sidecar);
        }
    }

    @Test
    void compactionEndsTheRecordedNativeSession() throws Exception {
        String sessionId = "ledger-native-compaction-" + UUID.randomUUID();
        Path stateFile = contextFile(sessionId);
        Path sidecar = stateFile.resolveSibling(sessionId + ".native-session.json");
        try {
            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(sessionId);
            ledger.append(CompactionService.ConversationEntry.user("old request"));
            ledger.append(CompactionService.ConversationEntry.assistant("old response"));
            ledger.append(CompactionService.ConversationEntry.user("recent request"));
            ledger.recordNativeSession("claude-cli", "native-1", "digest-1");
            assertNotNull(ledger.resumableNativeSession("claude-cli"));

            ConversationLedger.Snapshot before = ledger.snapshot();
            assertTrue(ledger.commitCompaction(
                    before.version(), before.coveredThroughForPrefix(2), "portable summary",
                    "generic", "anthropic", "claude-test", 1200, 200));
            assertNull(ledger.resumableNativeSession("claude-cli"),
                    "the native session still holds the uncompacted conversation");
        } finally {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(sidecar);
        }
    }

    @Test
    void boundaryPlannerPreservesWholeNewestToolExchange() {
        CompactionService service = new CompactionService(JsonUtils.standardMapper(), 8_192);
        List<CompactionService.ConversationEntry> entries = List.of(
                CompactionService.ConversationEntry.user("old"),
                CompactionService.ConversationEntry.assistant("old answer"),
                CompactionService.ConversationEntry.user("new request"),
                CompactionService.ConversationEntry.toolCall("read", "call-1", "{}"),
                CompactionService.ConversationEntry.toolResult("read", "call-1", "large output"),
                CompactionService.ConversationEntry.assistant("final answer"));

        assertEquals(2, ConversationBoundaryPlanner.preserveFrom(entries, service, 1),
                "the cutoff must move to the user that owns the newest tool exchange");
    }

    @Test
    void boundaryPlannerCompactsSummaryPlusOwnerlessProviderTail() {
        CompactionService service = new CompactionService(JsonUtils.standardMapper(), 8_192);
        List<CompactionService.ConversationEntry> entries = List.of(
                CompactionService.ConversationEntry.system(
                        ConversationLedger.SUMMARY_MARKER + "prior summary"),
                CompactionService.ConversationEntry.assistant("large response"),
                CompactionService.ConversationEntry.toolCall("read", "call-2", "{}"),
                CompactionService.ConversationEntry.toolResult("read", "call-2", "output"));

        assertEquals(entries.size(),
                ConversationBoundaryPlanner.preserveFrom(entries, service, 1));
    }

    private static Path contextFile(String sessionId) {
        return KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(sessionId + ".context.json");
    }
}
