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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The partition aggregate and the manifest derived from it — the part of the design that lets a
 * run say what it has <em>not</em> looked at.
 */
class EntityPartitionTest {

    private static final PartitionKey KEY =
            PartitionKey.forEntity("ent-acme", "discovery-v1", "snap-1");

    private static ChunkCandidate chunk(String id, DiscoveryChannel channel, double confidence) {
        return ChunkCandidate.of(id, channel, confidence);
    }

    private static EntityPartition withDiscovered(String... ids) {
        EntityPartition partition = EntityPartition.open(KEY);
        for (String id : ids) {
            partition = partition.admit(chunk(id, DiscoveryChannel.SEED, 1.0),
                    MembershipState.DISCOVERED, 1);
        }
        return partition;
    }

    @Nested
    @DisplayName("membership")
    class Membership {

        @Test
        void aFreshPartitionIsEmptyAndOwesNoWork() {
            EntityPartition partition = EntityPartition.open(KEY);
            assertEquals(PartitionPhase.NEW, partition.phase());
            assertEquals(0, partition.size());
            assertFalse(partition.hasOutstandingWork());
            assertEquals(KEY.id(), partition.id());
        }

        @Test
        void aPartitionMustHaveAKey() {
            assertThrows(IllegalArgumentException.class, () -> EntityPartition.open(null));
        }

        @Test
        void admittingRecordsWhoProposedTheChunkAndWhen() {
            EntityPartition partition = EntityPartition.open(KEY).admit(
                    ChunkCandidate.of("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9, "ticker AAPL"),
                    MembershipState.DISCOVERED, 2);
            PartitionMember member = partition.member("c1").orElseThrow();
            assertEquals("ticker AAPL", member.reason());
            assertEquals(2, member.discoveredRound());
            assertTrue(partition.hasOutstandingWork());
        }

        @Test
        void reAdmittingAChunkMergesRatherThanReplacingIt() {
            EntityPartition partition = EntityPartition.open(KEY)
                    .admit(ChunkCandidate.of("c1", DiscoveryChannel.SEMANTIC, 0.6, "similar"),
                            MembershipState.DISCOVERED, 1)
                    .admit(ChunkCandidate.of("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.95, "alias"),
                            MembershipState.DISCOVERED, 2);

            assertEquals(1, partition.size());
            PartitionMember member = partition.member("c1").orElseThrow();
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, member.channel());
            assertEquals(1, member.discoveredRound(), "first sighting is when it was discovered");
            assertTrue(member.reason().contains("similar") && member.reason().contains("alias"));
        }

        @Test
        void reDiscoveringProcessedWorkDoesNotScheduleItAgain() {
            EntityPartition partition = withDiscovered("c1")
                    .withState("c1", MembershipState.PROCESSED, "done")
                    .admit(chunk("c1", DiscoveryChannel.SEMANTIC, 0.8),
                            MembershipState.DISCOVERED, 2);

            assertEquals(MembershipState.PROCESSED, partition.member("c1").orElseThrow().state());
            assertTrue(partition.schedulable().isEmpty());
            assertFalse(partition.hasOutstandingWork());
        }

        @Test
        void statingAnOutcomeForAChunkNobodyProposedIsIgnored() {
            EntityPartition partition = withDiscovered("c1");
            assertSame(partition, partition.withState("nope", MembershipState.PROCESSED, null));
            assertSame(partition, partition.admit(null, MembershipState.DISCOVERED, 1));
            assertSame(partition, partition.withMember(null));
        }

        @Test
        void onlyDiscoveredAndInvalidatedMembersAreSchedulable() {
            EntityPartition partition = withDiscovered("c1", "c2", "c3", "c4")
                    .withState("c2", MembershipState.PROCESSED, null)
                    .withState("c3", MembershipState.DEFERRED, "low confidence")
                    .withState("c4", MembershipState.INVALIDATED, "source moved");

            assertEquals(List.of("c1", "c4"),
                    partition.schedulable().stream().map(PartitionMember::chunkId).toList());
        }

        @Test
        void deferredWorkStillCountsAsOutstanding() {
            EntityPartition partition = withDiscovered("c1")
                    .withState("c1", MembershipState.DEFERRED, "postponed");
            assertTrue(partition.hasOutstandingWork(), "postponed is not the same as finished");
            assertTrue(partition.schedulable().isEmpty(), "but it is not schedulable either");
        }

        @Test
        void unreadableAndRejectedChunksAreSettledNotOutstanding() {
            EntityPartition partition = withDiscovered("c1", "c2")
                    .withState("c1", MembershipState.INACCESSIBLE, "acl")
                    .withState("c2", MembershipState.EXCLUDED, "off topic");
            assertFalse(partition.hasOutstandingWork());
        }

        @Test
        void phaseAndRoundAdvanceWithoutDisturbingMembership() {
            EntityPartition partition = withDiscovered("c1")
                    .withPhase(PartitionPhase.PROCESSING)
                    .withRound(4);
            assertEquals(PartitionPhase.PROCESSING, partition.phase());
            assertEquals(4, partition.round());
            assertEquals(1, partition.size());
        }

        @Test
        void pinsCarryTheProvenanceLabelsExtractionPromptsNeed() {
            EntityPartition partition = EntityPartition.open(KEY)
                    .withPin("graph", "graph-7")
                    .withPin("corpus", "fpna-v15")
                    .withPin("  ", "ignored");
            assertEquals("graph-7", partition.pins().get("graph"));
            assertEquals("fpna-v15", partition.pins().get("corpus"));
            assertEquals(2, partition.pins().size());
        }

        @Test
        void membersAreImmutableFromOutside() {
            EntityPartition partition = withDiscovered("c1");
            assertThrows(UnsupportedOperationException.class,
                    () -> partition.members().put("c2", null));
        }
    }

