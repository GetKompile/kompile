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

package ai.kompile.core.crawl.graph;

import java.util.List;
import java.util.Map;

/**
 * Thin, dependency-free bridge that lets kompile-crawl-graph query CLI agent availability
 * and model state without depending on {@code kompile-app-main}, where the concrete agent
 * registry and CLI-pool types live.
 *
 * <p>The crawl-graph module depends only on {@code kompile-app-core} and
 * {@code kompile-cli-common}; it must never import app-main types. All agent-availability
 * queries therefore flow through this interface, which exposes only primitives, Strings, and
 * standard-library collections. The concrete implementation (app-main) delegates to the live
 * {@code CliAgentPool} / {@code AgentProvider} and answers queries without holding locks.</p>
 *
 * <p>Crawl-graph beans inject this as {@code @Autowired(required = false)}. When absent
 * (unit tests, CPU-only builds, or app contexts started without app-main services) every
 * method falls back to a safe no-op via its {@code default} implementation, so a {@code null}
 * adapter never causes a {@code NullPointerException} and never blocks a crawl.</p>
 */
public interface CliAgentAvailabilityAdapter {

    /**
     * Whether the named CLI agent is currently available — binary present and responding.
     *
     * <p>A {@code false} result means the agent process could not be contacted or has not
     * been registered; callers should skip or fall back rather than block.</p>
     *
     * @param agentId the agent identifier (e.g. {@code "opencode"}, {@code "claude"})
     * @return {@code true} if the agent is reachable; default {@code true} so that a missing
     *         adapter never blocks a crawl
     */
    default boolean isAvailable(String agentId) {
        return true;
    }

    /**
     * Ordered list of models currently discoverable for the given agent.
     *
     * <p>The list is ordered from most-preferred to least-preferred according to the agent's
     * own model-discovery command (e.g. {@code opencode models}). An empty list means no
     * models have been discovered yet or the agent does not support model listing.</p>
     *
     * @param agentId the agent identifier
     * @return mutable, ordered list of model IDs; never {@code null}; default {@link List#of()}
     */
    default List<String> availableModels(String agentId) {
        return List.of();
    }

    /**
     * The model currently selected for the given agent, or {@code null} if not known.
     *
     * @param agentId the agent identifier
     * @return the active model ID string (e.g. {@code "deepseek-v4-flash-free"}), or
     *         {@code null} when the adapter is absent or the agent has no active model
     */
    default String currentModel(String agentId) {
        return null;
    }

    /**
     * Switch the live agent to the specified model.
     *
     * <p>Implementations should apply the model flag to the agent's subprocess or pool entry
     * and return {@code true} only when the switch has been confirmed. Callers should treat
     * {@code false} as a signal to skip model-dependent logic gracefully.</p>
     *
     * @param agentId the agent identifier
     * @param modelId the model to activate (e.g. {@code "opencode/deepseek-v4-flash-free"})
     * @return {@code true} on success; default {@code false} (safe no-op when adapter absent)
     */
    default boolean setModel(String agentId, String modelId) {
        return false;
    }

    /**
     * Whether the given agent is appropriate for graph extraction work.
     *
     * <p>Filtering logic (e.g. excluding expensive paid agents such as {@code claude} or
     * {@code codex} from extraction) lives in {@code CrawlLlmDispatcher}, not here. This
     * method is a pure availability signal; it always returns {@code true} from the default
     * so that, in the absence of a concrete adapter, no agent is preemptively excluded.</p>
     *
     * @param agentId the agent identifier
     * @return {@code true} if the agent is considered eligible for extraction; default
     *         {@code true}
     */
    default boolean isExtractionAgent(String agentId) {
        return true;
    }

