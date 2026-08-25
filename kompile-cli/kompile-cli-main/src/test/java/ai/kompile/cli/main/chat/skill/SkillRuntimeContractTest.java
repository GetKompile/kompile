package ai.kompile.cli.main.chat.skill;

import ai.kompile.cli.main.chat.tools.SkillManagerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRuntimeContractTest {

    @Test
    void customSkillLookupAndSlashInvocationAreCaseInsensitive() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(SkillConfig.builder("Deploy")
                .description("Deploy a build")
                .promptTemplate("DEPLOY_MARKER {{args}}")
                .build());

        SkillRegistry.SkillInvocation invocation = registry.resolveInvocation("/DEPLOY staging")
                .orElseThrow();

        assertEquals("Deploy", invocation.skill().getName());
        assertEquals("staging", invocation.arguments());
        assertTrue(invocation.prompt().startsWith("<skill name=\"Deploy\">"));
        assertTrue(invocation.prompt().contains("DEPLOY_MARKER staging"));
        assertTrue(invocation.prompt().endsWith("</skill>"));
    }

    @Test
    void nonSkillInputIsNotRewritten() {
        SkillRegistry registry = new SkillRegistry();

        assertTrue(registry.resolveInvocation("normal chat message").isEmpty());
        assertTrue(registry.resolveInvocation("/unknown command").isEmpty());
    }

    @Test
    void generatedSkillInstructionsOnlyReferenceSupportedManagerAction() {
        String markdown = SkillsMarkdownGenerator.generate(new SkillRegistry().all());

        assertFalse(markdown.contains("action=apply_skill"));
        assertTrue(markdown.contains("action=expand_template"));
    }

    @Test
    void registryRejectsNamesThatCouldEscapeProviderDirectories() {
        SkillRegistry registry = new SkillRegistry();
        SkillConfig unsafe = SkillConfig.builder("../outside")
                .promptTemplate("unsafe")
                .build();

        assertThrows(IllegalArgumentException.class, () -> registry.register(unsafe));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                SkillConfig.builder("help").promptTemplate("shadow host command").build()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                SkillConfig.builder("title").promptTemplate("shadow title command").build()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                SkillConfig.builder("reminder").promptTemplate("shadow reminders").build()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                SkillConfig.builder("reminder-global").promptTemplate("shadow reminders").build()));
    }

    @Test
    void mutationToolRejectsTraversalBeforeTouchingFiles(@TempDir Path tempDir) throws Exception {
        Path victim = tempDir.resolve("victim.md");
        Files.writeString(victim, "KEEP_ME");
        ObjectMapper mapper = new ObjectMapper();
        SkillManagerTool tool = new SkillManagerTool(mapper, tempDir.resolve("project"));
        var params = mapper.createObjectNode();
        params.put("action", "delete_skill");
        params.put("name", "../victim");

        var result = tool.execute(params, null);

        assertTrue(result.isError());
        assertTrue(Files.exists(victim));

        Path root = tempDir.resolve("safe-root");
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve("safe.md"), victim);
        assertThrows(java.io.IOException.class,
                () -> SkillPathPolicy.resolve(root, "safe"));
    }

    @Test
    void providerInjectionRejectsSymlinkRootsAndPreservesConcurrentEdits(
            @TempDir Path tempDir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);

        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(project.resolve(".claude"), outside);
        SkillsInjection unsafe = new SkillsInjection(registry, project);
        assertEquals(0, unsafe.installSkills("claude"));
        assertFalse(Files.exists(outside.resolve("commands/commit.md")));

        Files.delete(project.resolve(".claude"));
        Path agents = project.resolve("AGENTS.md");
        Files.writeString(agents, "ORIGINAL\n");
        SkillsInjection injection = new SkillsInjection(registry, project);
        assertTrue(injection.installSkills("opencode") > 0);
        Files.writeString(agents, Files.readString(agents) + "SESSION_EDIT\n");
        injection.cleanup();

        String remaining = Files.readString(agents);
        assertTrue(remaining.contains("ORIGINAL"));
        assertTrue(remaining.contains("SESSION_EDIT"));
        assertFalse(remaining.contains("BEGIN KOMPILE MANAGED SKILLS"));

        SkillsInjection codex = new SkillsInjection(registry, project);
        assertTrue(codex.installSkills("codex") > 0);
        Path codexSkill = project.resolve(".agents/skills/commit/SKILL.md");
        assertTrue(Files.isRegularFile(codexSkill));
        codex.cleanup();
        assertFalse(Files.exists(codexSkill));

        SkillsInjection gemini = new SkillsInjection(registry, project);
        assertTrue(gemini.installSkills("gemini") > 0);
        assertTrue(Files.readString(project.resolve("GEMINI.md"))
                .contains("# Kompile Skills"));
        gemini.cleanup();
        assertFalse(Files.exists(project.resolve("GEMINI.md")));
    }

    @Test
    void providerScanRejectsAncestorSymlink(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path outside = tempDir.resolve("outside");
        Path secret = outside.resolve("skills/secret.md");
        Files.createDirectories(secret.getParent());
        Files.createDirectories(project);
        Files.writeString(secret, "SECRET_PROVIDER_CONTENT");
        Files.createSymbolicLink(project.resolve(".codex"), outside);

        ObjectMapper mapper = new ObjectMapper();
        SkillManagerTool tool = new SkillManagerTool(mapper, project);
        var params = mapper.createObjectNode();
        params.put("action", "scan_provider_skills");
        var result = tool.execute(params, null);

        assertFalse(result.getOutput().contains("SECRET_PROVIDER_CONTENT"));
    }

    @Test
    void providerPackagedSkillsAreLoadedAndKompileProjectSkillsWin(
            @TempDir Path tempDir) throws Exception {
        String previousHome = System.getProperty("user.home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        System.setProperty("user.home", tempDir.toString());
        try {
            Path providerSkill = tempDir.resolve(".codex/skills/shared-check/SKILL.md");
            Files.createDirectories(providerSkill.getParent());
            Files.writeString(providerSkill, """
                    ---
                    name: shared-check
                    description: Shared provider skill
                    ---
                    PROVIDER_SKILL_MARKER {{args}}
                    """);
            Path providerOnly = tempDir.resolve(".claude/skills/provider-only/SKILL.md");
            Files.createDirectories(providerOnly.getParent());
            Files.writeString(providerOnly, """
                    ---
                    name: Provider-Only
                    description: Provider-only skill
                    ---
                    PROVIDER_ONLY_MARKER {{args}}
                    """);
            Path duplicate = tempDir.resolve(".codex/skills/provider-only/SKILL.md");
            Files.createDirectories(duplicate.getParent());
            Files.writeString(duplicate, """
                    ---
                    name: provider-only
                    description: Duplicate provider skill
                    ---
                    DUPLICATE_MARKER {{args}}
                    """);

            Path projectSkill = project.resolve(".kompile/skills/shared-check.md");
            Files.createDirectories(projectSkill.getParent());
            Files.writeString(projectSkill, """
                    ---
                    name: shared-check
                    description: Project override
                    ---
                    PROJECT_SKILL_MARKER {{args}}
                    """);

            Map<String, SkillConfig> loaded = new CustomSkillLoader(project).loadAll();

            assertTrue(loaded.get("provider-only").getPromptTemplate()
                    .contains("PROVIDER_ONLY_MARKER"));
            assertFalse(loaded.get("provider-only").getPromptTemplate()
                    .contains("DUPLICATE_MARKER"));
            assertTrue(loaded.get("shared-check").getPromptTemplate()
                    .contains("PROJECT_SKILL_MARKER"));

            Path external = tempDir.resolve("external-skill.md");
            Files.writeString(external, """
                    ---
                    name: linked-skill
                    ---
                    LINKED_MARKER
                    """);
            Path linked = tempDir.resolve(".qwen/skills/linked-skill");
            Files.createDirectories(linked.getParent());
            Files.createSymbolicLink(linked, external);
            assertFalse(new CustomSkillLoader(project).loadAll().containsKey("linked-skill"));

            Path outsideProvider = tempDir.resolve("outside-provider");
            Path escapedSkill = outsideProvider.resolve("skills/escaped/SKILL.md");
            Files.createDirectories(escapedSkill.getParent());
            Files.writeString(escapedSkill, """
                    ---
                    name: escaped
                    ---
                    ESCAPED_MARKER
                    """);
            Files.createSymbolicLink(project.resolve(".codex"), outsideProvider);
            assertFalse(new CustomSkillLoader(project).loadAll().containsKey("escaped"));
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }
}
