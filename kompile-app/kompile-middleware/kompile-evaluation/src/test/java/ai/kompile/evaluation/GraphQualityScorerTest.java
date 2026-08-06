package ai.kompile.evaluation;

import ai.kompile.core.evaluation.graph.GraphDecisionTraceEvent;
import ai.kompile.core.evaluation.graph.GraphMissReason;
import ai.kompile.core.evaluation.graph.GraphMissStage;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.EntityMissFacet;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedEntity;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedGraph;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedRelation;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.RelationMissFacet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQualityScorerTest {

    @Test
    void reportsFalseNegativeFacetsSeparatelyFromUnexpectedActualAtoms() {
        GraphQualityScorer.Evaluation evaluation = new GraphQualityScorer().score(
                expected(), actual(), profile());

        assertEquals(3, evaluation.summary().goldMisses());
        assertEquals(1, evaluation.summary().entityMissFacets().get(EntityMissFacet.WRONG_TYPE));
        assertEquals(1, evaluation.summary().entityMissFacets().get(EntityMissFacet.ABSENT_ENTITY));
        assertEquals(1, evaluation.summary().entityMissFacets().get(EntityMissFacet.UNEXPECTED_ENTITY));
        assertEquals(1, evaluation.summary().relationMissFacets().get(RelationMissFacet.WRONG_TYPE));
        assertEquals(3, evaluation.summary().unattributedGoldMisses());
        assertEquals(0.0d, evaluation.summary().attributionCoverage(), 1.0e-9);
        assertEquals(1, evaluation.quality().entities().falsePositives(),
                "the unexpected entity remains a false positive, not a gold miss");

        GraphQualityScorer.FactComparison facts = evaluation.facts();
        assertEquals(new GraphQualityScorer.FactCounts(
                        3, 3, 1, 2, 2, 1.0d / 3.0d, 1.0d / 3.0d, 1.0d / 3.0d),
                facts.entityCounts());
        assertEquals(new GraphQualityScorer.FactCounts(
                        1, 1, 0, 1, 1, 0.0d, 0.0d, 0.0d),
                facts.relationCounts());
        assertEquals(List.of(
                        new GraphQualityScorer.EntityFact("alpha", "Alpha Account", "ACCOUNT"),
                        new GraphQualityScorer.EntityFact("gamma", "Gamma Process", "PROCESS")),
                facts.missedEntities());
        assertEquals(List.of(
                        new GraphQualityScorer.EntityFact("a", "Alpha Account", "ORGANIZATION"),
                        new GraphQualityScorer.EntityFact("extra", "Unexpected", "ACCOUNT")),
                facts.extraEntities());
        assertEquals(List.of(new GraphQualityScorer.RelationFact("alpha", "OWNS", "beta")),
                facts.missedRelations());
        assertEquals(List.of(new GraphQualityScorer.RelationFact("a", "MANAGES", "b")),
                facts.extraRelations());
        assertTrue(facts.schemaAdherence().valid());
        assertEquals("entities expected=3 extracted=3 matched=1 missed=2 extra=2; "
                        + "relations expected=1 extracted=1 matched=0 missed=1 extra=1; "
                        + "schema=valid violations=0",
                facts.summary());
    }

    @Test
    void reportsConcreteSchemaViolationsUsingOnlyEntityAndRelationTerminology() {
        Graph graph = actual();
        graph.getRelationships().add(relation("missing", "UNKNOWN", "b"));
        CompositeGraphQualityEvaluator.ScoringProfile strictSchema =
                new CompositeGraphQualityEvaluator.ScoringProfile(
                        0.30, 0.35, 0.20, 0.15, 0.80,
                        Set.of("ACCOUNT", "PROCESS"), Set.of("OWNS"), Set.of());

        GraphQualityScorer.SchemaAdherence schema = new GraphQualityScorer().score(
                expected(), graph, strictSchema).facts().schemaAdherence();

        assertFalse(schema.valid());
        assertTrue(schema.violations().stream().anyMatch(value ->
                value.equals("extracted entity a uses undeclared type ORGANIZATION")));
        assertTrue(schema.violations().stream().anyMatch(value ->
                value.contains("extracted relation uses undeclared type MANAGES")));
        assertTrue(schema.violations().stream().anyMatch(value ->
                value.contains("extracted relation references an entity not present")));
    }

    @Test
    void reportsWhenExpectedFactsCannotBeRepresentedByTheSelectedSchema() {
        CompositeGraphQualityEvaluator.ScoringProfile incompatibleSchema =
                new CompositeGraphQualityEvaluator.ScoringProfile(
                        0.30, 0.35, 0.20, 0.15, 0.80,
                        Set.of("ACCOUNT"), Set.of("MANAGES"), Set.of());

        GraphQualityScorer.SchemaAdherence schema = new GraphQualityScorer().score(
                expected(), new Graph(), incompatibleSchema).facts().schemaAdherence();

        assertFalse(schema.valid());
        assertTrue(schema.violations().contains(
                "expected entity gamma uses undeclared type PROCESS"));
        assertTrue(schema.violations().stream().anyMatch(value ->
                value.contains("expected relation uses undeclared type OWNS")));
    }

    @Test
    void exactStandardizedSchemaOverridesProfileAndValidatesRelationEntityTypes() {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(List.of(
                entity("alpha", "Alpha Account", "ACCOUNT"),
                entity("beta", "Beta Account", "ACCOUNT"),
                entity("gamma", "Gamma Process", "PROCESS"))));
        graph.setRelationships(new ArrayList<>(List.of(
                relation("alpha", "OWNS", "beta"))));
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("ACCOUNT", "An account", List.of()),
                        new NodeType("PROCESS", "A process", List.of())),
                List.of(new RelationshipType("OWNS", "Owns", List.of())),
                List.of("(PROCESS)-[:OWNS]->(ACCOUNT)"));
        CompositeGraphQualityEvaluator.ScoringProfile deliberatelyWrongProfile =
                new CompositeGraphQualityEvaluator.ScoringProfile(
                        0.30, 0.35, 0.20, 0.15, 0.80,
                        Set.of("IGNORED_ENTITY_TYPE"), Set.of("IGNORED_RELATION_TYPE"), Set.of());

        GraphQualityScorer.SchemaAdherence adherence = new GraphQualityScorer().score(
                expected(), graph, deliberatelyWrongProfile, schema, List.of())
                .facts().schemaAdherence();

        assertFalse(adherence.valid());
        assertTrue(adherence.violations().stream().noneMatch(value ->
                value.contains("undeclared type ACCOUNT") || value.contains("undeclared type OWNS")));
        assertTrue(adherence.violations().stream().anyMatch(value ->
                value.startsWith("expected relation ")
                        && value.contains("source entity type ACCOUNT")
                        && value.contains("(PROCESS)-[:OWNS]->(ACCOUNT)")));
        assertTrue(adherence.violations().stream().anyMatch(value ->
                value.startsWith("extracted relation ")
                        && value.contains("source entity type ACCOUNT")
                        && value.contains("(PROCESS)-[:OWNS]->(ACCOUNT)")));
    }

    @Test
    void correlatesProductionDecisionEventsAndSummarizesWhyGoldAtomsWereLost() {
        List<GraphDecisionTraceEvent> events = List.of(
                event("wrong-type", "source-a", "doc-a", "Alpha Account",
                        GraphMissStage.MENTION_IDENTITY,
                        GraphMissReason.MENTION_IDENTITY_UNRESOLVED, List.of("a")),
                event("missing-entity", "source-b", "doc-b", "Gamma Process",
                        GraphMissStage.PROPOSITION,
                        GraphMissReason.PROPOSITION_NOT_PRODUCED, List.of()),
                event("missing-relation", "source-c", "doc-c", "a|OWNS|b",
                        GraphMissStage.GRAPH_ADMISSION,
                        GraphMissReason.EXTRACTION_CONFIDENCE_BELOW_THRESHOLD,
                        List.of("a", "b")));

        GraphQualityScorer.Evaluation evaluation = new GraphQualityScorer().score(
                expected(), actual(), profile(), events);

        assertEquals(3, evaluation.summary().attributedGoldMisses());
        assertEquals(0, evaluation.summary().unattributedGoldMisses());
        assertEquals(1.0d, evaluation.summary().attributionCoverage(), 1.0e-9);
        assertEquals(1, evaluation.summary().attributedReasons()
                .get(GraphMissReason.MENTION_IDENTITY_UNRESOLVED));
        assertEquals(1, evaluation.summary().attributedReasons()
                .get(GraphMissReason.PROPOSITION_NOT_PRODUCED));
        assertEquals(1, evaluation.summary().attributedReasons()
                .get(GraphMissReason.EXTRACTION_CONFIDENCE_BELOW_THRESHOLD));
        assertEquals(1, evaluation.summary().attributedStages().get(GraphMissStage.PROPOSITION));
        assertEquals(1, evaluation.summary().attributedStages().get(GraphMissStage.MENTION_IDENTITY));
        assertEquals(1, evaluation.summary().attributedStages().get(GraphMissStage.GRAPH_ADMISSION));
        assertTrue(evaluation.misses().stream().allMatch(miss ->
                miss.attribution().causalTrail().stream().allMatch(event ->
                        event.atom().equals(miss.miss().atom()))));
        assertTrue(evaluation.misses().stream()
                .flatMap(miss -> miss.attribution().causalTrail().stream())
                .allMatch(event -> event.metadata().containsKey("observedAtom")),
                "the canonical gold atom must retain the raw production atom for auditability");
    }

    @Test
    void callerCanProvideDomainSpecificTraceCorrelationWithoutChangingScoring() {
        GraphDecisionTraceEvent opaque = event("opaque", "source", "doc", "opaque-runtime-key",
                GraphMissStage.VALIDATOR, GraphMissReason.VALIDATOR_REJECTED, List.of());

        GraphQualityScorer.Evaluation evaluation = new GraphQualityScorer().score(
                expected(), actual(), profile(), List.of(opaque),
                (miss, expected, quality, events) -> miss.atom().equals("entity:gamma")
                        ? events : List.of());

        assertEquals(1, evaluation.summary().attributedGoldMisses());
        assertEquals(GraphMissReason.VALIDATOR_REJECTED,
                evaluation.misses().stream()
                        .filter(miss -> miss.miss().atom().equals("entity:gamma"))
                        .findFirst().orElseThrow().attribution().reason());
    }

    private static ExpectedGraph expected() {
        return new ExpectedGraph(List.of(
                new ExpectedEntity("alpha", "Alpha Account", "ACCOUNT", List.of("A Account")),
                new ExpectedEntity("beta", "Beta Account", "ACCOUNT", List.of()),
                new ExpectedEntity("gamma", "Gamma Process", "PROCESS", List.of())),
                List.of(new ExpectedRelation("alpha", "OWNS", "beta")));
    }

    private static Graph actual() {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(List.of(
                entity("a", "Alpha Account", "ORGANIZATION"),
                entity("b", "Beta Account", "ACCOUNT"),
                entity("extra", "Unexpected", "ACCOUNT"))));
        graph.setRelationships(new ArrayList<>(List.of(
                relation("a", "MANAGES", "b"))));
        return graph;
    }

    private static CompositeGraphQualityEvaluator.ScoringProfile profile() {
        return new CompositeGraphQualityEvaluator.ScoringProfile(
                0.30, 0.35, 0.20, 0.15, 0.80,
                Set.of("ACCOUNT", "ORGANIZATION", "PROCESS"),
                Set.of("OWNS", "MANAGES"), Set.of());
    }

    private static Entity entity(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setAliases(new ArrayList<>());
        return entity;
    }

    private static Relationship relation(String source, String type, String target) {
        Relationship relationship = new Relationship();
        relationship.setSource(source);
        relationship.setType(type);
        relationship.setTarget(target);
        return relationship;
    }

    private static GraphDecisionTraceEvent event(
            String id,
            String source,
            String document,
            String atom,
            GraphMissStage stage,
            GraphMissReason reason,
            List<String> candidates) {
        return new GraphDecisionTraceEvent(id, source, document, "chunk", "shard", atom,
                stage, reason, "rejected", candidates, Map.of(), Map.of());
    }
}