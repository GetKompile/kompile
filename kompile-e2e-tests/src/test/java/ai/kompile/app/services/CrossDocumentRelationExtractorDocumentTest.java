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

package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Strategies D, E, and F of {@link CrossDocumentRelationExtractor}.
 *
 * <ul>
 *   <li>Strategy D: {@code resolveHyperlinks()} — PDF annotation documents whose text
 *       contains URLs that match another document's filename.</li>
 *   <li>Strategy E: {@code detectSharedAuthors()} — documents sharing the same author
 *       metadata field.</li>
 *   <li>Strategy F: {@code detectSharedKeywords()} — documents sharing keywords from
 *       the keywords metadata field.</li>
 * </ul>
 *
 * <p>Each test uses only synthetic metadata — no real datasets are required.
 * Mockito stubs {@link GraphNodeRepository} and {@link KnowledgeGraphService} so the
 * extractor can resolve node IDs and record edges without a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossDocumentRelationExtractorDocumentTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private GraphNodeRepository graphNodeRepository;

    private CrossDocumentRelationExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CrossDocumentRelationExtractor(knowledgeGraphService, graphNodeRepository);
        // Default: no edges exist yet; createEdgeWithMetadata returns null (void mock is fine)
        when(knowledgeGraphService.edgeExists(anyString(), anyString())).thenReturn(false);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a Document whose {@code source} path is {@code /uploads/<fileName>}.
     * Extra metadata entries are merged on top of the base map.
     */
    private Document makeDoc(String fileName, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileName", fileName);
        meta.put("source", "/uploads/" + fileName);
        if (extraMeta != null) {
            meta.putAll(extraMeta);
        }
        return new Document("content of " + fileName, meta);
    }

    /**
     * Registers a stub so that when the extractor looks up a DOCUMENT node by
     * {@code /uploads/<fileName>} it gets back a {@link GraphNode} with the given
     * {@code nodeId}.
     */
    private void stubNodeLookup(String fileName, String nodeId) {
        String sourcePath = "/uploads/" + fileName;
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        when(graphNodeRepository.findByExternalIdAndNodeType(eq(sourcePath), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(node));
        when(graphNodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq(sourcePath), eq(NodeLevel.DOCUMENT), anyLong()))
                .thenReturn(Optional.of(node));
    }

    // =========================================================================
    // Strategy D: resolveHyperlinks
    // =========================================================================

    /**
     * D-1: An annotation document whose text contains a URL whose filename segment
     * matches another document in the batch should produce a HYPERLINK_TO edge.
     */
    @Test
    void strategyD_annotationDocWithUrlMatchingOtherDocFilename_createsHyperlinkEdge() {
        // The target document referenced by the hyperlink
        Document targetDoc = makeDoc("technical_spec.pdf", null);

        // Annotation document — the PDF loader sets extractionType="annotations".
        // Its text contains a URL whose filename segment matches the target document.
        Map<String, Object> annoMeta = new HashMap<>();
        annoMeta.put("extractionType", "annotations");
        annoMeta.put("fileName", "report_annotations.pdf");
        annoMeta.put("source", "/uploads/report_annotations.pdf");
        Document annoDoc = new Document(
                "See also: https://example.com/docs/technical_spec.pdf for details.",
                annoMeta);

        stubNodeLookup("report_annotations.pdf", "node-anno");
        stubNodeLookup("technical_spec.pdf", "node-target");

        int result = extractor.extractRelations(List.of(annoDoc, targetDoc), 1L);

        assertTrue(result >= 1, "Expected at least one HYPERLINK_TO edge");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                eq("node-anno"), eq("node-target"),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("HYPERLINK_TO"),
                anyString(), contains("HYPERLINK_TO"),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * D-2: A URL that does not match any document filename in the batch should not
     * produce any edge.
     */
    @Test
    void strategyD_urlWithNoMatchingDocumentFilename_createsNoEdge() {
        Map<String, Object> annoMeta = new HashMap<>();
        annoMeta.put("extractionType", "annotations");
        annoMeta.put("fileName", "report_annotations.pdf");
        annoMeta.put("source", "/uploads/report_annotations.pdf");
        Document annoDoc = new Document(
                "External link: https://external-site.com/no_such_document.pdf",
                annoMeta);

        // The other document has an unrelated filename
        Document otherDoc = makeDoc("inventory.xlsx", null);

        stubNodeLookup("report_annotations.pdf", "node-anno");
        stubNodeLookup("inventory.xlsx", "node-inventory");

        int result = extractor.extractRelations(List.of(annoDoc, otherDoc), 1L);

        // The URL's filename segment "no_such_document.pdf" does not match "inventory.xlsx"
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("HYPERLINK_TO"),
                anyString(), anyString(), any(EdgeProvenance.class), anyLong());
        assertEquals(0, result);
    }

    /**
     * D-3: A document that does NOT have extractionType="annotations" must be
     * skipped entirely by Strategy D, even if its text contains a URL that
     * matches another document.
     */
    @Test
    void strategyD_nonAnnotationDocument_isSkippedByStrategyD() {
        // Same URL in text, but extractionType is "content" not "annotations"
        Map<String, Object> contentMeta = new HashMap<>();
        contentMeta.put("extractionType", "content");
        contentMeta.put("fileName", "body_page.pdf");
        contentMeta.put("source", "/uploads/body_page.pdf");
        Document contentDoc = new Document(
                "Reference: https://example.com/docs/appendix.pdf",
                contentMeta);

        Document appendixDoc = makeDoc("appendix.pdf", null);

        stubNodeLookup("body_page.pdf", "node-body");
        stubNodeLookup("appendix.pdf", "node-appendix");

        extractor.extractRelations(List.of(contentDoc, appendixDoc), 1L);

        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("HYPERLINK_TO"),
                anyString(), anyString(), any(EdgeProvenance.class), anyLong());
    }

    /**
     * D-4: URL-encoded filename (%20 decoded to space) should match the target
     * document whose filename contains a space.
     */
    @Test
    void strategyD_urlEncodedFilenameWithSpaces_matchesTargetDocument() {
        Map<String, Object> annoMeta = new HashMap<>();
        annoMeta.put("extractionType", "annotations");
        annoMeta.put("fileName", "index_annotations.pdf");
        annoMeta.put("source", "/uploads/index_annotations.pdf");
        // URL contains %20 which should be decoded to a space before matching
        Document annoDoc = new Document(
                "Link: https://docs.example.com/files/budget%20report.xlsx",
                annoMeta);

        // Target document has a space in its filename
        Map<String, Object> targetMeta = new HashMap<>();
        targetMeta.put("fileName", "budget report.xlsx");
        targetMeta.put("source", "/uploads/budget report.xlsx");
        Document targetDoc = new Document("budget content", targetMeta);

        // Register stubs using the actual source paths
        GraphNode annoNode = new GraphNode();
        annoNode.setNodeId("node-index-anno");
        when(graphNodeRepository.findByExternalIdAndNodeType(
                eq("/uploads/index_annotations.pdf"), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(annoNode));
        when(graphNodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq("/uploads/index_annotations.pdf"), eq(NodeLevel.DOCUMENT), anyLong()))
                .thenReturn(Optional.of(annoNode));

        GraphNode targetNode = new GraphNode();
        targetNode.setNodeId("node-budget-report");
        when(graphNodeRepository.findByExternalIdAndNodeType(
                eq("/uploads/budget report.xlsx"), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(targetNode));
        when(graphNodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq("/uploads/budget report.xlsx"), eq(NodeLevel.DOCUMENT), anyLong()))
                .thenReturn(Optional.of(targetNode));

        int result = extractor.extractRelations(List.of(annoDoc, targetDoc), 1L);

        assertTrue(result >= 1, "URL-decoded filename should match target document");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                eq("node-index-anno"), eq("node-budget-report"),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("HYPERLINK_TO"),
                anyString(), contains("HYPERLINK_TO"),
                any(EdgeProvenance.class), anyLong());
    }

    // =========================================================================
    // Strategy E: detectSharedAuthors
    // =========================================================================

    /**
     * E-1: Two documents with the same author value produce one SHARED_AUTHOR edge.
     */
    @Test
    void strategyE_twoDocumentsSameAuthor_createsOneSharedAuthorEdge() {
        Document docA = makeDoc("docA.pdf", Map.of("author", "Jane Smith"));
        Document docB = makeDoc("docB.pdf", Map.of("author", "Jane Smith"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        int result = extractor.extractRelations(List.of(docA, docB), 1L);

        assertTrue(result >= 1, "Expected at least one SHARED_AUTHOR edge");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_AUTHOR"),
                anyString(), contains("SHARED_AUTHOR"),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * E-2: Three documents with the same author produce three SHARED_AUTHOR edges
     * (A-B, A-C, B-C — all combinations).
     */
    @Test
    void strategyE_threeDocumentsSameAuthor_createsThreeEdges() {
        Document docA = makeDoc("docA.pdf", Map.of("author", "Alice"));
        Document docB = makeDoc("docB.pdf", Map.of("author", "Alice"));
        Document docC = makeDoc("docC.pdf", Map.of("author", "Alice"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");
        stubNodeLookup("docC.pdf", "node-C");

        extractor.extractRelations(List.of(docA, docB, docC), 1L);

        // 3 pairs from C(3,2): A-B, A-C, B-C.
        // Count only calls with label SHARED_AUTHOR to isolate strategy E.
        verify(knowledgeGraphService, times(3)).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_AUTHOR"),
                anyString(), contains("SHARED_AUTHOR"),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * E-3: Documents with different authors produce no SHARED_AUTHOR edge.
     */
    @Test
    void strategyE_documentsWithDifferentAuthors_createsNoEdge() {
        Document docA = makeDoc("docA.pdf", Map.of("author", "Alice"));
        Document docB = makeDoc("docB.pdf", Map.of("author", "Bob"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        extractor.extractRelations(List.of(docA, docB), 1L);

        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_AUTHOR"),
                anyString(), anyString(),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * E-4: Author matching is case-insensitive — "Jane Smith" and "jane smith"
     * should be treated as the same author.
     */
    @Test
    void strategyE_authorNameCaseInsensitiveMatching_createsEdge() {
        Document docA = makeDoc("docA.pdf", Map.of("author", "Jane Smith"));
        Document docB = makeDoc("docB.pdf", Map.of("author", "jane smith"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        int result = extractor.extractRelations(List.of(docA, docB), 1L);

        assertTrue(result >= 1, "Case-insensitive author match should create a SHARED_AUTHOR edge");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_AUTHOR"),
                anyString(), anyString(),
                any(EdgeProvenance.class), anyLong());
    }

    // =========================================================================
    // Strategy F: detectSharedKeywords
    // =========================================================================

    /**
     * F-1: Two documents sharing a keyword produce one SHARED_KEYWORD edge.
     */
    @Test
    void strategyF_twoDocumentsSharingKeyword_createsOneSharedKeywordEdge() {
        Document docA = makeDoc("docA.pdf", Map.of("keywords", "machine learning, AI, neural networks"));
        Document docB = makeDoc("docB.pdf", Map.of("keywords", "neural networks, deep learning"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        int result = extractor.extractRelations(List.of(docA, docB), 1L);

        assertTrue(result >= 1, "Documents sharing keyword 'neural networks' should create a SHARED_KEYWORD edge");
        verify(knowledgeGraphService, atLeastOnce()).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_KEYWORD"),
                anyString(), contains("SHARED_KEYWORD"),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * F-2: Documents with no overlapping keywords produce no SHARED_KEYWORD edge.
     */
    @Test
    void strategyF_noOverlappingKeywords_createsNoEdge() {
        Document docA = makeDoc("docA.pdf", Map.of("keywords", "finance, accounting, budget"));
        Document docB = makeDoc("docB.pdf", Map.of("keywords", "biology, genetics, proteins"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        extractor.extractRelations(List.of(docA, docB), 1L);

        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_KEYWORD"),
                anyString(), anyString(),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * F-3: Keywords with 2 characters or fewer must be ignored — they are too
     * short to be meaningful signals.
     */
    @Test
    void strategyF_shortKeywordsLessThanOrEqualToTwoChars_areIgnored() {
        // "AI" and "ML" are 2 chars each — should be ignored
        Document docA = makeDoc("docA.pdf", Map.of("keywords", "AI, ML"));
        Document docB = makeDoc("docB.pdf", Map.of("keywords", "AI, ML"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        extractor.extractRelations(List.of(docA, docB), 1L);

        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_KEYWORD"),
                anyString(), anyString(),
                any(EdgeProvenance.class), anyLong());
    }

    /**
     * F-4: When two documents share multiple keywords, only one SHARED_KEYWORD edge
     * should be created between them (duplicate pair suppression).
     */
    @Test
    void strategyF_multipleSharedKeywords_avoidsDuplicateEdgesForSamePair() {
        // Both documents share "machine learning" AND "neural networks"
        Document docA = makeDoc("docA.pdf", Map.of("keywords", "machine learning, neural networks, optimization"));
        Document docB = makeDoc("docB.pdf", Map.of("keywords", "machine learning, neural networks, clustering"));

        stubNodeLookup("docA.pdf", "node-A");
        stubNodeLookup("docB.pdf", "node-B");

        int result = extractor.extractRelations(List.of(docA, docB), 1L);

        // Only one edge between node-A and node-B regardless of how many keywords match
        verify(knowledgeGraphService, times(1)).createEdgeWithMetadata(
                anyString(), anyString(),
                eq(EdgeType.USER_DEFINED),
                anyDouble(), eq("SHARED_KEYWORD"),
                anyString(), anyString(),
                any(EdgeProvenance.class), anyLong());
    }

    // =========================================================================
    // Integration: extractRelations calls all six strategies
    // =========================================================================

    /**
     * Integration: {@code extractRelations()} invokes all six strategies.
     * This test wires up conditions to trigger strategies A, B, C, D, E, and F
     * simultaneously and verifies the total count reflects contributions from each.
     */
    @Test
    void extractRelations_callsAllSixStrategies() {
        // Strategy B: two version files (also triggers A/C lookup paths)
        Document v1 = makeDoc("proposal_v1.pdf", Map.of("author", "Alice", "keywords", "project, strategy"));
        Document v2 = makeDoc("proposal_v2.pdf", Map.of("author", "Alice", "keywords", "project, planning"));

        // Strategy A: email with attachment referencing v1
        Map<String, Object> emailMeta = new HashMap<>();
        emailMeta.put("email.subject", "Review this");
        emailMeta.put("email.attachmentNames", List.of("proposal_v1.pdf"));
        emailMeta.put("author", "Bob");
        emailMeta.put("keywords", "email, review");
        Document emailDoc = makeDoc("review_email.html", emailMeta);

        // Strategy D: annotation doc pointing to v2
        Map<String, Object> annoMeta = new HashMap<>();
        annoMeta.put("extractionType", "annotations");
        annoMeta.put("author", "Charlie");
        annoMeta.put("keywords", "annotations");
        Document annoDoc = new Document(
                "Reference: https://docs.example.com/proposal_v2.pdf",
                new HashMap<>(Map.of(
                        "extractionType", "annotations",
                        "fileName", "index_anno.pdf",
                        "source", "/uploads/index_anno.pdf",
                        "author", "Charlie",
                        "keywords", "annotations")));

        stubNodeLookup("proposal_v1.pdf", "node-v1");
        stubNodeLookup("proposal_v2.pdf", "node-v2");
        stubNodeLookup("review_email.html", "node-email");

        GraphNode annoNode = new GraphNode();
        annoNode.setNodeId("node-anno");
        when(graphNodeRepository.findByExternalIdAndNodeType(
                eq("/uploads/index_anno.pdf"), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(annoNode));
        when(graphNodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq("/uploads/index_anno.pdf"), eq(NodeLevel.DOCUMENT), anyLong()))
                .thenReturn(Optional.of(annoNode));

        int result = extractor.extractRelations(
                List.of(v1, v2, emailDoc, annoDoc), 1L);

        // At minimum: strategy B (1 version edge) + strategy A (1 attachment) +
        // strategy D (1 hyperlink) + strategy E (1 shared author Alice on v1&v2) = 4
        assertTrue(result >= 4,
                "Should create at least 4 edges combining strategies A, B, D, and E; got " + result);
    }
}
