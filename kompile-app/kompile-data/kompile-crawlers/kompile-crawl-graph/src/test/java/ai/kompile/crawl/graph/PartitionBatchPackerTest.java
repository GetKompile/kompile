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

import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the crawl's cost planner meets the partition's evidence ordering.
 *
 * <p>The property under test throughout: balancing may decide what shares a batch, and may not
 * decide what is read first.</p>
 */
@DisplayName("Partition batch packing")
class PartitionBatchPackerTest {

    private CrawlBatchPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new CrawlBatchPlanner();
    }

    private static PartitionMember member(String chunkId, double confidence) {
        return PartitionMember.admit(
                ChunkCandidate.of(chunkId, DiscoveryChannel.SEMANTIC, confidence, "found"),
                MembershipState.DISCOVERED, 1);
    }

    /** A member of an ordered sequence — a process trace or a time series. */
    private static PartitionMember ordered(String chunkId, String orderKey) {
        return PartitionMember.admit(
                ChunkCandidate.of(chunkId, DiscoveryChannel.PROCESS, 0.7, "step")
                        .withOrderKey(orderKey),
                MembershipState.DISCOVERED, 1);
    }

    /** Members named c1..cN, in descending confidence, as a stratum arrives. */
    private static List<PartitionMember> stratum(int size) {
        List<PartitionMember> members = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            members.add(member("c" + i, 1.0 - (i * 0.01)));
        }
        return members;
    }

    private static List<String> ids(List<PartitionMember> batch) {
        return batch.stream().map(PartitionMember::chunkId).toList();
    }

    private static List<List<String>> shape(List<List<PartitionMember>> packed) {
        return packed.stream().map(PartitionBatchPackerTest::ids).toList();
    }

    private static List<String> flatten(List<List<PartitionMember>> packed) {
        List<String> all = new ArrayList<>();
        for (List<PartitionMember> batch : packed) {
            all.addAll(ids(batch));
        }
        return all;
    }

    /** Costs by chunk id, so a test can make one member deliberately heavy. */
    private static Function<PartitionMember, Long> costs(Map<String, Long> byId) {
        return member -> byId.getOrDefault(member.chunkId(), 1L);
    }

    /** Chunks are named c1..cN in the order the stratum presents them. */
    private static int rank(String chunkId) {
        return Integer.parseInt(chunkId.substring(1));
    }

    /**
     * The guarantee packing owes callers: within a batch, and across batches, evidence is still
     * read in the order the stratum put it in. Balancing may change who shares a batch — nothing
     * more.
     */
    private static void assertBatchesInEvidenceOrder(List<List<PartitionMember>> packed) {
        int previousLead = Integer.MIN_VALUE;
        for (List<PartitionMember> batch : packed) {
            List<String> batchIds = ids(batch);
            for (int i = 1; i < batchIds.size(); i++) {
                assertTrue(rank(batchIds.get(i - 1)) < rank(batchIds.get(i)),
                        "batch " + batchIds + " is out of evidence order in " + shape(packed));
            }
            int lead = rank(batchIds.get(0));
            assertTrue(previousLead < lead,
                    "batches are out of evidence order: " + shape(packed));
            previousLead = lead;
        }
    }

    @Nested
    @DisplayName("By size")
    class BySize {

        @Test
        void anEmptyStratumPacksIntoNothing() {
            assertTrue(PartitionBatchPacker.bySize(planner, 4).pack(List.of()).isEmpty());
            assertTrue(PartitionBatchPacker.bySize(planner, 4).pack(null).isEmpty());
        }

        @Test
        void theCapIsRespectedAndTheOrderIsUntouched() {
            List<List<PartitionMember>> packed =
                    PartitionBatchPacker.bySize(planner, 2).pack(stratum(5));
            assertEquals(List.of(List.of("c1", "c2"), List.of("c3", "c4"), List.of("c5")),
                    shape(packed));
        }

        @Test
        void aStratumSmallerThanTheCapIsOneBatch() {
            assertEquals(List.of(List.of("c1", "c2")),
                    shape(PartitionBatchPacker.bySize(planner, 8).pack(stratum(2))));
        }

        @Test
        void aCapOfZeroStillMakesProgressRatherThanEmptyBatchesForever() {
            assertEquals(5, PartitionBatchPacker.bySize(planner, 0).pack(stratum(5)).size());
        }

        @Test
        void everyMemberLandsInExactlyOneBatch() {
            assertEquals(List.of("c1", "c2", "c3", "c4", "c5", "c6", "c7"),
                    flatten(PartitionBatchPacker.bySize(planner, 3).pack(stratum(7))));
        }
    }

    @Nested
    @DisplayName("By cost")
    class ByCost {

        @Test
        void theHeavyMembersAreSpreadAcrossBatchesRatherThanStackedInOne() {
            // c1 and c2 are each worth the whole rest of the stratum, and two of them together
            // blow the cost cap, so balancing has to put them in different batches.
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put("c1", 100L);
            weights.put("c2", 100L);
            List<List<PartitionMember>> packed = PartitionBatchPacker
                    .byCost(planner, costs(weights), 2, 120L).pack(stratum(4));

            assertEquals(2, packed.size());
            for (List<PartitionMember> batch : packed) {
                assertEquals(1, ids(batch).stream().filter(id -> id.equals("c1") || id.equals("c2"))
                                .count(),
                        "each batch should carry exactly one heavy member, got " + shape(packed));
            }
        }

        @Test
        void theStratumsConfidenceOrderSurvivesBalancing() {
            // The whole point: grouping is the planner's call, sequence is not. Balancing here
            // groups {c1,c3} and {c2,c4} — but it must not be the thing that decides c4 is read
            // before c1 just because c4 is long.
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put("c2", 50L);
            weights.put("c4", 50L);
            List<List<PartitionMember>> packed = PartitionBatchPacker
                    .byCost(planner, costs(weights), 2, 0L).pack(stratum(4));

            assertEquals("c1", flatten(packed).get(0),
                    "the most confident chunk is still read first");
            assertBatchesInEvidenceOrder(packed);
        }

        @Test
        void everyBatchIsInternallySortedHoweverTheWeightsFall() {
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put("c1", 3L);
            weights.put("c2", 90L);
            weights.put("c3", 12L);
            weights.put("c4", 40L);
            weights.put("c5", 7L);
            weights.put("c6", 65L);
            assertBatchesInEvidenceOrder(PartitionBatchPacker
                    .byCost(planner, costs(weights), 2, 100L).pack(stratum(6)));
        }

        @Test
        void withinABatchTheMembersStayInEvidenceOrderToo() {
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put("c1", 1L);
            weights.put("c4", 90L);
            List<List<PartitionMember>> packed = PartitionBatchPacker
                    .byCost(planner, costs(weights), 4, 0L).pack(stratum(4));

            assertEquals(1, packed.size());
            assertEquals(List.of("c1", "c2", "c3", "c4"), ids(packed.get(0)));
        }

        @Test
        void theItemCapStillBinds() {
            List<List<PartitionMember>> packed = PartitionBatchPacker
                    .byCost(planner, PartitionBatchPacker.UNIFORM_COST, 3, 0L).pack(stratum(7));
            for (List<PartitionMember> batch : packed) {
                assertTrue(batch.size() <= 3, "batch of " + batch.size() + " exceeds the cap");
            }
            assertEquals(7, flatten(packed).size());
        }

        @Test
        void aCostCapSplitsWhatWouldOtherwiseFitByCount() {
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put("c1", 60L);
            weights.put("c2", 60L);
            List<List<PartitionMember>> packed = PartitionBatchPacker
                    .byCost(planner, costs(weights), 8, 100L).pack(stratum(2));
            assertEquals(2, packed.size(), "two 60-cost members cannot share a 100-cost batch");
        }

        @Test
        void aMemberThePricerThrowsOnIsStillPacked() {
            Function<PartitionMember, Long> hostile = member -> {
                throw new IllegalStateException("no price for " + member.chunkId());
            };
            assertEquals(List.of("c1", "c2", "c3"),
                    flatten(PartitionBatchPacker.byCost(planner, hostile, 2, 0L).pack(stratum(3))));
        }

        @Test
        void aNullPricerFallsBackToTreatingEveryMemberAlike() {
            assertEquals(List.of("c1", "c2", "c3", "c4"),
                    flatten(PartitionBatchPacker.byCost(planner, null, 2, 0L).pack(stratum(4))));
        }
    }

    @Nested
    @DisplayName("When order is load-bearing")
    class OrderedStrata {

        @Test
        void aStratumCarryingOrderKeysIsNotRebalanced() {
            List<PartitionMember> trace = List.of(
                    ordered("c1", "t1"), ordered("c2", "t2"),
                    ordered("c3", "t3"), ordered("c4", "t4"));
            Map<String, Long> weights = new LinkedHashMap<>();
            weights.put("c1", 100L);
            weights.put("c4", 100L);

            List<List<PartitionMember>> packed = PartitionBatchPacker
                    .byCost(planner, costs(weights), 2, 0L).pack(trace);

            // Sequential, so the first and last steps never end up read back to back.
            assertEquals(List.of(List.of("c1", "c2"), List.of("c3", "c4")), shape(packed));
        }

        @Test
        void oneOrderedMemberIsEnoughToMakeTheWholeStratumSequential() {
            List<PartitionMember> mixed = new ArrayList<>(stratum(3));
            mixed.add(ordered("c4", "t4"));
            assertTrue(PartitionBatchPacker.preservesOrder(mixed));
        }

        @Test
        void aStratumWithNoOrderKeysIsFreeToBeBalanced() {
            assertFalse(PartitionBatchPacker.preservesOrder(stratum(3)));
        }

        @Test
        void aBlankOrderKeyIsNotAnOrder() {
            assertFalse(PartitionBatchPacker.preservesOrder(List.of(ordered("c1", "  "))));
        }
    }

    @Nested
    @DisplayName("Pricing chunks")
    class Pricing {

        @Test
        void aLongerChunkCostsMoreThanAShortOne() {
            Map<String, Document> chunks = new LinkedHashMap<>();
            chunks.put("c1", new Document("short"));
            chunks.put("c2", new Document("a considerably longer piece of text than the other"));
            Function<PartitionMember, Long> pricer =
                    PartitionBatchPacker.costOfChunks(planner, chunks);

            assertTrue(pricer.apply(member("c2", 0.9)) > pricer.apply(member("c1", 0.9)));
        }

        @Test
        void aChunkWithNoDocumentCostsTheSameAsAnyOther() {
            Map<String, Document> chunks = Map.of("c1", new Document("text"));
            assertEquals(1L, PartitionBatchPacker.costOfChunks(planner, chunks)
                    .apply(member("unknown", 0.9)));
        }

        @Test
        void withNoChunksAtAllEveryMemberWeighsTheSame() {
            assertSame(PartitionBatchPacker.UNIFORM_COST,
                    PartitionBatchPacker.costOfChunks(planner, Map.of()));
            assertSame(PartitionBatchPacker.UNIFORM_COST,
                    PartitionBatchPacker.costOfChunks(planner, null));
        }
    }
}
