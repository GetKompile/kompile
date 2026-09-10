/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
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

package ai.kompile.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAgentRegistryTest {

    @Test
    void loadsPackagedCliAgentDefinitions() {
        List<AgentProvider> providers = CliAgentRegistry.loadAll();
        assertFalse(providers.isEmpty(), "cli-agents.json must be packaged on the classpath");

        Map<String, AgentProvider> byCommand = providers.stream()
                .collect(Collectors.toMap(AgentProvider::getCommand, Function.identity()));

        assertTrue(byCommand.keySet().containsAll(List.of(
                "claude", "codex", "gemini", "opencode", "qwen", "pi")));

        AgentProvider codex = byCommand.get("codex");
        assertNotNull(codex);
        assertEquals("Codex", codex.getDisplayName());
        assertEquals("--model", codex.getModelFlag());
        assertEquals("--dangerously-bypass-approvals-and-sandbox", codex.getSkipPermissionsFlag());

        AgentProvider opencode = byCommand.get("opencode");
        assertNotNull(opencode);
        assertEquals(List.of("opencode", "models", "--verbose"),
                opencode.getModelListCommand());
        assertEquals(List.of("opencode", "auth"), opencode.getAuthCommand());
        assertTrue(opencode.getDescription().contains("requires the opencode CLI"));

        AgentProvider pi = byCommand.get("pi");
        assertNotNull(pi);
        assertTrue(pi.getDescription().contains("requires the pi CLI"));
    }

    @Test
    void commandNamesExposePassthroughMenuCandidates() {
        assertTrue(CliAgentRegistry.commandNames().contains("codex"),
                "passthrough setup must be able to test codex on PATH");
    }

    @Test
    void fullAgentRosterIsPinned() {
        // The registry feeds every agent lane; a partial roster ships agent-lockout.
        // Roster changes are allowed but must be deliberate: update this test AND the
        // antrun gates in this module's pom and kompile-cli-main's pom together.
        List<AgentProvider> providers = CliAgentRegistry.loadAll();

        Set<String> commands = providers.stream()
                .map(AgentProvider::getCommand)
                .collect(Collectors.toSet());
        assertEquals(Set.of("claude", "codex", "gemini", "opencode", "qwen", "pi"), commands);

        List<AgentProvider> defaults = providers.stream()
                .filter(AgentProvider::isDefault)
                .toList();
        assertEquals(1, defaults.size(), "exactly one agent must be the default");
        assertEquals("claude", defaults.get(0).getCommand(), "claude is the default agent");
    }

    @Test
    void loadsMcpMetadataForAgentsThatDeclareIt() {
        AgentProvider claude = CliAgentRegistry.loadAll().stream()
                .filter(agent -> "claude".equals(agent.getCommand()))
                .findFirst()
                .orElseThrow();

        assertTrue(claude.isMcpSupported());
        // --mcp-server was a bogus flag that killed claude spawns; MCP injection goes
        // through --mcp-config. It must stay absent from the registry.
        assertNull(claude.getMcpServerFlag());
        assertEquals("--mcp-config", claude.getMcpConfigFlag());
        assertEquals("--allowedTools", claude.getMcpAllowToolsFlag());
    }
}
