package ai.kompile.cli.main.chat.skill;

import ai.kompile.cli.main.chat.tools.SkillManagerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
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
    void firstPartyPackagesLoadWithoutVendorsAndAllowOverrides(@TempDir Path tempDir) throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousInstall = System.getProperty("kompile.install.dir");
        Path home = tempDir.resolve("home");
        Path install = tempDir.resolve("distribution");
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        System.setProperty("kompile.install.dir", install.toString());
        try {
            Path packaged = install.resolve("lib/skills/first-party-check/SKILL.md");
            Files.createDirectories(packaged.getParent());
            Files.writeString(packaged, "---\nname: first-party-check\n---\nBUNDLED {{args}}\n");
            Path reference = packaged.getParent().resolve("references/guide.md");
            Files.createDirectories(reference.getParent());
            Files.writeString(reference, "Reference, not a separate skill");
            var loaded = new CustomSkillLoader(project).loadAll();
            assertEquals(1, loaded.size());
            assertTrue(loaded.get("first-party-check").getPromptTemplate().contains("BUNDLED"));

            ObjectMapper mapper = new ObjectMapper();
            SkillManagerTool tool = new SkillManagerTool(mapper, project);
            var params = mapper.createObjectNode().put("action", "expand_template")
                    .put("name", "first-party-check").put("args", "works");
            assertTrue(tool.execute(params, null).getOutput().contains("BUNDLED works"));
            for (String action : java.util.List.of("update_skill", "delete_skill")) {
                assertTrue(tool.execute(params.deepCopy().put("action", action), null).isError());
            }
            assertTrue(Files.readString(packaged).contains("BUNDLED"));

            Path userSkill = home.resolve(".kompile/skills/first-party-check/SKILL.md");
            Files.createDirectories(userSkill.getParent());
            Files.writeString(userSkill, "---\nname: first-party-check\n---\nUSER\n");
            assertTrue(new CustomSkillLoader(project).loadAll().get("first-party-check")
                    .getPromptTemplate().contains("USER"));
            Path projectSkill = project.resolve(".kompile/skills/first-party-check.md");
            Files.createDirectories(projectSkill.getParent());
            Files.writeString(projectSkill, "---\nname: first-party-check\n---\nPROJECT\n");
            assertTrue(new CustomSkillLoader(project).loadAll().get("first-party-check")
                    .getPromptTemplate().contains("PROJECT"));
        } finally {
            System.setProperty("user.home", previousHome);
            if (previousInstall == null) System.clearProperty("kompile.install.dir");
            else System.setProperty("kompile.install.dir", previousInstall);
        }
    }

    @Test
    void duplicateProviderSkillsDoNotEmitWarningsButInvalidSkillsStillDo(@TempDir Path project) throws Exception {
        Path first = project.resolve(".claude/skills/quiet-check/SKILL.md");
        Path second = project.resolve(".codex/skills/quiet-check/SKILL.md");
        Path invalid = project.resolve(".codex/skills/invalid/SKILL.md");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.createDirectories(invalid.getParent());
        Files.writeString(first, "---\nname: quiet-check\n---\nFirst provider prompt\n");
        Files.writeString(second, "---\nname: quiet-check\n---\nSecond provider prompt\n");
        Files.writeString(invalid, "---\nname: ../bad\n---\nInvalid prompt\n");

        Logger logger = (Logger) LoggerFactory.getLogger(CustomSkillLoader.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        try {
            for (int reload = 0; reload < 2; reload++) {
                var skills = new CustomSkillLoader(project).loadAll();
                assertTrue(skills.get("quiet-check").getPromptTemplate().contains("First provider prompt"));
            }
            var projectEvents = events.list.stream()
                    .filter(event -> event.getFormattedMessage().contains(project.toString()))
                    .toList();
            assertTrue(projectEvents.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                    && event.getFormattedMessage().contains("Ignoring duplicate provider skill")));
            assertFalse(projectEvents.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)
                    && event.getFormattedMessage().contains("duplicate provider skill")));
            assertTrue(projectEvents.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("invalid name")));
        } finally {
            logger.detachAppender(events);
            events.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

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
    void packageLifecyclePreservesReferencesAndScopes(@TempDir Path temp) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            ObjectMapper mapper = new ObjectMapper();
            Path project = temp.resolve("project");
            SkillManagerTool tool = new SkillManagerTool(mapper, project);
            var create = mapper.createObjectNode().put("action", "create_skill")
                    .put("name", "package-check").put("layout", "package")
                    .put("prompt_template", "ORIGINAL {{args}}");
            assertFalse(tool.execute(create, null).isError());
            Path userPackage = temp.resolve(".kompile/skills/package-check");
            Path reference = userPackage.resolve("references/guide.md");
            Files.createDirectories(reference.getParent());
            Files.writeString(reference, "KEEP REFERENCE");
            assertTrue(tool.execute(create.deepCopy().put("layout", "flat"), null).isError());

            var update = mapper.createObjectNode().put("action", "update_skill")
                    .put("name", "package-check").put("prompt_template", "UPDATED {{args}}");
            assertFalse(tool.execute(update, null).isError());
            assertEquals("KEEP REFERENCE", Files.readString(reference));
            var expand = mapper.createObjectNode().put("action", "expand_template")
                    .put("name", "package-check").put("args", "works");
            assertTrue(tool.execute(expand, null).getOutput().contains("UPDATED works"));

            assertFalse(tool.execute(create.deepCopy().put("project_scope", true), null).isError());
            Path projectPackage = project.resolve(".kompile/skills/package-check");
            assertFalse(tool.execute(update.deepCopy().put("project_scope", false)
                    .put("prompt_template", "USER ONLY"), null).isError());
            assertTrue(Files.readString(projectPackage.resolve("SKILL.md")).contains("ORIGINAL"));
            var delete = mapper.createObjectNode().put("action", "delete_skill").put("name", "package-check");
            assertFalse(tool.execute(delete, null).isError());
            assertFalse(Files.exists(projectPackage));
            assertTrue(Files.exists(reference));
            assertFalse(tool.execute(delete.deepCopy().put("project_scope", false), null).isError());
            assertFalse(Files.exists(userPackage));
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void packageMutationsRejectAmbiguityAndUnsafePaths(@TempDir Path project) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillManagerTool tool = new SkillManagerTool(mapper, project);
        Path root = project.resolve(".kompile/skills");
        Path pkg = root.resolve("safe-package");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("SKILL.md"), "---\nname: safe-package\n---\nOriginal");
        Path flat = root.resolve("safe-package.md");
        Files.writeString(flat, "Flat");
        var params = mapper.createObjectNode().put("name", "safe-package").put("project_scope", true);
        for (String action : java.util.List.of("create_skill", "update_skill", "delete_skill")) {
            assertTrue(tool.execute(params.deepCopy().put("action", action), null).isError());
        }
        Files.delete(flat);
        Path outside = project.resolve("outside.md");
        Files.writeString(outside, "OUTSIDE");
        Files.createSymbolicLink(pkg.resolve("linked.md"), outside);
        assertTrue(tool.execute(params.deepCopy().put("action", "delete_skill"), null).isError());
        assertTrue(Files.exists(pkg.resolve("SKILL.md")));
        assertEquals("OUTSIDE", Files.readString(outside));
        Files.delete(pkg.resolve("linked.md"));
        Files.delete(pkg.resolve("SKILL.md"));
        Files.createSymbolicLink(pkg.resolve("SKILL.md"), outside);
        assertTrue(tool.execute(params.deepCopy().put("action", "update_skill"), null).isError());
        assertTrue(tool.execute(params.deepCopy().put("action", "delete_skill"), null).isError());
        Files.createSymbolicLink(root.resolve("linked-package"), pkg);
        assertTrue(tool.execute(params.deepCopy().put("action", "create_skill")
                .put("name", "linked-package").put("layout", "package"), null).isError());
    }

    @Test
    void flatSkillNamedSkillDoesNotDeleteSkillRoot(@TempDir Path project) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillManagerTool tool = new SkillManagerTool(mapper, project);
        var params = mapper.createObjectNode().put("name", "SKILL").put("project_scope", true);
        assertFalse(tool.execute(params.deepCopy().put("action", "create_skill"), null).isError());
        Path sibling = project.resolve(".kompile/skills/other.md");
        Files.writeString(sibling, "KEEP");
        assertFalse(tool.execute(params.deepCopy().put("action", "delete_skill"), null).isError());
        assertEquals("KEEP", Files.readString(sibling));
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
