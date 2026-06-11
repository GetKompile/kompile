package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnforcerDiffArchiveTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private EnforcerDiffArchive archive;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        // Initialize git repo in temp dir for diff capture
        ProcessBuilder pb = new ProcessBuilder("git", "init");
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        pb.start().waitFor();

        // Configure git user for commits
        exec("git", "config", "user.email", "test@test.com");
        exec("git", "config", "user.name", "Test");

        // Create initial commit
        Files.writeString(tempDir.resolve("file.txt"), "initial content\n");
        exec("git", "add", ".");
        exec("git", "commit", "-m", "initial");

        archive = new EnforcerDiffArchive("test-session", tempDir, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (archive != null) {
            archive.purge();
        }
    }

    @Test
    void archiveRootCreated() {
        Path expectedRoot = tempDir.resolve(".kompile").resolve("enforcer-archive").resolve("test-session");
        assertEquals(expectedRoot, archive.getArchiveRoot());
        assertTrue(Files.isDirectory(expectedRoot.resolve("snapshots")));
    }

    @Test
    void beginAndCompleteTurnCreatesSnapshot() throws IOException {
        // Modify a file to simulate agent work
        Files.writeString(tempDir.resolve("file.txt"), "modified content\n");

        EnforcerDiffArchive.TurnSnapshot snapshot = archive.beginTurn();
        assertNotNull(snapshot);
        assertEquals("turn-001", snapshot.getTurnId());

        archive.completeTurn(snapshot, false);

        // Check metadata exists
        Path metaFile = archive.getArchiveRoot().resolve("snapshots").resolve("turn-001").resolve("metadata.json");
        assertTrue(Files.exists(metaFile));

        String meta = Files.readString(metaFile, StandardCharsets.UTF_8);
        assertTrue(meta.contains("\"violated\" : false"));
        assertTrue(meta.contains("\"turnId\" : \"turn-001\""));
    }

    @Test
    void violatedTurnFlaggedInManifest() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "bad content\n");

        EnforcerDiffArchive.TurnSnapshot snapshot = archive.beginTurn();
        archive.completeTurn(snapshot, true);

        List<EnforcerDiffArchive.TurnMetadata> turns = archive.listTurns();
        assertEquals(1, turns.size());
        assertTrue(turns.get(0).violated());
    }

    @Test
    void rollbackRestoresOriginalFile() throws IOException {
        // Modify file
        Files.writeString(tempDir.resolve("file.txt"), "modified by agent\n");

        EnforcerDiffArchive.TurnSnapshot snapshot = archive.beginTurn();
        archive.completeTurn(snapshot, true);

        // The original was archived — rollback should restore it
        EnforcerDiffArchive.RollbackResult result = archive.rollback("turn-001");
        assertTrue(result.success());
        assertFalse(result.restoredFiles().isEmpty());
    }

    @Test
    void rollbackViolationsOnlyUndoesViolatedTurns() throws IOException {
        // Turn 1: OK change
        Files.writeString(tempDir.resolve("file.txt"), "good change\n");
        EnforcerDiffArchive.TurnSnapshot s1 = archive.beginTurn();
        archive.completeTurn(s1, false);

        // Commit the good change
        exec("git", "add", ".");
        exec("git", "commit", "-m", "good");

        // Turn 2: Violated change
        Files.writeString(tempDir.resolve("file.txt"), "bad change\n");
        EnforcerDiffArchive.TurnSnapshot s2 = archive.beginTurn();
        archive.completeTurn(s2, true);

        List<EnforcerDiffArchive.TurnMetadata> turns = archive.listTurns();
        assertEquals(2, turns.size());
        assertFalse(turns.get(0).violated());
        assertTrue(turns.get(1).violated());

        // Rollback violations only
        EnforcerDiffArchive.RollbackResult result = archive.rollbackViolations();
        assertTrue(result.success());
    }

    @Test
    void getDiffForTurn() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "changed\n");
        EnforcerDiffArchive.TurnSnapshot snapshot = archive.beginTurn();
        archive.completeTurn(snapshot, false);

        String diff = archive.getTurnDiff("turn-001");
        assertNotNull(diff);
        // Git diff should show some content (the change from initial to modified)
    }

    @Test
    void purgeRemovesAllData() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "x\n");
        EnforcerDiffArchive.TurnSnapshot snapshot = archive.beginTurn();
        archive.completeTurn(snapshot, false);

        assertTrue(Files.isDirectory(archive.getArchiveRoot()));
        archive.purge();
        assertFalse(Files.isDirectory(archive.getArchiveRoot()));
    }

    @Test
    void purgeAllRemovesAllSessions() throws IOException {
        // Create a second archive
        EnforcerDiffArchive archive2 = new EnforcerDiffArchive("session-2", tempDir, objectMapper);

        assertTrue(Files.isDirectory(archive.getArchiveRoot()));
        assertTrue(Files.isDirectory(archive2.getArchiveRoot()));

        EnforcerDiffArchive.purgeAll(tempDir);
        assertFalse(Files.isDirectory(tempDir.resolve(".kompile").resolve("enforcer-archive")));
    }

    @Test
    void multipleSnapshotsIncrementTurnCounter() throws IOException {
        for (int i = 0; i < 3; i++) {
            Files.writeString(tempDir.resolve("file.txt"), "turn " + i + "\n");
            EnforcerDiffArchive.TurnSnapshot snapshot = archive.beginTurn();
            archive.completeTurn(snapshot, i == 1); // Only turn 2 violated
        }

        List<EnforcerDiffArchive.TurnMetadata> turns = archive.listTurns();
        assertEquals(3, turns.size());
        assertEquals("turn-001", turns.get(0).turnId());
        assertEquals("turn-002", turns.get(1).turnId());
        assertEquals("turn-003", turns.get(2).turnId());
        assertFalse(turns.get(0).violated());
        assertTrue(turns.get(1).violated());
        assertFalse(turns.get(2).violated());
    }

    @Test
    void nonExistentTurnReturnsFailure() throws IOException {
        EnforcerDiffArchive.RollbackResult result = archive.rollback("turn-999");
        assertFalse(result.success());
        assertTrue(result.message().contains("not found"));
    }

    private void exec(String... cmd) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes(); // drain
            proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
