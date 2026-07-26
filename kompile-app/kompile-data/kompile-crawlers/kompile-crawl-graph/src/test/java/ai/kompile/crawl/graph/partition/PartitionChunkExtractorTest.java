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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things a chunk can be, and the different claim each one makes.
 *
 * <p>Text this run does not have must fail loudly, because recording it as read-and-empty is a
 * statement about the corpus made on the strength of a lookup miss. Text with nothing in it must
 * succeed quietly, because that statement is true. Everything else goes to the crawl's own
 * extraction and comes back unwritten.</p>
 */
@DisplayName("What one chunk of a partition is worth")
class PartitionChunkExtractorTest {

    private final AtomicInteger extractions = new AtomicInteger();
    private final List<Document> extracted = new ArrayList<>();

    private Document chunk(String id, String text) {
        return new Document(id, text, Map.of());
    }

    private EntityPartition acme() {
        return EntityPartition.open(PartitionKey.forEntity("acme-corp", "policy-v1", "77"));
    }

    private PartitionMember member(String chunkId) {
        return PartitionMember.admit(
                ChunkCandidate.of(chunkId, DiscoveryChannel.SEED, 1.0).inDocument("doc-3"),
                MembershipState.DISCOVERED, 1);
    }

    /** Stands in for the crawl's real extraction, recording what it was asked to read. */
    private Graph record(Document doc, Graph produced) {
        extractions.incrementAndGet();
        extracted.add(doc);
        return produced;
    }

    private PartitionChunkExtractor extractorOver(List<Document> chunks, Graph produced) {
        return PartitionChunkExtractor.over(PartitionChunkTexts.index(chunks),
                doc -> record(doc, produced));
    }

    @Nested
    @DisplayName("When the run holds the text")
    class HoldingTheText {

        @Test
        void theChunkGoesToTheCrawlsOwnExtractionAndItsAnswerComesBackUntouched() {
            Document doc = chunk("chunk-7", "Acme Corp reported revenue of $4.1M.");
            Graph produced = new Graph();

            Graph result = extractorOver(List.of(doc), produced).extract(member("chunk-7"), acme());

            assertSame(produced, result, "the staged transaction must merge exactly what was found");
            assertEquals(1, extractions.get(), "a chunk is read once per member, not once per lookup");
            assertSame(doc, extracted.get(0));
        }

        @Test
        void aMemberAdmittedUnderAProvenanceAliasReadsTheSameChunk() {
            Document doc = new Document("doc-1", "Acme Corp restated Q3.",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "chunk-alias-9"));
            Graph produced = new Graph();

            Graph result = extractorOver(List.of(doc), produced)
                    .extract(member("chunk-alias-9"), acme());

            assertSame(produced, result);
            assertSame(doc, extracted.get(0),
                    "the graph cited the alias, so the alias has to reach the same text");
        }

        @Test
        void anExtractionThatFoundNothingIsPassedThroughAsNothing() {
            Document doc = chunk("chunk-7", "Page 4 of 12.");

            Graph result = extractorOver(List.of(doc), null).extract(member("chunk-7"), acme());

            assertNull(result, "the extraction read it and learned nothing; that is its answer to keep");
            assertEquals(1, extractions.get());
        }

        @Test
        void anExtractionThatFailsIsNotSwallowedIntoAnEmptyClaim() {
            Document doc = chunk("chunk-7", "Acme Corp reported revenue of $4.1M.");
            PartitionChunkExtractor extractor = PartitionChunkExtractor.over(
                    PartitionChunkTexts.index(List.of(doc)),
                    d -> {
                        throw new IllegalStateException("the model went away");
                    });

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> extractor.extract(member("chunk-7"), acme()));

