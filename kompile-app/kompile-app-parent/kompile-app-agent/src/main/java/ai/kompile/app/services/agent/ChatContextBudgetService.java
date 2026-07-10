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

import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the context budget for whatever a chat is actually talking to, picking the
 * authoritative metadata source per lane:
 *
 * <ul>
 *   <li><b>Staging-backed local models</b> ({@code kompile-local} OpenAI facade,
 *       {@code local-staging} pseudo-agent, or any {@code local/…} model id): the live
 *       {@code /api/llm/status maxContextLength} first (serving truth after KV bucketing),
 *       then the staging registry's {@code metadata.max_sequence_length}, then a
 *       conservative local default. These models must never inherit the 128K catalog
 *       default — a staged GGUF is typically 4K–32K.</li>
 *   <li><b>CLI-agent and hosted API models</b> (claude/codex/gemini/opencode models,
 *       OpenAI-compatible endpoints): {@link ModelCapabilityService}, which consults the
 *       registered LLM providers and the shared {@code ModelContextWindows} catalog
 *       (live models.dev data first, static table fallback).</li>
 * </ul>
 */
@Service
public class ChatContextBudgetService {

    private static final Logger log = LoggerFactory.getLogger(ChatContextBudgetService.class);

    /** Conservative window for a staged local model when neither live status nor registry knows. */
    static final int DEFAULT_LOCAL_CONTEXT_WINDOW = 4_096;

    /** Repo-wide chars-per-token heuristic (same convention as ModelCapability/CompactionService). */
    static final double CHARS_PER_TOKEN = 4.0;

    private static final int MIN_INPUT_BUDGET = 512;
    private static final int MIN_MAX_OUTPUT = 128;
    private static final int MAX_SYSTEM_OVERHEAD = 1_024;

    private final ModelCapabilityService modelCapabilityService;
    private final LocalStagingLlmService localStagingLlmService;

    @Autowired
    public ChatContextBudgetService(ModelCapabilityService modelCapabilityService,
                                    LocalStagingLlmService localStagingLlmService) {
        this.modelCapabilityService = modelCapabilityService;
        this.localStagingLlmService = localStagingLlmService;
    }

    /**
     * The budget for one chat lane.
     *
     * @param contextWindow     total window of the model, tokens
     * @param maxOutputTokens   generation reservation, tokens
     * @param inputBudgetTokens what may be spent on prompt + history, tokens
     * @param source            where the window number came from (for UI/diagnostics)
     */
    public record ContextBudget(String agentName,
                                String model,
                                int contextWindow,
                                int maxOutputTokens,
                                int inputBudgetTokens,
                                String source) {
    }

    /**
     * Resolve the context budget for an agent. Never throws — metadata lookups that fail
     * degrade to defaults so a budget is always available to the chat path.
     */
    public ContextBudget resolve(AgentProvider agent) {
        String agentName = agent != null ? agent.getName() : null;
        String model = agent != null ? agent.getModelName() : null;

        int contextWindow;
        int maxOutput;
        String source;

        if (isStagingBacked(agent)) {
            Optional<Integer> live = localStagingLlmService.liveMaxContextLength();
            if (live.isPresent()) {
                contextWindow = live.get();
                source = "staging-live";
            } else {
                Optional<LocalStagingLlmService.LocalModelCandidate> candidate =
                        localStagingLlmService.resolveCandidate(model);
                if (candidate.isPresent() && candidate.get().contextWindow() > 0) {
                    contextWindow = candidate.get().contextWindow();
                    source = "staging-registry";
                } else {
                    contextWindow = DEFAULT_LOCAL_CONTEXT_WINDOW;
                    source = "staging-default";
                }
            }
            // Local serving reserves at most a quarter of the window for generation.
            maxOutput = Math.max(MIN_MAX_OUTPUT, Math.min(1_024, contextWindow / 4));
        } else {
            ModelCapabilityService.ModelCapabilities caps = modelCapabilityService.getCapabilities(model);
            contextWindow = caps.contextWindow();
            maxOutput = caps.maxOutputTokens();
            source = "catalog";
        }

        // An explicit per-agent output cap wins when tighter than the model ceiling.
        if (agent != null && agent.getMaxTokens() > 0) {
            maxOutput = Math.min(maxOutput, agent.getMaxTokens());
        }
        // Generation may never eat more than half the window (protects tiny local models).
        maxOutput = Math.max(MIN_MAX_OUTPUT, Math.min(maxOutput, Math.max(MIN_MAX_OUTPUT, contextWindow / 2)));

        int systemOverhead = Math.min(MAX_SYSTEM_OVERHEAD, Math.max(64, contextWindow / 8));
        int inputBudget = Math.max(MIN_INPUT_BUDGET, contextWindow - maxOutput - systemOverhead);

        ContextBudget budget = new ContextBudget(agentName, model, contextWindow, maxOutput, inputBudget, source);
        log.debug("Resolved chat context budget: {}", budget);
        return budget;
    }

    /**
     * True when the agent's requests are ultimately served by the kompile staging/serving
     * lane, whose models are invisible to the model-name catalogs.
     */
    public boolean isStagingBacked(AgentProvider agent) {
        if (agent == null) return false;
        String name = agent.getName();
        if (KompileLocalModelService.AGENT_NAME.equals(name)
                || LocalStagingLlmService.AGENT_NAME.equals(name)) {
            return true;
        }
        String model = agent.getModelName();
        return model != null && model.startsWith(LocalStagingLlmService.MODEL_PREFIX);
    }

    /** Estimate tokens for a text using the repo-wide chars/4 heuristic. */
    public static int estimateTokens(String text) {
        return text == null ? 0 : (int) (text.length() / CHARS_PER_TOKEN);
    }

    /** Estimate tokens for a chat history using the repo-wide chars/4 heuristic. */
    public static int estimateHistoryTokens(List<AgentChatRequest.ChatHistoryEntry> history) {
        if (history == null || history.isEmpty()) return 0;
        int total = 0;
        for (AgentChatRequest.ChatHistoryEntry entry : history) {
            if (entry != null) {
                total += estimateTokens(entry.getContent());
            }
        }
        return total;
    }
}
