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

    // Extraction model configuration
    private ExtractionModelConfig extractionConfig = ExtractionModelConfig.defaults();

    public Neo4jGraphConstructor(Driver neo4jDriver, LLMChat llmChat, ObjectMapper objectMapper, TextChunker textChunker) {
        this.neo4jDriver = neo4jDriver;
        this.llmChat = llmChat;
        this.objectMapper = objectMapper;
        this.textChunker = textChunker;
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
        SchemaEnforcementMode currentMode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

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
                    ExtractedGraphDTO.ExtractedGraph extractedGraph = objectMapper.readValue(jsonResponse, ExtractedGraphDTO.ExtractedGraph.class);

                    if (currentMode == SchemaEnforcementMode.STRICT && schema != null) {
                        cleanGraph(extractedGraph, schema); // Operates on internal DTOs
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
                } catch (Exception e) {
                    log.error("Failed to process chunk: {} for document: {}", chunk.getId(), doc.getId(), e);
                }
            }
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
            entity.setType(ee.getNodeLabel()); // Now properly set the type from extraction
            entity.setDescription(ee.getDescription());
            entity.setMetadata(ee.getMetadata());
            entity.setConfidence(1.0); // Default confidence for LLM extraction
            return entity;
        }).collect(Collectors.toList()));

        finalGraph.setRelationships(allExtractedRelationships.stream().map(er -> {
            Relationship relationship = new Relationship();
            relationship.setSource(er.getSource());
            relationship.setTarget(er.getTarget());
            relationship.setType(er.getRelationshipType()); // Now properly set the type from extraction
            relationship.setDescription(er.getDescription());
            relationship.setMetadata(er.getMetadata());
            relationship.setWeight(1.0); // Default weight
            relationship.setConfidence(1.0); // Default confidence for LLM extraction
            return relationship;
        }).collect(Collectors.toList()));

        return finalGraph;
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

                    For each entity, provide:
                    - "id": a unique identifier for the entity within this text chunk.
                    - "title": the primary name or title of the entity.
                    - "label": the node label from the schema (e.g., "PERSON", "ORGANIZATION").
                    - "description": a short description of the entity.
                    - "metadata": any other relevant properties as key-value pairs.

                    For each relationship, provide:
                    - "source": the "id" of the source entity.
                    - "target": the "id" of the target entity.
                    - "type": the relationship type from the schema (e.g., "WORKS_AT", "LOCATED_IN").
                    - "description": a natural language description of how the entities are related.
                    - "metadata": any other relevant properties.
                    """.formatted(
                    schema.getNodeTypes().stream().map(nt -> "- Label: " + nt.getLabel() + ", Description: " + nt.getDescription()).collect(Collectors.joining("\n")),
                    schema.getRelationshipTypes().stream().map(rt -> "- Type: " + rt.getType() + ", Description: " + rt.getDescription()).collect(Collectors.joining("\n"))
            );
        } else {
            schemaDescription = "Entities should have an 'id', 'title', 'label' (e.g. PERSON), and 'description'. Relationships should have 'source', 'target', 'type' (e.g. WORKS_AT), and 'description'.";
        }

        return """
               From the text below, extract entities and their relationships based on the provided schema instructions.
               %s

               Text:
               \"""
               %s
               \"""

               Respond with a JSON object containing two keys: "entities" (a list of objects) and "relationships" (a list of objects).
               Example entity: {"id": "e1", "title": "John Doe", "label": "PERSON", "description": "A person.", "metadata": {"email": "john.doe@example.com"}}
               Example relationship: {"source": "e1", "target": "e2", "type": "WORKS_AT", "description": "John Doe works at Acme Corp.", "metadata": {"role": "Engineer"}}
               """.formatted(schemaDescription, text);
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