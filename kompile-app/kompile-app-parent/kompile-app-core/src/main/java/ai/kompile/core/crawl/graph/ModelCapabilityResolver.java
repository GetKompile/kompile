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

import ai.kompile.core.llm.ModelCapability;

import java.util.Optional;

/**
 * Resolves the real {@link ModelCapability} (context window + max output tokens) of the model a crawl
 * will call for graph extraction, so per-call batch sizes can be budgeted from true model limits
 * rather than guessed constants.
 *
 * <p>Optional adapter — mirrors {@link ResourceGovernorAdapter}: the implementation lives in app-main,
 * where both the remote model registry ({@code ModelContextWindows}) and the local model manager
 * (staged-model {@code max_sequence_length}) are reachable. It is absent in test/subprocess slices,
 * where the orchestrator falls back to {@link ModelCapability#unknownRemote()}.</p>
 */
public interface ModelCapabilityResolver {

    /**
     * Resolve the extraction model's capabilities. Implementations should resolve a CLI agent's
     * actual configured model (e.g. {@code opencode-cli} → {@code deepseek-chat}) before lookup.
     *
     * @param backendType the routed backend type ({@code LOCAL_MODEL} vs {@code CLI_AGENT}/{@code
     *                    API_AGENT}), or null when no explicit processing route is configured
     * @param provider    the configured extraction provider (e.g. {@code "opencode-cli"}); may be null
     * @param modelName   the configured model name; may be null/blank (CLI agents resolve it from the
     *                    agent registry)
     * @param agentName   the CLI agent name when {@code backendType} is {@code CLI_AGENT}, else null
     * @return the resolved capability, or empty when the model cannot be determined
     */
    Optional<ModelCapability> resolve(ProcessingRouteConfig.ProcessingBackendType backendType,
                                      String provider, String modelName, String agentName);

    /**
     * Effective concurrent-request capacity of the LOCAL model serving lane. One loaded model on
     * one accelerator stream serves generation serially, so the default is 1. Implementations may
     * report a higher value when the serving process actually accepts parallel generation (e.g. a
     * batched server advertising {@code maxConcurrentRequests} on its status endpoint). Extraction
     * clamps local wave width to this so queued calls don't masquerade as high-latency models —
     * queue wait would otherwise poison the latency/throughput EWMAs and the adaptive timeouts
     * derived from them.
     */
    default int localGenerationConcurrency() {
        return 1;
    }
}
