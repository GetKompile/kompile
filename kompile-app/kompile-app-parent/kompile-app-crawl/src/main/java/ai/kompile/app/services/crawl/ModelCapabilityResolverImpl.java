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

package ai.kompile.app.services.crawl;

import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.crawl.graph.ModelCapabilityResolver;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.llm.ModelCapability;
import ai.kompile.core.llm.ModelContextWindows;
import ai.kompile.app.services.GraphExtractionConfigService;
import ai.kompile.modelmanager.RegistryBasedModelManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * App-main implementation of {@link ModelCapabilityResolver}. Resolves a crawl's extraction model to
 * its real context window + max output tokens so graph-extraction batch budgets come from true model
 * limits rather than guessed constants. Mirrors the {@code ResourceGovernorAdapter} pattern: the
 * interface is in app-core (visible to the crawl-graph orchestrator), the impl lives here where both
 * the remote model registry and the local model manager are reachable.
 *
 * <ul>
 *   <li>{@code LOCAL_MODEL} → the staged on-device model's {@code max_sequence_length} from
 *       {@link RegistryBasedModelManager}; output budget a conservative slice of that small context.</li>
 *   <li>{@code CLI_AGENT} → the agent's <em>live</em> configured model (from
 *       {@link AgentRegistryService}, e.g. {@code opencode-cli} → {@code deepseek-chat}) looked up in
 *       {@link ModelContextWindows}.</li>
 *   <li>{@code API_AGENT} / unknown → the configured model name looked up in {@link ModelContextWindows}.</li>
 * </ul>
 */
@Service
@Slf4j
public class ModelCapabilityResolverImpl implements ModelCapabilityResolver {

    /** Local on-device models have small windows; reserve only a modest slice for output. */
    private static final int LOCAL_MAX_OUTPUT_CAP = 1_024;
    /** Fallback context (tokens) for a local model the registry doesn't know. */
    private static final int LOCAL_DEFAULT_CONTEXT = 2_048;

    private final AgentRegistryService agentRegistry;
    private final RegistryBasedModelManager modelManager;
    private final GraphExtractionConfigService graphExtractionConfigService;

    @Autowired
    public ModelCapabilityResolverImpl(
            @Autowired(required = false) AgentRegistryService agentRegistry,
            @Autowired(required = false) RegistryBasedModelManager modelManager,
            @Autowired(required = false) GraphExtractionConfigService graphExtractionConfigService) {
        this.agentRegistry = agentRegistry;
        this.modelManager = modelManager;
        this.graphExtractionConfigService = graphExtractionConfigService;
    }

    @Override
    public Optional<ModelCapability> resolve(ProcessingRouteConfig.ProcessingBackendType backendType,
                                             String provider, String modelName, String agentName) {
        try {
            if (backendType == ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL) {
                return Optional.of(resolveLocal(modelName));
            }

            // Remote (CLI/API) or unknown: determine the concrete model name, then look up its real
            // context window + output ceiling. CLI agents carry the chosen model on the live
            // AgentProvider (cli-agents.json only declares the model FLAG, not the value).
            String resolvedModel = modelName;
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = cliAgentModel(agentName);
            }
            if (resolvedModel == null || resolvedModel.isBlank()) {
                // Last resort: the provider itself may name a CLI agent (e.g. "opencode-cli").
                resolvedModel = cliAgentModel(provider);
            }
            if (resolvedModel == null || resolvedModel.isBlank()) {
                // Hardening: the crawl request usually carries a generic provider ("default"/"llm-chat")
                // that names no agent, so fall back to the CONFIGURED extraction agent
                // (graph-extraction-config.json extractionModelProvider, e.g. "opencode-cli"). Without
                // this, every default-routed crawl budgets from the 128k DEFAULT instead of the agent's
                // real context window — the bug that made graph-extraction batches tiny.
                resolvedModel = configuredExtractionAgentModel();
            }
            if (resolvedModel == null || resolvedModel.isBlank()) {
                // The fallback executor selects from GraphExtractionConfig's allow/priority list later,
                // after the orchestrator has already planned graph batches. Use the same first
                // configured candidate here so large-context models (DeepSeek V4, Kimi, etc.) drive the
                // batch budget instead of the generic unknown-remote default.
                resolvedModel = configuredExtractionCandidateModel();
            }

            int contextTokens = ModelContextWindows.getContextWindow(resolvedModel);
            int maxOutputTokens = ModelContextWindows.getMaxOutputTokens(resolvedModel);
            return Optional.of(new ModelCapability(resolvedModel, contextTokens, maxOutputTokens, false));
        } catch (Exception e) {
            log.debug("Model capability resolution failed (backend={}, provider={}, model={}): {}",
                    backendType, provider, modelName, e.getMessage());
            return Optional.empty();
        }
    }

    private ModelCapability resolveLocal(String modelId) {
        Integer maxSeq = (modelManager != null && modelId != null && !modelId.isBlank())
                ? safeMaxSequenceLength(modelId) : null;
        int contextTokens = (maxSeq != null && maxSeq > 0) ? maxSeq : LOCAL_DEFAULT_CONTEXT;
        int outputTokens = Math.max(256, Math.min(LOCAL_MAX_OUTPUT_CAP, contextTokens / 2));
        return new ModelCapability(modelId, contextTokens, outputTokens, true);
    }

    private Integer safeMaxSequenceLength(String modelId) {
        try {
            return modelManager.getMaxSequenceLength(modelId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Model of the system's CONFIGURED extraction agent — the graph-extraction-config
     * {@code extractionModelProvider} (e.g. "opencode-cli") resolved to its live model. Used as the
     * fallback so a crawl that doesn't explicitly route an LLM still budgets from the real model window.
     */
    private String configuredExtractionAgentModel() {
        if (graphExtractionConfigService == null) {
            return null;
        }
        try {
            String configuredProvider = graphExtractionConfigService.getConfig().extractionModelProvider;
            if (configuredProvider != null && !configuredProvider.isBlank()
                    && !"default".equalsIgnoreCase(configuredProvider)) {
                return cliAgentModel(configuredProvider);
            }
        } catch (Exception e) {
            log.debug("Configured extraction agent fallback failed: {}", e.getMessage());
        }
        return null;
    }

    private String configuredExtractionCandidateModel() {
        if (graphExtractionConfigService == null) {
            return null;
        }
        try {
            GraphExtractionConfigService.GraphExtractionConfig config = graphExtractionConfigService.getConfig();
            String allowed = firstUsable(config.extractionModelAllow);
            if (allowed != null) {
                return allowed;
            }
            return firstUsable(config.extractionModelPriority);
        } catch (Exception e) {
            log.debug("Configured extraction candidate fallback failed: {}", e.getMessage());
            return null;
        }
    }

    private String firstUsable(List<String> models) {
        if (models == null) {
            return null;
        }
        for (String model : models) {
            if (model != null && !model.isBlank()) {
                return model.trim();
            }
        }
        return null;
    }

    private String cliAgentModel(String agentName) {
        if (agentRegistry == null || agentName == null || agentName.isBlank()) {
            return null;
        }
        try {
            return agentRegistry.getAgent(agentName)
                    .map(AgentProvider::getModelName)
                    .filter(m -> m != null && !m.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
