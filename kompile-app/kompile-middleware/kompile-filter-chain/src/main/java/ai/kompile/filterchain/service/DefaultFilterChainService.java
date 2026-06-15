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

import ai.kompile.core.filter.*;
import ai.kompile.filterchain.config.FilterChainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of the filter chain service.
 * Executes filters in priority order for each phase.
 */
@Service
public class DefaultFilterChainService implements FilterChainService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFilterChainService.class);

    private final FilterChainConfigService configService;
    private final FilterRegistry registry;

    @Autowired
    public DefaultFilterChainService(
            FilterChainConfigService configService,
            FilterRegistry registry) {
        this.configService = configService;
        this.registry = registry;
        log.info("DefaultFilterChainService initialized");
    }

    @Override
    public FilterResult execute(FilterContext context, FilterPhase phase) {
        if (!isEnabled()) {
            log.debug("Filter chain is disabled, skipping phase: {}", phase);
            return FilterResult.continueWith(context);
        }

        List<Filter> filters = registry.getFiltersForPhase(phase);
        if (filters.isEmpty()) {
            log.debug("No filters registered for phase: {}", phase);
            return FilterResult.continueWith(context);
        }

        log.debug("Executing {} filters for phase: {}", filters.size(), phase);

        FilterChainConfig config = configService.getConfiguration();
        List<FilterTraceEntry> allTraces = new ArrayList<>();
        FilterContext currentContext = context;

        for (Filter filter : filters) {
            if (!filter.isEnabled()) {
                log.debug("Skipping disabled filter: {}", filter.getId());
                continue;
            }

            try {
                long startTime = System.currentTimeMillis();

                log.debug("Executing filter: {} (priority: {})", filter.getId(), filter.getPriority());
                FilterResult result = filter.execute(currentContext, phase);

                long duration = System.currentTimeMillis() - startTime;

                // Add performance trace
                if (config.isTracingEnabled()) {
                    allTraces.add(FilterTraceEntry.performance(filter.getId(), phase, duration));
                    if (result.getTraces() != null) {
                        allTraces.addAll(result.getTraces());
                    }
                }

                // Check for termination
                if (result.isTerminating()) {
                    log.info("Filter '{}' terminated chain with action: {} (HTTP {})",
                            filter.getId(), result.getAction(), result.getHttpStatusCode());

                    // Record termination in context
                    currentContext.setTermination(FilterContext.FilterTermination.builder()
                            .filterId(filter.getId())
                            .action(result.getAction())
                            .message(result.getMessage())
                            .httpStatusCode(result.getHttpStatusCode())
                            .build());

                    return result.withFilterId(filter.getId())
                            .withTraces(allTraces)
                            .withExecutionTime(duration);
                }

                // Continue with mutated context
                if (result.getMutatedContext() != null) {
                    currentContext = result.getMutatedContext();
                }

                log.debug("Filter '{}' completed in {}ms", filter.getId(), duration);

            } catch (FilterExecutionException e) {
                log.error("Filter '{}' threw FilterExecutionException: {}", filter.getId(), e.getMessage());

                if (!config.isContinueOnError()) {
                    return e.toFilterResult()
                            .withFilterId(filter.getId())
                            .withTraces(allTraces);
                }

                allTraces.add(FilterTraceEntry.error(filter.getId(),
                        "Filter error (continuing): " + e.getMessage()));

            } catch (Exception e) {
                log.error("Unexpected error in filter '{}': {}", filter.getId(), e.getMessage(), e);

                if (!config.isContinueOnError()) {
                    return FilterResult.terminateFatalError("Filter '" + filter.getId() + "' failed: " + e.getMessage())
                            .withFilterId(filter.getId())
                            .withTraces(allTraces);
                }

                allTraces.add(FilterTraceEntry.error(filter.getId(),
                        "Unexpected error (continuing): " + e.getMessage()));
            }
        }

        // All filters passed, add traces to context
        if (config.isTracingEnabled()) {
            currentContext.getTraces().addAll(allTraces);
        }

        log.debug("Phase {} completed successfully with {} filter(s)", phase, filters.size());
        return FilterResult.continueWith(currentContext).withTraces(allTraces);
    }

    @Override
    public boolean isEnabled() {
        return configService.isEnabled();
    }

    @Override
    public List<Filter> getFilters() {
        return registry.getActiveFilters();
    }

    @Override
    public List<Filter> getFiltersForPhase(FilterPhase phase) {
        return registry.getFiltersForPhase(phase);
    }

    @Override
    public void registerFilter(Filter filter) {
        registry.registerLocalFilter(filter);
    }

    @Override
    public boolean unregisterFilter(String filterId) {
        return registry.unregisterFilter(filterId);
    }

    @Override
    public Filter getFilter(String filterId) {
        return registry.getActiveFilter(filterId);
    }

    @Override
    public void refresh() {
        registry.refresh();
        log.info("Filter chain refreshed: {} active filters", registry.getActiveFilterCount());
    }

    @Override
    public List<FilterInfo> getAvailableFilters() {
        return registry.getFilterInfo();
    }
}
