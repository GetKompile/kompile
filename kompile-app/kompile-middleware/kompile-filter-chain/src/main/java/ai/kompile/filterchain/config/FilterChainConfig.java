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

package ai.kompile.filterchain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration for the filter chain.
 * This configuration is persisted to JSON and can be modified via the UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterChainConfig {

    /**
     * Whether filter chains are globally enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Ordered list of filter configurations.
     * Filters are executed in order, sorted by priority within each phase.
     */
    @Builder.Default
    private List<FilterConfig> filters = new ArrayList<>();

    /**
     * Global timeout for remote filters in milliseconds.
     * Individual filters can override this.
     */
    @Builder.Default
    private int globalTimeoutMs = 30000;

    /**
     * Whether to continue execution if a filter fails with an error.
     * If false (default), any filter error stops the chain.
     */
    @Builder.Default
    private boolean continueOnError = false;

    /**
     * Whether to collect traces from filter execution.
     */
    @Builder.Default
    private boolean tracingEnabled = true;

    /**
     * Maximum number of traces to keep per request.
     */
    @Builder.Default
    private int maxTracesPerRequest = 100;

    /**
     * Create default configuration.
     */
    public static FilterChainConfig defaults() {
        return FilterChainConfig.builder()
                .enabled(true)
                .filters(new ArrayList<>())
                .globalTimeoutMs(30000)
                .continueOnError(false)
                .tracingEnabled(true)
                .maxTracesPerRequest(100)
                .build();
    }

    /**
     * Merge another configuration into this one.
     * Non-null values in the other config override this config's values.
     *
     * @param other The configuration to merge
     * @return A new merged configuration
     */
    public FilterChainConfig merge(FilterChainConfig other) {
        if (other == null) {
            return this;
        }

        return FilterChainConfig.builder()
                .enabled(other.enabled)
                .filters(other.filters != null && !other.filters.isEmpty() ? other.filters : this.filters)
                .globalTimeoutMs(other.globalTimeoutMs > 0 ? other.globalTimeoutMs : this.globalTimeoutMs)
                .continueOnError(other.continueOnError)
                .tracingEnabled(other.tracingEnabled)
                .maxTracesPerRequest(other.maxTracesPerRequest > 0 ? other.maxTracesPerRequest : this.maxTracesPerRequest)
                .build();
    }

    /**
     * Add a filter to the configuration.
     *
     * @param filter The filter configuration to add
     */
    public void addFilter(FilterConfig filter) {
        if (filters == null) {
            filters = new ArrayList<>();
        }
        filters.add(filter);
    }

    /**
     * Remove a filter by ID.
     *
     * @param filterId The filter ID to remove
     * @return true if a filter was removed
     */
    public boolean removeFilter(String filterId) {
        if (filters == null || filterId == null) {
            return false;
        }
        return filters.removeIf(f -> filterId.equals(f.getId()));
    }

    /**
     * Get a filter by ID.
     *
     * @param filterId The filter ID
     * @return The filter configuration, or null if not found
     */
    public FilterConfig getFilter(String filterId) {
        if (filters == null || filterId == null) {
            return null;
        }
        return filters.stream()
                .filter(f -> filterId.equals(f.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Update a filter configuration.
     *
     * @param filter The filter configuration to update
     * @return true if the filter was found and updated
     */
    public boolean updateFilter(FilterConfig filter) {
        if (filters == null || filter == null || filter.getId() == null) {
            return false;
        }
        for (int i = 0; i < filters.size(); i++) {
            if (filter.getId().equals(filters.get(i).getId())) {
                filters.set(i, filter);
                return true;
            }
        }
        return false;
    }
}
