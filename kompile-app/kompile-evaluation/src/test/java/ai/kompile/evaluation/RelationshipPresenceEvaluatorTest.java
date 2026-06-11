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
import ai.kompile.core.graphrag.model.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipPresenceEvaluatorTest {

    private RelationshipPresenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        EvaluationProperties props = new EvaluationProperties();
        props.getRelationshipPresence().setThreshold(0.5);
        evaluator = new RelationshipPresenceEvaluator(props);
    }

    @Test
    void testPerfectMatch() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice"), entity("2", "Acme Corp")),
                List.of(relationship("1", "2", "WORKS_AT"))
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice"), entity("b", "Acme Corp")),
                List.of(relationship("a", "b", "WORKS_AT"))
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getPrecision(), 0.001);
        assertEquals(1.0, result.getRecall(), 0.001);
        assertEquals(1.0, result.getF1(), 0.001);
        assertEquals(1, result.getTruePositives());
        assertEquals(EvaluationType.RELATIONSHIP_PRESENCE, result.getEvaluationType());
    }

    @Test
    void testMissingRelationship() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice"), entity("2", "Acme Corp")),
                List.of(relationship("1", "2", "WORKS_AT"))
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice"), entity("b", "Acme Corp"), entity("c", "New York")),
                List.of(
                        relationship("a", "b", "WORKS_AT"),
                        relationship("b", "c", "LOCATED_IN")
                )
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(1.0, result.getPrecision(), 0.001);
        assertEquals(0.5, result.getRecall(), 0.001);
        assertEquals(1, result.getTruePositives());
        assertEquals(0, result.getFalsePositives());
        assertEquals(1, result.getFalseNegatives());
    }

    @Test
    void testExtraRelationship() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice"), entity("2", "Acme Corp"), entity("3", "Bob")),
                List.of(
                        relationship("1", "2", "WORKS_AT"),
                        relationship("1", "3", "KNOWS")
                )
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice"), entity("b", "Acme Corp")),
                List.of(relationship("a", "b", "WORKS_AT"))
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(0.5, result.getPrecision(), 0.001);
        assertEquals(1.0, result.getRecall(), 0.001);
        assertEquals(1, result.getTruePositives());
        assertEquals(1, result.getFalsePositives());
    }

    @Test
    void testTypeMismatchInRelationship() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice"), entity("2", "Acme Corp")),
                List.of(relationship("1", "2", "EMPLOYED_BY"))
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice"), entity("b", "Acme Corp")),
                List.of(relationship("a", "b", "WORKS_AT"))
        );

        // Relationship types differ -> no match
        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(0, result.getTruePositives());
        assertEquals(1, result.getFalsePositives());
        assertEquals(1, result.getFalseNegatives());
    }

    @Test
    void testCaseInsensitiveRelationshipType() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice"), entity("2", "Acme Corp")),
                List.of(relationship("1", "2", "works_at"))
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice"), entity("b", "Acme Corp")),
                List.of(relationship("a", "b", "WORKS_AT"))
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        assertEquals(1, result.getTruePositives());
    }

    @Test
    void testFuzzyEntityMatchInRelationship() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice Smith"), entity("2", "Acme Corporation")),
                List.of(relationship("1", "2", "WORKS_AT"))
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice Smith"), entity("b", "Acme Corp")),
                List.of(relationship("a", "b", "WORKS_AT"))
        );

        // Without fuzzy - "Acme Corporation" != "Acme Corp"
        GraphEvaluationResult exactResult = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));
        assertEquals(0, exactResult.getTruePositives());

        // With fuzzy (similarity ~0.56 for Corporation vs Corp)
        GraphEvaluationResult fuzzyResult = evaluator.evaluate(extracted,
                GraphEvaluationContext.withFuzzyMatch(groundTruth, 0.5));
        assertEquals(1, fuzzyResult.getTruePositives());
    }

    @Test
    void testRelationshipTypeFilter() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice"), entity("2", "Acme Corp"), entity("3", "New York")),
                List.of(
                        relationship("1", "2", "WORKS_AT"),
                        relationship("2", "3", "LOCATED_IN")
                )
        );

        Graph groundTruth = buildGraph(
                List.of(entity("a", "Alice"), entity("b", "Acme Corp"), entity("c", "New York")),
                List.of(
                        relationship("a", "b", "WORKS_AT"),
                        relationship("b", "c", "LOCATED_IN")
                )
        );

        // Only evaluate WORKS_AT relationships
        GraphEvaluationContext ctx = GraphEvaluationContext.builder()
                .groundTruth(groundTruth)
                .relationshipTypeFilter(Set.of("WORKS_AT"))
                .build();

        GraphEvaluationResult result = evaluator.evaluate(extracted, ctx);

        assertEquals(1, result.getTruePositives());
        assertEquals(0, result.getFalsePositives());
        assertEquals(0, result.getFalseNegatives());
        assertEquals(1.0, result.getF1(), 0.001);
    }

    @Test
    void testNoGroundTruth() {
        Graph extracted = buildGraph(
                List.of(entity("1", "Alice")),
                List.of()
        );

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.builder().build());

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore(), 0.001);
    }

    @Test
    void testEmptyRelationships() {
        Graph extracted = buildGraph(List.of(entity("1", "Alice")), List.of());
        Graph groundTruth = buildGraph(List.of(entity("a", "Alice")), List.of());

        GraphEvaluationResult result = evaluator.evaluate(extracted,
                GraphEvaluationContext.withGroundTruth(groundTruth));

        // Both empty -> 0 TP, 0 FP, 0 FN -> all zeros
        assertEquals(0.0, result.getF1(), 0.001);
    }

    // --- helpers ---

    private static Graph buildGraph(List<Entity> entities, List<Relationship> relationships) {
        Graph g = new Graph();
        g.setEntities(entities);
        g.setRelationships(relationships);
        return g;
    }

    private static Entity entity(String id, String title) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(title);
        e.setType("ENTITY");
        return e;
    }

    private static Relationship relationship(String source, String target, String type) {
        Relationship r = new Relationship();
        r.setSource(source);
        r.setTarget(target);
        r.setType(type);
        return r;
    }
}
