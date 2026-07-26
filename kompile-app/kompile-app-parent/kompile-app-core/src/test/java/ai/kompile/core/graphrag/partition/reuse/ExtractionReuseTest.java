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

package ai.kompile.core.graphrag.partition.reuse;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a chunk once and using it everywhere it is evidence.
 *
 * <p>The claims that matter: identical work is done once, non-identical work is never confused
 * with it, a reuse hands back the whole answer rather than permission to skip it, and a failure is
 * never remembered as one.</p>
 */
class ExtractionReuseTest {

    private static final EntityPartition PARTITION =
            EntityPartition.open(PartitionKey.forEntity("acme", "discovery-v1", null));

    private static PartitionMember member(String chunkId, String version) {
        ChunkCandidate candidate = ChunkCandidate.of(chunkId, DiscoveryChannel.SEMANTIC, 0.7);
        return PartitionMember.admit(
                version == null ? candidate : candidate.withVersion(version),
                MembershipState.DISCOVERED, 1);
    }

    private static Graph graphOf(String entityTitle) {
        Entity entity = new Entity();
        entity.setId(entityTitle);
        entity.setTitle(entityTitle);
        return Graph.builder().entities(List.of(entity)).relationships(List.of()).build();
    }

    /** Records every chunk it is asked to read, so a saved call is visible. */
    private static final class Reader implements ExtractionReuse.ChunkExtractor {

        private final List<String> read = new ArrayList<>();
        private final Graph answer;

        private Reader(Graph answer) {
            this.answer = answer;
        }

        @Override
        public Graph extract(PartitionMember member, EntityPartition partition) {
            read.add(member == null ? "<none>" : member.chunkId());
            return answer;
        }
    }

    @Nested
    @DisplayName("The same text")
    class SameText {

        @Test
        @DisplayName("is read once, however many partitions it is evidence for")
        void isReadOnce() {
            // The bridge chunk: one text, two partitions that both need what it says.
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            ExtractionReuse.Outcome first = reuse.extract(member("c1", null),
                    "Acme employs Alice.", PARTITION, reader);
            ExtractionReuse.Outcome second = reuse.extract(member("c1", null),
                    "Acme employs Alice.", PARTITION, reader);

            assertEquals(List.of("c1"), reader.read);
            assertFalse(first.reused());
            assertTrue(second.reused());
        }

        @Test
        @DisplayName("hands back the answer, not permission to skip it")
        void handsBackTheAnswer() {
            // A partition that staged nothing while claiming the chunk was processed would be
            // claiming coverage its graph does not have.
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Graph answer = graphOf("Acme");
            Reader reader = new Reader(answer);

            reuse.extract(member("c1", null), "Acme employs Alice.", PARTITION, reader);
            ExtractionReuse.Outcome second = reuse.extract(member("c2", null),
                    "Acme employs Alice.", PARTITION, reader);

            assertSame(answer, second.produced());
        }

        @Test
        @DisplayName("under a different chunk id is still the same question")
        void underADifferentIdIsStillTheSameQuestion() {
            // Overlapping windows and repeated boilerplate: different names, identical words.
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            reuse.extract(member("c1", "v1"), "Acme employs Alice.", PARTITION, reader);
            reuse.extract(member("c2", "v9"), "Acme employs Alice.", PARTITION, reader);

            assertEquals(List.of("c1"), reader.read);
        }

        @Test
        @DisplayName("says which chunk was actually read")
        void saysWhichChunkWasRead() {
            // So nobody later mistakes a reused answer for a second independent reading.
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            reuse.extract(member("c1", null), "Acme employs Alice.", PARTITION, reader);
            ExtractionReuse.Outcome second = reuse.extract(member("c2", null),
                    "Acme employs Alice.", PARTITION, reader);

            assertTrue(second.note().contains("c1"), second.note());
        }

        @Test
        @DisplayName("that yielded nothing is not read again to find out again")
        void nothingExtractedIsRemembered() {
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(null);

            reuse.extract(member("c1", null), "A page of headers.", PARTITION, reader);
            ExtractionReuse.Outcome second = reuse.extract(member("c2", null),
                    "A page of headers.", PARTITION, reader);

            assertEquals(List.of("c1"), reader.read);
            assertTrue(second.reused());
            assertNull(second.produced());
        }
    }

    @Nested
    @DisplayName("Different work")
    class DifferentWork {

        @Test
        @DisplayName("different text is read")
        void differentTextIsRead() {
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            reuse.extract(member("c1", null), "Acme employs Alice.", PARTITION, reader);
            reuse.extract(member("c2", null), "Acme employs Bob.", PARTITION, reader);

            assertEquals(List.of("c1", "c2"), reader.read);
        }

