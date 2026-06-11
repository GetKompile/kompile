/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.enrichment.impl.clean;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.CrossIndexIdResolver;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkDeduplicationServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EnrichmentAuditService auditService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private IndexerService indexerService;

    @Mock
    private CrossIndexIdResolver crossIndexIdResolver;

    private ChunkDeduplicationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChunkDeduplicationService(
                nodeRepository,
                edgeRepository,
                knowledgeGraphService,
                auditService,
                objectMapper,
                vectorStore,
                indexerService,
                crossIndexIdResolver
        );
    }

    // ─── buildShingles ─────────────────────────────────────────────────────────

    @Test
    void buildShinglesNormal() {
        Set<String> shingles = service.buildShingles("abcdef", 3);
        assertEquals(Set.of("abc", "bcd", "cde", "def"), shingles);
    }

    @Test
    void buildShinglesShorterThanK() {
        Set<String> shingles = service.buildShingles("ab", 5);
        assertEquals(Set.of("ab"), shingles);
    }

    @Test
    void buildShinglesEmptyString() {
        Set<String> shingles = service.buildShingles("", 3);
        assertEquals(Set.of(""), shingles);
    }

    // ─── computeMinHash ────────────────────────────────────────────────────────

    @Test
    void computeMinHashDeterministic() {
        Set<String> shingles = Set.of("hello", "world", "foo");
        long[] a = new long[]{1L, 3L, 7L};
        long[] b = new long[]{2L, 5L, 11L};

        long[] sig1 = service.computeMinHash(shingles, a, b, 3);
        long[] sig2 = service.computeMinHash(shingles, a, b, 3);

        assertArrayEquals(sig1, sig2,
                "Same input shingles with same hash coefficients must produce identical MinHash signature");
    }

    // ─── estimateJaccard ───────────────────────────────────────────────────────

    @Test
    void estimateJaccardIdenticalSignatures() {
        long[] sig = new long[]{1L, 2L, 3L, 4L};
        double jaccard = service.estimateJaccard(sig, sig, 4);
        assertEquals(1.0, jaccard, 1e-9, "Identical signatures should produce Jaccard estimate of 1.0");
    }

    @Test
    void estimateJaccardDisjointSignatures() {
        // Craft two signatures with 0 matches
        long[] sig1 = new long[]{1L, 2L, 3L, 4L};
        long[] sig2 = new long[]{10L, 20L, 30L, 40L};
        double jaccard = service.estimateJaccard(sig1, sig2, 4);
        assertTrue(jaccard < 0.5, "Disjoint signatures should produce a low Jaccard estimate, got: " + jaccard);
    }

    // ─── deduplicateChunks ─────────────────────────────────────────────────────

    @Test
    void deduplicateChunksIdenticalContent() {
        Long factSheetId = 1L;
        String sharedText = "the quick brown fox jumps over the lazy dog and more text here";

        GraphNode n1 = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET)
                .contentPreview(sharedText)
                .title("chunk-1").confidence(0.5).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-1").build();

        GraphNode n2 = GraphNode.builder()
                .id(2L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET)
                .contentPreview(sharedText)
                .title("chunk-2").confidence(0.5).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-2").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(n1, n2));

        // Stub edges so mergeChunks doesn't NPE
        GraphNode src = GraphNode.builder().id(10L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.DOCUMENT).externalId("doc").title("doc").build();
        GraphEdge edge = GraphEdge.builder()
                .id(100L).edgeId(UUID.randomUUID().toString())
                .sourceNode(src).targetNode(n2)
                .edgeType(EdgeType.CONTAINS).weight(1.0)
                .description("contains").factSheetId(factSheetId).build();
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(n2.getId()))
                .thenReturn(List.of(edge));

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.5)  // low threshold to force merge on identical text
                .deduplicationShingleSize(3)
                .build();

        int deduped = service.deduplicateChunks(factSheetId, "job-1", config);

        assertEquals(1, deduped, "Identical content chunks should be deduplicated");
        verify(knowledgeGraphService, atLeastOnce()).deleteNode(anyString());
    }

    @Test
    void deduplicateChunksFewerThanTwo() {
        Long factSheetId = 2L;
        GraphNode n1 = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET).contentPreview("only one chunk")
                .title("chunk-1").externalId("e1").factSheetId(factSheetId).build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(n1));

        EnrichmentConfig config = EnrichmentConfig.builder().build();
        int deduped = service.deduplicateChunks(factSheetId, "job-2", config);

        assertEquals(0, deduped, "Single snippet — no deduplication should occur");
        verifyNoInteractions(knowledgeGraphService);
    }

    @Test
    void deduplicateChunksDifferentContent() {
        Long factSheetId = 3L;
        GraphNode n1 = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET)
                .contentPreview("the quick brown fox jumps over the lazy dog")
                .title("chunk-1").externalId("e1").factSheetId(factSheetId).build();

        GraphNode n2 = GraphNode.builder()
                .id(2L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET)
                .contentPreview("completely different text about quantum mechanics and wave functions")
                .title("chunk-2").externalId("e2").factSheetId(factSheetId).build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(n1, n2));

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.85)
                .deduplicationShingleSize(5)
                .build();

        int deduped = service.deduplicateChunks(factSheetId, "job-3", config);

        assertEquals(0, deduped, "Completely different chunks should not be deduplicated");
        verifyNoInteractions(knowledgeGraphService);
    }

    // ─── index cleanup on dedup ───────────────────────────────────────────────

    @Test
    void deduplicateChunksDeletesFromVectorAndKeywordIndex() {
        Long factSheetId = 10L;
        String sharedText = "the quick brown fox jumps over the lazy dog and more text here";
        String indexDocId = "spring-ai-uuid-123";

        GraphNode n1 = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET)
                .contentPreview(sharedText)
                .title("chunk-1").confidence(0.9).edgeCount(0)
                .factSheetId(factSheetId).externalId("chunk:job1:doc.pdf:0").build();

        GraphNode n2 = GraphNode.builder()
                .id(2L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET)
                .contentPreview(sharedText)
                .title("chunk-2").confidence(0.5).edgeCount(0)
                .factSheetId(factSheetId).externalId("chunk:job1:doc.pdf:1").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(n1, n2));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(anyLong()))
                .thenReturn(List.of());
        // Resolver maps the loser's externalId to the index document ID
        when(crossIndexIdResolver.resolveIndexDocumentIds("chunk:job1:doc.pdf:1"))
                .thenReturn(List.of(indexDocId));

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.5)
                .deduplicationShingleSize(3)
                .build();

        int deduped = service.deduplicateChunks(factSheetId, "job-dedup", config);

        assertEquals(1, deduped);
        // n2 is the loser (lower confidence) — its graph node is deleted
        verify(knowledgeGraphService).deleteNode(n2.getNodeId());
        // Vector store and keyword index should also be cleaned up
        verify(vectorStore).delete(List.of(indexDocId));
        verify(indexerService).deleteFromKeywordIndex(List.of(indexDocId));
    }

    @Test
    void deduplicateChunksPreservesSemanticEdgeMetadataOnRedirect() {
        Long factSheetId = 12L;
        String sharedText = "identical text for semantic edge redirect preservation in chunk dedup";

        GraphNode winner = GraphNode.builder()
                .id(1L).nodeId("winner-node")
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-winner").confidence(0.9).edgeCount(1)
                .factSheetId(factSheetId).externalId("winner-ext").build();
        GraphNode loser = GraphNode.builder()
                .id(2L).nodeId("loser-node")
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-loser").confidence(0.5).edgeCount(1)
                .factSheetId(factSheetId).externalId("loser-ext").build();
        GraphNode related = GraphNode.builder()
                .id(3L).nodeId("related-node")
                .nodeType(NodeLevel.ENTITY).title("Related Entity")
                .externalId("related-ext").factSheetId(factSheetId).build();

        GraphEdge semanticEdge = GraphEdge.builder()
                .id(100L).edgeId("edge-1")
                .sourceNode(loser).targetNode(related)
                .edgeType(EdgeType.USER_DEFINED)
                .label("REFERENCES")
                .description("References related entity")
                .metadataJson("{\"sourceDocumentId\":\"doc-1\"}")
                .provenance(EdgeProvenance.EXTRACTED.name())
                .factSheetId(factSheetId)
                .weight(0.75)
                .build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(winner, loser));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(loser.getId()))
                .thenReturn(List.of(semanticEdge));
        when(knowledgeGraphService.edgeExists("winner-node", "related-node",
                EdgeType.USER_DEFINED, "REFERENCES", factSheetId)).thenReturn(false);

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.5)
                .deduplicationShingleSize(3)
                .build();

        int deduped = service.deduplicateChunks(factSheetId, "job-semantic-edge", config);

        assertEquals(1, deduped);
        verify(knowledgeGraphService).createEdgeWithMetadata("winner-node", "related-node",
                EdgeType.USER_DEFINED, 0.75, "REFERENCES", "References related entity",
                "{\"sourceDocumentId\":\"doc-1\"}", EdgeProvenance.EXTRACTED, factSheetId);
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("edge-1"));
        verify(knowledgeGraphService, never()).createEdge(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void deduplicateChunksSkipsSemanticDuplicateEdgeOnRedirect() {
        Long factSheetId = 13L;
        String sharedText = "identical text for duplicate semantic edge cleanup in chunk dedup";

        GraphNode winner = GraphNode.builder()
                .id(1L).nodeId("winner-node")
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-winner").confidence(0.9).edgeCount(1)
                .factSheetId(factSheetId).externalId("winner-ext").build();
        GraphNode loser = GraphNode.builder()
                .id(2L).nodeId("loser-node")
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-loser").confidence(0.5).edgeCount(1)
                .factSheetId(factSheetId).externalId("loser-ext").build();
        GraphNode related = GraphNode.builder()
                .id(3L).nodeId("related-node")
                .nodeType(NodeLevel.ENTITY).title("Related Entity")
                .externalId("related-ext").factSheetId(factSheetId).build();

        GraphEdge semanticEdge = GraphEdge.builder()
                .id(100L).edgeId("edge-duplicate")
                .sourceNode(loser).targetNode(related)
                .edgeType(EdgeType.USER_DEFINED)
                .description("MENTIONS")
                .factSheetId(factSheetId)
                .weight(0.75)
                .build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(winner, loser));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(loser.getId()))
                .thenReturn(List.of(semanticEdge));
        when(knowledgeGraphService.edgeExists("winner-node", "related-node",
                EdgeType.USER_DEFINED, "MENTIONS", factSheetId)).thenReturn(true);

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.5)
                .deduplicationShingleSize(3)
                .build();

        int deduped = service.deduplicateChunks(factSheetId, "job-duplicate-edge", config);

        assertEquals(1, deduped);
        verify(knowledgeGraphService).deleteEdgesBulk(List.of("edge-duplicate"));
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(anyString(), anyString(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void deduplicateChunksSkipsIndexCleanupWhenResolverUnavailable() {
        // Create service without resolver
        ChunkDeduplicationService serviceNoResolver = new ChunkDeduplicationService(
                nodeRepository, edgeRepository, knowledgeGraphService, auditService,
                objectMapper, vectorStore, indexerService, null);

        Long factSheetId = 11L;
        String sharedText = "identical text for dedup testing without resolver available here";

        GraphNode n1 = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-1").confidence(0.9).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-1").build();

        GraphNode n2 = GraphNode.builder()
                .id(2L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-2").confidence(0.5).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-2").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(n1, n2));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(anyLong()))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.5)
                .deduplicationShingleSize(3)
                .build();

        int deduped = serviceNoResolver.deduplicateChunks(factSheetId, "job-no-resolver", config);

        assertEquals(1, deduped);
        // Graph node still deleted
        verify(knowledgeGraphService).deleteNode(n2.getNodeId());
        // But vector/keyword NOT touched because resolver is null
        verifyNoInteractions(vectorStore);
        verifyNoInteractions(indexerService);
    }

    // ─── shouldSwap (via deduplication behavior) ──────────────────────────────

    @Test
    void shouldSwapPreferencesHigherConfidence() {
        Long factSheetId = 4L;
        String sharedText = "identical content repeated here for testing dedup logic works correctly";

        // n1 is encountered first (index 0) — would normally be winner
        // n2 has HIGHER confidence — swap should happen so n2 becomes winner
        GraphNode n1 = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-low-conf").confidence(0.3).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-low").build();

        GraphNode n2 = GraphNode.builder()
                .id(2L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.SNIPPET).contentPreview(sharedText)
                .title("chunk-high-conf").confidence(0.9).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-high").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET))
                .thenReturn(List.of(n1, n2));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(anyLong()))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .deduplicationJaccardThreshold(0.5)
                .deduplicationShingleSize(3)
                .build();

        service.deduplicateChunks(factSheetId, "job-4", config);

        // The loser (lower confidence = n1) should be deleted, not the higher-confidence n2
        verify(knowledgeGraphService).deleteNode(n1.getNodeId());
        verify(knowledgeGraphService, never()).deleteNode(n2.getNodeId());
    }
}
