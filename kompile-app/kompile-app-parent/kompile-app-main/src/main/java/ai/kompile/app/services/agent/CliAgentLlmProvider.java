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
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * LlmProvider implementation that exposes CLI-based AI agents (Claude Code, Codex, Gemini CLI).
 * These are subprocess-based agents that run locally installed CLI tools.
 * <p>
 * Each CLI agent is exposed as a separate "model" within this provider.
 */
@Component
@ConditionalOnClass(name = "ai.kompile.orchestrator.api.LlmProvider")
public class CliAgentLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(CliAgentLlmProvider.class);

    private final AgentRegistryService agentRegistryService;

    public CliAgentLlmProvider(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
        log.info("CliAgentLlmProvider initialized, will expose CLI agents as models");
    }

    @Override
    public String getId() {
        return "cli-agents";
    }

    @Override
    public String getDisplayName() {
        return "CLI Agents";
    }

    @Override
    public boolean isAvailable() {
        // Available if any CLI agent is installed
        return agentRegistryService.hasAvailableAgents();
    }

    @Override
    public int getPriority() {
        return 50; // Medium priority - prefer API providers but CLI agents are useful
    }

    @Override
    public boolean supportsStreaming() {
        return true; // CLI agents support streaming output
    }

    @Override
    public int getMaxTokens() {
        return -1; // CLI agents don't have a fixed token limit
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    @Override
    public List<LlmProvider.ModelInfo> getAvailableModels() {
        List<LlmProvider.ModelInfo> models = new ArrayList<>();

        for (AgentProvider agent : agentRegistryService.getAllAgents()) {
            // Include all agents, but mark availability
            String description = agent.getDescription();
            if (!agent.isAvailable()) {
                description = (description != null ? description : agent.getDisplayName()) + " (not installed)";
            }

            models.add(new LlmProvider.ModelInfo(
                    agent.getName(),           // e.g., "claude-cli"
                    agent.getDisplayName(),    // e.g., "Claude Code"
                    description,
                    -1,                        // No fixed context window
                    true                       // CLI agents support tools/file access
            ));
        }

        // Sort: available agents first, then by default status
        models.sort((a, b) -> {
            AgentProvider agentA = agentRegistryService.getAgent(a.id()).orElse(null);
            AgentProvider agentB = agentRegistryService.getAgent(b.id()).orElse(null);

            if (agentA == null || agentB == null) return 0;

            // Available agents first
            if (agentA.isAvailable() != agentB.isAvailable()) {
                return agentA.isAvailable() ? -1 : 1;
            }

            // Default agent first among available
            if (agentA.isDefault() != agentB.isDefault()) {
                return agentA.isDefault() ? -1 : 1;
            }

            return a.displayName().compareTo(b.displayName());
        });

        log.debug("Returning {} CLI agent models ({} available)",
                models.size(), agentRegistryService.getAvailableAgentCount());

        return models;
    }

    // Session management - delegate to AgentChatService
    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        // CLI agents use AgentChatService for session management, not this provider
        throw new UnsupportedOperationException(
                "Use AgentChatService for CLI agent sessions. " +
                "This provider is for discovery and model listing only."
        );
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        throw new UnsupportedOperationException("Use AgentChatService for CLI agent sessions");
    }

    @Override
    public void cancelSession(Long sessionId) {
        // No-op - AgentChatService handles cancellation
    }

    @Override
    public boolean isSessionActive(Long sessionId) {
        return false;
    }

    @Override
    public Flux<String> streamOutput(Long sessionId) {
        return Flux.empty();
    }
}
