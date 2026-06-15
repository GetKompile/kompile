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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A named, reusable prompt template for graph extraction.
 *
 * <p>Templates are stored in {@code ~/.kompile/config/prompts/*.json} and referenced
 * by ID from {@link ai.kompile.core.crawler.pipeline.IngestPipelineDefinition#getExtractionPromptTemplate()}.
 *
 * <h3>Supported variables:</h3>
 * <ul>
 *   <li>{@code {{TEXT}}} — The chunk text to extract from</li>
 *   <li>{@code {{ENTITY_TYPES}}} — Comma-separated list of entity types</li>
 *   <li>{@code {{RELATIONSHIP_TYPES}}} — Comma-separated list of relationship types</li>
 *   <li>{@code {{SCHEMA_DESCRIPTION}}} — Schema description from GraphSchema (if available)</li>
 *   <li>{@code {{OUTPUT_FORMAT}}} — The standard JSON output format instructions</li>
 *   <li>{@code {{DOMAIN_CONTEXT}}} — Domain-specific context from augmentations</li>
 * </ul>
 *
 * <h3>Example template JSON:</h3>
 * <pre>{@code
 * {
 *   "id": "financial-extraction",
 *   "name": "Financial Document Extraction",
 *   "description": "Extracts financial entities and relationships from SEC filings and reports",
 *   "template": "Extract entities from the following financial document.\n\nEntity types: {{ENTITY_TYPES}}\nRelationship types: {{RELATIONSHIP_TYPES}}\n\n{{DOMAIN_CONTEXT}}\n\n{{OUTPUT_FORMAT}}\n\nDocument text:\n\"\"\"\n{{TEXT}}\n\"\"\"",
 *   "defaultEntityTypes": "COMPANY,FINANCIAL_METRIC,REGULATORY_BODY,PERSON,DATE",
 *   "defaultRelationshipTypes": "REPORTS_METRIC,REGULATED_BY,HAS_OFFICER,FILED_ON"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptTemplate {

    /** Unique identifier for this template */
    private String id;

    /** Human-readable name */
    private String name;

    /** Description of what this template is designed for */
    private String description;

    /**
     * The template string with variable placeholders.
     * Variables use double-brace syntax: {@code {{VARIABLE_NAME}}}.
     */
    private String template;

    /** Default entity types for this template (comma-separated). Used when no override is provided. */
    private String defaultEntityTypes;

    /** Default relationship types for this template (comma-separated). Used when no override is provided. */
    private String defaultRelationshipTypes;

    /**
     * Render this template by replacing variable placeholders with the given values.
     *
     * @param variables map of variable names (without braces) to their values
     * @return the rendered prompt string
     */
    public String render(Map<String, String> variables) {
        if (template == null) return "";
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                result = result.replace(placeholder, entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
    }
}
