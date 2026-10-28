package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resumable-subagent registry semantics: sidecar visibility, name allocation,
 * resolution, locking, prune — and the identity-boundary guards that keep
 * resumable subagent runs out of the main resume listing (subagents stay
 * subagents) while keeping main conversations out of the subagent registry.
 */
@TemporaryUserHome
class SubagentResumeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path conversationsDir() throws Exception {
        // @TemporaryUserHome is class-scoped, so per-test isolation comes from a
        // per-test directory under the swapped home rather than the shared dir.
        Path dir = ai.kompile.cli.common.KompileHome.homeDirectory().toPath()
                .resolve("conversations")
                .resolve("test-" + java.util.UUID.randomUUID());
        Files.createDirectories(dir);
        return dir;
    }

    /** Writes the three artifacts of one resumable run. */
    private void seedRun(Path dir, String sessionId, String name, String cwd) throws Exception {
        SubagentResumeMetadata metadata = SubagentResumeMetadata.create(
                sessionId, name, "explore", "parent-1", null, "test-model");
        metadata.write(SubagentResumeMetadata.sidecarFor(dir, sessionId), MAPPER);
        SubagentLedger ledger = new SubagentLedger(
                SubagentLedger.fileFor(dir, sessionId), MAPPER);
        ledger.open();
        ledger.append(CompactionService.ConversationEntry.user("task for " + sessionId));
        ledger.append(CompactionService.ConversationEntry.assistant("done"));
        ledger.close();
        String header = "──── Conversation: " + sessionId + " ────\n"
                + "Started: now\nAgent:   kompile\nCWD:     " + cwd + "\n\n> user hello\n";
        Files.writeString(dir.resolve(sessionId + ".txt"), header);
    }

    private static CompactionService.ConversationEntry toolCall(String id) {
        return CompactionService.ConversationEntry.toolCall("grep", id, "{\"pattern\":\"x\"}");
    }

    private static CompactionService.ConversationEntry toolResult(String id, String body) {
        return CompactionService.ConversationEntry.toolResult("grep", id, body);
    }

    // ------------------------------------------------------------- ledger

    @Test
    void ledgerRoundTripsEntries() throws Exception {
        Path dir = conversationsDir();
        SubagentLedger ledger = new SubagentLedger(
                SubagentLedger.fileFor(dir, "subagent-a-1"), MAPPER);
        ledger.open();
        ledger.append(CompactionService.ConversationEntry.user("hello"));
        ledger.append(toolCall("call-1"));
        ledger.append(toolResult("call-1", "output body"));
        ledger.append(CompactionService.ConversationEntry.assistant("answer"));
        ledger.close();

        List<CompactionService.ConversationEntry> read =
                SubagentLedger.read(SubagentLedger.fileFor(dir, "subagent-a-1"), MAPPER);
        assertEquals(4, read.size());
        assertEquals(CompactionService.EntryType.USER, read.get(0).type);
        assertEquals("hello", read.get(0).content);
        assertEquals(CompactionService.EntryType.TOOL_CALL, read.get(1).type);
        assertEquals("grep", read.get(1).toolName);
        assertEquals("call-1", read.get(1).toolCallId);
        assertEquals(CompactionService.EntryType.TOOL_RESULT, read.get(2).type);
        assertEquals("output body", read.get(2).content);
        assertEquals(CompactionService.EntryType.ASSISTANT, read.get(3).type);
        assertEquals("answer", read.get(3).content);
    }

    @Test
    void ledgerTornTrailingLineIsDroppedNotFatal() throws Exception {
        Path dir = conversationsDir();
        Files.createDirectories(dir);
        Path file = SubagentLedger.fileFor(dir, "subagent-torn-1");
        Files.writeString(file, "{\"v\":1}\n"
                + "{\"type\":\"USER\",\"role\":\"user\",\"content\":\"good line\"}\n"
                + "{\"type\":\"USER\",\"role\":\"user\",\"content\":\"torn trunca");
        List<CompactionService.ConversationEntry> read = SubagentLedger.read(file, MAPPER);
        assertEquals(1, read.size());
        assertEquals("good line", read.get(0).content);
    }

    @Test
    void ledgerRewriteReplacesWholeFile() throws Exception {
        Path dir = conversationsDir();
        SubagentLedger ledger = new SubagentLedger(
                SubagentLedger.fileFor(dir, "subagent-rew-1"), MAPPER);
        ledger.open();
        ledger.append(CompactionService.ConversationEntry.user("old-1"));
        ledger.append(CompactionService.ConversationEntry.user("old-2"));
        ledger.close();
        ledger.rewrite(List.of(
                CompactionService.ConversationEntry.system("compacted summary"),
                CompactionService.ConversationEntry.user("new")));
        List<CompactionService.ConversationEntry> read =
                SubagentLedger.read(SubagentLedger.fileFor(dir, "subagent-rew-1"), MAPPER);
        assertEquals(2, read.size());
        assertEquals(CompactionService.EntryType.SYSTEM, read.get(0).type);
        assertEquals("new", read.get(1).content);
        ledger.close();
    }

    @Test
    void ledgerRewriteKeepsAppendingAndOpenIsIdempotent() throws Exception {
        Path file = SubagentLedger.fileFor(conversationsDir(), "subagent-rewrite-live");
        SubagentLedger ledger = new SubagentLedger(file, MAPPER);
        ledger.open();
        ledger.open();
        try {
            ledger.append(CompactionService.ConversationEntry.user("before"));
            ledger.rewrite(List.of(CompactionService.ConversationEntry.system("summary")));
            ledger.append(CompactionService.ConversationEntry.user("after"));
        } finally {
            ledger.close();
        }
        var entries = SubagentLedger.read(file, MAPPER);
        assertEquals(List.of("summary", "after"), entries.stream().map(e -> e.content).toList());
    }

    @Test
    void openLedgerExcludesOtherWritersAndPruningUntilClosed() throws Exception {
        Path dir = conversationsDir();
        String id = "subagent-active-ledger";
        Path file = SubagentLedger.fileFor(dir, id);
        SubagentLedger first = new SubagentLedger(file, MAPPER);
        SubagentLedger second = new SubagentLedger(file, MAPPER);
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        first.open();
        try {
            first.append(CompactionService.ConversationEntry.user("live"));
            assertTrue(service.isLocked(id));
            assertNotNull(service.tryLock(id), "another service cannot acquire the active writer lock");
            assertThrows(IllegalStateException.class, second::open);
            assertNotNull(service.prune(id), "prune must not delete an active ledger");
            assertTrue(Files.exists(file));
            assertOtherJvmCannotLock(dir, id);
        } finally {
            first.close();
            second.close();
        }
        assertFalse(service.isLocked(id));
        second.open();
        second.close();
        assertNull(service.prune(id));
    }

    private static void assertOtherJvmCannotLock(Path directory, String id) throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(javaBinary, "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WriterLockProbe.class.getName(), directory.toString(), id).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "writer probe timed out");
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static class WriterLockProbe {
        public static void main(String[] args) {
            SubagentResumeService service = new SubagentResumeService(Path.of(args[0]), MAPPER);
            String error = service.tryLock(args[1]);
            if (error == null) {
                service.unlock(args[1]);
                throw new AssertionError("a second JVM acquired an active child's writer lock");
            }
        }
    }

    @Test
    void unreadableOrMissingLedgerYieldsEmptyList() throws Exception {
        assertTrue(SubagentLedger.read(
                conversationsDir().resolve("nope.jsonl"), MAPPER).isEmpty());
    }

    // ------------------------------------------------------------ sidecar

    @Test
    void sidecarRequiresSubagentPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> SubagentResumeMetadata.create("main-chat-session", null, "x", null, null, null));
    }

    @Test
    void nameValidation() {
        assertNull(SubagentResumeMetadata.nameError("dsp-fix"));
        assertNull(SubagentResumeMetadata.nameError("a1_b-c"));
        assertNotNull(SubagentResumeMetadata.nameError(""));
        assertNotNull(SubagentResumeMetadata.nameError("Uppercase"));
        assertNotNull(SubagentResumeMetadata.nameError("-lead"));
        assertNotNull(SubagentResumeMetadata.nameError("has space"));
        assertNotNull(SubagentResumeMetadata.nameError("a".repeat(65)));
    }

    private static void assertThrows(Class<? extends Exception> type, Runnable action) {
        try {
            action.run();
            org.junit.jupiter.api.Assertions.fail("expected " + type.getSimpleName());
        } catch (Exception e) {
            assertTrue(type.isInstance(e), "expected " + type.getSimpleName() + ", got " + e);
        }
    }

    // -------------------------------------------------------------- registry

    @Test
    void listFindsOnlySidecarRuns() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-one-aaaaaaaa", "one", "/tmp/proj");
        seedRun(dir, "subagent-two-bbbbbbbb", "two", "/tmp/proj");
        // A non-resumable subagent-shaped transcript must not appear.
        Files.writeString(dir.resolve("subagent-three-cccccccc.txt"),
                "──── Conversation: subagent-three-cccccccc ────\n");
        // A main-chat transcript must not appear either.
        Files.writeString(dir.resolve("main-session-1.txt"),
                "──── Conversation: main-session-1 ────\n");

        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        List<SubagentResumeService.Summary> all = service.list();
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(s -> s.sessionId().startsWith("subagent-")));
        assertTrue(all.stream().noneMatch(s -> s.sessionId().equals("main-session-1")));
        // Newest first.
        assertEquals("subagent-two-bbbbbbbb", all.get(0).sessionId());
    }

    @Test
    void listNeverContainsMainConversations() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-guard-1", "guarded", "/tmp/proj");
        for (int i = 0; i < 3; i++) {
            Files.writeString(dir.resolve("cli-main-" + i + ".txt"),
                    "──── Conversation: cli-main-" + i + " ────\nTitle: main work\n");
        }
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        List<SubagentResumeService.Summary> rows = service.list();
        assertEquals(1, rows.size());
        assertEquals("subagent-guard-1", rows.get(0).sessionId());
    }

    @Test
    void resolutionByIdNameAndPrefix() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-res-11111111", "alpha-investigation", "/p");
        seedRun(dir, "subagent-res-22222222", "alpha-review", "/p");
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);

        assertTrue(service.resolve("subagent-res-11111111").ok());
        assertTrue(service.resolve("alpha-investigation").ok());
        assertTrue(service.resolve("alpha-review").ok());
        assertFalse(service.resolve("alpha").ok(), "prefix matching two names is ambiguous");
        assertFalse(service.resolve("missing").ok());
        assertFalse(service.resolve("main-session-1").ok(),
                "a main-conversation id must never resolve here");
    }

    @Test
    void nameConflictRejectsDuplicates() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-dup-11111111", "taken-name", "/p");
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        assertNotNull(service.nameConflict("taken-name"));
        assertNull(service.nameConflict("free-name"));
    }

    // -------------------------------------------------------------- locking

    @Test
    void lockIsExclusiveWithinThisJvmAndReleasable() throws Exception {
        SubagentResumeService service = new SubagentResumeService(conversationsDir(), MAPPER);
        assertNull(service.tryLock("subagent-lock-1"));
        assertTrue(service.isLocked("subagent-lock-1"));
        // A second acquire in the same JVM is refused (would double-write the ledger).
        assertNotNull(service.tryLock("subagent-lock-1"));
        service.unlock("subagent-lock-1");
        // After release, lockable again.
        assertNull(service.tryLock("subagent-lock-1"));
        service.unlock("subagent-lock-1");
    }

    // ---------------------------------------------------------------- prune

    @Test
    void pruneRemovesAllArtifactsAndRefusesNonSubagentIds() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-prune-111111", "prunable", "/p");
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        assertNull(service.prune("subagent-prune-111111"));
        assertFalse(Files.exists(SubagentResumeMetadata.sidecarFor(dir, "subagent-prune-111111")));
        assertFalse(Files.exists(SubagentLedger.fileFor(dir, "subagent-prune-111111")));
        assertNotNull(service.prune("main-session-1"), "non-subagent ids are refused");
    }

    // ------------------------------------------------- main-resume boundary

    @Test
    void resumableSubagentsStayOutOfTheMainResumeListing() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-boundary-1", "boundary", "/p");
        // The main listing (which scans the class-scoped home) must reject every
        // subagent- transcript, resumable or not.
        List<ChatHistory.ConversationSummary> mainList = ChatHistory.listResumableConversations();
        assertTrue(mainList.stream().noneMatch(s -> s.sessionId().startsWith("subagent-")),
                "resumable subagent runs leaked into the main resume listing");
        // ...while the subagent registry still sees it.
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        assertEquals(1, service.list().size());
    }

    @Test
    void cwdScopedListingMatchesOnlySameDirectory() throws Exception {
        Path dir = conversationsDir();
        seedRun(dir, "subagent-cwd-11111111", "cwd-one", "/tmp/proj-a");
        seedRun(dir, "subagent-cwd-22222222", "cwd-two", "/tmp/proj-b");
        SubagentResumeService service = new SubagentResumeService(dir, MAPPER);
        assertEquals(1, service.list(Path.of("/tmp/proj-a")).size());
        assertEquals("subagent-cwd-11111111",
                service.list(Path.of("/tmp/proj-a")).get(0).sessionId());
        assertEquals(2, service.list((Path) null).size());
    }
}
