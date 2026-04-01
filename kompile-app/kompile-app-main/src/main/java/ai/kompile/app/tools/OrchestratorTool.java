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

import ai.kompile.orchestrator.api.OrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class OrchestratorTool {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorTool.class);

    private final OrchestratorService orchestratorService;

    @Autowired
    public OrchestratorTool(@Autowired(required = false) OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
        logger.info("OrchestratorTool initialized");
    }

    public record ListOrchestratorsInput() {}
    public record GetOrchestratorInput(String instanceId) {}
    public record StartOrchestratorInput(String instanceId) {}
    public record PauseOrchestratorInput(String instanceId) {}
    public record StopOrchestratorInput(String instanceId) {}
    public record CreateOrchestratorInput(String name, String description) {}
    public record GetOrchestratorContextInput(String instanceId) {}

    @Tool(name = "list_orchestrators",
            description = "Lists all registered orchestrator instances with their IDs, names, and current status.")
    public Map<String, Object> listOrchestrators(ListOrchestratorsInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            var instances = orchestratorService.getAllInstances();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", instances.size());
            result.put("orchestrators", instances.stream().map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.getInstanceId());
                m.put("name", i.getName());
                m.put("description", i.getDescription());
                m.put("status", i.getStatus() != null ? i.getStatus().name() : "UNKNOWN");
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing orchestrators: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_orchestrator",
            description = "Gets a specific orchestrator instance by its ID with full details including current state.")
    public Map<String, Object> getOrchestrator(GetOrchestratorInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            if (input.instanceId() == null) return Map.of("status", "error", "error", "instanceId is required");
            var instance = orchestratorService.getInstance(input.instanceId());
            if (instance.isEmpty()) return Map.of("status", "error", "error", "Orchestrator not found: " + input.instanceId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("orchestrator", instance.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting orchestrator: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "create_orchestrator",
            description = "Creates a new orchestrator instance with a name and description.")
    public Map<String, Object> createOrchestrator(CreateOrchestratorInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            if (input.name() == null || input.name().isBlank()) return Map.of("status", "error", "error", "name is required");
            var instance = orchestratorService.create(input.name(), input.description());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", instance.getInstanceId());
            result.put("name", instance.getName());
            result.put("message", "Orchestrator created");
            return result;
        } catch (Exception e) {
            logger.error("Error creating orchestrator: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "start_orchestrator",
            description = "Starts an orchestrator instance by its ID.")
    public Map<String, Object> startOrchestrator(StartOrchestratorInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            if (input.instanceId() == null) return Map.of("status", "error", "error", "instanceId is required");
            orchestratorService.start(input.instanceId());
            return Map.of("status", "success", "message", "Orchestrator started", "instanceId", input.instanceId());
        } catch (Exception e) {
            logger.error("Error starting orchestrator: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "pause_orchestrator",
            description = "Pauses a running orchestrator instance by its ID.")
    public Map<String, Object> pauseOrchestrator(PauseOrchestratorInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            if (input.instanceId() == null) return Map.of("status", "error", "error", "instanceId is required");
            orchestratorService.pause(input.instanceId());
            return Map.of("status", "success", "message", "Orchestrator paused", "instanceId", input.instanceId());
        } catch (Exception e) {
            logger.error("Error pausing orchestrator: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "stop_orchestrator",
            description = "Stops an orchestrator instance by its ID.")
    public Map<String, Object> stopOrchestrator(StopOrchestratorInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            if (input.instanceId() == null) return Map.of("status", "error", "error", "instanceId is required");
            orchestratorService.stop(input.instanceId());
            return Map.of("status", "success", "message", "Orchestrator stopped", "instanceId", input.instanceId());
        } catch (Exception e) {
            logger.error("Error stopping orchestrator: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_orchestrator_context",
            description = "Gets the current context/state data for an orchestrator instance.")
    public Map<String, Object> getOrchestratorContext(GetOrchestratorContextInput input) {
        try {
            if (orchestratorService == null) return Map.of("status", "error", "error", "OrchestratorService not available");
            if (input.instanceId() == null) return Map.of("status", "error", "error", "instanceId is required");
            var context = orchestratorService.getContext(input.instanceId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("instanceId", input.instanceId());
            result.put("context", context);
            return result;
        } catch (Exception e) {
            logger.error("Error getting orchestrator context: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
