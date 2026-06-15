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

import ai.kompile.app.services.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PromptTemplateTool {

    private static final Logger logger = LoggerFactory.getLogger(PromptTemplateTool.class);

    private final PromptTemplateService promptTemplateService;

    @Autowired
    public PromptTemplateTool(@Autowired(required = false) PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
        logger.info("PromptTemplateTool initialized");
    }

    public record ListPromptTemplatesInput(String category) {}
    public record GetPromptTemplateInput(String name) {}
    public record DeletePromptTemplateInput(String name) {}
    public record SearchPromptTemplatesInput(String query) {}
    public record GetTemplatesByTagInput(String tag) {}

    @Tool(name = "list_prompt_templates",
            description = "Lists all prompt templates. Optionally filter by category.")
    public Map<String, Object> listPromptTemplates(ListPromptTemplatesInput input) {
        try {
            if (promptTemplateService == null) return Map.of("status", "error", "error", "PromptTemplateService not available");
            var templates = input.category() != null
                    ? promptTemplateService.getTemplatesByCategory(input.category())
                    : promptTemplateService.getAllTemplates();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", templates.size());
            result.put("templates", templates.stream().map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.getName());
                m.put("id", t.getId());
                m.put("category", t.getCategory());
                m.put("enabled", t.isEnabled());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing prompt templates: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_prompt_template",
            description = "Gets a specific prompt template by its name with full details including template text and variables.")
    public Map<String, Object> getPromptTemplate(GetPromptTemplateInput input) {
        try {
            if (promptTemplateService == null) return Map.of("status", "error", "error", "PromptTemplateService not available");
            if (input.name() == null) return Map.of("status", "error", "error", "name is required");
            var template = promptTemplateService.getTemplateByName(input.name());
            if (template.isEmpty()) return Map.of("status", "error", "error", "Template not found: " + input.name());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("template", template.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting prompt template: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_prompt_template",
            description = "Deletes a prompt template by its name.")
    public Map<String, Object> deletePromptTemplate(DeletePromptTemplateInput input) {
        try {
            if (promptTemplateService == null) return Map.of("status", "error", "error", "PromptTemplateService not available");
            if (input.name() == null) return Map.of("status", "error", "error", "name is required");
            boolean deleted = promptTemplateService.deleteTemplate(input.name());
            if (!deleted) return Map.of("status", "error", "error", "Template not found: " + input.name());
            return Map.of("status", "success", "message", "Template deleted", "name", input.name());
        } catch (Exception e) {
            logger.error("Error deleting prompt template: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "search_prompt_templates",
            description = "Searches prompt templates by query string matching name, description, or content.")
    public Map<String, Object> searchPromptTemplates(SearchPromptTemplatesInput input) {
        try {
            if (promptTemplateService == null) return Map.of("status", "error", "error", "PromptTemplateService not available");
            if (input.query() == null) return Map.of("status", "error", "error", "query is required");
            var templates = promptTemplateService.searchTemplates(input.query());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", templates.size());
            result.put("templates", templates);
            return result;
        } catch (Exception e) {
            logger.error("Error searching prompt templates: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_templates_by_tag",
            description = "Gets all prompt templates with a specific tag.")
    public Map<String, Object> getTemplatesByTag(GetTemplatesByTagInput input) {
        try {
            if (promptTemplateService == null) return Map.of("status", "error", "error", "PromptTemplateService not available");
            if (input.tag() == null) return Map.of("status", "error", "error", "tag is required");
            var templates = promptTemplateService.getTemplatesByTag(input.tag());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", templates.size());
            result.put("templates", templates);
            return result;
        } catch (Exception e) {
            logger.error("Error getting templates by tag: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
