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
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The driver: discover, plan, process, commit, repeat — and stop with an honest account of what
 * it did and did not read.
 */
class PartitionLifecycleTest {

    private static final PartitionKey KEY =
            PartitionKey.forEntity("ent-acme", "discovery-v1", "snap-1");

    private record Fake(DiscoveryChannel channel,
                        BiFunction<EntityPartition, Integer, List<ChunkCandidate>> body)
            implements DiscoveryChannelProvider {

        @Override
        public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
            return body.apply(partition, round);
        }
    }

    private static ChunkCandidate chunk(String id, double confidence) {
        return ChunkCandidate.of(id, DiscoveryChannel.SEMANTIC, confidence);
    }

    private static PartitionDiscoveryCoordinator seeded(String... ids) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        for (String id : ids) {
            candidates.add(chunk(id, 0.9));
        }
        return new PartitionDiscoveryCoordinator(
                List.of(DiscoveryChannelProvider.seeded(DiscoveryChannel.SEED, candidates)),
                DiscoveryPolicy.defaults()
                        .withChannels("seed-only", DiscoveryChannel.SEED, DiscoveryChannel.SEMANTIC,
                                DiscoveryChannel.STRUCTURED_RELATIONSHIP));
    }

    @Test
    void aRunThatReadsEverythingClosesThePartition() {
        PartitionStore store = PartitionStore.inMemory();
        PartitionLifecycle lifecycle = new PartitionLifecycle(store, seeded("c1", "c2", "c3"), 16);
        List<String> seen = new ArrayList<>();

        PartitionLifecycle.Result result = lifecycle.run(KEY, (member, partition) -> {
            seen.add(member.chunkId());
            return PartitionLifecycle.ProcessOutcome.processed();
        });

        assertEquals(List.of("c1", "c2", "c3"), seen);
        assertEquals(3, result.processed());
        assertEquals(0, result.failed());
        assertEquals(2, result.rounds(), "one round to find, one to confirm nothing else exists");
        assertTrue(result.frontierExhausted());
        assertTrue(result.isProvisionallyComplete());
        assertEquals(PartitionPhase.CLOSED, result.partition().phase());
        assertEquals(1.0, result.manifest().coverage(), 1e-9);
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void theFinalPartitionIsWhatIsLeftInTheStore() {
        PartitionStore store = PartitionStore.inMemory();
        PartitionLifecycle lifecycle = new PartitionLifecycle(store, seeded("c1"), 16);
        PartitionLifecycle.Result result =
                lifecycle.run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.processed());

        EntityPartition persisted = store.load(KEY.id()).orElseThrow();
        assertEquals(PartitionPhase.CLOSED, persisted.phase());
        assertEquals(result.partition(), persisted);
    }

    @Test
    void everyChunkIsCommittedBeforeTheNextOneIsRead() {
        PartitionStore store = PartitionStore.inMemory();
        PartitionLifecycle lifecycle = new PartitionLifecycle(store, seeded("c1", "c2", "c3"), 16);
        List<Integer> processedSoFar = new ArrayList<>();

        lifecycle.run(KEY, (member, partition) -> {
            processedSoFar.add(partition.inState(MembershipState.PROCESSED).size());
            return PartitionLifecycle.ProcessOutcome.processed();
        });

        assertEquals(List.of(0, 1, 2), processedSoFar,
                "each chunk sees the results of the ones before it");
    }

    @Test
    void aBatchIsMarkedInFlightBeforeAnyOfItRuns() {
        // If the process dies mid-batch the store must say "scheduled", not "never seen" —
        // otherwise a resumed run cannot tell abandoned work from undiscovered work.
        PartitionStore store = PartitionStore.inMemory();
        PartitionLifecycle lifecycle = new PartitionLifecycle(store, seeded("c1", "c2", "c3"), 16);
        List<Integer> scheduledWhenFirstRan = new ArrayList<>();

        lifecycle.run(KEY, (member, partition) -> {
            if (scheduledWhenFirstRan.isEmpty()) {
                EntityPartition persisted = store.load(KEY.id()).orElseThrow();
                scheduledWhenFirstRan.add(persisted.inState(MembershipState.SCHEDULED).size());
            }
            return PartitionLifecycle.ProcessOutcome.processed();
        });

        assertEquals(List.of(3), scheduledWhenFirstRan);
    }

    @Test
    void aLaterRoundSeesWhatAnEarlierRoundCommitted() {
        // Expansion: the structural channel only proposes a neighbour once the seed is committed.
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                DiscoveryChannelProvider.seeded(DiscoveryChannel.SEED, List.of(chunk("seed", 0.9))),
                new Fake(DiscoveryChannel.STRUCTURED_RELATIONSHIP, (partition, round) ->
                        partition.member("seed")
                                .filter(m -> m.state() == MembershipState.PROCESSED)
                                .map(m -> List.of(ChunkCandidate.of("neighbour",
                                        DiscoveryChannel.STRUCTURED_RELATIONSHIP, 0.9)))
                                .orElse(List.of()))),
                DiscoveryPolicy.defaults().withChannels("expanding", DiscoveryChannel.SEED,
                        DiscoveryChannel.STRUCTURED_RELATIONSHIP));

        PartitionLifecycle.Result result =
                new PartitionLifecycle(PartitionStore.inMemory(), coordinator, 16)
                        .run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.processed());

        assertEquals(2, result.processed());
        assertEquals(3, result.rounds());
        assertTrue(result.isProvisionallyComplete());
        assertEquals(2, result.partition().member("neighbour").orElseThrow().discoveredRound());
    }

    @Test
    void aChunkThatBlowsUpIsDeferredNotLostAndTheRunSaysSo() {
        PartitionLifecycle lifecycle =
                new PartitionLifecycle(PartitionStore.inMemory(), seeded("good", "bad"), 16);

        PartitionLifecycle.Result result = lifecycle.run(KEY, (member, partition) -> {
            if ("bad".equals(member.chunkId())) {
                throw new IllegalStateException("extraction exploded");
            }
            return PartitionLifecycle.ProcessOutcome.processed();
        });

        assertEquals(1, result.processed());
        assertEquals(1, result.failed());
        assertEquals(MembershipState.DEFERRED,
                result.partition().member("bad").orElseThrow().state());
        assertTrue(result.partition().member("bad").orElseThrow().note()
                .contains("extraction exploded"));
        assertFalse(result.isProvisionallyComplete(),
                "a run that dropped a chunk has not covered its subject");
        assertEquals(PartitionPhase.RECONCILING, result.partition().phase());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("failed processing")));
    }

    @Test
    void aProcessorReturningNothingIsADeferralNotASuccess() {
        PartitionLifecycle.Result result =
                new PartitionLifecycle(PartitionStore.inMemory(), seeded("c1"), 16)
                        .run(KEY, (m, p) -> null);

        assertEquals(0, result.processed());
        assertEquals(0, result.failed(), "returning nothing is not the same as throwing");
        assertEquals(MembershipState.DEFERRED, result.partition().member("c1").orElseThrow().state());
        assertTrue(result.manifest().deferred().contains("c1"));
    }

    @Test
    void unreadableChunksAreReportedAsGapsRatherThanFailures() {
        PartitionLifecycle.Result result =
                new PartitionLifecycle(PartitionStore.inMemory(), seeded("open", "sealed"), 16)
                        .run(KEY, (member, partition) -> "sealed".equals(member.chunkId())
                                ? PartitionLifecycle.ProcessOutcome.inaccessible("acl denied")
                                : PartitionLifecycle.ProcessOutcome.processed());

        assertEquals(1, result.processed());
        assertEquals(0, result.failed());
        assertTrue(result.isProvisionallyComplete());
        assertTrue(result.manifest().isCompleteWithGaps(),
                "complete only in the sense that it gave up on one chunk");
        assertEquals(List.of("sealed"), result.manifest().inaccessible());
        assertEquals(PartitionPhase.CLOSED, result.partition().phase());
    }

    @Test
    void aRunThatDefersEverythingReconcilesInsteadOfClosing() {
        PartitionLifecycle.Result result =
                new PartitionLifecycle(PartitionStore.inMemory(), seeded("c1", "c2"), 16)
                        .run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.deferred("no budget"));

        assertEquals(0, result.processed());
        assertTrue(result.frontierExhausted());
        assertFalse(result.isProvisionallyComplete());
        assertEquals(PartitionPhase.RECONCILING, result.partition().phase());
        assertEquals(2, result.manifest().deferred().size());
        assertEquals(0.0, result.manifest().coverage(), 1e-9);
    }

    @Test
    void spendingTheRoundBudgetIsSaidOutLoud() {
        // A channel that always proposes something new: the frontier never closes, so the run
        // must stop on budget and admit that is why.
        PartitionDiscoveryCoordinator endless = new PartitionDiscoveryCoordinator(
                List.of(new Fake(DiscoveryChannel.SEMANTIC,
                        (partition, round) -> List.of(chunk("round" + round, 0.9)))),
                DiscoveryPolicy.defaults().withBudget("two-rounds", 2, 100));

        PartitionLifecycle.Result result =
                new PartitionLifecycle(PartitionStore.inMemory(), endless, 16)
                        .run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.processed());

        assertEquals(2, result.rounds());
        assertEquals(2, result.processed());
        assertFalse(result.frontierExhausted());
        assertFalse(result.isProvisionallyComplete(),
                "there was more evidence and the run knows it");
        assertEquals(PartitionPhase.PROCESSING, result.partition().phase());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("round budget of 2")));
        assertTrue(result.describe().contains("rounds=2"), result.describe());
    }

    @Test
    void aBrokenChannelIsCarriedIntoTheRunsCaveats() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                DiscoveryChannelProvider.seeded(DiscoveryChannel.SEED, List.of(chunk("c1", 0.9))),
                new Fake(DiscoveryChannel.SEMANTIC, (partition, round) -> {
                    throw new IllegalStateException("vector index down");
                })),
                DiscoveryPolicy.defaults().withChannels("seed-and-semantic",
                        DiscoveryChannel.SEED, DiscoveryChannel.SEMANTIC));

        PartitionLifecycle.Result result =
                new PartitionLifecycle(PartitionStore.inMemory(), coordinator, 16)
                        .run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.processed());

        assertEquals(1, result.processed());
        assertTrue(result.channelFailures().containsKey(DiscoveryChannel.SEMANTIC));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("discovery channels failed")));
        assertTrue(result.describe().contains("discovery channels failed"), result.describe());

        // Every chunk the run knew about was read, so the manifest alone says "complete" — but a
        // whole way of finding evidence never ran, and completeness is relative to a policy that
        // names that channel. The run must not round that up.
        assertTrue(result.manifest().isProvisionallyComplete());
        assertFalse(result.isProvisionallyComplete());
        assertEquals(PartitionPhase.RECONCILING, result.partition().phase());
    }

    @Test
    void resumingAPartitionOnlyProcessesWhatIsStillOwed() {
        PartitionStore store = PartitionStore.inMemory();
        store.save(EntityPartition.open(KEY)
                .admit(chunk("already", 0.9), MembershipState.DISCOVERED, 1)
                .withState("already", MembershipState.PROCESSED, "done in an earlier run")
                .admit(chunk("owed", 0.9), MembershipState.DISCOVERED, 1));

        List<String> seen = new ArrayList<>();
        PartitionLifecycle.Result result = new PartitionLifecycle(store,
                new PartitionDiscoveryCoordinator(List.of(), DiscoveryPolicy.defaults()), 16)
                .run(KEY, (member, partition) -> {
                    seen.add(member.chunkId());
                    return PartitionLifecycle.ProcessOutcome.processed();
                });

        assertEquals(List.of("owed"), seen, "committed work is not redone");
        assertEquals(1, result.processed());
        assertTrue(result.isProvisionallyComplete());
    }

    @Test
    void invalidatedWorkIsPickedUpAgainOnResume() {
        PartitionStore store = PartitionStore.inMemory();
        store.save(EntityPartition.open(KEY)
                .admit(chunk("c1", 0.9).inDocument("docA"), MembershipState.DISCOVERED, 1)
                .withState("c1", MembershipState.PROCESSED, null)
                .invalidateDocument("docA", "v2", "source changed"));

        List<String> seen = new ArrayList<>();
        new PartitionLifecycle(store,
                new PartitionDiscoveryCoordinator(List.of(), DiscoveryPolicy.defaults()), 16)
                .run(KEY, (member, partition) -> {
                    seen.add(member.chunkId());
                    return PartitionLifecycle.ProcessOutcome.processed();
                });

        assertEquals(List.of("c1"), seen, "a changed source is re-read, not assumed still current");
    }

    @Test
    void authoritativeEvidenceIsReadBeforeContradictions() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                DiscoveryChannelProvider.seeded(DiscoveryChannel.CONTRADICTION,
                        List.of(ChunkCandidate.of("dispute", DiscoveryChannel.CONTRADICTION, 0.9))),
                DiscoveryChannelProvider.seeded(DiscoveryChannel.DIRECT_IDENTIFIER,
                        List.of(ChunkCandidate.of("filing", DiscoveryChannel.DIRECT_IDENTIFIER,
                                0.95)))),
                DiscoveryPolicy.defaults());

        List<String> seen = new ArrayList<>();
        new PartitionLifecycle(PartitionStore.inMemory(), coordinator, 16)
                .run(KEY, (member, partition) -> {
                    seen.add(member.chunkId());
                    return PartitionLifecycle.ProcessOutcome.processed();
                });

        assertEquals(List.of("filing", "dispute"), seen);
    }

    @Test
    void aCallerSuppliedPackerDecidesBatchBoundaries() {
        List<Integer> batchSizes = new ArrayList<>();
        PartitionLifecycle lifecycle = new PartitionLifecycle(PartitionStore.inMemory(),
                seeded("c1", "c2", "c3", "c4"),
                stratum -> {
                    List<List<PartitionMember>> batches = MiniBatchPlanner.sequential(stratum, 2);
                    batches.forEach(b -> batchSizes.add(b.size()));
                    return batches;
                });

        PartitionLifecycle.Result result =
                lifecycle.run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.processed());

        assertEquals(List.of(2, 2), batchSizes);
        assertEquals(4, result.processed());
    }

    @Test
    void openingAPartitionPersistsItWithoutRunningAnything() {
        PartitionStore store = PartitionStore.inMemory();
        PartitionLifecycle lifecycle = new PartitionLifecycle(store, seeded("c1"), 16);

        EntityPartition opened = lifecycle.open(KEY);
        assertEquals(0, opened.size());
        assertEquals(PartitionPhase.NEW, opened.phase());
        assertTrue(store.load(KEY.id()).isPresent());
    }

    @Test
    void aLifecycleNeedsACoordinatorAKeyAndAProcessor() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionLifecycle(null, null, 16));

        PartitionLifecycle lifecycle = new PartitionLifecycle(null, seeded("c1"), 16);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.run(null,
                (m, p) -> PartitionLifecycle.ProcessOutcome.processed()));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.run(KEY, null));
    }

    @Test
    void aRunWithNothingToDoIsHonestAboutHavingCoveredNothing() {
        PartitionLifecycle.Result result = new PartitionLifecycle(PartitionStore.inMemory(),
                new PartitionDiscoveryCoordinator(List.of(), DiscoveryPolicy.defaults()), 16)
                .run(KEY, (m, p) -> PartitionLifecycle.ProcessOutcome.processed());

        assertEquals(0, result.processed());
        assertEquals(1, result.rounds());
        assertTrue(result.frontierExhausted());
        assertFalse(result.isProvisionallyComplete(),
                "finding no evidence is not the same as having covered the subject");
        assertEquals(PartitionPhase.RECONCILING, result.partition().phase());
    }
}
