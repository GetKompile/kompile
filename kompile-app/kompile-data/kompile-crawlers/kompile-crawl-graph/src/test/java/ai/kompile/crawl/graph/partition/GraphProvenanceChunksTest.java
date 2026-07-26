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

package ai.kompile.crawl.graph.partition;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link from a graph fact back to the text it came from.
 *
 * <p>Without it a neighbourhood walk can name entities it cannot cite, so these tests are mostly
 * about the ways that link is spelled in this tree rather than about parsing as such.</p>
 */
@DisplayName("Graph provenance → chunk ids")
class GraphProvenanceChunksTest {

    private static GraphEdge edgeWith(String metadataJson) {
        return GraphEdge.builder()
                .edgeId("e1")
                .sourceNode(GraphNode.builder().nodeId("entity_a").build())
                .targetNode(GraphNode.builder().nodeId("entity_b").build())
                .edgeType(EdgeType.USER_DEFINED)
                .metadataJson(metadataJson)
                .build();
    }

    private static GraphNode nodeWith(String metadataJson) {
        return GraphNode.builder()
                .nodeId("entity_a")
                .externalId("a")
                .nodeType(NodeLevel.ENTITY)
                .metadataJson(metadataJson)
                .build();
    }

    @Nested
    @DisplayName("Chunk ids")
    class ChunkIds {

        @Test
        void theReservedUnderscoreSpellingIsRead() {
            assertEquals(List.of("chunk-7"),
                    GraphProvenanceChunks.chunkIdsOf(edgeWith("{\"_sourceChunkId\":\"chunk-7\"}")));
        }

        @Test
        void theBareSpellingIsReadToo() {
            // GraphProvenanceKeys.describe strips the underscore, and the older extraction
            // projection wrote the bare form directly. Reading only one halves the citable graph.
            assertEquals(List.of("chunk-7"),
                    GraphProvenanceChunks.chunkIdsOf(edgeWith("{\"sourceChunkId\":\"chunk-7\"}")));
        }

        @Test
        void bothSpellingsTogetherYieldBothChunksWithTheReservedOneFirst() {
            List<String> ids = GraphProvenanceChunks.chunkIdsOf(
                    edgeWith("{\"sourceChunkId\":\"bare\",\"_sourceChunkId\":\"reserved\"}"));
            assertEquals(List.of("reserved", "bare"), ids);
        }

        @Test
        void thesameIdUnderBothSpellingsIsNotCountedTwice() {
            assertEquals(List.of("chunk-7"), GraphProvenanceChunks.chunkIdsOf(
                    edgeWith("{\"sourceChunkId\":\"chunk-7\",\"_sourceChunkId\":\"chunk-7\"}")));
        }

        @Test
        void aFactSupportedBySeveralChunksMayRecordThemAsAList() {
            assertEquals(List.of("c1", "c2", "c3"), GraphProvenanceChunks.chunkIdsOf(
                    edgeWith("{\"_sourceChunkId\":[\"c1\",\"c2\",\"c3\"]}")));
        }

        @Test
        void orAsOneDelimitedStringWhichIsWhatFlatColumnsProduce() {
            assertEquals(List.of("c1", "c2", "c3", "c4"), GraphProvenanceChunks.chunkIds(
                    Map.of(GraphProvenanceKeys.SOURCE_CHUNK_ID, "c1,c2;c3|c4")));
        }

        @Test
        void surroundingWhitespaceIsNotPartOfAChunkId() {
            assertEquals(List.of("c1", "c2"),
                    GraphProvenanceChunks.chunkIds(Map.of("chunkIds", " c1 , c2 ")));
        }

        @Test
        void anArrayValueIsReadTheSameWayAList() {
            Map<String, Object> metadata = Map.of("_sourceChunkId", new Object[]{"c1", "c2"});
            assertEquals(List.of("c1", "c2"), GraphProvenanceChunks.chunkIds(metadata));
        }

        @Test
        void unparseableMetadataIsMissingProvenanceNotAFailedWalk() {
            assertTrue(GraphProvenanceChunks.chunkIdsOf(edgeWith("{not json")).isEmpty());
            assertEquals(Map.of(), GraphProvenanceChunks.metadataOf(edgeWith("{not json")));
        }

        @Test
        void anEdgeWithNoMetadataHasNoChunks() {
            assertTrue(GraphProvenanceChunks.chunkIdsOf(edgeWith(null)).isEmpty());
            assertTrue(GraphProvenanceChunks.chunkIdsOf(edgeWith("   ")).isEmpty());
            assertTrue(GraphProvenanceChunks.chunkIdsOf((GraphEdge) null).isEmpty());
        }

        @Test
        void nodeProvenanceIsReadThroughTheParsedMetadataTheDomainAlreadyExposes() {
            assertEquals(List.of("chunk-9"),
                    GraphProvenanceChunks.chunkIdsOf(nodeWith("{\"_sourceChunkId\":\"chunk-9\"}")));
            assertTrue(GraphProvenanceChunks.chunkIdsOf((GraphNode) null).isEmpty());
        }
    }

    @Nested
    @DisplayName("Document ids")
    class DocumentIds {

        @Test
        void theReservedDocumentKeyIsPreferred() {
            assertEquals("doc-1", GraphProvenanceChunks.documentIdOf(
                    edgeWith("{\"_sourceDocumentId\":\"doc-1\",\"documentId\":\"doc-2\"}")));
        }

        @Test
        void theBareSpellingsAreAcceptedWhenTheReservedOneIsAbsent() {
            assertEquals("doc-2",
                    GraphProvenanceChunks.documentIdOf(edgeWith("{\"documentId\":\"doc-2\"}")));
            assertEquals("doc-3",
                    GraphProvenanceChunks.documentIdOf(edgeWith("{\"source_id\":\"doc-3\"}")));
        }

        @Test
        void theCoarseOriginLabelIsNeverMistakenForADocument() {
            // _source is "crawl"/"upload". Reading it as a document id would make every
            // crawl-sourced chunk look like it came from one document, and invalidation of that
            // document would then wipe the partition.
            GraphEdge edge = edgeWith("{\"_source\":\"crawl\",\"_sourceChunkId\":\"c1\"}");
            assertNull(GraphProvenanceChunks.documentIdOf(edge));
            assertEquals(List.of("c1"), GraphProvenanceChunks.chunkIdsOf(edge));
        }

        @Test
        void anAbsentDocumentIsNullRatherThanAGuess() {
            assertNull(GraphProvenanceChunks.documentIdOf(edgeWith("{\"_sourceChunkId\":\"c1\"}")));
            assertNull(GraphProvenanceChunks.documentIdOf(edgeWith(null)));
            assertNull(GraphProvenanceChunks.documentId(null));
        }

        @Test
        void aBlankValueIsNotADocumentId() {
            assertNull(GraphProvenanceChunks.documentIdOf(
                    edgeWith("{\"_sourceDocumentId\":\"   \"}")));
        }

        @Test
        void nodesCarryTheirDocumentTheSameWay() {
            assertEquals("doc-4", GraphProvenanceChunks.documentIdOf(
                    nodeWith("{\"_sourceDocumentId\":\"doc-4\"}")));
            assertNull(GraphProvenanceChunks.documentIdOf((GraphNode) null));
        }
    }
}
