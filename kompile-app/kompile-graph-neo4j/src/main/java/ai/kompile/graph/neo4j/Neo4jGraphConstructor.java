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

package ai.kompile.graph.neo4j;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import org.springframework.ai.chat.prompt.ChatOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Neo4j-based graph constructor implementation.
 * Bean registration is handled by Neo4jGraphBeans.
 */
@Slf4j
public class Neo4jGraphConstructor implements GraphConstructor {

    private final Driver neo4jDriver;
    private final LLMChat llmChat;
    private final ObjectMapper objectMapper;
    private final TextChunker textChunker;
    private final EntityResolutionService entityResolutionService;

    // Extraction model configuration
    private ExtractionModelConfig extractionConfig = ExtractionModelConfig.defaults();

    public Neo4jGraphConstructor(Driver neo4jDriver, LLMChat llmChat, ObjectMapper objectMapper,
                                 TextChunker textChunker, EntityResolutionService entityResolutionService) {
        this.neo4jDriver = neo4jDriver;
        this.llmChat = llmChat;
        this.objectMapper = objectMapper;
        this.textChunker = textChunker;
        this.entityResolutionService = entityResolutionService;
    }

    @Override
    public void configure(ExtractionModelConfig config) {
        if (config != null) {
            this.extractionConfig = config;
            log.info("Configured entity extraction: provider={}, model={}, temperature={}, maxTokens={}",
                    config.provider(), config.modelName(), config.temperature(), config.maxTokens());
        }
    }

    // Cypher queries remain the same
    private static final String UPSERT_NODE_QUERY = """
            UNWIND $rows AS row
            MERGE (n {id: row.id})
            ON CREATE SET n = row.properties, n.id = row.id
            ON MATCH SET n += row.properties
            WITH n, row.labels AS labels
            CALL apoc.create.addLabels(n, labels) YIELD node
            RETURN elementId(node) AS elementId
            """;

    private static final String UPSERT_RELATIONSHIP_QUERY = """
            UNWIND $rows AS row
            MATCH (start {id: row.source})
            MATCH (end {id: row.target})
            CALL apoc.merge.relationship(start, row.type, {}, row.properties, end, {}) YIELD rel
            RETURN elementId(rel) AS elementId
            """;


    @Override
    public Graph constructGraph(String collectionName) throws IOException {
        log.warn("constructGraph(collectionName) is not fully implemented. It would require an IndexerService to fetch documents.");
        return new Graph();
    }



