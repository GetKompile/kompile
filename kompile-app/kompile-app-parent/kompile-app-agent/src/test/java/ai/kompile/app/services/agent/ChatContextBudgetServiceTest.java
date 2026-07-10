/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget resolution picks the authoritative context-window source per lane:
 * staging metadata for local models (never the 128K catalog default), the model
 * catalogs for everything else. Output reservation and input budget must stay
 * sane on tiny local windows.
 */
class ChatContextBudgetServiceTest {

    /** Staging fake: controllable live window and registry candidate. */
    private static final class FakeStaging extends LocalStagingLlmService {
        private final Integer live;
        private final Integer registry;

        FakeStaging(Integer live, Integer registry) {
            this.live = live;
            this.registry = registry;
        }

        @Override
        public Optional<Integer> liveMaxContextLength() {
            return Optional.ofNullable(live);
        }

        @Override
        public Optional<LocalModelCandidate> resolveCandidate(String requestedModelId) {
            if (registry == null) return Optional.empty();
            return Optional.of(new LocalModelCandidate(
                    "lfm2", "local/lfm2", java.nio.file.Path.of("/tmp/lfm2.gguf"),
                    registry, 0L, java.nio.file.Path.of("/tmp/registry.json")));
        }
    }

    private static ModelCapabilityService catalogOnly() {
        // No providers registered — resolution flows to ModelContextWindows / defaults.
        return new ModelCapabilityService(null);
    }

    private static AgentProvider agent(String name, String model, int maxTokens) {
        return AgentProvider.builder()
                .name(name)
                .displayName(name)
                .agentType(AgentType.API)
                .modelName(model)
                .maxTokens(maxTokens)
                .available(true)
                .build();
    }

    @Test
    void stagingLaneUsesLiveWindow() {
        ChatContextBudgetService service =
                new ChatContextBudgetService(catalogOnly(), new FakeStaging(4_096, 32_768));
        ChatContextBudgetService.ContextBudget budget =
                service.resolve(agent("kompile-local", "lfm2", 4096));

        assertEquals(4_096, budget.contextWindow());
        assertEquals("staging-live", budget.source());
        assertTrue(budget.maxOutputTokens() <= 1_024, "local lane reserves at most window/4 (capped)");
        assertTrue(budget.inputBudgetTokens() > 0);
        assertTrue(budget.inputBudgetTokens() < budget.contextWindow());
    }

    @Test
    void stagingLaneFallsBackToRegistryThenDefault() {
        ChatContextBudgetService registryBacked =
                new ChatContextBudgetService(catalogOnly(), new FakeStaging(null, 8_192));
        ChatContextBudgetService.ContextBudget fromRegistry =
                registryBacked.resolve(agent("local-staging", "local/lfm2", 0));
        assertEquals(8_192, fromRegistry.contextWindow());
        assertEquals("staging-registry", fromRegistry.source());

        ChatContextBudgetService bare =
                new ChatContextBudgetService(catalogOnly(), new FakeStaging(null, null));
        ChatContextBudgetService.ContextBudget fallback =
                bare.resolve(agent("kompile-local", "mystery", 0));
        assertEquals(4_096, fallback.contextWindow(), "local models never inherit the 128K default");
        assertEquals("staging-default", fallback.source());
    }

    @Test
    void localModelPrefixRoutesToStagingEvenWithOtherAgentName() {
        ChatContextBudgetService service =
                new ChatContextBudgetService(catalogOnly(), new FakeStaging(4_096, null));
        ChatContextBudgetService.ContextBudget budget =
                service.resolve(agent("some-facade", "local/lfm2", 0));
        assertEquals("staging-live", budget.source());
    }

    @Test
    void catalogLaneUsesModelCatalog() {
        ChatContextBudgetService service =
                new ChatContextBudgetService(catalogOnly(), new FakeStaging(null, null));
        ChatContextBudgetService.ContextBudget budget =
                service.resolve(agent("claude-cli", "claude-sonnet-4", 0));

        assertEquals("catalog", budget.source());
        assertEquals(ai.kompile.core.llm.ModelContextWindows.getContextWindow("claude-sonnet-4"),
                budget.contextWindow(), "catalog lane must mirror the shared catalog");
        assertTrue(budget.contextWindow() >= 100_000, "claude-sonnet-4 is a large-window model");
        assertFalse(service.isStagingBacked(agent("claude-cli", "claude-sonnet-4", 0)));
    }

    @Test
    void agentMaxTokensTightensOutputReservation() {
        ChatContextBudgetService service =
                new ChatContextBudgetService(catalogOnly(), new FakeStaging(null, null));
        ChatContextBudgetService.ContextBudget budget =
                service.resolve(agent("api-agent", "claude-sonnet-4", 2_000));
        assertEquals(2_000, budget.maxOutputTokens());
    }

    @Test
    void tokenEstimationUsesCharsPerFour() {
        assertEquals(100, ChatContextBudgetService.estimateTokens("x".repeat(400)));
        List<AgentChatRequest.ChatHistoryEntry> history = List.of(
                new AgentChatRequest.ChatHistoryEntry("user", "x".repeat(400)),
                new AgentChatRequest.ChatHistoryEntry("assistant", "y".repeat(800)));
        assertEquals(300, ChatContextBudgetService.estimateHistoryTokens(history));
    }
}
