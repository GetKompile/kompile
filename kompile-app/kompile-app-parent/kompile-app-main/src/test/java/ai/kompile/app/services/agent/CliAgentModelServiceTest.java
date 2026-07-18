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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CliAgentModelServiceTest {

    @Test
    void modelListParserKeepsRealModelIds() {
        assertEquals("opencode/deepseek-v4-flash",
                CliAgentModelService.parseModelListLine("opencode/deepseek-v4-flash").orElseThrow());
        assertEquals("qwen2.5-coder:7b",
                CliAgentModelService.parseModelListLine("  - qwen2.5-coder:7b  ").orElseThrow());
    }

    @Test
    void modelListParserRejectsCliHelpAndDocsPaths() {
        assertTrue(CliAgentModelService.parseModelListLine(
                "No models available. Use /login to log into a provider via OAuth or API key. See:").isEmpty());
        assertTrue(CliAgentModelService.parseModelListLine(
                "/home/agibsonccc/.nvm/versions/node/v22.15.0/lib/node_modules/@earendil-works/pi-coding-agent/docs/providers.md").isEmpty());
        assertTrue(CliAgentModelService.parseModelListLine("https://example.invalid/models.md").isEmpty());
        assertTrue(CliAgentModelService.parseModelListLine("Available models:").isEmpty());
    }

    @Test
    void extractionSelectionConstrainsProvidersNamedByPriority() throws Exception {
        AgentRegistryService registry = mock(AgentRegistryService.class);
        AgentSubprocessExecutor executor = mock(AgentSubprocessExecutor.class);
        LocalStagingLlmService localStaging = mock(LocalStagingLlmService.class);
        CliAgentModelService service = new CliAgentModelService(registry, executor);
        setField(service, "localStagingLlmService", localStaging);
        AgentProvider opencodeProvider = AgentProvider.builder()
                .name("opencode-cli")
                .displayName("OpenCode")
                .modelListCommand(List.of())
                .build();
        AgentProvider localProvider = AgentProvider.builder()
                .name(CliAgentModelService.LOCAL_STAGING_AGENT_NAME)
                .displayName("Local Staging")
                .modelListCommand(List.of())
                .build();
        when(registry.getAgent("opencode-cli")).thenReturn(Optional.of(opencodeProvider));
        when(registry.getAgent(CliAgentModelService.LOCAL_STAGING_AGENT_NAME)).thenReturn(Optional.of(localProvider));
        when(registry.checkAgentAvailability("opencode-cli")).thenReturn(true);
        when(registry.checkAgentAvailability(CliAgentModelService.LOCAL_STAGING_AGENT_NAME)).thenReturn(true);
        when(localStaging.listModelIds(false)).thenReturn(List.of("local/lfm2.5-1.2b-instruct"));

        seedModelCache(service, "opencode-cli", List.of(
                "opencode/deepseek-v4-pro",
                "opencode/gpt-5",
                "opencode/glm-5.2",
                "opencode/kimi-k2.6",
                "opencode/deepseek-v4-flash-free"));
        service.setActiveExtractionPolicy(
                List.of("opencode", "local"),
                List.of("claude", "codex", "gpt"),
                List.of(),
                List.of("opencode/kimi-k2.6", "deepseek-v4-flash-free"));

        assertEquals(List.of(
                        "opencode/kimi-k2.6",
                        "opencode/deepseek-v4-flash-free"),
                service.selectExtractionModels("opencode-cli"));
        assertEquals(List.of("local/lfm2.5-1.2b-instruct"),
                service.selectExtractionModels(CliAgentModelService.LOCAL_STAGING_AGENT_NAME));
    }

    @Test
    void throughputBreaksCorrectnessTiesForProvenModels() throws Exception {
        AgentRegistryService registry = mock(AgentRegistryService.class);
        AgentSubprocessExecutor executor = mock(AgentSubprocessExecutor.class);
        CliAgentModelService service = new CliAgentModelService(registry, executor);
        AgentProvider opencodeProvider = AgentProvider.builder()
                .name("opencode-cli")
                .displayName("OpenCode")
                .modelListCommand(List.of())
                .build();
        when(registry.getAgent("opencode-cli")).thenReturn(Optional.of(opencodeProvider));
        when(registry.checkAgentAvailability("opencode-cli")).thenReturn(true);

        seedModelCache(service, "opencode-cli", List.of("opencode/slow-model", "opencode/fast-model"));
        // slow-model is FIRST in the manual priority list — throughput must out-rank it.
        service.setActiveExtractionPolicy(
                List.of("opencode"),
                List.of(),
                List.of(),
                List.of("opencode/slow-model", "opencode/fast-model"));

        // Both proven (latency recorded) with correctness in the same tie band; fast-model has
        // 5x the observed output throughput (e.g. an MTP-accelerated decode path).
        service.recordModelLatency("opencode/slow-model", 1_000);
        service.recordModelLatency("opencode/fast-model", 1_000);
        service.recordModelCorrectness("opencode/slow-model", 0.90);
        service.recordModelCorrectness("opencode/fast-model", 0.90);
        service.recordModelThroughput("opencode/slow-model", 1_000, 10_000);  // 100 chars/s
        service.recordModelThroughput("opencode/fast-model", 5_000, 10_000);  // 500 chars/s

        assertEquals(List.of("opencode/fast-model", "opencode/slow-model"),
                service.selectExtractionModels("opencode-cli"),
                "within the correctness tie band, observed throughput must decide precedence");

        // Correctness beyond the band still dominates raw speed.
        service.recordModelCorrectness("opencode/slow-model", 1.0);  // EWMA → 0.93, out of the band
        assertEquals(List.of("opencode/slow-model", "opencode/fast-model"),
                service.selectExtractionModels("opencode-cli"),
                "a clearly more-correct model must out-rank a faster but worse one");
    }

    @SuppressWarnings("unchecked")
    private static void seedModelCache(CliAgentModelService service, String agentName, List<String> models)
            throws Exception {
        Field f = CliAgentModelService.class.getDeclaredField("modelCache");
        f.setAccessible(true);
        ((Map<String, List<String>>) f.get(service)).put(agentName, models);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
