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

import ai.kompile.app.services.prompt.PromptTemplateService;
import ai.kompile.core.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing prompt templates.
 * Provides CRUD operations and template rendering endpoints.
 */
@RestController
@RequestMapping("/api/prompts")
public class PromptTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(PromptTemplateController.class);

    private final PromptTemplateService templateService;

    @Autowired
    public PromptTemplateController(PromptTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * Lists all templates.
     * GET /api/prompts
     */
    @GetMapping
    public ResponseEntity<List<PromptTemplate>> getAllTemplates(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false, defaultValue = "false") boolean enabledOnly) {

        List<PromptTemplate> templates;

        if (query != null && !query.isEmpty()) {
            templates = templateService.searchTemplates(query);
        } else if (category != null && !category.isEmpty()) {
            templates = templateService.getTemplatesByCategory(category);
        } else if (tag != null && !tag.isEmpty()) {
            templates = templateService.getTemplatesByTag(tag);
        } else if (enabledOnly) {
            templates = templateService.getEnabledTemplates();
        } else {
            templates = templateService.getAllTemplates();
        }

        return ResponseEntity.ok(templates);
    }

    /**
     * Gets a specific template by name.
     * GET /api/prompts/{name}
     */
    @GetMapping("/{name}")
    public ResponseEntity<PromptTemplate> getTemplateByName(@PathVariable String name) {
        return templateService.getTemplateByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Creates a new template.
     * POST /api/prompts
     */
    @PostMapping
    public ResponseEntity<?> createTemplate(@RequestBody PromptTemplate template) {
        try {
            PromptTemplate created = templateService.createTemplate(template);
            logger.info("Created prompt template: {}", created.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to create template: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create template: " + e.getMessage()));
        }
    }

    /**
     * Updates an existing template.
     * PUT /api/prompts/{name}
     */
    @PutMapping("/{name}")
    public ResponseEntity<?> updateTemplate(@PathVariable String name,
                                            @RequestBody PromptTemplate updates) {
        try {
            PromptTemplate updated = templateService.updateTemplate(name, updates);
            logger.info("Updated prompt template: {}", name);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to update template {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update template: " + e.getMessage()));
        }
    }

    /**
     * Deletes a template.
     * DELETE /api/prompts/{name}
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteTemplate(@PathVariable String name) {
        try {
            boolean deleted = templateService.deleteTemplate(name);
            if (deleted) {
                logger.info("Deleted prompt template: {}", name);
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to delete template {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete template: " + e.getMessage()));
        }
    }

    /**
     * Enables or disables a template.
     * PATCH /api/prompts/{name}/enabled
     */
    @PatchMapping("/{name}/enabled")
    public ResponseEntity<?> setTemplateEnabled(@PathVariable String name,
                                                @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'enabled' field"));
        }

        try {
            PromptTemplate updated = templateService.setTemplateEnabled(name, enabled);
            logger.info("{} prompt template: {}", enabled ? "Enabled" : "Disabled", name);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to set enabled state for template {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update template: " + e.getMessage()));
        }
    }

    /**
     * Renders a template with provided variables.
     * POST /api/prompts/{name}/render
     */
    @PostMapping("/{name}/render")
    public ResponseEntity<?> renderTemplate(@PathVariable String name,
                                            @RequestBody Map<String, Object> variables) {
        try {
            String rendered = templateService.renderTemplate(name, variables);
            return ResponseEntity.ok(Map.of(
                    "templateName", name,
                    "rendered", rendered
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to render template {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to render template: " + e.getMessage()));
        }
    }

    /**
     * Renders a template with full context including system prompt.
     * POST /api/prompts/{name}/render-full
     */
    @PostMapping("/{name}/render-full")
    public ResponseEntity<?> renderTemplateFull(@PathVariable String name,
                                                @RequestBody Map<String, Object> variables) {
        try {
            PromptTemplateService.RenderedTemplate rendered = templateService.renderTemplateWithSystemPrompt(name, variables);
            return ResponseEntity.ok(rendered);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to render template {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to render template: " + e.getMessage()));
        }
    }

    /**
     * Duplicates a template.
     * POST /api/prompts/{name}/duplicate
     */
    @PostMapping("/{name}/duplicate")
    public ResponseEntity<?> duplicateTemplate(@PathVariable String name,
                                               @RequestBody Map<String, String> body) {
        String newName = body.get("newName");
        if (newName == null || newName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'newName' field"));
        }

        try {
            PromptTemplate copy = templateService.duplicateTemplate(name, newName);
            logger.info("Duplicated template '{}' to '{}'", name, newName);
            return ResponseEntity.status(HttpStatus.CREATED).body(copy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to duplicate template {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to duplicate template: " + e.getMessage()));
        }
    }

    /**
     * Gets available template categories.
     * GET /api/prompts/meta/categories
     */
    @GetMapping("/meta/categories")
    public ResponseEntity<Map<String, PromptTemplate.CategoryInfo>> getCategories() {
        return ResponseEntity.ok(templateService.getCategories());
    }

    /**
     * Gets templates grouped by category.
     * GET /api/prompts/meta/grouped
     */
    @GetMapping("/meta/grouped")
    public ResponseEntity<Map<String, List<PromptTemplate>>> getTemplatesGroupedByCategory() {
        return ResponseEntity.ok(templateService.getTemplatesByCategories());
    }

    /**
     * Gets a summary of all templates.
     * GET /api/prompts/meta/summary
     */
    @GetMapping("/meta/summary")
    public ResponseEntity<PromptTemplateService.TemplateSummary> getSummary() {
        return ResponseEntity.ok(templateService.getSummary());
    }

    /**
     * Refreshes templates from disk.
     * POST /api/prompts/meta/refresh
     */
    @PostMapping("/meta/refresh")
    public ResponseEntity<?> refreshTemplates() {
        try {
            templateService.refresh();
            logger.info("Refreshed prompt templates");
            return ResponseEntity.ok(Map.of(
                    "message", "Templates refreshed successfully",
                    "templateCount", templateService.getAllTemplates().size()
            ));
        } catch (Exception e) {
            logger.error("Failed to refresh templates: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to refresh templates: " + e.getMessage()));
        }
    }

    /**
     * Extracts variable names from a template.
     * GET /api/prompts/{name}/variables
     */
    @GetMapping("/{name}/variables")
    public ResponseEntity<?> getTemplateVariables(@PathVariable String name) {
        return templateService.getTemplateByName(name)
                .map(template -> ResponseEntity.ok(Map.of(
                        "defined", template.getVariables(),
                        "extracted", template.extractVariableNames()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Adds an example to a template.
     * POST /api/prompts/{name}/examples
     */
    @PostMapping("/{name}/examples")
    public ResponseEntity<?> addExample(@PathVariable String name,
                                        @RequestBody PromptTemplate.TemplateExample example) {
        return templateService.getTemplateByName(name)
                .map(template -> {
                    if (template.getExamples() == null) {
                        template.setExamples(new java.util.ArrayList<>());
                    }
                    template.getExamples().add(example);
                    PromptTemplate updated = templateService.updateTemplate(name, template);
                    logger.info("Added example to template: {}", name);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Previews a template without saving.
     * POST /api/prompts/preview
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewTemplate(@RequestBody PreviewRequest request) {
        try {
            // Create a temporary template for preview
            PromptTemplate temp = PromptTemplate.builder()
                    .content(request.content())
                    .systemPrompt(request.systemPrompt())
                    .build();

            String rendered = temp.render(request.variables() != null ? request.variables() : Map.of());
            List<String> extractedVars = temp.extractVariableNames();

            return ResponseEntity.ok(Map.of(
                    "rendered", rendered,
                    "extractedVariables", extractedVars
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Request for previewing a template.
     */
    public record PreviewRequest(
            String content,
            String systemPrompt,
            Map<String, Object> variables
    ) {}
}
