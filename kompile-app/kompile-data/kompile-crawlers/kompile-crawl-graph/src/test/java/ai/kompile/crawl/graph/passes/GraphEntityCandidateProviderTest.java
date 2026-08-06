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
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.PassContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        graph.setRelationships(new ArrayList<>());
        return graph;
    }

    private static Relationship relationship(String source, String type, String target) {
        Relationship relationship = new Relationship();
        relationship.setSource(source);
        relationship.setType(type);
        relationship.setTarget(target);
        return relationship;
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
        assertTrue(candidates.get(0).identitySignals().canonicalNameExact());
        assertTrue(candidates.get(0).identitySignals().typeCompatible());
    }

    @Test
    void nfkcEquivalentMultilingualNamesAreExactIdentityMatches() {
        Graph graph = graphOf(entity("forecast-apac", "ＡＰＡＣ予測", "REGIONAL_FORECAST"));

        List<EntityCandidate> candidates = provider(graph)
                .candidatesFor("APAC予測", "REGIONAL_FORECAST", CONTEXT, 8);

        assertEquals(1, candidates.size());
        assertEquals("forecast-apac", candidates.get(0).id());
        assertEquals(1.0, candidates.get(0).score(), 1e-9);
        assertTrue(candidates.get(0).identitySignals().canonicalNameExact());
        assertTrue(candidates.get(0).identitySignals().typeCompatible());
    }

    @Test
    void anAliasMatchIsOfferedWhenTheCanonicalNameDoesNot() {
        Graph graph = graphOf(entity("ent-ibm", "International Business Machines", "ORGANIZATION", "IBM"));

        List<EntityCandidate> candidates =
                provider(graph).candidatesFor("IBM", "ORGANIZATION", CONTEXT, 8);

        assertEquals(1, candidates.size());
        assertEquals("ent-ibm", candidates.get(0).id());
        assertTrue(candidates.get(0).aliases().contains("IBM"));
        assertTrue(candidates.get(0).identitySignals().aliasExact());
    }

    @Test
    void candidateCarriesOnlyItsBoundedGraphNeighborhoodForIdentityReasoning() {
        Graph graph = graphOf(
                entity("ent-acme", "Acme Corporation", "ORGANIZATION"),
                entity("ent-division", "North Division", "BUSINESS_UNIT"),
                entity("ent-zeta", "Zeta Logistics", "ORGANIZATION"),
                entity("ent-harbor", "Harbor Project", "PROJECT"));
        graph.getRelationships().add(relationship(
                "ent-acme", "OPERATES_DIVISION", "ent-division"));
        graph.getRelationships().add(relationship(
                "ent-zeta", "RUNS_PROJECT", "ent-harbor"));

        EntityCandidate candidate = provider(graph)
                .candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).get(0);

        assertEquals("OUT OPERATES_DIVISION -> North Division", candidate.identityContext());
        assertFalse(candidate.identityContext().contains("Harbor Project"),
                "unrelated graph state must not leak into a candidate's small-model context");
        assertTrue(candidate.identityContext().length()
                <= GraphEntityCandidateProvider.MAX_NEIGHBORHOOD_CHARS);
    }

    @Test
    void candidateNeighborhoodCarriesBoundedLogicAndEmbeddingFacetsWithoutRawProgramsOrVectors() {
        Graph graph = graphOf(
                entity("ent-acme", "Acme Corporation", "ORGANIZATION"),
                entity("ent-parent", "Global Holdings", "ORGANIZATION"),
                entity("ent-peer", "North Division", "BUSINESS_UNIT"));
        Relationship inferred = relationship("ent-acme", "OWNED_BY", "ent-parent");
        inferred.setConfidence(0.93);
        inferred.setMetadata(Map.of(
                "provenanceType", "INFERRED",
                "supportingRuleIds", List.of("rule:ownership", "rule:transitive"),
                "inferenceVersion", "fol-v3",
                "inferenceRunId", "run-17",
                "strengthBand", "STRONG",
                "ruleProgram", "ownedBy(X,Y) :- subsidiaryOf(X,Y)"));
        Relationship embedded = relationship("ent-acme", "OPERATES_DIVISION", "ent-peer");
        embedded.setMetadata(Map.of(
                "kgEmbeddingAlgorithm", "TRANSE",
                "kgEmbeddingVersion", 9L,
                "similarityScore", 0.82,
                "kgRelationEmbedding", List.of(0.1, 0.2, 0.3)));
        graph.getRelationships().add(inferred);
        graph.getRelationships().add(embedded);

        String context = provider(graph)
                .candidatesFor("Acme", "ORGANIZATION", CONTEXT, 8).get(0).identityContext();

        assertTrue(context.contains("logic=INFERRED"));
        assertTrue(context.contains("rules=rule:ownership|rule:transitive"));
        assertTrue(context.contains("logicVersion=fol-v3"));
        assertTrue(context.contains("embedding=TRANSE@9(soft)"));
        assertTrue(context.contains("similarity=0.820(soft)"));
        assertFalse(context.contains("ruleProgram"));
        assertFalse(context.contains("ownedBy(X,Y)"));
        assertFalse(context.contains("0.1"), "raw vectors must stay engine-side");
        assertTrue(context.length() <= GraphEntityCandidateProvider.MAX_NEIGHBORHOOD_CHARS);
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
        assertTrue(candidates.get(0).identitySignals().typeCompatible());
        assertFalse(candidates.get(1).identitySignals().typeCompatible());
    }

    @Test
    void stableIdentifierAnchorHardFiltersAnExplicitMustNotMergePeer() {
        Entity sarahOne = entity("person-sarah-1", "Sarah Chen", "PERSON");
        sarahOne.setMetadata(Map.of("email", "sarah.one@example.com"));
        Entity sarahTwo = entity("person-sarah-2", "Sarah Chen", "PERSON");
        sarahTwo.setMetadata(Map.of("email", "sarah.two@example.com"));
        Graph graph = graphOf(sarahOne, sarahTwo);
        graph.getRelationships().add(relationship(
                "person-sarah-1", "MUST_NOT_MERGE", "person-sarah-2"));

        List<EntityCandidate> candidates = provider(graph).candidatesFor(
                "Sarah Chen <sarah.one@example.com>", "PERSON", CONTEXT, 8);

        assertEquals(1, candidates.size(),
                "a graph-distinct peer of the unique identifier anchor is not a legal ballot choice");
        EntityCandidate candidate = candidates.get(0);
        assertEquals("person-sarah-1", candidate.id());
        assertTrue(candidate.identitySignals().stableIdentifierExact());
        assertEquals("sarah.one@example.com", candidate.identitySignals().matchedIdentifier());
        assertTrue(candidate.identityContext().contains("stableIdentifiers=sarah.one@example.com"));
        assertTrue(candidate.identityContext().contains("distinct from Sarah Chen"));
    }

    @Test
    void sameNameDistinctPeersRemainVisibleWhenNoSourceSignalSelectsAnAnchor() {
        Entity sarahOne = entity("person-sarah-1", "Sarah Chen", "PERSON");
        Entity sarahTwo = entity("person-sarah-2", "Sarah Chen", "PERSON");
        Graph graph = graphOf(sarahOne, sarahTwo);
        graph.getRelationships().add(relationship(
                "person-sarah-1", "DISTINCT_FROM", "person-sarah-2"));

        List<EntityCandidate> candidates = provider(graph)
                .candidatesFor("Sarah Chen", "PERSON", CONTEXT, 8);

        assertEquals(2, candidates.size(),
                "without an identifier anchor the model must see the ambiguity and may abstain");
        assertTrue(candidates.stream().noneMatch(c -> c.identitySignals().forbiddenMerge()));
        assertTrue(candidates.stream().allMatch(c -> c.identitySignals().constraintReason()
                .contains("graph marks this identity distinct")));
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
