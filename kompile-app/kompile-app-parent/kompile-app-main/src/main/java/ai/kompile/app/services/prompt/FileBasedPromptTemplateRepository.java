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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.prompt.PromptTemplate;
import ai.kompile.core.prompt.PromptTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-based implementation of PromptTemplateRepository.
 * Stores prompt templates as JSON files in a configurable directory.
 */
@Component
public class FileBasedPromptTemplateRepository implements PromptTemplateRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileBasedPromptTemplateRepository.class);

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;

    // In-memory cache for fast lookups
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public FileBasedPromptTemplateRepository(
            @Value("${kompile.prompts.templates.directory:./data/prompt-templates}") String directory) {
        this.storageDirectory = Path.of(directory);
        this.objectMapper = JsonUtils.newStandardMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void initialize() {
        try {
            // Create storage directory if it doesn't exist
            Files.createDirectories(storageDirectory);
            logger.info("Prompt template storage directory: {}", storageDirectory.toAbsolutePath());

            // Load all existing templates
            loadAllTemplates();
            logger.info("Loaded {} prompt templates from disk", cache.size());

            // Create default templates if none exist
            if (cache.isEmpty()) {
                createDefaultTemplates();
            }
        } catch (IOException e) {
            logger.error("Failed to initialize prompt template repository: {}", e.getMessage(), e);
        }
    }

    private void loadAllTemplates() {
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            paths.filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::loadTemplate);
        } catch (IOException e) {
            logger.error("Failed to load prompt templates: {}", e.getMessage());
        }
    }

    private void loadTemplate(Path path) {
        try {
            PromptTemplate template = objectMapper.readValue(path.toFile(), PromptTemplate.class);
            if (template.getName() != null) {
                cache.put(template.getName(), template);
                logger.debug("Loaded prompt template: {}", template.getName());
            }
        } catch (IOException e) {
            logger.error("Failed to load prompt template from {}: {}", path, e.getMessage());
        }
    }

    private void createDefaultTemplates() {
        logger.info("Creating default prompt templates");

        // RAG Query Template
        save(PromptTemplate.builder()
                .name("rag_query")
                .displayName("RAG Query")
                .description("Standard template for RAG-based document Q&A")
                .category("rag")
                .content("""
                        Based on the following context, answer the question.

                        Context:
                        {{context}}

                        Question: {{question}}

                        Provide a clear and concise answer based only on the information in the context. If the answer cannot be found in the context, say so.
                        """)
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("context")
                                .displayName("Context")
                                .description("The retrieved document context")
                                .type("string")
                                .required(true)
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("question")
                                .displayName("Question")
                                .description("The user's question")
                                .type("string")
                                .required(true)
                                .build()
                ))
                .tags(List.of("rag", "qa", "retrieval"))
                .builtIn(true)
                .build());

        // Summarization Template
        save(PromptTemplate.builder()
                .name("summarize_text")
                .displayName("Text Summarizer")
                .description("Summarize text content to a specified length")
                .category("summarization")
                .content("""
                        Summarize the following text in {{length}} sentences.

                        Text:
                        {{text}}

                        Summary:
                        """)
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("text")
                                .displayName("Text")
                                .description("The text to summarize")
                                .type("string")
                                .required(true)
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("length")
                                .displayName("Length")
                                .description("Number of sentences for the summary")
                                .type("string")
                                .defaultValue("3")
                                .build()
                ))
                .tags(List.of("summary", "condense"))
                .builtIn(true)
                .build());

        // Code Review Template
        save(PromptTemplate.builder()
                .name("code_review")
                .displayName("Code Review")
                .description("Review code for quality, bugs, and improvements")
                .category("code")
                .content("""
                        Review the following {{language}} code for:
                        - Bugs and potential issues
                        - Code quality and best practices
                        - Performance considerations
                        - Security concerns

                        Code:
                        ```{{language}}
                        {{code}}
                        ```

                        Provide your review with specific suggestions for improvement.
                        """)
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("code")
                                .displayName("Code")
                                .description("The code to review")
                                .type("string")
                                .required(true)
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("language")
                                .displayName("Language")
                                .description("Programming language")
                                .type("string")
                                .defaultValue("java")
                                .allowedValues(List.of("java", "python", "javascript", "typescript", "go", "rust"))
                                .build()
                ))
                .tags(List.of("code", "review", "quality"))
                .builtIn(true)
                .build());

        // Data Extraction Template
        save(PromptTemplate.builder()
                .name("extract_entities")
                .displayName("Entity Extractor")
                .description("Extract structured entities from unstructured text")
                .category("extraction")
                .content("""
                        Extract the following types of entities from the text:
                        {{entity_types}}

                        Text:
                        {{text}}

                        Return the extracted entities as a JSON object with entity types as keys and arrays of found entities as values.
                        """)
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("text")
                                .displayName("Text")
                                .description("The text to extract entities from")
                                .type("string")
                                .required(true)
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("entity_types")
                                .displayName("Entity Types")
                                .description("Types of entities to extract (comma-separated)")
                                .type("string")
                                .defaultValue("names, dates, locations, organizations")
                                .build()
                ))
                .tags(List.of("extraction", "ner", "entities"))
                .outputFormat("json")
                .builtIn(true)
                .build());

        // Classification Template
        save(PromptTemplate.builder()
                .name("classify_text")
                .displayName("Text Classifier")
                .description("Classify text into predefined categories")
                .category("classification")
                .content("""
                        Classify the following text into one of these categories:
                        {{categories}}

                        Text:
                        {{text}}

                        Return only the category name that best matches the text.
                        """)
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("text")
                                .displayName("Text")
                                .description("The text to classify")
                                .type("string")
                                .required(true)
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("categories")
                                .displayName("Categories")
                                .description("Available categories (comma-separated)")
                                .type("string")
                                .required(true)
                                .exampleValue("positive, negative, neutral")
                                .build()
                ))
                .tags(List.of("classification", "categorization"))
                .builtIn(true)
                .build());

        // System Prompt Template
        save(PromptTemplate.builder()
                .name("agent_system_prompt")
                .displayName("Agent System Prompt")
                .description("System prompt for configuring an AI agent")
                .category("system")
                .systemPrompt("""
                        You are {{agent_name}}, an AI assistant with the following characteristics:

                        Role: {{role}}

                        Expertise: {{expertise}}

                        Guidelines:
                        {{guidelines}}

                        Always be helpful, accurate, and professional in your responses.
                        """)
                .content("{{user_message}}")
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("agent_name")
                                .displayName("Agent Name")
                                .description("Name of the AI agent")
                                .type("string")
                                .defaultValue("Assistant")
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("role")
                                .displayName("Role")
                                .description("The agent's role or purpose")
                                .type("string")
                                .defaultValue("A helpful AI assistant")
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("expertise")
                                .displayName("Expertise")
                                .description("Areas of expertise")
                                .type("string")
                                .defaultValue("General knowledge and problem-solving")
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("guidelines")
                                .displayName("Guidelines")
                                .description("Behavioral guidelines")
                                .type("string")
                                .defaultValue("- Be concise and clear\\n- Provide accurate information\\n- Ask for clarification when needed")
                                .build(),
                        PromptTemplate.TemplateVariable.builder()
                                .name("user_message")
                                .displayName("User Message")
                                .description("The user's message")
                                .type("string")
                                .required(true)
                                .build()
                ))
                .tags(List.of("system", "agent", "persona"))
                .builtIn(true)
                .build());

        logger.info("Created {} default prompt templates", 6);
    }

    @Override
    public PromptTemplate save(PromptTemplate template) {
        // Generate ID if not set
        if (template.getId() == null || template.getId().isEmpty()) {
            template.setId(UUID.randomUUID().toString());
        }

        // Set timestamps
        if (template.getCreatedAt() == null) {
            template.setCreatedAt(Instant.now());
        }
        template.setUpdatedAt(Instant.now());

        // Validate name
        if (template.getName() == null || template.getName().isEmpty()) {
            throw new IllegalArgumentException("Template name is required");
        }

        // Write to file
        Path filePath = getFilePath(template.getName());
        try {
            objectMapper.writeValue(filePath.toFile(), template);
            cache.put(template.getName(), template);
            logger.debug("Saved prompt template: {}", template.getName());
            return template;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save prompt template: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<PromptTemplate> findById(String id) {
        return cache.values().stream()
                .filter(t -> id.equals(t.getId()))
                .findFirst();
    }

    @Override
    public Optional<PromptTemplate> findByName(String name) {
        return Optional.ofNullable(cache.get(name));
    }

    @Override
    public List<PromptTemplate> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public List<PromptTemplate> findByCategory(String category) {
        return cache.values().stream()
                .filter(t -> category.equalsIgnoreCase(t.getCategory()))
                .collect(Collectors.toList());
    }

    @Override
    public List<PromptTemplate> search(String query) {
        if (query == null || query.isEmpty()) {
            return findAll();
        }

        String lowerQuery = query.toLowerCase();
        return cache.values().stream()
                .filter(t -> matchesSearch(t, lowerQuery))
                .collect(Collectors.toList());
    }

    private boolean matchesSearch(PromptTemplate template, String query) {
        if (template.getName() != null && template.getName().toLowerCase().contains(query)) return true;
        if (template.getDisplayName() != null && template.getDisplayName().toLowerCase().contains(query)) return true;
        if (template.getDescription() != null && template.getDescription().toLowerCase().contains(query)) return true;
        if (template.getCategory() != null && template.getCategory().toLowerCase().contains(query)) return true;
        if (template.getTags() != null) {
            for (String tag : template.getTags()) {
                if (tag.toLowerCase().contains(query)) return true;
            }
        }
        return false;
    }

    @Override
    public List<PromptTemplate> findByTag(String tag) {
        return cache.values().stream()
                .filter(t -> t.getTags() != null && t.getTags().stream()
                        .anyMatch(tt -> tt.equalsIgnoreCase(tag)))
                .collect(Collectors.toList());
    }

    @Override
    public List<PromptTemplate> findEnabled() {
        return cache.values().stream()
                .filter(PromptTemplate::isEnabled)
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteById(String id) {
        Optional<PromptTemplate> template = findById(id);
        return template.map(t -> deleteByName(t.getName())).orElse(false);
    }

    @Override
    public boolean deleteByName(String name) {
        PromptTemplate template = cache.get(name);
        if (template == null) {
            return false;
        }

        // Don't allow deleting built-in templates
        if (template.isBuiltIn()) {
            throw new IllegalArgumentException("Cannot delete built-in template");
        }

        Path filePath = getFilePath(name);
        try {
            Files.deleteIfExists(filePath);
            cache.remove(name);
            logger.debug("Deleted prompt template: {}", name);
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete prompt template {}: {}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean existsByName(String name) {
        return cache.containsKey(name);
    }

    @Override
    public long count() {
        return cache.size();
    }

    @Override
    public void refresh() {
        cache.clear();
        loadAllTemplates();
        logger.info("Refreshed prompt templates, loaded {} templates", cache.size());
    }

    private Path getFilePath(String name) {
        // Sanitize filename
        String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        return storageDirectory.resolve(safeName + ".json");
    }
}
