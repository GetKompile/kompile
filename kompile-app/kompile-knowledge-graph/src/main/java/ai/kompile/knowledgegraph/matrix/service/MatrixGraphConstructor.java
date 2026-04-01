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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Matrix-based implementation of GraphConstructor.
 * <p>
 * Constructs graphs using an LLM for entity/relationship extraction and
 * stores the results in a matrix-based graph backed by a vector store.
 * </p>
 * <p>
 * This is the default graph constructor that works without external dependencies.
 * It is automatically enabled when MatrixGraphStore, LLMChat, and EmbeddingModel beans are all available.
 * </p>
 */
@Service
@ConditionalOnBean({MatrixGraphStore.class, LLMChat.class, EmbeddingModel.class})
@RequiredArgsConstructor
@Slf4j
public class MatrixGraphConstructor implements GraphConstructor {

    private final MatrixGraphStore graphStore;
    private final LLMChat llmChat;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    /**
     * Default edge type for entity relationships.
     */
    private static final String ENTITY_EDGE_TYPE = "RELATED_TO";

    @Override
    public Graph constructGraph(String collectionName) throws IOException {
        log.warn("constructGraph(collectionName) requires an IndexerService to fetch documents. " +
                "Use constructGraphFromDocs for direct document processing.");
        return new Graph();
    }