    @Nested
    @DisplayName("selective invalidation")
    class Invalidation {

        private EntityPartition twoDocuments() {
            return EntityPartition.open(KEY)
                    .admit(chunk("a1", DiscoveryChannel.SEED, 1.0).inDocument("docA"),
                            MembershipState.DISCOVERED, 1)
                    .admit(chunk("a2", DiscoveryChannel.SEED, 1.0).inDocument("docA"),
                            MembershipState.DISCOVERED, 1)
                    .admit(chunk("b1", DiscoveryChannel.SEED, 1.0).inDocument("docB"),
                            MembershipState.DISCOVERED, 1)
                    .withState("a1", MembershipState.PROCESSED, null)
                    .withState("a2", MembershipState.PROCESSED, null)
                    .withState("b1", MembershipState.PROCESSED, null);
        }

        @Test
        void aChangedSourceInvalidatesItsOwnChunksAndNoOthers() {
            EntityPartition partition = twoDocuments().invalidateDocument("docA", "v2", null);

            assertEquals(MembershipState.INVALIDATED, partition.member("a1").orElseThrow().state());
            assertEquals(MembershipState.INVALIDATED, partition.member("a2").orElseThrow().state());
            assertEquals(MembershipState.PROCESSED, partition.member("b1").orElseThrow().state());
            assertEquals("v2", partition.member("a1").orElseThrow().chunkVersion());
        }

        @Test
        void invalidatedWorkBecomesSchedulableAgain() {
            EntityPartition partition = twoDocuments().invalidateDocument("docA", "v2", null);
            assertEquals(List.of("a1", "a2"),
                    partition.schedulable().stream().map(PartitionMember::chunkId).toList());
            assertTrue(partition.hasOutstandingWork());
        }

        @Test
        void invalidatingAnUnknownOrAlreadyInvalidDocumentChangesNothing() {
            EntityPartition partition = twoDocuments();
            assertSame(partition, partition.invalidateDocument("docZ", "v2", null));
            assertSame(partition, partition.invalidateDocument(null, "v2", null));
            assertSame(partition, partition.invalidateDocument("  ", "v2", null));

            EntityPartition once = partition.invalidateDocument("docA", "v2", null);
            assertSame(once, once.invalidateDocument("docA", "v3", null));
        }

        @Test
        void aSingleChunkCanBeInvalidatedOnItsOwn() {
            EntityPartition partition = twoDocuments().invalidateChunk("a1", "v2", "re-chunked");
            assertEquals(MembershipState.INVALIDATED, partition.member("a1").orElseThrow().state());
            assertEquals("re-chunked", partition.member("a1").orElseThrow().note());
            assertEquals(MembershipState.PROCESSED, partition.member("a2").orElseThrow().state());
            assertSame(partition, partition.invalidateChunk("nope", "v2", null));
        }
    }

    @Nested
    @DisplayName("evidence manifest")
    class Manifest {

        @Test
        void anUntouchedPartitionIsNotCompleteEvenWithNothingOutstanding() {
            EvidenceManifest manifest = EntityPartition.open(KEY).manifest(true);
            assertEquals(0, manifest.total());
            assertEquals(0.0, manifest.coverage(), 1e-9);
            assertFalse(manifest.isProvisionallyComplete(),
                    "a partition that read nothing has not covered its subject");
        }

