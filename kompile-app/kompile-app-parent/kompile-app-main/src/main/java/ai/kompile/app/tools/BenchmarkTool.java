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

import ai.kompile.app.services.SamediffBenchmarkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class BenchmarkTool {

    private static final Logger logger = LoggerFactory.getLogger(BenchmarkTool.class);

    private final SamediffBenchmarkService benchmarkService;

    @Autowired
    public BenchmarkTool(@Autowired(required = false) SamediffBenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
        logger.info("BenchmarkTool initialized");
    }

    public record ListBenchmarkConfigsInput() {}
    public record GetBenchmarkConfigInput(String name) {}
    public record DeleteBenchmarkConfigInput(String name) {}
    public record GetActiveBenchmarkConfigInput() {}
    public record ActivateBenchmarkConfigInput(String name) {}

    @Tool(name = "list_benchmark_configs",
            description = "Lists all SameDiff benchmark configurations with their names and settings.")
    public Map<String, Object> listBenchmarkConfigs(ListBenchmarkConfigsInput input) {
        try {
            if (benchmarkService == null) return Map.of("status", "error", "error", "SamediffBenchmarkService not available");
            var configs = benchmarkService.listConfigs();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", configs.size());
            result.put("configs", configs);
            return result;
        } catch (Exception e) {
            logger.error("Error listing benchmark configs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_benchmark_config",
            description = "Gets a specific benchmark configuration by name.")
    public Map<String, Object> getBenchmarkConfig(GetBenchmarkConfigInput input) {
        try {
            if (benchmarkService == null) return Map.of("status", "error", "error", "SamediffBenchmarkService not available");
            if (input.name() == null) return Map.of("status", "error", "error", "name is required");
            var config = benchmarkService.getConfig(input.name());
            if (config == null) return Map.of("status", "error", "error", "Config not found: " + input.name());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("config", config);
            return result;
        } catch (Exception e) {
            logger.error("Error getting benchmark config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_benchmark_config",
            description = "Deletes a benchmark configuration by name.")
    public Map<String, Object> deleteBenchmarkConfig(DeleteBenchmarkConfigInput input) {
        try {
            if (benchmarkService == null) return Map.of("status", "error", "error", "SamediffBenchmarkService not available");
            if (input.name() == null) return Map.of("status", "error", "error", "name is required");
            boolean deleted = benchmarkService.deleteConfig(input.name());
            if (!deleted) return Map.of("status", "error", "error", "Config not found: " + input.name());
            return Map.of("status", "success", "message", "Config deleted", "name", input.name());
        } catch (Exception e) {
            logger.error("Error deleting benchmark config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_active_benchmark_config",
            description = "Gets the currently active benchmark configuration.")
    public Map<String, Object> getActiveBenchmarkConfig(GetActiveBenchmarkConfigInput input) {
        try {
            if (benchmarkService == null) return Map.of("status", "error", "error", "SamediffBenchmarkService not available");
            var config = benchmarkService.getActiveConfig();
            if (config == null) return Map.of("status", "success", "message", "No active config");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("config", config);
            return result;
        } catch (Exception e) {
            logger.error("Error getting active benchmark config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "activate_benchmark_config",
            description = "Activates a benchmark configuration by name, applying its settings to the SameDiff environment.")
    public Map<String, Object> activateBenchmarkConfig(ActivateBenchmarkConfigInput input) {
        try {
            if (benchmarkService == null) return Map.of("status", "error", "error", "SamediffBenchmarkService not available");
            if (input.name() == null) return Map.of("status", "error", "error", "name is required");
            var config = benchmarkService.activateConfig(input.name());
            if (config == null) return Map.of("status", "error", "error", "Config not found: " + input.name());
            benchmarkService.applyConfigToEnvironment(config);
            return Map.of("status", "success", "message", "Config activated and applied", "name", input.name());
        } catch (Exception e) {
            logger.error("Error activating benchmark config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
