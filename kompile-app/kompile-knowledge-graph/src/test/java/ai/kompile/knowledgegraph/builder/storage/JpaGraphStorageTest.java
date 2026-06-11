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

package ai.kompile.knowledgegraph.builder.storage;

import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link JpaGraphStorage} — storeProposal with new/existing nodes,
 * metadata enrichment, isAvailable, and error handling.
 *
 * Note: JpaGraphStorage does not implement EXTRACTED_FROM snippet linking;
 * sourceChunkId is stored only in proposal metadata, not as a graph edge.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class JpaGraphStorageTest {

    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;

    @Captor private ArgumentCaptor<GraphNode> nodeCaptor;
    @Captor private ArgumentCaptor<GraphEdge> edgeCaptor;

    private JpaGraphStorage storage;

    @BeforeEach
    void setUp() {
        storage = new JpaGraphStorage(nodeRepository, edgeRepository);
    }

    private TripleProposal proposal(String subjectName, String predicate, String objectName) {
        return TripleProposal.builder()
                .proposalId("prop-1")
                .factSheetId(1L)
                .subjectName(subjectName)
                .subjectType("PERSON")
                .subjectDescription("Subject desc")
                .predicateName(predicate)
                .objectName(objectName)
                .objectType("ORGANIZATION")
                .objectDescription("Object desc")
                .confidence(0.9)
                .build();
    }

    private GraphNode savedNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("entity_" + title.toLowerCase().replaceAll("[^a-z0-9]", "_"))
                .title(title)
                .factSheetId(1L)
                .edgeCount(0)
                .build();
    }

    private GraphEdge savedEdge(String edgeId) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .edgeType(EdgeType.USER_DEFINED)
                .build();
    }

    // ─── getStorageType ──────────────────────────────────────────────

    @Test
    void getStorageType_returnsJpa() {
        assertEquals("jpa", storage.getStorageType());
    }

    // ─── isAvailable ─────────────────────────────────────────────────

    @Test
    void isAvailable_repositoryWorking_returnsTrue() {
        when(nodeRepository.count()).thenReturn(5L);
        assertTrue(storage.isAvailable());
    }

    @Test
    void isAvailable_repositoryThrows_returnsFalse() {
        when(nodeRepository.count()).thenThrow(new RuntimeException("DB down"));
        assertFalse(storage.isAvailable());
    }

    // ─── storeProposal: basic success ────────────────────────────────

    @Test
    void storeProposal_createsNewNodes_returnsSuccess() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        // No existing nodes
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), eq(NodeLevel.ENTITY), eq(1L)))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode)    // subject save
                .thenReturn(objectNode)     // object save
                .thenReturn(subjectNode)    // edge count update for subject
                .thenReturn(objectNode);    // edge count update for object
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        TripleProposal p = proposal("Alice", "WORKS_AT", "Acme Corp");
        GraphStorageStrategy.StorageResult result = storage.storeProposal(p);

        assertTrue(result.success());
        assertEquals("sn-1", result.subjectNodeId());
        assertEquals("on-1", result.objectNodeId());
        assertEquals("e-1", result.edgeId());
        assertNull(result.errorMessage());
    }

    @Test
    void storeProposal_reusesExistingNodes() {
        GraphNode existingSubject = savedNode("existing-s", "Alice");
        GraphNode existingObject = savedNode("existing-o", "Acme Corp");
        GraphEdge edge = savedEdge("e-2");

        // Existing nodes found
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq("entity_alice"), eq(NodeLevel.ENTITY), eq(1L)))
                .thenReturn(Optional.of(existingSubject));
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                eq("entity_acme_corp"), eq(NodeLevel.ENTITY), eq(1L)))
                .thenReturn(Optional.of(existingObject));
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(existingSubject)
                .thenReturn(existingObject);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        TripleProposal p = proposal("Alice", "WORKS_AT", "Acme Corp");
        GraphStorageStrategy.StorageResult result = storage.storeProposal(p);

        assertTrue(result.success());
        assertEquals("existing-s", result.subjectNodeId());
        assertEquals("existing-o", result.objectNodeId());
    }

    // ─── storeProposal: edge creation ────────────────────────────────

    @Test
    void storeProposal_createsUserDefinedEdge() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode, objectNode, subjectNode, objectNode);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        storage.storeProposal(proposal("Alice", "WORKS_AT", "Acme Corp"));

        verify(edgeRepository).save(edgeCaptor.capture());
        GraphEdge capturedEdge = edgeCaptor.getValue();
        assertEquals(EdgeType.USER_DEFINED, capturedEdge.getEdgeType());
        assertEquals("WORKS_AT", capturedEdge.getLabel());
        assertFalse(capturedEdge.getBidirectional());
    }

    // ─── storeProposal: sourceChunkId presence ───────────────────────
    // JpaGraphStorage stores proposals regardless of sourceChunkId; it does not
    // create EXTRACTED_FROM edges — that enrichment is done elsewhere.

    @Test
    void storeProposal_withSourceChunkId_stillCreatesEdge() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), eq(NodeLevel.ENTITY), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode, objectNode, subjectNode, objectNode);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        TripleProposal p = TripleProposal.builder()
                .proposalId("prop-2")
                .factSheetId(1L)
                .subjectName("Alice")
                .subjectType("PERSON")
                .predicateName("WORKS_AT")
                .objectName("Acme Corp")
                .objectType("ORGANIZATION")
                .sourceChunkId("chunk-42")
                .confidence(0.8)
                .build();

        GraphStorageStrategy.StorageResult result = storage.storeProposal(p);

        assertTrue(result.success());
        // Only the USER_DEFINED edge is created; no EXTRACTED_FROM linking
        verify(edgeRepository, times(1)).save(any(GraphEdge.class));
    }

    @Test
    void storeProposal_withNoSourceChunkId_onlyCreatesRelationEdge() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode, objectNode, subjectNode, objectNode);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        storage.storeProposal(proposal("Alice", "WORKS_AT", "Acme Corp"));

        // Should only create 1 edge (the USER_DEFINED relation)
        verify(edgeRepository, times(1)).save(any(GraphEdge.class));
        // No snippet lookup happens
        verify(nodeRepository, never()).findByExternalIdAndNodeType(anyString(), eq(NodeLevel.SNIPPET));
    }

    @Test
    void storeProposal_withSourceChunkAndContext_returnsSuccess() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), eq(NodeLevel.ENTITY), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode, objectNode, subjectNode, objectNode);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        TripleProposal p = TripleProposal.builder()
                .proposalId("prop-3")
                .factSheetId(1L)
                .subjectName("Alice")
                .subjectType("PERSON")
                .predicateName("WORKS_AT")
                .objectName("Acme Corp")
                .objectType("ORGANIZATION")
                .sourceChunkId("missing-chunk")
                .sourceDocumentId("doc-1")
                .sourceContext("Some context text")
                .confidence(0.8)
                .build();

        GraphStorageStrategy.StorageResult result = storage.storeProposal(p);

        assertTrue(result.success());
    }

    // ─── storeProposal: error handling ───────────────────────────────

    @Test
    void storeProposal_repositoryThrows_returnsFailure() {
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), any(), anyLong()))
                .thenThrow(new RuntimeException("DB error"));

        TripleProposal p = proposal("Alice", "WORKS_AT", "Acme Corp");
        GraphStorageStrategy.StorageResult result = storage.storeProposal(p);

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("DB error"));
    }

    // ─── storeProposal: externalId generation ────────────────────────

    @Test
    void storeProposal_generatesExternalIdFromName() {
        GraphNode subjectNode = savedNode("sn-1", "John Doe");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode, objectNode, subjectNode, objectNode);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        storage.storeProposal(proposal("John Doe", "WORKS_AT", "Acme Corp"));

        // Verify externalId lookup used normalized form
        verify(nodeRepository).findByExternalIdAndNodeTypeAndFactSheetId(
                eq("entity_john_doe"), eq(NodeLevel.ENTITY), eq(1L));
    }

    // ─── storeProposal: null subjectType ─────────────────────────────

    @Test
    void storeProposal_nullSubjectType_usesUnknown() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenAnswer(inv -> {
                    GraphNode n = inv.getArgument(0);
                    if (n.getMetadataJson() != null && n.getMetadataJson().contains("UNKNOWN")) {
                        return subjectNode;
                    }
                    return objectNode;
                });
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        TripleProposal p = TripleProposal.builder()
                .proposalId("prop-5")
                .factSheetId(1L)
                .subjectName("Alice")
                .subjectType(null) // null type
                .predicateName("KNOWS")
                .objectName("Acme Corp")
                .objectType("ORG")
                .confidence(0.5)
                .build();

        GraphStorageStrategy.StorageResult result = storage.storeProposal(p);
        assertTrue(result.success());
    }

    // ─── storeProposal: confidence ───────────────────────────────────

    @Test
    void storeProposal_setsConfidenceOnEdge() {
        GraphNode subjectNode = savedNode("sn-1", "Alice");
        GraphNode objectNode = savedNode("on-1", "Acme Corp");
        GraphEdge edge = savedEdge("e-1");

        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any(GraphNode.class)))
                .thenReturn(subjectNode, objectNode, subjectNode, objectNode);
        when(edgeRepository.save(any(GraphEdge.class))).thenReturn(edge);

        TripleProposal p = proposal("Alice", "WORKS_AT", "Acme Corp");
        p.setConfidence(0.95);
        storage.storeProposal(p);

        verify(edgeRepository).save(edgeCaptor.capture());
        assertEquals(0.95, edgeCaptor.getValue().getWeight());
    }
}
