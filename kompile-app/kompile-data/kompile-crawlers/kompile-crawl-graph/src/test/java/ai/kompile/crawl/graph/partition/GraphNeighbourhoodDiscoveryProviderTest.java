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

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionSubjects;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The structured-relationship channel: evidence found by walking the graph the crawl produced.
 */
@DisplayName("Graph neighbourhood discovery")
class GraphNeighbourhoodDiscoveryProviderTest {

    private static final long FACT_SHEET = 11L;

    private KnowledgeGraphService graph;

    @BeforeEach
    void setUp() {
        graph = mock(KnowledgeGraphService.class);
        when(graph.getNodeByExternalIdInFactSheet(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());
    }

    private GraphNeighbourhoodDiscoveryProvider provider() {
        return new GraphNeighbourhoodDiscoveryProvider(graph,
                PartitionFactSheets.fromPinOrSnapshot());
    }

    private static EntityPartition partition() {
        return EntityPartition.open(PartitionKey.forEntity("acme", "v1", null))
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, String.valueOf(FACT_SHEET));
    }

    private static GraphNode node(String nodeId, String externalId, String chunkId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .externalId(externalId)
                .title(externalId)
                .nodeType(NodeLevel.ENTITY)
                .metadataJson(chunkId == null ? null
                        : "{\"_sourceChunkId\":\"" + chunkId + "\",\"_sourceDocumentId\":\"doc-"
                                + chunkId + "\"}")
                .build();
    }

