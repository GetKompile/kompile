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
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden and metamorphic coverage over graph shapes closer to production identity-resolution data
 * than the small hub fixture. The expected behavior is intentionally policy-level: unrelated nodes,
 * serialization order, and low-quality evidence must not change a candidate's admission outcome.
 */
class HybridGraphAdmissionComplexFixtureTest {

    private static final String CANDIDATE = "person-acme";
    private static final String HOMONYM = "company-acme";

    @Test
    void multiCommunityIdentityGraphAdmitsStrongCandidate() {
        GraphAdmissionResult result = evaluate(complexFixture(false, false).materialize());

        assertEquals(AdmissionDecision.REUSE, result.decision(),
                "dense positive identity evidence should admit the existing node: " + result);
        assertTrue(result.score() >= 0.70);
        assertTrue(result.margin() >= 0.10);
    }

    @Test
    void disconnectedCelebrityHubCannotVetoAResolvedIdentity() {
        GraphAdmissionResult baseline = evaluate(noisyCandidateGraph(false));
        GraphAdmissionResult withHub = evaluate(noisyCandidateGraph(true));

        assertEquals(AdmissionDecision.REUSE, baseline.decision(), "baseline=" + baseline);
        assertEquals(AdmissionDecision.REUSE, withHub.decision(), "withHub=" + withHub);
        assertEquals(baseline.score(), withHub.score(), 1e-9);
        assertEquals(baseline.margin(), withHub.margin(), 1e-9);
    }

    @Test
    void conflictingHomonymEvidenceDefersInsteadOfReusing() {
        GraphAdmissionResult result = evaluate(complexFixture(false, true).materialize());

        assertNotEquals(AdmissionDecision.REUSE, result.decision(),
                "a same-label, conflicting-type homonym must not be silently reused");
    }

    @Test
    void complexGraphIsInvariantToEntityAndRelationInsertionOrder() {
        Fixture fixture = complexFixture(false, false);
        GraphAdmissionResult baseline = evaluate(fixture.materialize());

        for (int seed = 0; seed < 20; seed++) {
            GraphAdmissionResult permuted = evaluate(fixture.shuffled(seed).materialize());
            assertEquals(baseline.decision(), permuted.decision(), "decision changed for seed " + seed);
            assertEquals(baseline.score(), permuted.score(), 1e-6,
                    "score changed for seed " + seed);
            assertEquals(baseline.margin(), permuted.margin(), 1e-6,
                    "margin changed for seed " + seed);
        }
    }

    @Test
    void seededNoisyGraphsRemainFiniteDeterministicAndPermutationStable() {
        for (int seed = 0; seed < 25; seed++) {
            Fixture fixture = randomFixture(seed);
            GraphAdmissionResult first = evaluate(fixture.materialize());
            GraphAdmissionResult repeated = evaluate(fixture.materialize());
            GraphAdmissionResult permuted = evaluate(fixture.shuffled(seed * 31L + 7L).materialize());

            assertNotNull(first.decision(), "missing decision for seed " + seed);
            assertTrue(Double.isFinite(first.score()) && first.score() >= 0.0 && first.score() <= 1.0,
                    "invalid score for seed " + seed + ": " + first.score());
            assertTrue(Double.isFinite(first.margin()) && first.margin() >= 0.0 && first.margin() <= 1.0,
                    "invalid margin for seed " + seed + ": " + first.margin());
            assertEquals(first.decision(), repeated.decision(), "repeat decision changed for seed " + seed);
            assertEquals(first.score(), repeated.score(), 1e-6, "repeat score changed for seed " + seed);
            assertEquals(first.decision(), permuted.decision(), "permuted decision changed for seed " + seed);
            assertEquals(first.score(), permuted.score(), 1e-6, "permuted score changed for seed " + seed);
        }
    }

    private GraphAdmissionResult evaluate(UnifiedGraph graph) {
        AdmissionCandidate selected = new AdmissionCandidate(CANDIDATE, "person:acme");
        AdmissionCandidate homonym = new AdmissionCandidate(HOMONYM, "person:acme");
        AdmissionRequest request = new AdmissionRequest(
                "identity-resolution", selected, List.of(selected, homonym),
                "snapshot:complex", "admission:v2", graph);
        return new HybridGraphAdmissionEvaluator().evaluate(request);
    }

