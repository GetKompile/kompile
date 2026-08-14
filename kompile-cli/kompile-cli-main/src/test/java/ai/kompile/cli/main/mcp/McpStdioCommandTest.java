/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.main.mcp;

import ai.kompile.cli.main.chat.roles.BuiltInRoles;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioCommandTest {

    @Test
    void daemonBridgeIsExplicitOptInAndNoDaemonNeverEnablesIt() throws Exception {
        McpStdioCommand defaults = new McpStdioCommand();
        new CommandLine(defaults).parseArgs();
        assertFalse(daemonEnabled(defaults));

        McpStdioCommand optedIn = new McpStdioCommand();
        new CommandLine(optedIn).parseArgs("--daemon");
        assertTrue(daemonEnabled(optedIn));

        McpStdioCommand explicitlyDisabled = new McpStdioCommand();
        new CommandLine(explicitlyDisabled).parseArgs("--no-daemon");
        assertFalse(daemonEnabled(explicitlyDisabled));
    }

    private boolean daemonEnabled(McpStdioCommand command) throws Exception {
        Field field = McpStdioCommand.class.getDeclaredField("daemon");
        field.setAccessible(true);
        return field.getBoolean(command);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyProfilesDoNotExposeBash() throws Exception {
        Field field = McpStdioCommand.class.getDeclaredField("PROFILE_TOOLS");
        field.setAccessible(true);
        Map<String, Set<String>> profiles = (Map<String, Set<String>>) field.get(null);

        assertFalse(profiles.get("minimal").contains("bash"));
        assertFalse(profiles.get("explore").contains("bash"));
        assertTrue(profiles.get("core").contains("bash"));
    }

    @Test
    void buildCallResult_preservesToolMetadataAsStructuredContent() {
        McpStdioCommand command = new McpStdioCommand();
        ToolResult result = ToolResult.success(
                "reasoning layers",
                "Fetched reasoning overlays",
                Map.of(
                        "factSheetId", 42,
                        "layers", List.of("ontology", "psl", "mebn"),
                        "scores", Map.of("hybrid", 0.87)));

        ObjectNode callResult = command.buildCallResult(result);

        assertFalse(callResult.get("isError").asBoolean());
        assertEquals("text", callResult.get("content").get(0).get("type").asText());
        assertTrue(callResult.has("structuredContent"));
        assertEquals("reasoning layers", callResult.get("structuredContent").get("title").asText());
        assertEquals("Fetched reasoning overlays", callResult.get("structuredContent").get("output").asText());
        ObjectNode metadata = (ObjectNode) callResult.get("structuredContent").get("metadata");
        assertEquals(42, metadata.get("factSheetId").asInt());
        assertEquals("ontology", metadata.get("layers").get(0).asText());
        assertEquals(0.87, metadata.get("scores").get("hybrid").asDouble(), 1e-9);
    }

    @Test
    void buildCallResult_omitsStructuredContentWhenMetadataIsEmpty() {
        McpStdioCommand command = new McpStdioCommand();

        ObjectNode callResult = command.buildCallResult(ToolResult.success("plain text"));

        assertFalse(callResult.has("structuredContent"));
    }

    @Test
    void architectRoleKeepsCodexDefaultsWithFullAccess() {
        assertEquals("gpt-5.6-sol", BuiltInRoles.ARCHITECT.getAgentDefaultsFor("codex").getModel());
        assertEquals("xhigh", BuiltInRoles.ARCHITECT.getAgentDefaultsFor("codex").resolveThinking("gpt-5.6-sol"));
        assertTrue(BuiltInRoles.ARCHITECT.isCanSpawnSubagents());
        assertEquals(Set.of("*"), BuiltInRoles.ARCHITECT.getEnabledTools());
        assertTrue(BuiltInRoles.ARCHITECT.getPermissionOverrides().isEmpty());
    }
}
