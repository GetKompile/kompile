package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphAdditionCalibration;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.crawl.graph.passes.GraphEntityCandidateProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAdditionCalibrationBehaviorTest {

    @Test
    void profilesChangeSoftCandidateRecallButNotHardGraphInvariants() {
        Map<GraphAdditionCalibration.Profile, Integer> admitted = new EnumMap<>(GraphAdditionCalibration.Profile.class);

        for (GraphAdditionCalibration.Profile profile : GraphAdditionCalibration.Profile.values()) {
            GraphExtractionConfig config = config(profile);
            Graph candidateGraph = graph(entity("candidate", "alxzz", "ORGANIZATION"));
            int count = new GraphEntityCandidateProvider(candidateGraph,
                    config.getEffectiveCandidateMinScore()).candidatesFor(
                    "alpha", "ORGANIZATION", PassContext.forChunk("chunk", "doc", "alpha"), 8).size();
            admitted.put(profile, count);

            assertHardValidatorRejectionsRemain(profile);
            assertMustNotMergeRemainsEnforced(config);
            assertEquals(GraphAdditionCalibration.HardInvariant.values().length,
                    config.getResolvedGraphAdditionCalibration().hardInvariants().size());
        }

        assertTrue(admitted.get(GraphAdditionCalibration.Profile.RECALL_BIASED)
                >= admitted.get(GraphAdditionCalibration.Profile.STANDARD));
        assertTrue(admitted.get(GraphAdditionCalibration.Profile.STANDARD)
                >= admitted.get(GraphAdditionCalibration.Profile.CONSERVATIVE));
        assertTrue(admitted.get(GraphAdditionCalibration.Profile.RECALL_BIASED)
                > admitted.get(GraphAdditionCalibration.Profile.CONSERVATIVE),
                "the production candidate provider consumes the calibrated soft threshold");
    }

    private static void assertHardValidatorRejectionsRemain(GraphAdditionCalibration.Profile profile) {
        ExtractedEntity person = new ExtractedEntity("person", "A", "PERSON", List.of(),
                "", 0.99, Map.of());
        ExtractedEntity target = new ExtractedEntity("target", "B", "WRONG_TYPE", List.of(),
                "evidence", 0.99, Map.of());
        ExtractedRelation incomplete = new ExtractedRelation("missing", "target", "APPROVED_BY",
                "evidence", 0.99, Map.of(), null);
        ExtractionResult structurallyInvalid = ExtractionResult.of(List.of(person, target),
                List.of(incomplete), null);
        assertFalse(GraphExtractionValidator.validate(structurallyInvalid,
                GraphExtractionValidationPolicy.defaults(), null).valid(),
                profile + " must not admit incomplete endpoints or missing required evidence descriptions");

        GraphExtractionValidationPolicy schemaPolicy = GraphExtractionValidationPolicy.builder()
                .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                .build();
        ExtractedRelation wrongSchema = new ExtractedRelation("person", "target", "APPROVED_BY",
                "evidence", 0.99, Map.of(), null);
        assertFalse(GraphExtractionValidator.validate(
                ExtractionResult.of(List.of(person, target), List.of(wrongSchema), null),
                schemaPolicy, null).valid(), profile + " must not relax schema validity");
    }

    private static void assertMustNotMergeRemainsEnforced(GraphExtractionConfig config) {
        Entity anchor = entity("sarah-1", "Sarah Chen", "PERSON");
        anchor.setMetadata(Map.of("email", "sarah.one@example.com"));
        Entity forbidden = entity("sarah-2", "Sarah Chen", "PERSON");
        Graph graph = graph(anchor, forbidden);
        Relationship relation = new Relationship();
        relation.setSource("sarah-1");
        relation.setTarget("sarah-2");
        relation.setType("MUST_NOT_MERGE");
        graph.getRelationships().add(relation);

        var candidates = new GraphEntityCandidateProvider(graph,
                config.getEffectiveCandidateMinScore()).candidatesFor(
                "Sarah Chen <sarah.one@example.com>", "PERSON",
                PassContext.forChunk("chunk", "doc", "Sarah Chen"), 8);
        assertEquals(List.of("sarah-1"), candidates.stream().map(candidate -> candidate.id()).toList());
    }

    private static GraphExtractionConfig config(GraphAdditionCalibration.Profile profile) {
        GraphExtractionConfig config = new GraphExtractionConfig();
        config.setGraphAdditionCalibration(GraphAdditionCalibration.builder().profile(profile).build());
        return config;
    }

    private static Entity entity(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setAliases(new ArrayList<>());
        return entity;
    }

    private static Graph graph(Entity... entities) {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(List.of(entities)));
        graph.setRelationships(new ArrayList<>());
        return graph;
    }
}
