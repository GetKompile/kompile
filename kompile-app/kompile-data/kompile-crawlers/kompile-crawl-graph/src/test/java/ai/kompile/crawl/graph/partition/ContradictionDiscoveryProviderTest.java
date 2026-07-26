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

import ai.kompile.core.graphrag.maintenance.GraphMaintenanceService;
import ai.kompile.core.graphrag.maintenance.model.Contradiction;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionSubjects;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The contradiction channel: making sure both sides of a disagreement are in the partition.
 *
 * <p>Its job is not to pick a winner — resolution does that — but to stop a partition inheriting
 * whichever side happened to be retrieved.</p>
 */
@DisplayName("Contradiction discovery")
class ContradictionDiscoveryProviderTest {

    private static final long FACT_SHEET = 11L;

    private GraphMaintenanceService maintenance;
    private KnowledgeGraphService graph;

    @BeforeEach
    void setUp() {
        maintenance = mock(GraphMaintenanceService.class);
        graph = mock(KnowledgeGraphService.class);
    }

    private ContradictionDiscoveryProvider provider() {
        return new ContradictionDiscoveryProvider(maintenance, graph,
                PartitionFactSheets.fromPinOrSnapshot());
    }

    private static EntityPartition partition() {
        return EntityPartition.open(PartitionKey.forEntity("acme", "v1", null))
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, String.valueOf(FACT_SHEET));
    }

    private static GraphEdge edge(String edgeId, String chunkId) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNode(GraphNode.builder().nodeId("entity_acme").build())
                .targetNode(GraphNode.builder().nodeId("entity_beta").build())
                .edgeType(EdgeType.USER_DEFINED)
                .relationType("headquarteredIn")
                .metadataJson(chunkId == null ? null : "{\"_sourceChunkId\":\"" + chunkId + "\"}")
                .build();
    }

    private static Contradiction conflict(String entityIdA, String entityIdB,
                                          List<String> conflictingEdgeIds) {
        return conflict(entityIdA, entityIdB, conflictingEdgeIds, List.of(), null, null);
    }

    private static Contradiction conflict(String entityIdA, String entityIdB,
                                          List<String> conflictingEdgeIds,
                                          List<String> candidateStaleEdgeIds,
                                          Double severity, String predicate) {
        return new Contradiction(
                entityIdA, entityIdB, "hq = London", "hq = Paris", "doc-old", "doc-new",
                Contradiction.ContradictionType.CONFLICTING_PROPERTY,
                Contradiction.Resolution.NEEDS_REVIEW,
                "cx-1", conflictingEdgeIds, candidateStaleEdgeIds, predicate, severity,
                null, null, null, null, null);
    }

    private static List<String> ids(List<ChunkCandidate> candidates) {
        return candidates.stream().map(ChunkCandidate::chunkId).toList();
    }

    private static ChunkCandidate find(List<ChunkCandidate> candidates, String chunkId) {
        return candidates.stream().filter(c -> c.chunkId().equals(chunkId)).findFirst()
                .orElse(null);
    }

    @Nested
    @DisplayName("When it runs at all")
    class Gating {

        @Test
        void itScansOnTheFirstRoundOnly() {
            // Detection is a scan of the whole fact sheet, and the conflicts it finds do not
            // change because a later round admitted more chunks.
            assertTrue(provider().discover(partition(), 2, 20).isEmpty());
            verifyNoInteractions(maintenance);
            verifyNoInteractions(graph);
        }

        @Test
        void aPartitionThatNamesNoFactSheetIsNotScanned() {
            EntityPartition unscoped =
                    EntityPartition.open(PartitionKey.forEntity("acme", "v1", null));
            assertTrue(provider().discover(unscoped, 1, 20).isEmpty());
            verifyNoInteractions(maintenance);
        }

        @Test
        void aNullPartitionProposesNothing() {
            assertTrue(provider().discover(null, 1, 20).isEmpty());
            verifyNoInteractions(maintenance);
        }

        @Test
        void theChannelItSuppliesIsTheContradictionOne() {
            assertEquals(DiscoveryChannel.CONTRADICTION, provider().channel());
        }

        @Test
        void withNoConflictsTheEdgeIndexIsNeverBuilt() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(List.of());
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
            verify(graph, never()).getEdgesInFactSheet(anyLong());
        }

        @Test
        void aDetectorReturningNullIsAnEmptyRoundNotAnError() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(null);
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }
    }

    @Nested
    @DisplayName("Which conflicts are about the subject")
    class SubjectMatching {

        @BeforeEach
        void oneResolvableEdge() {
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", "c1")));
        }

        @Test
        void anExactEntityIdMatches() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void eitherSideOfTheConflictMayBeTheSubject() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("beta", "acme", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void aNodeIdIsMatchedAgainstAnExternalIdSubject() {
            // Node ids follow <type>_<externalId>; a partition is usually keyed on the external
            // id alone. Matching only one spelling would make this channel silently empty.
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("entity_acme", "entity_beta", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void andAnExternalIdAgainstANodeIdSubject() {
            EntityPartition keyedOnNodeId =
                    EntityPartition.open(PartitionKey.forEntity("entity_acme", "v1", null))
                            .withPin(PartitionFactSheets.FACT_SHEET_PIN,
                                    String.valueOf(FACT_SHEET));
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(keyedOnNodeId, 1, 20)));
        }

        @Test
        void caseIsNotWhatDistinguishesTwoEntities() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("ACME", "beta", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void aConflictBetweenTwoOtherEntitiesIsNotThisPartitionsBusiness() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("gamma", "beta", List.of("e1"))));
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
            verify(graph, never()).getEdgesInFactSheet(anyLong());
        }

        @Test
        void aConflictWithNoEntityIdsAtAllMatchesNothing() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict(null, null, List.of("e1"))));
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }
    }

    @Nested
    @DisplayName("When the partition is about several subjects")
    class Membership {

        @BeforeEach
        void oneResolvableEdge() {
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", "c1")));
        }

        /** A group-keyed claim: its id names no node, so only the pin can say who it is about. */
        private EntityPartition group(String... subjects) {
            EntityPartition open = EntityPartition.open(PartitionKey.forGroup("acme+1", "v1", null))
                    .withPin(PartitionFactSheets.FACT_SHEET_PIN, String.valueOf(FACT_SHEET));
            return PartitionSubjects.pinnedOn(open, List.of(subjects));
        }

        @Test
        void aConflictTouchingAnyOneMemberIsTheWholeGroupsBusiness() {
            // Any, not all: requiring every member would hide exactly the disagreements grouping
            // put these subjects in one partition to compare.
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("beta", "gamma", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(group("acme", "beta"), 1, 20)));
        }

        @Test
        void aConflictTouchingNoMemberIsStillNotThisPartitionsBusiness() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("gamma", "delta", List.of("e1"))));
            assertTrue(provider().discover(group("acme", "beta"), 1, 20).isEmpty());
            verify(graph, never()).getEdgesInFactSheet(anyLong());
        }

        @Test
        void aConflictBetweenTwoMembersIsCitedOnceNotTwice() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(group("acme", "beta"), 1, 20)));
        }

        @Test
        void aGroupIdNamesNoNodeSoAnUnpinnedGroupPartitionIsNotScanned() {
            assertTrue(provider().discover(group(), 1, 20).isEmpty());
            verifyNoInteractions(maintenance);
        }

        @Test
        void thePinnedMembershipOutranksAKeyThatNamesADifferentSubject() {
            EntityPartition aboutBeta = PartitionSubjects.pinnedOn(partition(), List.of("beta"));
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "gamma", List.of("e1"))));
            assertTrue(provider().discover(aboutBeta, 1, 20).isEmpty(),
                    "the key's entity is not the membership");
        }

        @Test
        void anExplicitBindingSuppliesTheMembershipAClaimCannotStateYet() {
            ContradictionDiscoveryProvider bound = new ContradictionDiscoveryProvider(maintenance,
                    graph, PartitionFactSheets.fromPinOrSnapshot(),
                    PartitionSubjects.fixed(List.of("beta")),
                    ContradictionDiscoveryProvider.DEFAULT_CONFIDENCE);
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("beta", "gamma", List.of("e1"))));
            assertEquals(List.of("c1"), ids(bound.discover(group(), 1, 20)));
        }

        @Test
        void theFactSheetIsScannedOnceForTheWholeMembership() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "gamma", List.of("e1")),
                            conflict("beta", "delta", List.of("e1"))));
            assertEquals(List.of("c1"), ids(provider().discover(group("acme", "beta"), 1, 20)));
            verify(maintenance, times(1)).detectContradictions(FACT_SHEET);
            verify(graph, times(1)).getEdgesInFactSheet(FACT_SHEET);
        }
    }

    @Nested
    @DisplayName("Resolving conflicts to text")
    class Citing {

        @Test
        void bothSidesOfTheConflictAreProposed() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(
                    List.of(conflict("acme", "beta", List.of("e1", "e2"))));
            when(graph.getEdgesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "c-old"), edge("e2", "c-new")));
            assertEquals(List.of("c-old", "c-new"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void theEdgesProposedAsStaleAreCitedToo() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(
                    List.of(conflict("acme", "beta", List.of("e1"), List.of("e2"), null, null)));
            when(graph.getEdgesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "c-old"), edge("e2", "c-new")));
            assertEquals(List.of("c-old", "c-new"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void anEdgeNamedTwiceIsCitedOnce() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(
                    List.of(conflict("acme", "beta", List.of("e1"), List.of("e1"), null, null)));
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", "c1")));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void anEdgeIdThatResolvesToNothingIsSkipped() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("ghost", "e1"))));
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", "c1")));
            assertEquals(List.of("c1"), ids(provider().discover(partition(), 1, 20)));
        }

        @Test
        void aConflictWhoseEdgesRecordedNoChunkCannotBeCited() {
            // A real gap, not a silent one: the provider logs it rather than letting the partition
            // look as though it had considered the conflict.
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", null)));
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }

        @Test
        void theEdgeIndexIsBuiltOnceHoweverManyConflictsThereAre() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(List.of(
                    conflict("acme", "beta", List.of("e1")),
                    conflict("acme", "gamma", List.of("e2")),
                    conflict("acme", "delta", List.of("e1"))));
            when(graph.getEdgesInFactSheet(FACT_SHEET))
                    .thenReturn(List.of(edge("e1", "c1"), edge("e2", "c2")));
            assertEquals(2, provider().discover(partition(), 1, 20).size());
            verify(graph, times(1)).getEdgesInFactSheet(FACT_SHEET);
        }

        @Test
        void theCapIsAHardBound() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(
                    List.of(conflict("acme", "beta", List.of("e1", "e2", "e3"))));
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(
                    List.of(edge("e1", "c1"), edge("e2", "c2"), edge("e3", "c3")));
            assertEquals(2, provider().discover(partition(), 1, 2).size());
        }

        @Test
        void aStoreWithNoEdgesCitesNothing() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(null);
            assertTrue(provider().discover(partition(), 1, 20).isEmpty());
        }
    }

    @Nested
    @DisplayName("What the proposal says")
    class Attribution {

        @BeforeEach
        void oneResolvableEdge() {
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", "c1")));
        }

        @Test
        void severityIsTheConfidenceWhenTheDetectorMeasuredOne() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(List.of(
                    conflict("acme", "beta", List.of("e1"), List.of(), 0.9, "hq")));
            assertEquals(0.9, find(provider().discover(partition(), 1, 20), "c1").confidence(),
                    1e-9);
        }

        @Test
        void otherwiseTheChannelsOwnDefaultApplies() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals(ContradictionDiscoveryProvider.DEFAULT_CONFIDENCE,
                    find(provider().discover(partition(), 1, 20), "c1").confidence(), 1e-9);
        }

        @Test
        void aChunkOnTheWrongSideOfAConflictIsStillWorthSchedulingByDefault() {
            // Dropping it is exactly how a partition ends up confidently one-sided.
            assertTrue(ContradictionDiscoveryProvider.DEFAULT_CONFIDENCE > 0.5);
        }

        @Test
        void theReasonNamesThePredicateAndTheConflict() {
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(List.of(
                    conflict("acme", "beta", List.of("e1"), List.of(), 0.9, "hq")));
            assertEquals("contradicts admitted evidence on hq [cx-1]",
                    find(provider().discover(partition(), 1, 20), "c1").reason());
        }

        @Test
        void withNoPredicateTheConflictTypeIsNamedInstead() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals("contradicts admitted evidence (CONFLICTING_PROPERTY) [cx-1]",
                    find(provider().discover(partition(), 1, 20), "c1").reason());
        }

        @Test
        void everyProposalIsAttributedToTheContradictionChannel() {
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals(DiscoveryChannel.CONTRADICTION,
                    find(provider().discover(partition(), 1, 20), "c1").channel());
        }

        @Test
        void theDocumentComesFromTheEdgeWhenTheEdgeRecordedOne() {
            GraphEdge withDocument = GraphEdge.builder().edgeId("e1")
                    .edgeType(EdgeType.USER_DEFINED)
                    .metadataJson("{\"_sourceChunkId\":\"c1\",\"_sourceDocumentId\":\"doc-edge\"}")
                    .build();
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(withDocument));
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals("doc-edge",
                    find(provider().discover(partition(), 1, 20), "c1").documentId());
        }

        @Test
        void andFallsBackToTheDocumentTheDetectorNamedSoInvalidationStillHasAKey() {
            // Without a document link a chunk can never be invalidated when its source changes —
            // it goes on looking processed forever.
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
            assertEquals("doc-new",
                    find(provider().discover(partition(), 1, 20), "c1").documentId());
        }

        @Test
        void aConflictThatNamesNoDocumentAtAllLeavesTheLinkEmptyRatherThanInventingOne() {
            Contradiction noDocuments = new Contradiction(
                    "acme", "beta", "hq = London", "hq = Paris", null, null,
                    Contradiction.ContradictionType.CONFLICTING_PROPERTY,
                    Contradiction.Resolution.NEEDS_REVIEW,
                    "cx-1", List.of("e1"), List.of(), null, null,
                    null, null, null, null, null);
            when(maintenance.detectContradictions(FACT_SHEET)).thenReturn(List.of(noDocuments));
            assertNull(find(provider().discover(partition(), 1, 20), "c1").documentId());
        }
    }

    @Nested
    @DisplayName("Against an existing partition")
    class ExistingMembers {

        @BeforeEach
        void oneResolvableEdge() {
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(List.of(edge("e1", "c1")));
            when(maintenance.detectContradictions(FACT_SHEET))
                    .thenReturn(List.of(conflict("acme", "beta", List.of("e1"))));
        }

        @Test
        void aChunkAlreadyHeldOnTheseExactTermsIsNotReProposed() {
            EntityPartition partition = partition();
            List<ChunkCandidate> first = provider().discover(partition, 1, 20);
            assertEquals(1, first.size());
            EntityPartition after =
                    partition.admit(first.get(0), MembershipState.DISCOVERED, 1);
            assertTrue(provider().discover(after, 1, 20).isEmpty());
        }

        @Test
        void aChunkAlreadyAdmittedByAStrongerChannelStillGainsTheContradictionReason() {
            // The stronger channel keeps the attribution; what the partition gains is the note
            // that this chunk is on one side of a conflict.
            EntityPartition partition = partition().admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95,
                            "matched an alias"),
                    MembershipState.DISCOVERED, 1);
            List<ChunkCandidate> found = provider().discover(partition, 1, 20);
            assertNotNull(find(found, "c1"), "the conflict is new information about a known chunk");
        }
    }
}
