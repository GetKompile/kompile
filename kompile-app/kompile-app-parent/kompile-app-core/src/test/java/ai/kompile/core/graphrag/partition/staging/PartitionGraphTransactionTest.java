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

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.staging.PartitionGraphTransaction.Checkpoint;
import ai.kompile.core.graphrag.partition.staging.PartitionGraphTransaction.CommitReport;
import ai.kompile.core.graphrag.partition.staging.PartitionGraphTransaction.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction's job is that the graph hears about a partition once, and that a chunk whose
 * extraction went wrong costs only that chunk.
 */
class PartitionGraphTransactionTest {

    private static final PartitionKey KEY = PartitionKey.forEntity("Acme", "discovery-v1", "snap-1");

    // ---------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------

    private static Entity entity(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        return entity;
    }

    private static Graph oneEntity(String title) {
        return Graph.builder().entities(List.of(entity(title, title, "COMPANY"))).build();
    }

    private static Graph edgeTo(String source, String target) {
        Relationship relationship = new Relationship();
        relationship.setSource(source);
        relationship.setTarget(target);
        relationship.setType("owns");
        return Graph.builder()
                .entities(List.of(entity(source, source, "COMPANY")))
                .relationships(List.of(relationship))
                .build();
    }

    private static PartitionMember member(String chunkId) {
        return PartitionMember.admit(ChunkCandidate.of(chunkId, DiscoveryChannel.SEED, 1.0),
                MembershipState.DISCOVERED, 1);
    }

    /** A sink that records what it was handed and reports having written all of it. */
    private static final class RecordingSink implements GraphCommitSink {

        private final List<Graph> committed = new ArrayList<>();
        private final AtomicReference<PartitionKey> lastKey = new AtomicReference<>();

        @Override
        public CommitOutcome commit(PartitionKey key, Graph graph) {
            lastKey.set(key);
            committed.add(graph);
            return CommitOutcome.of(graph.getEntities().size(), graph.getRelationships().size());
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("staging")
    class Staging {

        @Test
        void aNewTransactionIsOpenAndHoldsNothing() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, 5L);

            assertEquals(Status.OPEN, tx.status());
            assertTrue(tx.isOpen());
            assertTrue(tx.staged().isEmpty());
            assertTrue(tx.checkpoints().isEmpty());
            assertNull(tx.closedBecause());
            assertEquals(5L, tx.factSheetId());
            assertEquals(KEY, tx.key());
        }

        @Test
        void stagingAChunkAccumulatesIntoOneMergedPicture() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            tx.stage(member("c1"), oneEntity("Acme"));
            tx.stage(member("c2"), oneEntity("acme"));

            assertEquals(1, tx.staged().entities().size());
            assertEquals(List.of("c1", "c2"), tx.staged().entity("acme").orElseThrow().chunkIds());
        }

        @Test
        void everyStagedChunkLeavesACheckpointBehindItCanBeUndoneAt() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            tx.stage(member("c1"), oneEntity("Acme"));
            tx.stage(member("c2"), oneEntity("Bob"));

