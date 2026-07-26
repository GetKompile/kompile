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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The value types the partition control plane is built from. */
class PartitionModelTest {

    @Nested
    @DisplayName("MembershipState")
    class MembershipStates {

        @Test
        void onlyProcessedCountsAsCoverage() {
            for (MembershipState state : MembershipState.values()) {
                assertEquals(state == MembershipState.PROCESSED, state.isCovered(), state.name());
            }
        }

        @Test
        void aChunkNobodyReadIsOutstandingWhateverTheReason() {
            assertTrue(MembershipState.DISCOVERED.isOutstanding());
            assertTrue(MembershipState.SCHEDULED.isOutstanding());
            assertTrue(MembershipState.DEFERRED.isOutstanding());
            assertTrue(MembershipState.INVALIDATED.isOutstanding());
            assertFalse(MembershipState.PROCESSED.isOutstanding());
            assertFalse(MembershipState.INACCESSIBLE.isOutstanding());
            assertFalse(MembershipState.EXCLUDED.isOutstanding());
        }

        @Test
        void unreadableAndRejectedAreAccountedForWithoutBeingCoverage() {
            assertTrue(MembershipState.INACCESSIBLE.isAccountedFor());
            assertTrue(MembershipState.EXCLUDED.isAccountedFor());
            assertFalse(MembershipState.INACCESSIBLE.isCovered());
            assertFalse(MembershipState.EXCLUDED.isCovered());
        }

        @Test
        void onlyDiscoveredAndInvalidatedMayBeScheduled() {
            assertTrue(MembershipState.DISCOVERED.isSchedulable());
            assertTrue(MembershipState.INVALIDATED.isSchedulable());
            assertFalse(MembershipState.SCHEDULED.isSchedulable());
            assertFalse(MembershipState.PROCESSED.isSchedulable());
            assertFalse(MembershipState.DEFERRED.isSchedulable());
        }

        @Test
        void reDiscoveringAProcessedChunkDoesNotScheduleItAgain() {
            assertEquals(MembershipState.PROCESSED,
                    MembershipState.merge(MembershipState.PROCESSED, MembershipState.DISCOVERED));
        }

        @Test
        void invalidationBeatsEverythingBecauseTheSourceMoved() {
            for (MembershipState other : MembershipState.values()) {
                assertEquals(MembershipState.INVALIDATED,
                        MembershipState.merge(other, MembershipState.INVALIDATED), other.name());
                assertEquals(MembershipState.INVALIDATED,
                        MembershipState.merge(MembershipState.INVALIDATED, other), other.name());
            }
        }

        @Test
        void losingAccessLaterDoesNotUndoAnExtractionThatAlreadyRan() {
            assertEquals(MembershipState.PROCESSED,
                    MembershipState.merge(MembershipState.PROCESSED, MembershipState.INACCESSIBLE));
        }

        @Test
        void unreadableBeatsEveryNonTerminalState() {
            assertEquals(MembershipState.INACCESSIBLE,
                    MembershipState.merge(MembershipState.DISCOVERED, MembershipState.INACCESSIBLE));
            assertEquals(MembershipState.INACCESSIBLE,
                    MembershipState.merge(MembershipState.DEFERRED, MembershipState.INACCESSIBLE));
        }

        @Test
        void aStrongerProposalRescuesAPreviouslyRejectedChunk() {
            assertEquals(MembershipState.DISCOVERED,
                    MembershipState.merge(MembershipState.EXCLUDED, MembershipState.DISCOVERED));
        }

        @Test
        void mergingWithNothingIsIdentity() {
            assertEquals(MembershipState.DEFERRED,
                    MembershipState.merge(null, MembershipState.DEFERRED));
            assertEquals(MembershipState.DEFERRED,
                    MembershipState.merge(MembershipState.DEFERRED, null));
        }
    }

    @Nested
    @DisplayName("DiscoveryChannel")
    class Channels {

