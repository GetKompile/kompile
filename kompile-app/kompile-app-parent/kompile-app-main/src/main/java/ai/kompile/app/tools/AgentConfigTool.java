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
import ai.kompile.app.web.controllers.SystemPromptController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for agent configuration management.
 * Exposes API agent config, ReAct agent settings, and system prompt management.
 */
@Component
public class AgentConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(AgentConfigTool.class);

    private final ApiAgentConfigController apiAgentConfigController;
    private final ReActAgentConfigController reactAgentConfigController;
    private final SystemPromptController systemPromptController;

    @Autowired
    public AgentConfigTool(
            @Autowired(required = false) ApiAgentConfigController apiAgentConfigController,
            @Autowired(required = false) ReActAgentConfigController reactAgentConfigController,
            @Autowired(required = false) SystemPromptController systemPromptController) {
        this.apiAgentConfigController = apiAgentConfigController;
        this.reactAgentConfigController = reactAgentConfigController;
        this.systemPromptController = systemPromptController;
    }

    // Input records
    public record ListApiAgentsInput() {}
    public record TestApiAgentInput(String name) {}
    public record GetReActConfigInput() {}
    public record GetReActStatusInput() {}
    public record GetEvaluationTypesInput() {}
    public record ListSystemPromptsInput() {}
    public record ListSystemPromptsForFactSheetInput(Long factSheetId) {}
    public record GetSystemPromptInput(String id) {}
    public record GetActiveSystemPromptInput() {}
    public record CreateSystemPromptInput(String name, String content, String description, List<String> tags) {}
    public record ActivateSystemPromptInput(String id) {}
    public record DeleteSystemPromptInput(String id) {}
    public record SearchSystemPromptsInput(String query) {}
    public record GetSystemPromptCountInput() {}

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

    // === System Prompts ===

    @Tool(name = "list_system_prompts",
            description = "Lists all system prompts for the active fact sheet.")
    public Map<String, Object> listSystemPrompts(ListSystemPromptsInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            ResponseEntity<?> response = systemPromptController.listPrompts();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing system prompts: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_system_prompts_for_fact_sheet",
            description = "Lists system prompts for a specific fact sheet.")
    public Map<String, Object> listSystemPromptsForFactSheet(ListSystemPromptsForFactSheetInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "Fact sheet ID is required");
            ResponseEntity<?> response = systemPromptController.listPromptsForFactSheet(input.factSheetId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing system prompts for fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_system_prompt",
            description = "Gets a specific system prompt by its ID.")
    public Map<String, Object> getSystemPrompt(GetSystemPromptInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Prompt ID is required");
            ResponseEntity<?> response = systemPromptController.getPrompt(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting system prompt: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_active_system_prompt",
            description = "Gets the currently active system prompt for the current fact sheet.")
    public Map<String, Object> getActiveSystemPrompt(GetActiveSystemPromptInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            ResponseEntity<?> response = systemPromptController.getActivePrompt();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting active system prompt: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "activate_system_prompt",
            description = "Activates a specific system prompt version by its ID.")
    public Map<String, Object> activateSystemPrompt(ActivateSystemPromptInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Prompt ID is required");
            ResponseEntity<?> response = systemPromptController.activatePrompt(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error activating system prompt: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_system_prompt",
            description = "Deletes a system prompt by its ID.")
    public Map<String, Object> deleteSystemPrompt(DeleteSystemPromptInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Prompt ID is required");
            ResponseEntity<?> response = systemPromptController.deletePrompt(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting system prompt: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "search_system_prompts",
            description = "Searches system prompts by name.")
    public Map<String, Object> searchSystemPrompts(SearchSystemPromptsInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Search query is required");
            ResponseEntity<?> response = systemPromptController.searchPrompts(input.query());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error searching system prompts: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_system_prompt_count",
            description = "Gets the total count of system prompts.")
    public Map<String, Object> getSystemPromptCount(GetSystemPromptCountInput input) {
        try {
            if (systemPromptController == null) return Map.of("status", "error", "error", "System prompt service not available");
            ResponseEntity<?> response = systemPromptController.getPromptCount();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting system prompt count: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