        @Test
        void coverageIsProcessedOverAdmittedNotOverProposed() {
            // A policy that rejects 6 junk proposals should not report 25% — the rejections are
            // not evidence we skipped, they are evidence we judged irrelevant.
            EntityPartition partition = withDiscovered("c1", "c2")
                    .withState("c1", MembershipState.PROCESSED, null);
            for (int i = 0; i < 6; i++) {
                partition = partition.admit(chunk("junk" + i, DiscoveryChannel.SEMANTIC, 0.05),
                        MembershipState.EXCLUDED, 1);
            }

            EvidenceManifest manifest = partition.manifest(false);
            assertEquals(8, manifest.total());
            assertEquals(2, manifest.admitted());
            assertEquals(1, manifest.covered());
            assertEquals(0.5, manifest.coverage(), 1e-9);
        }

        @Test
        void theRejectionsAreStatedRatherThanNettedOut() {
            EntityPartition partition = withDiscovered("c1")
                    .withState("c1", MembershipState.PROCESSED, null)
                    .admit(chunk("junk", DiscoveryChannel.SEMANTIC, 0.05),
                            MembershipState.EXCLUDED, 1);
            String described = partition.manifest(true).describe();
            assertTrue(described.contains("covered=1/1"), described);
            assertTrue(described.contains("excluded=1"), described);
        }

        @Test
        void everyKindOfNotProcessedIsReportedSeparately() {
            EntityPartition partition = withDiscovered("done", "pending", "later", "locked", "stale")
                    .withState("done", MembershipState.PROCESSED, null)
                    .withState("later", MembershipState.DEFERRED, "budget")
                    .withState("locked", MembershipState.INACCESSIBLE, "acl")
                    .withState("stale", MembershipState.INVALIDATED, "source moved");

            EvidenceManifest manifest = partition.manifest(true);
            assertEquals(List.of("later"), manifest.deferred());
            assertEquals(List.of("locked"), manifest.inaccessible());
            assertEquals(List.of("stale"), manifest.invalidated());
            assertEquals(List.of("pending", "later", "stale"), manifest.outstanding());
            assertEquals(2, manifest.accountedFor(),
                    "the processed one plus the locked one — settled without both being coverage");
            assertEquals(1, manifest.covered());
        }

        @Test
        void completionNeedsBothAnExhaustedFrontierAndNoOutstandingWork() {
            EntityPartition done = withDiscovered("c1")
                    .withState("c1", MembershipState.PROCESSED, null);

            assertTrue(done.manifest(true).isProvisionallyComplete());
            assertFalse(done.manifest(false).isProvisionallyComplete(),
                    "channels still producing candidates means the answer can still change");

            EntityPartition stillOwed = done.admit(chunk("c2", DiscoveryChannel.SEED, 1.0),
                    MembershipState.DISCOVERED, 2);
            assertFalse(stillOwed.manifest(true).isProvisionallyComplete());
        }

        @Test
        void completeWithGapsIsDistinctFromComplete() {
            EntityPartition clean = withDiscovered("c1")
                    .withState("c1", MembershipState.PROCESSED, null);
            assertTrue(clean.manifest(true).isProvisionallyComplete());
            assertFalse(clean.manifest(true).isCompleteWithGaps());

            EntityPartition gapped = clean.admit(chunk("c2", DiscoveryChannel.SEED, 1.0),
                            MembershipState.DISCOVERED, 1)
                    .withState("c2", MembershipState.INACCESSIBLE, "acl");
            EvidenceManifest manifest = gapped.manifest(true);
            assertTrue(manifest.isProvisionallyComplete());
            assertTrue(manifest.isCompleteWithGaps(),
                    "an answer built from this is missing evidence known to exist");
            assertTrue(manifest.describe().contains("inaccessible=1"));
        }