    private static GraphEdge edge(String edgeId, String sourceId, String targetId, String relation,
                                  String chunkId) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNode(GraphNode.builder().nodeId(sourceId).build())
                .targetNode(GraphNode.builder().nodeId(targetId).build())
                .edgeType(EdgeType.USER_DEFINED)
                .relationType(relation)
                .metadataJson(chunkId == null ? null : "{\"_sourceChunkId\":\"" + chunkId + "\"}")
                .build();
    }

    private static ChunkCandidate find(List<ChunkCandidate> candidates, String chunkId) {
        return candidates.stream().filter(c -> c.chunkId().equals(chunkId)).findFirst()
                .orElse(null);
    }

    private static List<String> ids(List<ChunkCandidate> candidates) {
        return candidates.stream().map(ChunkCandidate::chunkId).toList();
    }

    /** Admits everything a round proposed, the way the coordinator would. */
    private static EntityPartition admitAll(EntityPartition partition,
                                            List<ChunkCandidate> candidates, int round) {
        EntityPartition updated = partition;
        for (ChunkCandidate candidate : candidates) {
            updated = updated.admit(candidate, MembershipState.DISCOVERED, round);
        }
        return updated;
    }

    @Nested
    @DisplayName("Scope")
    class Scope {

        @Test
        void aPartitionThatNamesNoFactSheetIsNotWalkedAtAll() {
            EntityPartition unscoped =
                    EntityPartition.open(PartitionKey.forEntity("acme", "v1", null));
            assertTrue(provider().discover(unscoped, 1, 20).isEmpty());
            verifyNoInteractions(graph);
        }

        @Test
        void aNullPartitionProposesNothing() {
            assertTrue(provider().discover(null, 1, 20).isEmpty());
            verifyNoInteractions(graph);
        }

        @Test
        void aSubjectWithNoNodeInTheFactSheetProposesNothing() {
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("entity_other", "other", "c-other")));
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }

        @Test
        void theChannelItSuppliesIsTheStructuredRelationshipOne() {
            assertEquals(DiscoveryChannel.STRUCTURED_RELATIONSHIP, provider().channel());
        }
    }

    @Nested
    @DisplayName("Seeds")
    class Seeds {

        @Test
        void theSubjectMayBeANodeIdOutright() {
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme-inc", "c1")));
            List<ChunkCandidate> found = provider().discover(partition(), 1, 20);
            assertEquals(List.of("c1"), ids(found));
        }

        @Test
        void orAnExternalIdResolvedThroughTheCanonicalEntityLookup() {
            GraphNode seed = node("entity_acme", "acme", "c1");
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of());
            when(graph.getNodeByExternalIdInFactSheet("acme", NodeLevel.ENTITY, FACT_SHEET))
                    .thenReturn(Optional.of(seed));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void orATitleMatchedCaseInsensitivelyOverTheIndexAlreadyInMemory() {
            GraphNode seed = GraphNode.builder().nodeId("n1").title("ACME").externalId("x-9")
                    .nodeType(NodeLevel.ENTITY)
                    .metadataJson("{\"_sourceChunkId\":\"c1\"}").build();
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(seed));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void aTombstonedSeedIsNotEvidence() {
            GraphNode seed = GraphNode.builder().nodeId("acme").externalId("acme")
                    .nodeType(NodeLevel.ENTITY).stale(true)
                    .metadataJson("{\"_sourceChunkId\":\"c1\"}").build();
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(seed));
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }
    }

    @Nested
    @DisplayName("Membership")
    class Membership {

        @BeforeEach
        void twoSubjectsInOneFactSheet() {
            //   acme --employs--> alice          beta --wrote--> memo
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(
                    node("acme", "acme", "c-acme"),
                    node("alice", "alice", "c-alice"),
                    node("beta", "beta", "c-beta"),
                    node("memo", "memo", "c-memo")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1")));
            when(graph.getEdgesForNodeInFactSheet("beta", FACT_SHEET))
                    .thenReturn(List.of(edge("e2", "beta", "memo", "wrote", "c-e2")));
        }

        /** A group-keyed claim: its id names no node in any graph, so only the pin can say who. */
        private EntityPartition group(String... subjects) {
            EntityPartition open = EntityPartition.open(PartitionKey.forGroup("acme+1", "v1", null))
                    .withPin(PartitionFactSheets.FACT_SHEET_PIN, String.valueOf(FACT_SHEET));
            return PartitionSubjects.pinnedOn(open, List.of(subjects));
        }

        @Test
        void aGroupWalksOutFromEveryOneOfItsSubjects() {
            List<String> found = ids(provider().discover(group("acme", "beta"), 1, 20));
            assertTrue(found.containsAll(
                            List.of("c-acme", "c-e1", "c-alice", "c-beta", "c-e2", "c-memo")),
                    "expected both subjects' neighbourhoods, got " + found);
        }

        @Test
        void theReasonNamesTheSubjectTheChunkWasActuallyReachedFrom() {
            List<ChunkCandidate> found = provider().discover(group("acme", "beta"), 1, 20);
            assertEquals("1 hop from acme via employs", find(found, "c-e1").reason());
            assertEquals("1 hop from beta via wrote", find(found, "c-e2").reason());
        }

        @Test
        void aGroupIdNamesNoNodeSoAnUnpinnedGroupPartitionRefusesToWalk() {
            assertTrue(provider().discover(group(), 1, 20).isEmpty());
            verifyNoInteractions(graph);
        }

        @Test
        void thePinnedMembershipOutranksAKeyThatNamesADifferentSubject() {
            List<String> found = ids(provider().discover(
                    PartitionSubjects.pinnedOn(partition(), List.of("beta")), 1, 20));
            assertTrue(found.contains("c-beta"), "expected the pinned subject, got " + found);
            assertFalse(found.contains("c-acme"), "the key's entity is not the membership");
        }

        @Test
        void groupingNeverNarrowsWhatASubjectWouldHaveReachedAlone() {
            List<String> alone = ids(provider().discover(partition(), 2, 20));
            List<String> together = ids(provider().discover(group("acme", "beta"), 2, 20));
            assertTrue(together.containsAll(alone),
                    "grouping lost " + alone + " from " + together);
            assertTrue(together.contains("c-beta"));
        }

        @Test
        void anExplicitBindingSuppliesTheMembershipAClaimCannotStateYet() {
            GraphNeighbourhoodDiscoveryProvider bound = new GraphNeighbourhoodDiscoveryProvider(
                    graph, PartitionFactSheets.fromPinOrSnapshot(),
                    PartitionSubjects.fixed(List.of("beta")),
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_MAX_HOPS,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_BASE_CONFIDENCE,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_HOP_DECAY);
            List<String> found = ids(bound.discover(group(), 1, 20));
            assertTrue(found.contains("c-beta"), "expected the bound subject, got " + found);
            assertFalse(found.contains("c-acme"));
        }

        @Test
        void oneIndexPassServesTheWholeMembership() {
            // The saving grouping exists to make: several subjects, one read of the fact sheet.
            provider().discover(group("acme", "beta"), 2, 20);
            verify(graph, times(1)).getNodesInFactSheet(FACT_SHEET);
        }

        @Test
        void aChunkBothSubjectsReachIsProposedOnceRatherThanTwice() {
            when(graph.getEdgesForNodeInFactSheet("beta", FACT_SHEET)).thenReturn(List.of(
                    edge("e2", "beta", "memo", "wrote", "c-e2"),
                    edge("e3", "beta", "alice", "employs", "c-e1")));
            List<String> found = ids(provider().discover(group("acme", "beta"), 1, 20));
            assertEquals(1, found.stream().filter("c-e1"::equals).count(),
                    "shared evidence should be read once, got " + found);
        }
    }

    @Nested
    @DisplayName("The walk")
    class Walk {

        @BeforeEach
        void graphOfThreeEntities() {
            //   acme --employs--> alice --wrote--> memo
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(
                    node("acme", "acme", "c-acme"),
                    node("alice", "alice", "c-alice"),
                    node("memo", "memo", "c-memo")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1")));
            when(graph.getEdgesForNodeInFactSheet("alice", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1"),
                            edge("e2", "alice", "memo", "wrote", "c-e2")));
            when(graph.getEdgesForNodeInFactSheet("memo", FACT_SHEET))
                    .thenReturn(List.of(edge("e2", "alice", "memo", "wrote", "c-e2")));
        }

        @Test
        void hopZeroIsTheTextTheSubjectItselfWasExtractedFrom() {
            ChunkCandidate own = find(provider().discover(partition(), 1, 20), "c-acme");
            assertNotNull(own);
            assertEquals(GraphNeighbourhoodDiscoveryProvider.DEFAULT_BASE_CONFIDENCE,
                    own.confidence(), 1e-9);
            assertEquals("extracted the subject entity", own.reason());
            assertEquals("doc-c-acme", own.documentId());
        }

        @Test
        void oneRoundReachesOneHopAndNoFurther() {
            List<String> found = ids(provider().discover(partition(), 1, 20));
            assertTrue(found.containsAll(List.of("c-acme", "c-e1", "c-alice")),
                    "expected the subject, the edge and its neighbour, got " + found);
            assertFalse(found.contains("c-e2"), "round one must not reach the second hop");
            assertFalse(found.contains("c-memo"));
        }

        @Test
        void theSecondRoundWidensToTwoHops() {
            List<String> found = ids(provider().discover(partition(), 2, 20));
            assertTrue(found.containsAll(List.of("c-acme", "c-e1", "c-alice", "c-e2", "c-memo")),
                    "expected the whole two-hop neighbourhood, got " + found);
        }

        @Test
        void theWalkStopsAtMaxHopsHoweverManyRoundsRun() {
            GraphNeighbourhoodDiscoveryProvider oneHop = new GraphNeighbourhoodDiscoveryProvider(
                    graph, PartitionFactSheets.fromPinOrSnapshot(), 1,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_BASE_CONFIDENCE,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_HOP_DECAY);
            assertFalse(ids(oneHop.discover(partition(), 9, 20)).contains("c-memo"));
        }

        @Test
        void confidenceDecaysWithDistanceSoTheManifestRecordsHowFarOutAChunkWas() {
            List<ChunkCandidate> found = provider().discover(partition(), 2, 20);
            double own = find(found, "c-acme").confidence();
            double oneHop = find(found, "c-alice").confidence();
            double twoHops = find(found, "c-memo").confidence();
            assertTrue(own > oneHop && oneHop > twoHops,
                    "expected decay, got " + own + " > " + oneHop + " > " + twoHops);
            assertEquals(GraphNeighbourhoodDiscoveryProvider.DEFAULT_BASE_CONFIDENCE
                            * GraphNeighbourhoodDiscoveryProvider.DEFAULT_HOP_DECAY,
                    oneHop, 1e-9);
        }

        @Test
        void aDirectlyRelatedChunkIsSchedulableAndATwoHopOneIsMerelyRecorded() {
            // The default calibration straddles the default policy's 0.5 promotion threshold on
            // purpose: distance should defer evidence, not discard it.
            List<ChunkCandidate> found = provider().discover(partition(), 2, 20);
            assertTrue(find(found, "c-alice").confidence() > 0.5);
            assertTrue(find(found, "c-memo").confidence() < 0.5);
            assertTrue(find(found, "c-memo").confidence() > 0.2);
        }

        @Test
        void theReasonNamesTheDistanceAndTheRelationThatWasFollowed() {
            assertEquals("1 hop from acme via employs",
                    find(provider().discover(partition(), 1, 20), "c-e1").reason());
            assertEquals("2 hops from acme via wrote",
                    find(provider().discover(partition(), 2, 20), "c-e2").reason());
        }

        @Test
        void resultsComeBackStrongestFirst() {
            List<ChunkCandidate> found = provider().discover(partition(), 2, 20);
            for (int i = 1; i < found.size(); i++) {
                assertTrue(found.get(i - 1).confidence() >= found.get(i).confidence(),
                        "candidates must be ranked, got " + found);
            }
        }

        @Test
        void everyProposalIsAttributedToTheStructuredRelationshipChannel() {
            for (ChunkCandidate candidate : provider().discover(partition(), 2, 20)) {
                assertEquals(DiscoveryChannel.STRUCTURED_RELATIONSHIP, candidate.channel());
            }
        }

        @Test
        void theCapIsAHardBound() {
            assertEquals(2, provider().discover(partition(), 2, 2).size());
        }
    }

    @Nested
    @DisplayName("Store access")
    class StoreAccess {

        @BeforeEach
        void graphOfThreeEntities() {
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(
                    node("acme", "acme", "c-acme"),
                    node("alice", "alice", "c-alice"),
                    node("memo", "memo", "c-memo")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1")));
            when(graph.getEdgesForNodeInFactSheet("alice", FACT_SHEET))
                    .thenReturn(List.of(edge("e2", "alice", "memo", "wrote", "c-e2")));
        }

        @Test
        void theNodeIndexIsBuiltOncePerRoundNotOncePerEdge() {
            provider().discover(partition(), 2, 20);
            verify(graph, times(1)).getNodesInFactSheet(FACT_SHEET);
        }

        @Test
        void endpointsAreResolvedFromThatIndexAndNeverReadBackOneAtATime() {
            // A per-edge node read would make a two-hop walk one store round-trip per edge, which
            // is the expensive side of this channel.
            provider().discover(partition(), 2, 20);
            verify(graph, never()).getNode(anyString());
            verify(graph, never()).getNodeByExternalId(anyString(), any());
        }

        @Test
        void edgesAreReadPerFrontierNodeAndOnlyForNodesActuallyReached() {
            provider().discover(partition(), 1, 20);
            verify(graph, times(1)).getEdgesForNodeInFactSheet("acme", FACT_SHEET);
            verify(graph, never()).getEdgesForNodeInFactSheet("memo", FACT_SHEET);
        }
    }

    @Nested
    @DisplayName("Tombstones and broken edges")
    class Degradation {

        @Test
        void aStaleEdgeIsNotEvidence() {
            GraphEdge stale = edge("e1", "acme", "alice", "employs", "c-e1");
            stale.setStale(true);
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme", "c-acme"),
                            node("alice", "alice", "c-alice")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(stale));
            assertEquals(List.of("c-acme"), ids(provider().discover(partition(), 2, 20)));
        }

        @Test
        void aStaleNeighbourIsNotWalkedThoughTheEdgeToItStillCites() {
            GraphNode alice = GraphNode.builder().nodeId("alice").externalId("alice")
                    .nodeType(NodeLevel.ENTITY).stale(true)
                    .metadataJson("{\"_sourceChunkId\":\"c-alice\"}").build();
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme", "c-acme"), alice));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1")));
            List<String> found = ids(provider().discover(partition(), 2, 20));
            assertTrue(found.contains("c-e1"), "the edge itself is still a fact that was asserted");
            assertFalse(found.contains("c-alice"));
        }

        @Test
        void anEndpointMissingFromTheIndexEndsTheWalkWithoutLosingTheEdgesOwnChunk() {
            // Matrix-store edges carry hollow, id-only endpoints; if the far end is not in the
            // fact sheet's own nodes there is nothing to read from it.
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme", "c-acme")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "ghost", "employs", "c-e1")));
            assertEquals(List.of("c-acme", "c-e1"), ids(provider().discover(partition(), 2, 20)));
        }

        @Test
        void anEdgeNamingNeitherEndAsTheCurrentNodeIsNotFollowed() {
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme", "c-acme"),
                            node("alice", "alice", "c-alice")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "bob", "alice", "employs", "c-e1")));
            List<String> found = ids(provider().discover(partition(), 2, 20));
            assertTrue(found.contains("c-e1"));
            assertFalse(found.contains("c-alice"), "guessing a far end would fabricate a relation");
        }

        @Test
        void anEdgeWithNoChunkProvenanceCitesNothingButStillOpensTheWalk() {
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme", "c-acme"),
                            node("alice", "alice", "c-alice")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", null)));
            assertEquals(List.of("c-acme", "c-alice"),
                    ids(provider().discover(partition(), 2, 20)));
        }

        @Test
        void anExtractorThatWasUnsureOfARelationProducesWeakerEvidence() {
            GraphEdge unsure = edge("e1", "acme", "alice", "employs", "c-e1");
            unsure.setConfidence(0.5);
            when(graph.getNodesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(node("acme", "acme", "c-acme"),
                            node("alice", "alice", "c-alice")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(unsure));
            List<ChunkCandidate> found = provider().discover(partition(), 1, 20);
            assertEquals(GraphNeighbourhoodDiscoveryProvider.DEFAULT_BASE_CONFIDENCE
                            * GraphNeighbourhoodDiscoveryProvider.DEFAULT_HOP_DECAY * 0.5,
                    find(found, "c-e1").confidence(), 1e-9);
        }

        @Test
        void aStoreReturningNothingIsAnEmptyRoundNotAnError() {
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(null);
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }
    }

    @Nested
    @DisplayName("Convergence")
    class Convergence {

        @BeforeEach
        void graphOfThreeEntities() {
            when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(
                    node("acme", "acme", "c-acme"),
                    node("alice", "alice", "c-alice")));
            when(graph.getEdgesForNodeInFactSheet("acme", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1")));
        }

        @Test
        void reWalkingTheSameGroundProposesNothingSoTheFrontierCanReadAsExhausted() {
            // mergeWith accumulates reasons, so a repeat proposal would count as an upgrade and
            // the lifecycle would keep spending rounds on a neighbourhood it had already covered.
            EntityPartition partition = partition();
            List<ChunkCandidate> first = provider().discover(partition, 1, 20);
            assertFalse(first.isEmpty());
            EntityPartition after = admitAll(partition, first, 1);
            assertTrue(provider().discover(after, 1, 20).isEmpty());
        }

        @Test
        void aWiderRoundStillProposesWhatTheNarrowerOneCouldNotReach() {
            when(graph.getEdgesForNodeInFactSheet("alice", FACT_SHEET))
                    .thenReturn(List.of(edge("e2", "alice", "memo", "wrote", "c-e2")));
            EntityPartition partition = partition();
            EntityPartition after = admitAll(partition, provider().discover(partition, 1, 20), 1);
            assertEquals(List.of("c-e2"), ids(provider().discover(after, 2, 20)));
        }

        @Test
        void aChunkAlreadyHeldOnWeakerTermsIsStillUpgraded() {
            // A chunk a semantic round admitted at 0.4 has a structured reason now, and the
            // manifest should say so rather than remember it as a similarity hit.
            EntityPartition partition = partition().admit(
                    ChunkCandidate.of("c-acme", DiscoveryChannel.SEMANTIC, 0.4, "looked similar"),
                    MembershipState.DISCOVERED, 1);
            List<ChunkCandidate> found = provider().discover(partition, 1, 20);
            ChunkCandidate upgraded = find(found, "c-acme");
            assertNotNull(upgraded, "a stronger channel must be able to re-propose a known chunk");
            assertEquals(DiscoveryChannel.STRUCTURED_RELATIONSHIP, upgraded.channel());
        }

        @Test
        void anAlreadyProcessedChunkIsNotReProposedOnIdenticalTerms() {
            EntityPartition partition = partition();
            List<ChunkCandidate> first = provider().discover(partition, 1, 20);
            EntityPartition after = partition;
            for (ChunkCandidate candidate : first) {
                after = after.admit(candidate, MembershipState.PROCESSED, 1);
            }
            assertTrue(provider().discover(after, 1, 20).isEmpty());
        }

        @Test
        void aCycleIsWalkedOnceRatherThanForever() {
            when(graph.getEdgesForNodeInFactSheet("alice", FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "acme", "alice", "employs", "c-e1")));
            List<ChunkCandidate> found = provider().discover(partition(), 5, 20);
            assertEquals(3, found.size(), "expected each chunk once, got " + ids(found));
        }
    }
}
