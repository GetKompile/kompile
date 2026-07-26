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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The read side of the coverage claim. What matters here is not the arithmetic but that the report
 * refuses to net a gap out: a partition that gave up holding unread material has to look different
 * from one that read everything, and a rejected proposal has to stay visible next to the coverage
 * figure it was kept out of.
 */
@DisplayName("PartitionCoverageReport")
class PartitionCoverageReportTest {

    private static final String POLICY = "discovery-v1";
    private static final String SNAPSHOT = "snap-1";

    /** A partition whose members are all processed, closed so the frontier reads as exhausted. */
    private static EntityPartition complete(String subject, int chunks) {
        EntityPartition partition = EntityPartition.open(
                PartitionKey.forEntity(subject, POLICY, SNAPSHOT));
        for (int i = 0; i < chunks; i++) {
            partition = partition.admit(
                    ChunkCandidate.of(subject + "-c" + i, DiscoveryChannel.DIRECT_IDENTIFIER, 0.9)
                            .inDocument("doc-" + subject),
                    MembershipState.PROCESSED, 1);
        }
        return partition.withPhase(PartitionPhase.CLOSED);
    }

    private static EntityPartition with(String subject, MembershipState... states) {
        EntityPartition partition = EntityPartition.open(
                PartitionKey.forEntity(subject, POLICY, SNAPSHOT));
        for (int i = 0; i < states.length; i++) {
            partition = partition.admit(
                    ChunkCandidate.of(subject + "-c" + i, DiscoveryChannel.SEMANTIC, 0.7)
                            .inDocument("doc-" + subject),
                    states[i], 1);
        }
        return partition;
    }

    @Nested
    @DisplayName("Rolling several partitions into one answer")
    class RollUp {

        @Test
        @DisplayName("sums coverage over the scope and names what the scope was")
        void sumsCoverage() {
            PartitionCoverageReport report = PartitionCoverageReport.of("fact sheet 7",
                    List.of(complete("acme", 3), complete("globex", 1)));

            assertEquals("fact sheet 7", report.scope());
            assertEquals(2, report.partitions());
            assertEquals(4, report.covered());
            assertEquals(4, report.admitted());
            assertEquals(1.0, report.coverage());
            assertEquals(2, report.complete());
            assertEquals(0, report.open());
        }

        @Test
        @DisplayName("a partition that gave up holding unreadable material is complete WITH GAPS")
        void completeWithGapsIsCountedApart() {
            EntityPartition gappy = with("acme", MembershipState.PROCESSED,
                    MembershipState.INACCESSIBLE).withPhase(PartitionPhase.CLOSED);

            PartitionCoverageReport report = PartitionCoverageReport.of("acme", List.of(gappy));

            assertEquals(1, report.complete(), "it did finish");
            assertEquals(1, report.completeWithGaps(), "and it finished missing evidence");
            assertEquals(1, report.inaccessible());
            assertEquals(0.5, report.coverage(), "the unread chunk stays in the denominator");
        }

        @Test
        @DisplayName("outstanding work keeps a partition open however much it has read")
        void outstandingKeepsItOpen() {
            EntityPartition busy = with("acme", MembershipState.PROCESSED,
                    MembershipState.PROCESSED, MembershipState.DISCOVERED);

            PartitionCoverageReport report = PartitionCoverageReport.of("acme", List.of(busy));

            assertEquals(0, report.complete());
            assertEquals(1, report.open());
            assertEquals(1, report.outstanding());
            assertFalse(report.subjects().get(0).provisionallyComplete());
        }

        @Test
        @DisplayName("rejected proposals are stated, never netted out of coverage")
        void exclusionsAreStatedNotNetted() {
            EntityPartition picky = with("acme", MembershipState.PROCESSED,
                    MembershipState.EXCLUDED, MembershipState.EXCLUDED)
                    .withPhase(PartitionPhase.CLOSED);

            PartitionCoverageReport report = PartitionCoverageReport.of("acme", List.of(picky));

            assertEquals(2, report.excluded(), "the rejections are reported");
            assertEquals(1, report.admitted(), "and kept out of the denominator");
            assertEquals(1.0, report.coverage());
            assertTrue(report.describe().contains("1/1"));
        }