            assertEquals(List.of("c1", "c2"),
                    tx.checkpoints().stream().map(Checkpoint::label).toList());
        }

        @Test
        void anExplicitCheckpointCanBeLabelledOrLeftToNameItself() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            assertEquals("round-1", tx.checkpoint("round-1").label());
            assertEquals("checkpoint-1", tx.checkpoint("  ").label());
        }

        @Test
        void stagingWithoutAMemberOrProvenanceIsRefusedRatherThanAttributedToNobody() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            assertThrows(NullPointerException.class, () -> tx.stage((PartitionMember) null,
                    oneEntity("Acme")));
            assertThrows(NullPointerException.class, () -> tx.stage((StagedProvenance) null,
                    oneEntity("Acme")));
        }

        @Test
        void aTransactionAlwaysHasAKey() {
            assertThrows(NullPointerException.class,
                    () -> PartitionGraphTransaction.openOn(null, 1L));
        }
    }

    @Nested
    @DisplayName("rolling a bad chunk back")
    class RollingBack {

        @Test
        void rollingBackToAChunkDropsItAndKeepsEverythingBeforeIt() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));
            Checkpoint beforeSecond = tx.stage(member("c2"), oneEntity("Bob"));
            tx.stage(member("c3"), oneEntity("Zeta"));

            tx.rollbackTo(beforeSecond);

            assertEquals(1, tx.staged().entities().size());
            assertTrue(tx.staged().entity("acme").isPresent());
            assertFalse(tx.staged().entity("bob").isPresent());
            assertFalse(tx.staged().entity("zeta").isPresent(),
                    "everything staged after the checkpoint goes with it");
        }

        @Test
        void rollingBackAlsoDropsTheCheckpointsThatCameAfter() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));
            Checkpoint beforeSecond = tx.stage(member("c2"), oneEntity("Bob"));
            tx.stage(member("c3"), oneEntity("Zeta"));

            tx.rollbackTo(beforeSecond);

            assertEquals(List.of("c1"), tx.checkpoints().stream().map(Checkpoint::label).toList());
        }

        @Test
        void aRolledBackTransactionKeepsWorkingAfterwards() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));
            Checkpoint beforeSecond = tx.stage(member("c2"), oneEntity("Bob"));

            tx.rollbackTo(beforeSecond);
            tx.stage(member("c2-retry"), oneEntity("Bobbie"));

            assertEquals(2, tx.staged().entities().size());
            assertTrue(tx.staged().entity("bobbie").isPresent());
        }

        @Test
        void aCheckpointFromSomewhereElseIsRefused() {
            PartitionGraphTransaction other = PartitionGraphTransaction.openOn(KEY, null);
            Checkpoint foreign = other.checkpoint("elsewhere");
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> tx.rollbackTo(foreign));
            assertTrue(thrown.getMessage().contains("does not belong"), thrown.getMessage());
        }

        @Test
        void aCheckpointPastTheEndIsRefused() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            assertThrows(IllegalArgumentException.class,
                    () -> tx.rollbackTo(new Checkpoint(4, "invented", StagedGraph.empty())));
            assertThrows(NullPointerException.class, () -> tx.rollbackTo(null));
        }
    }

    @Nested
    @DisplayName("committing")
    class Committing {

        @Test
        void theSinkIsHandedTheMergedGraphUnderThePartitionsIdentity() {
            RecordingSink sink = new RecordingSink();
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, 42L);
            tx.stage(member("c1"), oneEntity("Acme"));
            tx.stage(member("c2"), oneEntity("acme"));

            CommitReport report = tx.commit(sink);

            assertEquals(KEY, sink.lastKey.get());
            assertEquals(1, sink.committed.size());
            Graph committed = sink.committed.get(0);
            assertEquals(KEY.id(), committed.getName());
            assertEquals(42L, committed.getFactSheetId());
            assertEquals(1, committed.getEntities().size(),
                    "the store should never have to deduplicate what staging already merged");
            assertEquals(1, report.outcome().entities());
            assertEquals(Status.COMMITTED, tx.status());
        }

        @Test
        void eachBatchFlushIsVisibleWithoutRewritingTheEarlierBatch() {
            RecordingSink sink = new RecordingSink();
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, 42L);

            tx.stage(member("c1"), oneEntity("Acme"));
            GraphCommitSink.CommitOutcome first = tx.flush(sink);

            assertEquals(Status.OPEN, tx.status(), "a batch flush must not close the partition");
            assertEquals(1, first.entities());
            assertEquals(1, tx.flushCount());
            assertTrue(tx.checkpoints().isEmpty(),
                    "a rollback cannot cross output already exposed to graph discovery");
            assertEquals(List.of("Acme"), sink.committed.get(0).getEntities().stream()
                    .map(Entity::getTitle).toList());

            tx.stage(member("c2"), oneEntity("Bob"));
            CommitReport report = tx.commit(sink);

            assertEquals(2, sink.committed.size());
            assertEquals(List.of("Bob"), sink.committed.get(1).getEntities().stream()
                    .map(Entity::getTitle).toList(),
                    "the second write is the pending delta, not the whole partition again");
            assertEquals(2, report.outcome().entities());
            assertEquals(2, report.staged().entities().size());
            assertEquals(2, tx.flushCount());
            assertEquals(Status.COMMITTED, tx.status());
        }

        @Test
        void aCheckpointCannotRollBackAcrossAVisibleBatchBoundary() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, 42L);
            Checkpoint beforeFirstChunk = tx.stage(member("c1"), oneEntity("Acme"));

            tx.flush(new RecordingSink());

            assertThrows(IllegalArgumentException.class, () -> tx.rollbackTo(beforeFirstChunk));
        }

        @Test
        void anEmptyPartitionStillCommitsAndSaysItFoundNothing() {
            RecordingSink sink = new RecordingSink();
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            CommitReport report = tx.commit(sink);

            assertTrue(sink.committed.isEmpty(), "there is nothing to hand a store");
            assertEquals(0, report.outcome().entities());
            assertEquals(List.of("nothing was staged"), report.notes());
            assertEquals(Status.COMMITTED, tx.status());
        }

        @Test
        void theReportCarriesTheDisagreementsStagingSettledOnTheWay() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));
            tx.stage(member("c2"), Graph.builder()
                    .entities(List.of(entity("Acme", "Acme", "ORGANIZATION"))).build());

            CommitReport report = tx.commit(new RecordingSink());

            assertEquals(1, report.conflicts().size());
            assertEquals("type", report.conflicts().get(0).field());
            assertFalse(report.isClean());
        }

        @Test
        void anEdgeIntoSomethingUnstagedIsCalledOutRatherThanLeftToLookLikeAWrite() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), edgeTo("Acme", "Zeta Holdings"));

            CommitReport report = tx.commit(new RecordingSink());

            assertEquals(1, report.notes().size());
            assertTrue(report.notes().get(0).contains("never staged"), report.notes().toString());
        }

        @Test
        void aCleanCommitSaysSo() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));

            CommitReport report = tx.commit(new RecordingSink());

            assertTrue(report.isClean());
            assertTrue(report.describe().contains(KEY.id()), report.describe());
        }

        @Test
        void aSinkThatWritesNothingBackIsTreatedAsHavingWrittenNothing() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));

            CommitReport report = tx.commit((key, graph) -> null);

            assertEquals(0, report.outcome().entities());
            assertEquals(Status.COMMITTED, tx.status());
        }

        @Test
        void aDiscardingSinkCommitsWithoutStoringAndAdmitsIt() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));

            CommitReport report = tx.commit(GraphCommitSink.discarding());

            assertFalse(report.outcome().isClean());
            assertEquals(List.of("commit sink discards"), report.outcome().problems());
        }

        @Test
        void aSinkThatBlowsUpLeavesTheTransactionAbandonedRatherThanClaimingACommit() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> tx.commit((key, graph) -> {
                        throw new IllegalStateException("the store is down");
                    }));

            assertEquals("the store is down", thrown.getMessage());
            assertEquals(Status.ABANDONED, tx.status());
            assertTrue(tx.closedBecause().contains("commit failed"), tx.closedBecause());
        }

        @Test
        void committingWithoutASinkIsRefused() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            assertThrows(NullPointerException.class, () -> tx.commit(null));
        }

        @Test
        void theStagedGraphIsCarriedIntoTheReportSoTheCallerNeedNotKeepIt() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));
            StagedGraph staged = tx.staged();

            CommitReport report = tx.commit(new RecordingSink());

            assertSame(staged, report.staged());
        }
    }

    @Nested
    @DisplayName("once it is closed")
    class Closed {

        @Test
        void abandoningRecordsWhyAndWritesNothing() {
            RecordingSink sink = new RecordingSink();
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));

            tx.abandon("the subject turned out to be a duplicate");

            assertEquals(Status.ABANDONED, tx.status());
            assertEquals("the subject turned out to be a duplicate", tx.closedBecause());
            assertTrue(sink.committed.isEmpty());
            assertFalse(tx.staged().isEmpty(), "what it held is still inspectable after the fact");
        }

        @Test
        void abandoningWithoutAReasonStillSaysItWasAbandoned() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);

            tx.abandon("   ");

            assertEquals("abandoned", tx.closedBecause());
        }

        @Test
        void nothingMoreCanBeStagedCheckpointedOrRolledBack() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            Checkpoint first = tx.stage(member("c1"), oneEntity("Acme"));
            tx.commit(new RecordingSink());

            assertThrows(IllegalStateException.class,
                    () -> tx.stage(member("c2"), oneEntity("Bob")));
            assertThrows(IllegalStateException.class, () -> tx.checkpoint("late"));
            assertThrows(IllegalStateException.class, () -> tx.rollbackTo(first));
        }

        @Test
        void itCannotBeCommittedTwice() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.stage(member("c1"), oneEntity("Acme"));
            tx.commit(new RecordingSink());

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> tx.commit(new RecordingSink()));
            assertTrue(thrown.getMessage().contains("COMMITTED"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains(KEY.id()), thrown.getMessage());
        }

        @Test
        void anAbandonedTransactionCannotBeCommitted() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.abandon("gave up");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> tx.commit(new RecordingSink()));
            assertTrue(thrown.getMessage().contains("gave up"), thrown.getMessage());
        }

        @Test
        void aCommittedTransactionCannotBeAbandoned() {
            PartitionGraphTransaction tx = PartitionGraphTransaction.openOn(KEY, null);
            tx.commit(new RecordingSink());

            assertThrows(IllegalStateException.class, () -> tx.abandon("too late"));
        }
    }

    @Nested
    @DisplayName("commit outcomes")
    class Outcomes {

        @Test
        void negativeCountsFromAMisbehavingSinkAreNotBelieved() {
            GraphCommitSink.CommitOutcome outcome =
                    new GraphCommitSink.CommitOutcome(-3, -1, null);

            assertEquals(0, outcome.entities());
            assertEquals(0, outcome.relationships());
            assertTrue(outcome.isClean());
        }

        @Test
        void anOutcomeDescribesItselfWithItsProblems() {
            GraphCommitSink.CommitOutcome outcome =
                    new GraphCommitSink.CommitOutcome(2, 1, List.of("one edge was rejected"));

            assertFalse(outcome.isClean());
            assertTrue(outcome.describe().contains("one edge was rejected"), outcome.describe());
        }

        @Test
        void aReportWithoutAnOutcomeStillReadsAsAnEmptyCommit() {
            CommitReport report = new CommitReport(KEY, null, StagedGraph.empty(), null, null);

            assertNotNull(report.outcome());
            assertEquals(0, report.outcome().entities());
            assertTrue(report.isClean());
        }
    }
}