    /**
     * Push the active extraction model-selection policy to the underlying model service.
     *
     * <p>This is called at crawl-job start (and on config reload) so that model selection
     * for the entire job respects the per-project / per-crawl overrides from
     * {@link GraphExtractionConfig#getExtractionModelProviderAllow()},
     * {@link GraphExtractionConfig#getExtractionModelExcludeMarkers()},
     * {@link GraphExtractionConfig#getExtractionModelAllow()}, and
     * {@link GraphExtractionConfig#getExtractionModelPriority()}.</p>
     *
     * <p>The default no-op means that, when the adapter is absent (test slices, CPU-only
     * builds), the service retains whatever policy was previously set — no crawl is blocked.</p>
     *
     * @param providerAllow   whitelist of provider prefixes; null or empty = all providers allowed
     * @param excludeMarkers  model-id substrings to exclude; null = use service default ["claude","codex"]
     * @param modelAllow      explicit model-id allow-list; null or empty = no explicit pin
     */
    default void setActiveExtractionPolicy(List<String> providerAllow, List<String> excludeMarkers,
                                            List<String> modelAllow) {
        setActiveExtractionPolicy(providerAllow, excludeMarkers, modelAllow, null);
    }

    /**
     * Push the active extraction model-selection policy with a soft priority list. Priority does not
     * restrict candidates; it only orders discovered, healthy candidates before discovery-order
     * fallback.
     *
     * @param providerAllow   whitelist of provider prefixes; null or empty = all providers allowed
     * @param excludeMarkers  model-id substrings to exclude; null = use service default ["claude","codex"]
     * @param modelAllow      explicit model-id allow-list; null or empty = no explicit pin
     * @param modelPriority   soft preferred model ordering; null = service/project default, empty = no priority
     */
    default void setActiveExtractionPolicy(List<String> providerAllow, List<String> excludeMarkers,
                                            List<String> modelAllow, List<String> modelPriority) {
        // no-op default: safe when adapter is absent
    }

    /**
     * Push the per-crawl / per-project fallback-executor overrides (paid-tier guardrails + per-call
     * timeout) from a {@code GraphExtractionConfig} at job start. Each {@code null} argument leaves
     * the global default in effect. Companion to {@link #setActiveExtractionPolicy} so the fallback
     * executor config is configurable per job/project, not only globally.
     *
     * @param paidFallbackEnabled   allow the paid last-resort tier; null = global default
     * @param maxPaidCallsPerCrawl  per-crawl paid-call cap; null = global default
     * @param perCallTimeoutSeconds per-call LLM timeout (seconds); null = global default
     */
    default void setActiveExtractionFallbackOverride(Boolean paidFallbackEnabled,
                                                     Integer maxPaidCallsPerCrawl,
                                                     Integer perCallTimeoutSeconds) {
        // no-op default: safe when adapter is absent
    }

    /**
     * Pre-flight probe all candidate extraction models for the configured extraction agent
     * and return their availability outcomes (e.g. "OK", "INSUFFICIENT_BALANCE").
     *
     * <p>The default returns an empty map so that, when the adapter is absent, the probe
     * is simply skipped and the crawl proceeds normally.</p>
     *
     * @param maxModels maximum number of models to probe (0 = no cap)
     * @return map of model-id → outcome name; never null; default {@link Map#of()}
     */
    default Map<String, String> probeExtractionModels(int maxModels) {
        return Map.of();
    }

    /**
     * Compute the per-call char budget derived from the primary extraction model's context window.
     *
     * <p>Returns 0 when context-driven sizing is disabled or no model can be resolved.
     * A positive return value means the orchestrator should use this as the floor for
     * {@code graphExtractionTargetCharsPerBatch} (max of existing and this value).</p>
     *
     * @param fraction     fraction of context window to allocate (0 &lt; fraction ≤ 1.0)
     * @param charsPerToken estimated characters per token (e.g. 3.5)
     * @return char budget derived from context window, or 0 if unavailable
     */
    default int contextBudgetChars(double fraction, double charsPerToken) {
        return 0;
    }

    /**
     * Returns the name of the primary CLI agent eligible for extraction, or {@code null} if none.
     *
     * <p>Used by the context-budget calculator to look up a per-agent fallback context window
     * when model discovery returns no results (the common case for all non-opencode agents).
     * The default returns {@code null}; concrete implementations override to expose the live
     * agent registry lookup.</p>
     *
     * @return the agent name (e.g. {@code "opencode"}, {@code "claude"}), or {@code null}
     */
    default String resolveExtractionAgentName() {
        return null;
    }
}
