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

import ai.kompile.app.web.controllers.SystemPromptController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for system prompt management.
 *
 * Split out of {@code AgentConfigTool}, which used to cover API agents, ReAct settings and system
 * prompts in one bean. System prompts are a chat surface and {@code SystemPromptController} ships
 * in this module, while the API-agent and ReAct halves are admin; a single tool would have forced
 * one of the two personas to carry the other's controller. Tool names are unchanged, so MCP
 * clients see the same surface.
 *
 * See docs/architecture/app-persona-boundary.md.
 */
@Component
public class SystemPromptTool {

    private static final Logger logger = LoggerFactory.getLogger(SystemPromptTool.class);

    private final SystemPromptController systemPromptController;

    @Autowired
    public SystemPromptTool(@Autowired(required = false) SystemPromptController systemPromptController) {
        this.systemPromptController = systemPromptController;
    }

    // Input records
    public record ListSystemPromptsInput() {}
    public record ListSystemPromptsForFactSheetInput(Long factSheetId) {}
    public record GetSystemPromptInput(String id) {}
    public record GetActiveSystemPromptInput() {}
    public record CreateSystemPromptInput(String name, String content, String description, List<String> tags) {}
    public record ActivateSystemPromptInput(String id) {}
    public record DeleteSystemPromptInput(String id) {}
    public record SearchSystemPromptsInput(String query) {}
    public record GetSystemPromptCountInput() {}

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
