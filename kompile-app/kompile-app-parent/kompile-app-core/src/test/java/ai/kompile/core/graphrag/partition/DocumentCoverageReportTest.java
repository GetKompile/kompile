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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The document axis of coverage.
 *
 * <p>The one thing that must not slip here is double counting. The same chunk is deliberately a
 * member of every partition it is evidence for — that overlap is the point of partitioning — so a
 * document read once by three subjects has to report as its own size, not three times it.</p>
 */
@DisplayName("DocumentCoverageReport")
class DocumentCoverageReportTest {

    private static final String POLICY = "discovery-v1";
    private static final String SNAPSHOT = "snap-1";
    private static final String DOC = "reports/2024-q3.pdf";

    /** A partition about {@code subject} holding the named chunks of {@link #DOC} in a state. */
    private static EntityPartition holding(String subject, MembershipState state, String... chunks) {
        return holding(subject, DOC, DiscoveryChannel.SEMANTIC, state, chunks);
    }

    private static EntityPartition holding(String subject, String documentId,
                                           DiscoveryChannel channel, MembershipState state,
                                           String... chunks) {
        EntityPartition partition = EntityPartition.open(
                PartitionKey.forEntity(subject, POLICY, SNAPSHOT));
        for (String chunk : chunks) {
            partition = partition.admit(
                    ChunkCandidate.of(chunk, channel, 0.8).inDocument(documentId), state, 1);
        }
        return partition;
    }

    @Nested
    @DisplayName("Counting a document that several subjects share")
    class SharedDocument {

        @Test
        @DisplayName("a chunk two partitions both hold is one chunk, not two")
        void overlapIsNotDoubleCounted() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1", "c2"),
                    holding("globex", MembershipState.PROCESSED, "c2", "c3")));

            assertEquals(3, report.chunks(), "c2 is one chunk seen twice");
            assertEquals(3, report.read());
            assertEquals(2, report.partitions());
            assertEquals(1.0, report.coverage());
        }

        @Test
        @DisplayName("the subjects a chunk was read for are named on it")
        void namesWhoHoldsEachChunk() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1"),
                    holding("globex", MembershipState.PROCESSED, "c1")));

            DocumentCoverageReport.ChunkCoverage shared = report.chunkDetail().get(0);
            assertEquals("c1", shared.chunkId());
            assertEquals(List.of("acme", "globex"), shared.heldBy());
            assertEquals(List.of("acme", "globex"), report.subjects());
        }

        @Test
        @DisplayName("where partitions disagree about a chunk, the model's own merge decides")
        void disagreementResolvesByMerge() {
            // One subject processed it; for another its source has since moved. Invalidation wins:
            // the chunk must be re-read, so reporting it as read would be a lie about the document.
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1"),
                    holding("globex", MembershipState.INVALIDATED, "c1")));

            assertEquals(1, report.chunks());
            assertEquals(0, report.read());
            assertEquals(1, report.invalidated());
            assertEquals(1, report.outstanding());
            assertEquals(MembershipState.INVALIDATED.name(), report.chunkDetail().get(0).state());
        }

        @Test
        @DisplayName("a revoked scope does not undo an extraction that already committed")
        void processedBeatsInaccessible() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1"),
                    holding("globex", MembershipState.INACCESSIBLE, "c1")));

            assertEquals(1, report.read());
            assertEquals(0, report.inaccessible());
        }
    }

    @Nested
    @DisplayName("Scoping to the document that was asked about")
    class Scoping {

        @Test
        @DisplayName("chunks of other documents in the same partition are ignored")
        void otherDocumentsAreIgnored() {
            EntityPartition mixed = holding("acme", MembershipState.PROCESSED, "c1")
                    .admit(ChunkCandidate.of("other-c1", DiscoveryChannel.SEMANTIC, 0.8)
                            .inDocument("reports/2023-q1.pdf"), MembershipState.PROCESSED, 1);

            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(mixed));

            assertEquals(1, report.chunks());
            assertEquals(List.of("c1"), report.chunkDetail().stream()
                    .map(DocumentCoverageReport.ChunkCoverage::chunkId).toList());
        }

        @Test
        @DisplayName("a partition that never saw the document is about something else, not a zero")
        void untouchedPartitionsDoNotCount() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1"),
                    holding("globex", "reports/2023-q1.pdf", DiscoveryChannel.SEMANTIC,
                            MembershipState.PROCESSED, "x1")));

            assertEquals(1, report.partitions());
            assertEquals(List.of("acme"), report.subjects());
        }

        @Test
        @DisplayName("no partition having read it is an answer, and is not the same as fully read")
        void unreadDocumentIsAnAnswer() {
            DocumentCoverageReport report = DocumentCoverageReport.empty(DOC);

            assertEquals(0, report.partitions());
            assertEquals(0, report.chunks());
            assertEquals(0.0, report.coverage(), "not 1.0 — nothing was read, not everything");
            assertTrue(report.describe().contains("no partition has read"));
        }

        @Test
        @DisplayName("nulls in the collection are skipped")
        void nullsAreSkipped() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC,
                    Arrays.asList(holding("acme", MembershipState.PROCESSED, "c1"), null));

            assertEquals(1, report.partitions());
        }
    }

    @Nested
    @DisplayName("What the reader sees first")
    class Presentation {

        @Test
        @DisplayName("unread chunks sort ahead of read ones")
        void unreadSortsFirst() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1", "c3"),
                    holding("globex", MembershipState.DEFERRED, "c2")));

            assertEquals("c2", report.chunkDetail().get(0).chunkId());
            assertFalse(report.chunkDetail().get(0).read());
            assertTrue(report.chunkDetail().get(1).read());
        }

        @Test
        @DisplayName("rejected chunks stay visible but out of the coverage denominator")
        void exclusionsAreStatedNotNetted() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", MembershipState.PROCESSED, "c1"),
                    holding("globex", MembershipState.EXCLUDED, "c2")));

            assertEquals(2, report.chunks());
            assertEquals(1, report.excluded());
            assertEquals(1, report.admitted());
            assertEquals(1.0, report.coverage());
            assertEquals(1, report.byState().get(MembershipState.EXCLUDED.name()));
        }

        @Test
        @DisplayName("the strongest channel that found a chunk anywhere is the one reported")
        void strongestChannelWins() {
            DocumentCoverageReport report = DocumentCoverageReport.of(DOC, List.of(
                    holding("acme", DOC, DiscoveryChannel.SEMANTIC,
                            MembershipState.PROCESSED, "c1"),
                    holding("globex", DOC, DiscoveryChannel.DIRECT_IDENTIFIER,
                            MembershipState.PROCESSED, "c1")));

            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER.name(),
                    report.chunkDetail().get(0).channel());
        }
    }
}
