package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProductPromptIsolationTest {
    @TempDir Path workspace;

    @Test void productContextDoesNotLoadWorkspaceInstructionsOrCodingCatalogs() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "UNTRUSTED WORKSPACE INSTRUCTION");
        Files.createDirectories(workspace.resolve(".kompile/skills"));
        Files.writeString(workspace.resolve(".kompile/skills/other.md"), "Unrelated coding workflow");
        ProjectChatContext context = ProjectChatContext.forProduct(workspace);
        assertEquals(workspace.toAbsolutePath().normalize(), context.workingDirectory());
        assertEquals("", context.renderSystemPrompt());
        assertEquals("", context.renderSkillsPrompt());
        assertTrue(context.agentsMdFiles().isEmpty());
        assertTrue(context.skillRegistry().all().isEmpty());
        assertTrue(Files.readString(workspace.resolve("AGENTS.md")).contains("UNTRUSTED"));
    }

    @Test void ordinarySkillRegistryRetainsItsDefaults() {
        assertFalse(new SkillRegistry().all().isEmpty());
        assertTrue(SkillRegistry.empty().all().isEmpty());
    }
}
