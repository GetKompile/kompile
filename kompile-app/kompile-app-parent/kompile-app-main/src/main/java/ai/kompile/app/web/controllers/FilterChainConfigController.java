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

package ai.kompile.app.web.controllers;

import ai.kompile.filterchain.config.FilterChainConfig;
import ai.kompile.filterchain.config.FilterConfig;
import ai.kompile.filterchain.service.FilterChainConfigService;
import ai.kompile.filterchain.service.FilterChainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for filter chain configuration.
 * Provides endpoints for managing the filter chain via the UI.
 */
@RestController
@RequestMapping("/api/filterchain")
public class FilterChainConfigController {

    private static final Logger log = LoggerFactory.getLogger(FilterChainConfigController.class);

    private final FilterChainConfigService configService;
    private final FilterChainService filterChainService;

    @Autowired
    public FilterChainConfigController(
            @Autowired(required = false) FilterChainConfigService configService,
            @Autowired(required = false) FilterChainService filterChainService) {
        this.configService = configService;
        this.filterChainService = filterChainService;
    }

    /**
     * Get the current filter chain configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        if (configService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Filter chain module not enabled. Set kompile.filterchain.enabled=true"
            ));
        }

        FilterChainConfig config = configService.getConfiguration();
        Map<String, Object> response = new HashMap<>();
        response.put("available", true);
        response.put("enabled", config.isEnabled());
        response.put("globalTimeoutMs", config.getGlobalTimeoutMs());
        response.put("continueOnError", config.isContinueOnError());
        response.put("tracingEnabled", config.isTracingEnabled());
        response.put("filters", config.getFilters());
        response.put("configPath", configService.getConfigFilePath());

        return ResponseEntity.ok(response);
    }

    /**
     * Update the filter chain configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody FilterChainConfig config) {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        try {
            FilterChainConfig updated = configService.updateConfiguration(config);

            // Refresh the filter chain service
            if (filterChainService != null) {
                filterChainService.refresh();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "config", updated
            ));
        } catch (Exception e) {
            log.error("Failed to update filter chain config: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Toggle filter chain enabled state.
     */
    @PostMapping("/toggle")
    public ResponseEntity<?> toggleEnabled(@RequestBody Map<String, Boolean> request) {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        Boolean enabled = request.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing 'enabled' field"
            ));
        }

        FilterChainConfig config = configService.setEnabled(enabled);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", config.isEnabled()
        ));
    }

    /**
     * Get list of available filters.
     */
    @GetMapping("/filters")
    public ResponseEntity<?> getFilters() {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        List<FilterConfig> filters = configService.getFilters();

        // Also get available local filters from the service
        List<FilterChainService.FilterInfo> availableFilters = null;
        if (filterChainService != null) {
            availableFilters = filterChainService.getAvailableFilters();
        }

        return ResponseEntity.ok(Map.of(
                "configuredFilters", filters,
                "availableFilters", availableFilters != null ? availableFilters : List.of()
        ));
    }

    /**
     * Add a new filter.
     */
    @PostMapping("/filters")
    public ResponseEntity<?> addFilter(@RequestBody FilterConfig filter) {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        try {
            FilterChainConfig config = configService.addFilter(filter);

            if (filterChainService != null) {
                filterChainService.refresh();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "config", config
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Update an existing filter.
     */
    @PutMapping("/filters/{filterId}")
    public ResponseEntity<?> updateFilter(
            @PathVariable String filterId,
            @RequestBody FilterConfig filter) {

        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        try {
            // Ensure ID matches path
            filter.setId(filterId);
            FilterChainConfig config = configService.updateFilter(filter);

            if (filterChainService != null) {
                filterChainService.refresh();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "config", config
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Delete a filter.
     */
    @DeleteMapping("/filters/{filterId}")
    public ResponseEntity<?> deleteFilter(@PathVariable String filterId) {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        try {
            FilterChainConfig config = configService.removeFilter(filterId);

            if (filterChainService != null) {
                filterChainService.refresh();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "config", config
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Toggle a filter's enabled state.
     */
    @PostMapping("/filters/{filterId}/toggle")
    public ResponseEntity<?> toggleFilter(@PathVariable String filterId) {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        try {
            FilterChainConfig config = configService.toggleFilter(filterId);

            if (filterChainService != null) {
                filterChainService.refresh();
            }

            FilterConfig filter = config.getFilter(filterId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filterId", filterId,
                    "enabled", filter != null && filter.isEnabled()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<?> resetConfig() {
        if (configService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Filter chain module not enabled"
            ));
        }

        FilterChainConfig config = configService.resetConfiguration();

        if (filterChainService != null) {
            filterChainService.refresh();
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "config", config
        ));
    }

    /**
     * Get filter chain status.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        Map<String, Object> status = new HashMap<>();

        status.put("moduleAvailable", configService != null);

        if (configService != null) {
            FilterChainConfig config = configService.getConfiguration();
            status.put("enabled", config.isEnabled());
            status.put("filterCount", config.getFilters().size());
            status.put("configPath", configService.getConfigFilePath());
        }

        if (filterChainService != null) {
            status.put("serviceActive", filterChainService.isEnabled());
            status.put("activeFilters", filterChainService.getFilters().size());
        }

        return ResponseEntity.ok(status);
    }
}