    @Override
    public Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema schema,
                                         SchemaEnforcementMode enforcementMode) {
        String graphId = "graph-" + UUID.randomUUID();
        AdjacencyMatrixGraph matrixGraph = graphStore.createGraph(graphId, null);

        List<ExtractedGraphDTO.ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> allRelationships = new ArrayList<>();
        SchemaEnforcementMode mode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

        // Extract entities and relationships from each document
        for (RetrievedDoc doc : docs) {
            try {
                String prompt = createExtractionPrompt(doc.getText(), schema);
                String jsonResponse = llmChat.prompt().user(prompt).call().content();

                // Parse JSON response
                ExtractedGraphDTO.ExtractedGraph extracted = parseExtractionResponse(jsonResponse);

                if (extracted != null) {
                    // Apply schema enforcement
                    if (mode == SchemaEnforcementMode.STRICT && schema != null) {
                        cleanGraph(extracted, schema);
                    }

                    // Prefix IDs with document ID to ensure uniqueness
                    String docPrefix = "doc_" + doc.getId() + "_";
                    if (extracted.getEntities() != null) {
                        for (ExtractedGraphDTO.ExtractedEntity entity : extracted.getEntities()) {
                            entity.setId(docPrefix + entity.getId());
                            if (entity.getMetadata() == null) {
                                entity.setMetadata(new HashMap<>());
                            }
                            entity.getMetadata().put("sourceDocumentId", doc.getId());
                            allEntities.add(entity);
                        }
                    }

                    if (extracted.getRelationships() != null) {
                        for (ExtractedGraphDTO.ExtractedRelationship rel : extracted.getRelationships()) {
                            rel.setSource(docPrefix + rel.getSource());
                            rel.setTarget(docPrefix + rel.getTarget());
                            if (rel.getMetadata() == null) {
                                rel.setMetadata(new HashMap<>());
                            }
                            rel.getMetadata().put("sourceDocumentId", doc.getId());
                            allRelationships.add(rel);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to process document: {}", doc.getId(), e);
            }
        }

        // Add entities to matrix graph
        List<String> nodeIds = new ArrayList<>();
        List<String> textForEmbedding = new ArrayList<>();

        for (ExtractedGraphDTO.ExtractedEntity entity : allEntities) {
            MatrixGraphNode node = MatrixGraphNode.builder()
                    .nodeId(entity.getId())
                    .nodeType(entity.getNodeLabel() != null ? entity.getNodeLabel() : "ENTITY")
                    .title(entity.getTitle())
                    .description(entity.getDescription())
                    .metadata(entity.getMetadata())
                    .build();

            graphStore.addNode(graphId, node);
            nodeIds.add(entity.getId());
            textForEmbedding.add(entity.getTitle() + " " +
                    (entity.getDescription() != null ? entity.getDescription() : ""));
        }

        // Generate and store embeddings
        if (!textForEmbedding.isEmpty() && embeddingModel != null) {
            try {
                INDArray embeddings = embeddingModel.embed(textForEmbedding);
                graphStore.storeNodeEmbeddings(graphId, nodeIds, embeddings);
            } catch (Exception e) {
                log.error("Failed to generate embeddings for entities", e);
            }
        }

        // Add relationships to matrix graph
        for (ExtractedGraphDTO.ExtractedRelationship rel : allRelationships) {
            double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;
            String edgeType = rel.getRelationshipType() != null ? rel.getRelationshipType() : ENTITY_EDGE_TYPE;

            graphStore.addEdge(graphId, rel.getSource(), rel.getTarget(), weight, edgeType, false);
        }

        // Save the graph
        try {
            Optional<AdjacencyMatrixGraph> loadedGraph = graphStore.loadGraph(graphId);
            if (loadedGraph.isPresent()) {
                graphStore.saveGraph(loadedGraph.get());
            }
        } catch (IOException e) {
            log.error("Failed to save constructed graph", e);
        }

        // Convert to core Graph model
        return convertToGraph(allEntities, allRelationships);
    }

    /**
     * Constructs a graph and returns both the core Graph model and the matrix graph ID.
     *
     * @param docs            Documents to process
     * @param schema          Optional schema
     * @param enforcementMode Schema enforcement mode
     * @param factSheetId     Optional fact sheet ID for scoping
     * @return GraphConstructionResult with both graph ID and Graph object
     */
    public GraphConstructionResult constructGraphWithId(List<RetrievedDoc> docs, GraphSchema schema,
                                                         SchemaEnforcementMode enforcementMode,
                                                         Long factSheetId) {
        String graphId = "graph-" + (factSheetId != null ? factSheetId + "-" : "") + UUID.randomUUID();
        AdjacencyMatrixGraph matrixGraph = graphStore.createGraph(graphId, factSheetId);

        List<ExtractedGraphDTO.ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> allRelationships = new ArrayList<>();
        SchemaEnforcementMode mode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

        for (RetrievedDoc doc : docs) {
            try {
                String prompt = createExtractionPrompt(doc.getText(), schema);
                String jsonResponse = llmChat.prompt().user(prompt).call().content();

                ExtractedGraphDTO.ExtractedGraph extracted = parseExtractionResponse(jsonResponse);

                if (extracted != null) {
                    if (mode == SchemaEnforcementMode.STRICT && schema != null) {
                        cleanGraph(extracted, schema);
                    }

                    String docPrefix = "doc_" + doc.getId() + "_";
                    if (extracted.getEntities() != null) {
                        for (ExtractedGraphDTO.ExtractedEntity entity : extracted.getEntities()) {
                            entity.setId(docPrefix + entity.getId());
                            if (entity.getMetadata() == null) {
                                entity.setMetadata(new HashMap<>());
                            }
                            entity.getMetadata().put("sourceDocumentId", doc.getId());
                            allEntities.add(entity);
                        }
                    }

                    if (extracted.getRelationships() != null) {
                        for (ExtractedGraphDTO.ExtractedRelationship rel : extracted.getRelationships()) {
                            rel.setSource(docPrefix + rel.getSource());
                            rel.setTarget(docPrefix + rel.getTarget());
                            if (rel.getMetadata() == null) {
                                rel.setMetadata(new HashMap<>());
                            }
                            rel.getMetadata().put("sourceDocumentId", doc.getId());
                            allRelationships.add(rel);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to process document: {}", doc.getId(), e);
            }
        }

        // Add entities
        List<String> nodeIds = new ArrayList<>();
        List<String> textForEmbedding = new ArrayList<>();

        for (ExtractedGraphDTO.ExtractedEntity entity : allEntities) {
            MatrixGraphNode node = MatrixGraphNode.builder()
                    .nodeId(entity.getId())
                    .nodeType(entity.getNodeLabel() != null ? entity.getNodeLabel() : "ENTITY")
                    .title(entity.getTitle())
                    .description(entity.getDescription())
                    .metadata(entity.getMetadata())
                    .factSheetId(factSheetId)
                    .build();

            graphStore.addNode(graphId, node);
            nodeIds.add(entity.getId());
            textForEmbedding.add(entity.getTitle() + " " +
                    (entity.getDescription() != null ? entity.getDescription() : ""));
        }

        // Embeddings
        if (!textForEmbedding.isEmpty() && embeddingModel != null) {
            try {
                INDArray embeddings = embeddingModel.embed(textForEmbedding);
                graphStore.storeNodeEmbeddings(graphId, nodeIds, embeddings);
            } catch (Exception e) {
                log.error("Failed to generate embeddings", e);
            }
        }

        // Add relationships
        for (ExtractedGraphDTO.ExtractedRelationship rel : allRelationships) {
            double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;
            String edgeType = rel.getRelationshipType() != null ? rel.getRelationshipType() : ENTITY_EDGE_TYPE;
            graphStore.addEdge(graphId, rel.getSource(), rel.getTarget(), weight, edgeType, false);
        }

        // Save
        try {
            Optional<AdjacencyMatrixGraph> loadedGraph = graphStore.loadGraph(graphId);
            if (loadedGraph.isPresent()) {
                graphStore.saveGraph(loadedGraph.get());
            }
        } catch (IOException e) {
            log.error("Failed to save graph", e);
        }

        return new GraphConstructionResult(graphId, convertToGraph(allEntities, allRelationships));
    }

    /**
     * Result of graph construction including the graph ID.
     */
    public record GraphConstructionResult(String graphId, Graph graph) {}

    private String createExtractionPrompt(String text, GraphSchema schema) {
        String schemaDescription;
        if (schema != null && schema.getNodeTypes() != null && schema.getRelationshipTypes() != null) {
            schemaDescription = """
                    The entities must conform to the following node types:
                    %s

                    The relationships must conform to the following types:
                    %s

                    For each entity, provide:
                    - "id": a unique identifier
                    - "title": the primary name
                    - "label": the node label from the schema
                    - "description": a short description
                    - "metadata": additional properties

                    For each relationship, provide:
                    - "source": the source entity id
                    - "target": the target entity id
                    - "type": the relationship type from the schema
                    - "description": how they are related
                    - "weight": optional strength (0.0 to 1.0)
                    """.formatted(
                    schema.getNodeTypes().stream()
                            .map(nt -> "- Label: " + nt.getLabel() + ", Description: " + nt.getDescription())
                            .collect(Collectors.joining("\n")),
                    schema.getRelationshipTypes().stream()
                            .map(rt -> "- Type: " + rt.getType() + ", Description: " + rt.getDescription())
                            .collect(Collectors.joining("\n"))
            );
        } else {
            schemaDescription = "Extract entities with 'id', 'title', 'label', and 'description'. " +
                    "Extract relationships with 'source', 'target', 'type', and 'description'.";
        }

        return """
               Extract entities and relationships from the text below.
               %s

               Text:
               \"""
               %s
               \"""

               Respond with a JSON object containing "entities" (list) and "relationships" (list).
               Example: {"entities": [{"id": "e1", "title": "John", "label": "PERSON", "description": "A person"}],
                         "relationships": [{"source": "e1", "target": "e2", "type": "WORKS_AT", "description": "John works at Acme"}]}
               """.formatted(schemaDescription, text);
    }

    private ExtractedGraphDTO.ExtractedGraph parseExtractionResponse(String jsonResponse) {
        try {
            // Try to extract JSON from the response
            String json = jsonResponse;
            int jsonStart = jsonResponse.indexOf('{');
            int jsonEnd = jsonResponse.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json = jsonResponse.substring(jsonStart, jsonEnd + 1);
            }
            return objectMapper.readValue(json, ExtractedGraphDTO.ExtractedGraph.class);
        } catch (Exception e) {
            log.error("Failed to parse LLM extraction response: {}", e.getMessage());
            return null;
        }
    }

    private void cleanGraph(ExtractedGraphDTO.ExtractedGraph graph, GraphSchema schema) {
        if (graph == null || schema == null) {
            return;
        }

        Set<String> allowedLabels = schema.getAllNodeLabels();
        Set<String> allowedTypes = schema.getAllRelationshipTypes();

        // Filter entities
        if (graph.getEntities() != null) {
            graph.setEntities(graph.getEntities().stream()
                    .filter(e -> e.getNodeLabel() == null || allowedLabels.contains(e.getNodeLabel()))
                    .collect(Collectors.toList()));
        }

        // Filter relationships
        Set<String> validEntityIds = graph.getEntities() != null
                ? graph.getEntities().stream().map(ExtractedGraphDTO.ExtractedEntity::getId).collect(Collectors.toSet())
                : Collections.emptySet();

        if (graph.getRelationships() != null) {
            graph.setRelationships(graph.getRelationships().stream()
                    .filter(r -> (r.getRelationshipType() == null || allowedTypes.contains(r.getRelationshipType()))
                            && validEntityIds.contains(r.getSource())
                            && validEntityIds.contains(r.getTarget()))
                    .collect(Collectors.toList()));
        }
    }

    private Graph convertToGraph(List<ExtractedGraphDTO.ExtractedEntity> entities, List<ExtractedGraphDTO.ExtractedRelationship> relationships) {
        Graph graph = new Graph();

        graph.setEntities(entities.stream().map(e -> {
            Entity entity = new Entity();
            entity.setId(e.getId());
            entity.setTitle(e.getTitle());
            entity.setDescription(e.getDescription());
            entity.setMetadata(e.getMetadata());
            return entity;
        }).collect(Collectors.toList()));

        graph.setRelationships(relationships.stream().map(r -> {
            Relationship rel = new Relationship();
            rel.setSource(r.getSource());
            rel.setTarget(r.getTarget());
            rel.setDescription(r.getDescription());
            if (r.getMetadata() == null) {
                r.setMetadata(new HashMap<>());
            }
            r.getMetadata().put("relationshipType", r.getRelationshipType());
            r.getMetadata().put("weight", r.getWeight());
            rel.setMetadata(r.getMetadata());
            return rel;
        }).collect(Collectors.toList()));

        graph.setCommunities(new ArrayList<>());

        return graph;
    }
}
