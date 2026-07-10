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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.format.LlmJsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the LLM graph-extraction stage for an interactive dry-run preview.
 *
 * <p>This deliberately stops at the parsed {@link GraphExtractionSchema.ExtractionResult}: it never
 * calls {@code KnowledgeGraphService}, {@code GraphPersistenceHelper}, vector indexing, or crawl-state
 * persistence.</p>
 */
@Component
public class GraphExtractionPreviewService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionPreviewService.class);

    private static final int DEFAULT_DOCUMENT_LIMIT = 3;
    private static final int MAX_DOCUMENT_LIMIT = 10;
    private static final int DEFAULT_CHAR_LIMIT = 12_000;
    private static final int MAX_CHAR_LIMIT = 40_000;
    private static final int MAX_WARNINGS = 8;

    @Autowired(required = false)
    private CrawlLlmDispatcher llmDispatcher;

    public PreviewResponse preview(
            List<Document> documents,
            GraphExtractionConfig config,
            ProcessingRouteConfig processingRoute,
            int maxDocuments,
            int maxCharsPerDocument) {
        if (config == null) {
            config = GraphExtractionConfig.builder().build();
        }
        if (documents == null || documents.isEmpty()) {
            return PreviewResponse.skipped("No loaded text was available for graph extraction preview.");
        }
        if (llmDispatcher == null) {
            return PreviewResponse.unavailable("LLM graph extraction is not available in this runtime.");
        }

        int documentLimit = sanitizeDocumentLimit(maxDocuments);
        int charLimit = sanitizeCharLimit(maxCharsPerDocument);
        List<String> warnings = new ArrayList<>();
        List<EntityPreview> entities = new ArrayList<>();
        List<RelationPreview> relations = new ArrayList<>();
        Map<String, EntityPreview> entitiesById = new LinkedHashMap<>();

        UnifiedCrawlJob previewJob = previewJob(config, processingRoute);
        String extractionPrompt = buildExtractionPrompt(config);
        int analyzed = 0;
        int truncatedDocuments = 0;

        for (Document document : documents) {
            if (analyzed >= documentLimit) {
                break;
            }
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            analyzed++;
            if (text.length() > charLimit) {
                text = text.substring(0, charLimit);
                truncatedDocuments++;
            }

            ExtractionAttempt attempt = extractDocument(document, text, extractionPrompt, config, previewJob, warnings);
            if (attempt == null || attempt.result() == null) {
                continue;
            }

            String sourceDocumentId = sourceDocumentId(document, analyzed);
            String sourceTitle = sourceTitle(document, sourceDocumentId);
            Map<String, String> namesThisDocument = new LinkedHashMap<>();
            for (GraphExtractionSchema.ExtractedEntity entity : attempt.result().entities()) {
                EntityPreview preview = new EntityPreview(
                        entity.id(),
                        entity.name(),
                        entity.type(),
                        entity.aliases(),
                        entity.description(),
                        entity.confidence(),
                        sourceDocumentId,
                        sourceTitle,
                        entity.properties());
                entities.add(preview);
                entitiesById.putIfAbsent(entity.id(), preview);
                namesThisDocument.put(entity.id(), entity.name());
            }
            for (GraphExtractionSchema.ExtractedRelation relation : attempt.result().relations()) {
                String sourceName = firstNonBlank(namesThisDocument.get(relation.source()),
                        entitiesById.containsKey(relation.source()) ? entitiesById.get(relation.source()).name() : null,
                        relation.source());
                String targetName = firstNonBlank(namesThisDocument.get(relation.target()),
                        entitiesById.containsKey(relation.target()) ? entitiesById.get(relation.target()).name() : null,
                        relation.target());
                relations.add(new RelationPreview(
                        relation.source(),
                        relation.target(),
                        relation.type(),
                        sourceName,
                        targetName,
                        relation.description(),
                        relation.confidence(),
                        sourceDocumentId,
                        sourceTitle,
                        relation.properties(),
                        relation.occurredAt()));
            }
        }

        if (analyzed == 0) {
            addWarning(warnings, "No non-empty preview documents were available for LLM graph extraction.");
        }
        if (truncatedDocuments > 0) {
            addWarning(warnings, "Graph extraction preview truncated " + truncatedDocuments
                    + " document(s) to " + charLimit + " characters each.");
        }
        if (documents.size() > analyzed) {
            addWarning(warnings, "Graph extraction preview analyzed " + analyzed + " of " + documents.size()
                    + " available document chunks.");
        }

        return new PreviewResponse(
                true,
                true,
                true,
                analyzed > 0,
                warnings.stream().anyMatch(w -> w.toLowerCase(Locale.ROOT).contains("failed")) ? "PARTIAL" : "COMPLETED",
                analyzed,
                truncatedDocuments,
                entities.size(),
                relations.size(),
                firstNonBlank(config.getModelName(), config.getLlmProvider(), "default"),
                entities,
                relations,
                warnings);
    }

    private ExtractionAttempt extractDocument(
            Document document,
            String text,
            String extractionPrompt,
            GraphExtractionConfig config,
            UnifiedCrawlJob previewJob,
            List<String> warnings) {
        String prompt = extractionPrompt + vlmHint(document) + "\n\nText to analyze:\n" + text;
        int maxRetries = Math.max(0, previewJob.getRequest().getMaxValidationRetries());
        String lastValidationErrors = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String promptToSend = prompt;
            if (attempt > 0 && lastValidationErrors != null) {
                promptToSend = prompt + "\n\n[RETRY: Previous response had validation errors: "
                        + lastValidationErrors + ". Please fix these issues in your response.]";
            }

            try {
                String response = llmDispatcher.promptWithCapacityFallback(promptToSend, "llm", previewJob);
                if (response == null || response.isBlank()) {
                    lastValidationErrors = "LLM returned no response";
                    continue;
                }

                String json = LlmJsonExtractor.extractJsonObject(response);
                if (json == null || json.isBlank()) {
                    lastValidationErrors = "LLM response did not contain a JSON object";
                    continue;
                }

                GraphExtractionSchema.ExtractionResult result = GraphExtractionValidator.fromJson(json);
                var validation = GraphExtractionValidator.validate(result);
                if (!validation.valid()) {
                    lastValidationErrors = String.join("; ", validation.errors());
                    continue;
                }
                return new ExtractionAttempt(result);
            } catch (Exception e) {
                lastValidationErrors = e.getMessage();
                log.debug("Graph extraction preview attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }

        addWarning(warnings, "Graph extraction preview failed for " + sourceTitle(document, document.getId())
                + (lastValidationErrors != null ? ": " + lastValidationErrors : "."));
        return null;
    }

    private UnifiedCrawlJob previewJob(GraphExtractionConfig config, ProcessingRouteConfig processingRoute) {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Single source graph extraction preview")
                .graphExtraction(config)
                .processingRoute(processingRoute)
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .jobId("single-source-preview-" + UUID.randomUUID())
                .request(request)
                .build();
        job.getCurrentPhase().set("GRAPH_EXTRACTION_PREVIEW");
        job.getGraphChunksTotal().set(0);
        return job;
    }

    private String buildExtractionPrompt(GraphExtractionConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(GraphExtractionValidator.getExtractionPromptInstructions());

        if (config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
            sb.append("\n\nFocus on extracting these entity types: ");
            sb.append(String.join(", ", config.getEntityTypes()));
        }

        if (config.getRelationshipTypes() != null && !config.getRelationshipTypes().isEmpty()) {
            sb.append("\nFocus on extracting these relationship types: ");
            sb.append(String.join(", ", config.getRelationshipTypes()));
        }

        if (config.getCustomPrompt() != null && !config.getCustomPrompt().isBlank()) {
            sb.append("\n\nAdditional instructions: ").append(config.getCustomPrompt());
        }

        return sb.toString();
    }

    private String vlmHint(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null || !Boolean.TRUE.equals(metadata.get(GraphConstants.META_VLM_PROCESSED))) {
            return "";
        }
        String vlmModel = metadata.get(GraphConstants.META_VLM_MODEL) instanceof String model ? model : "unknown";
        return "\n\nNote: This text was extracted from a PDF using a Visual Language Model ("
                + vlmModel + "). It may contain document structure markup (headings, tables, form fields, "
                + "references). Extract entities and relationships from both prose content and structured elements.\n";
    }

    private int sanitizeDocumentLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_DOCUMENT_LIMIT;
        }
        return Math.min(MAX_DOCUMENT_LIMIT, requested);
    }

    private int sanitizeCharLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_CHAR_LIMIT;
        }
        return Math.min(MAX_CHAR_LIMIT, requested);
    }

    private String sourceDocumentId(Document document, int index) {
        return firstNonBlank(
                asString(document.getMetadata() != null ? document.getMetadata().get("sourceDocumentId") : null),
                document.getId(),
                asString(document.getMetadata() != null ? document.getMetadata().get(GraphConstants.META_SOURCE) : null),
                "preview-doc-" + index);
    }

    private String sourceTitle(Document document, String fallback) {
        Map<String, Object> metadata = document.getMetadata();
        return firstNonBlank(
                asString(metadata != null ? metadata.get("title") : null),
                asString(metadata != null ? metadata.get("fileName") : null),
                asString(metadata != null ? metadata.get(GraphConstants.META_SOURCE_PATH) : null),
                asString(metadata != null ? metadata.get(GraphConstants.META_SOURCE) : null),
                fallback);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private void addWarning(List<String> warnings, String warning) {
        if (warning != null && !warning.isBlank() && warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }

    private record ExtractionAttempt(GraphExtractionSchema.ExtractionResult result) {
    }

    public record PreviewResponse(
            boolean dryRun,
            boolean available,
            boolean enabled,
            boolean extractionRun,
            String status,
            int documentsAnalyzed,
            int truncatedDocumentCount,
            int entityCount,
            int relationCount,
            String modelName,
            List<EntityPreview> entities,
            List<RelationPreview> relations,
            List<String> warnings) {
        public static PreviewResponse unavailable(String warning) {
            return new PreviewResponse(true, false, true, false, "UNAVAILABLE", 0, 0, 0, 0,
                    null, List.of(), List.of(), List.of(warning));
        }

        public static PreviewResponse skipped(String warning) {
            return new PreviewResponse(true, true, false, false, "SKIPPED", 0, 0, 0, 0,
                    null, List.of(), List.of(), List.of(warning));
        }
    }

    public record EntityPreview(
            String id,
            String name,
            String type,
            List<String> aliases,
            String description,
            Double confidence,
            String sourceDocumentId,
            String sourceTitle,
            Map<String, String> properties) {
    }

    public record RelationPreview(
            String source,
            String target,
            String type,
            String sourceName,
            String targetName,
            String description,
            Double confidence,
            String sourceDocumentId,
            String sourceTitle,
            Map<String, String> properties,
            String occurredAt) {
    }
}