    @Override
    public Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema schema, SchemaEnforcementMode enforcementMode) {
        List<ExtractedGraphDTO.ExtractedEntity> allExtractedEntities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> allExtractedRelationships = new ArrayList<>();
        List<ExtractionResult> chunkExtractions = new ArrayList<>();
        SchemaEnforcementMode currentMode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

        String modelName = extractionConfig.modelName() != null ? extractionConfig.modelName() : extractionConfig.provider();

        for (RetrievedDoc doc : docs) {
            List<RetrievedDoc> chunks = textChunker.chunk(doc, doc.getMetadata());

            for (RetrievedDoc chunk : chunks) {
                try {
                    String prompt = createExtractionPrompt(chunk.getText(), schema);

                    // Build chat options from extraction config
                    ChatOptions.Builder optionsBuilder = ChatOptions.builder();
                    if (extractionConfig.temperature() != null) {
                        optionsBuilder.temperature(extractionConfig.temperature());
                    }
                    if (extractionConfig.maxTokens() != null) {
                        optionsBuilder.maxTokens(extractionConfig.maxTokens());
                    }
                    ChatOptions options = optionsBuilder.build();

                    String jsonResponse = llmChat.prompt()
                            .user(prompt)
                            .options(options)
                            .call()
                            .content();

                    // Try parsing as new standardized format first
                    ExtractionResult chunkResult = tryParseStandardFormat(jsonResponse, chunk.getId(), doc.getId(), modelName);

                    if (chunkResult != null) {
                        chunkExtractions.add(chunkResult);
                    } else {
                        // Fallback to legacy ExtractedGraphDTO format
                        ExtractedGraphDTO.ExtractedGraph extractedGraph = objectMapper.readValue(jsonResponse, ExtractedGraphDTO.ExtractedGraph.class);

                        if (currentMode == SchemaEnforcementMode.STRICT && schema != null) {
                            cleanGraph(extractedGraph, schema);
                        }

                        String chunkPrefix = "chunk_" + chunk.getId() + "_";
                        if (extractedGraph.getEntities() != null) {
                            for (ExtractedGraphDTO.ExtractedEntity entity : extractedGraph.getEntities()) {
                                entity.setId(chunkPrefix + entity.getId());
                                if (entity.getMetadata() == null) {
                                    entity.setMetadata(new HashMap<>());
                                }
                                entity.getMetadata().put("sourceChunkId", chunk.getId());
                                entity.getMetadata().put("sourceDocumentId", doc.getId());
                                allExtractedEntities.add(entity);
                            }
                        }
                        if (extractedGraph.getRelationships() != null) {
                            for (ExtractedGraphDTO.ExtractedRelationship rel : extractedGraph.getRelationships()) {
                                rel.setSource(chunkPrefix + rel.getSource());
                                rel.setTarget(chunkPrefix + rel.getTarget());
                                if (rel.getMetadata() == null) {
                                    rel.setMetadata(new HashMap<>());
                                }
                                rel.getMetadata().put("sourceChunkId", chunk.getId());
                                rel.getMetadata().put("sourceDocumentId", doc.getId());
                                allExtractedRelationships.add(rel);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to process chunk: {} for document: {}", chunk.getId(), doc.getId(), e);
                }
            }
        }

        // If we have new-format extractions, run entity resolution and convert
        if (!chunkExtractions.isEmpty()) {
            ExtractionResult resolved = entityResolutionService.resolve(chunkExtractions);
            Graph resolvedGraph = GraphExtractionValidator.toGraph(resolved);

            // Also write resolved entities to Neo4j via legacy path
            List<ExtractedGraphDTO.ExtractedEntity> resolvedLegacyEntities = new ArrayList<>();
            for (ExtractedEntity ee : resolved.entities()) {
                ExtractedGraphDTO.ExtractedEntity legacy = new ExtractedGraphDTO.ExtractedEntity();
                legacy.setId(ee.id());
                legacy.setTitle(ee.name());
                legacy.setNodeLabel(ee.type());
                legacy.setDescription(ee.description());
                Map<String, Object> meta = new HashMap<>();
                if (ee.properties() != null) meta.putAll(ee.properties());
                if (ee.aliases() != null && !ee.aliases().isEmpty()) meta.put("aliases", String.join(",", ee.aliases()));
                legacy.setMetadata(meta);
                resolvedLegacyEntities.add(legacy);
            }
            allExtractedEntities.addAll(resolvedLegacyEntities);

            List<ExtractedGraphDTO.ExtractedRelationship> resolvedLegacyRels = new ArrayList<>();
            for (ExtractedRelation er : resolved.relations()) {
                ExtractedGraphDTO.ExtractedRelationship legacy = new ExtractedGraphDTO.ExtractedRelationship();
                legacy.setSource(er.source());
                legacy.setTarget(er.target());
                legacy.setRelationshipType(er.type());
                legacy.setDescription(er.description());
                legacy.setConfidence(er.confidence());
                Map<String, Object> meta = new HashMap<>();
                if (er.properties() != null) meta.putAll(er.properties());
                legacy.setMetadata(meta);
                resolvedLegacyRels.add(legacy);
            }
            allExtractedRelationships.addAll(resolvedLegacyRels);
        }

        if (!allExtractedEntities.isEmpty()) {
            writeEntitiesToNeo4j(allExtractedEntities, schema);
        }
        if (!allExtractedRelationships.isEmpty()) {
            writeRelationshipsToNeo4j(allExtractedRelationships, schema);
        }

        // Convert internal DTOs to user's DTOs for the returned Graph object
        Graph finalGraph = new Graph();
        finalGraph.setEntities(allExtractedEntities.stream().map(ee -> {
            Entity entity = new Entity();
            entity.setId(ee.getId());
            entity.setTitle(ee.getTitle());
            entity.setType(ee.getNodeLabel());
            entity.setDescription(ee.getDescription());
            entity.setMetadata(ee.getMetadata());
            entity.setConfidence(ee.getMetadata() != null && ee.getMetadata().containsKey("confidence")
                    ? Double.parseDouble(ee.getMetadata().get("confidence").toString()) : 1.0);
            return entity;
        }).collect(Collectors.toList()));

        finalGraph.setRelationships(allExtractedRelationships.stream().map(er -> {
            Relationship relationship = new Relationship();
            relationship.setSource(er.getSource());
            relationship.setTarget(er.getTarget());
            relationship.setType(er.getRelationshipType());
            relationship.setDescription(er.getDescription());
            relationship.setMetadata(er.getMetadata());
            relationship.setWeight(er.getConfidence() != null ? er.getConfidence() : 1.0);
            relationship.setConfidence(er.getConfidence() != null ? er.getConfidence() : 1.0);
            return relationship;
        }).collect(Collectors.toList()));

        return finalGraph;
    }

    private ExtractionResult tryParseStandardFormat(String jsonResponse, String chunkId, String docId, String model) {
        try {
            // Clean markdown fences if present
            String clean = jsonResponse.trim();
            if (clean.startsWith("```json")) clean = clean.substring(7);
            else if (clean.startsWith("```")) clean = clean.substring(3);
            if (clean.endsWith("```")) clean = clean.substring(0, clean.length() - 3);
            clean = clean.trim();

            ExtractionResult result = GraphExtractionValidator.fromJson(clean);
            // Check if it looks like our format (has 'entities' with 'name' field, not 'title')
            if (result.entities() != null && !result.entities().isEmpty() && result.entities().get(0).name() != null) {
                // Add metadata
                ExtractionMetadata metadata = ExtractionMetadata.forChunk(chunkId, docId, model);
                return new ExtractionResult(result.schema(), result.entities(), result.relations(), metadata);
            }
        } catch (Exception e) {
            // Not in standard format, that's fine
        }
        return null;
    }

    private String createExtractionPrompt(String text, GraphSchema schema) throws JsonProcessingException {
        // Use custom prompt if configured
        if (extractionConfig.customPrompt() != null && !extractionConfig.customPrompt().isEmpty()) {
            return extractionConfig.customPrompt()
                    .replace("{{TEXT}}", text)
                    .replace("{text}", text);
        }

        String schemaDescription;
        if (schema != null && schema.getNodeTypes() != null && schema.getRelationshipTypes() != null) {
            schemaDescription = """
                    The entities must conform to the following node types:
                    %s

                    The relationships must conform to the following types:
                    %s
                    """.formatted(
                    schema.getNodeTypes().stream().map(nt -> "- Label: " + nt.getLabel() + ", Description: " + nt.getDescription()).collect(Collectors.joining("\n")),
                    schema.getRelationshipTypes().stream().map(rt -> "- Type: " + rt.getType() + ", Description: " + rt.getDescription()).collect(Collectors.joining("\n"))
            );
        } else {
            schemaDescription = "";
        }

        return """
               From the text below, extract entities and their relationships.
               %s

               %s

               Text:
               \"""
               %s
               \"""
               """.formatted(schemaDescription, GraphExtractionValidator.getExtractionPromptInstructions(), text);
    }

    private void cleanGraph(ExtractedGraphDTO.ExtractedGraph graph, GraphSchema schema) {
        if (graph == null || schema == null) {
            return;
        }

        Map<String, NodeType> nodeTypeMap = schema.getNodeTypeMap();
        Map<String, RelationshipType> relationshipTypeMap = schema.getRelationshipTypeMap();
        Map<String, Set<String>> allowedNodePropsMap = schema.getNodePropertiesByName();
        Map<String, Set<String>> allowedRelPropsMap = schema.getRelationshipPropertiesByName();

        List<ExtractedGraphDTO.ExtractedEntity> filteredEntities = new ArrayList<>();
        if (!CollectionUtils.isEmpty(graph.getEntities())) {
            for (ExtractedGraphDTO.ExtractedEntity entity : graph.getEntities()) {
                NodeType nt = nodeTypeMap.get(entity.getNodeLabel()); // Use internal DTO's nodeLabel
                if (nt != null) {
                    if (entity.getMetadata() == null) entity.setMetadata(new HashMap<>());
                    Map<String, Object> filteredProps = new HashMap<>();
                    Set<String> allowedProps = allowedNodePropsMap.getOrDefault(entity.getNodeLabel(), Collections.emptySet());
                    for (Map.Entry<String, Object> entry : entity.getMetadata().entrySet()) {
                        if (allowedProps.contains(entry.getKey())) {
                            filteredProps.put(entry.getKey(), entry.getValue());
                        }
                    }
                    entity.setMetadata(filteredProps);
                    filteredEntities.add(entity);
                } else {
                    log.warn("Removing entity with invalid label: {} (ID: {})", entity.getNodeLabel(), entity.getId());
                }
            }
        }
        graph.setEntities(filteredEntities);
        Set<String> validEntityIds = filteredEntities.stream().map(ExtractedGraphDTO.ExtractedEntity::getId).collect(Collectors.toSet());

        List<ExtractedGraphDTO.ExtractedRelationship> filteredRelationships = new ArrayList<>();
        if (!CollectionUtils.isEmpty(graph.getRelationships())) {
            for (ExtractedGraphDTO.ExtractedRelationship rel : graph.getRelationships()) {
                RelationshipType rt = relationshipTypeMap.get(rel.getRelationshipType()); // Use internal DTO's relationshipType
                if (rt != null && validEntityIds.contains(rel.getSource()) && validEntityIds.contains(rel.getTarget())) {
                    if (rel.getMetadata() == null) rel.setMetadata(new HashMap<>());
                    Map<String, Object> filteredProps = new HashMap<>();
                    Set<String> allowedProps = allowedRelPropsMap.getOrDefault(rel.getRelationshipType(), Collections.emptySet());
                    for (Map.Entry<String, Object> entry : rel.getMetadata().entrySet()) {
                        if (allowedProps.contains(entry.getKey())) {
                            filteredProps.put(entry.getKey(), entry.getValue());
                        }
                    }
                    rel.setMetadata(filteredProps);
                    filteredRelationships.add(rel);
                } else {
                    log.warn("Removing relationship of type: {} (Source: {}, Target: {}) due to invalid type or missing/invalid nodes", rel.getRelationshipType(), rel.getSource(), rel.getTarget());
                }
            }
        }
        graph.setRelationships(filteredRelationships);
        log.info("After schema cleaning: {} entities, {} relationships remaining.", graph.getEntities().size(), graph.getRelationships().size());
    }

    private void writeEntitiesToNeo4j(List<ExtractedGraphDTO.ExtractedEntity> entities, GraphSchema schema) {
        if (entities.isEmpty()) return;
        List<Map<String, Object>> entityRows = entities.stream()
                .map(e -> {
                    Map<String, Object> props = new HashMap<>(e.getMetadata() != null ? e.getMetadata() : Collections.emptyMap());
                    props.put("title", e.getTitle());
                    props.put("description", e.getDescription());
                    props.put("id", e.getId());

                    List<String> labels = new ArrayList<>();
                    labels.add("__Entity__");
                    if (e.getNodeLabel() != null && schema != null && schema.getAllNodeLabels().contains(e.getNodeLabel())) {
                        labels.add(e.getNodeLabel());
                    } else if (e.getNodeLabel() != null) {
                        log.warn("Entity label '{}' for id '{}' not found in schema or schema is null. Using only base label.", e.getNodeLabel(), e.getId());
                    } else {
                        log.warn("Entity with id '{}' has a null nodeLabel from extraction. Using only base label.", e.getId());
                    }
                    return Map.of("id", e.getId(), "labels", labels, "properties", props);
                })
                .collect(Collectors.toList());
        try (Session session = neo4jDriver.session()) {
            session.run(UPSERT_NODE_QUERY, Values.parameters("rows", entityRows));
            log.info("Successfully upserted {} entities.", entities.size());
        } catch (Exception ex) {
            log.error("Failed to upsert entities to Neo4j", ex);
        }
    }

    private void writeRelationshipsToNeo4j(List<ExtractedGraphDTO.ExtractedRelationship> relationships, GraphSchema schema) {
        if (relationships.isEmpty()) return;
        List<Map<String, Object>> relationshipRows = relationships.stream()
                .filter(r -> {
                    if (r.getRelationshipType() == null) {
                        log.warn("Relationship with null type (Source: {}, Target: {}) will be skipped.", r.getSource(), r.getTarget());
                        return false;
                    }
                    if (schema != null && !schema.getAllRelationshipTypes().contains(r.getRelationshipType())) {
                        log.warn("Relationship type '{}' for source '{}', target '{}' not found in schema or schema is null. Skipping.", r.getRelationshipType(), r.getSource(), r.getTarget());
                        return false;
                    }
                    return true;
                })
                .map(r -> {
                    Map<String, Object> props = new HashMap<>(r.getMetadata() != null ? r.getMetadata() : Collections.emptyMap());
                    props.put("description", r.getDescription());
                    return Map.of(
                            "source", r.getSource(),
                            "target", r.getTarget(),
                            "type", r.getRelationshipType(), // Use the type from ExtractedGraphDTO.ExtractedRelationship
                            "properties", props);
                })
                .collect(Collectors.toList());
        if (relationshipRows.isEmpty()) {
            log.info("No valid relationships to write after schema filtering.");
            return;
        }
        try (Session session = neo4jDriver.session()) {
            session.run(UPSERT_RELATIONSHIP_QUERY, Values.parameters("rows", relationshipRows));
            log.info("Successfully upserted {} relationships.", relationshipRows.size());
        } catch (Exception ex) {
            log.error("Failed to upsert relationships to Neo4j", ex);
        }
    }
}