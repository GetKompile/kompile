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

package ai.kompile.filterchain.service;

import ai.kompile.core.filter.Filter;
import ai.kompile.core.filter.FilterContext;
import ai.kompile.core.filter.FilterPhase;
import ai.kompile.core.filter.FilterResult;

import java.util.List;

/**
 * Service interface for executing the filter chain.
 * This is the main entry point for filter chain execution in the RAG pipeline.
 */
public interface FilterChainService {

    /**
     * Execute all enabled filters for the given phase.
     *
     * @param context The filter context
     * @param phase The phase to execute
     * @return The result of filter chain execution
     */
    FilterResult execute(FilterContext context, FilterPhase phase);

    /**
     * Check if the filter chain is enabled.
     */
    boolean isEnabled();

    /**
     * Get all registered filters.
     */
    List<Filter> getFilters();

    /**
     * Get filters for a specific phase, sorted by priority.
     */
    List<Filter> getFiltersForPhase(FilterPhase phase);

    /**
     * Register a local filter.
     *
     * @param filter The filter to register
     */
    void registerFilter(Filter filter);

    /**
     * Unregister a filter by ID.
     *
     * @param filterId The filter ID
     * @return true if the filter was removed
     */
    boolean unregisterFilter(String filterId);

    /**
     * Get a filter by ID.
     *
     * @param filterId The filter ID
     * @return The filter, or null if not found
     */
    Filter getFilter(String filterId);

    /**
     * Refresh the filter chain from configuration.
     * Call this after configuration changes.
     */
    void refresh();

    /**
     * Get information about available filters.
     */
    List<FilterInfo> getAvailableFilters();

    /**
     * Information about an available filter.
     */
    record FilterInfo(
            String id,
            String name,
            String description,
            String type,
            String[] categories,
            boolean enabled,
            int priority
    ) {}
}
