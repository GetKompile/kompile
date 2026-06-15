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

package ai.kompile.filterchain;

import ai.kompile.core.filter.Filter;
import ai.kompile.core.guardrails.InputGuardrail;
import ai.kompile.core.guardrails.OutputGuardrail;
import ai.kompile.filterchain.adapter.GuardrailInputFilterAdapter;
import ai.kompile.filterchain.adapter.GuardrailOutputFilterAdapter;
import ai.kompile.filterchain.config.FilterChainProperties;
import ai.kompile.filterchain.service.FilterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Auto-configuration for the filter chain framework.
 * <p>
 * This configuration:
 * <ul>
 *   <li>Enables filter chain when kompile.filterchain.enabled=true</li>
 *   <li>Discovers and wraps existing guardrails as filters</li>
 *   <li>Registers local filters from Spring context</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "ai.kompile.filterchain")
@EnableConfigurationProperties(FilterChainProperties.class)
@ConditionalOnClass(name = "ai.kompile.filterchain.FilterChainAutoConfiguration")
@ConditionalOnProperty(name = "kompile.filterchain.enabled", havingValue = "true", matchIfMissing = false)
public class FilterChainAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FilterChainAutoConfiguration.class);

    private final FilterRegistry filterRegistry;
    private final List<InputGuardrail> inputGuardrails;
    private final List<OutputGuardrail> outputGuardrails;
    private final List<Filter> customFilters;

    @Autowired
    public FilterChainAutoConfiguration(
            FilterRegistry filterRegistry,
            @Autowired(required = false) List<InputGuardrail> inputGuardrails,
            @Autowired(required = false) List<OutputGuardrail> outputGuardrails,
            @Autowired(required = false) List<Filter> customFilters) {
        this.filterRegistry = filterRegistry;
        this.inputGuardrails = inputGuardrails;
        this.outputGuardrails = outputGuardrails;
        this.customFilters = customFilters;
    }

    @PostConstruct
    public void initializeFilters() {
        log.info("Initializing filter chain...");

        int guardrailCount = 0;
        int customCount = 0;

        // Wrap input guardrails as filters
        if (inputGuardrails != null) {
            for (InputGuardrail guardrail : inputGuardrails) {
                GuardrailInputFilterAdapter adapter = new GuardrailInputFilterAdapter(guardrail);
                filterRegistry.registerLocalFilter(adapter);
                guardrailCount++;
                log.debug("Registered input guardrail as filter: {} (priority: {})",
                        adapter.getId(), adapter.getPriority());
            }
        }

        // Wrap output guardrails as filters
        if (outputGuardrails != null) {
            for (OutputGuardrail guardrail : outputGuardrails) {
                GuardrailOutputFilterAdapter adapter = new GuardrailOutputFilterAdapter(guardrail);
                filterRegistry.registerLocalFilter(adapter);
                guardrailCount++;
                log.debug("Registered output guardrail as filter: {} (priority: {})",
                        adapter.getId(), adapter.getPriority());
            }
        }

        // Register custom filters
        if (customFilters != null) {
            for (Filter filter : customFilters) {
                // Skip if already registered (e.g., guardrail adapters)
                if (!filterRegistry.hasFilter(filter.getId())) {
                    filterRegistry.registerLocalFilter(filter);
                    customCount++;
                    log.debug("Registered custom filter: {} (priority: {})",
                            filter.getId(), filter.getPriority());
                }
            }
        }

        // Rebuild phase index
        filterRegistry.rebuildPhaseIndex();

        log.info("Filter chain initialized: {} guardrail adapter(s), {} custom filter(s), {} total active",
                guardrailCount, customCount, filterRegistry.getActiveFilterCount());
    }
}