        @Test
        void authoritativeChannelsOutrankProposals() {
            assertTrue(DiscoveryChannel.DIRECT_IDENTIFIER.priority()
                    < DiscoveryChannel.SEMANTIC.priority());
            assertTrue(DiscoveryChannel.STRUCTURED_RELATIONSHIP.priority()
                    < DiscoveryChannel.SEMANTIC.priority());
        }

        @Test
        void contradictionsAreWeighedLastOfAll() {
            for (DiscoveryChannel channel : DiscoveryChannel.values()) {
                if (channel != DiscoveryChannel.CONTRADICTION) {
                    assertTrue(channel.priority() < DiscoveryChannel.CONTRADICTION.priority(),
                            channel.name());
                }
            }
        }

        @Test
        void anIdentifierMatchIsRememberedOverAMereResemblance() {
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER,
                    DiscoveryChannel.strongest(DiscoveryChannel.SEMANTIC,
                            DiscoveryChannel.DIRECT_IDENTIFIER));
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER,
                    DiscoveryChannel.strongest(DiscoveryChannel.DIRECT_IDENTIFIER,
                            DiscoveryChannel.SEMANTIC));
        }

        @Test
        void strongestTolerantOfNulls() {
            assertEquals(DiscoveryChannel.SEED, DiscoveryChannel.strongest(null, DiscoveryChannel.SEED));
            assertEquals(DiscoveryChannel.SEED, DiscoveryChannel.strongest(DiscoveryChannel.SEED, null));
        }

        @Test
        void everyChannelExplainsItself() {
            for (DiscoveryChannel channel : DiscoveryChannel.values()) {
                assertFalse(channel.defaultReason() == null || channel.defaultReason().isBlank(),
                        channel.name());
            }
        }
    }

    @Nested
    @DisplayName("AccessScope")
    class AccessScopes {

        @Test
        void unrestrictedMaterialIsReadableByAnyone() {
            assertTrue(AccessScope.unrestricted().isReadableBy(AccessScope.of("finance")));
            assertTrue(AccessScope.unrestricted().isReadableBy(AccessScope.unrestricted()));
            assertTrue(AccessScope.unrestricted().isReadableBy(null));
        }

        @Test
        void aReaderHoldingNoDomainsCannotReadRestrictedMaterial() {
            // The safe direction for a default: holding nothing means seeing only what is public.
            assertFalse(AccessScope.of("hr").isReadableBy(AccessScope.unrestricted()));
            assertFalse(AccessScope.of("hr").isReadableBy(null));
        }

        @Test
        void holdingAnyOneOfTheDomainsIsEnough() {
            AccessScope material = AccessScope.of("hr", "legal");
            assertTrue(material.isReadableBy(AccessScope.of("legal")));
            assertTrue(material.isReadableBy(AccessScope.of("legal", "finance")));
            assertFalse(material.isReadableBy(AccessScope.of("finance")));
        }

        @Test
        void aUnionIsAtLeastAsRestrictedAsItsParts() {
            AccessScope union = AccessScope.of("hr").union(AccessScope.of("legal"));
            assertEquals(Set.of("hr", "legal"), union.domains());
            assertTrue(union.isReadableBy(AccessScope.of("hr")));
        }

        @Test
        void unionWithUnrestrictedDoesNotWidenAccess() {
            AccessScope restricted = AccessScope.of("hr");
            assertEquals(restricted, restricted.union(AccessScope.unrestricted()));
            assertEquals(restricted, restricted.union(null));
            assertEquals(restricted, AccessScope.unrestricted().union(restricted));
        }

        @Test
        void blanksAndDuplicatesAreNormalizedAway() {
            AccessScope scope = AccessScope.of("hr", " hr ", "", "  ", "legal");
            assertEquals(Set.of("hr", "legal"), scope.domains());
        }

        @Test
        void emptyMeansUnrestricted() {
            assertTrue(AccessScope.of().isUnrestricted());
            assertTrue(AccessScope.of(List.of()).isUnrestricted());
            assertTrue(new AccessScope(null).isUnrestricted());
            assertEquals("unrestricted", AccessScope.unrestricted().toString());
        }
    }

    @Nested
    @DisplayName("PartitionKey")
    class Keys {

        @Test
        void policyAndSnapshotArePartOfIdentityNotMetadata() {
            PartitionKey a = PartitionKey.forEntity("ent-acme", "policy-1", "snap-1");
            PartitionKey b = PartitionKey.forEntity("ent-acme", "policy-2", "snap-1");
            PartitionKey c = PartitionKey.forEntity("ent-acme", "policy-1", "snap-2");
            assertNotEquals(a.id(), b.id());
            assertNotEquals(a.id(), c.id());
        }

        @Test
        void idIsDeterministicAcrossRuns() {
            assertEquals(PartitionKey.forEntity("ent-acme", "p1", "s1").id(),
                    PartitionKey.forEntity("ent-acme", "p1", "s1").id());
        }

        @Test
        void aPartitionMustBeAboutSomething() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PartitionKey(null, null, "cat", null, "p", "s"));
            assertThrows(IllegalArgumentException.class,
                    () -> new PartitionKey("  ", " ", null, null, "p", "s"));
        }

        @Test
        void groupPartitionsAreSupportedAlongsideSingleEntities() {
            PartitionKey group = PartitionKey.forGroup("community-4", "p", "s");
            assertEquals("community-4", group.subject());
            assertTrue(group.id().contains("g=community-4"));
        }

        @Test
        void narrowingProducesADifferentPartition() {
            PartitionKey base = PartitionKey.forEntity("ent-acme", "p", "s");
            assertNotEquals(base.id(), base.withCategory("financial").id());
            assertNotEquals(base.id(), base.withTimeWindow("2019-Q1").id());
            assertNotEquals(base.id(), base.withPolicyVersion("p2").id());
            assertNotEquals(base.id(), base.withSnapshot("s2").id());
        }

        @Test
        void categoryIsCaseInsensitiveInTheIdentifier() {
            PartitionKey base = PartitionKey.forEntity("ent-acme", "p", "s");
            assertEquals(base.withCategory("Financial").id(), base.withCategory("financial").id());
        }

        @Test
        void anUnversionedPolicyIsLabelledRatherThanOmitted() {
            assertTrue(PartitionKey.forEntity("ent-acme", null, null).id().contains("p=unversioned"));
        }

        @Test
        void blankComponentsAreNormalizedToAbsent() {
            PartitionKey key = new PartitionKey("ent-acme", "  ", "", null, "  ", null);
            assertEquals(null, key.groupId());
            assertEquals(null, key.category());
            assertEquals(null, key.policyVersion());
        }
    }

    @Nested
    @DisplayName("PartitionMember")
    class Members {

        private static ChunkCandidate candidate(String id, DiscoveryChannel channel, double conf) {
            return ChunkCandidate.of(id, channel, conf);
        }

        @Test
        void admittingACandidateKeepsItsReasonAndChannel() {
            PartitionMember member = PartitionMember.admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9, "alias hit"),
                    MembershipState.DISCOVERED, 1);
            assertEquals("alias hit", member.reason());
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, member.channel());
            assertEquals(1, member.discoveredRound());
            assertEquals(0, member.processedRound());
        }

        @Test
        void aCandidateWithoutAReasonBorrowsItsChannelsExplanation() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6), MembershipState.DISCOVERED, 1);
            assertEquals(DiscoveryChannel.SEMANTIC.defaultReason(), member.reason());
        }

        @Test
        void aSecondChannelAgreeingStrengthensMembershipAndNeverWeakensIt() {
            PartitionMember weak = PartitionMember.admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.55, "looks similar"),
                    MembershipState.DISCOVERED, 1);
            PartitionMember merged = weak.mergeWith(
                    ChunkCandidate.of("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95, "alias hit"),
                    MembershipState.DISCOVERED);

            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, merged.channel());
            assertEquals(0.95, merged.confidence(), 1e-9);
            assertTrue(merged.reason().contains("looks similar"));
            assertTrue(merged.reason().contains("alias hit"));
        }

        @Test
        void aWeakerSecondOpinionDoesNotDowngradeTheRecordedChannel() {
            PartitionMember strong = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95),
                    MembershipState.DISCOVERED, 1);
            PartitionMember merged = strong.mergeWith(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.4), MembershipState.DEFERRED);

            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, merged.channel());
            assertEquals(0.95, merged.confidence(), 1e-9);
            assertEquals(MembershipState.DISCOVERED, merged.state());
        }

        @Test
        void repeatingTheSameReasonDoesNotAccumulateNoise() {
            PartitionMember member = PartitionMember.admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.6, "same"),
                    MembershipState.DISCOVERED, 1);
            PartitionMember merged = member.mergeWith(
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.6, "same"),
                    MembershipState.DISCOVERED);
            assertEquals("same", merged.reason());
        }

        @Test
        void mergingUnionsAccessScopeSoTheStricterRuleSurvives() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6)
                            .withAccessScope(AccessScope.of("hr")),
                    MembershipState.DISCOVERED, 1);
            PartitionMember merged = member.mergeWith(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6)
                            .withAccessScope(AccessScope.of("legal")),
                    MembershipState.DISCOVERED);
            assertEquals(Set.of("hr", "legal"), merged.accessScope().domains());
        }

        @Test
        void mergingWithNothingIsIdentity() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEED, 1.0), MembershipState.DISCOVERED, 1);
            assertSame(member, member.mergeWith(null, MembershipState.PROCESSED));
        }

        @Test
        void reachingATerminalStateRecordsTheRoundItSettledIn() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEED, 1.0), MembershipState.DISCOVERED, 1);
            PartitionMember done = member.withState(MembershipState.PROCESSED, 2, "ok");
            assertEquals(2, done.processedRound());
            assertEquals("ok", done.note());
        }

        @Test
        void goingBackToOutstandingClearsTheSettledRound() {
            PartitionMember done = PartitionMember
                    .admit(candidate("c1", DiscoveryChannel.SEED, 1.0), MembershipState.DISCOVERED, 1)
                    .withState(MembershipState.PROCESSED, 2, null);
            PartitionMember reopened = done.withState(MembershipState.INVALIDATED, 3, "source moved");
            assertEquals(0, reopened.processedRound());
            assertTrue(reopened.state().isOutstanding());
        }

        @Test
        void invalidationRecordsTheNewSourceVersion() {
            PartitionMember member = PartitionMember
                    .admit(candidate("c1", DiscoveryChannel.SEED, 1.0).withVersion("v1"),
                            MembershipState.DISCOVERED, 1)
                    .withState(MembershipState.PROCESSED, 1, null);
            PartitionMember stale = member.invalidated("v2", null);
            assertEquals("v2", stale.chunkVersion());
            assertEquals(MembershipState.INVALIDATED, stale.state());
            assertEquals("source version changed", stale.note());
        }

        @Test
        void confidenceIsClampedAndAChunkIdIsRequired() {
            assertEquals(1.0, PartitionMember.admit(candidate("c1", DiscoveryChannel.SEED, 4.0),
                    MembershipState.DISCOVERED, 1).confidence(), 1e-9);
            assertEquals(0.0, PartitionMember.admit(candidate("c1", DiscoveryChannel.SEED, -4.0),
                    MembershipState.DISCOVERED, 1).confidence(), 1e-9);
            assertThrows(IllegalArgumentException.class,
                    () -> ChunkCandidate.of("  ", DiscoveryChannel.SEED, 0.5));
        }

        @Test
        void notANumberConfidenceBecomesZeroRatherThanPoisoningComparisons() {
            assertEquals(0.0, ChunkCandidate.of("c1", DiscoveryChannel.SEED, Double.NaN)
                    .confidence(), 1e-9);
        }

        @Test
        void aRepeatOfWhatIsAlreadyHeldIsAbsorbed() {
            // The property a re-walking channel depends on: because merging accumulates reasons,
            // without this a round that only re-proposed known chunks would still read as an
            // upgrade, and the frontier would never be exhausted.
            ChunkCandidate proposal =
                    ChunkCandidate.of("c1", DiscoveryChannel.STRUCTURED_RELATIONSHIP, 0.64,
                            "1 hop from acme via employs");
            PartitionMember member =
                    PartitionMember.admit(proposal, MembershipState.DISCOVERED, 1);
            assertTrue(member.absorbs(proposal));
        }

        @Test
        void aStrongerChannelSayingTheSameThingIsNotAbsorbed() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6), MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(candidate("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.6)));
        }

        @Test
        void moreConfidenceIsNotAbsorbed() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.4), MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(candidate("c1", DiscoveryChannel.SEMANTIC, 0.8)));
        }

        @Test
        void aWeakerSecondOpinionSayingNothingNewIsAbsorbed() {
            // Nothing about the member would change: the channel is already stronger, the
            // confidence already higher, and the reason is already on the record.
            PartitionMember member = PartitionMember.admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95, "cited here"),
                    MembershipState.DISCOVERED, 1);
            assertTrue(member.absorbs(
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.4, "cited here")));
        }

        @Test
        void aWeakerChannelsOwnExplanationIsStillSomethingNew() {
            // Its default reason is not the one on the record, so the audit trail gains a line.
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95),
                    MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(candidate("c1", DiscoveryChannel.SEMANTIC, 0.4)));
        }

        @Test
        void aNewReasonForAKnownChunkIsRealNews() {
            PartitionMember member = PartitionMember.admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.6, "looks similar"),
                    MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.6, "1 hop from acme")));
        }

        @Test
        void aWiderAccessScopeIsNotAbsorbed() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6)
                            .withAccessScope(AccessScope.of("hr")),
                    MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(candidate("c1", DiscoveryChannel.SEMANTIC, 0.6)
                    .withAccessScope(AccessScope.of("legal"))));
        }

        @Test
        void aDocumentLinkTheMemberLacksIsNotAbsorbed() {
            // Without the link the chunk can never be invalidated when its source changes.
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6),
                    MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(
                    candidate("c1", DiscoveryChannel.SEMANTIC, 0.6).inDocument("doc-1")));
        }

        @Test
        void anAlreadyProcessedChunkAbsorbsItsOwnRediscovery() {
            // Re-proposing settled work must not look like progress, or a partition would keep
            // rescheduling what it has already done.
            ChunkCandidate proposal =
                    ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.6, "looks similar");
            PartitionMember done = PartitionMember
                    .admit(proposal, MembershipState.DISCOVERED, 1)
                    .withState(MembershipState.PROCESSED, 2, null);
            assertTrue(done.absorbs(proposal));
            assertEquals(2, done.processedRound(), "and the round it settled in is not disturbed");
        }

        @Test
        void nothingProposedIsNotAbsorption() {
            PartitionMember member = PartitionMember.admit(
                    candidate("c1", DiscoveryChannel.SEED, 1.0), MembershipState.DISCOVERED, 1);
            assertFalse(member.absorbs(null));
        }
    }

    @Nested
    @DisplayName("DiscoveryPolicy")
    class Policies {

        @Test
        void defaultsRunIdentifiersStructureSemanticsAndContradictions() {
            DiscoveryPolicy policy = DiscoveryPolicy.defaults();
            assertTrue(policy.runs(DiscoveryChannel.DIRECT_IDENTIFIER));
            assertTrue(policy.runs(DiscoveryChannel.STRUCTURED_RELATIONSHIP));
            assertTrue(policy.runs(DiscoveryChannel.SEMANTIC));
            assertTrue(policy.runs(DiscoveryChannel.CONTRADICTION));
        }

        @Test
        void topicAndProcessChannelsAreOptInBecauseTheyNeedConfiguration() {
            DiscoveryPolicy policy = DiscoveryPolicy.defaults();
            assertFalse(policy.runs(DiscoveryChannel.TOPIC));
            assertFalse(policy.runs(DiscoveryChannel.PROCESS));
            assertFalse(policy.runs(null));
        }

        @Test
        void confidenceBandsSeparateRejectedFromPostponedFromSchedulable() {
            DiscoveryPolicy policy = DiscoveryPolicy.defaults();
            assertEquals(MembershipState.EXCLUDED, policy.classify(0.1));
            assertEquals(MembershipState.DEFERRED, policy.classify(0.3));
            assertEquals(MembershipState.DISCOVERED, policy.classify(0.5));
            assertEquals(MembershipState.DISCOVERED, policy.classify(0.99));
        }

        @Test
        void bandsAreKeptOrderedSoNotSureYetNeverBecomesRejected() {
            DiscoveryPolicy policy = DiscoveryPolicy.defaults()
                    .withThresholds("v2", 0.6, 0.2);
            assertTrue(policy.deferBelow() >= policy.excludeBelow());
            assertEquals(MembershipState.EXCLUDED, policy.classify(0.5));
            assertEquals(MembershipState.DISCOVERED, policy.classify(0.6));
        }

        @Test
        void nonsenseThresholdsFallBackToDefaults() {
            DiscoveryPolicy policy = new DiscoveryPolicy("v", null, 0, -1, 5, 0, 0, null);
            assertEquals(DiscoveryPolicy.DEFAULT_EXCLUDE_BELOW, policy.excludeBelow(), 1e-9);
            assertEquals(DiscoveryPolicy.DEFAULT_DEFER_BELOW, policy.deferBelow(), 1e-9);
            assertEquals(DiscoveryPolicy.DEFAULT_MAX_ROUNDS, policy.maxRounds());
            assertEquals(DiscoveryPolicy.DEFAULT_MAX_MEMBERS, policy.maxMembers());
            assertEquals(DiscoveryPolicy.DEFAULT_MAX_CANDIDATES_PER_CHANNEL,
                    policy.maxCandidatesPerChannel());
        }

        @Test
        void anEmptyChannelRequestMeansTheDefaultsNotSilence() {
            assertEquals(DiscoveryPolicy.defaults().channels(),
                    DiscoveryPolicy.defaults().withChannels("v2", List.of()).channels());
            assertEquals(DiscoveryPolicy.defaults().channels(),
                    DiscoveryPolicy.defaults().withChannels("v2", (List<DiscoveryChannel>) null)
                            .channels());
        }

        @Test
        void narrowingChannelsIsExact() {
            DiscoveryPolicy policy = DiscoveryPolicy.defaults()
                    .withChannels("identifiers-only", DiscoveryChannel.DIRECT_IDENTIFIER);
            assertEquals(Set.of(DiscoveryChannel.DIRECT_IDENTIFIER), policy.channels());
            assertEquals("identifiers-only", policy.version());
        }

        @Test
        void aBlankVersionIsNamedRatherThanLeftNull() {
            assertEquals(DiscoveryPolicy.DEFAULT_VERSION,
                    DiscoveryPolicy.defaults().withChannels("  ",
                            DiscoveryChannel.SEED).version());
        }

        @Test
        void aNarrowerReaderScopeIsADifferentPolicy() {
            DiscoveryPolicy restricted = DiscoveryPolicy.defaults()
                    .withReaderScope("finance-reader", AccessScope.of("finance"));
            assertEquals("finance-reader", restricted.version());
            assertEquals(Set.of("finance"), restricted.readerScope().domains());
            assertNotEquals(DiscoveryPolicy.defaults().version(), restricted.version());
        }

        @Test
        void budgetsAreCarriedOnTheVersionedPolicy() {
            DiscoveryPolicy policy = DiscoveryPolicy.defaults().withBudget("tight", 1, 5);
            assertEquals(1, policy.maxRounds());
            assertEquals(5, policy.maxMembers());
        }
    }
}