        @Test
        void countsAreBrokenOutByStateAndByChannel() {
            EntityPartition partition = EntityPartition.open(KEY)
                    .admit(chunk("c1", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9),
                            MembershipState.DISCOVERED, 1)
                    .admit(chunk("c2", DiscoveryChannel.SEMANTIC, 0.6),
                            MembershipState.DISCOVERED, 1)
                    .admit(chunk("c3", DiscoveryChannel.SEMANTIC, 0.6),
                            MembershipState.DISCOVERED, 1)
                    .withState("c1", MembershipState.PROCESSED, null);

            EvidenceManifest manifest = partition.manifest(false);
            assertEquals(1, manifest.count(MembershipState.PROCESSED));
            assertEquals(2, manifest.count(MembershipState.DISCOVERED));
            assertEquals(0, manifest.count(MembershipState.EXCLUDED));
            assertEquals(1, manifest.count(DiscoveryChannel.DIRECT_IDENTIFIER));
            assertEquals(2, manifest.count(DiscoveryChannel.SEMANTIC));
            assertEquals(0, manifest.count(DiscoveryChannel.CONTRADICTION));
        }

        @Test
        void theManifestNamesThePolicyAndSnapshotItIsRelativeTo() {
            String described = withDiscovered("c1").manifest(false).describe();
            assertTrue(described.contains("p=discovery-v1"), described);
            assertTrue(described.contains("s=snap-1"), described);
            assertTrue(described.contains("[open]"), described);
        }

        @Test
        void aManifestIsDefensiveAboutNulls() {
            EvidenceManifest manifest =
                    new EvidenceManifest(KEY, 1, null, null, null, null, null, null, false);
            assertEquals(0, manifest.total());
            assertTrue(manifest.deferred().isEmpty());
            assertFalse(manifest.isProvisionallyComplete());
        }
    }

    @Nested
    @DisplayName("PartitionStore")
    class Store {

        @Test
        void loadOrOpenReturnsWhatWasSavedAndOtherwiseAFreshPartition() {
            PartitionStore store = new PartitionStore.InMemoryPartitionStore();

            EntityPartition fresh = store.loadOrOpen(KEY);
            assertEquals(0, fresh.size());

            store.save(withDiscovered("c1"));
            assertEquals(1, store.loadOrOpen(KEY).size());
        }

        @Test
        void lookupsToleratePartitionsThatWereNeverSaved() {
            PartitionStore store = new PartitionStore.InMemoryPartitionStore();
            assertTrue(store.load(KEY.id()).isEmpty());
            assertTrue(store.load(null).isEmpty());
            store.save(null);
            store.delete(null);
            assertEquals(0, ((PartitionStore.InMemoryPartitionStore) store).size());
        }

        @Test
        void partitionsAreFoundByThePolicyTheyWereBuiltUnder() {
            PartitionStore store = new PartitionStore.InMemoryPartitionStore();
            store.save(EntityPartition.open(PartitionKey.forEntity("a", "v1", "s")));
            store.save(EntityPartition.open(PartitionKey.forEntity("b", "v1", "s")));
            store.save(EntityPartition.open(PartitionKey.forEntity("c", "v2", "s")));

            assertEquals(2, store.findByPolicy("v1").size());
            assertEquals(1, store.findByPolicy("v2").size());
            assertEquals(0, store.findByPolicy("v3").size());
        }

        @Test
        void aChangedDocumentCanFindEveryPartitionThatCitedIt() {
            PartitionStore store = new PartitionStore.InMemoryPartitionStore();
            store.save(EntityPartition.open(PartitionKey.forEntity("a", "v1", "s"))
                    .admit(chunk("c1", DiscoveryChannel.SEED, 1.0).inDocument("docA"),
                            MembershipState.DISCOVERED, 1));
            store.save(EntityPartition.open(PartitionKey.forEntity("b", "v1", "s"))
                    .admit(chunk("c2", DiscoveryChannel.SEED, 1.0).inDocument("docA"),
                            MembershipState.DISCOVERED, 1));
            store.save(EntityPartition.open(PartitionKey.forEntity("c", "v1", "s"))
                    .admit(chunk("c3", DiscoveryChannel.SEED, 1.0).inDocument("docB"),
                            MembershipState.DISCOVERED, 1));

            assertEquals(2, store.findByDocument("docA").size());
            assertEquals(1, store.findByDocument("docB").size());
            assertEquals(0, store.findByDocument("docC").size());
            assertEquals(0, store.findByDocument(null).size());
        }

        @Test
        void savingTheSameKeyTwiceOverwritesRatherThanAccumulating() {
            PartitionStore.InMemoryPartitionStore store = new PartitionStore.InMemoryPartitionStore();
            store.save(withDiscovered("c1"));
            store.save(withDiscovered("c1", "c2"));
            assertEquals(1, store.size());
            assertEquals(2, store.load(KEY.id()).orElseThrow().size());

            store.delete(KEY.id());
            assertEquals(0, store.size());
        }
    }
}
