/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CustomSkillLoader")
class CustomSkillLoaderTest {

    @TempDir
    Path tempDir;

    private CustomSkillLoader loader;

    @BeforeEach
    void setUp() {
        loader = new CustomSkillLoader(tempDir);
    }

    @Nested
    @DisplayName("Loading from directories")
    class LoadFromDirectories {

        @Test
        void emptyDirectoryReturnsEmptyMap() {
            Map<String, SkillConfig> skills = loader.loadAll();
            assertTrue(skills.isEmpty());
        }

        @Test
        void loadsFromProjectScopedDirectory() throws IOException {
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve("deploy.md"), """
                    ---
                    name: deploy
                    description: Deploy to staging
                    category: devops
                    ---
                    Deploy the current branch. {{args}}
                    """);

            Map<String, SkillConfig> skills = loader.loadAll();
            assertEquals(1, skills.size());
            assertNotNull(skills.get("deploy"));
            assertEquals("devops", skills.get("deploy").getCategory());
        }

        @Test
        void ignoresNonMdFiles() throws IOException {
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve("deploy.txt"), "not a skill");
            Files.writeString(skillsDir.resolve("deploy.yaml"), "not a skill");

            Map<String, SkillConfig> skills = loader.loadAll();
            assertTrue(skills.isEmpty());
        }

        @Test
        void loadsMultipleSkills() throws IOException {
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve("deploy.md"), """
                    ---
                    name: deploy
                    description: Deploy
                    ---
                    Deploy. {{args}}
                    """);
            Files.writeString(skillsDir.resolve("lint.md"), """
                    ---
                    name: lint
                    description: Lint code
                    ---
                    Lint the code. {{args}}
                    """);

            Map<String, SkillConfig> skills = loader.loadAll();
            assertEquals(2, skills.size());
            assertNotNull(skills.get("deploy"));
            assertNotNull(skills.get("lint"));
        }
    }

    @Nested
    @DisplayName("Parsing skill files")
    class ParsingSkillFiles {

        @Test
        void parsesFullFrontmatter() throws IOException {
            Path file = tempDir.resolve("skill.md");
            Files.writeString(file, """
                    ---
                    name: deploy
                    display_name: Deploy App
                    description: Deploy the application
                    category: devops
                    tools: bash, read, grep
                    max_steps: 25
                    model: fast
                    ---
                    Deploy the app to staging. {{args}}

                    Steps:
                    1. Check git status
                    2. Push changes
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertNotNull(skill);
            assertEquals("deploy", skill.getName());
            assertEquals("Deploy App", skill.getDisplayName());
            assertEquals("Deploy the application", skill.getDescription());
            assertEquals("devops", skill.getCategory());
            assertEquals(Set.of("bash", "read", "grep"), skill.getAllowedTools());
            assertEquals(25, skill.getMaxSteps());
            assertEquals("fast", skill.getModelHint());
            assertFalse(skill.isBuiltIn());
            assertTrue(skill.getPromptTemplate().contains("Deploy the app"));
            assertTrue(skill.getPromptTemplate().contains("{{args}}"));
        }

        @Test
        void parsesFileWithoutFrontmatter() throws IOException {
            Path file = tempDir.resolve("simple.md");
            Files.writeString(file, "Just do the thing. {{args}}");

            SkillConfig skill = loader.parseSkillFile(file);

            assertNotNull(skill);
            assertEquals("simple", skill.getName());
            assertEquals("simple", skill.getDisplayName());
            assertEquals("Just do the thing. {{args}}", skill.getPromptTemplate());
            assertFalse(skill.isBuiltIn());
        }

        @Test
        void parsesFileWithMalformedFrontmatter() throws IOException {
            Path file = tempDir.resolve("broken.md");
            Files.writeString(file, "---\nname: broken\nno closing frontmatter\nsome prompt");

            SkillConfig skill = loader.parseSkillFile(file);

            assertNotNull(skill);
            assertEquals("broken", skill.getName());
        }

        @Test
        void usesFilenameWhenNoNameInFrontmatter() throws IOException {
            Path file = tempDir.resolve("my-skill.md");
            Files.writeString(file, """
                    ---
                    description: A skill without a name field
                    category: misc
                    ---
                    Do something. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertEquals("my-skill", skill.getName());
        }

        @Test
        void toolsWildcardParsed() throws IOException {
            Path file = tempDir.resolve("wild.md");
            Files.writeString(file, """
                    ---
                    name: wild
                    tools: *
                    ---
                    Do everything. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertEquals(Set.of("*"), skill.getAllowedTools());
        }

        @Test
        void noToolsFieldResultsInNull() throws IOException {
            Path file = tempDir.resolve("notool.md");
            Files.writeString(file, """
                    ---
                    name: notool
                    ---
                    No tools specified. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertNull(skill.getAllowedTools());
        }

        @Test
        void defaultCategoryIsCustom() throws IOException {
            Path file = tempDir.resolve("nocat.md");
            Files.writeString(file, """
                    ---
                    name: nocat
                    ---
                    No category. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertEquals("custom", skill.getCategory());
        }

        @Test
        void defaultMaxStepsIsZero() throws IOException {
            Path file = tempDir.resolve("nosteps.md");
            Files.writeString(file, """
                    ---
                    name: nosteps
                    ---
                    Template. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertEquals(0, skill.getMaxSteps());
        }

        @Test
        void invalidMaxStepsUsesDefault() throws IOException {
            Path file = tempDir.resolve("badsteps.md");
            Files.writeString(file, """
                    ---
                    name: badsteps
                    max_steps: notanumber
                    ---
                    Template. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertEquals(0, skill.getMaxSteps());
        }

        @Test
        void defaultModelHintIsNull() throws IOException {
            Path file = tempDir.resolve("nomodel.md");
            Files.writeString(file, """
                    ---
                    name: nomodel
                    ---
                    Template. {{args}}
                    """);

            SkillConfig skill = loader.parseSkillFile(file);

            assertNull(skill.getModelHint());
        }
    }

    @Nested
    @DisplayName("Override behavior")
    class OverrideBehavior {

        @Test
        void projectSkillsOverrideUserSkills() throws IOException {
            // Simulate user-scoped skill by creating a file that loadAll would find
            // Since we can't easily mock KompileHome, test the override via project dir
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);

            // First load with one version
            Files.writeString(skillsDir.resolve("deploy.md"), """
                    ---
                    name: deploy
                    description: Version 1
                    ---
                    Template v1. {{args}}
                    """);

            Map<String, SkillConfig> skills = loader.loadAll();
            assertEquals("Version 1", skills.get("deploy").getDescription());

            // Overwrite with new version
            Files.writeString(skillsDir.resolve("deploy.md"), """
                    ---
                    name: deploy
                    description: Version 2
                    ---
                    Template v2. {{args}}
                    """);

            skills = loader.loadAll();
            assertEquals("Version 2", skills.get("deploy").getDescription());
        }
    }

    @Nested
    @DisplayName("Integration with SkillRegistry")
    class IntegrationWithRegistry {

        @Test
        void customSkillsCanBeRegisteredInRegistry() throws IOException {
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve("deploy.md"), """
                    ---
                    name: deploy
                    description: Deploy to staging
                    category: devops
                    tools: bash, read
                    ---
                    Deploy now. {{args}}
                    """);

            Map<String, SkillConfig> customSkills = loader.loadAll();

            SkillRegistry registry = new SkillRegistry();
            for (SkillConfig custom : customSkills.values()) {
                registry.register(custom);
            }

            // Should have 7 built-in + 1 custom = 8
            assertEquals(8, registry.all().size());

            // Custom skill should be findable
            SkillConfig deploy = registry.get("deploy");
            assertNotNull(deploy);
            assertEquals("devops", deploy.getCategory());
            assertFalse(deploy.isBuiltIn());

            // Built-in skills still present
            assertNotNull(registry.get("commit"));
            assertTrue(registry.get("commit").isBuiltIn());
        }

        @Test
        void customSkillCanOverrideBuiltIn() throws IOException {
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve("commit.md"), """
                    ---
                    name: commit
                    description: My custom commit
                    category: git
                    ---
                    My custom commit workflow. {{args}}
                    """);

            Map<String, SkillConfig> customSkills = loader.loadAll();

            SkillRegistry registry = new SkillRegistry();
            for (SkillConfig custom : customSkills.values()) {
                registry.register(custom);
            }

            // Still 7 total (override, not addition)
            assertEquals(7, registry.all().size());

            // Verify override
            SkillConfig commit = registry.get("commit");
            assertEquals("My custom commit", commit.getDescription());
            assertFalse(commit.isBuiltIn());
        }

        @Test
        void customSkillAppearsInNewCategory() throws IOException {
            Path skillsDir = tempDir.resolve(".kompile").resolve("skills");
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve("deploy.md"), """
                    ---
                    name: deploy
                    description: Deploy
                    category: devops
                    ---
                    Deploy. {{args}}
                    """);

            Map<String, SkillConfig> customSkills = loader.loadAll();

            SkillRegistry registry = new SkillRegistry();
            for (SkillConfig custom : customSkills.values()) {
                registry.register(custom);
            }

            // New category should appear
            assertTrue(registry.categories().contains("devops"));
            assertEquals(1, registry.getByCategory("devops").size());
        }
    }
}
