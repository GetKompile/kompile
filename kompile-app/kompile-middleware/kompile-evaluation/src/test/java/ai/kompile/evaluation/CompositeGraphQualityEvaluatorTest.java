package ai.kompile.evaluation;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedEntity;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedGraph;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedRelation;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.RelationMissFacet;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.Report;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ScoringProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeGraphQualityEvaluatorTest {

    private final CompositeGraphQualityEvaluator evaluator = new CompositeGraphQualityEvaluator();

    @Test
    void exactNormalizedLabelsMatch() {
        Report report = evaluate(
                expected(List.of(expectedEntity("a", "Alpha, Inc.", "organization")), List.of()),
                graph(List.of(entity("1", " alpha inc ", "ORGANIZATION")), List.of()),
                profile(0.8));

        assertEquals(1, report.entities().truePositives());
        assertEquals(1.0, report.entities().f1());
        assertTrue(report.entityMatches().get(0).exact());
    }

    @Test
    void expectedAndActualAliasesParticipateWithoutChangingActualTitle() {
        Entity actual = entity("1", "International Business Machines", "organization");
        actual.setAliases(new ArrayList<>(List.of("IBM")));
        ExpectedEntity expected = new ExpectedEntity("ibm", "IBM Corp", "organization", List.of("IBM"));

        Report report = evaluate(expected(List.of(expected), List.of()),
                graph(List.of(actual), List.of()), profile(0.8));

        assertEquals(1, report.entities().truePositives());
        assertTrue(report.entityMatches().get(0).exact());
        assertEquals("International Business Machines", actual.getTitle());
        assertEquals(List.of("IBM"), actual.getAliases());
    }

    @Test
    void fuzzyMatchingHonorsConfiguredThreshold() {
        ExpectedGraph expected = expected(
                List.of(expectedEntity("a", "Acme Corporation", "organization")), List.of());
        Graph actual = graph(List.of(entity("1", "Acme Corporaton", "organization")), List.of());

        assertEquals(1, evaluate(expected, actual, profile(0.80)).entities().truePositives());
        assertEquals(0, evaluate(expected, actual, profile(0.99)).entities().truePositives());
    }

    @Test
    void exactMatchWinsOneToOneCompetitionDeterministically() {
        ExpectedGraph expected = expected(List.of(
                expectedEntity("exact", "Alpha", "concept"),
                expectedEntity("fuzzy", "Alph", "concept")), List.of());
        Graph actual = graph(List.of(entity("node", "Alpha", "concept")), List.of());

        Report first = evaluate(expected, actual, profile(0.70));
        Report second = evaluate(expected, actual, profile(0.70));

        assertEquals(1, first.entities().truePositives());
        assertEquals("exact", first.entityMatches().get(0).expectedKey());
        assertEquals(first, second);
    }

    @Test
    void globalAssignmentMaximizesCoverageBeforeIndividualSimilarity() {
        ExpectedGraph expected = expected(List.of(
                expectedEntity("flexible", "Alpha", "concept"),
                expectedEntity("constrained", "Alph", "concept")), List.of());
        Graph actual = graph(List.of(
                entity("exact-for-flexible", "Alpha", "concept"),
                entity("only-flexible", "Alphaa", "concept")), List.of());

        Report report = evaluate(expected, actual, profile(0.75));

        assertEquals(2, report.entities().truePositives());
        assertEquals("only-flexible", report.entityMatches().stream()
                .filter(match -> match.expectedKey().equals("flexible"))
                .findFirst().orElseThrow().actualId());
        assertEquals("exact-for-flexible", report.entityMatches().stream()
                .filter(match -> match.expectedKey().equals("constrained"))
                .findFirst().orElseThrow().actualId());
    }

    @Test
    void canonicalIdsParticipateInIdentityResolution() {
        ExpectedGraph expected = expected(List.of(
                expectedEntity("canonical-id", "Gold display label", "concept")), List.of());
        Graph actual = graph(List.of(
                entity("canonical-id", "Different display label", "concept")), List.of());

        Report report = evaluate(expected, actual, profile(0.99));

        assertEquals(1, report.entities().truePositives());
        assertTrue(report.entityMatches().get(0).exact());
    }

    @Test
    void equalIdentityCandidatesPreferTheMatchingType() {
        ExpectedGraph expected = expected(List.of(
                expectedEntity("expected-person", "Alpha", "person"),
                expectedEntity("expected-org", "Alpha", "organization")), List.of());
        Graph actual = graph(List.of(
                entity("actual-org", "Alpha", "organization"),
                entity("actual-person", "Alpha", "person")), List.of());

        Report report = evaluate(expected, actual, profile(0.80));

        assertEquals(2, report.entities().truePositives());
        assertEquals(2, report.types().truePositives());
        assertTrue(report.entityMatches().stream().allMatch(match -> match.typeMatches()));
    }

    @Test
    void relationWithMappedEndpointsAndWrongTypeIsDiagnosed() {
        ExpectedGraph expected = twoEntitiesWithRelation("owns");
        Graph actual = graph(twoActualEntities(), List.of(relation("1", "2", "manages")));

        Report report = evaluate(expected, actual, profile(0.8));

        assertEquals(0, report.relationships().truePositives());
        assertEquals(RelationMissFacet.WRONG_TYPE, report.relationMisses().get(0).facet());
    }

    @Test
    void reversedRelationIsDiagnosed() {
        ExpectedGraph expected = twoEntitiesWithRelation("owns");
        Graph actual = graph(twoActualEntities(), List.of(relation("2", "1", "owns")));

        Report report = evaluate(expected, actual, profile(0.8));

        assertEquals(RelationMissFacet.REVERSED_DIRECTION, report.relationMisses().get(0).facet());
    }

    @Test
    void absentRelationAndAbsentEndpointsHaveDistinctFacets() {
        ExpectedGraph expected = twoEntitiesWithRelation("owns");
        Graph bothEntities = graph(twoActualEntities(), List.of());
        Graph missingTarget = graph(List.of(entity("1", "Alpha", "organization")), List.of());

        assertEquals(RelationMissFacet.ABSENT_RELATION,
                evaluate(expected, bothEntities, profile(0.8)).relationMisses().get(0).facet());
        assertEquals(RelationMissFacet.MISSING_TARGET_ENDPOINT,
                evaluate(expected, missingTarget, profile(0.8)).relationMisses().get(0).facet());

        Graph missingSource = graph(List.of(entity("2", "Beta", "organization")), List.of());
        assertEquals(RelationMissFacet.MISSING_SOURCE_ENDPOINT,
                evaluate(expected, missingSource, profile(0.8)).relationMisses().get(0).facet());
    }

    @Test
    void evaluationDoesNotLeakGoldLabelsIntoActualGraph() {
        Entity actual = entity("actual", "Alfa", "wrong-type");
        List<String> aliases = new ArrayList<>(List.of("A"));
        actual.setAliases(aliases);
        Graph graph = graph(List.of(actual), List.of());
        ExpectedGraph gold = expected(
                List.of(new ExpectedEntity("gold", "Alpha", "concept", List.of("Secret Gold Alias"))),
                List.of());

        evaluate(gold, graph, profile(0.70));

        assertEquals("Alfa", actual.getTitle());
        assertEquals("wrong-type", actual.getType());
        assertEquals(List.of("A"), actual.getAliases());
        assertFalse(actual.getAliases().contains("Secret Gold Alias"));
        assertEquals("Alpha", gold.entities().get(0).label());
    }

    @Test
    void cleanlinessReportsOnlyConfiguredOptionalSignals() {
        Entity first = entity("1", "Alpha", "known");
        Entity duplicate = entity("2", " alpha ", "unknown");
        Relationship dangling = relation("1", "missing", "unknown-edge");
        ScoringProfile profile = new ScoringProfile(0.30, 0.35, 0.20, 0.15, 0.8,
                Set.of("known"), Set.of("known-edge"), Set.of());

        Report report = evaluate(expected(List.of(), List.of()),
                graph(List.of(first, duplicate), List.of(dangling)), profile);

        assertEquals(1, report.cleanliness().duplicateEntities());
        assertEquals(1, report.cleanliness().danglingRelationships());
        assertEquals(2, report.cleanliness().isolatedEntities());
        assertTrue(report.cleanliness().outOfVocabulary().isPresent());
        assertEquals(2, report.cleanliness().outOfVocabulary().orElseThrow().failures());
        assertTrue(report.cleanliness().missingProvenance().isEmpty());
    }

    private Report evaluate(ExpectedGraph expected, Graph actual, ScoringProfile profile) {
        return evaluator.evaluate(expected, actual, profile);
    }

    private static ScoringProfile profile(double threshold) {
        return new ScoringProfile(0.30, 0.35, 0.20, 0.15, threshold,
                Set.of(), Set.of(), Set.of());
    }

    private static ExpectedGraph twoEntitiesWithRelation(String type) {
        return expected(List.of(
                expectedEntity("a", "Alpha", "organization"),
                expectedEntity("b", "Beta", "organization")),
                List.of(new ExpectedRelation("a", type, "b")));
    }

    private static List<Entity> twoActualEntities() {
        return List.of(entity("1", "Alpha", "organization"),
                entity("2", "Beta", "organization"));
    }

    private static ExpectedEntity expectedEntity(String key, String label, String type) {
        return new ExpectedEntity(key, label, type, List.of());
    }

    private static ExpectedGraph expected(
            List<ExpectedEntity> entities, List<ExpectedRelation> relations) {
        return new ExpectedGraph(entities, relations);
    }

    private static Entity entity(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setAliases(new ArrayList<>());
        return entity;
    }

    private static Relationship relation(String source, String target, String type) {
        Relationship relation = new Relationship();
        relation.setSource(source);
        relation.setTarget(target);
        relation.setType(type);
        return relation;
    }

    private static Graph graph(List<Entity> entities, List<Relationship> relations) {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(entities));
        graph.setRelationships(new ArrayList<>(relations));
        return graph;
    }
}
