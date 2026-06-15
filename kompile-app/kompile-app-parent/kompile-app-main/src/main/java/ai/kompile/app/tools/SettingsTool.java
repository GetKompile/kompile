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

package ai.kompile.app.tools;

import ai.kompile.filterchain.service.FilterChainConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SettingsTool {

    private static final Logger logger = LoggerFactory.getLogger(SettingsTool.class);

    private final FilterChainConfigService filterChainConfigService;

    @Autowired
    public SettingsTool(@Autowired(required = false) FilterChainConfigService filterChainConfigService) {
        this.filterChainConfigService = filterChainConfigService;
        logger.info("SettingsTool initialized");
    }

    public record GetFilterChainConfigInput() {}
    public record ToggleFilterChainInput(Boolean enabled) {}
    public record ToggleFilterInput(String filterId) {}
    public record GetFilterInput(String filterId) {}
    public record RemoveFilterInput(String filterId) {}
    public record ResetFilterChainInput() {}
    public record GetFiltersInput() {}

    @Tool(name = "get_filter_chain_config",
            description = "Gets the current filter chain configuration including all filters, their order, and enabled status.")
    public Map<String, Object> getFilterChainConfig(GetFilterChainConfigInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            var config = filterChainConfigService.getConfiguration();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("enabled", filterChainConfigService.isEnabled());
            result.put("config", config);
            return result;
        } catch (Exception e) {
            logger.error("Error getting filter chain config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "toggle_filter_chain",
            description = "Enables or disables the entire filter chain. Set enabled=true to enable, false to disable.")
    public Map<String, Object> toggleFilterChain(ToggleFilterChainInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            if (input.enabled() == null) return Map.of("status", "error", "error", "enabled is required");
            filterChainConfigService.setEnabled(input.enabled());
            return Map.of("status", "success", "enabled", input.enabled(), "message", "Filter chain " + (input.enabled() ? "enabled" : "disabled"));
        } catch (Exception e) {
            logger.error("Error toggling filter chain: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "toggle_filter",
            description = "Toggles a specific filter on or off by its filterId.")
    public Map<String, Object> toggleFilter(ToggleFilterInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            if (input.filterId() == null) return Map.of("status", "error", "error", "filterId is required");
            filterChainConfigService.toggleFilter(input.filterId());
            return Map.of("status", "success", "message", "Filter toggled", "filterId", input.filterId());
        } catch (Exception e) {
            logger.error("Error toggling filter: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_filter",
            description = "Gets details of a specific filter by its filterId.")
    public Map<String, Object> getFilter(GetFilterInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            if (input.filterId() == null) return Map.of("status", "error", "error", "filterId is required");
            var filter = filterChainConfigService.getFilter(input.filterId());
            if (filter == null) return Map.of("status", "error", "error", "Filter not found: " + input.filterId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("filter", filter);
            return result;
        } catch (Exception e) {
            logger.error("Error getting filter: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "remove_filter",
            description = "Removes a specific filter from the filter chain by its filterId.")
    public Map<String, Object> removeFilter(RemoveFilterInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            if (input.filterId() == null) return Map.of("status", "error", "error", "filterId is required");
            filterChainConfigService.removeFilter(input.filterId());
            return Map.of("status", "success", "message", "Filter removed", "filterId", input.filterId());
        } catch (Exception e) {
            logger.error("Error removing filter: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_filters",
            description = "Lists all configured filters in the filter chain.")
    public Map<String, Object> listFilters(GetFiltersInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            var filters = filterChainConfigService.getFilters();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", filters.size());
            result.put("filters", filters);
            return result;
        } catch (Exception e) {
            logger.error("Error listing filters: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_filter_chain",
            description = "Resets the filter chain configuration to defaults.")
    public Map<String, Object> resetFilterChain(ResetFilterChainInput input) {
        try {
            if (filterChainConfigService == null) return Map.of("status", "error", "error", "FilterChainConfigService not available");
            filterChainConfigService.resetConfiguration();
            return Map.of("status", "success", "message", "Filter chain reset to defaults");
        } catch (Exception e) {
            logger.error("Error resetting filter chain: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
