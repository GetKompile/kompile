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

package ai.kompile.crawl.graph;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.maintenance.GraphMaintenanceService;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryChannelProvider;
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionLifecycle;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionPhase;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.core.graphrag.partition.PartitionSubjects;
import ai.kompile.core.graphrag.partition.grouping.EntityGroup;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import ai.kompile.core.graphrag.partition.reuse.ExtractionReuse;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.PartitionRequest;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunAllResult;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunResult;
import ai.kompile.crawl.graph.partition.PartitionFactSheets;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seam where the partition control plane meets a crawl: which channels get built, what the
 * partition records about where it looked, and what happens when a deployment cannot supply a
 * channel the policy names.
 */
@DisplayName("Entity partition crawl service")
class EntityPartitionCrawlServiceTest {

    private static final long FACT_SHEET = 11L;

    private CrawlBatchPlanner planner;
    private KnowledgeGraphService graph;
    private GraphMaintenanceService maintenance;
    private ObjectProvider<VectorStore> vectorStores;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        planner = new CrawlBatchPlanner();
        graph = mock(KnowledgeGraphService.class);
        maintenance = mock(GraphMaintenanceService.class);
        vectorStores = mock(ObjectProvider.class);
        when(vectorStores.getIfAvailable()).thenReturn(null);
    }

    private EntityPartitionCrawlService service() {
        return new EntityPartitionCrawlService(planner, graph, maintenance, vectorStores);
    }

    /** The same service with a vector index behind it. */
    private EntityPartitionCrawlService serviceWithIndex() {
        when(vectorStores.getIfAvailable()).thenReturn(mock(VectorStore.class));
        return service();
    }

    private static PartitionRequest graphOnly(String subject) {
        return PartitionRequest.forEntity(subject, FACT_SHEET)
                .withPolicy(EntityPartitionCrawlService.graphOnlyPolicy());
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

    /** A two-node fact sheet: the subject, one relation out, one chunk apiece. */
    private void graphAboutAcme() {
        when(graph.getNodesInFactSheet(FACT_SHEET)).thenReturn(List.of(
                node("entity_acme", "acme", "c1"),
                node("entity_bob", "bob", "c2")));
        when(graph.getEdgesForNodeInFactSheet("entity_acme", FACT_SHEET))
                .thenReturn(List.of(edge("e1", "entity_acme", "entity_bob", "employs", "c3")));
    }

    private static List<DiscoveryChannel> channelsOf(List<DiscoveryChannelProvider> providers) {
        return providers.stream().map(DiscoveryChannelProvider::channel).toList();
    }

    /** An extracted entity; id is the extractor's per-chunk handle, not a graph id. */
    private static Entity extracted(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        return entity;
    }

    private static Relationship extractedLink(String source, String target, String type) {
        Relationship link = new Relationship();
        link.setSource(source);
        link.setTarget(target);
        link.setType(type);
        return link;
    }

    @Nested
    @DisplayName("The graph-only policy")
    class GraphOnlyPolicy {

        @Test
        void itNamesOnlyTheChannelsAGraphCanAnswer() {
            DiscoveryPolicy policy = EntityPartitionCrawlService.graphOnlyPolicy();
            assertTrue(policy.runs(DiscoveryChannel.STRUCTURED_RELATIONSHIP));
            assertTrue(policy.runs(DiscoveryChannel.CONTRADICTION));
            assertFalse(policy.runs(DiscoveryChannel.SEMANTIC));
            assertFalse(policy.runs(DiscoveryChannel.DIRECT_IDENTIFIER));
        }

        @Test
        void itsCoverageIsNotComparableToARunThatHadTheIndex() {
            // Different version, because a run that looked in fewer places did a different job.
            assertEquals(EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION,
                    EntityPartitionCrawlService.graphOnlyPolicy().version());
            assertNotEquals(DiscoveryPolicy.DEFAULT_VERSION,
                    EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION);
        }

        @Test
        void itKeepsTheSeedAndManualChannelsSoPinnedEvidenceStillCounts() {
            DiscoveryPolicy policy = EntityPartitionCrawlService.graphOnlyPolicy();
            assertTrue(policy.runs(DiscoveryChannel.SEED));
            assertTrue(policy.runs(DiscoveryChannel.MANUAL));
        }
    }

    @Nested
    @DisplayName("Assembling channels")
    class Providers {

        @Test
        void aGraphOnlyPolicyGetsTheTwoGraphChannels() {
            List<DiscoveryChannelProvider> providers =
                    service().providersFor(graphOnly("acme"));
            assertEquals(List.of(DiscoveryChannel.STRUCTURED_RELATIONSHIP,
                    DiscoveryChannel.CONTRADICTION), channelsOf(providers));
        }

        @Test
        void aPolicyNamingOneGraphChannelGetsOnlyThatOne() {
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET)
                    .withPolicy(DiscoveryPolicy.defaults().withChannels("v-struct",
                            DiscoveryChannel.SEED, DiscoveryChannel.STRUCTURED_RELATIONSHIP));
            assertEquals(List.of(DiscoveryChannel.STRUCTURED_RELATIONSHIP),
                    channelsOf(service().providersFor(request)));
        }

        @Test
        void aPolicyThatNeedsTheIndexIsRefusedWhenThereIsNone() {
            // The failure this design exists to prevent: reporting a subject covered when a whole
            // way of looking never ran.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> service().providersFor(PartitionRequest.forEntity("acme", FACT_SHEET)));
            assertTrue(refused.getMessage().contains(DiscoveryPolicy.DEFAULT_VERSION),
                    "the refusal should name the policy it could not honour: "
                            + refused.getMessage());
            assertTrue(refused.getMessage().contains("graphOnlyPolicy"),
                    "the refusal should point at the deliberate way to run without an index: "
                            + refused.getMessage());
        }

        @Test
        void withAnIndexEveryDefaultChannelIsBuilt() {
            List<DiscoveryChannel> channels = channelsOf(serviceWithIndex()
                    .providersFor(PartitionRequest.forEntity("acme", FACT_SHEET)));
            assertTrue(channels.containsAll(List.of(DiscoveryChannel.STRUCTURED_RELATIONSHIP,
                            DiscoveryChannel.CONTRADICTION, DiscoveryChannel.DIRECT_IDENTIFIER,
                            DiscoveryChannel.SEMANTIC)),
                    "expected all four default channels, got " + channels);
        }

        @Test
        void aPolicyThatAsksForNoDiscoveryAtAllIsNotRefused() {
            // Seed and manual evidence arrive from outside; a policy running only those needs no
            // provider and no index, and that is a coherent thing to ask for.
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET)
                    .withPolicy(DiscoveryPolicy.defaults().withChannels("v-pinned-only",
                            DiscoveryChannel.SEED, DiscoveryChannel.MANUAL));
            assertTrue(service().providersFor(request).isEmpty());
        }

        @Test
        void aDeploymentWithNoVectorStoreBeanAtAllSaysSoRatherThanFailing() {
            // No provider at all is a legitimate wiring, not a missing dependency.
            assertFalse(new EntityPartitionCrawlService(planner, graph, maintenance, null)
                    .hasVectorStore());
        }

        @Test
        void aProviderWithNothingBehindItReadsAsNoIndex() {
            assertFalse(service().hasVectorStore());
        }

        @Test
        void aProviderHoldingAStoreReadsAsAnIndex() {
            assertTrue(serviceWithIndex().hasVectorStore());
        }

        @Test
        void aCoordinatorIsBuiltFromTheRequestsOwnPolicy() {
            assertEquals(EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION,
                    service().coordinatorFor(graphOnly("acme")).policy().version());
        }
    }

    @Nested
    @DisplayName("Requests")
    class Requests {

        @Test
        void aPartitionNeedsASubject() {
            assertThrows(IllegalArgumentException.class,
                    () -> PartitionRequest.forEntity(null, FACT_SHEET));
            assertThrows(IllegalArgumentException.class,
                    () -> PartitionRequest.forEntity("   ", FACT_SHEET));
        }

        @Test
        void whatWasNotSaidGetsAWorkableDefault() {
            PartitionRequest request = PartitionRequest.forEntity("  acme  ", null);
            assertEquals("acme", request.subject());
            assertEquals(DiscoveryPolicy.DEFAULT_VERSION, request.policy().version());
            assertTrue(request.identifiers().isEmpty());
            assertTrue(request.chunks().isEmpty());
            assertEquals(EntityPartitionCrawlService.DEFAULT_MAX_ITEMS_PER_BATCH,
                    request.maxItemsPerBatch());
            assertEquals(0L, request.targetCostPerBatch());
        }

        @Test
        void aNonsenseBatchCapFallsBackRatherThanStallingTheRun() {
            PartitionRequest request =
                    PartitionRequest.forEntity("acme", FACT_SHEET).withBatching(0, -5L);
            assertEquals(EntityPartitionCrawlService.DEFAULT_MAX_ITEMS_PER_BATCH,
                    request.maxItemsPerBatch());
            assertEquals(0L, request.targetCostPerBatch());
        }

        @Test
        void identityIsSubjectAndPolicyAndSnapshot() {
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET);
            assertEquals(PartitionKey.forEntity("acme", DiscoveryPolicy.DEFAULT_VERSION, null),
                    request.key());

            // A different set of channels is a different claim, so a different partition.
            assertFalse(request.key().id().equals(graphOnly("acme").key().id()));
            // So is the same question asked of a different corpus snapshot.
            assertFalse(request.key().id()
                    .equals(request.withSnapshot("snap-2").key().id()));
        }

        @Test
        void aWitherChangesOneThingAndLeavesTheRest() {
            Map<String, Document> chunks = Map.of("c1", new Document("text"));
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET)
                    .withSnapshot("snap-1")
                    .withIdentifiers(List.of("Acme Corp", "ACME"))
                    .withChunks(chunks)
                    .withBatching(4, 500L)
                    .withPolicy(EntityPartitionCrawlService.graphOnlyPolicy());

            assertEquals("acme", request.subject());
            assertEquals(FACT_SHEET, request.factSheetId());
            assertEquals("snap-1", request.snapshotId());
            assertEquals(List.of("Acme Corp", "ACME"), request.identifiers());
            assertEquals(chunks.keySet(), request.chunks().keySet());
            assertEquals(4, request.maxItemsPerBatch());
            assertEquals(500L, request.targetCostPerBatch());
            assertEquals(EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION,
                    request.policy().version());
        }

        @Test
        void aRequestCannotBeMutatedThroughTheListItWasGiven() {
            List<String> identifiers = new ArrayList<>(List.of("Acme Corp"));
            PartitionRequest request =
                    PartitionRequest.forEntity("acme", FACT_SHEET).withIdentifiers(identifiers);
            identifiers.add("smuggled in later");
            assertEquals(List.of("Acme Corp"), request.identifiers());
        }
    }

    @Nested
    @DisplayName("Grouping")
    class Grouping {

        private static final EntityGroup PAIR =
                EntityGroup.of(List.of("entity_bob", "entity_acme"));

        /** Three entities, two of them related: the smallest fact sheet grouping can shape. */
        private void factSheetOfThree() {
            when(graph.getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY)).thenReturn(
                    List.of(node("entity_acme", "acme", "c1"),
                            node("entity_bob", "bob", "c2"),
                            node("entity_zed", "zed", "c3")));
            when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(
                    List.of(edge("e1", "entity_acme", "entity_bob", "employs", "c4")));
        }

        @Test
        void aGroupRequestIsNamedAfterTheGroupAndReadsAllOfIt() {
            PartitionRequest request =
                    PartitionRequest.forGroup(PAIR, GroupingPolicy.defaults(), FACT_SHEET);
            assertEquals(PAIR.id(), request.subject());
            assertEquals(PAIR.members(), request.members());
            assertEquals(GroupingPolicy.HYBRID_VERSION, request.groupingVersion());
            assertTrue(request.isGrouped());
        }

        @Test
        void aGroupPartitionIsKeyedAsAGroupBecauseItsIdNamesNoEntity() {
            PartitionRequest request =
                    PartitionRequest.forGroup(PAIR, GroupingPolicy.defaults(), FACT_SHEET);
            assertEquals(PartitionKey.forGroup(PAIR.id(), DiscoveryPolicy.DEFAULT_VERSION, null),
                    request.key());
            assertNull(request.key().entityId());
        }

        @Test
        void aGroupOfOneIsAnEntityPartition() {
            // Otherwise the same claim looks like a new one every time grouping happens to split
            // differently, and its coverage can never be compared to the last run's.
            PartitionRequest single = PartitionRequest.forGroup(
                    EntityGroup.ofSingle("entity_acme"), GroupingPolicy.defaults(), FACT_SHEET);
            assertFalse(single.isGrouped());
            assertEquals(
                    PartitionKey.forEntity("entity_acme", DiscoveryPolicy.DEFAULT_VERSION, null),
                    single.key());
        }

        @Test
        void anEntityRequestIsAboutItselfAndNoGroupingChoseIt() {
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET);
            assertEquals(List.of("acme"), request.members());
            assertNull(request.groupingVersion());
            assertFalse(request.isGrouped());
        }

        @Test
        void aMembershipOfNothingFallsBackToTheSubjectRatherThanAnEmptyClaim() {
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET)
                    .withMembers(List.of("   "), "grouping-v1-hybrid");
            assertEquals(List.of("acme"), request.members());
        }

        @Test
        void aSubjectNamedTwiceIsReadOnce() {
            PartitionRequest request = PartitionRequest.forEntity("acme", FACT_SHEET)
                    .withMembers(List.of("acme", " acme ", "beta"), "grouping-v1-hybrid");
            assertEquals(List.of("acme", "beta"), request.members());
        }

        @Test
        void theMembershipSurvivesEveryOtherWither() {
            PartitionRequest request =
                    PartitionRequest.forGroup(PAIR, GroupingPolicy.defaults(), FACT_SHEET)
                            .withSnapshot("snap-1")
                            .withBatching(4, 500L)
                            .withPolicy(EntityPartitionCrawlService.graphOnlyPolicy());
            assertEquals(PAIR.members(), request.members());
            assertEquals(GroupingPolicy.HYBRID_VERSION, request.groupingVersion());
        }

        @Test
        void groupingIsHybridByDefault() {
            factSheetOfThree();
            assertEquals(GroupingPolicy.HYBRID_VERSION,
                    service().groupingFor(FACT_SHEET, null).policy().version());
        }

        @Test
        void everySubjectOfTheFactSheetIsCoveredExactlyOnce() {
            factSheetOfThree();
            List<PartitionRequest> requests = service().groupedRequests(FACT_SHEET);
            List<String> covered = requests.stream().flatMap(r -> r.members().stream()).toList();
            assertEquals(3, covered.size(), "a subject must be neither dropped nor read twice: "
                    + covered);
            assertTrue(covered.containsAll(
                    List.of("entity_acme", "entity_bob", "entity_zed")), "got " + covered);
        }

        @Test
        void relatedSubjectsShareAPartitionAndAnUnrelatedOneDoesNot() {
            factSheetOfThree();
            List<PartitionRequest> requests = service().groupedRequests(FACT_SHEET);
            assertEquals(2, requests.size(), "expected the linked pair and the loner, got "
                    + requests.stream().map(PartitionRequest::members).toList());
            PartitionRequest pair = requests.stream().filter(PartitionRequest::isGrouped)
                    .findFirst().orElseThrow();
            assertTrue(pair.members().containsAll(List.of("entity_acme", "entity_bob")));
            assertFalse(pair.members().contains("entity_zed"));
        }

        @Test
        void everyGroupedRequestRecordsTheGroupingThatChoseIt() {
            factSheetOfThree();
            service().groupedRequests(FACT_SHEET).forEach(request ->
                    assertEquals(GroupingPolicy.HYBRID_VERSION, request.groupingVersion()));
        }

        @Test
        void theDiscoveryPolicyAskedForIsWhatEveryRequestRuns() {
            factSheetOfThree();
            List<PartitionRequest> requests = service().groupedRequests(FACT_SHEET,
                    GroupingPolicy.defaults(), EntityPartitionCrawlService.graphOnlyPolicy());
            assertFalse(requests.isEmpty());
            requests.forEach(request -> assertEquals(
                    EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION,
                    request.policy().version()));
        }

        @Test
        void perEntityGroupingGivesEachSubjectItsOwnRequest() {
            factSheetOfThree();
            List<PartitionRequest> requests = service().groupedRequests(FACT_SHEET,
                    GroupingPolicy.perEntity(), null);
            assertEquals(3, requests.size());
            requests.forEach(request -> assertFalse(request.isGrouped()));
        }

        @Test
        void anEmptyFactSheetProducesNoRequestsRatherThanFailing() {
            when(graph.getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY))
                    .thenReturn(List.of());
            assertTrue(service().groupedRequests(FACT_SHEET).isEmpty());
        }

        @Test
        void theChannelsOfAGroupedRequestReadEveryMemberNotJustTheGroupsName() {
            // The point of the whole seam: the group id names no node, so a channel bound only to
            // the subject would walk a graph looking for something that was never in it.
            graphAboutAcme();
            PartitionRequest request =
                    PartitionRequest.forGroup(PAIR, GroupingPolicy.defaults(), FACT_SHEET)
                            .withPolicy(EntityPartitionCrawlService.graphOnlyPolicy());
            DiscoveryChannelProvider walker = service().providersFor(request).stream()
                    .filter(p -> p.channel() == DiscoveryChannel.STRUCTURED_RELATIONSHIP)
                    .findFirst().orElseThrow();

            EntityPartition partition = EntityPartition.open(request.key())
                    .withPin(PartitionFactSheets.FACT_SHEET_PIN, String.valueOf(FACT_SHEET));
            List<String> found = walker.discover(partition, 1, 20).stream()
                    .map(ChunkCandidate::chunkId).toList();
            assertTrue(found.containsAll(List.of("c1", "c2")),
                    "expected both members' own chunks, got " + found);
        }

        @Test
        void aRunPartitionRecordsWhoItWasAboutAndHowItWasGrouped() {
            graphAboutAcme();
            PartitionRequest request =
                    PartitionRequest.forGroup(PAIR, GroupingPolicy.defaults(), FACT_SHEET)
                            .withPolicy(EntityPartitionCrawlService.graphOnlyPolicy());
            PartitionStore store = PartitionStore.inMemory();

            service().run(request, store,
                    (member, partition) -> PartitionLifecycle.ProcessOutcome.processed());

            EntityPartition saved = store.load(request.key().id()).orElseThrow();
            assertEquals(PAIR.members(), PartitionSubjects.resolve(saved),
                    "the membership has to be readable back off the claim");
            assertEquals(GroupingPolicy.HYBRID_VERSION, PartitionSubjects.groupingVersion(saved));
            assertEquals(String.valueOf(FACT_SHEET),
                    saved.pins().get(PartitionFactSheets.FACT_SHEET_PIN));
        }

        @Test
        void aSingleEntityClaimDoesNotRepeatItsOwnKeyInAPin() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            PartitionRequest request = graphOnly("acme");

            service().run(request, store,
                    (member, partition) -> PartitionLifecycle.ProcessOutcome.processed());

            EntityPartition saved = store.load(request.key().id()).orElseThrow();
            assertNull(saved.pins().get(PartitionSubjects.SUBJECTS_PIN),
                    "a pin repeating the key is a second answer to a question the key answers");
            assertEquals(List.of("acme"), PartitionSubjects.resolve(saved));
        }
    }

    @Nested
    @DisplayName("Running a partition")
    class Running {

        private final List<String> read = new ArrayList<>();

        private PartitionLifecycle.ChunkProcessor recording() {
            return (member, partition) -> {
                read.add(member.chunkId());
                return PartitionLifecycle.ProcessOutcome.processed();
            };
        }

        @Test
        void theFactSheetIsPinnedOntoTheClaimBeforeAnythingLooks() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            PartitionRequest request = graphOnly("acme");

            service().run(request, store, recording());

            EntityPartition saved = store.load(request.key().id()).orElseThrow();
            assertEquals(String.valueOf(FACT_SHEET),
                    saved.pins().get(PartitionFactSheets.FACT_SHEET_PIN),
                    "the claim should record the scope its evidence came from");
            verify(graph, atLeastOnce()).getNodesInFactSheet(FACT_SHEET);
        }

        @Test
        void aRequestWithNoFactSheetPinsNothingAndWalksNothing() {
            PartitionRequest request = PartitionRequest.forEntity("acme", null)
                    .withPolicy(EntityPartitionCrawlService.graphOnlyPolicy());
            PartitionStore store = PartitionStore.inMemory();

            service().run(request, store, recording());

            assertTrue(store.load(request.key().id()).orElseThrow().pins().isEmpty());
            verify(graph, never()).getNodesInFactSheet(anyLong());
        }

        @Test
        void evidenceReachableFromTheSubjectIsFoundAndRead() {
            graphAboutAcme();
            PartitionLifecycle.Result result =
                    service().run(graphOnly("acme"), PartitionStore.inMemory(), recording());

            // The subject's own chunk, the relation's chunk, and the neighbour's chunk.
            assertEquals(List.of("c1", "c2", "c3"), read.stream().sorted().toList());
            assertEquals(3, result.processed());
            assertTrue(result.frontierExhausted());
        }

        @Test
        void aClaimThatCoveredEverythingItFoundCloses() {
            graphAboutAcme();
            PartitionLifecycle.Result result =
                    service().run(graphOnly("acme"), PartitionStore.inMemory(), recording());

            assertTrue(result.isProvisionallyComplete(), result.describe());
            assertEquals(PartitionPhase.CLOSED, result.partition().phase());
            assertTrue(result.channelFailures().isEmpty());
        }

        @Test
        void theMostConfidentEvidenceIsReadFirst() {
            graphAboutAcme();
            service().run(graphOnly("acme"), PartitionStore.inMemory(), recording());

            // c1 came out of the subject's own extraction; the others are a hop away.
            assertEquals("c1", read.get(0));
        }

        @Test
        void aSubjectWithNothingInTheGraphStillProducesAClaimSayingSo() {
            PartitionLifecycle.Result result =
                    service().run(graphOnly("nobody"), PartitionStore.inMemory(), recording());

            assertTrue(read.isEmpty());
            assertEquals(0, result.processed());
            assertTrue(result.frontierExhausted(), "it looked, and there was nothing there");
            assertEquals(1, result.rounds(), "nothing to expand into means one round is enough");
        }

        @Test
        void aChunkThatBlowsUpIsHeldOpenRatherThanLost() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            PartitionRequest request = graphOnly("acme");

            PartitionLifecycle.Result result = service().run(request, store,
                    (member, partition) -> {
                        throw new IllegalStateException("extractor is down");
                    });

            assertTrue(result.failed() >= 3, "every chunk failed, got " + result.failed());
            assertFalse(result.isProvisionallyComplete(),
                    "a run that could not read its evidence is not complete");
            PartitionMember member = store.load(request.key().id()).orElseThrow()
                    .member("c1").orElseThrow();
            assertEquals(MembershipState.DEFERRED, member.state());
            assertNotNull(member.note());
            assertTrue(member.note().contains("extractor is down"), member.note());
        }

        @Test
        void aSecondRunOverTheSameStoreDoesNotRereadWhatIsDone() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            PartitionRequest request = graphOnly("acme");
            EntityPartitionCrawlService service = service();

            service.run(request, store, recording());
            int firstPass = read.size();
            read.clear();

            PartitionLifecycle.Result second = service.run(request, store, recording());

            assertEquals(3, firstPass);
            assertTrue(read.isEmpty(), "already-processed chunks were read again: " + read);
            assertEquals(0, second.processed());
            assertTrue(second.isProvisionallyComplete());
        }

        @Test
        void aRunWithNoStoreStillWorksAndSimplyDoesNotOutliveItself() {
            graphAboutAcme();
            PartitionLifecycle.Result result =
                    service().run(graphOnly("acme"), null, recording());
            assertEquals(3, result.processed());
        }

        @Test
        void aRunNeedsARequest() {
            assertThrows(NullPointerException.class,
                    () -> service().run(null, PartitionStore.inMemory(), recording()));
        }

        @Test
        void batchesArePricedFromTheChunksTheRequestCarries() {
            graphAboutAcme();
            // A cost cap this tight forces one chunk per batch, which the run must still finish.
            PartitionRequest request = graphOnly("acme")
                    .withChunks(Map.of(
                            "c1", new Document("a fairly long piece of chunk text to price"),
                            "c2", new Document("short"),
                            "c3", new Document("also short")))
                    .withBatching(8, 12L);

            PartitionLifecycle.Result result =
                    service().run(request, PartitionStore.inMemory(), recording());
            assertEquals(3, result.processed());
            assertEquals(List.of("c1", "c2", "c3"), read.stream().sorted().toList());
        }
    }

    @Nested
    @DisplayName("Running several subjects")
    class RunningAll {

        @Test
        void oneSubjectFailingIsNotTheOthersFailing() {
            graphAboutAcme();
            AtomicInteger reads = new AtomicInteger();
            // "beta" asks for the semantic index, which this deployment does not have.
            List<PartitionRequest> requests = List.of(
                    graphOnly("acme"),
                    PartitionRequest.forEntity("beta", FACT_SHEET));

            Map<String, PartitionLifecycle.Result> results = service().runAll(requests, null,
                    (member, partition) -> {
                        reads.incrementAndGet();
                        return PartitionLifecycle.ProcessOutcome.processed();
                    });

            assertEquals(List.of("acme"), List.copyOf(results.keySet()),
                    "the refused subject should be absent, not silently reported as covered");
            assertEquals(3, reads.get());
        }

        @Test
        void subjectsComeBackInTheOrderTheyWereAskedFor() {
            graphAboutAcme();
            Map<String, PartitionLifecycle.Result> results = service().runAll(
                    List.of(graphOnly("acme"), graphOnly("bob")), PartitionStore.inMemory(),
                    (member, partition) -> PartitionLifecycle.ProcessOutcome.processed());

            assertEquals(List.of("acme", "bob"), List.copyOf(results.keySet()));
        }

        @Test
        void oneStoreIsSharedSoLaterSubjectsSeeEarlierWork() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            service().runAll(List.of(graphOnly("acme"), graphOnly("bob")), store,
                    (member, partition) -> PartitionLifecycle.ProcessOutcome.processed());

            assertTrue(store.load(graphOnly("acme").key().id()).isPresent());
            assertTrue(store.load(graphOnly("bob").key().id()).isPresent());
            assertEquals(2, store.findByPolicy(
                    EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION).size());
        }

        @Test
        void nothingAskedForIsNothingRun() {
            EntityPartitionCrawlService service = service();
            PartitionLifecycle.ChunkProcessor processor =
                    (member, partition) -> PartitionLifecycle.ProcessOutcome.processed();
            assertTrue(service.runAll(null, null, processor).isEmpty());
            assertTrue(service.runAll(List.of(), null, processor).isEmpty());
        }

        @Test
        void aNullRequestInTheListIsSkippedRatherThanFatal() {
            graphAboutAcme();
            List<PartitionRequest> requests = new ArrayList<>();
            requests.add(null);
            requests.add(graphOnly("acme"));

            assertEquals(List.of("acme"), List.copyOf(service().runAll(requests, null,
                    (member, partition) -> PartitionLifecycle.ProcessOutcome.processed())
                    .keySet()));
        }
    }

    @Nested
    @DisplayName("Running a partition into a staged graph")
    class RunningStaged {

        private final List<Graph> committed = new ArrayList<>();

        /** A sink that records what it was handed and claims it took all of it. */
        private GraphCommitSink recordingSink() {
            return (key, graph) -> {
                committed.add(graph);
                return GraphCommitSink.CommitOutcome.of(graph.getEntities().size(),
                        graph.getRelationships().size());
            };
        }

        /** Every chunk says the same thing about the same company. */
        private EntityPartitionCrawlService.StagedExtractor sameCompany() {
            return (member, partition) -> Graph.builder()
                    .entities(List.of(extracted("x1", "Acme Corp", "ORGANIZATION")))
                    .build();
        }

        private List<String> stagedChunks(StagedRunResult result) {
            return List.copyOf(result.transaction().staged().chunkIds());
        }

        @Test
        void whatEveryChunkSaidAboutOneSubjectBecomesOneEntity() {
            graphAboutAcme();

            StagedRunResult result = service().runStaged(graphOnly("acme"),
                    PartitionStore.inMemory(), sameCompany(), recordingSink());

            assertEquals(1, result.transaction().staged().entities().size(),
                    "three chunks naming the same company should not make three entities");
            assertEquals(List.of("c1", "c2", "c3"), stagedChunks(result).stream().sorted().toList(),
                    "the merged entity should carry every chunk that mentioned it");
        }

        @Test
        void theGraphHearsAboutThePartitionOnceNotOncePerChunk() {
            graphAboutAcme();

            service().runStaged(graphOnly("acme"), PartitionStore.inMemory(), sameCompany(),
                    recordingSink());

            assertEquals(1, committed.size(), "one partition, one write");
            assertEquals(1, committed.get(0).getEntities().size());
        }

        @Test
        void theCommittedGraphIsNamedForThePartitionItCameFrom() {
            graphAboutAcme();
            PartitionRequest request = graphOnly("acme");

            StagedRunResult result = service().runStaged(request, PartitionStore.inMemory(),
                    sameCompany(), recordingSink());

            assertEquals(request.key().id(), committed.get(0).getName());
            assertEquals(FACT_SHEET, committed.get(0).getFactSheetId());
            assertEquals(request.key(), result.transaction().key());
        }

        @Test
        void relationshipsAreRewrittenOntoTheMergedEndpoints() {
            graphAboutAcme();
            EntityPartitionCrawlService.StagedExtractor linking = (member, partition) ->
                    Graph.builder()
                            .entities(List.of(extracted("a", "Acme Corp", null),
                                    extracted("b", "Bob", null)))
                            .relationships(List.of(extractedLink("a", "b", "employs")))
                            .build();

            service().runStaged(graphOnly("acme"), PartitionStore.inMemory(), linking,
                    recordingSink());

            Graph written = committed.get(0);
            assertEquals(2, written.getEntities().size());
            assertEquals(1, written.getRelationships().size(),
                    "the same assertion from three chunks is one edge");
            List<String> ids = written.getEntities().stream().map(Entity::getId).sorted().toList();
            Relationship edge = written.getRelationships().get(0);
            assertTrue(ids.contains(edge.getSource()) && ids.contains(edge.getTarget()),
                    "endpoints must be the merged keys, not the extractor's per-chunk ids: "
                            + edge.getSource() + " -> " + edge.getTarget() + " among " + ids);
        }

        @Test
        void chunksThatDisagreeLeaveAReceiptRatherThanOverwritingEachOther() {
            graphAboutAcme();
            // c1 is read first, so it owns the type; the later chunks disagree.
            EntityPartitionCrawlService.StagedExtractor disagreeing = (member, partition) ->
                    Graph.builder()
                            .entities(List.of(extracted("x1", "Acme Corp",
                                    "c1".equals(member.chunkId()) ? "COMPANY" : "ORGANIZATION")))
                            .build();

            StagedRunResult result = service().runStaged(graphOnly("acme"),
                    PartitionStore.inMemory(), disagreeing, recordingSink());

            assertFalse(result.commit().conflicts().isEmpty(),
                    "a type disagreement between chunks should be recorded");
            assertEquals("COMPANY", committed.get(0).getEntities().get(0).getType(),
                    "the first chunk to state a field keeps it");
        }

        @Test
        void aChunkThatExtractedNothingIsNotAFailure() {
            graphAboutAcme();
            EntityPartitionCrawlService.StagedExtractor quietOnC2 = (member, partition) ->
                    "c2".equals(member.chunkId()) ? null
                            : Graph.builder()
                                    .entities(List.of(extracted(null, "Acme Corp", null)))
                                    .build();

            StagedRunResult result = service().runStaged(graphOnly("acme"),
                    PartitionStore.inMemory(), quietOnC2, recordingSink());

            assertEquals(3, result.run().processed(), "an empty extraction is still a read chunk");
            assertTrue(result.isCommittedAndComplete(), result.describe());
            assertFalse(stagedChunks(result).contains("c2"));
        }

        @Test
        void aChunkWhoseExtractorBlewUpIsLeftOutButTheRestStillLands() {
            graphAboutAcme();
            EntityPartitionCrawlService.StagedExtractor failingOnC2 = (member, partition) -> {
                if ("c2".equals(member.chunkId())) {
                    throw new IllegalStateException("extractor is down");
                }
                return Graph.builder()
                        .entities(List.of(extracted(null, "Acme Corp", null)))
                        .build();
            };

            StagedRunResult result = service().runStaged(graphOnly("acme"),
                    PartitionStore.inMemory(), failingOnC2, recordingSink());

            List<String> staged = stagedChunks(result);
            assertTrue(staged.containsAll(List.of("c1", "c3")), staged.toString());
            assertFalse(staged.contains("c2"), "a chunk that blew up has nothing to contribute");
            assertNotNull(result.commit(), "chunks that were genuinely read are still evidence");
            assertFalse(result.isCommittedAndComplete(),
                    "a partition that could not read everything has not closed");
        }

        @Test
        void aSubjectWithNoEvidenceCommitsNothingAndSaysSo() {
            StagedRunResult result = service().runStaged(graphOnly("nobody"),
                    PartitionStore.inMemory(), sameCompany(), recordingSink());

            assertTrue(committed.isEmpty(), "an empty staged graph should not reach the writer");
            assertEquals(0, result.commit().outcome().entities());
            assertTrue(result.commit().notes().contains("nothing was staged"),
                    result.commit().describe());
        }

        @Test
        void aRunWithNoSinkHandsBackAnOpenTransaction() {
            graphAboutAcme();

            StagedRunResult result = service().runStaged(graphOnly("acme"),
                    PartitionStore.inMemory(), sameCompany(), null);

            assertNull(result.commit());
            assertTrue(result.transaction().isOpen(),
                    "a caller that wants to inspect before writing must still be able to");
            assertFalse(result.transaction().staged().isEmpty());
            assertFalse(result.isCommittedAndComplete(), "nothing was written yet");
        }

        @Test
        void aRunThatFallsOverWritesNothingAtAll() {
            graphAboutAcme();
            // The partition store gives out after the run is underway, so chunks have been staged
            // by the time it happens.
            PartitionStore failing = new PartitionStore() {
                private final PartitionStore delegate = PartitionStore.inMemory();
                private int saves;

                @Override
                public Optional<EntityPartition> load(String partitionId) {
                    return delegate.load(partitionId);
                }

                @Override
                public void save(EntityPartition partition) {
                    if (++saves > 3) {
                        throw new IllegalStateException("store is gone");
                    }
                    delegate.save(partition);
                }

                @Override
                public void delete(String partitionId) {
                    delegate.delete(partitionId);
                }

                @Override
                public List<EntityPartition> findByPolicy(String policyVersion) {
                    return delegate.findByPolicy(policyVersion);
                }

                @Override
                public List<EntityPartition> findByDocument(String documentId) {
                    return delegate.findByDocument(documentId);
                }

                @Override
                public List<EntityPartition> findAll() {
                    return delegate.findAll();
                }
            };

            assertThrows(IllegalStateException.class, () -> service().runStaged(graphOnly("acme"),
                    failing, sameCompany(), recordingSink()));
            assertTrue(committed.isEmpty(),
                    "a half-finished run must not leave half a partition in the graph");
        }

        @Test
        void aStagedRunNeedsBothARequestAndAnExtractor() {
            assertThrows(NullPointerException.class, () -> service().runStaged(null,
                    PartitionStore.inMemory(), sameCompany(), recordingSink()));
            assertThrows(NullPointerException.class, () -> service().runStaged(graphOnly("acme"),
                    PartitionStore.inMemory(), null, recordingSink()));
        }

        @Test
        void theClaimIsStillDurableWhenTheRunAlsoWrites() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            PartitionRequest request = graphOnly("acme");

            service().runStaged(request, store, sameCompany(), recordingSink());

            EntityPartition saved = store.load(request.key().id()).orElseThrow();
            assertEquals(PartitionPhase.CLOSED, saved.phase());
            assertEquals(3, saved.memberList().size());
        }
    }

    @Nested
    @DisplayName("Reusing an extraction two chunks both deserve")
    class Reusing {

        /** c1 and c2 are overlapping windows over one sentence; c3 says something else. */
        private final String shared = "Acme Corp employs Bob.";

        private final List<String> read = new ArrayList<>();
        private final List<Graph> committed = new ArrayList<>();

        /** Records every chunk the extractor was actually asked to read. */
        private EntityPartitionCrawlService.StagedExtractor counting() {
            return (member, partition) -> {
                read.add(member.chunkId());
                return Graph.builder()
                        .entities(List.of(extracted("x1", "Acme Corp", "ORGANIZATION")))
                        .build();
            };
        }

        private GraphCommitSink recordingSink() {
            return (key, graph) -> {
                committed.add(graph);
                return GraphCommitSink.CommitOutcome.of(graph.getEntities().size(),
                        graph.getRelationships().size());
            };
        }

        /** The same request, with the text a run needs to tell one chunk's work from another's. */
        private PartitionRequest withText(String subject) {
            return graphOnly(subject).withChunks(Map.of(
                    "c1", new Document(shared),
                    "c2", new Document("  Acme Corp   employs Bob. "),
                    "c3", new Document("Bob signed the lease in March.")));
        }

        private List<String> stagedChunks(StagedRunResult result) {
            return result.transaction().staged().chunkIds().stream().sorted().toList();
        }

        @Test
        void twoChunksHoldingTheSameWordsAreReadOnce() {
            graphAboutAcme();

            StagedRunResult result = service().runStaged(withText("acme"),
                    PartitionStore.inMemory(), counting(), recordingSink());

            assertEquals(2, read.size(),
                    "the same sentence in two windows is one question, not two: " + read);
            assertEquals(List.of("c1", "c2", "c3"), stagedChunks(result),
                    "a reuse saves the model call, never the evidence");
            assertTrue(result.isCommittedAndComplete(), result.describe());
        }

        @Test
        void theChunkThatSkippedTheReadStillSaysWhereItsAnswerCameFrom() {
            graphAboutAcme();
            PartitionStore store = PartitionStore.inMemory();
            PartitionRequest request = withText("acme");

            service().runStaged(request, store, counting(), recordingSink());

            EntityPartition saved = store.load(request.key().id()).orElseThrow();
            List<String> reused = new ArrayList<>();
            for (String chunkId : List.of("c1", "c2")) {
                String note = saved.member(chunkId).map(PartitionMember::note).orElse("");
                if (note != null && note.contains("reused extraction")) {
                    reused.add(chunkId);
                }
            }
            assertEquals(1, reused.size(),
                    "exactly one of the two identical windows was read; the other should say so");
            assertTrue(saved.member(reused.get(0)).orElseThrow().note().contains("first read for"),
                    "the note should name the chunk whose read paid for it");
        }

        @Test
        void aRunWithoutTheChunkTextReadsEveryChunk() {
            graphAboutAcme();

            service().runStaged(graphOnly("acme"), PartitionStore.inMemory(), counting(),
                    recordingSink());

            assertEquals(3, read.size(),
                    "with no text to compare, nothing can be shown identical: " + read);
        }

        @Test
        void aRunHandedNoLedgerStillGetsOne() {
            graphAboutAcme();

            service().runStaged(withText("acme"), PartitionStore.inMemory(), counting(),
                    recordingSink(), null);

            assertEquals(2, read.size(), "reuse is not something a caller has to switch on");
        }

        @Test
        void aChunkTwoPartitionsBothClaimIsReadOnceForBoth() {
            graphAboutAcme();

            StagedRunAllResult result = service().runAllStaged(
                    List.of(withText("acme"), withText("bob")), PartitionStore.inMemory(),
                    counting(), recordingSink());

            assertEquals(2, read.size(), "bob's only chunk was already read for acme: " + read);
            assertEquals(List.of("acme", "bob"), List.copyOf(result.runs().keySet()));
            assertEquals(2, result.reuse().firstRuns());
            assertEquals(2, result.reuse().reuses(), "c2 within acme, and again for bob");
            assertTrue(result.isCommittedAndComplete(), result.describe());
            assertTrue(result.describe().contains("reused"), result.describe());
        }

        @Test
        void everyPartitionStillWritesItsOwnGraph() {
            graphAboutAcme();
            PartitionRequest acme = withText("acme");
            PartitionRequest bob = withText("bob");

            service().runAllStaged(List.of(acme, bob), PartitionStore.inMemory(), counting(),
                    recordingSink());

            assertEquals(List.of(acme.key().id(), bob.key().id()),
                    committed.stream().map(Graph::getName).toList(),
                    "a partition that reused an answer still has to state it under its own name");
            assertTrue(committed.stream().allMatch(g -> g.getEntities().size() == 1),
                    "the reusing partition staged the remembered graph, it did not skip the chunk");
        }

        @Test
        void aLedgerTheCallerKeepsOutlivesTheRun() {
            graphAboutAcme();
            ExtractionReuse reuse = ExtractionReuse.forRun();

            service().runStaged(withText("acme"), PartitionStore.inMemory(), counting(),
                    recordingSink(), reuse);
            read.clear();
            StagedRunResult second = service().runStaged(withText("acme"),
                    PartitionStore.inMemory(), counting(), recordingSink(), reuse);

            assertTrue(read.isEmpty(), "a second run over the same text asks nothing new: " + read);
            assertEquals(List.of("c1", "c2", "c3"), stagedChunks(second),
                    "nothing was re-read, and nothing was left out");
            assertEquals(2, reuse.stats().firstRuns(), "two distinct texts across six chunks");
            assertEquals(4, reuse.stats().reuses(), "one in the first run, all three in the second");
        }

        @Test
        void nothingAskedForIsNothingRun() {
            StagedRunAllResult none = service().runAllStaged(List.of(), null, counting(),
                    recordingSink());

            assertTrue(none.runs().isEmpty());
            assertTrue(none.failed().isEmpty(), "nothing was asked for, so nothing went missing");
            assertEquals(0, none.reuse().total());
            assertFalse(none.isCommittedAndComplete(), "no partitions is not a closed partition");
            assertTrue(service().runAllStaged(null, null, counting(), recordingSink())
                    .runs().isEmpty());
        }

        @Test
        void aNullRequestInTheListIsSkippedRatherThanFatal() {
            graphAboutAcme();
            List<PartitionRequest> requests = new ArrayList<>();
            requests.add(null);
            requests.add(withText("acme"));

            StagedRunAllResult result = service().runAllStaged(requests, PartitionStore.inMemory(),
                    counting(), recordingSink());

            assertEquals(List.of("acme"), List.copyOf(result.runs().keySet()));
        }

        @Test
        void aSubjectWhoseRunFallsOverDoesNotTakeTheOthersWithIt() {
            graphAboutAcme();
            PartitionStore refusingBob = new PartitionStore() {
                private final PartitionStore delegate = PartitionStore.inMemory();

                @Override
                public Optional<EntityPartition> load(String partitionId) {
                    return delegate.load(partitionId);
                }

                @Override
                public void save(EntityPartition partition) {
                    if (partition.id().contains("bob")) {
                        throw new IllegalStateException("store is gone");
                    }
                    delegate.save(partition);
                }

                @Override
                public void delete(String partitionId) {
                    delegate.delete(partitionId);
                }

                @Override
                public List<EntityPartition> findByPolicy(String policyVersion) {
                    return delegate.findByPolicy(policyVersion);
                }

                @Override
                public List<EntityPartition> findByDocument(String documentId) {
                    return delegate.findByDocument(documentId);
                }

                @Override
                public List<EntityPartition> findAll() {
                    return delegate.findAll();
                }
            };

            StagedRunAllResult result = service().runAllStaged(
                    List.of(withText("acme"), withText("bob")), refusingBob, counting(),
                    recordingSink());

            assertEquals(List.of("acme"), List.copyOf(result.runs().keySet()),
                    "one subject the store cannot hold is not the whole run");
            assertEquals(List.of("bob"), result.failed(),
                    "the subject that was asked for and never answered has to be named");
            assertEquals(1, committed.size(), "the run that fell over must not have written");
            assertTrue(result.runs().get("acme").isCommittedAndComplete(),
                    "the partition that did close still closed");
            assertFalse(result.isCommittedAndComplete(),
                    "a run missing a subject it was asked for has not finished");
            assertTrue(result.describe().contains("failed: bob"), result.describe());
        }
    }
}
