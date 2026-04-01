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

import java.util.List;

/**
 * Interface for the filter chain executor.
 * <p>
 * The filter chain is an ordered list of filters that a request flows through.
 * Filters are executed in priority order within each phase, and can:
 * <ul>
 *   <li>Mutate the context and pass it downstream</li>
 *   <li>Short-circuit and return early</li>
 * </ul>
 */
public interface FilterChain {

    /**
     * Execute all filters for the given phase.
     *
     * @param context The filter context
     * @param phase The phase to execute
     * @return The result of filter chain execution
     */
    FilterResult execute(FilterContext context, FilterPhase phase);

    /**
     * Get all registered filters.
     *
     * @return List of all filters
     */
    List<Filter> getFilters();

    /**
     * Get filters for a specific phase, sorted by priority.
     *
     * @param phase The phase to get filters for
     * @return List of filters for the phase
     */
    List<Filter> getFiltersForPhase(FilterPhase phase);

    /**
     * Check if the filter chain is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Register a new filter.
     *
     * @param filter The filter to register
     */
    void registerFilter(Filter filter);

    /**
     * Unregister a filter by ID.
     *
     * @param filterId The filter ID to unregister
     * @return true if the filter was found and removed
     */
    boolean unregisterFilter(String filterId);

    /**
     * Get a filter by ID.
     *
     * @param filterId The filter ID
     * @return The filter, or null if not found
     */
    Filter getFilter(String filterId);
}
