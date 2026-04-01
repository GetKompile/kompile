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

package ai.kompile.core.filter;

import java.util.Map;
import java.util.Set;

/**
 * Core interface for filters in the filter chain.
 * <p>
 * A filter is a reusable component that can:
 * <ul>
 *   <li>Inspect the incoming prompt, metadata, and conversation state</li>
 *   <li>Mutate or enrich the request (e.g., rewrite queries, build context)</li>
 *   <li>Short-circuit the flow and return a response early (e.g., block on compliance failure)</li>
 *   <li>Emit structured logs and traces for debugging</li>
 * </ul>
 * <p>
 * Filters can be local (built-in Java implementations) or remote (HTTP/MCP services).
 * They are executed in priority order within each phase of the RAG pipeline.
 */
public interface Filter {

    /**
     * Get the unique identifier for this filter.
     *
     * @return The filter ID
     */
    String getId();

    /**
     * Get the human-readable name of this filter.
     *
     * @return The filter name
     */
    String getName();

    /**
     * Get the description of what this filter does.
     *
     * @return The filter description
     */
    default String getDescription() {
        return getName();
    }

    /**
     * Get the phases where this filter applies.
     * A filter can apply to multiple phases.
     *
     * @return Set of applicable phases
     */
    Set<FilterPhase> getApplicablePhases();

    /**
     * Get the priority of this filter (lower = earlier execution).
     * Filters are sorted by priority within each phase.
     *
     * @return The priority (default 100)
     */
    default int getPriority() {
        return 100;
    }

    /**
     * Check if this filter is currently enabled.
     *
     * @return true if enabled
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Get the type of this filter (LOCAL, HTTP, MCP).
     *
     * @return The filter type
     */
    FilterType getType();

    /**
     * Execute the filter on the given context for the specified phase.
     * <p>
     * The filter should:
     * <ol>
     *   <li>Inspect the context</li>
     *   <li>Optionally mutate the context (and record mutations)</li>
     *   <li>Return a result indicating whether to continue or terminate</li>
     * </ol>
     *
     * @param context The filter context containing request/response data
     * @param phase The current execution phase
     * @return The filter result
     */
    FilterResult execute(FilterContext context, FilterPhase phase);

    /**
     * Check if this filter applies to the given phase.
     *
     * @param phase The phase to check
     * @return true if this filter should run in the given phase
     */
    default boolean appliesTo(FilterPhase phase) {
        Set<FilterPhase> phases = getApplicablePhases();
        return phases != null && phases.contains(phase);
    }

    /**
     * Get filter-specific configuration settings.
     * These can be used to customize filter behavior.
     *
     * @return Configuration settings map (may be empty)
     */
    default Map<String, Object> getSettings() {
        return Map.of();
    }

    /**
     * Check if this filter requires an LLM for its operation.
     * Useful for dependency management.
     *
     * @return true if an LLM is required
     */
    default boolean requiresLlm() {
        return false;
    }

    /**
     * Get categories this filter belongs to.
     * Used for organization and filtering in the UI.
     *
     * @return Array of category strings
     */
    default String[] getCategories() {
        return new String[0];
    }
}
