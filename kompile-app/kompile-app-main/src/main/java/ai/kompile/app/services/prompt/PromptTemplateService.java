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

package ai.kompile.app.services.prompt;

import ai.kompile.core.prompt.PromptTemplate;
import ai.kompile.core.prompt.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing prompt templates.
 * Provides CRUD operations, rendering, and template discovery.
 */
@Service
public class PromptTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(PromptTemplateService.class);

    private final PromptTemplateRepository repository;

    @Autowired
    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * Gets all templates.
     */
    public List<PromptTemplate> getAllTemplates() {
        return repository.findAll();
    }

    /**
     * Gets enabled templates only.
     */
    public List<PromptTemplate> getEnabledTemplates() {
        return repository.findEnabled();
    }

    /**
     * Gets a template by name.
     */
    public Optional<PromptTemplate> getTemplateByName(String name) {
        return repository.findByName(name);
    }

    /**
     * Gets a template by ID.
     */
    public Optional<PromptTemplate> getTemplateById(String id) {
        return repository.findById(id);
    }

    /**
     * Gets templates by category.
     */
    public List<PromptTemplate> getTemplatesByCategory(String category) {
        return repository.findByCategory(category);
    }

    /**
     * Searches templates by query.
     */
    public List<PromptTemplate> searchTemplates(String query) {
        return repository.search(query);
    }

    /**
     * Gets templates by tag.
     */
    public List<PromptTemplate> getTemplatesByTag(String tag) {
        return repository.findByTag(tag);
    }

    /**
     * Creates a new template.
     */
    public PromptTemplate createTemplate(PromptTemplate template) {
        // Validate name uniqueness
        if (repository.existsByName(template.getName())) {
            throw new IllegalArgumentException("Template with name '" + template.getName() + "' already exists");
        }

        // Validate required fields
        validateTemplate(template);

        // Set defaults
        template.setId(UUID.randomUUID().toString());
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(Instant.now());
        template.setBuiltIn(false);

        if (template.getCategory() == null) {
            template.setCategory("custom");
        }

        PromptTemplate saved = repository.save(template);
        logger.info("Created prompt template: {}", saved.getName());
        return saved;
    }

    /**
     * Updates an existing template.
     */
    public PromptTemplate updateTemplate(String name, PromptTemplate updates) {
        PromptTemplate existing = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + name));

        // Don't allow modifying built-in template content, but allow metadata changes
        if (existing.isBuiltIn()) {
            // Only update allowed fields for built-in templates
            existing.setEnabled(updates.isEnabled());
            if (updates.getExamples() != null) {
                existing.setExamples(updates.getExamples());
            }
        } else {
            // Update all fields for custom templates
            if (updates.getDisplayName() != null) {
                existing.setDisplayName(updates.getDisplayName());
            }
            if (updates.getDescription() != null) {
                existing.setDescription(updates.getDescription());
            }
            if (updates.getCategory() != null) {
                existing.setCategory(updates.getCategory());
            }
            if (updates.getContent() != null) {
                existing.setContent(updates.getContent());
            }
            if (updates.getSystemPrompt() != null) {
                existing.setSystemPrompt(updates.getSystemPrompt());
            }
            if (updates.getVariables() != null) {
                existing.setVariables(updates.getVariables());
            }
            if (updates.getExamples() != null) {
                existing.setExamples(updates.getExamples());
            }
            if (updates.getTags() != null) {
                existing.setTags(updates.getTags());
            }
            existing.setEnabled(updates.isEnabled());
            if (updates.getOutputFormat() != null) {
                existing.setOutputFormat(updates.getOutputFormat());
            }
            if (updates.getRecommendedModel() != null) {
                existing.setRecommendedModel(updates.getRecommendedModel());
            }
            if (updates.getMaxTokens() != null) {
                existing.setMaxTokens(updates.getMaxTokens());
            }
            if (updates.getTemperature() != null) {
                existing.setTemperature(updates.getTemperature());
            }
        }

        existing.setUpdatedAt(Instant.now());
        PromptTemplate saved = repository.save(existing);
        logger.info("Updated prompt template: {}", name);
        return saved;
    }

    /**
     * Deletes a template by name.
     */
    public boolean deleteTemplate(String name) {
        PromptTemplate template = repository.findByName(name).orElse(null);
        if (template == null) {
            return false;
        }

        if (template.isBuiltIn()) {
            throw new IllegalArgumentException("Cannot delete built-in template. Disable it instead.");
        }

        boolean deleted = repository.deleteByName(name);
        if (deleted) {
            logger.info("Deleted prompt template: {}", name);
        }
        return deleted;
    }

    /**
     * Enables or disables a template.
     */
    public PromptTemplate setTemplateEnabled(String name, boolean enabled) {
        PromptTemplate template = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + name));

        template.setEnabled(enabled);
        template.setUpdatedAt(Instant.now());
        PromptTemplate saved = repository.save(template);
        logger.info("{} prompt template: {}", enabled ? "Enabled" : "Disabled", name);
        return saved;
    }

    /**
     * Renders a template with the provided variables.
     */
    public String renderTemplate(String name, Map<String, Object> variables) {
        PromptTemplate template = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + name));

        // Validate required variables
        List<String> missing = template.validateVariables(variables);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required variables: " + String.join(", ", missing));
        }

        return template.render(variables);
    }

    /**
     * Renders a template and returns both the rendered content and system prompt.
     */
    public RenderedTemplate renderTemplateWithSystemPrompt(String name, Map<String, Object> variables) {
        PromptTemplate template = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + name));

        // Validate required variables
        List<String> missing = template.validateVariables(variables);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required variables: " + String.join(", ", missing));
        }

        String content = template.render(variables);
        String systemPrompt = template.getSystemPrompt() != null ?
                renderSystemPrompt(template.getSystemPrompt(), variables) : null;

        return new RenderedTemplate(
                template.getName(),
                content,
                systemPrompt,
                template.getOutputFormat(),
                template.getRecommendedModel(),
                template.getMaxTokens(),
                template.getTemperature()
        );
    }

    private String renderSystemPrompt(String systemPrompt, Map<String, Object> variables) {
        // Create a temporary template to render the system prompt
        PromptTemplate temp = PromptTemplate.builder()
                .content(systemPrompt)
                .build();
        return temp.render(variables);
    }

    /**
     * Gets available template categories.
     */
    public Map<String, PromptTemplate.CategoryInfo> getCategories() {
        return PromptTemplate.CATEGORIES;
    }

    /**
     * Gets templates grouped by category.
     */
    public Map<String, List<PromptTemplate>> getTemplatesByCategories() {
        return repository.findAll().stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "custom"
                ));
    }

    /**
     * Gets a summary of all templates.
     */
    public TemplateSummary getSummary() {
        List<PromptTemplate> all = repository.findAll();
        List<PromptTemplate> enabled = repository.findEnabled();
        Map<String, List<PromptTemplate>> byCategory = getTemplatesByCategories();

        long builtInCount = all.stream().filter(PromptTemplate::isBuiltIn).count();
        long customCount = all.stream().filter(t -> !t.isBuiltIn()).count();

        return new TemplateSummary(
                all.size(),
                enabled.size(),
                builtInCount,
                customCount,
                byCategory.size(),
                new ArrayList<>(byCategory.keySet())
        );
    }

    /**
     * Refreshes templates from disk.
     */
    public void refresh() {
        repository.refresh();
        logger.info("Refreshed prompt templates");
    }

    /**
     * Duplicates a template with a new name.
     */
    public PromptTemplate duplicateTemplate(String sourceName, String newName) {
        PromptTemplate source = repository.findByName(sourceName)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + sourceName));

        if (repository.existsByName(newName)) {
            throw new IllegalArgumentException("Template with name '" + newName + "' already exists");
        }

        PromptTemplate copy = PromptTemplate.builder()
                .id(UUID.randomUUID().toString())
                .name(newName)
                .displayName(source.getDisplayName() + " (Copy)")
                .description(source.getDescription())
                .category(source.getCategory())
                .content(source.getContent())
                .systemPrompt(source.getSystemPrompt())
                .variables(source.getVariables() != null ? new ArrayList<>(source.getVariables()) : null)
                .examples(source.getExamples() != null ? new ArrayList<>(source.getExamples()) : null)
                .tags(source.getTags() != null ? new ArrayList<>(source.getTags()) : null)
                .enabled(true)
                .builtIn(false)
                .outputFormat(source.getOutputFormat())
                .recommendedModel(source.getRecommendedModel())
                .maxTokens(source.getMaxTokens())
                .temperature(source.getTemperature())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        PromptTemplate saved = repository.save(copy);
        logger.info("Duplicated template '{}' to '{}'", sourceName, newName);
        return saved;
    }

    private void validateTemplate(PromptTemplate template) {
        if (template.getName() == null || template.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Template name is required");
        }

        if (template.getContent() == null || template.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Template content is required");
        }

        // Validate name format (alphanumeric, underscores, hyphens only)
        if (!template.getName().matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
            throw new IllegalArgumentException("Template name must start with a letter and contain only letters, numbers, underscores, and hyphens");
        }
    }

    /**
     * Rendered template with all context.
     */
    public record RenderedTemplate(
            String templateName,
            String content,
            String systemPrompt,
            String outputFormat,
            String recommendedModel,
            Integer maxTokens,
            Double temperature
    ) {}

    /**
     * Summary of templates.
     */
    public record TemplateSummary(
            long totalTemplates,
            long enabledTemplates,
            long builtInTemplates,
            long customTemplates,
            int categoryCount,
            List<String> categories
    ) {}
}
