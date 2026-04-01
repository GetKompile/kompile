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
import ai.kompile.core.filter.FilterPhase;
import ai.kompile.core.filter.FilterType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for managing available filters.
 * Maintains both local (built-in) filters and configuration for remote filters.
 */
@Component
public class FilterRegistry {

    private static final Logger log = LoggerFactory.getLogger(FilterRegistry.class);

    /**
     * Local (built-in) filters by ID.
     */
    private final Map<String, Filter> localFilters = new ConcurrentHashMap<>();

    /**
     * All active filters by ID.
     */
    private final Map<String, Filter> activeFilters = new ConcurrentHashMap<>();

    /**
     * Filters grouped by phase for fast lookup.
     */
    private final Map<FilterPhase, List<Filter>> filtersByPhase = new ConcurrentHashMap<>();

    /**
     * Register a local (built-in) filter.
     * Local filters are Java implementations discovered via Spring.
     *
     * @param filter The filter to register
     */
    public void registerLocalFilter(Filter filter) {
        if (filter == null || filter.getId() == null) {
            log.warn("Cannot register filter with null ID");
            return;
        }

        localFilters.put(filter.getId(), filter);
        log.info("Registered local filter: {} ({})", filter.getId(), filter.getName());

        // If enabled, add to active filters
        if (filter.isEnabled()) {
            activateFilter(filter);
        }
    }

    /**
     * Register multiple local filters.
     */
    public void registerLocalFilters(List<Filter> filters) {
        if (filters != null) {
            filters.forEach(this::registerLocalFilter);
        }
    }

    /**
     * Activate a filter (add to active filters and phase index).
     */
    public void activateFilter(Filter filter) {
        if (filter == null || filter.getId() == null) {
            return;
        }

        activeFilters.put(filter.getId(), filter);

        // Add to phase index
        for (FilterPhase phase : filter.getApplicablePhases()) {
            filtersByPhase.computeIfAbsent(phase, k -> new ArrayList<>()).add(filter);
        }

        // Re-sort filters for each phase
        rebuildPhaseIndex();

        log.debug("Activated filter: {} for phases: {}", filter.getId(), filter.getApplicablePhases());
    }

    /**
     * Deactivate a filter.
     */
    public void deactivateFilter(String filterId) {
        Filter removed = activeFilters.remove(filterId);
        if (removed != null) {
            // Remove from phase index
            for (List<Filter> phaseFilters : filtersByPhase.values()) {
                phaseFilters.removeIf(f -> filterId.equals(f.getId()));
            }
            log.debug("Deactivated filter: {}", filterId);
        }
    }

    /**
     * Get a local filter by ID.
     */
    public Filter getLocalFilter(String filterId) {
        return localFilters.get(filterId);
    }

    /**
     * Get an active filter by ID.
     */
    public Filter getActiveFilter(String filterId) {
        return activeFilters.get(filterId);
    }

    /**
     * Get all active filters.
     */
    public List<Filter> getActiveFilters() {
        return new ArrayList<>(activeFilters.values());
    }

    /**
     * Get all local filters.
     */
    public List<Filter> getLocalFilters() {
        return new ArrayList<>(localFilters.values());
    }

    /**
     * Get filters for a specific phase, sorted by priority.
     */
    public List<Filter> getFiltersForPhase(FilterPhase phase) {
        return filtersByPhase.getOrDefault(phase, Collections.emptyList());
    }

    /**
     * Unregister a filter by ID.
     */
    public boolean unregisterFilter(String filterId) {
        Filter removed = localFilters.remove(filterId);
        if (removed != null) {
            deactivateFilter(filterId);
            log.info("Unregistered filter: {}", filterId);
            return true;
        }
        return false;
    }

    /**
     * Clear and rebuild the phase index.
     */
    public void rebuildPhaseIndex() {
        // Clear existing
        filtersByPhase.clear();

        // Rebuild from active filters
        for (Filter filter : activeFilters.values()) {
            if (filter.isEnabled()) {
                for (FilterPhase phase : filter.getApplicablePhases()) {
                    filtersByPhase.computeIfAbsent(phase, k -> new ArrayList<>()).add(filter);
                }
            }
        }

        // Sort each phase by priority
        for (List<Filter> phaseFilters : filtersByPhase.values()) {
            phaseFilters.sort(Comparator.comparingInt(Filter::getPriority));
        }
    }

    /**
     * Rebuild active filters from local filters and configuration.
     * Called after configuration changes.
     */
    public void refresh() {
        activeFilters.clear();

        // Re-activate enabled local filters
        for (Filter filter : localFilters.values()) {
            if (filter.isEnabled()) {
                activeFilters.put(filter.getId(), filter);
            }
        }

        rebuildPhaseIndex();
        log.info("Refreshed filter registry: {} active filters", activeFilters.size());
    }

    /**
     * Get filter info for all registered filters.
     */
    public List<FilterChainService.FilterInfo> getFilterInfo() {
        List<FilterChainService.FilterInfo> info = new ArrayList<>();

        for (Filter filter : localFilters.values()) {
            info.add(new FilterChainService.FilterInfo(
                    filter.getId(),
                    filter.getName(),
                    filter.getDescription(),
                    filter.getType().name(),
                    filter.getCategories(),
                    filter.isEnabled(),
                    filter.getPriority()
            ));
        }

        return info;
    }

    /**
     * Check if a filter is registered.
     */
    public boolean hasFilter(String filterId) {
        return localFilters.containsKey(filterId) || activeFilters.containsKey(filterId);
    }

    /**
     * Get the count of active filters.
     */
    public int getActiveFilterCount() {
        return activeFilters.size();
    }

    /**
     * Get the count of filters for a phase.
     */
    public int getFilterCountForPhase(FilterPhase phase) {
        return filtersByPhase.getOrDefault(phase, Collections.emptyList()).size();
    }
}
