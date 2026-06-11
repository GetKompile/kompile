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
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI-side graph extraction engine. Sends text to an LLM (resolved via
 * {@link CliExtractionLlmClient}) with the standardized extraction prompt,
 * parses the JSON response into entities/relations, and optionally persists
 * results to a running kompile-app.
 *
 * <p>This class is entirely self-contained — it does NOT depend on
 * kompile-app-core classes. The extraction prompt and JSON schema are
 * embedded inline, matching {@code GraphExtractionValidator.getExtractionPromptInstructions()}.
 */
public class CliGraphExtractor implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final CliExtractionLlmClient llmClient;
    private List<String> entityTypes;
    private List<String> relationshipTypes;
    private double minConfidence = 0.5;
    private String customPrompt;

    public CliGraphExtractor(CliExtractionLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setEntityTypes(List<String> entityTypes) {
        this.entityTypes = entityTypes;
    }

    public void setRelationshipTypes(List<String> relationshipTypes) {
        this.relationshipTypes = relationshipTypes;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public void setCustomPrompt(String customPrompt) {
        this.customPrompt = customPrompt;
    }

    /**
     * Extract entities and relations from the given text.
     *
     * @return extraction result with entities and relations
     */
    public ExtractionResult extract(String text) {
        String prompt = buildPrompt(text);
        String llmResponse = llmClient.complete(prompt);
        return parseResponse(llmResponse);
    }

    /**
     * Extract and persist results to a running kompile-app server.
     *
     * @param client the HTTP client connected to kompile-app
     * @return the server's response as JSON
     */
    public String extractAndPersist(String text, KompileHttpClient client)
            throws IOException, InterruptedException {
        ExtractionResult result = extract(text);

        // Build the import request
        Map<String, Object> importBody = new LinkedHashMap<>();

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ExtractedEntity entity : result.entities) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", entity.id);
            node.put("title", entity.name);
            node.put("type", entity.type);
            node.put("description", entity.description);
            if (entity.confidence != null) node.put("confidence", entity.confidence);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (entity.aliases != null && !entity.aliases.isEmpty()) {
                metadata.put("aliases", entity.aliases);
            }
            if (entity.properties != null) metadata.putAll(entity.properties);
            if (!metadata.isEmpty()) node.put("metadata", metadata);
            nodes.add(node);
        }
        importBody.put("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (ExtractedRelation rel : result.relations) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("source", rel.source);
            edge.put("target", rel.target);
            edge.put("type", rel.type);
            edge.put("description", rel.description);
            if (rel.confidence != null) edge.put("weight", rel.confidence);
            if (rel.properties != null && !rel.properties.isEmpty()) {
                edge.put("metadata", rel.properties);
            }
            edges.add(edge);
        }
        importBody.put("edges", edges);
        importBody.put("format", "json");

        return client.postString("/api/knowledge-graph/import", importBody);
    }

    private String buildPrompt(String text) {
        StringBuilder prompt = new StringBuilder();

        if (customPrompt != null && !customPrompt.isBlank()) {
            prompt.append(customPrompt).append("\n\n");
        } else {
            prompt.append("Extract all entities and relationships from the following text.\n\n");
        }

        // Schema instructions (matches GraphExtractionValidator.getExtractionPromptInstructions())
        prompt.append(EXTRACTION_PROMPT_INSTRUCTIONS);

        // Optional type constraints
        if (entityTypes != null && !entityTypes.isEmpty()) {
            prompt.append("\nFocus on these entity types: ").append(String.join(", ", entityTypes)).append("\n");
        }
        if (relationshipTypes != null && !relationshipTypes.isEmpty()) {
            prompt.append("Focus on these relationship types: ").append(String.join(", ", relationshipTypes)).append("\n");
        }
        if (minConfidence > 0) {
            prompt.append("Only include entities and relationships with confidence >= ")
                    .append(String.format("%.2f", minConfidence)).append("\n");
        }

        prompt.append("\nText to analyze:\n---\n").append(text).append("\n---\n");
        return prompt.toString();
    }

    /**
     * Parse the LLM response, extracting JSON from potential markdown fences or
     * surrounding text.
     */
    ExtractionResult parseResponse(String response) {
        String json = extractJson(response);
        try {
            JsonNode root = MAPPER.readTree(json);

            List<ExtractedEntity> entities = new ArrayList<>();
            JsonNode entitiesNode = root.path("entities");
            if (entitiesNode.isArray()) {
                for (JsonNode e : entitiesNode) {
                    ExtractedEntity entity = new ExtractedEntity();
                    entity.id = e.path("id").asText(null);
                    entity.name = e.path("name").asText(null);
                    entity.type = e.path("type").asText(null);
                    entity.description = e.path("description").asText(null);
                    entity.confidence = e.has("confidence") ? e.path("confidence").asDouble(1.0) : 1.0;

                    JsonNode aliasesNode = e.path("aliases");
                    if (aliasesNode.isArray()) {
                        entity.aliases = new ArrayList<>();
                        for (JsonNode a : aliasesNode) entity.aliases.add(a.asText());
                    }

                    JsonNode propsNode = e.path("properties");
                    if (propsNode.isObject()) {
                        entity.properties = new LinkedHashMap<>();
                        propsNode.fields().forEachRemaining(f ->
                                entity.properties.put(f.getKey(), f.getValue().asText()));
                    }

                    if (entity.id != null && entity.name != null) {
                        entities.add(entity);
                    }
                }
            }

            List<ExtractedRelation> relations = new ArrayList<>();
            JsonNode relationsNode = root.path("relations");
            // Also try "relationships" as alternate key
            if (!relationsNode.isArray()) relationsNode = root.path("relationships");
            if (relationsNode.isArray()) {
                for (JsonNode r : relationsNode) {
                    ExtractedRelation rel = new ExtractedRelation();
                    rel.source = r.path("source").asText(null);
                    rel.target = r.path("target").asText(null);
                    rel.type = r.path("type").asText(null);
                    rel.description = r.path("description").asText(null);
                    rel.confidence = r.has("confidence") ? r.path("confidence").asDouble(1.0) : 1.0;

                    JsonNode propsNode = r.path("properties");
                    if (propsNode.isObject()) {
                        rel.properties = new LinkedHashMap<>();
                        propsNode.fields().forEachRemaining(f ->
                                rel.properties.put(f.getKey(), f.getValue().asText()));
                    }

                    if (rel.source != null && rel.target != null && rel.type != null) {
                        relations.add(rel);
                    }
                }
            }

            ExtractionResult result = new ExtractionResult();
            result.entities = entities;
            result.relations = relations;
            return result;
        } catch (Exception e) {
            // Return empty result with error info
            ExtractionResult result = new ExtractionResult();
            result.parseError = "Failed to parse LLM response: " + e.getMessage();
            result.rawResponse = response;
            return result;
        }
    }

    /**
     * Extract JSON content from a response that may contain markdown fences or
     * surrounding text.
     */
    static String extractJson(String response) {
        if (response == null || response.isBlank()) return "{}";

        String trimmed = response.trim();

        // Strip <think>...</think> blocks (e.g. Qwen3.5 thinking mode)
        trimmed = trimmed.replaceAll("(?s)<think>.*?</think>", "").trim();
        // Handle unclosed <think> block (thinking ran to max tokens)
        int thinkStart = trimmed.indexOf("<think>");
        if (thinkStart >= 0) {
            trimmed = trimmed.substring(0, thinkStart).trim();
        }

        // Strip markdown code fences
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        // If it starts with {, assume it's already JSON
        if (trimmed.startsWith("{")) return trimmed;

        // Try to find JSON object in the response
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }

    @Override
    public void close() {
        if (llmClient != null) {
            llmClient.close();
        }
    }

    // ========================================================================
    // Data classes (CLI-local, independent of kompile-app-core records)
    // ========================================================================

    public static class ExtractionResult {
        public List<ExtractedEntity> entities = new ArrayList<>();
        public List<ExtractedRelation> relations = new ArrayList<>();
        public String parseError;
        public String rawResponse;

        public boolean hasError() {
            return parseError != null;
        }

        public int entityCount() { return entities.size(); }
        public int relationCount() { return relations.size(); }
    }

    public static class ExtractedEntity {
        public String id;
        public String name;
        public String type;
        public String description;
        public Double confidence;
        public List<String> aliases;
        public Map<String, String> properties;
    }

    public static class ExtractedRelation {
        public String source;
        public String target;
        public String type;
        public String description;
        public Double confidence;
        public Map<String, String> properties;
    }

    // ========================================================================
    // Extraction prompt (mirrors GraphExtractionValidator)
    // ========================================================================

    static final String EXTRACTION_PROMPT_INSTRUCTIONS = """
            You MUST respond with a JSON object matching this exact schema:
            {
              "entities": [
                {
                  "id": "e1",
                  "name": "Acme Corp",
                  "type": "ORGANIZATION",
                  "aliases": ["Acme", "ACME Corporation"],
                  "description": "A technology company",
                  "confidence": 0.95,
                  "properties": {"founded": "2010", "industry": "software"}
                }
              ],
              "relations": [
                {
                  "source": "e1",
                  "target": "e2",
                  "type": "EMPLOYS",
                  "description": "CEO employment relationship",
                  "confidence": 0.95,
                  "properties": {"since": "2015", "role": "CEO"}
                }
              ]
            }

            Rules:
            - Each entity MUST have: id (unique within this extraction), name, type, description
            - Each entity SHOULD have: aliases (alternative names), confidence (0.0-1.0), properties
            - Each relation MUST have: source (entity id), target (entity id), type, description
            - Each relation SHOULD have: confidence (0.0-1.0), properties
            - Entity types should be UPPERCASE (PERSON, ORGANIZATION, LOCATION, CONCEPT, EVENT, PRODUCT, etc.)
            - Relation types should be UPPERCASE with underscores (WORKS_AT, LOCATED_IN, FOUNDED_BY, etc.)
            - Output ONLY valid JSON, no markdown fences, no explanations
            """;
}
