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

import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.crawler.pipeline.PromptAugmentation;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link PromptEngine} that delegates to
 * {@link PromptTemplateService} for template resolution and supports
 * per-pipeline prompt customization via {@link IngestPipelineDefinition}.
 *
 * <p>This implementation consolidates the prompt-building logic that was
 * previously scattered across multiple classes (UnifiedCrawlGraphServiceImpl,
 * LlmRelationExtractionAgent, LlmKnowledgeGraphBuilder, Neo4jGraphConstructor)
 * into a single, configurable service.</p>
 */
@Component
public class DefaultPromptEngine implements PromptEngine {

    private final PromptTemplateService templateService;

    public DefaultPromptEngine(PromptTemplateService templateService) {
        this.templateService = templateService;
    }

    @Override
    public String forGraphExtraction(String text) {
        return forGraphExtraction(text, null, null, null, null, null);
    }

    /**
     * Generate an extraction prompt with full configurability.
     *
     * @param text the text to extract from
     * @param entityTypes entity types to extract (null = use template defaults)
     * @param relationshipTypes relationship types to extract (null = use template defaults)
     * @param pipelineDefinition the pipeline this chunk was routed through (null = use defaults)
     * @param schemaDescription optional schema description
     * @param customPromptOverride optional inline prompt that overrides the template
     * @return the rendered extraction prompt
     */
    public String forGraphExtraction(String text,
                                      List<String> entityTypes,
                                      List<String> relationshipTypes,
                                      IngestPipelineDefinition pipelineDefinition,
                                      String schemaDescription,
                                      String customPromptOverride) {
        // Determine effective entity types: pipeline override > job-level > default
        List<String> effectiveEntityTypes = resolveEntityTypes(entityTypes, pipelineDefinition);
        List<String> effectiveRelTypes = resolveRelationshipTypes(relationshipTypes, pipelineDefinition);

        // Build variables map for template substitution
        Map<String, String> variables = new HashMap<>();
        variables.put("TEXT", text != null ? text : "");
        variables.put("ENTITY_TYPES", String.join(", ", effectiveEntityTypes));
        variables.put("RELATIONSHIP_TYPES",
                effectiveRelTypes.isEmpty() ? "" : "Relationship types to look for: " + String.join(", ", effectiveRelTypes));
        variables.put("OUTPUT_FORMAT", GraphExtractionValidator.getExtractionPromptInstructions());
        variables.put("SCHEMA_DESCRIPTION", schemaDescription != null ? schemaDescription : "");

        // Evaluate prompt augmentations for domain-specific context
        String domainContext = evaluateAugmentations(text, pipelineDefinition);
        variables.put("DOMAIN_CONTEXT", domainContext);

        // Determine which template to use: custom override > pipeline template > default
        String templateRef = resolveTemplateRef(customPromptOverride, pipelineDefinition);

        return templateService.resolve(templateRef, variables);
    }

