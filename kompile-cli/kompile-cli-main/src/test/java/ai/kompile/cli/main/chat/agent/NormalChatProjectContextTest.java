package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.ToolResultStore;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalChatProjectContextTest {

    @TempDir
    Path tempDir;

    @Test
    void oversizedProjectContextFailsClearly() throws Exception {
        Path workingDirectory = tempDir.resolve("oversized-project");
        Files.createDirectories(workingDirectory);
        Files.writeString(workingDirectory.resolve("AGENTS.md"), "x".repeat(128));
        String previous = System.getProperty("kompile.chat.maxProjectContextChars");
        System.setProperty("kompile.chat.maxProjectContextChars", "32");
        try {
            assertThrows(IllegalStateException.class,
                    () -> ProjectChatContext.load(workingDirectory));
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.chat.maxProjectContextChars");
            } else {
                System.setProperty("kompile.chat.maxProjectContextChars", previous);
            }
        }
    }

    @Test
    void normalChatSystemPromptIncludesAgentsMdAndLoadedSkillCatalog() throws Exception {
        Path workingDirectory = tempDir.resolve("normal-chat-project");
        Files.createDirectories(workingDirectory);
        Files.writeString(workingDirectory.resolve("AGENTS.md"),
                "NORMAL_CHAT_AGENTS_MARKER");

        Path skillsDirectory = workingDirectory.resolve(".kompile").resolve("skills");
        Files.createDirectories(skillsDirectory);
        Files.writeString(skillsDirectory.resolve("normal-context-check.md"), """
                ---
                name: normal-context-check
                description: Normal chat project context check
                ---
                NORMAL_CHAT_SKILL_INSTRUCTION {{args}}
                """);

        SkillRegistry skills = new SkillRegistry();
        for (SkillConfig custom : new CustomSkillLoader(workingDirectory).loadAll().values()) {
            skills.register(custom);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        AgentRegistry agents = new AgentRegistry();
        AgenticChatLoop loop = new AgenticChatLoop(
                "http://unused.invalid", objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), agents, workingDirectory, null, null, skills);

        String systemPrompt = loop.buildSystemPrompt(agents.getDefault());
        SkillConfig customSkill = skills.get("normal-context-check");

        assertTrue(systemPrompt.contains("NORMAL_CHAT_AGENTS_MARKER"));
        assertTrue(systemPrompt.contains("# Kompile Skills"));
        assertTrue(systemPrompt.contains("/normal-context-check"));
        assertTrue(systemPrompt.contains("Normal chat project context check"));
        assertNotNull(customSkill);
        assertTrue(customSkill.expandTemplate("requested work")
                .contains("NORMAL_CHAT_SKILL_INSTRUCTION requested work"));
    }

    @Test
    void toolResultInventoryDoesNotRewriteTheSystemPrompt() throws Exception {
        Path workingDirectory = tempDir.resolve("cache-stable-project");
        Files.createDirectories(workingDirectory);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRegistry agents = new AgentRegistry();
        AgenticChatLoop loop = new AgenticChatLoop(
                "http://unused.invalid", objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), agents, workingDirectory, null, null,
                new SkillRegistry());

        var constructor = ToolResultStore.class.getDeclaredConstructor(String.class, Path.class);
        constructor.setAccessible(true);
        ToolResultStore store = constructor.newInstance(
                "cache-stable-session", tempDir.resolve("tool-results"));
        var field = AgenticChatLoop.class.getDeclaredField("toolResultStore");
        field.setAccessible(true);
        field.set(loop, store);
        String before = loop.buildSystemPrompt(agents.getDefault());

        store.save("read", "call-1", "{}", "changing tool output", false);
        String after = loop.buildSystemPrompt(agents.getDefault());

        assertEquals(before, after);
        assertTrue(after.contains("_index.txt"));
        assertFalse(after.contains("0001-read.txt"));
    }
}
