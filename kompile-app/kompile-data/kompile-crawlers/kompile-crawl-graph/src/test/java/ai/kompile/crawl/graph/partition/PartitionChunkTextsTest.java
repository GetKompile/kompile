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

import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a partition can find the text behind an id it was handed.
 *
 * <p>The failure this guards against is quiet: a member is admitted, the text lookup misses, and
 * the chunk is deferred as unreadable while the crawl is holding it in memory. Every test here is
 * really asking "would that happen?" for one of the ways an id can reach a partition.</p>
 */
@DisplayName("Finding the text behind an id a partition cites")
class PartitionChunkTextsTest {

    private Document chunk(String id, String text) {
        return new Document(id, text, Map.of());
    }

    private Document chunk(String id, String text, Map<String, Object> metadata) {
        return new Document(id, text, metadata);
    }

    /** A chunk that is an image, not prose — {@code getText()} is genuinely null. */
    private Document mediaChunk(String id) {
        return new Document(id, new Media(MimeTypeUtils.IMAGE_PNG, URI.create("file:///chart.png")),
                Map.of());
    }

    @Nested
    @DisplayName("Keys a partition might cite a chunk under")
    class Keys {

        @Test
        void aChunkIsFoundUnderTheDocumentIdTheIndexKnows() {
            Document doc = chunk("chunk-7", "Acme Corp reported revenue of $4.1M.");

            Map<String, Document> index = PartitionChunkTexts.index(List.of(doc));

            assertEquals(1, index.size());
            assertSame(doc, index.get("chunk-7"));
        }

        /**
         * Iterates the real key list rather than a copy of it: a spelling added to provenance later
         * has to be indexed too, and a test that hardcoded seven strings would not notice.
         */
        @Test
        void aChunkIsFoundUnderEveryProvenanceSpellingOfItsId() {
            for (String key : GraphProvenanceChunks.CHUNK_KEYS) {
                Document doc = chunk("doc-1", "text", Map.of(key, "alias-under-" + key));

                Map<String, Document> index = PartitionChunkTexts.index(List.of(doc));

                assertSame(doc, index.get("alias-under-" + key),
                        "a partition citing the chunk under '" + key + "' finds no text");
            }
        }

        @Test
        void everyIdInAMultiValuedProvenanceEntryPointsAtTheChunk() {
            Document listed = chunk("doc-1", "text",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, List.of("c-1", "c-2")));
            Document delimited = chunk("doc-2", "text",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "c-3;c-4"));

            Map<String, Document> index = PartitionChunkTexts.index(List.of(listed, delimited));

            assertSame(listed, index.get("c-1"));
            assertSame(listed, index.get("c-2"));
            assertSame(delimited, index.get("c-3"));
            assertSame(delimited, index.get("c-4"));
        }

        @Test
        void aDocumentIdIsNeverDisplacedByAnotherChunksAlias() {
            Document real = chunk("shared-id", "the chunk that owns the id");
            Document aliasing = chunk("doc-2", "a chunk that merely cites it",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "shared-id"));

            Map<String, Document> index = PartitionChunkTexts.index(List.of(aliasing, real));

            assertSame(real, index.get("shared-id"),
                    "the alias won the key, so a partition citing it would extract the wrong text");
        }

        @Test
        void theEarlierChunkKeepsTheKeyWhenTwoAliasesWantIt() {
            Document first = chunk("doc-1", "first",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "contested"));
            Document second = chunk("doc-2", "second",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "contested"));

            Map<String, Document> index = PartitionChunkTexts.index(List.of(first, second));

            assertSame(first, index.get("contested"));
        }

        @Test
        void documentIdsComeBackInTheOrderTheCrawlProducedThem() {
            List<Document> docs = List.of(chunk("c-3", "three"), chunk("c-1", "one"),
                    chunk("c-2", "two"));

            List<String> keys = List.copyOf(PartitionChunkTexts.index(docs).keySet());

            assertEquals(List.of("c-3", "c-1", "c-2"), keys,
                    "batch ordering and reuse keys both read this map in order");
        }
    }

    @Nested
    @DisplayName("What the run can still answer for")
    class Coverage {

        @Test
        void aChunkWithNoTextIsIndexedLikeAnyOther() {
            Document blank = chunk("chunk-blank", "   ");

            Map<String, Document> index = PartitionChunkTexts.index(List.of(blank));

            assertSame(blank, index.get("chunk-blank"),
                    "a readable empty chunk is a different claim from a chunk this run never had");
        }

        @Test
        void aChunkThatIsNotProseAtAllIsIndexedToo() {
            Document image = mediaChunk("chunk-image");

            Map<String, Document> index = PartitionChunkTexts.index(List.of(image));

            assertSame(image, index.get("chunk-image"));
            assertNull(index.get("chunk-image").getText());
        }

        @Test
        void nothingToIndexIsAnEmptyAnswerNotAFailure() {
            assertTrue(PartitionChunkTexts.index(null).isEmpty());
            assertTrue(PartitionChunkTexts.index(List.of()).isEmpty());
        }

        @Test
        void aMissingDocumentInTheBatchDoesNotCostTheOthersTheirText() {
            Document present = chunk("chunk-7", "still here");

            Map<String, Document> index = PartitionChunkTexts.index(Arrays.asList(null, present, null));

            assertEquals(1, index.size());
            assertSame(present, index.get("chunk-7"));
        }

        @Test
        void indexingTheSameBatchTwiceAnswersTheSameWay() {
            List<Document> docs = List.of(
                    chunk("doc-1", "one", Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "alias-1")),
                    chunk("doc-2", "two"));

            assertEquals(PartitionChunkTexts.index(docs), PartitionChunkTexts.index(docs));
        }

        @Test
        void aChunkAnsweringToBothItsIdAndItsAliasIsOneChunkNotTwo() {
            Document doc = chunk("doc-1", "text",
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "alias-1"));

            Map<String, Document> index = PartitionChunkTexts.index(List.of(doc));

            assertEquals(2, index.size());
            assertSame(index.get("doc-1"), index.get("alias-1"));
        }
    }
}