        @Test
        @DisplayName("another extractor's questions are its own")
        void profilesDoNotShareAnswers() {
            // One ledger, two extractors. Serving the first one's answers for the second's
            // questions is how a reuse layer starts being quietly wrong.
            ExtractionLedger shared = ExtractionLedger.inMemory();
            Reader reader = new Reader(graphOf("Acme"));

            ExtractionReuse.of(shared, "lfm2.5/entities-v3")
                    .extract(member("c1", null), "Acme employs Alice.", PARTITION, reader);
            ExtractionReuse.of(shared, "lfm2.5/relationships-v1")
                    .extract(member("c1", null), "Acme employs Alice.", PARTITION, reader);

            assertEquals(List.of("c1", "c1"), reader.read);
        }

        @Test
        @DisplayName("a chunk whose source moved is read again")
        void aChangedSourceIsReadAgain() {
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            reuse.extract(member("c1", "v1"), null, PARTITION, reader);
            reuse.extract(member("c1", "v2"), null, PARTITION, reader);

            assertEquals(List.of("c1", "c1"), reader.read);
        }
    }

    @Nested
    @DisplayName("When the work cannot be identified")
    class Unidentifiable {

        @Test
        @DisplayName("it is done, every time it is asked for")
        void unidentifiableWorkIsAlwaysDone() {
            // No text and no source version: nothing can be shown identical to it, and guessing
            // on the chunk id alone would serve the old answer after the source changed.
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            ExtractionReuse.Outcome first =
                    reuse.extract(member("c1", null), null, PARTITION, reader);
            ExtractionReuse.Outcome second =
                    reuse.extract(member("c1", null), null, PARTITION, reader);

            assertEquals(List.of("c1", "c1"), reader.read);
            assertFalse(first.reused());
            assertFalse(second.reused());
            assertEquals(0, reuse.stats().total());
        }

        @Test
        @DisplayName("without text, the same chunk is still recognised")
        void withoutTextTheSameChunkIsRecognised() {
            // The weaker basis still saves the bridge chunk, which is the common case.
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            reuse.extract(member("c1", "v1"), null, PARTITION, reader);
            reuse.extract(member("c1", "v1"), null, PARTITION, reader);

            assertEquals(List.of("c1"), reader.read);
        }
    }

    @Nested
    @DisplayName("When the extractor fails")
    class Failure {

        @Test
        @DisplayName("the failure is the extractor's answer for now, not the text's")
        void failuresAreNotRemembered() {
            ExtractionReuse reuse = ExtractionReuse.forRun();
            List<String> attempts = new ArrayList<>();
            ExtractionReuse.ChunkExtractor flaky = (member, partition) -> {
                attempts.add(member.chunkId());
                if (attempts.size() == 1) {
                    throw new IllegalStateException("model unavailable");
                }
                return graphOf("Acme");
            };

            assertThrows(IllegalStateException.class, () -> reuse.extract(member("c1", null),
                    "Acme employs Alice.", PARTITION, flaky));
            ExtractionReuse.Outcome retry = reuse.extract(member("c1", null),
                    "Acme employs Alice.", PARTITION, flaky);

            assertEquals(2, attempts.size());
            assertFalse(retry.reused());
            assertEquals("Acme", retry.produced().getEntities().get(0).getTitle());
        }

        @Test
        @DisplayName("a run without an extractor is a caller's mistake, not a reuse")
        void anAbsentExtractorIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> ExtractionReuse.forRun()
                    .extract(member("c1", null), "Acme employs Alice.", PARTITION, null));
        }
    }

    @Nested
    @DisplayName("What a run can report")
    class Reporting {

        @Test
        @DisplayName("the totals say how much of the reading was already done")
        void theTotalsSayWhatWasSaved() {
            ExtractionReuse reuse = ExtractionReuse.forRun();
            Reader reader = new Reader(graphOf("Acme"));

            reuse.extract(member("c1", null), "Acme employs Alice.", PARTITION, reader);
            reuse.extract(member("c2", null), "Acme employs Alice.", PARTITION, reader);
            reuse.extract(member("c3", null), "Acme employs Bob.", PARTITION, reader);

            assertEquals(new ExtractionLedger.Stats(2, 1), reuse.stats());
        }

        @Test
        @DisplayName("a ledger a caller brought is the one used, and stays theirs")
        void aCallersLedgerIsUsed() {
            ExtractionLedger mine = ExtractionLedger.inMemory();
            ExtractionReuse reuse = ExtractionReuse.of(mine, "lfm2.5/entities-v3");

            reuse.extract(member("c1", null), "Acme employs Alice.", PARTITION,
                    new Reader(graphOf("Acme")));

            assertSame(mine, reuse.ledger());
            assertEquals(1, mine.stats().firstRuns());
            assertEquals("lfm2.5/entities-v3", reuse.profile());
        }

        @Test
        @DisplayName("a run that names no extractor still keeps its own answers apart")
        void anUnnamedRunStillHasAProfile() {
            assertEquals(ExtractionReuse.DEFAULT_PROFILE, ExtractionReuse.forRun().profile());
            assertEquals(ExtractionReuse.DEFAULT_PROFILE, ExtractionReuse.of(null, "  ").profile());
        }
    }
}
