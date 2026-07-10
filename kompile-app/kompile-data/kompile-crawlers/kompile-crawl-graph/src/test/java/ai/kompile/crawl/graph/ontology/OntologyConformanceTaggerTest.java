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

package ai.kompile.crawl.graph.ontology;

import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OntologyConformanceTagger}.
 *
 * <p>No Spring context: all collaborators are Mockito mocks injected via
 * {@link ReflectionTestUtils#setField}.  Tests cover:
 * <ol>
 *   <li>Bound ontology + non-conforming node → tagged {@code ontology.conformant=false} with reason</li>
 *   <li>Bound ontology + conforming node → tagged {@code ontology.conformant=true}, no violation key</li>
 *   <li>Case-insensitive type matching (entity type "PERSON" matches allowed type "person")</li>
 *   <li>Null provider / unbound ontology / empty allowed-types → no-op, zero metadata writes</li>
 *   <li>Null KnowledgeGraphService → no-op, zero metadata writes</li>
 *   <li>Mixed nodes: correct per-node tagging, counters correct</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyConformanceTaggerTest {

    private static final long FS_ID = 42L;

    @Mock
    private OntologyProjectionProvider ontologyProvider;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Captor
    private ArgumentCaptor<List<KnowledgeGraphService.NodeMetadataUpdate>> batchCaptor;

    private OntologyConformanceTagger tagger;

    @BeforeEach
    void setUp() {
        tagger = new OntologyConformanceTagger();
        ReflectionTestUtils.setField(tagger, "ontologyProvider", ontologyProvider);
        ReflectionTestUtils.setField(tagger, "knowledgeGraphService", knowledgeGraphService);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 1. Bound + non-conforming node → conformant=false, violation key set
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void boundOntology_nonConformingNode_taggedFalseWithReason() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of("PERSON", "ORGANIZATION"));
        when(ontologyProvider.allowedRelationshipTypes(FS_ID)).thenReturn(List.of());

        GraphNode node = makeEntityNode("PRODUCT", "node-1");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(node));
        when(knowledgeGraphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of());

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertEquals(0, result.nodesTaggedConformant());
        assertEquals(1, result.nodesTaggedNonConformant());

        // per-node updateNode must NOT be called — all writes go through the batch
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());

        verify(knowledgeGraphService).updateNodeKgeMetadataBatch(batchCaptor.capture());
        List<KnowledgeGraphService.NodeMetadataUpdate> updates = batchCaptor.getValue();
        assertThat(updates).hasSize(1);
        KnowledgeGraphService.NodeMetadataUpdate update = updates.get(0);
        assertThat(update.nodeId()).isEqualTo("node-1");
        assertThat(update.additionalMetadata())
                .containsEntry(OntologyConformanceTagger.META_CONFORMANT, "false");
        assertThat(update.additionalMetadata())
                .containsKey(OntologyConformanceTagger.META_VIOLATION);
        assertThat((String) update.additionalMetadata().get(OntologyConformanceTagger.META_VIOLATION))
                .contains("PRODUCT")
                .contains("not in bound ontology");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 2. Bound + conforming node → conformant=true, no violation key
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void boundOntology_conformingNode_taggedTrue_noViolation() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of("PERSON", "ORGANIZATION"));
        when(ontologyProvider.allowedRelationshipTypes(FS_ID)).thenReturn(List.of());

        GraphNode node = makeEntityNode("PERSON", "node-2");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(node));
        when(knowledgeGraphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of());

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertEquals(1, result.nodesTaggedConformant());
        assertEquals(0, result.nodesTaggedNonConformant());

        // per-node updateNode must NOT be called — all writes go through the batch
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());

        verify(knowledgeGraphService).updateNodeKgeMetadataBatch(batchCaptor.capture());
        List<KnowledgeGraphService.NodeMetadataUpdate> updates = batchCaptor.getValue();
        assertThat(updates).hasSize(1);
        KnowledgeGraphService.NodeMetadataUpdate update = updates.get(0);
        assertThat(update.nodeId()).isEqualTo("node-2");
        assertThat(update.additionalMetadata())
                .containsEntry(OntologyConformanceTagger.META_CONFORMANT, "true");
        // tag-delta for conformant nodes contains only META_CONFORMANT (no violation key)
        assertThat(update.additionalMetadata())
                .doesNotContainKey(OntologyConformanceTagger.META_VIOLATION);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 3. Case-insensitive matching: "PERSON" node matches allowed type "person"
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void caseInsensitiveMatch_personUpperMatchesLowerAllowed() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of("person", "organization"));
        when(ontologyProvider.allowedRelationshipTypes(FS_ID)).thenReturn(List.of());

        GraphNode node = makeEntityNode("PERSON", "node-ci");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(node));
        when(knowledgeGraphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of());

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertEquals(1, result.nodesTaggedConformant(), "PERSON must match lower-case allowed 'person'");
        assertEquals(0, result.nodesTaggedNonConformant());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 4a. Null provider → no-op
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void nullProvider_noOp_zeroMetadataWrites() {
        OntologyConformanceTagger bare = new OntologyConformanceTagger();
        // ontologyProvider intentionally NOT injected (stays null)
        ReflectionTestUtils.setField(bare, "knowledgeGraphService", knowledgeGraphService);

        OntologyConformanceTagger.TagResult result = bare.tag(FS_ID, true);

        assertThat(result).isEqualTo(OntologyConformanceTagger.TagResult.empty());
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());
        verify(knowledgeGraphService, never()).updateNodeKgeMetadataBatch(any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 4b. Unbound ontology → no-op
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void unboundOntology_noOp_zeroMetadataWrites() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(false);

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertThat(result).isEqualTo(OntologyConformanceTagger.TagResult.empty());
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());
        verify(knowledgeGraphService, never()).updateNodeKgeMetadataBatch(any());
        verify(knowledgeGraphService, never()).getNodesByTypeInFactSheet(any(), any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 4c. Empty allowed-types list → no-op
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void emptyAllowedTypes_noOp_zeroMetadataWrites() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of());

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertThat(result).isEqualTo(OntologyConformanceTagger.TagResult.empty());
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());
        verify(knowledgeGraphService, never()).updateNodeKgeMetadataBatch(any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 4d. Null KnowledgeGraphService → no-op
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void nullKnowledgeGraphService_noOp_zeroMetadataWrites() {
        OntologyConformanceTagger bare = new OntologyConformanceTagger();
        ReflectionTestUtils.setField(bare, "ontologyProvider", ontologyProvider);
        // knowledgeGraphService intentionally NOT injected (stays null)

        OntologyConformanceTagger.TagResult result = bare.tag(FS_ID, true);

        assertThat(result).isEqualTo(OntologyConformanceTagger.TagResult.empty());
        // No interaction with knowledgeGraphService at all
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 5. Mixed nodes: counters correct, each node tagged correctly
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void mixedNodes_countersCorrect_eachTaggedProperly() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of("PERSON", "ORGANIZATION"));
        when(ontologyProvider.allowedRelationshipTypes(FS_ID)).thenReturn(List.of());

        GraphNode person = makeEntityNode("PERSON",       "person-node");
        GraphNode org    = makeEntityNode("ORGANIZATION", "org-node");
        GraphNode prod   = makeEntityNode("PRODUCT",      "product-node");
        GraphNode loc    = makeEntityNode("LOCATION",     "location-node");

        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(person, org, prod, loc));
        when(knowledgeGraphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of());

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertEquals(2, result.nodesTaggedConformant(),    "PERSON + ORGANIZATION");
        assertEquals(2, result.nodesTaggedNonConformant(), "PRODUCT + LOCATION");
        assertEquals(4, result.totalNodesTagged());

        // per-node updateNode must NOT be called — all 4 writes go through one batch call
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());

        verify(knowledgeGraphService).updateNodeKgeMetadataBatch(batchCaptor.capture());
        List<KnowledgeGraphService.NodeMetadataUpdate> updates = batchCaptor.getValue();
        assertThat(updates).hasSize(4);

        // verify each node has the right conformance flag
        Map<String, String> conformanceById = new java.util.HashMap<>();
        for (KnowledgeGraphService.NodeMetadataUpdate u : updates) {
            conformanceById.put(u.nodeId(), (String) u.additionalMetadata().get(OntologyConformanceTagger.META_CONFORMANT));
        }
        assertThat(conformanceById).containsEntry("person-node",  "true");
        assertThat(conformanceById).containsEntry("org-node",     "true");
        assertThat(conformanceById).containsEntry("product-node", "false");
        assertThat(conformanceById).containsEntry("location-node","false");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 6. tagEdges=false → edge queries not performed
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void tagEdgesFalse_edgeQueriesNotPerformed() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of("PERSON"));
        // allowedRelationshipTypes is NOT stubbed — if called it would throw UnnecessaryStubbingException
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of());

        tagger.tag(FS_ID, false);

        verify(knowledgeGraphService, never()).getEdgesInFactSheet(any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 7. Node with no entity_type in metadata → tagged non-conformant with null reason
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void nodeWithNoEntityType_taggedNonConformant_withNullReason() {
        when(ontologyProvider.hasBoundOntology(FS_ID)).thenReturn(true);
        when(ontologyProvider.allowedEntityTypes(FS_ID)).thenReturn(List.of("PERSON"));
        when(ontologyProvider.allowedRelationshipTypes(FS_ID)).thenReturn(List.of());

        // Node has no entity_type key in metadata
        GraphNode node = makeEntityNode(null, "no-type-node");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(node));
        when(knowledgeGraphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of());

        OntologyConformanceTagger.TagResult result = tagger.tag(FS_ID, true);

        assertEquals(0, result.nodesTaggedConformant());
        assertEquals(1, result.nodesTaggedNonConformant());

        // per-node updateNode must NOT be called — writes go through the batch
        verify(knowledgeGraphService, never()).updateNode(anyString(), any(), any(), any());

        verify(knowledgeGraphService).updateNodeKgeMetadataBatch(batchCaptor.capture());
        List<KnowledgeGraphService.NodeMetadataUpdate> updates = batchCaptor.getValue();
        assertThat(updates).hasSize(1);
        KnowledgeGraphService.NodeMetadataUpdate update = updates.get(0);
        assertThat(update.nodeId()).isEqualTo("no-type-node");
        assertThat(update.additionalMetadata())
                .containsEntry(OntologyConformanceTagger.META_CONFORMANT, "false");
        assertThat((String) update.additionalMetadata().get(OntologyConformanceTagger.META_VIOLATION))
                .contains("unknown");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 8. TagResult helpers
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void tagResultHelpers_correct() {
        OntologyConformanceTagger.TagResult r = new OntologyConformanceTagger.TagResult(3, 2, 1, 4);
        assertEquals(5,  r.totalNodesTagged());
        assertEquals(5,  r.totalEdgesTagged());

        OntologyConformanceTagger.TagResult empty = OntologyConformanceTagger.TagResult.empty();
        assertEquals(0, empty.totalNodesTagged());
        assertEquals(0, empty.totalEdgesTagged());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helper: build a GraphNode backed by metadataJson with optional entity_type
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Constructs a GraphNode with {@code metadataJson} containing the {@code entity_type} key
     * (mirroring what the extraction pipeline writes) and the given nodeId.
     *
     * @param entityType the entity type to embed; {@code null} means the key is absent
     * @param nodeId     the node's UUID string
     */
    private static GraphNode makeEntityNode(String entityType, String nodeId) {
        String meta = entityType != null
                ? "{\"entity_type\":\"" + entityType + "\"}"
                : "{}";
        return GraphNode.builder()
                .nodeId(nodeId)
                .externalId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("Test node " + nodeId)
                .metadataJson(meta)
                .build();
    }
}
