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

import static org.junit.jupiter.api.Assertions.*;

class EntityTypeAccuracyEvaluatorTest {

    private EntityTypeAccuracyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        EvaluationProperties props = new EvaluationProperties();
        props.getEntityTypeAccuracy().setThreshold(0.7);
        evaluator = new EntityTypeAccuracyEvaluator(props);
    }

    @Test
    void testAllTypesCorrect() {
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
        assertEquals(1.0, result.getScore(), 0.001);
        assertEquals(3, result.getTruePositives());
        assertEquals(0, result.getFalsePositives());
        assertEquals(EvaluationType.ENTITY_TYPE_ACCURACY, result.getEvaluationType());
    }

    @Test
    void testSomeTypesWrong() {
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Acme Corp", "LOCATION"),   // wrong
                entity("3", "New York", "ORGANIZATION")  // wrong
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Acme Corp", "ORGANIZATION"),
                entity("c", "New York", "LOCATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertFalse(result.isPassed()); // 1/3 = 0.33 < 0.7 threshold
        assertEquals(1.0 / 3.0, result.getScore(), 0.001);
        assertEquals(1, result.getTruePositives());
        assertEquals(2, result.getFalsePositives()); // type mismatches
    }

    @Test
    void testAllTypesWrong() {
        Graph extracted = graphWith(
                entity("1", "Alice", "ORGANIZATION"),
                entity("2", "Acme Corp", "PERSON")
        );

        Graph groundTruth = graphWith(
                entity("a", "Alice", "PERSON"),
                entity("b", "Acme Corp", "ORGANIZATION")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore(), 0.001);
    }

    @Test
    void testCaseInsensitiveTypeComparison() {
        Graph extracted = graphWith(entity("1", "Alice", "person"));
        Graph groundTruth = graphWith(entity("a", "Alice", "PERSON"));

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(1.0, result.getScore(), 0.001);
        assertEquals(1, result.getTruePositives());
    }

    @Test
    void testUnmatchedEntitiesIgnored() {
        // Extra entities that don't match ground truth are simply not evaluated for type
        Graph extracted = graphWith(
                entity("1", "Alice", "PERSON"),
                entity("2", "Unknown Entity", "CONCEPT")
        );

        Graph groundTruth = graphWith(entity("a", "Alice", "PERSON"));

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(1.0, result.getScore(), 0.001);
        assertEquals(1, result.getTruePositives());
        // Only 1 match evaluated, the "Unknown Entity" is ignored
        assertEquals(1.0, result.getMetrics().get("total_matched"), 0.001);
    }

    @Test
    void testNoMatchableEntities() {
        Graph extracted = graphWith(entity("1", "Bob", "PERSON"));
        Graph groundTruth = graphWith(entity("a", "Alice", "PERSON"));

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        // No entities matched by title, so accuracy = 0/0 = 0.0
        assertEquals(0.0, result.getScore(), 0.001);
    }

    @Test
    void testNoGroundTruth() {
        Graph extracted = graphWith(entity("1", "Alice", "PERSON"));

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.builder().build());

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore(), 0.001);
    }

    @Test
    void testTypeMismatchDetails() {
        Graph extracted = graphWith(
                entity("1", "Apple", "ORGANIZATION"),
                entity("2", "Alice", "PERSON")
        );

        Graph groundTruth = graphWith(
                entity("a", "Apple", "PRODUCT"),
                entity("b", "Alice", "PERSON")
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(0.5, result.getScore(), 0.001);

        // Check individual match details
        long typeMismatches = result.getEntityMatches().stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.TYPE_MISMATCH)
                .count();
        assertEquals(1, typeMismatches);

        GraphEvaluationResult.EntityMatch mismatch = result.getEntityMatches().stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.TYPE_MISMATCH)
                .findFirst()
                .orElseThrow();
        assertEquals("Apple", mismatch.getExtractedTitle());
        assertEquals("ORGANIZATION", mismatch.getExtractedType());
        assertEquals("PRODUCT", mismatch.getExpectedType());
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
