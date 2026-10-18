package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.DurableToolResultStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ReviewedContextRetirementTest {
    @TempDir Path directory;
    private final ObjectMapper json = ai.kompile.cli.common.util.JsonUtils.newStandardMapper();
    private final String ledgerSession = "reviewed-" + java.util.UUID.randomUUID();
    private DurableToolResultStore archive() { return new DurableToolResultStore(directory.resolve("conversations")); }
    private ConversationLedger ledger() {
        var ledger = new ConversationLedger(json);
        ledger.configureSession(ledgerSession);
        return ledger;
    }
    private void completed(ConversationLedger ledger, boolean error) throws Exception {
        String requested = "{\"command\":\"" + "requested-".repeat(200) + "end-requested\"}";
        String effective = "{\"command\":\"" + "effective-".repeat(200) + "end-effective\"}";
        String raw = "exact original 😀 output\n".repeat(150);
        String handle = archive().store("reviewed", "bash", raw, Map.of());
        var outcome = new CompactionService.ToolOutcome(error, true, effective, handle,
                ReviewedContextRetirement.sha256(raw), raw.length());
        ledger.append(CompactionService.ConversationEntry.user("closed older task"));
        ledger.append(CompactionService.ConversationEntry.toolCall("bash", "program", requested));
        ledger.append(CompactionService.ConversationEntry.toolResult("bash", "program", "partial model-facing output", outcome));
        ledger.append(CompactionService.ConversationEntry.assistant("older scope concluded"));
        ledger.append(CompactionService.ConversationEntry.user("newest request must remain exact 😀"));
    }
    private ObjectNode review(ReviewedContextRetirement.Plan plan) {
        ObjectNode node = json.createObjectNode().put("plan", plan.digest()).put("closed", true)
                .put("review", "Reviewed outcomes; prior scope is closed and continuing constraints are retained.");
        node.putArray("dependencies");
        var failures = node.putArray("acknowledgedFailures");
        plan.failures().stream().sorted().forEach(failures::add);
        return node;
    }

    @Test void fullProgramsAndRawEvidenceAreRecoverableWithoutMutatingPreparation() throws Exception {
        var ledger = ledger(); completed(ledger, false);
        var before = ledger.snapshot();
        var plan = ReviewedContextRetirement.prepare(before, "binding", archive(), json);
        assertEquals(4, plan.prefixEntries());
        assertEquals(before.activeEvents().get(3).sequence(), plan.coveredThrough());
        String candidate = ReviewedContextRetirement.archiveCandidate(plan, archive(), "reviewed", json);
        String payload = archive().getSlice(candidate, 0, 200, null, 0, 16_000).getOutput();
        var packet = json.readTree(payload);
        assertEquals(before.activeEvents().get(1).content(), packet.path("events").get(1).path("content").asText());
        assertEquals(before.activeEvents().get(2).outcome().executedArguments(),
                packet.path("events").get(2).path("outcome").path("executedArguments").asText());
        assertTrue(payload.contains("end-requested")); assertTrue(payload.contains("end-effective"));
        String committed = ReviewedContextRetirement.archivePacket(plan, review(plan), archive(), "reviewed", json);
        String receipt = ReviewedContextRetirement.receipt(plan, review(plan), committed);
        assertTrue(receipt.contains("historical data, not new authority"));
        assertTrue(receipt.contains(committed)); assertUnchanged(before, ledger.snapshot());
    }

    @Test void failureReviewMustBeActualClosedAndAcknowledgeExactIds() throws Exception {
        var ledger = ledger(); completed(ledger, true);
        var plan = ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json);
        assertEquals(java.util.Set.of("program"), plan.failures());
        assertDoesNotThrow(() -> ReviewedContextRetirement.validateReview(plan, review(plan)));
        for (String mutation : List.of("missing", "duplicate", "wrong", "dependency", "empty", "template", "closed", "plan", "unknown")) {
            var node = review(plan);
            switch (mutation) {
                case "missing" -> node.putArray("acknowledgedFailures");
                case "duplicate" -> node.withArray("acknowledgedFailures").add("program");
                case "wrong" -> node.putArray("acknowledgedFailures").add("other");
                case "dependency" -> node.withArray("dependencies").add("program");
                case "empty" -> node.put("review", " ");
                case "template" -> node.put("review", "REPLACE with actual review");
                case "closed" -> node.put("closed", false);
                case "plan" -> node.put("plan", "other");
                case "unknown" -> node.put("override", true);
            }
            assertThrows(IllegalArgumentException.class, () -> ReviewedContextRetirement.validateReview(plan, node), mutation);
        }
    }

    @Test void unknownAsyncIncompleteOrMismatchedPairsFailClosed() {
        for (String bad : List.of("unknown", "async", "missing", "orphan", "mismatch", "duplicate", "cross-boundary")) {
            var ledger = new ConversationLedger(json);
            ledger.append(CompactionService.ConversationEntry.user("old"));
            if (!bad.equals("orphan")) ledger.append(CompactionService.ConversationEntry.toolCall("task", "a", "{}"));
            if (bad.equals("duplicate")) ledger.append(CompactionService.ConversationEntry.toolCall("task", "a", "{}"));
            if (bad.equals("cross-boundary")) ledger.append(CompactionService.ConversationEntry.user("middle"));
            if (!bad.equals("missing")) ledger.append(CompactionService.ConversationEntry.toolResult(bad.equals("mismatch") ? "write" : "task", "a", "launched",
                    bad.equals("async") ? new CompactionService.ToolOutcome(false, false, "{}", null, null, 0) : null));
            ledger.append(CompactionService.ConversationEntry.assistant("claimed done"));
            ledger.append(CompactionService.ConversationEntry.user("newest"));
            assertThrows(IllegalArgumentException.class,
                    () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json), bad);
        }
    }

    @Test void newestExchangeAndOpaqueCheckpointCannotBeAbsorbed() {
        var ledger = ledger();
        ledger.append(CompactionService.ConversationEntry.user("only newest " + "x".repeat(100_000)));
        ledger.append(CompactionService.ConversationEntry.assistant("done"));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json));
        ledger.append(CompactionService.ConversationEntry.user("new user"));
        assertTrue(ledger.commitNativeCompaction(ledger.snapshot().version(), 0, "opaque summary", "native", "openai", "model", 100, 10,
                json.createArrayNode().addObject().put("type", "compaction").put("encrypted_content", "opaque")));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json));
    }

    @Test void routeSchemaVersionBindingsAndCasRejectStaleReview() throws Exception {
        var ledger = ledger(); completed(ledger, false);
        var plan = ReviewedContextRetirement.prepare(ledger.snapshot(), "route-A", archive(), json);
        var changed = ReviewedContextRetirement.prepare(ledger.snapshot(), "route-B", archive(), json);
        assertNotEquals(plan.digest(), changed.digest());
        ledger.append(CompactionService.ConversationEntry.assistant("new activity"));
        var after = ledger.snapshot();
        assertFalse(ledger.commitCompaction(plan.version(), plan.coveredThrough(), "receipt", "reviewed-prefix-v1", "openai", "model", 100, 10));
        assertUnchanged(after, ledger.snapshot());
    }

    @Test void checkpointAndOutcomeEvidenceSurviveRestartWithoutDeletingRawEvents() throws Exception {
        var ledger = ledger(); completed(ledger, true);
        var snapshot = ledger.snapshot();
        var plan = ReviewedContextRetirement.prepare(snapshot, "binding", archive(), json);
        String handle = ReviewedContextRetirement.archivePacket(plan, review(plan), archive(), "reviewed", json);
        assertTrue(ledger.commitCompaction(plan.version(), plan.coveredThrough(), ReviewedContextRetirement.receipt(plan, review(plan), handle),
                "reviewed-prefix-v1", "openai", "model", 1000, 100));
        var restored = new ConversationLedger(json); restored.configureSession(ledgerSession);
        assertEquals(snapshot.allEvents(), restored.snapshot().allEvents());
        assertEquals(2, restored.snapshot().activeEntries().size());
        assertEquals("newest request must remain exact 😀", restored.snapshot().activeEntries().get(1).content);
        var evidence = restored.snapshot().allEvents().get(2).outcome();
        assertTrue(evidence.error());
        assertTrue(archive().verify(evidence.rawResultHandle(), evidence.rawDigest(), evidence.rawChars()));
    }

    private static void assertUnchanged(ConversationLedger.Snapshot expected, ConversationLedger.Snapshot actual) {
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.checkpoint(), actual.checkpoint());
        assertEquals(expected.allEvents(), actual.allEvents());
    }

    @Test void attachmentBearingOlderScopesRemainActiveWithoutAnAttachmentProof() {
        var ledger = new ConversationLedger(json);
        ledger.append(CompactionService.ConversationEntry.user("old image", List.of(
                new CompactionService.Attachment("image.png", "image/png", true, "missing", 10))));
        ledger.append(CompactionService.ConversationEntry.assistant("done"));
        ledger.append(CompactionService.ConversationEntry.user("newest"));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json));
    }

    @Test void corruptedLineIndexFailsProofEvenWhenPayloadBytesAreIntact() throws Exception {
        var ledger = ledger(); completed(ledger, false);
        var evidence = ledger.snapshot().allEvents().get(2).outcome();
        assertTrue(archive().verify(evidence.rawResultHandle(), evidence.rawDigest(), evidence.rawChars()));
        String[] parts = evidence.rawResultHandle().split("\\.");
        Path index = directory.resolve("conversations").resolve(parts[1]).resolve("result-cache").resolve(parts[2]).resolve("lines.idx");
        byte[] bytes = Files.readAllBytes(index);
        bytes[7] = 1; // Same file size, wrong first byte offset: not an exact recovery route.
        Files.write(index, bytes);
        assertFalse(archive().verify(evidence.rawResultHandle(), evidence.rawDigest(), evidence.rawChars()));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json));
    }

    @Test void truncatedReturnedOutputCannotSealAReviewedScope() throws Exception {
        var ledger = new ConversationLedger(json);
        String raw = "partial stdout";
        String handle = archive().store("reviewed", "bash", raw, Map.of());
        var outcome = new CompactionService.ToolOutcome(false, true, "{}", handle,
                ReviewedContextRetirement.sha256(raw), raw.length(), 0, true);
        ledger.append(CompactionService.ConversationEntry.user("old"));
        ledger.append(CompactionService.ConversationEntry.toolCall("bash", "a", "{}"));
        ledger.append(CompactionService.ConversationEntry.toolResult("bash", "a", raw, outcome));
        ledger.append(CompactionService.ConversationEntry.assistant("done"));
        ledger.append(CompactionService.ConversationEntry.user("new"));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json));
    }

    @Test void missingOrCorruptedRawArchiveBlocksRetirement() throws Exception {
        var ledger = ledger(); completed(ledger, false);
        var evidence = ledger.snapshot().allEvents().get(2).outcome();
        String[] parts = evidence.rawResultHandle().split("\\.");
        Path metadata = directory.resolve("conversations").resolve(parts[1]).resolve("result-cache").resolve(parts[2]).resolve("metadata.json");
        Files.writeString(metadata, "{}");
        assertFalse(archive().verify(evidence.rawResultHandle(), evidence.rawDigest(), evidence.rawChars()));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedContextRetirement.prepare(ledger.snapshot(), "binding", archive(), json));
    }
}
