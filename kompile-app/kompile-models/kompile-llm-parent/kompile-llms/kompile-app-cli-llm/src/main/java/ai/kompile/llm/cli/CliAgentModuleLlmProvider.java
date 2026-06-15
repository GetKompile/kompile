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

package ai.kompile.llm.cli;

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
 * LlmProvider implementation that exposes CLI coding agents as available models.
 * <p>
 * Discovers installed CLI agents (claude, codex, gemini) and any user-defined agents,
 * listing them as models in the orchestrator's provider registry.
 * Availability is read from {@link CliAgentLlmConfigService} at runtime.
 * </p>
 */
@Component
@ConditionalOnClass(name = "ai.kompile.orchestrator.api.LlmProvider")
public class CliAgentModuleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(CliAgentModuleLlmProvider.class);

    private final CliAgentLlmConfigService configService;

    public CliAgentModuleLlmProvider(CliAgentLlmConfigService configService) {
        this.configService = configService;
        log.info("CLI Agent LLM Provider initialized for orchestrator model listing");
    }

    @Override
    public String getId() {
        return "cli-agent-llm";
    }

    @Override
    public String getDisplayName() {
        return "CLI Agent LLM";
    }

    @Override
    public boolean isAvailable() {
        return configService.isEnabled()
                && configService.checkAvailability(configService.getActiveCommand());
    }

    @Override
    public int getPriority() {
        return 60;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public int getMaxTokens() {
        return -1;
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        List<ModelInfo> models = new ArrayList<>();

        for (CliAgentLlmConfigService.AgentStatus agent : configService.listAgents()) {
            String description = agent.description();
            if (!agent.available()) {
                description += " (not installed)";
            }

            models.add(new ModelInfo(
                    "cli:" + agent.name(),
                    agent.displayName(),
                    description,
                    -1,
                    true
            ));
        }

        // Sort: available first, then active first
        models.sort((a, b) -> {
            boolean aAvail = !a.description().contains("(not installed)");
            boolean bAvail = !b.description().contains("(not installed)");
            if (aAvail != bAvail) return aAvail ? -1 : 1;

            String activeId = "cli:" + configService.getActiveCommand();
            if (a.id().equals(activeId) != b.id().equals(activeId)) {
                return a.id().equals(activeId) ? -1 : 1;
            }

            return a.displayName().compareTo(b.displayName());
        });

        return models;
    }

    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        throw new UnsupportedOperationException(
                "CLI Agent LLM uses LanguageModel interface for RAG. " +
                "Use the CliAgentLanguageModelImpl bean for direct LLM calls."
        );
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        throw new UnsupportedOperationException("Use CliAgentLanguageModelImpl for CLI agent LLM calls");
    }

    @Override
    public void cancelSession(Long sessionId) {
        // No-op
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
