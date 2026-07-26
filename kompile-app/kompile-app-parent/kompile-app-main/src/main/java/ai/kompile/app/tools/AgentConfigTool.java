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

import ai.kompile.app.web.controllers.ApiAgentConfigController;
import ai.kompile.app.web.controllers.ReActAgentConfigController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for agent configuration management.
 * Exposes API agent config and ReAct agent settings.
 *
 * System prompt management used to live here too; it moved to {@code SystemPromptTool} in
 * kompile-app-web-chat because {@code SystemPromptController} is a chat surface while the API-agent
 * and ReAct controllers are admin. Tool names are unchanged on both sides.
 * See docs/architecture/app-persona-boundary.md.
 */
@Component
public class AgentConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(AgentConfigTool.class);

    private final ApiAgentConfigController apiAgentConfigController;
    private final ReActAgentConfigController reactAgentConfigController;

    @Autowired
    public AgentConfigTool(
            @Autowired(required = false) ApiAgentConfigController apiAgentConfigController,
            @Autowired(required = false) ReActAgentConfigController reactAgentConfigController) {
        this.apiAgentConfigController = apiAgentConfigController;
        this.reactAgentConfigController = reactAgentConfigController;
    }

    // Input records
    public record ListApiAgentsInput() {}
    public record TestApiAgentInput(String name) {}
    public record GetReActConfigInput() {}
    public record GetReActStatusInput() {}
    public record GetEvaluationTypesInput() {}

    // === API Agent Config ===

    @Tool(name = "list_api_agents",
            description = "Lists all configured API agents (OpenAI-compatible endpoints) with masked API keys.")
    public Map<String, Object> listApiAgents(ListApiAgentsInput input) {
        try {
            if (apiAgentConfigController == null) return Map.of("status", "error", "error", "API agent config not available");
            ResponseEntity<?> response = apiAgentConfigController.listApiAgents();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing API agents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_api_agent",
            description = "Tests connectivity to a configured API agent endpoint by name.")
    public Map<String, Object> testApiAgent(TestApiAgentInput input) {
        try {
            if (apiAgentConfigController == null) return Map.of("status", "error", "error", "API agent config not available");
            if (input.name() == null) return Map.of("status", "error", "error", "Agent name is required");
            ResponseEntity<?> response = apiAgentConfigController.testApiAgent(input.name());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error testing API agent: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === ReAct Agent Config ===

    @Tool(name = "get_react_agent_config",
            description = "Gets the current ReAct agent configuration.")
    public Map<String, Object> getReActConfig(GetReActConfigInput input) {
        try {
            if (reactAgentConfigController == null) return Map.of("status", "error", "error", "ReAct agent config not available");
            ResponseEntity<?> response = reactAgentConfigController.getConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting ReAct agent config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_react_agent_status",
            description = "Gets the ReAct agent configuration status.")
    public Map<String, Object> getReActStatus(GetReActStatusInput input) {
        try {
            if (reactAgentConfigController == null) return Map.of("status", "error", "error", "ReAct agent config not available");
            ResponseEntity<?> response = reactAgentConfigController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting ReAct agent status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_evaluation_types",
            description = "Gets available evaluation types for the ReAct agent.")
    public Map<String, Object> getEvaluationTypes(GetEvaluationTypesInput input) {
        try {
            if (reactAgentConfigController == null) return Map.of("status", "error", "error", "ReAct agent config not available");
            ResponseEntity<?> response = reactAgentConfigController.getEvaluationTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting evaluation types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

}