            assertEquals("the model went away", thrown.getMessage(),
                    "the lifecycle defers on a throw; converting it to null would record a lie");
        }
    }

    @Nested
    @DisplayName("When the chunk teaches nothing")
    class TeachingNothing {

        @Test
        void anEmptyChunkIsReadAndLearnsNothing() {
            Graph result = extractorOver(List.of(chunk("chunk-7", "")), new Graph())
                    .extract(member("chunk-7"), acme());

            assertNull(result);
            assertEquals(0, extractions.get(), "there was nothing to spend an extraction on");
        }

        @Test
        void aWhitespaceOnlyChunkIsTheSameCase() {
            Graph result = extractorOver(List.of(chunk("chunk-7", "  \n\t ")), new Graph())
                    .extract(member("chunk-7"), acme());

            assertNull(result);
            assertEquals(0, extractions.get());
        }

        @Test
        void aChunkThatIsNotProseAtAllIsReadAndLearnsNothing() {
            Document image = new Document("chunk-image",
                    new Media(MimeTypeUtils.IMAGE_PNG, URI.create("file:///chart.png")), Map.of());

            Graph result = extractorOver(List.of(image), new Graph())
                    .extract(member("chunk-image"), acme());

            assertNull(result, "an image chunk is readable and holds no text for this extractor");
            assertEquals(0, extractions.get());
        }
    }

    @Nested
    @DisplayName("When the run does not have the text")
    class MissingTheText {

        @Test
        void itThrowsRatherThanRecordTheChunkAsReadAndEmpty() {
            PartitionChunkExtractor extractor =
                    extractorOver(List.of(chunk("chunk-7", "held")), new Graph());

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> extractor.extract(member("chunk-99"), acme()));

            assertTrue(thrown.getMessage().contains("chunk-99"),
                    "the deferral reason has to name the chunk: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("acme-corp"),
                    "and the partition that admitted it: " + thrown.getMessage());
            assertEquals(0, extractions.get());
        }

        @Test
        void theReasonStillNamesTheChunkWhenTheCallerPassedNoPartition() {
            PartitionChunkExtractor extractor =
                    extractorOver(List.of(chunk("chunk-7", "held")), new Graph());

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> extractor.extract(member("chunk-99"), null));

            assertTrue(thrown.getMessage().contains("chunk-99"), thrown.getMessage());
        }

        @Test
        void aRunHoldingNoChunksAtAllDefersRatherThanBreaks() {
            PartitionChunkExtractor extractor =
                    PartitionChunkExtractor.over(null, doc -> new Graph());

            assertEquals(0, extractor.size());
            assertThrows(IllegalStateException.class,
                    () -> extractor.extract(member("chunk-7"), acme()));
        }

        @Test
        void aMissingMemberIsAProgrammingErrorNotADeferral() {
            PartitionChunkExtractor extractor =
                    extractorOver(List.of(chunk("chunk-7", "held")), new Graph());

            assertThrows(NullPointerException.class, () -> extractor.extract(null, acme()));
        }
    }

    @Nested
    @DisplayName("What it can answer for")
    class Coverage {

        @Test
        void sizeIsTheNumberOfIdsTheRunCanAnswerFor() {
            Document aliased = new Document("doc-1", "text",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "alias-1"));

            PartitionChunkExtractor extractor =
                    extractorOver(List.of(aliased, chunk("doc-2", "more")), new Graph());

            assertEquals(3, extractor.size(), "two document ids and one alias");
        }

        @Test
        void aLaterChangeToTheCrawlsIndexCannotChangeWhatItAlreadyAnswers() {
            Map<String, Document> live = new HashMap<>();
            live.put("chunk-7", chunk("chunk-7", "held"));
            PartitionChunkExtractor extractor =
                    PartitionChunkExtractor.over(live, doc -> new Graph());

            live.put("chunk-99", chunk("chunk-99", "arrived late"));

            assertEquals(1, extractor.size());
            assertThrows(IllegalStateException.class,
                    () -> extractor.extract(member("chunk-99"), acme()));
        }

        @Test
        void anExtractorWithoutAnExtractionIsRefusedWhereItIsBuilt() {
            assertThrows(NullPointerException.class,
                    () -> PartitionChunkExtractor.over(Map.of(), null));
        }
    }
}
