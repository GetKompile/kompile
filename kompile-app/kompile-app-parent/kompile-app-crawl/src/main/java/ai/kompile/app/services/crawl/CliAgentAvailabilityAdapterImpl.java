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
import ai.kompile.app.services.agent.CliAgentModelService;
import ai.kompile.app.services.agent.ModelFallbackConfigManager;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.crawl.graph.CliAgentAvailabilityAdapter;
import ai.kompile.core.llm.ModelContextWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * App-main implementation of {@link CliAgentAvailabilityAdapter}.
 *
 * <p>Delegates to the live {@link AgentRegistryService} (for binary availability) and
 * {@link CliAgentModelService} (for model discovery and model switching). This keeps
 * {@code kompile-crawl-graph} free of any app-main dependency while still letting the
 * dispatcher consult real runtime state.</p>
 *
 * <p>All agent lookups are guarded: a missing agent returns the safe no-op defaults
 * (available=true, models=[], currentModel=null, setModel=false) rather than throwing.</p>
 */
@Component
public class CliAgentAvailabilityAdapterImpl implements CliAgentAvailabilityAdapter {

    private static final Logger log = LoggerFactory.getLogger(CliAgentAvailabilityAdapterImpl.class);

    @Autowired
    private AgentRegistryService agentRegistry;

    @Autowired
    private CliAgentModelService cliAgentModelService;

    // Optional: drives the per-crawl/per-project fallback-config override. required=false so the
    // adapter still works in boot contexts where the fallback executor isn't present.
    @Autowired(required = false)
    private ModelFallbackConfigManager modelFallbackConfigManager;

    @Override
    public boolean isAvailable(String agentId) {
        if (agentId == null || agentId.isBlank()) return false;
        try {
            return agentRegistry.checkAgentAvailability(agentId);
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.isAvailable('{}') failed: {}", agentId, e.getMessage());
            return true; // fail-open: do not block crawl on availability-check error
        }
    }

    @Override
    public List<String> availableModels(String agentId) {
        if (agentId == null || agentId.isBlank()) return List.of();
        try {
            CliAgentModelService.AgentModelInfo info =
                    cliAgentModelService.getModelsForAgent(agentId, false);
            List<String> models = info.availableModels();
            return models != null ? models : List.of();
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.availableModels('{}') failed: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String currentModel(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            return cliAgentModelService.getCurrentModel(agentId);
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.currentModel('{}') failed: {}", agentId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean setModel(String agentId, String modelId) {
        if (agentId == null || agentId.isBlank() || modelId == null || modelId.isBlank()) return false;
        try {
            return cliAgentModelService.setAgentModel(agentId, modelId);
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.setModel('{}', '{}') failed: {}",
                    agentId, modelId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isExtractionAgent(String agentId) {
        // claude-cli and codex-cli are paid agents and must NOT be used for extraction.
        // opencode-cli and other free-tier agents are eligible.
        if (agentId == null) return true;
        String lower = agentId.toLowerCase(Locale.ROOT);
        return !lower.contains("claude") && !lower.contains("codex");
    }

    @Override
    public void setActiveExtractionPolicy(List<String> providerAllow, List<String> excludeMarkers,
                                           List<String> modelAllow, List<String> modelPriority) {
        try {
            cliAgentModelService.setActiveExtractionPolicy(providerAllow, excludeMarkers, modelAllow, modelPriority);
            log.debug("Applied extraction policy: providerAllow={}, excludeMarkers={}, modelAllow={}, modelPriority={}",
                    providerAllow, excludeMarkers, modelAllow, modelPriority);
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.setActiveExtractionPolicy failed: {}", e.getMessage());
        }
    }

    @Override
    public void setActiveExtractionFallbackOverride(Boolean paidFallbackEnabled,
                                                    Integer maxPaidCallsPerCrawl,
                                                    Integer perCallTimeoutSeconds) {
        if (modelFallbackConfigManager == null) {
            return; // fallback executor not present in this boot context
        }
        try {
            modelFallbackConfigManager.setActiveExtractionFallbackOverride(
                    paidFallbackEnabled, maxPaidCallsPerCrawl, perCallTimeoutSeconds);
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.setActiveExtractionFallbackOverride failed: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, String> probeExtractionModels(int maxModels) {
        try {
            String agentName = resolveExtractionAgentName();
            if (agentName == null) {
                return Map.of();
            }
            Map<String, CliAgentModelService.ModelOutcome> raw =
                    cliAgentModelService.probeExtractionModels(agentName, maxModels);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((model, outcome) -> result.put(model, outcome.name()));
            return result;
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.probeExtractionModels failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public int contextBudgetChars(double fraction, double charsPerToken) {
        try {
            String agentName = resolveExtractionAgentName();
            if (agentName == null) return 0;
            List<String> models = cliAgentModelService.selectExtractionModels(agentName);
            if (models.isEmpty()) return 0;
            // Primary model = first entry (healthy-first ordering from selectExtractionModels).
            String primary = models.get(0);
            // Strip provider prefix (e.g. "opencode/deepseek-v4-pro" → "deepseek-v4-pro")
            String bareModel = primary.contains("/")
                    ? primary.substring(primary.lastIndexOf('/') + 1)
                    : primary;
            int ctxTokens = ModelContextWindows.getContextWindow(bareModel);
            long budgetChars = (long) (ctxTokens * charsPerToken * fraction);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, budgetChars));
        } catch (Exception e) {
            log.debug("CliAgentAvailabilityAdapter.contextBudgetChars failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the first non-api agent that passes {@link #isExtractionAgent(String)},
     * or {@code null} if none is registered. Exposed publicly so the context-budget calculator
     * in {@code CrawlRuntimeConfigManager} can look up a per-agent fallback context window when
     * model discovery returns no results.</p>
     */
    @Override
    public String resolveExtractionAgentName() {
        try {
            for (AgentProvider agent : agentRegistry.getAllAgents()) {
                if (!agent.isApiAgent() && isExtractionAgent(agent.getName())) {
                    return agent.getName();
                }
            }
        } catch (Exception e) {
            log.debug("resolveExtractionAgentName failed: {}", e.getMessage());
        }
        return null;
    }
}
