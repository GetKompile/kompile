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

package ai.kompile.core.graphrag.partition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering of work within a partition: authoritative evidence first, contradictions last, and
 * in-stratum packing left to whoever knows the cost model.
 */
class MiniBatchPlannerTest {

    private static final PartitionKey KEY =
            PartitionKey.forEntity("ent-acme", "discovery-v1", "snap-1");

    private static EntityPartition partitionOf(ChunkCandidate... candidates) {
        EntityPartition partition = EntityPartition.open(KEY);
        for (ChunkCandidate candidate : candidates) {
            partition = partition.admit(candidate, MembershipState.DISCOVERED, 1);
        }
        return partition;
    }

    private static ChunkCandidate chunk(String id, DiscoveryChannel channel, double confidence) {
        return ChunkCandidate.of(id, channel, confidence);
    }

    private static List<String> idsOf(List<MiniBatchPlanner.MiniBatch> plan) {
        List<String> ids = new ArrayList<>();
        for (MiniBatchPlanner.MiniBatch batch : plan) {
            ids.addAll(batch.chunkIds());
        }
        return ids;
    }

    @Test
    void strataRunInEvidenceOrderWithContradictionsLast() {
        EntityPartition partition = partitionOf(
                chunk("contra", DiscoveryChannel.CONTRADICTION, 0.9),
                chunk("semantic", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("identifier", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9),
                chunk("structural", DiscoveryChannel.STRUCTURED_RELATIONSHIP, 0.9));

        assertEquals(List.of(DiscoveryChannel.DIRECT_IDENTIFIER,
                        DiscoveryChannel.STRUCTURED_RELATIONSHIP,
                        DiscoveryChannel.SEMANTIC,
                        DiscoveryChannel.CONTRADICTION),
                List.copyOf(MiniBatchPlanner.stratify(partition).keySet()));
        assertEquals(List.of("identifier", "structural", "semantic", "contra"),
                idsOf(MiniBatchPlanner.plan(partition, 10)));
    }

    @Test
    void withinAStratumTheStrongestEvidenceIsReadFirst() {
        EntityPartition partition = partitionOf(
                chunk("weak", DiscoveryChannel.SEMANTIC, 0.55),
                chunk("strong", DiscoveryChannel.SEMANTIC, 0.95),
                chunk("middling", DiscoveryChannel.SEMANTIC, 0.75));

        assertEquals(List.of("strong", "middling", "weak"),
                idsOf(MiniBatchPlanner.plan(partition, 10)));
    }

    @Test
    void anOrderKeyReadsAProcessInTheOrderItHappened() {
        EntityPartition partition = partitionOf(
                chunk("march", DiscoveryChannel.PROCESS, 0.8).withOrderKey("2024-03"),
                chunk("january", DiscoveryChannel.PROCESS, 0.8).withOrderKey("2024-01"),
                chunk("february", DiscoveryChannel.PROCESS, 0.8).withOrderKey("2024-02"));

        assertEquals(List.of("january", "february", "march"),
                idsOf(MiniBatchPlanner.plan(partition, 10)));
    }

    @Test
    void confidenceOutranksChronologyWithinAStratum() {
        EntityPartition partition = partitionOf(
                chunk("late-but-certain", DiscoveryChannel.PROCESS, 0.95).withOrderKey("2024-12"),
                chunk("early-but-vague", DiscoveryChannel.PROCESS, 0.6).withOrderKey("2024-01"));

        assertEquals(List.of("late-but-certain", "early-but-vague"),
                idsOf(MiniBatchPlanner.plan(partition, 10)));
    }

    @Test
    void planIsReproducibleWhenEverythingElseTies() {
        EntityPartition partition = partitionOf(
                chunk("zebra", DiscoveryChannel.SEMANTIC, 0.8),
                chunk("alpha", DiscoveryChannel.SEMANTIC, 0.8),
                chunk("mango", DiscoveryChannel.SEMANTIC, 0.8));

        assertEquals(List.of("alpha", "mango", "zebra"),
                idsOf(MiniBatchPlanner.plan(partition, 10)));
        assertEquals(idsOf(MiniBatchPlanner.plan(partition, 10)),
                idsOf(MiniBatchPlanner.plan(partition, 10)));
    }

    @Test
    void onlySchedulableMembersAreEverPlanned() {
        EntityPartition partition = partitionOf(
                chunk("todo", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("done", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("later", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("locked", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("stale", DiscoveryChannel.SEMANTIC, 0.9))
                .withState("done", MembershipState.PROCESSED, null)
                .withState("later", MembershipState.DEFERRED, null)
                .withState("locked", MembershipState.INACCESSIBLE, null)
                .withState("stale", MembershipState.INVALIDATED, null);

        assertEquals(List.of("stale", "todo"), idsOf(MiniBatchPlanner.plan(partition, 10)));
    }

    @Test
    void batchesNeverStraddleTwoStrata() {
        EntityPartition partition = partitionOf(
                chunk("i1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9),
                chunk("s1", DiscoveryChannel.SEMANTIC, 0.9));

        List<MiniBatchPlanner.MiniBatch> plan = MiniBatchPlanner.plan(partition, 50);
        assertEquals(2, plan.size(), "one stratum per batch even when both would fit in one");
        assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, plan.get(0).stratum());
        assertEquals(DiscoveryChannel.SEMANTIC, plan.get(1).stratum());
        assertEquals(List.of(1, 2), plan.stream().map(MiniBatchPlanner.MiniBatch::index).toList());
        assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER.priority(), plan.get(0).priority());
    }

    @Test
    void aStratumLargerThanTheBatchSizeIsSplitInOrder() {
        EntityPartition partition = partitionOf(
                chunk("a", DiscoveryChannel.SEMANTIC, 0.95),
                chunk("b", DiscoveryChannel.SEMANTIC, 0.85),
                chunk("c", DiscoveryChannel.SEMANTIC, 0.75),
                chunk("d", DiscoveryChannel.SEMANTIC, 0.65),
                chunk("e", DiscoveryChannel.SEMANTIC, 0.55));

        List<MiniBatchPlanner.MiniBatch> plan = MiniBatchPlanner.plan(partition, 2);
        assertEquals(3, plan.size());
        assertEquals(List.of("a", "b"), plan.get(0).chunkIds());
        assertEquals(List.of("c", "d"), plan.get(1).chunkIds());
        assertEquals(List.of("e"), plan.get(2).chunkIds());
        assertEquals(2, plan.get(0).size());
    }

    @Test
    void aNonsenseBatchSizeStillProducesUsableBatches() {
        EntityPartition partition = partitionOf(
                chunk("a", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("b", DiscoveryChannel.SEMANTIC, 0.8));
        List<MiniBatchPlanner.MiniBatch> plan = MiniBatchPlanner.plan(partition, 0);
        assertEquals(2, plan.size());
        assertEquals(List.of("a"), plan.get(0).chunkIds());
    }

    @Test
    void inStratumPackingIsDelegatedSoACostModelCanOwnIt() {
        // The composition point with the crawl's cost-balanced planner: strata stay in evidence
        // order, but how a stratum is sliced is the caller's business.
        EntityPartition partition = partitionOf(
                chunk("i1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9),
                chunk("s1", DiscoveryChannel.SEMANTIC, 0.9),
                chunk("s2", DiscoveryChannel.SEMANTIC, 0.8),
                chunk("s3", DiscoveryChannel.SEMANTIC, 0.7));

        List<List<PartitionMember>> seen = new ArrayList<>();
        List<MiniBatchPlanner.MiniBatch> plan = MiniBatchPlanner.plan(partition, stratum -> {
            seen.add(stratum);
            // A "cost model" that puts each member in its own batch.
            return stratum.stream().map(List::of).toList();
        });

        assertEquals(2, seen.size(), "the packer is invoked once per stratum, not once overall");
        assertEquals(4, plan.size());
        assertEquals(List.of("i1", "s1", "s2", "s3"), idsOf(plan));
        assertEquals(List.of(1, 2, 3, 4),
                plan.stream().map(MiniBatchPlanner.MiniBatch::index).toList());
    }

    @Test
    void aPackerThatDropsOrEmptiesAStratumDoesNotCorruptTheIndexing() {
        EntityPartition partition = partitionOf(
                chunk("i1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9),
                chunk("s1", DiscoveryChannel.SEMANTIC, 0.9));

        List<MiniBatchPlanner.MiniBatch> dropped = MiniBatchPlanner.plan(partition, stratum -> {
            if (stratum.get(0).channel() == DiscoveryChannel.SEMANTIC) {
                return null;
            }
            return List.of(stratum);
        });
        assertEquals(List.of("i1"), idsOf(dropped));

        List<MiniBatchPlanner.MiniBatch> emptied = MiniBatchPlanner.plan(partition,
                stratum -> List.of(List.of(), stratum));
        assertEquals(List.of(1, 2), emptied.stream()
                .map(MiniBatchPlanner.MiniBatch::index).toList());
    }

    @Test
    void planningNothingIsEmptyRatherThanAnError() {
        Function<List<PartitionMember>, List<List<PartitionMember>>> noPacker = null;
        assertTrue(MiniBatchPlanner.plan(EntityPartition.open(KEY), 10).isEmpty());
        assertTrue(MiniBatchPlanner.plan(null, 10).isEmpty());
        assertTrue(MiniBatchPlanner.plan(null, stratum -> List.of(stratum)).isEmpty());
        assertTrue(MiniBatchPlanner.plan(EntityPartition.open(KEY), noPacker).isEmpty());
        assertEquals(Map.of(), MiniBatchPlanner.stratify(null));
    }

    @Test
    void sequentialPackingPreservesOrderAndToleratesNothing() {
        List<PartitionMember> members = new ArrayList<>(
                partitionOf(chunk("a", DiscoveryChannel.SEMANTIC, 0.9),
                        chunk("b", DiscoveryChannel.SEMANTIC, 0.8),
                        chunk("c", DiscoveryChannel.SEMANTIC, 0.7)).schedulable());

        assertEquals(2, MiniBatchPlanner.sequential(members, 2).size());
        assertEquals(1, MiniBatchPlanner.sequential(members, 99).size());
        assertEquals(3, MiniBatchPlanner.sequential(members, -5).size());
        assertTrue(MiniBatchPlanner.sequential(null, 2).isEmpty());
        assertTrue(MiniBatchPlanner.sequential(List.of(), 2).isEmpty());
    }
}
