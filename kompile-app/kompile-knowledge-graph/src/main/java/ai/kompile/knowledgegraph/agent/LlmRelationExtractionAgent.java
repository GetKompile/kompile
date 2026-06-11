/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * A {@link RelationExtractionAgent} that delegates to the active language model (if one is
 * configured) to extract entities and relationships from each document chunk.
 *
 * <p>The LLM provider is configurable per-request via the {@code "llmProvider"} key in
 * {@link RelationExtractionAgent.ExtractionConfig#options()}. Supported values include
 * {@code "llm-chat"} (local SameDiff / Spring AI), {@code "claude-cli"}, {@code "gemini-cli"},
 * or any other provider registered in the {@link ExtractionLlmServiceRegistry}.
 * When not specified, falls back to the first available provider.
 */
@Component
@Slf4j
public class LlmRelationExtractionAgent implements RelationExtractionAgent {

    public static final String AGENT_ID = "llm-active";

    /**
     * Registry of available LLM providers for extraction.
     * The provider is selected per-request via {@code config.options().get("llmProvider")}.
     */
    private ExtractionLlmServiceRegistry llmServiceRegistry;

    @Autowired(required = false)
    public void setLlmServiceRegistry(ExtractionLlmServiceRegistry registry) {
        this.llmServiceRegistry = registry;
        if (registry != null) {
            log.info("LlmRelationExtractionAgent: ExtractionLlmServiceRegistry configured");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RelationExtractionAgent contract
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public String getId() {
        return AGENT_ID;
    }

    @Override
    public String getDescription() {
        return "LLM-based relation extraction using the active language model";
    }

    @Override
    public Set<String> supportedContentTypes() {
        // Empty set means "any content type"
        return Set.of();
    }

    @Override
    public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
        long startTime = System.currentTimeMillis();

        if (chunks == null || chunks.isEmpty()) {
            return emptyResult(0, System.currentTimeMillis() - startTime);
        }

        ExtractionConfig effectiveConfig = config != null ? config : ExtractionConfig.defaults();

        // Resolve the LLM provider from config options
        ExtractionLlmService llmService = resolveLlmService(effectiveConfig);
        if (llmService == null) {
            log.warn("LlmRelationExtractionAgent: no LLM provider available, returning empty graph");
            return emptyResult(0, System.currentTimeMillis() - startTime);
        }
        log.info("LlmRelationExtractionAgent: using provider '{}' for {} chunks",
                llmService.getId(), chunks.size());

        List<GraphExtractionSchema.ExtractedEntity> allEntities = new ArrayList<>();
        List<GraphExtractionSchema.ExtractedRelation> allRelations = new ArrayList<>();

        for (RetrievedDoc chunk : chunks) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("LlmRelationExtractionAgent interrupted, stopping early");
                break;
            }
            if (!chunk.isText() || chunk.getText() == null || chunk.getText().isBlank()) {
                continue;
            }
            processChunk(chunk, effectiveConfig, llmService, allEntities, allRelations);
        }

        // Deduplicate entities by ID (keep first occurrence)
        List<GraphExtractionSchema.ExtractedEntity> dedupedEntities = deduplicateEntities(allEntities);
        // Deduplicate relations by (source, target, type) triple
        List<GraphExtractionSchema.ExtractedRelation> dedupedRelations = deduplicateRelations(allRelations);

        // Filter by minimum confidence
        double minConf = effectiveConfig.minConfidence();
        if (minConf > 0.0) {
            dedupedEntities = dedupedEntities.stream()
                    .filter(e -> e.confidence() == null || e.confidence() >= minConf)
                    .toList();
            dedupedRelations = dedupedRelations.stream()
                    .filter(r -> r.confidence() == null || r.confidence() >= minConf)
                    .toList();
        }

        Graph graph = GraphExtractionValidator.toGraph(
                GraphExtractionSchema.ExtractionResult.of(
                        dedupedEntities,
                        dedupedRelations,
                        GraphExtractionSchema.ExtractionMetadata.forChunk(null, null, AGENT_ID)
                )
        );

        long elapsed = System.currentTimeMillis() - startTime;
        AgentMetrics metrics = new AgentMetrics(
                AGENT_ID,
                elapsed,
                dedupedEntities.size(),
                dedupedRelations.size(),
                chunks.size(),
                llmService.getId(),
                Map.of("rawEntities", allEntities.size(), "rawRelations", allRelations.size(),
                        "llmProvider", llmService.getId())
        );

        log.info("LlmRelationExtractionAgent: {} entities, {} relations from {} chunks in {}ms",
                dedupedEntities.size(), dedupedRelations.size(), chunks.size(), elapsed);

        return new ExtractionResult(graph, metrics);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve which LLM provider to use for this extraction run.
     * Checks {@code config.options().get("llmProvider")} first, then falls back
     * to any available provider in the registry.
     */
    private ExtractionLlmService resolveLlmService(ExtractionConfig config) {
        if (llmServiceRegistry == null) {
            return null;
        }

        String requestedProvider = null;
        if (config.options() != null) {
            Object val = config.options().get("llmProvider");
            if (val instanceof String s && !s.isBlank()) {
                requestedProvider = s;
            }
        }

        return llmServiceRegistry.getOrFallback(requestedProvider);
    }

    private void processChunk(
            RetrievedDoc chunk,
            ExtractionConfig config,
            ExtractionLlmService llmService,
            List<GraphExtractionSchema.ExtractedEntity> entityAccum,
            List<GraphExtractionSchema.ExtractedRelation> relationAccum) {

        try {
            String entityTypeList = config.entityTypes() != null && !config.entityTypes().isEmpty()
                    ? String.join(", ", config.entityTypes())
                    : "PERSON, ORGANIZATION, LOCATION, CONCEPT, EVENT";

            // Truncate chunk text to keep prompt within reasonable token limits.
            // For local models, use a smaller limit to leave room for output tokens.
            String chunkText = chunk.getText();
            boolean isVlmContent = chunk.getMetadata() != null
                    && Boolean.TRUE.equals(chunk.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            int maxChars = "llm-chat".equals(llmService.getId()) ? 800 : 2000;
            // VLM content contains structural markup that is valuable for extraction;
            // allow slightly more text for external models to preserve document structure.
            if (isVlmContent && !"llm-chat".equals(llmService.getId())) {
                maxChars = 3000;
            }
            if (chunkText.length() > maxChars) {
                chunkText = chunkText.substring(0, maxChars);
            }

            String prompt = buildExtractionPrompt(entityTypeList, chunkText, llmService.getId(), isVlmContent);

            String response = llmService.complete(prompt);

            if (response == null || response.isBlank()) {
                log.debug("LlmRelationExtractionAgent: empty response for chunk {}", chunk.getId());
                return;
            }

            // For local models, the prompt ends with "{" so the response continues
            // the JSON object — prepend "{" to make it parseable.
            if ("llm-chat".equals(llmService.getId()) && !response.trim().startsWith("{")) {
                response = "{" + response;
            }

            log.info("LlmRelationExtractionAgent: response from {} for chunk {} (length={}): {}",
                    llmService.getId(), chunk.getId(), response.length(),
                    response.substring(0, Math.min(300, response.length())));

            GraphExtractionSchema.ExtractionResult parsed = parseResponse(response);
            if (parsed != null) {
                entityAccum.addAll(parsed.entities());
                relationAccum.addAll(parsed.relations());
            }

        } catch (Exception e) {
            log.warn("LlmRelationExtractionAgent: failed to process chunk {}: {}",
                    chunk.getId(), e.getMessage());
        }
    }

    /**
     * Build the extraction prompt. Uses a compact few-shot format for local models
     * (llm-chat) and the full schema instructions for larger external models.
     *
     * @param entityTypes  comma-separated entity type list
     * @param text         chunk text to extract from
     * @param providerId   LLM provider ID (e.g., "llm-chat", "claude-cli")
     * @param isVlmContent true if the text was extracted from a PDF via VLM
     */
    private String buildExtractionPrompt(String entityTypes, String text, String providerId,
                                          boolean isVlmContent) {
        if ("llm-chat".equals(providerId)) {
            // Constrained prompt for small local models: provide a filled example,
            // then start the JSON so the model just continues the object.
            return """
                    <|im_start|>system
                    You extract entities from text and output JSON only.<|im_end|>
                    <|im_start|>user
                    Text: "Alice from Northstar Inc met Bob at the NYC office."
                    Extract entities and relations as JSON.<|im_end|>
                    <|im_start|>assistant
                    {"entities":[{"id":"e1","name":"Alice","type":"PERSON","description":"person from Northstar"},{"id":"e2","name":"Northstar Inc","type":"ORGANIZATION","description":"company"},{"id":"e3","name":"Bob","type":"PERSON","description":"person"},{"id":"e4","name":"NYC office","type":"LOCATION","description":"office location"}],"relations":[{"source":"e1","target":"e2","type":"WORKS_AT","description":"Alice works at Northstar"},{"source":"e1","target":"e3","type":"MET_WITH","description":"Alice met Bob"}]}<|im_end|>
                    <|im_start|>user
                    Text: "%s"
                    Extract entities (%s) and relations as JSON.<|im_end|>
                    <|im_start|>assistant
                    {""".formatted(text.replace("\"", "'"), entityTypes);
        }

        // VLM-specific extraction guidance for external models
        String vlmContext = "";
        if (isVlmContent) {
            vlmContext = """

                    IMPORTANT: This text was extracted from a PDF document using a Visual Language Model (VLM).
                    The content preserves document structure including headings, tables, form fields, and references.
                    When extracting entities and relationships:
                    - Table rows often represent structured entities with column headers as property names
                    - Section headings indicate topic or category entities
                    - References and citations create CITES or REFERENCES relationships
                    - Form field labels paired with values indicate entity properties
                    - Figure captions may describe entities, measurements, or processes

                    """;
        }

        // Source-type-aware process extraction guidance
        String processContext = buildProcessExtractionContext(text);

        // Full prompt for external models (Claude, Gemini, etc.)
        return """
                Extract entities and their relationships from the following text.

                Entity types to look for: %s
                %s%s
                %s

                Text to analyze:
                \"\"\"
                %s
                \"\"\"
                """.formatted(
                entityTypes,
                vlmContext,
                processContext,
                GraphExtractionValidator.getExtractionPromptInstructions(),
                text
        );
    }

    /**
     * Detects if the text content appears to contain procedural/instructional content
     * and returns additional extraction guidance for the LLM prompt.
     * Triggers on signals like: email-style headers, imperative instructions,
     * references to file names, step numbering, or spreadsheet references.
     */
    private String buildProcessExtractionContext(String text) {
        if (text == null || text.length() < 50) return "";

        String lower = text.toLowerCase();
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

        if (!hasEmailSignals && !hasProceduralSignals && !hasFileReferences && !hasSpreadsheetActions) {
            return "";
        }

        StringBuilder ctx = new StringBuilder("\n");
        if (hasEmailSignals || hasProceduralSignals) {
            ctx.append("""
                    PROCESS EXTRACTION: This text appears to contain instructions or procedural content.
                    In addition to standard entities, look for:
                    - PROCESS entities: Named workflows, procedures, or multi-step activities described in the text
                    - PROCEDURE entities: Specific sets of instructions for accomplishing a task
                    - Relations between processes: SUBPROCESS_OF (part of a larger process), DESCRIBES_PROCEDURE (document describes a procedure)
                    """);
        }
        if (hasFileReferences) {
            ctx.append("""
                    CROSS-DOCUMENT REFERENCES: This text references external files or documents.
                    Extract the referenced document/file names as entities and create:
                    - REFERENCES_DOCUMENT relations when the text mentions another document by name
                    - INSTRUCTS_USAGE relations when the text provides instructions for using a referenced document
                    - INPUT_TO / OUTPUT_OF relations connecting data artifacts to processes
                    """);
        }
        if (hasSpreadsheetActions) {
            ctx.append("""
                    SPREADSHEET OPERATIONS: This text describes operations on a spreadsheet.
                    Extract the spreadsheet as an entity and create relations capturing:
                    - Which cells/columns/rows are inputs (INPUT_TO)
                    - Which cells/columns/rows are outputs (OUTPUT_OF)
                    - What computation or validation is being described (DESCRIBES_PROCEDURE)
                    """);
        }

        return ctx.toString();
    }

    private GraphExtractionSchema.ExtractionResult parseResponse(String response) {
        String cleaned = response.trim();
        // Strip optional markdown fences
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // Try direct parse first
        try {
            return GraphExtractionValidator.fromJson(cleaned);
        } catch (Exception e) {
            // If direct parse fails, try to extract JSON object from the response.
            int firstBrace = cleaned.indexOf('{');
            int lastBrace = cleaned.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                String extracted = cleaned.substring(firstBrace, lastBrace + 1);
                try {
                    return GraphExtractionValidator.fromJson(extracted);
                } catch (Exception e2) {
                    log.debug("LlmRelationExtractionAgent: strict parse failed: {}", e2.getMessage());
                }
            }

            // Lenient fallback: handle truncated JSON and schema variations from small models.
            // Model may use "entity" instead of "name", or JSON may be cut off mid-token.
            GraphExtractionSchema.ExtractionResult lenient = parseLenient(cleaned);
            if (lenient != null) return lenient;

            log.debug("LlmRelationExtractionAgent: could not parse LLM response: {}", e.getMessage());
            log.trace("LlmRelationExtractionAgent: raw response (first 500 chars): {}",
                    response.substring(0, Math.min(500, response.length())));
            return null;
        }
    }

    /**
     * Lenient JSON parser that handles truncated output and schema variations.
     * Extracts individual entity objects from the "entities" array even if the
     * overall JSON is incomplete (hit token limit). Normalizes "entity" → "name".
     */
    private GraphExtractionSchema.ExtractionResult parseLenient(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<GraphExtractionSchema.ExtractedEntity> entities = new ArrayList<>();
            List<GraphExtractionSchema.ExtractedRelation> relations = new ArrayList<>();

            // Extract entities array content
            int entIdx = json.indexOf("\"entities\"");
            if (entIdx < 0) return null;
            int arrStart = json.indexOf('[', entIdx);
            if (arrStart < 0) return null;

            // Parse individual objects from the array, tolerating truncation
            int depth = 0;
            int objStart = -1;
            int entityCounter = 0;
            for (int i = arrStart + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{' && depth == 0) { objStart = i; depth = 1; }
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String objStr = json.substring(objStart, i + 1);
                        try {
                            com.fasterxml.jackson.databind.JsonNode node = om.readTree(objStr);
                            entityCounter++;
                            String id = node.has("id") ? node.get("id").asText()
                                    : "e" + entityCounter;
                            String name = node.has("name") ? node.get("name").asText()
                                    : node.has("entity") ? node.get("entity").asText() : null;
                            String type = node.has("type") ? node.get("type").asText() : "CONCEPT";
                            String desc = node.has("description") ? node.get("description").asText()
                                    : name != null ? name : "";
                            if (name != null && !name.isBlank()) {
                                entities.add(new GraphExtractionSchema.ExtractedEntity(
                                        id, name, type, List.of(), desc, null, Map.of()));
                            }
                        } catch (Exception ignored) {}
                        objStart = -1;
                    }
                }
                if (c == ']' && depth == 0) break; // end of entities array
            }

            // Try to extract relations array too
            int relIdx = json.indexOf("\"relations\"");
            if (relIdx >= 0) {
                int relArrStart = json.indexOf('[', relIdx);
                if (relArrStart >= 0) {
                    depth = 0; objStart = -1;
                    for (int i = relArrStart + 1; i < json.length(); i++) {
                        char c = json.charAt(i);
                        if (c == '{' && depth == 0) { objStart = i; depth = 1; }
                        else if (c == '{') depth++;
                        else if (c == '}') {
                            depth--;
                            if (depth == 0 && objStart >= 0) {
                                String objStr = json.substring(objStart, i + 1);
                                try {
                                    com.fasterxml.jackson.databind.JsonNode node = om.readTree(objStr);
                                    String src = node.has("source") ? node.get("source").asText() : null;
                                    String tgt = node.has("target") ? node.get("target").asText() : null;
                                    String type = node.has("type") ? node.get("type").asText() : "RELATED_TO";
                                    String desc = node.has("description") ? node.get("description").asText() : "";
                                    if (src != null && tgt != null) {
                                        relations.add(new GraphExtractionSchema.ExtractedRelation(
                                                src, tgt, type, desc, null, Map.of()));
                                    }
                                } catch (Exception ignored) {}
                                objStart = -1;
                            }
                        }
                        if (c == ']' && depth == 0) break;
                    }
                }
            }

            if (entities.isEmpty() && relations.isEmpty()) return null;

            log.info("LlmRelationExtractionAgent: lenient parse recovered {} entities, {} relations",
                    entities.size(), relations.size());
            return GraphExtractionSchema.ExtractionResult.of(entities, relations,
                    GraphExtractionSchema.ExtractionMetadata.forChunk(null, null, AGENT_ID));
        } catch (Exception e) {
            log.debug("LlmRelationExtractionAgent: lenient parse failed: {}", e.getMessage());
            return null;
        }
    }

    private List<GraphExtractionSchema.ExtractedEntity> deduplicateEntities(
            List<GraphExtractionSchema.ExtractedEntity> entities) {
        Map<String, GraphExtractionSchema.ExtractedEntity> seen = new java.util.LinkedHashMap<>();
        for (GraphExtractionSchema.ExtractedEntity e : entities) {
            seen.putIfAbsent(e.id(), e);
        }
        return new ArrayList<>(seen.values());
    }

    private List<GraphExtractionSchema.ExtractedRelation> deduplicateRelations(
            List<GraphExtractionSchema.ExtractedRelation> relations) {
        Map<String, GraphExtractionSchema.ExtractedRelation> seen = new java.util.LinkedHashMap<>();
        for (GraphExtractionSchema.ExtractedRelation r : relations) {
            String key = r.source() + "|" + r.target() + "|" + r.type();
            seen.putIfAbsent(key, r);
        }
        return new ArrayList<>(seen.values());
    }

    private ExtractionResult emptyResult(int chunksProcessed, long elapsedMs) {
        Graph emptyGraph = new Graph();
        emptyGraph.setEntities(List.of());
        emptyGraph.setRelationships(List.of());
        return new ExtractionResult(
                emptyGraph,
                new AgentMetrics(AGENT_ID, elapsedMs, 0, 0, chunksProcessed, null, Map.of())
        );
    }
}
