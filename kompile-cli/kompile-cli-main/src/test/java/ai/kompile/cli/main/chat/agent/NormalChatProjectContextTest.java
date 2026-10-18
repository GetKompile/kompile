package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsInjection;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolResultStore;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
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
    void systemPromptStripsLeakedManagedBlocksFromAgentsMd() throws Exception {
        Path workingDirectory = tempDir.resolve("leaked-blocks-project");
        Files.createDirectories(workingDirectory);
        String systemPromptBlock = SystemPromptManager.MANAGED_PROMPT_BEGIN
                + " leaked-session pid=1\nLEAKED_SYSTEM_PROMPT\n" + SystemPromptManager.MANAGED_PROMPT_END;
        String skillsBlock = SkillsInjection.SKILLS_BEGIN_PREFIX + "leaked-block pid=1 -->\n\n"
                + "LEAKED_SKILLS_BLOCK\n" + SkillsInjection.SKILLS_END_PREFIX + "leaked-block -->\n";
        Files.writeString(workingDirectory.resolve("AGENTS.md"),
                "USER_INSTRUCTIONS_MARKER\n\n" + systemPromptBlock + "\n\n" + skillsBlock);

        String systemPrompt = ProjectChatContext.load(workingDirectory).renderSystemPrompt();

        assertTrue(systemPrompt.contains("USER_INSTRUCTIONS_MARKER"));
        assertFalse(systemPrompt.contains("LEAKED_SYSTEM_PROMPT"));
        assertFalse(systemPrompt.contains("LEAKED_SKILLS_BLOCK"));
        assertFalse(systemPrompt.contains(SystemPromptManager.MANAGED_PROMPT_BEGIN));
        assertFalse(systemPrompt.contains(SkillsInjection.SKILLS_BEGIN_PREFIX));
    }

    @Test
    void nativeClaudeOmitsImportedProjectFileButKeepsOtherInstructionsAndSkills() throws Exception {
        Path project = tempDir.resolve("native-project");
        Path module = Files.createDirectories(project.resolve("module"));
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve("AGENTS.md"), "IMPORTED_PROJECT_RULES");
        Files.writeString(project.resolve("CLAUDE.md"), "# Native instructions\n@AGENTS.md\n");
        Files.writeString(module.resolve("AGENTS.md"), "UNIMPORTED_MODULE_RULES");
        Path global = KompileHome.homeDirectory().toPath();
        Files.createDirectories(global);
        Files.writeString(global.resolve("AGENTS.md"), "GLOBAL_KOMPILE_RULES");
        ProjectChatContext context = ProjectChatContext.load(module, new SkillRegistry());

        String nativePrompt = context.renderSystemPrompt(true);
        assertFalse(nativePrompt.contains("IMPORTED_PROJECT_RULES"));
        assertTrue(nativePrompt.contains("UNIMPORTED_MODULE_RULES"));
        assertTrue(nativePrompt.contains("GLOBAL_KOMPILE_RULES"));
        assertTrue(nativePrompt.contains("# Kompile Skills"));
        assertTrue(context.renderSystemPrompt().contains("IMPORTED_PROJECT_RULES"),
                "API/server providers still need the entire instruction hierarchy");
        assertTrue(context.agentsMdContent().contains("IMPORTED_PROJECT_RULES"),
                "inspection and stored project context remain complete");
    }

    @Test
    void nativeClaudeKeepsInstructionsWhenImportIsOnlyAnExampleOrComment() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("example-project"));
        Files.writeString(project.resolve("AGENTS.md"), "REQUIRED_RULES");
        for (String claude : List.of("```md\n@AGENTS.md\n```\n",
                "~~~\n@AGENTS.md\n~~~\n", "<!--\n@AGENTS.md\n-->\n",
                "````md\n```\n@AGENTS.md\n```\n````\n",
                "~~~~\n~~~\n@AGENTS.md\n~~~~\n",
                "```md\n```not-a-closing-fence\n@AGENTS.md\n```\n",
                "    @AGENTS.md\n", "\t@AGENTS.md\n",
                "Read AGENTS.md when needed.", "@some-other-file.md")) {
            Files.writeString(project.resolve("CLAUDE.md"), claude);
            assertTrue(ProjectChatContext.load(project).renderSystemPrompt(true)
                    .contains("REQUIRED_RULES"), claude);
        }
        Files.delete(project.resolve("CLAUDE.md"));
        assertTrue(ProjectChatContext.load(project).renderSystemPrompt(true).contains("REQUIRED_RULES"));
        Files.createSymbolicLink(project.resolve("CLAUDE.md"), project.resolve("AGENTS.md"));
        assertTrue(ProjectChatContext.load(project).renderSystemPrompt(true).contains("REQUIRED_RULES"));
    }

    @Test
    void instructionDedupIsAppliedOnlyToNativeClaudeRoute() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("route-project"));
        Files.writeString(project.resolve("AGENTS.md"), "NATIVE_IMPORTED_RULES");
        Files.writeString(project.resolve("CLAUDE.md"), "@./AGENTS.md\n");
        ObjectMapper mapper = new ObjectMapper();
        AgentRegistry agents = new AgentRegistry();
        ChatConfig config = new ChatConfig("anthropic", "unused-key", "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, project)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), agents, project, client, null, new SkillRegistry());
            assertFalse(loop.buildSystemPrompt(agents.getDefault()).contains("NATIVE_IMPORTED_RULES"));
            config.setAuthenticationMethod("api_key");
            assertTrue(loop.buildSystemPrompt(agents.getDefault()).contains("NATIVE_IMPORTED_RULES"),
                    "switching to direct API must restore inline instructions");
        }
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
