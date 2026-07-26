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
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-channel discovery: several ways of finding evidence about one subject, run in evidence
 * order, none of them able to take the round down with it.
 */
class PartitionDiscoveryCoordinatorTest {

    private static final PartitionKey KEY =
            PartitionKey.forEntity("ent-acme", "discovery-v1", "snap-1");

    /** Provider backed by a function of (partition, round), so tests can react to state. */
    private record Fake(DiscoveryChannel channel,
                        BiFunction<EntityPartition, Integer, List<ChunkCandidate>> body,
                        List<DiscoveryChannel> callLog) implements DiscoveryChannelProvider {

        @Override
        public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
            callLog.add(channel);
            return body.apply(partition, round);
        }
    }

    private static Fake provider(DiscoveryChannel channel, List<DiscoveryChannel> callLog,
                                 ChunkCandidate... candidates) {
        List<ChunkCandidate> fixed = Arrays.asList(candidates);
        return new Fake(channel, (p, r) -> r == 1 ? fixed : List.of(), callLog);
    }

    private static Fake failing(DiscoveryChannel channel, List<DiscoveryChannel> callLog) {
        return new Fake(channel, (p, r) -> {
            throw new IllegalStateException("index unavailable");
        }, callLog);
    }

    private static ChunkCandidate chunk(String id, DiscoveryChannel channel, double confidence) {
        return ChunkCandidate.of(id, channel, confidence);
    }

    @Test
    void authoritativeChannelsRunBeforeWeakerOnes() {
        List<DiscoveryChannel> calls = new ArrayList<>();
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                provider(DiscoveryChannel.CONTRADICTION, calls),
                provider(DiscoveryChannel.SEMANTIC, calls),
                provider(DiscoveryChannel.DIRECT_IDENTIFIER, calls),
                provider(DiscoveryChannel.STRUCTURED_RELATIONSHIP, calls)),
                DiscoveryPolicy.defaults());

        assertEquals(List.of(DiscoveryChannel.DIRECT_IDENTIFIER,
                        DiscoveryChannel.STRUCTURED_RELATIONSHIP,
                        DiscoveryChannel.SEMANTIC,
                        DiscoveryChannel.CONTRADICTION),
                coordinator.activeChannels());

        coordinator.discover(EntityPartition.open(KEY), 1);
        assertEquals(coordinator.activeChannels(), calls);
    }

    @Test
    void channelsThePolicyDoesNotRunAreNeverCalled() {
        List<DiscoveryChannel> calls = new ArrayList<>();
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(provider(DiscoveryChannel.TOPIC, calls,
                                chunk("t1", DiscoveryChannel.TOPIC, 0.9)),
                        provider(DiscoveryChannel.SEMANTIC, calls,
                                chunk("s1", DiscoveryChannel.SEMANTIC, 0.9))),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(List.of(DiscoveryChannel.SEMANTIC), calls);
        assertEquals(1, outcome.added());
        assertTrue(outcome.partition().member("t1").isEmpty());
    }

    @Test
    void nullAndUnchannelledProvidersAreIgnoredRatherThanCrashingTheRound() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                Arrays.asList(null, DiscoveryChannelProvider.none(null)), null);
        assertTrue(coordinator.activeChannels().isEmpty());
        assertEquals(DiscoveryPolicy.defaults(), coordinator.policy());
        assertEquals(0, coordinator.discover(EntityPartition.open(KEY), 1).added());
    }

    @Test
    void aBrokenChannelNarrowsTheRoundInsteadOfDestroyingIt() {
        List<DiscoveryChannel> calls = new ArrayList<>();
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                failing(DiscoveryChannel.DIRECT_IDENTIFIER, calls),
                provider(DiscoveryChannel.SEMANTIC, calls,
                        chunk("s1", DiscoveryChannel.SEMANTIC, 0.9))),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(1, outcome.added(), "the working channel's evidence survives");
        assertTrue(outcome.hasFailures());
        assertTrue(outcome.failures().get(DiscoveryChannel.DIRECT_IDENTIFIER)
                .contains("index unavailable"));
        assertTrue(outcome.describe().contains("channelFailures"), outcome.describe());
    }

    @Test
    void lowConfidenceProposalsAreRejectedAndCounted() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(provider(DiscoveryChannel.SEMANTIC, new ArrayList<>(),
                        chunk("keep", DiscoveryChannel.SEMANTIC, 0.9),
                        chunk("maybe", DiscoveryChannel.SEMANTIC, 0.3),
                        chunk("junk", DiscoveryChannel.SEMANTIC, 0.05))),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(1, outcome.excluded());
        assertEquals(3, outcome.totalProposals());
        EntityPartition partition = outcome.partition();
        assertEquals(MembershipState.DISCOVERED, partition.member("keep").orElseThrow().state());
        assertEquals(MembershipState.DEFERRED, partition.member("maybe").orElseThrow().state());
        assertEquals(MembershipState.EXCLUDED, partition.member("junk").orElseThrow().state());
        assertEquals(List.of("keep"),
                partition.schedulable().stream().map(PartitionMember::chunkId).toList());
    }

    @Test
    void aChunkTheRunMayNotReadIsRecordedAsAGapNotDropped() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(provider(DiscoveryChannel.SEMANTIC, new ArrayList<>(),
                        chunk("open", DiscoveryChannel.SEMANTIC, 0.9),
                        chunk("sealed", DiscoveryChannel.SEMANTIC, 0.9)
                                .withAccessScope(AccessScope.of("hr")))),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(1, outcome.inaccessible());
        assertEquals(MembershipState.INACCESSIBLE,
                outcome.partition().member("sealed").orElseThrow().state());
        assertEquals(List.of("sealed"), outcome.partition().manifest(true).inaccessible(),
                "the gap is named in the manifest rather than silently missing from it");
        assertTrue(outcome.describe().contains("inaccessible=1"), outcome.describe());
    }

    @Test
    void aReaderHoldingTheRightDomainSeesTheRestrictedChunk() {
        DiscoveryPolicy policy = DiscoveryPolicy.defaults()
                .withReaderScope("hr-reader", AccessScope.of("hr"));
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(provider(DiscoveryChannel.SEMANTIC, new ArrayList<>(),
                        chunk("sealed", DiscoveryChannel.SEMANTIC, 0.9)
                                .withAccessScope(AccessScope.of("hr")))),
                policy);

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(0, outcome.inaccessible());
        assertEquals(MembershipState.DISCOVERED,
                outcome.partition().member("sealed").orElseThrow().state());
    }

    @Test
    void aChannelReturningMoreThanItsCapIsTruncatedNotTrusted() {
        List<ChunkCandidate> flood = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            flood.add(chunk("c" + i, DiscoveryChannel.SEMANTIC, 0.9));
        }
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(new Fake(DiscoveryChannel.SEMANTIC, (p, r) -> flood, new ArrayList<>())),
                DiscoveryPolicy.defaults().withCandidateCap("capped", 3));

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(3, outcome.added());
        assertEquals(3, outcome.totalProposals());
    }

    @Test
    void hittingTheMemberCapPostponesRatherThanDiscards() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(provider(DiscoveryChannel.SEMANTIC, new ArrayList<>(),
                        chunk("c1", DiscoveryChannel.SEMANTIC, 0.9),
                        chunk("c2", DiscoveryChannel.SEMANTIC, 0.9),
                        chunk("c3", DiscoveryChannel.SEMANTIC, 0.9))),
                DiscoveryPolicy.defaults().withBudget("tiny", 3, 2));

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(1, outcome.deferredOverCap());
        assertEquals(3, outcome.partition().size(), "the overflow is remembered, not forgotten");
        assertEquals(MembershipState.DEFERRED,
                outcome.partition().member("c3").orElseThrow().state());
        assertTrue(outcome.partition().manifest(true).deferred().contains("c3"));
        assertTrue(outcome.describe().contains("overCap=1"), outcome.describe());
    }

    @Test
    void twoChannelsFindingTheSameChunkStrengthenItRatherThanDoubleCountingIt() {
        List<DiscoveryChannel> calls = new ArrayList<>();
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                provider(DiscoveryChannel.SEMANTIC, calls,
                        ChunkCandidate.of("shared", DiscoveryChannel.SEMANTIC, 0.6, "similar")),
                provider(DiscoveryChannel.DIRECT_IDENTIFIER, calls,
                        ChunkCandidate.of("shared", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95,
                                "alias hit"))),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);

        assertEquals(1, outcome.added());
        assertEquals(1, outcome.upgraded());
        assertEquals(2, outcome.totalProposals());
        PartitionMember member = outcome.partition().member("shared").orElseThrow();
        assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, member.channel(),
                "the identifier channel ran first and the semantic hit did not overwrite it");
        assertTrue(member.reason().contains("alias hit") && member.reason().contains("similar"));
    }

    @Test
    void aRoundThatProposesNothingNewReportsAnExhaustedFrontier() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(provider(DiscoveryChannel.SEMANTIC, new ArrayList<>(),
                        chunk("c1", DiscoveryChannel.SEMANTIC, 0.9))),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome first =
                coordinator.discover(EntityPartition.open(KEY), 1);
        assertFalse(first.frontierExhausted());

        PartitionDiscoveryCoordinator.Outcome second =
                coordinator.discover(first.partition(), 2);
        assertTrue(second.frontierExhausted());
        assertEquals(0, second.added());
        assertEquals(1, second.partition().size());
    }

    @Test
    void reProposingAnIdenticalCandidateIsNotAnUpgrade() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(new Fake(DiscoveryChannel.SEMANTIC,
                        (p, r) -> List.of(ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.9,
                                "same reason")),
                        new ArrayList<>())),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome first =
                coordinator.discover(EntityPartition.open(KEY), 1);
        PartitionDiscoveryCoordinator.Outcome second =
                coordinator.discover(first.partition(), 2);

        assertEquals(0, second.upgraded());
        assertTrue(second.frontierExhausted(),
                "a channel repeating itself must not keep the loop alive forever");
    }

    @Test
    void laterRoundsSeeWhatEarlierRoundsCommitted() {
        // The expansion case: a channel only proposes a neighbour once the seed is processed.
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(new Fake(DiscoveryChannel.STRUCTURED_RELATIONSHIP, (partition, round) ->
                        partition.inState(MembershipState.PROCESSED).isEmpty()
                                ? List.of()
                                : List.of(chunk("neighbour", DiscoveryChannel.STRUCTURED_RELATIONSHIP,
                                0.9)),
                        new ArrayList<>())),
                DiscoveryPolicy.defaults());

        EntityPartition seeded = EntityPartition.open(KEY)
                .admit(chunk("seed", DiscoveryChannel.SEED, 1.0), MembershipState.DISCOVERED, 1);
        assertTrue(coordinator.discover(seeded, 1).frontierExhausted());

        EntityPartition processed = seeded.withState("seed", MembershipState.PROCESSED, null);
        PartitionDiscoveryCoordinator.Outcome outcome = coordinator.discover(processed, 2);
        assertEquals(1, outcome.added());
        assertEquals(2, outcome.partition().member("neighbour").orElseThrow().discoveredRound());
    }

    @Test
    void theRoundNumberIsRecordedOnThePartition() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(DiscoveryChannelProvider.none(DiscoveryChannel.SEMANTIC)),
                DiscoveryPolicy.defaults());
        assertEquals(7, coordinator.discover(EntityPartition.open(KEY), 7).partition().round());
    }

    @Test
    void discoveringIntoNothingIsProgrammerErrorNotASilentNoOp() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(), DiscoveryPolicy.defaults());
        assertThrows(IllegalArgumentException.class, () -> coordinator.discover(null, 1));
    }

    @Test
    void nullCandidatesInsideAChannelsResultAreSkipped() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(new Fake(DiscoveryChannel.SEMANTIC,
                        (p, r) -> Arrays.asList(chunk("c1", DiscoveryChannel.SEMANTIC, 0.9), null),
                        new ArrayList<>())),
                DiscoveryPolicy.defaults());

        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);
        assertEquals(1, outcome.added());
        assertEquals(1, outcome.totalProposals());
    }

    @Test
    void aChannelReturningNullIsTreatedAsHavingFoundNothing() {
        PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(
                List.of(new Fake(DiscoveryChannel.SEMANTIC, (p, r) -> null, new ArrayList<>())),
                DiscoveryPolicy.defaults());
        PartitionDiscoveryCoordinator.Outcome outcome =
                coordinator.discover(EntityPartition.open(KEY), 1);
        assertEquals(0, outcome.added());
        assertFalse(outcome.hasFailures());
    }
}
