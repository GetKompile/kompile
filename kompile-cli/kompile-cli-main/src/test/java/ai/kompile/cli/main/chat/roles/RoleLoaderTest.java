/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.roles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsPerAgentModelAndModelSpecificThinkingDefaults() throws Exception {
        Map<String, RoleAgentDefaults> defaults = Map.of(
                "codex", new RoleAgentDefaults(
                        "gpt-5.6-terra", "medium",
                        Map.of("gpt-5.6-sol", "max", "gpt-5.6-terra", "high")),
                "claude", new RoleAgentDefaults(
                        "claude-sonnet", "high",
                        Map.of("claude-opus", "max")),
                "opencode", new RoleAgentDefaults(
                        "openai/gpt-5.6", "low",
                        Map.of("OpenAI/GPT-Pro", "high")));

        RoleConfig original = RoleConfig.builder()
                .name("doer")
                .displayName("Doer")
                .description("Implements focused tasks")
                .category("development")
                .systemPrompt("Implement the assigned change and verify it.")
                .modelHint("prompt-only-hint")
                .agentDefaults(defaults)
                .build();

        Path file = tempDir.resolve("doer.md");
        RoleLoader.saveRole(original, file);
        RoleConfig loaded = new RoleLoader(tempDir).parseRoleFile(file, false);

        assertEquals("prompt-only-hint", loaded.getModelHint());
        assertEquals(defaults, loaded.getAgentDefaults());
        assertEquals("max",
                loaded.getAgentDefaultsFor("CODEX").resolveThinking("gpt-5.6-sol"));
        assertEquals("high",
                loaded.getAgentDefaultsFor("opencode").resolveThinking("OpenAI/GPT-Pro"));

        String markdown = Files.readString(file);
        assertTrue(markdown.contains("agent_defaults.codex.model: gpt-5.6-terra"));
        assertTrue(markdown.contains(
                "agent_defaults.codex.thinking.models.gpt-5.6-sol: max"));
    }

    @Test
    void parsesThinkingShorthandWithoutChangingLegacyRoles() throws Exception {
        Path shorthand = tempDir.resolve("shorthand.md");
        Files.writeString(shorthand, """
                ---
                name: shorthand
                model: legacy-hint
                agent_defaults.codex.model: gpt-5.6-terra
                agent_defaults.codex.thinking: high
                ---
                Keep changes focused.
                """);

        RoleConfig loaded = new RoleLoader(tempDir).parseRoleFile(shorthand, false);
        assertEquals("legacy-hint", loaded.getModelHint());
        assertEquals("gpt-5.6-terra", loaded.getAgentDefaultsFor("codex").getModel());
        assertEquals("high", loaded.getAgentDefaultsFor("codex").getDefaultThinking());

        Path legacy = tempDir.resolve("legacy.md");
        Files.writeString(legacy, """
                ---
                name: legacy
                model: default
                ---
                Existing role.
                """);
        RoleConfig legacyRole = new RoleLoader(tempDir).parseRoleFile(legacy, false);
        assertTrue(legacyRole.getAgentDefaults().isEmpty());
    }

    @Test
    void validatesSingleLineValuesAndNormalizesProviderKeys() {
        RoleAgentDefaults defaults =
                new RoleAgentDefaults("gpt-5.6-terra", "medium", Map.of());

        Map<String, RoleAgentDefaults> normalized =
                RoleManager.normalizeAgentDefaults(Map.of(" CODEX ", defaults));
        assertEquals(defaults, normalized.get("codex"));

        assertThrows(IllegalArgumentException.class,
                () -> RoleManager.normalizeAgentDefaults(Map.of("gemini", defaults)));
        assertThrows(IllegalArgumentException.class,
                () -> new RoleAgentDefaults("gpt-5.6\n---", "medium", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RoleAgentDefaults(
                        "gpt-5.6", "medium", Map.of("gpt-5.6-sol", "max\rnext")));
    }

    @Test
    void frontmatterDelimiterMustOccupyItsOwnLine() throws Exception {
        RoleConfig original = RoleConfig.builder()
                .name("delimiter")
                .displayName("Delimiter")
                .description("Delimiter-safe role")
                .category("test")
                .systemPrompt("Keep the body intact.")
                .agentDefaults(Map.of(
                        "codex", new RoleAgentDefaults(
                                "gpt---preview", "medium", Map.of())))
                .build();

        Path file = tempDir.resolve("delimiter.md");
        RoleLoader.saveRole(original, file);
        RoleConfig loaded = new RoleLoader(tempDir).parseRoleFile(file, false);

        assertEquals("gpt---preview",
                loaded.getAgentDefaultsFor("codex").getModel());
        assertEquals("Keep the body intact.", loaded.getSystemPrompt());
    }
}
