package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicGraphSchemaInferencerTest {

    @Test
    void derivesTypesAndDirectedPatternsFromSourceNativeGraphOutput() {
        Entity message = entity("message-1", "EXTRACTOR_MESSAGE");
        Entity actor = entity("actor-1", "DETERMINISTIC_ACTOR");
        Relationship emittedBy = relationship(
                "message-1", "actor-1", "EMITTED_BY");
        Graph graph = Graph.builder()
                .entities(new ArrayList<>(List.of(message, actor)))
                .relationships(new ArrayList<>(List.of(emittedBy)))
                .build();

        GraphSchema schema = DeterministicGraphSchemaInferencer.infer(graph);

        assertEquals(List.of("DETERMINISTIC_ACTOR", "EXTRACTOR_MESSAGE"),
                schema.getNodeTypes().stream().map(NodeType::getLabel).toList());
        assertEquals(List.of("EMITTED_BY"),
                schema.getRelationshipTypes().stream()
                        .map(RelationshipType::getType).toList());
        assertEquals(List.of(
                "(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"),
                schema.getPatterns());
    }

    @Test
    void preservesEveryTypeEmittedByTheDeterministicGraph() {
        Graph graph = Graph.builder()
                .entities(new ArrayList<>(List.of(
                        entity("left", "ENTITY"),
                        entity("right", "UNKNOWN"))))
                .relationships(new ArrayList<>(List.of(
                        relationship("left", "right", "RELATED_TO"))))
                .build();

        GraphSchema schema = DeterministicGraphSchemaInferencer.infer(graph);

        assertEquals(List.of("ENTITY", "UNKNOWN"),
                schema.getNodeTypes().stream().map(NodeType::getLabel).toList());
        assertEquals(List.of("RELATED_TO"),
                schema.getRelationshipTypes().stream()
                        .map(RelationshipType::getType).toList());
        assertEquals(List.of("(ENTITY)-[:RELATED_TO]->(UNKNOWN)"), schema.getPatterns());
    }

    private static Entity entity(String id, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setType(type);
        return entity;
    }

    private static Relationship relationship(String source, String target, String type) {
        Relationship relationship = new Relationship();
        relationship.setSource(source);
        relationship.setTarget(target);
        relationship.setType(type);
        return relationship;
    }
}