        @Test
        @DisplayName("least covered first, so the weakest claim needs no scrolling")
        void leastCoveredSortsFirst() {
            PartitionCoverageReport report = PartitionCoverageReport.of("all", List.of(
                    complete("done", 2),
                    with("barely", MembershipState.DISCOVERED, MembershipState.DISCOVERED),
                    with("half", MembershipState.PROCESSED, MembershipState.DISCOVERED)));

            List<String> order = report.subjects().stream()
                    .map(PartitionCoverageReport.SubjectCoverage::subject).toList();
            assertEquals(List.of("barely", "half", "done"), order);
        }

        @Test
        @DisplayName("an empty scope is an honest answer, not an error")
        void emptyScopeIsAnAnswer() {
            PartitionCoverageReport report = PartitionCoverageReport.empty("fact sheet 9");

            assertEquals(0, report.partitions());
            assertEquals(0.0, report.coverage());
            assertTrue(report.describe().contains("no partitions recorded"));
        }

        @Test
        @DisplayName("nulls in the collection are skipped rather than counted as uncovered subjects")
        void nullsAreSkipped() {
            PartitionCoverageReport report = PartitionCoverageReport.of("all",
                    Arrays.asList(complete("acme", 1), null));

            assertEquals(1, report.partitions());
        }
    }

    @Nested
    @DisplayName("One subject's claim across a serialisation boundary")
    class SubjectDetail {

        @Test
        @DisplayName("carries the gap chunk ids whole, because a count cannot be acted on")
        void carriesGapIdsWhole() {
            EntityPartition partition = with("acme", MembershipState.PROCESSED,
                    MembershipState.DEFERRED, MembershipState.INACCESSIBLE,
                    MembershipState.INVALIDATED);

            PartitionCoverageReport.SubjectCoverage claim =
                    PartitionCoverageReport.SubjectCoverage.of(partition);

            assertEquals(List.of("acme-c1"), claim.deferred());
            assertEquals(List.of("acme-c2"), claim.inaccessible());
            assertEquals(List.of("acme-c3"), claim.invalidated());
            assertTrue(claim.outstanding().containsAll(List.of("acme-c1", "acme-c3")),
                    "deferred and invalidated are both still owed");
        }

        @Test
        @DisplayName("policy and snapshot travel with the claim; they are what makes it comparable")
        void policyTravelsWithTheClaim() {
            PartitionCoverageReport.SubjectCoverage claim =
                    PartitionCoverageReport.SubjectCoverage.of(complete("acme", 1));

            assertEquals(POLICY, claim.policyVersion());
            assertEquals(SNAPSHOT, claim.snapshotId());
            assertEquals("acme", claim.subject());
            assertEquals(PartitionPhase.CLOSED.name(), claim.phase());
            assertNotNull(claim.summary());
        }

        @Test
        @DisplayName("enums become names, so a JSON reader sees the same states the model does")
        void enumsBecomeNames() {
            PartitionCoverageReport.SubjectCoverage claim =
                    PartitionCoverageReport.SubjectCoverage.of(complete("acme", 2));

            assertEquals(2, claim.byState().get(MembershipState.PROCESSED.name()));
            assertEquals(2, claim.byChannel().get(DiscoveryChannel.DIRECT_IDENTIFIER.name()));
        }

        @Test
        @DisplayName("the lists it hands out cannot be edited by whoever received them")
        void listsAreImmutable() {
            PartitionCoverageReport.SubjectCoverage claim = PartitionCoverageReport.SubjectCoverage
                    .of(with("acme", MembershipState.DEFERRED));

            assertThrows(UnsupportedOperationException.class, () -> claim.deferred().add("x"));
        }
    }
}
