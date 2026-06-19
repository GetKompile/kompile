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

package ai.kompile.core.graphrag.prompts;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing named prompt templates used during graph extraction.
 *
 * <p>Templates are loaded from {@code ~/.kompile/config/prompts/*.json} at startup
 * and can be referenced by ID from pipeline definitions. New templates can be
 * registered at runtime.</p>
 *
 * <p>A template can be either a named template ID (resolved from the registry)
 * or an inline prompt string (used directly). The {@link #resolve} method
 * handles both cases transparently.</p>
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final Path promptsDir;

    public PromptTemplateService(
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir) {
        this.promptsDir = Paths.get(dataDir, "config", "prompts");
    }

    @PostConstruct
    public void init() {
        loadTemplatesFromDisk();
        registerBuiltInTemplates();
    }

    /**
     * Resolve a prompt template reference to a rendered prompt string.
     *
     * <p>If {@code templateRef} matches a registered template ID, the template is
     * rendered with the given variables. Otherwise, {@code templateRef} is treated
     * as an inline prompt string and variable substitution is applied directly.</p>
     *
     * @param templateRef template ID or inline prompt string
     * @param variables variable values for substitution (TEXT, ENTITY_TYPES, etc.)
     * @return the rendered prompt string
     */
    public String resolve(String templateRef, Map<String, String> variables) {
        if (templateRef == null || templateRef.isBlank()) {
            return buildDefaultPrompt(variables);
        }

        // Check if it's a named template
        PromptTemplate template = templates.get(templateRef);
        if (template != null) {
            // Fill in default entity/relationship types if not overridden
            Map<String, String> enriched = new HashMap<>(variables);
            if (!enriched.containsKey("ENTITY_TYPES") || enriched.get("ENTITY_TYPES").isBlank()) {
                if (template.getDefaultEntityTypes() != null) {
                    enriched.put("ENTITY_TYPES", template.getDefaultEntityTypes());
                }
            }
            if (!enriched.containsKey("RELATIONSHIP_TYPES") || enriched.get("RELATIONSHIP_TYPES").isBlank()) {
                if (template.getDefaultRelationshipTypes() != null) {
                    enriched.put("RELATIONSHIP_TYPES", template.getDefaultRelationshipTypes());
                }
            }
            enriched.putIfAbsent("OUTPUT_FORMAT", GraphExtractionValidator.getExtractionPromptInstructions());
            return template.render(enriched);
        }

        // Treat as inline prompt — apply variable substitution
        return substituteVariables(templateRef, variables);
    }

    /**
     * Register a template at runtime.
     */
    public void register(PromptTemplate template) {
        templates.put(template.getId(), template);
        log.info("Registered prompt template: {} ({})", template.getId(), template.getName());
    }

    /**
     * Get a template by ID.
     */
    public Optional<PromptTemplate> get(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    /**
     * List all registered template IDs and names.
     */
    public List<PromptTemplate> listTemplates() {
        return List.copyOf(templates.values());
    }

    /**
     * Save a template to disk and register it.
     */
    public void save(PromptTemplate template) throws IOException {
        Files.createDirectories(promptsDir);
        Path file = promptsDir.resolve(template.getId() + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), template);
        register(template);
        log.info("Saved prompt template to {}", file);
    }

    /**
     * Delete a template from disk and unregister it.
     */
    public boolean delete(String id) {
        PromptTemplate removed = templates.remove(id);
        if (removed != null) {
            Path file = promptsDir.resolve(id + ".json");
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Failed to delete template file {}: {}", file, e.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * Build the default extraction prompt using the standard format.
     */
    public String buildDefaultPrompt(Map<String, String> variables) {
        String entityTypes = variables.getOrDefault("ENTITY_TYPES",
                "PERSON, ORGANIZATION, LOCATION, CONCEPT, EVENT");
        String relationshipTypes = variables.getOrDefault("RELATIONSHIP_TYPES", "");
        String text = variables.getOrDefault("TEXT", "");
        String domainContext = variables.getOrDefault("DOMAIN_CONTEXT", "");
        String schemaDescription = variables.getOrDefault("SCHEMA_DESCRIPTION", "");

        StringBuilder sb = new StringBuilder();
        sb.append("Extract entities and their relationships from the following text.\n\n");
        sb.append("Entity types to look for: ").append(entityTypes).append("\n");

        if (!relationshipTypes.isBlank()) {
            sb.append("Relationship types to look for: ").append(relationshipTypes).append("\n");
        }

        if (!schemaDescription.isBlank()) {
            sb.append("\n").append(schemaDescription).append("\n");
        }

        if (!domainContext.isBlank()) {
            sb.append("\n").append(domainContext).append("\n");
        }

        sb.append("\n").append(GraphExtractionValidator.getExtractionPromptInstructions());
        sb.append("\n\nText to analyze:\n\"\"\"\n").append(text).append("\n\"\"\"");

        return sb.toString();
    }

    // ---- Internal ----

    private void loadTemplatesFromDisk() {
        if (!Files.isDirectory(promptsDir)) {
            log.debug("Prompts directory does not exist: {}", promptsDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(promptsDir, "*.json")) {
            for (Path file : stream) {
                try {
                    PromptTemplate template = objectMapper.readValue(file.toFile(), PromptTemplate.class);
                    if (template.getId() != null) {
                        templates.put(template.getId(), template);
                        log.info("Loaded prompt template from {}: {}", file.getFileName(), template.getId());
                    }
                } catch (IOException e) {
                    log.warn("Failed to load prompt template from {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan prompts directory {}: {}", promptsDir, e.getMessage());
        }
    }

    private void registerBuiltInTemplates() {
        // Default extraction template
        register(PromptTemplate.builder()
                .id("default")
                .name("Default Extraction")
                .description("Standard entity and relationship extraction")
                .template("""
                        Extract entities and their relationships from the following text.

                        Entity types to look for: {{ENTITY_TYPES}}
                        {{RELATIONSHIP_TYPES}}
                        {{SCHEMA_DESCRIPTION}}
                        {{DOMAIN_CONTEXT}}

                        {{OUTPUT_FORMAT}}

                        Text to analyze:
                        \"\"\"
                        {{TEXT}}
                        \"\"\"""")
                .defaultEntityTypes("PERSON, ORGANIZATION, LOCATION, CONCEPT, EVENT")
                .build());

        // Code-focused extraction template
        register(PromptTemplate.builder()
                .id("code")
                .name("Code Extraction")
                .description("Extract software entities and relationships from source code")
                .template("""
                        Extract software entities and their relationships from the following code or technical documentation.

                        Entity types to look for: {{ENTITY_TYPES}}
                        Relationship types to look for: {{RELATIONSHIP_TYPES}}

                        Focus on:
                        - Module/package structure and dependencies
                        - Class/interface hierarchies and implementations
                        - Function signatures and call relationships
                        - Configuration and deployment relationships
                        - API endpoints and their handlers

                        {{OUTPUT_FORMAT}}

                        Code/documentation to analyze:
                        \"\"\"
                        {{TEXT}}
                        \"\"\"""")
                .defaultEntityTypes("CLASS, INTERFACE, FUNCTION, MODULE, PACKAGE, API_ENDPOINT, CONFIG_PROPERTY")
                .defaultRelationshipTypes("IMPORTS, EXTENDS, IMPLEMENTS, CALLS, DEPENDS_ON, CONFIGURES, HANDLES")
                .build());

        // Financial extraction template
        register(PromptTemplate.builder()
                .id("financial")
                .name("Financial Document Extraction")
                .description("Extract financial entities and relationships from reports and filings")
                .template("""
                        Extract financial entities and relationships from the following document.

                        Entity types to look for: {{ENTITY_TYPES}}
                        Relationship types to look for: {{RELATIONSHIP_TYPES}}
                        {{DOMAIN_CONTEXT}}

                        Pay special attention to:
                        - Company names, tickers, and organizational structure
                        - Financial metrics with their values and time periods
                        - Regulatory bodies and compliance references
                        - Key personnel and their roles

                        {{OUTPUT_FORMAT}}

                        Document text:
                        \"\"\"
                        {{TEXT}}
                        \"\"\"""")
                .defaultEntityTypes("COMPANY, FINANCIAL_METRIC, REGULATORY_BODY, PERSON, DATE, PRODUCT, MARKET")
                .defaultRelationshipTypes("REPORTS_METRIC, REGULATED_BY, HAS_OFFICER, SUBSIDIARY_OF, OPERATES_IN, FILED_ON")
                .build());

        // Legal/compliance extraction template
        register(PromptTemplate.builder()
                .id("legal")
                .name("Legal Document Extraction")
                .description("Extract legal entities and relationships from contracts and regulations")
                .template("""
                        Extract legal entities and relationships from the following document.

                        Entity types to look for: {{ENTITY_TYPES}}
                        Relationship types to look for: {{RELATIONSHIP_TYPES}}
                        {{DOMAIN_CONTEXT}}

                        Pay special attention to:
                        - Parties to agreements and their obligations
                        - Legal terms, clauses, and conditions
                        - Dates, deadlines, and time periods
                        - References to laws, regulations, and standards

                        {{OUTPUT_FORMAT}}

                        Document text:
                        \"\"\"
                        {{TEXT}}
                        \"\"\"""")
                .defaultEntityTypes("PARTY, CLAUSE, OBLIGATION, RIGHT, TERM, REGULATION, JURISDICTION, DATE")
                .defaultRelationshipTypes("PARTY_TO, OBLIGATED_BY, GRANTS_RIGHT, REFERENCES, GOVERNED_BY, EFFECTIVE_ON, AMENDS")
                .build());
    }

    private String substituteVariables(String text, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            // Support both {{VAR}} and {var} placeholder styles
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
            result = result.replace("{" + entry.getKey().toLowerCase() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
