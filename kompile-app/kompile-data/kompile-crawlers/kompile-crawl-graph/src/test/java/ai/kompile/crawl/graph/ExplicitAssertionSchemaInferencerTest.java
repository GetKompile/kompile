package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitAssertionSchemaInferencerTest {

    @Test
    void derivesOnlyVocabularyStatedByArbitraryCorpusAssertions() {
        GraphSchema schema = ExplicitAssertionSchemaInferencer.infer(Map.of(
                "one",
                "Aster Beacon is a sensor. Nimbus Vault is a repository. "
                        + "Aster Beacon monitors Nimbus Vault."));

        assertEquals(List.of("REPOSITORY", "SENSOR"),
                schema.getAllNodeLabels().stream().sorted().toList());
        assertEquals(Set.of("MONITORS"), schema.getAllRelationshipTypes());
        assertEquals(List.of("(SENSOR)-[:MONITORS]->(REPOSITORY)"), schema.getPatterns());
    }

    @Test
    void mergesAssertionsAcrossTheFullOrderedCorpus() {
        Map<String, String> passages = new LinkedHashMap<>();
        passages.put("one", "Cedar Team is a committee.");
        passages.put("two", "Quartz Plan is a proposal. Cedar Team approved Quartz Plan.");

        GraphSchema schema = ExplicitAssertionSchemaInferencer.infer(passages);

        assertTrue(schema.getAllNodeLabels().containsAll(List.of("COMMITTEE", "PROPOSAL")));
        assertTrue(schema.getAllRelationshipTypes().contains("APPROVED"));
        assertTrue(schema.getPatterns().contains(
                "(COMMITTEE)-[:APPROVED]->(PROPOSAL)"));
    }

    @Test
    void appositiveClassificationContributesEndpointAndRelationCardinality() {
        ExplicitAssertionSchemaInferencer.Analysis analysis =
                ExplicitAssertionSchemaInferencer.analyze(Map.of(
                        "one",
                        "Alex Rivera is a person. Alex Rivera works at Acme Robotics, a company."));

        assertEquals(2, analysis.explicitEntityCount());
        assertEquals(1, analysis.explicitRelationCount());
        assertEquals(List.of(
                        new ExplicitAssertionSchemaInferencer.EntityAssertion("Alex Rivera", "PERSON"),
                        new ExplicitAssertionSchemaInferencer.EntityAssertion("Acme Robotics", "COMPANY")),
                analysis.entityAssertions());
        assertEquals(List.of(
                        new ExplicitAssertionSchemaInferencer.RelationAssertion(0, 1, "WORKS_AT")),
                analysis.relationAssertions());
    }

    @Test
    void doesNotInventTypesWithoutExplicitClassificationAssertions() {
        assertNull(ExplicitAssertionSchemaInferencer.infer(Map.of(
                "one", "Aster Beacon monitors Nimbus Vault.")));
    }
}
