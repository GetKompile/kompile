/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.orchestrator.api.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CliAgentLlmProvider} — model listing, availability, provider metadata,
 * and unsupported operation handling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliAgentLlmProviderTest {

    @Mock
    private AgentRegistryService agentRegistryService;

    private CliAgentLlmProvider provider;

    private AgentProvider claudeAgent;
    private AgentProvider codexAgent;
    private AgentProvider geminiAgent;

    @BeforeEach
    void setUp() {
        claudeAgent = AgentProvider.builder()
                .name("claude-cli")
                .displayName("Claude Code")
                .command("claude")
                .available(true)
                .isDefault(true)
                .description("Claude Code CLI")
                .build();

        codexAgent = AgentProvider.builder()
                .name("codex-cli")
                .displayName("Codex")
                .command("codex")
                .available(false)
                .isDefault(false)
                .description("OpenAI Codex CLI")
                .build();

        geminiAgent = AgentProvider.builder()
                .name("gemini-cli")
                .displayName("Gemini")
                .command("gemini")
                .available(false)
                .isDefault(false)
                .description("Google Gemini CLI")
                .build();

        when(agentRegistryService.getAllAgents())
                .thenReturn(List.of(claudeAgent, codexAgent, geminiAgent));
        when(agentRegistryService.hasAvailableAgents()).thenReturn(true);
        when(agentRegistryService.getAvailableAgentCount()).thenReturn(1);
        when(agentRegistryService.getAgent("claude-cli")).thenReturn(Optional.of(claudeAgent));
        when(agentRegistryService.getAgent("codex-cli")).thenReturn(Optional.of(codexAgent));
        when(agentRegistryService.getAgent("gemini-cli")).thenReturn(Optional.of(geminiAgent));

        provider = new CliAgentLlmProvider(agentRegistryService);
    }

    // ── Basic metadata ───────────────────────────────────────────────────────────

    @Test
    void getId_returnsCLIAgents() {
        assertEquals("cli-agents", provider.getId());
    }

    @Test
    void getDisplayName_returnsCliAgents() {
        assertEquals("CLI Agents", provider.getDisplayName());
    }

    @Test
    void getPriority_returns50() {
        assertEquals(50, provider.getPriority());
    }

    @Test
    void supportsStreaming_returnsTrue() {
        assertTrue(provider.supportsStreaming());
    }

    @Test
    void getMaxTokens_returnsNegative1() {
        assertEquals(-1, provider.getMaxTokens());
    }

    @Test
    void supportsModelListing_returnsTrue() {
        assertTrue(provider.supportsModelListing());
    }

    // ── isAvailable ──────────────────────────────────────────────────────────────

    @Test
    void isAvailable_whenAgentsPresent_returnsTrue() {
        assertTrue(provider.isAvailable());
    }

    @Test
    void isAvailable_whenNoAgents_returnsFalse() {
        when(agentRegistryService.hasAvailableAgents()).thenReturn(false);
        assertFalse(provider.isAvailable());
    }

    // ── getAvailableModels ──────────────────────────────────────────────────────

    @Test
    void getAvailableModels_returnsAllAgentsAsModels() {
        List<LlmProvider.ModelInfo> models = provider.getAvailableModels();
        assertNotNull(models);
        assertEquals(3, models.size());
    }

    @Test
    void getAvailableModels_availableAgentsFirst() {
        List<LlmProvider.ModelInfo> models = provider.getAvailableModels();
        // claude-cli is available, should come first
        assertEquals("claude-cli", models.get(0).id());
    }

    @Test
    void getAvailableModels_unavailableAgentHasNotInstalledSuffix() {
        List<LlmProvider.ModelInfo> models = provider.getAvailableModels();
        // Find the unavailable agents
        LlmProvider.ModelInfo codex = models.stream()
                .filter(m -> "codex-cli".equals(m.id()))
                .findFirst().orElseThrow();
        assertTrue(codex.description().contains("not installed"),
                "Unavailable agent description should contain '(not installed)'");
    }

    @Test
    void getAvailableModels_modelHasContextWindowNeg1() {
        List<LlmProvider.ModelInfo> models = provider.getAvailableModels();
        models.forEach(m -> assertEquals(-1, m.contextWindow()));
    }

    @Test
    void getAvailableModels_modelSupportTools() {
        List<LlmProvider.ModelInfo> models = provider.getAvailableModels();
        models.forEach(m -> assertTrue(m.supportsTools()));
    }

    // ── Unsupported operations ───────────────────────────────────────────────────

    @Test
    void startSession_throws() {
        assertThrows(UnsupportedOperationException.class,
                () -> provider.startSession(null));
    }

    @Test
    void sendMessage_throws() {
        assertThrows(UnsupportedOperationException.class,
                () -> provider.sendMessage(1L, "hello"));
    }

    @Test
    void cancelSession_doesNotThrow() {
        assertDoesNotThrow(() -> provider.cancelSession(1L));
    }

    @Test
    void isSessionActive_returnsFalse() {
        assertFalse(provider.isSessionActive(1L));
    }

    @Test
    void streamOutput_returnsEmptyFlux() {
        Flux<String> flux = provider.streamOutput(1L);
        assertNotNull(flux);
        // Should be empty
        List<String> items = flux.collectList().block();
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    // ── Model sorting ────────────────────────────────────────────────────────────

    @Test
    void getAvailableModels_defaultAgentFirstAmongAvailable() {
        // Add a second available non-default agent
        AgentProvider extraAgent = AgentProvider.builder()
                .name("extra-cli")
                .displayName("Extra")
                .command("extra")
                .available(true)
                .isDefault(false)
                .description("Extra CLI")
                .build();

        when(agentRegistryService.getAllAgents())
                .thenReturn(List.of(codexAgent, extraAgent, claudeAgent));
        when(agentRegistryService.getAgent("extra-cli")).thenReturn(Optional.of(extraAgent));

        List<LlmProvider.ModelInfo> models = provider.getAvailableModels();
        // claude-cli (available + default) should come first
        assertEquals("claude-cli", models.get(0).id());
    }
}