    private UnifiedGraph noisyCandidateGraph(boolean disconnectedHub) {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity(CANDIDATE, "PERSON", "Acme", 1.0, 1.0))
                .addEntity(entity(HOMONYM, "COMPANY", "Acme", 0.20, 0.20));
        for (int i = 0; i < 28; i++) {
            graph.addEntity(entity("background-" + i, "FACT", "background-" + i, 0.10, 0.10));
            graph.addRelation(relation("background-edge-" + i, "background-" + i,
                    "background-" + ((i + 1) % 28), "RELATED", 0.01, 0.0));
        }
        if (disconnectedHub) {
            graph.addEntity(entity("celebrity-hub", "PERSON", "Celebrity", 1.0, 1.0));
        }
        return graph;
    }

    private Fixture complexFixture(boolean disconnectedHub, boolean contradiction) {
        List<SimpleGraphEntity> entities = new ArrayList<>();
        entities.add(entity(CANDIDATE, "PERSON", "Acme", 0.76, 0.98));
        entities.add(entity(HOMONYM, "COMPANY", "Acme", 0.45, 0.20));
        entities.add(entity("globex-person", "PERSON", "Globex", 0.25, 0.90));

        for (int i = 0; i < 6; i++) {
            entities.add(entity("acme-alias-" + i, "PERSON", "Acme", 0.30, 0.85));
        }
        for (int i = 0; i < 20; i++) {
            entities.add(entity("noise-" + i, i % 2 == 0 ? "DOCUMENT" : "TOPIC",
                    "noise-" + i, 0.10 + (i % 3) * 0.02, 0.05));
        }
        if (disconnectedHub) {
            entities.add(entity("celebrity-hub", "PERSON", "Celebrity", 0.90, 0.99));
            for (int i = 0; i < 4; i++) {
                entities.add(entity("celebrity-fan-" + i, "PERSON", "Fan", 0.40, 0.90));
            }
        }

        List<SimpleGraphRelation> relations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            relations.add(relation("same-as-" + i, CANDIDATE, "acme-alias-" + i,
                    "SAME_AS", 0.90, 0.95));
            relations.add(relation("alias-to-candidate-" + i, "acme-alias-" + i, CANDIDATE,
                    "MENTIONS", 0.82, 0.80));
        }
        for (int i = 0; i < 6; i++) {
            relations.add(relation("alias-cycle-" + i, "acme-alias-" + i,
                    "acme-alias-" + ((i + 1) % 6), "CAUSES", 0.70, 0.80));
        }

        // Parallel evidence intentionally makes insertion order observable in the current PSL projection.
        relations.add(relation("parallel-weak", CANDIDATE, "acme-alias-0", "SAME_AS", 0.10, 0.90));
        relations.add(relation("parallel-strong", CANDIDATE, "acme-alias-0", "SAME_AS", 0.90, 0.90));
        relations.add(relation("candidate-to-globex", CANDIDATE, "globex-person",
                "RELATED", 0.20, 0.20));
        relations.add(relation("homonym-link", CANDIDATE, HOMONYM,
                contradiction ? "CONTRADICTS" : "SUPPORTS", 0.90, 0.95));

        for (int i = 0; i < 20; i++) {
            relations.add(relation("noise-edge-" + i, "noise-" + i,
                    "noise-" + ((i + 1) % 20), i % 2 == 0 ? "RELATED" : "CONTRADICTS",
                    0.01 + (i % 3) * 0.01, 0.0));
        }
        if (disconnectedHub) {
            for (int i = 0; i < 4; i++) {
                relations.add(relation("fan-edge-" + i, "celebrity-hub", "celebrity-fan-" + i,
                        "RELATED", 0.90, 0.90));
            }
        }
        return new Fixture(entities, relations);
    }

    private Fixture randomFixture(long seed) {
        Random random = new Random(seed);
        List<SimpleGraphEntity> entities = new ArrayList<>();
        entities.add(entity(CANDIDATE, "PERSON", "candidate", 0.55, 0.70));
        for (int i = 1; i < 32; i++) {
            entities.add(entity("node-" + i, i % 4 == 0 ? "PERSON" : "FACT",
                    "node-" + i, 0.10 + random.nextDouble() * 0.70,
                    random.nextDouble()));
        }

        List<SimpleGraphRelation> relations = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            String source = i == 0 ? CANDIDATE : "node-" + (1 + random.nextInt(31));
            String target = "node-" + (1 + random.nextInt(31));
            String type = switch (i % 5) {
                case 0 -> "SAME_AS";
                case 1 -> "SUPPORTS";
                case 2 -> "CONTRADICTS";
                case 3 -> "CAUSES";
                default -> "RELATED";
            };
            relations.add(relation("random-edge-" + i, source, target, type,
                    random.nextDouble(), random.nextDouble()));
        }
        return new Fixture(entities, relations);
    }

    private SimpleGraphEntity entity(String id, String type, String label,
                                     double weight, double confidence) {
        return new SimpleGraphEntity(id, type, label, weight, confidence,
                Set.of(), null, null, Map.of());
    }

    private SimpleGraphRelation relation(String id, String source, String target, String type,
                                         double weight, double confidence) {
        return new SimpleGraphRelation(id, source, target, type, weight, confidence,
                true, Set.of(), null, null, Map.of());
    }

    private record Fixture(List<SimpleGraphEntity> entities, List<SimpleGraphRelation> relations) {

        UnifiedGraph materialize() {
            UnifiedGraph graph = new UnifiedGraph();
            entities.forEach(graph::addEntity);
            relations.forEach(graph::addRelation);
            return graph;
        }

        Fixture shuffled(long seed) {
            Random random = new Random(seed);
            List<SimpleGraphEntity> shuffledEntities = new ArrayList<>(entities);
            List<SimpleGraphRelation> shuffledRelations = new ArrayList<>(relations);
            Collections.shuffle(shuffledEntities, random);
            Collections.shuffle(shuffledRelations, random);
            return new Fixture(shuffledEntities, shuffledRelations);
        }
    }
}
