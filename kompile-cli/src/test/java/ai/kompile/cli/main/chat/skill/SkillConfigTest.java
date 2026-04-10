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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SkillConfig")
class SkillConfigTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        void nameIsRequired() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertEquals("test", skill.getName());
        }

        @Test
        void displayNameDefaultsToName() {
            SkillConfig skill = SkillConfig.builder("myskill").build();
            assertEquals("myskill", skill.getDisplayName());
        }

        @Test
        void descriptionDefaultsToEmpty() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertEquals("", skill.getDescription());
        }

        @Test
        void allowedToolsDefaultsToNull() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertNull(skill.getAllowedTools());
        }

        @Test
        void maxStepsDefaultsToZero() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertEquals(0, skill.getMaxSteps());
        }

        @Test
        void modelHintDefaultsToNull() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertNull(skill.getModelHint());
        }

        @Test
        void builtInDefaultsToFalse() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertFalse(skill.isBuiltIn());
        }

        @Test
        void categoryDefaultsToGeneral() {
            SkillConfig skill = SkillConfig.builder("test").build();
            assertEquals("general", skill.getCategory());
        }
    }

    @Nested
    @DisplayName("Builder customization")
    class BuilderCustomization {

        @Test
        void allFieldsCanBeSet() {
            Set<String> tools = Set.of("bash", "read");
            SkillConfig skill = SkillConfig.builder("commit")
                    .displayName("Commit")
                    .description("Stage and commit changes")
                    .promptTemplate("Create a git commit. {{args}}")
                    .allowedTools(tools)
                    .maxSteps(20)
                    .modelHint("fast")
                    .builtIn(true)
                    .category("git")
                    .build();

            assertEquals("commit", skill.getName());
            assertEquals("Commit", skill.getDisplayName());
            assertEquals("Stage and commit changes", skill.getDescription());
            assertEquals("Create a git commit. {{args}}", skill.getPromptTemplate());
            assertEquals(tools, skill.getAllowedTools());
            assertEquals(20, skill.getMaxSteps());
            assertEquals("fast", skill.getModelHint());
            assertTrue(skill.isBuiltIn());
            assertEquals("git", skill.getCategory());
        }
    }

    @Nested
    @DisplayName("Template expansion")
    class TemplateExpansion {

        @Test
        void replacesArgsPlaceholder() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("Do something with {{args}}")
                    .build();
            assertEquals("Do something with hello world", skill.expandTemplate("hello world"));
        }

        @Test
        void nullArgsReplacesWithEmpty() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("Do something with {{args}}")
                    .build();
            assertEquals("Do something with", skill.expandTemplate(null));
        }

        @Test
        void blankArgsReplacesWithEmpty() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("Do something with {{args}}")
                    .build();
            assertEquals("Do something with", skill.expandTemplate("   "));
        }

        @Test
        void emptyArgsReplacesWithEmpty() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("Do something with {{args}}")
                    .build();
            assertEquals("Do something with", skill.expandTemplate(""));
        }

        @Test
        void resultIsTrimmed() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("  Do something  ")
                    .build();
            assertEquals("Do something", skill.expandTemplate(""));
        }

        @Test
        void multipleArgsPlaceholdersAllReplaced() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("First: {{args}}, Second: {{args}}")
                    .build();
            assertEquals("First: X, Second: X", skill.expandTemplate("X"));
        }

        @Test
        void noPlaceholderLeavesTemplateUnchanged() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("Do something fixed")
                    .build();
            assertEquals("Do something fixed", skill.expandTemplate("ignored"));
        }

        @Test
        void argsWithSpecialCharsPreserved() {
            SkillConfig skill = SkillConfig.builder("test")
                    .promptTemplate("Commit: {{args}}")
                    .build();
            assertEquals("Commit: -m \"fix auth bug\"", skill.expandTemplate("-m \"fix auth bug\""));
        }
    }
}
