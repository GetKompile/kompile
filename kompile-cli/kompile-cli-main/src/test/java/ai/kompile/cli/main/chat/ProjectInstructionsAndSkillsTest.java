package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgentsMdLoader;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInstructionsAndSkillsTest {

    @TempDir
    Path tempDir;

    @Test
    void directProjectContextLoadsAgentsHierarchyAndCustomSkills() throws Exception {
        Path project = tempDir.resolve("project");
        Path workingDirectory = project.resolve("module");
        Files.createDirectories(workingDirectory);
        Files.writeString(project.resolve("AGENTS.md"), "ROOT_AGENTS_CONTEXT_MARKER");
        Files.writeString(workingDirectory.resolve("AGENTS.md"), "MODULE_AGENTS_CONTEXT_MARKER");

        Path skillsDirectory = workingDirectory.resolve(".kompile").resolve("skills");
        Files.createDirectories(skillsDirectory);
        Files.writeString(skillsDirectory.resolve("context-check.md"), """
                ---
                name: context-check
                description: Project context loading check
                ---
                CUSTOM_SKILL_CONTEXT_MARKER {{args}}
                """);

        String agentsMd = new AgentsMdLoader(workingDirectory).load();
        Map<String, SkillConfig> skills = new CustomSkillLoader(workingDirectory).loadAll();

        assertTrue(agentsMd.contains("ROOT_AGENTS_CONTEXT_MARKER"));
        assertTrue(agentsMd.contains("MODULE_AGENTS_CONTEXT_MARKER"));
        assertTrue(agentsMd.indexOf("ROOT_AGENTS_CONTEXT_MARKER")
                < agentsMd.indexOf("MODULE_AGENTS_CONTEXT_MARKER"));
        SkillConfig customSkill = skills.get("context-check");
        assertNotNull(customSkill);
        assertEquals("CUSTOM_SKILL_CONTEXT_MARKER requested work",
                customSkill.expandTemplate("requested work"));
    }

    @Test
    void emulatedContextCombinesAgentsMdAndSkillsThenRestoresOriginalFile() throws Exception {
        Path workingDirectory = tempDir.resolve("managed-project");
        Files.createDirectories(workingDirectory);
        Path agentsMd = workingDirectory.resolve("AGENTS.md");
        String originalInstructions = "ORIGINAL_PROJECT_INSTRUCTIONS\n";
        Files.writeString(agentsMd, originalInstructions);

        Path skillsDirectory = workingDirectory.resolve(".kompile").resolve("skills");
        Files.createDirectories(skillsDirectory);
        Files.writeString(skillsDirectory.resolve("managed-context-check.md"), """
                ---
                name: managed-context-check
                description: Managed project context loading check
                ---
                MANAGED_CUSTOM_SKILL_MARKER {{args}}
                """);

        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        command.agent = "opencode";
        command.workingDir = workingDirectory.toString();
        command.injectSkills = true;
        command.systemPromptManager = SystemPromptManager.resolve(
                "CENTRAL_SYSTEM_PROMPT_MARKER", null, null);

        try {
            command.injectConfiguredContext();

            String combined = Files.readString(agentsMd);
            assertTrue(combined.contains("CENTRAL_SYSTEM_PROMPT_MARKER"));
            assertTrue(combined.contains("ORIGINAL_PROJECT_INSTRUCTIONS"));
            assertTrue(combined.contains("# Kompile Skills"));
            assertTrue(combined.contains("MANAGED_CUSTOM_SKILL_MARKER"));
        } finally {
            command.cleanupConfiguredContext();
        }
        // The command also performs an outer safety cleanup after its REPL cleanup.
        command.cleanupConfiguredContext();

        assertEquals(originalInstructions, Files.readString(agentsMd));
        assertFalse(Files.exists(workingDirectory.resolve("AGENTS.md.kompile-backup")));
        assertFalse(Files.exists(workingDirectory.resolve("AGENTS.md.kompile-skills-backup")));
    }
}
