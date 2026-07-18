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

package ai.kompile.core.graphrag.format;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates, imports, and exports graph extraction data using the standardized schema.
 */
public final class GraphExtractionValidator {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private GraphExtractionValidator() {
    }

    /**
     * Validation result with errors.
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    /**
     * Validate an extraction result for completeness and consistency.
     */
    public static ValidationResult validate(ExtractionResult result) {
        List<String> errors = new ArrayList<>();

        if (result == null) {
            return ValidationResult.fail(List.of("ExtractionResult is null"));
        }

        Set<String> entityIds = new HashSet<>();
        for (int i = 0; i < result.entities().size(); i++) {
            ExtractedEntity entity = result.entities().get(i);
            if (entity.id() == null || entity.id().isBlank()) {
                errors.add("Entity at index " + i + " has null or blank id");
            } else if (!entityIds.add(entity.id())) {
                errors.add("Duplicate entity id: " + entity.id());
            }
            if (entity.name() == null || entity.name().isBlank()) {
                errors.add("Entity '" + entity.id() + "' has null or blank name");
            }
            if (entity.type() == null || entity.type().isBlank()) {
                errors.add("Entity '" + entity.id() + "' has null or blank type");
            }
            if (entity.confidence() != null && (entity.confidence() < 0.0 || entity.confidence() > 1.0)) {
                errors.add("Entity '" + entity.id() + "' has confidence out of range [0,1]: " + entity.confidence());
            }
        }

        for (int i = 0; i < result.relations().size(); i++) {
            ExtractedRelation rel = result.relations().get(i);
            if (rel.source() == null || rel.source().isBlank()) {
                errors.add("Relation at index " + i + " has null or blank source");
            } else if (!entityIds.contains(rel.source())) {
                errors.add("Relation at index " + i + " references unknown source entity: " + rel.source());
            }
            if (rel.target() == null || rel.target().isBlank()) {
                errors.add("Relation at index " + i + " has null or blank target");
            } else if (!entityIds.contains(rel.target())) {
                errors.add("Relation at index " + i + " references unknown target entity: " + rel.target());
            }
            if (rel.type() == null || rel.type().isBlank()) {
                errors.add("Relation at index " + i + " has null or blank type");
            }
            if (rel.confidence() != null && (rel.confidence() < 0.0 || rel.confidence() > 1.0)) {
                errors.add("Relation at index " + i + " has confidence out of range [0,1]: " + rel.confidence());
            }
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    /**
     * Export an ExtractionResult to JSON string.
     */
    public static String toJson(ExtractionResult result) throws JsonProcessingException {
        return MAPPER.writeValueAsString(result);
    }

    /**
     * Import an ExtractionResult from JSON string.
     */
    public static ExtractionResult fromJson(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, ExtractionResult.class);
    }

    /**
     * Convert an ExtractionResult to the core Graph model.
     */
    public static Graph toGraph(ExtractionResult result) {
        Graph graph = new Graph();

        if (result.metadata() != null) {
            graph.setId(result.metadata().graphId());
            graph.setParentGraphId(result.metadata().parentGraphId());
        }

        List<Entity> entities = new ArrayList<>();
        for (ExtractedEntity ee : result.entities()) {
            Entity entity = new Entity();
            entity.setId(ee.id());
            entity.setTitle(ee.name());
            entity.setType(ee.type());
            entity.setDescription(ee.description());
            entity.setConfidence(ee.confidence());

            Map<String, Object> metadata = new HashMap<>();
            if (ee.properties() != null) {
                metadata.putAll(ee.properties());
            }
            if (ee.aliases() != null && !ee.aliases().isEmpty()) {
                metadata.put("aliases", ee.aliases());
            }
            entity.setMetadata(metadata);
            entities.add(entity);
        }
        graph.setEntities(entities);

        List<Relationship> relationships = new ArrayList<>();
        for (ExtractedRelation er : result.relations()) {
            Relationship rel = new Relationship();
            rel.setSource(er.source());
            rel.setTarget(er.target());
            rel.setType(er.type());
            rel.setDescription(er.description());
            rel.setConfidence(er.confidence());
            rel.setWeight(er.confidence());

            Map<String, Object> metadata = new HashMap<>();
            if (er.properties() != null) {
                metadata.putAll(er.properties());
            }
            rel.setMetadata(metadata);
            relationships.add(rel);
        }
        graph.setRelationships(relationships);

        return graph;
    }

    /**
     * Convert a core Graph model to an ExtractionResult.
     */
    @SuppressWarnings("unchecked")
    public static ExtractionResult fromGraph(Graph graph, String model) {
        List<ExtractedEntity> entities = new ArrayList<>();
        if (graph.getEntities() != null) {
            for (Entity e : graph.getEntities()) {
                List<String> aliases = List.of();
                Map<String, String> props = new HashMap<>();
                if (e.getMetadata() != null) {
                    Object aliasObj = e.getMetadata().get("aliases");
                    if (aliasObj instanceof List<?>) {
                        aliases = ((List<?>) aliasObj).stream()
                                .map(Object::toString)
                                .toList();
                    }
                    for (Map.Entry<String, Object> entry : e.getMetadata().entrySet()) {
                        if (!"aliases".equals(entry.getKey()) && entry.getValue() != null) {
                            props.put(entry.getKey(), entry.getValue().toString());
                        }
                    }
                }
                entities.add(new ExtractedEntity(
                        e.getId(), e.getTitle(), e.getType(),
                        aliases, e.getDescription(), e.getConfidence(), props
                ));
            }
        }

        List<ExtractedRelation> relations = new ArrayList<>();
        if (graph.getRelationships() != null) {
            for (Relationship r : graph.getRelationships()) {
                Map<String, String> props = new HashMap<>();
                if (r.getMetadata() != null) {
                    for (Map.Entry<String, Object> entry : r.getMetadata().entrySet()) {
                        if (entry.getValue() != null) {
                            props.put(entry.getKey(), entry.getValue().toString());
                        }
                    }
                }
                relations.add(new ExtractedRelation(
                        r.getSource(), r.getTarget(), r.getType(),
                        r.getDescription(), r.getConfidence(), props
                ));
            }
        }

        return ExtractionResult.of(entities, relations,
                GraphExtractionSchema.ExtractionMetadata.forChunkInGraph(null, null, model, graph.getId(), graph.getParentGraphId()));
    }

    /**
     * Build the extraction prompt instruction that forces conformant JSON output.
     */
    public static String getExtractionPromptInstructions() {
        return """
                Extract a knowledge graph ONLY from the SOURCE TEXT supplied after these instructions.
                Never copy names, facts, types, or relationships from these instructions, and never invent facts.
                If the source supports no graph facts, return {"entities":[],"relations":[]}.

                Return exactly one valid JSON object with top-level arrays "entities" and "relations".
                Start from this empty structure and populate it only with source-supported facts:
                {"entities":[],"relations":[]}

                Rules:
                - Each entity MUST have string fields: id (unique within this extraction), name, type, description.
                - Entity name and description MUST be grounded in the source text.
                - Each entity MUST have: confidence — a float in [0.0, 1.0] calibrated to how certain the text supports this entity.
                  Use 0.9-1.0 for entities stated explicitly and unambiguously; 0.7-0.9 for entities strongly implied;
                  0.5-0.7 for uncertain or indirect mentions; below 0.5 for speculative references.
                - Each entity MAY have: aliases (string array) and properties (object).
                - Each relation MUST have string fields: source (entity id), target (entity id), type, description.
                - Relation source and target MUST refer to ids in the entities array.
                - Each relation MUST have: confidence — a float in [0.0, 1.0] calibrated to how certain the text supports this relation.
                  Use 0.85-1.0 for relations stated directly; 0.6-0.85 for inferred relations; 0.4-0.6 for possible but uncertain;
                  below 0.4 for speculative links.
                - Each relation MAY have: properties (object).
                - Entity types MUST be UPPERCASE category names.
                - Relation types MUST be UPPERCASE_WITH_UNDERSCORES.
                - Every non-structural value MUST be supported by the source text.
                - Output ONLY valid JSON, with no markdown fences or explanations.
                """;
    }

    /**
     * Build extraction prompt instructions for a multi-chunk prompt where each chunk is
     * delimited by a stable {@code ===== CHUNK k | source=&lt;id&gt; =====} marker.
     *
     * <p>The model is told to emit a {@code "chunkId"} property on every extracted entity
     * and relation that matches the source identifier from the nearest preceding chunk
     * header. This lets the parser attribute results back to the originating document even
     * when the model merges entities across chunk boundaries.</p>
     */
    public static String getMultiChunkExtractionPromptInstructions() {
        return """
                Extract a knowledge graph ONLY from the MULTIPLE SOURCE TEXT chunks provided below.
                Each chunk is preceded by a header line of the form:
                  ===== CHUNK <k> | source=<chunkId> =====

                Never copy names, facts, types, or relationships from these instructions, and never invent facts.
                If the chunks support no graph facts, return {"entities":[],"relations":[]}.

                Return exactly one valid JSON object with top-level arrays "entities" and "relations".
                Start from this empty structure and populate it only with chunk-supported facts:
                {"entities":[],"relations":[]}

                Rules:
                - Each entity MUST have string fields: id (unique within this extraction), name, type, description, chunkId.
                - Entity name and description MUST be grounded in a source chunk.
                - Each entity chunkId MUST equal the source value from its nearest preceding chunk header.
                - Each entity MUST have: confidence — a float in [0.0, 1.0] calibrated to how certain the text supports this entity.
                  Use 0.9-1.0 for entities stated explicitly and unambiguously; 0.7-0.9 for entities strongly implied;
                  0.5-0.7 for uncertain or indirect mentions; below 0.5 for speculative references.
                - Each entity MAY have: aliases (string array) and properties (object).
                - Each relation MUST have string fields: source (entity id), target (entity id), type, description, chunkId.
                - Relation source and target MUST refer to ids in the entities array.
                - Each relation chunkId MUST equal the source value from its nearest preceding chunk header.
                - Each relation MUST have: confidence — a float in [0.0, 1.0] calibrated to how certain the text supports this relation.
                  Use 0.85-1.0 for relations stated directly; 0.6-0.85 for inferred relations; 0.4-0.6 for possible but uncertain;
                  below 0.4 for speculative links.
                - Each relation MAY have: properties (object).
                - Entity types MUST be UPPERCASE category names.
                - Relation types MUST be UPPERCASE_WITH_UNDERSCORES.
                - Every non-structural value MUST be supported by a source chunk.
                - Output ONLY valid JSON, with no markdown fences or explanations.
                - If an entity or relation spans multiple chunks, use the chunkId where it was first mentioned.
                """;
    }
}
