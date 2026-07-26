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
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.PassContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's half of identity resolution: retrieve everything a mention could plausibly be, and
 * let the model pick. These tests pin what "plausibly" means — and that nothing outside the in-run
 * graph can ever be offered.
 */
class GraphEntityCandidateProviderTest {

    private static final PassContext CONTEXT =
            PassContext.forChunk("chunk-1", "doc-1", "Acme acquired Initech.")
                    .withGraph("graph-7", null);

    private static Entity entity(String id, String title, String type, String... aliases) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setAliases(new ArrayList<>(List.of(aliases)));
        return entity;
    }

    private static Graph graphOf(Entity... entities) {
        Graph graph = new Graph();
        graph.setId("graph-7");
        graph.setEntities(new ArrayList<>(List.of(entities)));
        return graph;
    }

    private static GraphEntityCandidateProvider provider(Graph graph) {
        return new GraphEntityCandidateProvider(graph, 0.55);
    }

    @Test
    void anExactNameMatchIsOfferedFirstWithFullScore() {
        Graph graph = graphOf(
                entity("ent-acme", "Acme Corporation", "ORGANIZATION"),
                entity("ent-initech", "Initech", "ORGANIZATION"));

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("Acme Corp.", "ORGANIZATION", CONTEXT, 8);

        assertFalse(candidates.isEmpty());
        assertEquals("ent-acme", candidates.get(0).id());
        // "Acme Corp." and "Acme Corporation" both normalize to "acme" — the same resolution the
        // crawler's own merge step would make.
        assertEquals(1.0, candidates.get(0).score(), 1e-9);
        assertEquals("Acme Corporation", candidates.get(0).name());
        assertEquals("ORGANIZATION", candidates.get(0).type());
        assertEquals("crawl-graph:graph-7", candidates.get(0).provenance());
    }

    @Test
    void anAliasMatchIsOfferedWhenTheCanonicalNameDoesNot() {
        Graph graph = graphOf(entity("ent-ibm", "International Business Machines", "ORGANIZATION", "IBM"));

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("IBM", "ORGANIZATION", CONTEXT, 8);

        assertEquals(1, candidates.size());
        assertEquals("ent-ibm", candidates.get(0).id());
        assertTrue(candidates.get(0).aliases().contains("IBM"));
    }

    @Test
    void aShortFormIsRetrievedEvenThoughEditDistanceScoresItBadly() {
        Graph graph = graphOf(entity("ent-northwind", "Northwind Traders International", "ORGANIZATION"));

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("Northwind Traders", "ORGANIZATION", CONTEXT, 8);

        assertEquals(1, candidates.size(), "a containment match must survive the min-score filter");
        assertTrue(candidates.get(0).score() >= 0.75);
    }

    @Test
    void aTypeMismatchIsDemotedRatherThanHidden() {
        Graph graph = graphOf(
                entity("ent-person", "Acme", "PERSON"),
                entity("ent-org", "Acme", "ORGANIZATION"));

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8);

        assertEquals(2, candidates.size(), "the wrong-typed entity is still offered");
        assertEquals("ent-org", candidates.get(0).id());
        assertTrue(candidates.get(1).score() < candidates.get(0).score());
    }

    @Test
    void anUnrelatedEntityIsNeverOffered() {
        Graph graph = graphOf(entity("ent-zeta", "Zeta Logistics", "ORGANIZATION"));

        assertTrue(provider(graph).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).isEmpty());
    }

    @Test
    void theMinScoreKnobControlsHowWideRecallIs() {
        Graph graph = graphOf(entity("ent-acmi", "Acmi", "ORGANIZATION"));

        // 0.75 similarity: offered at a permissive threshold, withheld at a strict one.
        assertEquals(1, new GraphEntityCandidateProvider(graph, 0.5)
                .candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).size());
        assertTrue(new GraphEntityCandidateProvider(graph, 0.9)
                .candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).isEmpty());
    }

    @Test
    void theBallotIsCappedAtTheRequestedLimitKeepingTheBestScores() {
        Graph graph = graphOf(
                entity("ent-1", "Acme", "ORGANIZATION"),
                entity("ent-2", "Acme Corporation", "ORGANIZATION"),
                entity("ent-3", "Acme Holdings", "ORGANIZATION"),
                entity("ent-4", "Acmi", "ORGANIZATION"));

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 2);

        assertEquals(2, candidates.size());
        assertTrue(candidates.get(0).score() >= candidates.get(1).score());
    }

    @Test
    void anUntypedMentionStillRetrievesByName() {
        Graph graph = graphOf(entity("ent-acme", "Acme Corporation", "ORGANIZATION"));

        List<EntityCandidate> candidates = provider(graph).candidatesFor("Acme", null, CONTEXT, 8);

        assertEquals(1, candidates.size());
        assertEquals(1.0, candidates.get(0).score(), 1e-9);
    }

    @Test
    void anEmptyGraphOffersNothingSoEveryMentionIsNew() {
        assertTrue(provider(new Graph()).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).isEmpty());
        assertTrue(provider(null).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).isEmpty());
    }

    @Test
    void aBlankMentionOrZeroLimitRetrievesNothing() {
        Graph graph = graphOf(entity("ent-acme", "Acme Corporation", "ORGANIZATION"));

        assertTrue(provider(graph).candidatesFor("  ", "ORGANIZATION", CONTEXT, 8).isEmpty());
        assertTrue(provider(graph).candidatesFor(null, "ORGANIZATION", CONTEXT, 8).isEmpty());
        assertTrue(provider(graph).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 0).isEmpty());
    }

    @Test
    void anEntityWithoutAnIdIsSkippedBecauseItCannotBeReused() {
        Graph graph = graphOf(entity(null, "Acme Corporation", "ORGANIZATION"),
                entity("  ", "Acme Corp", "ORGANIZATION"));

        assertTrue(provider(graph).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).isEmpty());
    }

    @Test
    void theEntityListIsSnapshotSoAConcurrentMergeCannotBreakRetrieval() {
        Graph graph = graphOf(entity("ent-acme", "Acme Corporation", "ORGANIZATION"));
        GraphEntityCandidateProvider provider = provider(graph);

        List<EntityCandidate> first = provider.candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8);
        // A worker thread merges another chunk's entity into the same graph.
        graph.getEntities().add(entity("ent-acme-2", "Acme Corp", "ORGANIZATION"));

        assertEquals(1, first.size(), "an earlier result must not change under us");
        assertEquals(2, provider.candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).size(),
                "the next retrieval sees the merged entity");
    }

    @Test
    void anEntityWithNoTitleFallsBackToItsIdAsTheDisplayName() {
        Entity untitled = entity("ent-acme", null, "ORGANIZATION", "Acme");
        Graph graph = graphOf(untitled);

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8);

        assertEquals(1, candidates.size());
        assertNotNull(candidates.get(0).name());
        assertEquals("ent-acme", candidates.get(0).name());
    }
}
