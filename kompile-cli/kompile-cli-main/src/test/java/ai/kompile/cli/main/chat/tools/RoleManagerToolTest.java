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
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.roles.RoleAgentDefaults;
import ai.kompile.cli.main.chat.roles.RoleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleManagerToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaExposesFixedProviderDefaultsAndModelThinkingMap() {
        RoleManagerTool tool = new RoleManagerTool(new RoleManager(tempDir), objectMapper);

        var defaults = tool.parameterSchema().path("properties").path("agent_defaults");
        assertEquals("object", defaults.path("type").asText());
        assertFalse(defaults.path("additionalProperties").asBoolean(true));
        for (String agent : java.util.List.of("codex", "claude", "opencode")) {
            assertEquals("object",
                    defaults.path("properties").path(agent).path("type").asText());
            assertEquals("string",
                    defaults.path("properties").path(agent)
                            .path("properties").path("model").path("type").asText());
            assertEquals("string",
                    defaults.path("properties").path(agent)
                            .path("properties").path("thinking")
                            .path("properties").path("models")
                            .path("additionalProperties").path("type").asText());
        }
    }

    @Test
    void parsesProviderDefaultsAndRejectsUnknownProviders() {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode codex = params.putObject("agent_defaults").putObject("codex");
        codex.put("model", "gpt-5.6-terra");
        ObjectNode thinking = codex.putObject("thinking");
        thinking.put("default", "medium");
        thinking.putObject("models").put("gpt-5.6-sol", "ultra");

        Map<String, RoleAgentDefaults> parsed =
                RoleManagerTool.parseAgentDefaults(params);
        assertEquals("gpt-5.6-terra", parsed.get("codex").getModel());
        assertEquals("ultra", parsed.get("codex").resolveThinking("gpt-5.6-sol"));

        ObjectNode invalid = objectMapper.createObjectNode();
        invalid.putObject("agent_defaults").putObject("gemini").put("model", "gemini-pro");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RoleManagerTool.parseAgentDefaults(invalid));
        assertTrue(error.getMessage().contains("Supported agents"), error.getMessage());

        ObjectNode multiline = objectMapper.createObjectNode();
        multiline.putObject("agent_defaults").putObject("codex")
                .put("model", "gpt-5.6\n---");
        assertThrows(IllegalArgumentException.class,
                () -> RoleManagerTool.parseAgentDefaults(multiline));
    }
}
