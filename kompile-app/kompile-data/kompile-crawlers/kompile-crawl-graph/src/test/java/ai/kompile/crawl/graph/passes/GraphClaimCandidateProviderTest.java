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

package ai.kompile.crawl.graph.passes;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.PassContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim pass can only compare a new proposition against claims it was handed. These tests pin
 * which existing claims the engine puts on the ballot — the same-endpoint claim (so a repeat is
 * recognised as corroboration rather than a new fact) and the rival claim (so a functional
 * predicate's second value can be flagged). Everything else stays off the ballot.
 */
class GraphClaimCandidateProviderTest {

    private static final PassContext CONTEXT =
            PassContext.forChunk("chunk-1", "doc-1", "Acme acquired Initech in 2019.")
                    .withGraph("graph-7", null);

    private static Entity entity(String id, String title) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        return entity;
    }

    private static Relationship relationship(String source, String type, String target) {
        Relationship relationship = new Relationship();
        relationship.setSource(source);
        relationship.setType(type);
        relationship.setTarget(target);
        return relationship;
    }

    private static Graph graphOf(List<Entity> entities, Relationship... relationships) {
        Graph graph = new Graph();
        graph.setId("graph-7");
        graph.setEntities(new ArrayList<>(entities));
        graph.setRelationships(new ArrayList<>(List.of(relationships)));
        return graph;
    }

    private static Graph acmeGraph(Relationship... relationships) {
        return graphOf(
                List.of(entity("ent-acme", "Acme Corporation"),
                        entity("ent-initech", "Initech"),
                        entity("ent-globex", "Globex")),
                relationships);
    }

    @Test
    void aClaimOverTheSameEndpointsIsOffered() {
        Graph graph = acmeGraph(relationship("ent-acme", "ACQUIRED", "ent-initech"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals(1, candidates.size());
        ClaimCandidate candidate = candidates.get(0);
        assertEquals("ACQUIRED(ent-acme,ent-initech)", candidate.atomKey());
        assertEquals("ent-acme", candidate.subject());
        assertEquals("ACQUIRED", candidate.predicate());
        assertEquals("ent-initech", candidate.object());
        assertTrue(candidate.summary().contains("[same pair]"), candidate.summary());
    }

    @Test
    void theSameEndpointsInTheOtherDirectionAreOfferedAndFlaggedAsReversed() {
        // Direction is a semantic decision — ACQUIRED is not symmetric — so the model has to see
        // the existing edge and say whether the new proposition duplicates or contradicts it.
        Graph graph = acmeGraph(relationship("ent-initech", "ACQUIRED", "ent-acme"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).summary().contains("[reversed]"), candidates.get(0).summary());
        assertEquals("ent-initech", candidates.get(0).subject());
    }

    @Test
    void aRivalClaimWithTheSameSubjectAndPredicateIsOffered() {
        // The contradiction surface: Acme cannot have acquired two different companies under a
        // functional reading. The model flags it; truth maintenance decides which survives.
        Graph graph = acmeGraph(relationship("ent-acme", "ACQUIRED", "ent-globex"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals(1, candidates.size());
        assertEquals("ent-globex", candidates.get(0).object());
        assertTrue(candidates.get(0).summary().contains("[same subject and predicate]"),
                candidates.get(0).summary());
    }

    @Test
    void sameEndpointClaimsOutrankRivals() {
        Graph graph = acmeGraph(
                relationship("ent-acme", "ACQUIRED", "ent-globex"),
                relationship("ent-acme", "ACQUIRED", "ent-initech"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals(2, candidates.size());
        assertEquals("ent-initech", candidates.get(0).object());
        assertEquals("ent-globex", candidates.get(1).object());
    }

    @Test
    void aDifferentPredicateOverTheSameSubjectIsNotARival() {
        Graph graph = acmeGraph(relationship("ent-acme", "PARTNERED_WITH", "ent-globex"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void claimsAboutOtherSubjectsAreNeverOffered() {
        Graph graph = acmeGraph(relationship("ent-globex", "ACQUIRED", "ent-initech"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void anUnresolvedObjectStillSurfacesTheSubjectsExistingClaims() {
        // A brand-new object entity has no id yet. Existing claims by the same subject over the same
        // predicate are exactly what the model needs to see to decide "new value" vs "contradiction".
        Graph graph = acmeGraph(relationship("ent-acme", "ACQUIRED", "ent-globex"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", null, CONTEXT, 5);

        assertEquals(1, candidates.size());
        assertEquals("ent-globex", candidates.get(0).object());
    }

    @Test
    void predicateCaseAndWhitespaceDoNotHideARival() {
        Graph graph = acmeGraph(relationship("ent-acme", "acquired", "ent-globex"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "  Acquired ", "ent-initech", CONTEXT, 5);

        assertEquals(1, candidates.size());
        assertEquals("ACQUIRED", candidates.get(0).predicate());
        assertEquals("ACQUIRED(ent-acme,ent-globex)", candidates.get(0).atomKey());
    }

    @Test
    void withoutAPredicateOnlySameEndpointClaimsAreOffered() {
        Graph graph = acmeGraph(
                relationship("ent-acme", "ACQUIRED", "ent-globex"),
                relationship("ent-acme", "ACQUIRED", "ent-initech"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", null, "ent-initech", CONTEXT, 5);

        assertEquals(1, candidates.size());
        assertEquals("ent-initech", candidates.get(0).object());
    }

    @Test
    void theSummaryUsesEntityTitlesAndTheRecordedTime() {
        Relationship relationship = relationship("ent-acme", "ACQUIRED", "ent-initech");
        relationship.setOccurredAt("2019");
        Graph graph = acmeGraph(relationship);

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals("Acme Corporation ACQUIRED Initech (2019) [same pair]",
                candidates.get(0).summary());
    }

    @Test
    void anEntityMissingFromTheGraphFallsBackToItsIdInTheSummary() {
        Graph graph = graphOf(List.of(entity("ent-acme", "Acme Corporation")),
                relationship("ent-acme", "ACQUIRED", "ent-unknown"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-unknown", CONTEXT, 5);

        assertEquals("Acme Corporation ACQUIRED ent-unknown [same pair]", candidates.get(0).summary());
    }

    @Test
    void confidenceAndTalliesFallBackWhenTheEdgeCarriesNoMetadata() {
        Graph graph = acmeGraph(relationship("ent-acme", "ACQUIRED", "ent-initech"));

        ClaimCandidate candidate = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5).get(0);

        assertEquals(0.5, candidate.currentConfidence(), 1e-9);
        assertEquals(1, candidate.supporting());
        assertEquals(0, candidate.refuting());
    }

    @Test
    void recordedTalliesAreReadFromNumbersAndFromStrings() {
        Relationship numeric = relationship("ent-acme", "ACQUIRED", "ent-initech");
        numeric.setConfidence(0.82);
        Map<String, Object> numericMeta = new LinkedHashMap<>();
        numericMeta.put(GraphClaimCandidateProvider.META_SUPPORTING, 4);
        numericMeta.put(GraphClaimCandidateProvider.META_REFUTING, 1);
        numeric.setMetadata(numericMeta);

        Relationship textual = relationship("ent-acme", "ACQUIRED", "ent-globex");
        Map<String, Object> textualMeta = new LinkedHashMap<>();
        textualMeta.put(GraphClaimCandidateProvider.META_SUPPORTING, "7");
        textualMeta.put(GraphClaimCandidateProvider.META_REFUTING, "not-a-number");
        textual.setMetadata(textualMeta);

        Graph graph = acmeGraph(numeric, textual);
        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals(0.82, candidates.get(0).currentConfidence(), 1e-9);
        assertEquals(4, candidates.get(0).supporting());
        assertEquals(1, candidates.get(0).refuting());
        assertEquals(7, candidates.get(1).supporting());
        assertEquals(0, candidates.get(1).refuting(), "unparseable tallies fall back rather than throw");
    }

    @Test
    void theBallotIsCappedAndAZeroLimitOffersNothing() {
        Graph graph = acmeGraph(
                relationship("ent-acme", "ACQUIRED", "ent-globex"),
                relationship("ent-acme", "ACQUIRED", "ent-initech"),
                relationship("ent-acme", "ACQUIRED", "ent-other"));

        GraphClaimCandidateProvider provider = new GraphClaimCandidateProvider(graph);

        assertEquals(2, provider.candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 2).size());
        assertTrue(provider.candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 0).isEmpty());
    }

    @Test
    void anEmptyOrAbsentGraphOffersNothing() {
        assertTrue(new GraphClaimCandidateProvider(null)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5).isEmpty());
        assertTrue(new GraphClaimCandidateProvider(new Graph())
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5).isEmpty());
    }

    @Test
    void aBlankSubjectOffersNothing() {
        Graph graph = acmeGraph(relationship("ent-acme", "ACQUIRED", "ent-initech"));
        GraphClaimCandidateProvider provider = new GraphClaimCandidateProvider(graph);

        assertTrue(provider.candidatesFor(null, "ACQUIRED", "ent-initech", CONTEXT, 5).isEmpty());
        assertTrue(provider.candidatesFor("   ", "ACQUIRED", "ent-initech", CONTEXT, 5).isEmpty());
    }

    @Test
    void edgesMissingAnEndpointAreSkippedRatherThanFailing() {
        Relationship halfEdge = relationship("ent-acme", "ACQUIRED", null);
        Graph graph = acmeGraph(halfEdge, relationship("ent-acme", "ACQUIRED", "ent-initech"));

        List<ClaimCandidate> candidates = new GraphClaimCandidateProvider(graph)
                .candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 5);

        assertEquals(1, candidates.size());
        assertEquals("ent-initech", candidates.get(0).object());
    }

    @Test
    void theBallotIsSnapshotUnderTheGraphMonitorSoAConcurrentMergeCannotBreakIt() throws Exception {
        Graph graph = acmeGraph(relationship("ent-acme", "ACQUIRED", "ent-initech"));
        GraphClaimCandidateProvider provider = new GraphClaimCandidateProvider(graph);

        Thread merger = new Thread(() -> {
            for (int i = 0; i < 400; i++) {
                synchronized (graph) {
                    graph.getRelationships().add(relationship("ent-acme", "ACQUIRED", "ent-" + i));
                }
            }
        });
        merger.start();
        for (int i = 0; i < 400; i++) {
            List<ClaimCandidate> candidates =
                    provider.candidatesFor("ent-acme", "ACQUIRED", "ent-initech", CONTEXT, 8);
            assertFalse(candidates.isEmpty());
        }
        merger.join();
    }
}
