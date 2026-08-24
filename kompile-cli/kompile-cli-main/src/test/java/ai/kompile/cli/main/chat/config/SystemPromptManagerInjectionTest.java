package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptManagerInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void injectionRejectsSymlinkedAgentsFile() throws Exception {
        Path outside = tempDir.resolve("outside.md");
        Files.writeString(outside, "DO_NOT_TOUCH");
        Files.createSymbolicLink(tempDir.resolve("AGENTS.md"), outside);
        SystemPromptManager manager = SystemPromptManager.resolve("PROMPT", null, null);

        assertNull(manager.injectInstructionFile("codex", tempDir));
        assertEquals("DO_NOT_TOUCH", Files.readString(outside));
    }

    @Test
    void repeatedInjectionRestoresMissingOriginalFileToMissing() throws Exception {
        Path agents = tempDir.resolve("AGENTS.md");
        SystemPromptManager first = SystemPromptManager.resolve("FIRST_PROMPT", null, null);
        first.injectInstructionFile("codex", tempDir);

        SystemPromptManager second = SystemPromptManager.resolve("SECOND_PROMPT", null, null);
        second.injectInstructionFile("codex", tempDir);
        second.cleanup();
        assertTrue(Files.readString(agents).contains("FIRST_PROMPT"));
        first.cleanup();

        assertFalse(Files.exists(agents));
    }

    @Test
    void cleanupPreservesProjectEditsMadeDuringSession() throws Exception {
        Path agents = tempDir.resolve("AGENTS.md");
        Files.writeString(agents, "ORIGINAL\n");
        SystemPromptManager manager = SystemPromptManager.resolve("PROMPT", null, null);
        manager.injectInstructionFile("codex", tempDir);
        Files.writeString(agents, Files.readString(agents) + "\nSESSION_EDIT\n");

        manager.cleanup();

        assertTrue(Files.readString(agents).contains("ORIGINAL"));
        assertTrue(Files.readString(agents).contains("SESSION_EDIT"));
        assertFalse(Files.readString(agents).contains(SystemPromptManager.MANAGED_PROMPT_BEGIN));
    }

    @Test
    void overlappingInjectionsCleanUpOnlyTheirOwnBlocks() throws Exception {
        Path agents = tempDir.resolve("AGENTS.md");
        String original = "# Project instructions\n\nORIGINAL_MARKER\n";
        Files.writeString(agents, original);

        SystemPromptManager first = SystemPromptManager.resolve("FIRST_PROMPT", null, null);
        first.injectInstructionFile("codex", tempDir);

        // Simulate a crashed first process: a second manager starts before cleanup.
        SystemPromptManager second = SystemPromptManager.resolve("SECOND_PROMPT", null, null);
        second.injectInstructionFile("codex", tempDir);

        String injected = Files.readString(agents);
        assertEquals(2, occurrences(injected, SystemPromptManager.MANAGED_PROMPT_BEGIN));
        assertTrue(injected.contains("SECOND_PROMPT"));
        assertTrue(injected.contains("FIRST_PROMPT"));
        assertTrue(injected.contains("ORIGINAL_MARKER"));

        second.cleanup();
        assertTrue(Files.readString(agents).contains("FIRST_PROMPT"));
        first.cleanup();
        assertEquals(original.strip(), Files.readString(agents).strip());
    }

    @Test
    void injectionDoesNotTouchPreexistingBackupFile() throws Exception {
        Path backup = tempDir.resolve("AGENTS.md.kompile-backup");
        Files.writeString(backup, "USER_BACKUP");
        SystemPromptManager manager = SystemPromptManager.resolve("PROMPT", null, null);
        manager.injectInstructionFile("codex", tempDir);
        manager.cleanup();

        assertEquals("USER_BACKUP", Files.readString(backup));
    }

    private int occurrences(String text, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }
}
