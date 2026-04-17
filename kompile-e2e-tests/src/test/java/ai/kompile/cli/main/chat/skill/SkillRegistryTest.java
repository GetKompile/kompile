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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SkillRegistry")
class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    @Nested
    @DisplayName("Built-in skills")
    class BuiltInSkills {

        @Test
        void registersSevenBuiltInSkills() {
            assertEquals(7, registry.all().size());
        }

        @Test
        void allBuiltInSkillsAreMarkedBuiltIn() {
            for (SkillConfig skill : registry.all()) {
                assertTrue(skill.isBuiltIn(), "Skill " + skill.getName() + " should be built-in");
            }
        }

        @Test
        void commitSkillExists() {
            SkillConfig commit = registry.get("commit");
            assertNotNull(commit);
            assertEquals("git", commit.getCategory());
            assertNotNull(commit.getPromptTemplate());
            assertTrue(commit.getPromptTemplate().contains("git"));
            assertTrue(commit.getAllowedTools().contains("bash"));
        }

        @Test
        void prSkillExists() {
            SkillConfig pr = registry.get("pr");
            assertNotNull(pr);
            assertEquals("git", pr.getCategory());
            assertTrue(pr.getPromptTemplate().contains("pull request"));
        }

        @Test
        void reviewSkillExists() {
            SkillConfig review = registry.get("review");
            assertNotNull(review);
            assertEquals("code", review.getCategory());
            assertTrue(review.getPromptTemplate().contains("review") ||
                    review.getPromptTemplate().contains("Review"));
        }

        @Test
        void simplifySkillExists() {
            SkillConfig simplify = registry.get("simplify");
            assertNotNull(simplify);
            assertEquals("code", simplify.getCategory());
            assertEquals(Set.of("*"), simplify.getAllowedTools());
        }

        @Test
        void explainSkillExists() {
            SkillConfig explain = registry.get("explain");
            assertNotNull(explain);
            assertEquals("code", explain.getCategory());
        }

        @Test
        void testSkillExists() {
            SkillConfig test = registry.get("test");
            assertNotNull(test);
            assertEquals("code", test.getCategory());
            assertEquals(Set.of("*"), test.getAllowedTools());
        }

        @Test
        void fixSkillExists() {
            SkillConfig fix = registry.get("fix");
            assertNotNull(fix);
            assertEquals("debug", fix.getCategory());
            assertEquals(Set.of("*"), fix.getAllowedTools());
        }

        @Test
        void expectedSkillNames() {
            Set<String> names = registry.names();
            assertTrue(names.contains("commit"));
            assertTrue(names.contains("pr"));
            assertTrue(names.contains("review"));
            assertTrue(names.contains("simplify"));
            assertTrue(names.contains("explain"));
            assertTrue(names.contains("test"));
            assertTrue(names.contains("fix"));
        }

        @Test
        void allSkillsHavePromptTemplatesWithArgsPlaceholder() {
            for (SkillConfig skill : registry.all()) {
                assertNotNull(skill.getPromptTemplate(),
                        "Skill " + skill.getName() + " should have a prompt template");
                assertTrue(skill.getPromptTemplate().contains("{{args}}"),
                        "Skill " + skill.getName() + " should have {{args}} placeholder");
            }
        }

        @Test
        void allSkillsHaveAllowedTools() {
            for (SkillConfig skill : registry.all()) {
                assertNotNull(skill.getAllowedTools(),
                        "Built-in skill " + skill.getName() + " should have allowedTools set");
                assertFalse(skill.getAllowedTools().isEmpty(),
                        "Built-in skill " + skill.getName() + " should have at least one allowed tool");
            }
        }

        @Test
        void allSkillsHaveDescriptions() {
            for (SkillConfig skill : registry.all()) {
                assertNotNull(skill.getDescription());
                assertFalse(skill.getDescription().isBlank(),
                        "Skill " + skill.getName() + " should have a description");
            }
        }
    }

    @Nested
    @DisplayName("Categories")
    class Categories {

        @Test
        void hasGitCategory() {
            List<SkillConfig> gitSkills = registry.getByCategory("git");
            assertEquals(2, gitSkills.size());
        }

        @Test
        void hasCodeCategory() {
            List<SkillConfig> codeSkills = registry.getByCategory("code");
            assertEquals(4, codeSkills.size());
        }

        @Test
        void hasDebugCategory() {
            List<SkillConfig> debugSkills = registry.getByCategory("debug");
            assertEquals(1, debugSkills.size());
        }

        @Test
        void getCategoryIsCaseInsensitive() {
            List<SkillConfig> skills = registry.getByCategory("GIT");
            assertEquals(2, skills.size());
        }

        @Test
        void unknownCategoryReturnsEmpty() {
            List<SkillConfig> skills = registry.getByCategory("nonexistent");
            assertTrue(skills.isEmpty());
        }

        @Test
        void categoriesListsAllDistinctCategories() {
            List<String> categories = registry.categories();
            assertTrue(categories.contains("git"));
            assertTrue(categories.contains("code"));
            assertTrue(categories.contains("debug"));
            assertEquals(3, categories.size());
        }
    }

    @Nested
    @DisplayName("Registration and lookup")
    class RegistrationAndLookup {

        @Test
        void getNonexistentReturnsNull() {
            assertNull(registry.get("nonexistent"));
        }

        @Test
        void registerCustomSkill() {
            SkillConfig custom = SkillConfig.builder("deploy")
                    .displayName("Deploy")
                    .description("Deploy to staging")
                    .promptTemplate("Deploy to staging. {{args}}")
                    .category("devops")
                    .build();

            registry.register(custom);

            SkillConfig found = registry.get("deploy");
            assertNotNull(found);
            assertEquals("Deploy", found.getDisplayName());
            assertEquals("devops", found.getCategory());
        }

        @Test
        void customSkillAppearsInAll() {
            int before = registry.all().size();
            registry.register(SkillConfig.builder("custom1")
                    .promptTemplate("template")
                    .build());

            assertEquals(before + 1, registry.all().size());
        }

        @Test
        void customSkillAppearsInNames() {
            registry.register(SkillConfig.builder("custom1")
                    .promptTemplate("template")
                    .build());

            assertTrue(registry.names().contains("custom1"));
        }

        @Test
        void customSkillAppearsInCategory() {
            registry.register(SkillConfig.builder("custom1")
                    .promptTemplate("template")
                    .category("devops")
                    .build());

            List<SkillConfig> devops = registry.getByCategory("devops");
            assertEquals(1, devops.size());
            assertEquals("custom1", devops.get(0).getName());
        }

        @Test
        void registerOverridesExistingByName() {
            SkillConfig override = SkillConfig.builder("commit")
                    .displayName("My Commit")
                    .promptTemplate("Custom commit template. {{args}}")
                    .category("git")
                    .build();

            registry.register(override);

            SkillConfig found = registry.get("commit");
            assertEquals("My Commit", found.getDisplayName());
            assertEquals("Custom commit template. {{args}}", found.getPromptTemplate());
        }

        @Test
        void allReturnsUnmodifiableCollection() {
            Collection<SkillConfig> all = registry.all();
            assertThrows(UnsupportedOperationException.class, () ->
                    ((java.util.Collection<SkillConfig>) all).clear());
        }

        @Test
        void namesReturnsUnmodifiableSet() {
            Set<String> names = registry.names();
            assertThrows(UnsupportedOperationException.class, () -> names.clear());
        }
    }

    @Nested
    @DisplayName("Template expansion integration")
    class TemplateExpansionIntegration {

        @Test
        void commitSkillExpandsArgs() {
            SkillConfig commit = registry.get("commit");
            String expanded = commit.expandTemplate("-m \"fix auth bug\"");
            assertTrue(expanded.contains("-m \"fix auth bug\""));
            assertTrue(expanded.contains("git status"));
        }

        @Test
        void commitSkillExpandsEmptyArgs() {
            SkillConfig commit = registry.get("commit");
            String expanded = commit.expandTemplate("");
            assertFalse(expanded.contains("{{args}}"));
            assertTrue(expanded.contains("git status"));
        }

        @Test
        void fixSkillExpandsErrorDescription() {
            SkillConfig fix = registry.get("fix");
            String expanded = fix.expandTemplate("NullPointerException in FooService.java:42");
            assertTrue(expanded.contains("NullPointerException in FooService.java:42"));
        }
    }
}