    @Override
    public String forEntitySummarization(Entity entity, List<String> textUnits) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following entity based on the text passages where it appears.\n\n");
        sb.append("Entity: ").append(entity.getTitle()).append("\n");
        sb.append("Type: ").append(entity.getType()).append("\n\n");
        sb.append("Text passages:\n");
        for (int i = 0; i < textUnits.size(); i++) {
            sb.append("--- Passage ").append(i + 1).append(" ---\n");
            sb.append(textUnits.get(i)).append("\n\n");
        }
        sb.append("""
                Provide a concise summary that captures the key facts about this entity.
                Return a JSON object:
                {
                  "summary": "A 2-3 sentence summary of the entity",
                  "key_facts": ["fact1", "fact2", ...],
                  "importance_score": 0.0-1.0
                }
                Output ONLY valid JSON, no markdown fences, no explanations.
                """);
        return sb.toString();
    }

    @Override
    public String forCommunitySummarization(Community community, List<Entity> entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following community of related entities.\n\n");
        sb.append("Community: ").append(community.getTitle()).append("\n\n");
        sb.append("Entities in this community:\n");
        for (Entity entity : entities) {
            sb.append("- ").append(entity.getTitle()).append(" (").append(entity.getType()).append(")");
            if (entity.getDescription() != null && !entity.getDescription().isBlank()) {
                sb.append(": ").append(entity.getDescription());
            }
            sb.append("\n");
        }
        sb.append("""

                Provide a concise summary that captures what this community represents
                and how the entities relate to each other.
                Return a JSON object:
                {
                  "summary": "A 2-3 sentence community summary",
                  "theme": "The overarching theme or topic",
                  "key_entities": ["entity1", "entity2", ...],
                  "importance_score": 0.0-1.0
                }
                Output ONLY valid JSON, no markdown fences, no explanations.
                """);
        return sb.toString();
    }

    // ---- Internal ----

    private List<String> resolveEntityTypes(List<String> jobLevel, IngestPipelineDefinition pipeline) {
        // Pipeline-level overrides job-level
        if (pipeline != null && pipeline.getExtractionEntityTypes() != null
                && !pipeline.getExtractionEntityTypes().isEmpty()) {
            return pipeline.getExtractionEntityTypes();
        }
        if (jobLevel != null && !jobLevel.isEmpty()) {
            return jobLevel;
        }
        return List.of("PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT");
    }

    private List<String> resolveRelationshipTypes(List<String> jobLevel, IngestPipelineDefinition pipeline) {
        if (pipeline != null && pipeline.getExtractionRelationshipTypes() != null
                && !pipeline.getExtractionRelationshipTypes().isEmpty()) {
            return pipeline.getExtractionRelationshipTypes();
        }
        if (jobLevel != null && !jobLevel.isEmpty()) {
            return jobLevel;
        }
        return List.of();
    }

    private String resolveTemplateRef(String customPromptOverride, IngestPipelineDefinition pipeline) {
        // Explicit custom prompt takes highest priority
        if (customPromptOverride != null && !customPromptOverride.isBlank()) {
            return customPromptOverride;
        }
        // Pipeline-level template
        if (pipeline != null && pipeline.getExtractionPromptTemplate() != null
                && !pipeline.getExtractionPromptTemplate().isBlank()) {
            return pipeline.getExtractionPromptTemplate();
        }
        // Fall through to default template in the service
        return null;
    }

    private String evaluateAugmentations(String text, IngestPipelineDefinition pipeline) {
        if (text == null || text.isEmpty()) return "";

        // Use pipeline-specific augmentations if defined
        if (pipeline != null && pipeline.getPromptAugmentations() != null
                && !pipeline.getPromptAugmentations().isEmpty()) {
            return PromptAugmentation.evaluateAll(pipeline.getPromptAugmentations(), text);
        }

        // Fall back to built-in heuristics
        return buildBuiltInAugmentations(text);
    }

    /**
     * Built-in heuristics that replicate the previously hardcoded logic from
     * LlmRelationExtractionAgent.buildProcessExtractionContext().
     * These are used as a fallback when no pipeline-specific augmentations are configured.
     */
    private String buildBuiltInAugmentations(String text) {
        if (text.length() < 50) return "";

        String lower = text.toLowerCase();
        StringBuilder ctx = new StringBuilder();

        boolean hasEmailSignals = lower.contains("from:") || lower.contains("to:")
                || lower.contains("subject:") || lower.contains("dear ")
                || lower.contains("please ") || lower.contains("hi ");
        boolean hasProceduralSignals = lower.contains("step 1") || lower.contains("step one")
                || lower.contains("first,") || lower.contains("then ")
                || lower.contains("follow these") || lower.contains("instructions")
                || lower.contains("how to ") || lower.contains("procedure");
        boolean hasFileReferences = lower.contains(".xlsx") || lower.contains(".xls")
                || lower.contains(".csv") || lower.contains(".pdf")
                || lower.contains(".docx") || lower.contains("spreadsheet")
                || lower.contains("workbook") || lower.contains("attached");
        boolean hasSpreadsheetActions = lower.contains("fill in") || lower.contains("fill out")
                || lower.contains("enter the") || lower.contains("update the")
                || lower.contains("column ") || lower.contains("row ")
                || lower.contains("cell ") || lower.contains("formula");

        if (hasEmailSignals || hasProceduralSignals) {
            ctx.append("""

                    PROCESS EXTRACTION: This text appears to contain instructions or procedural content.
                    In addition to standard entities, look for:
                    - PROCESS entities: Named workflows, procedures, or multi-step activities
                    - PROCEDURE entities: Specific sets of instructions for accomplishing a task
                    - Relations: SUBPROCESS_OF, DESCRIBES_PROCEDURE
                    """);
        }
        if (hasFileReferences) {
            ctx.append("""
                    CROSS-DOCUMENT REFERENCES: This text references external files or documents.
                    Extract referenced document/file names as entities and create:
                    - REFERENCES_DOCUMENT relations when mentioning another document by name
                    - INSTRUCTS_USAGE relations for instructions about a referenced document
                    - INPUT_TO / OUTPUT_OF relations connecting data artifacts to processes
                    """);
        }
        if (hasSpreadsheetActions) {
            ctx.append("""
                    SPREADSHEET OPERATIONS: This text describes operations on a spreadsheet.
                    Extract the spreadsheet as an entity and create relations capturing:
                    - INPUT_TO for input cells/columns/rows
                    - OUTPUT_OF for output cells/columns/rows
                    - DESCRIBES_PROCEDURE for computations or validations
                    """);
        }

        return ctx.toString();
    }
}
