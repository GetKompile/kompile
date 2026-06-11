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

package ai.kompile.evaluation;

import ai.kompile.core.evaluation.EvaluationType;
import ai.kompile.core.evaluation.GraphEvaluationContext;
import ai.kompile.core.evaluation.GraphEvaluationResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EntityPresenceEvaluatorTest {

    private EntityPresenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        EvaluationProperties props = new EvaluationProperties();
        props.getEntityPresence().setThreshold(0.5);
        evaluator = new EntityPresenceEvaluator(props);
    }

    @Test
    void testPerfectMatch() {
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Acme Corp", "ORGANIZATION"),
                entity("3", "New York", "LOCATION")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Acme Corp", "ORGANIZATION"),
                entity("c", "New York", "LOCATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getPrecision(), 0.001);
        assertEquals(1.0, result.getRecall(), 0.001);
        assertEquals(1.0, result.getF1(), 0.001);
        assertEquals(3, result.getTruePositives());
        assertEquals(0, result.getFalsePositives());
        assertEquals(0, result.getFalseNegatives());
        assertEquals(EvaluationType.ENTITY_PRESENCE, result.getEvaluationType());
    }

    @Test
    void testPartialMatch() {
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Acme Corp", "ORGANIZATION")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Acme Corp", "ORGANIZATION"),
                entity("c", "New York", "LOCATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(1.0, result.getPrecision(), 0.001); // 2/2 extracted are correct
        assertEquals(2.0 / 3.0, result.getRecall(), 0.001); // 2/3 expected were found
        assertEquals(2, result.getTruePositives());
        assertEquals(0, result.getFalsePositives());
        assertEquals(1, result.getFalseNegatives());
    }

    @Test
    void testExtraExtractedEntities() {
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Acme Corp", "ORGANIZATION"),
                entity("3", "Some Noise", "CONCEPT")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Acme Corp", "ORGANIZATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(2.0 / 3.0, result.getPrecision(), 0.001);
        assertEquals(1.0, result.getRecall(), 0.001);
        assertEquals(2, result.getTruePositives());
        assertEquals(1, result.getFalsePositives());
        assertEquals(0, result.getFalseNegatives());
    }

    @Test
    void testNoMatch() {
        Graph extracted = graphWith(
                entity("1", "Bob", "PERSON"),
                entity("2", "BigCo", "ORGANIZATION")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Acme Corp", "ORGANIZATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getPrecision(), 0.001);
        assertEquals(0.0, result.getRecall(), 0.001);
        assertEquals(0.0, result.getF1(), 0.001);
        assertEquals(0, result.getTruePositives());
        assertEquals(2, result.getFalsePositives());
        assertEquals(2, result.getFalseNegatives());
    }

    @Test
    void testCaseInsensitiveMatch() {
        Graph extracted = graphWith(entity("1", "alice", "PERSON"));
        Graph groundTruth = graphWith(entity("a", "ALICE", "PERSON"));

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(1, result.getTruePositives());
        assertEquals(1.0, result.getF1(), 0.001);
    }

    @Test
    void testFuzzyMatch() {
        Graph extracted = graphWith(entity("1", "Acme Corporation", "ORGANIZATION"));
        Graph groundTruth = graphWith(entity("a", "Acme Corp", "ORGANIZATION"));

        // Without fuzzy match - should not match
        GraphEvaluationResult exactResult = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));
        assertEquals(0, exactResult.getTruePositives());

        // With fuzzy match - should match (similarity ~0.56 for Corporation vs Corp)
        GraphEvaluationResult fuzzyResult = evaluator.evaluate(extracted,
                GraphEvaluationContext.withFuzzyMatch(groundTruth, 0.5));
        assertEquals(1, fuzzyResult.getTruePositives());
    }

    @Test
    void testEntityTypeFilter() {
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Acme Corp", "ORGANIZATION"),
                entity("3", "New York", "LOCATION")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Bob", "PERSON"),
                entity("c", "Acme Corp", "ORGANIZATION")
        );

        // Only evaluate PERSON entities
        GraphEvaluationContext ctx = GraphEvaluationContext.builder()
                .groundTruth(groundTruth)
                .entityTypeFilter(Set.of("PERSON"))
                .build();

        GraphEvaluationResult result = evaluator.evaluate(extracted, ctx);

        // Extracted: Alice (PERSON). Expected: Alice, Bob (PERSON)
        assertEquals(1.0, result.getPrecision(), 0.001);
        assertEquals(0.5, result.getRecall(), 0.001);
        assertEquals(1, result.getTruePositives());
        assertEquals(0, result.getFalsePositives());
        assertEquals(1, result.getFalseNegatives());
    }

    @Test
    void testTypeMismatch() {
        Graph extracted = graphWith(entity("1", "Apple", "ORGANIZATION"));
        Graph groundTruth = graphWith(entity("a", "Apple", "PRODUCT"));

        // Without requireTypeMatch - title matches, so it's a TP (but with TYPE_MISMATCH detail)
        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));
        assertEquals(0, result.getTruePositives());
        // Should be TYPE_MISMATCH
        assertEquals(1, result.getEntityMatches().stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.TYPE_MISMATCH)
                .count());

        // With requireTypeMatch - type differs, so no match
        GraphEvaluationContext strictCtx = GraphEvaluationContext.builder()
                .groundTruth(groundTruth)
                .requireTypeMatch(true)
                .build();
        GraphEvaluationResult strictResult = evaluator.evaluate(extracted, strictCtx);
        assertEquals(0, strictResult.getTruePositives());
        assertEquals(1, strictResult.getFalsePositives());
        assertEquals(1, strictResult.getFalseNegatives());
    }

    @Test
    void testEmptyExtracted() {
        Graph extracted = graphWith();
        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Bob", "PERSON")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(0.0, result.getPrecision(), 0.001);
        assertEquals(0.0, result.getRecall(), 0.001);
        assertEquals(0.0, result.getF1(), 0.001);
        assertEquals(2, result.getFalseNegatives());
    }

    @Test
    void testEmptyGroundTruth() {
        Graph extracted = graphWith(entity("1", "Alice", "PERSON"));
        Graph groundTruth = graphWith();

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(0.0, result.getPrecision(), 0.001);
        assertEquals(0.0, result.getRecall(), 0.001);
        assertEquals(1, result.getFalsePositives());
    }

    @Test
    void testNoGroundTruth() {
        Graph extracted = graphWith(entity("1", "Alice", "PERSON"));

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.builder().build());

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore(), 0.001);
        assertNotNull(result.getExplanation());
    }

    @Test
    void testPerTypeMetrics() {
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Bob", "PERSON"),
                entity("3", "Acme Corp", "ORGANIZATION")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Bob", "PERSON"),
                entity("c", "Acme Corp", "ORGANIZATION"),
                entity("d", "BigCo", "ORGANIZATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        // PERSON: 2 TP, 0 FP, 0 FN -> precision=1.0, recall=1.0, f1=1.0
        assertEquals(1.0, result.getMetrics().get("person.precision"), 0.001);
        assertEquals(1.0, result.getMetrics().get("person.recall"), 0.001);

        // ORGANIZATION: 1 TP, 0 FP, 1 FN -> precision=1.0, recall=0.5
        assertEquals(1.0, result.getMetrics().get("organization.precision"), 0.001);
        assertEquals(0.5, result.getMetrics().get("organization.recall"), 0.001);
    }

    @Test
    void testLevenshteinDistance() {
        assertEquals(0, EntityPresenceEvaluator.levenshteinDistance("abc", "abc"));
        assertEquals(1, EntityPresenceEvaluator.levenshteinDistance("abc", "ab"));
        assertEquals(1, EntityPresenceEvaluator.levenshteinDistance("abc", "adc"));
        assertEquals(3, EntityPresenceEvaluator.levenshteinDistance("abc", "xyz"));
    }

    @Test
    void testComputeSimilarity() {
        assertEquals(1.0, EntityPresenceEvaluator.computeSimilarity("Alice", "alice", false));
        assertEquals(0.0, EntityPresenceEvaluator.computeSimilarity("Alice", "Bob", false));
        assertTrue(EntityPresenceEvaluator.computeSimilarity("Alice", "Alicee", true) > 0.8);
        assertEquals(0.0, EntityPresenceEvaluator.computeSimilarity(null, "Alice", true));
    }

    // --- helpers ---

    private static Graph graphWith(Entity... entities) {
        Graph g = new Graph();
        g.setEntities(entities.length > 0 ? Arrays.asList(entities) : Collections.emptyList());
        g.setRelationships(Collections.emptyList());
        return g;
    }

    private static Entity entity(String id, String title, String type) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(title);
        e.setType(type);
        return e;
    }
}
