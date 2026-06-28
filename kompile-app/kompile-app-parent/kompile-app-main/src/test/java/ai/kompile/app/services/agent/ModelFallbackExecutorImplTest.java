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

import ai.kompile.app.services.agent.ModelFallbackConfigManager.FallbackEntry;
import ai.kompile.app.services.agent.ModelFallbackConfigManager.ModelFallbackConfig;
import ai.kompile.core.agent.AgentProvider;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the extraction fallback chain is built DYNAMICALLY from the live model selector — free
 * models enumerated first (in selector order), paid agents (claude) appended LAST — with no
 * hardcoded model ids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelFallbackExecutorImplTest {

    @Mock private AgentRegistryService agentRegistry;
    @Mock private CliAgentModelService modelService;

    private ModelFallbackExecutorImpl executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new ModelFallbackExecutorImpl();
        inject("agentRegistry", agentRegistry);
        inject("modelService", modelService);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = ModelFallbackExecutorImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(executor, value);
    }

    private AgentProvider agent(String name) {
        // Use a real AgentProvider (not a mock) to avoid nested-stubbing during list construction.
        return AgentProvider.builder().name(name).build();
    }

    @Test
    void chainEnumeratesFreeModelsFirstThenPaidLast() {
        // Two agents: opencode-cli (free) and claude-cli (paid, in config.paidAgents).
        List<AgentProvider> agents = new ArrayList<>(List.of(agent("opencode-cli"), agent("claude-cli")));
        when(agentRegistry.getAllAgents()).thenReturn(agents);
        // The dynamic selector returns the discovered+health-ordered opencode models.
        when(modelService.selectExtractionModels("opencode-cli"))
                .thenReturn(List.of("opencode/deepseek-v4-pro", "opencode/glm-5.2", "opencode/kimi-k2.6"));

        ModelFallbackConfig config = ModelFallbackConfig.defaults(); // paidAgents={claude-cli}

        List<FallbackEntry> chain = executor.buildDynamicChain(config, "test");

        // Free opencode models come first, in selector order.
        assertEquals(new FallbackEntry("opencode-cli", "opencode/deepseek-v4-pro"), chain.get(0));
        assertEquals(new FallbackEntry("opencode-cli", "opencode/glm-5.2"), chain.get(1));
        assertEquals(new FallbackEntry("opencode-cli", "opencode/kimi-k2.6"), chain.get(2));

        // Paid agent (claude) is LAST and appears exactly once.
        FallbackEntry last = chain.get(chain.size() - 1);
        assertEquals("claude-cli", last.agentName());
        assertEquals(1, chain.stream().filter(e -> e.agentName().equals("claude-cli")).count());

        // No claude/codex anywhere in the FREE portion (the mandate).
        List<FallbackEntry> freePortion = chain.subList(0, chain.size() - 1);
        assertTrue(freePortion.stream().noneMatch(e -> e.agentName().contains("claude")),
                "free rotation must never contain a paid/claude agent");

        // selectExtractionModels is NOT called for the paid agent (it's skipped in the free loop).
        verify(modelService, never()).selectExtractionModels("claude-cli");
    }

    @Test
    void fallsBackToConfiguredChainWhenDiscoveryEmpty() {
        // No agents discovered (cold start) → use the configured chain as-is.
        when(agentRegistry.getAllAgents()).thenReturn(List.of());

        ModelFallbackConfig config = ModelFallbackConfig.defaults();
        List<FallbackEntry> chain = executor.buildDynamicChain(config, "test");

        assertEquals(config.fallbackChain, chain);
        // And that configured fallback contains NO hardcoded model version strings.
        assertTrue(chain.stream().allMatch(e -> e.modelId() == null || e.modelId().isBlank()),
                "cold-start fallback must not hardcode specific model ids");
    }
}
