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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Verifies the matrix store restores the extended edge fields carried in the typed
 * {@code EdgeMetadata} (metaJson) on import — bidirectional (M-3), factSheetId (M-1),
 * shared-entity payload + similarity (M-4), and explicit label (M-7).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixKnowledgeGraphServiceEdgeTest {

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @Mock
    private MatrixGraphStore graphStore;

    private MatrixKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        service = new MatrixKnowledgeGraphService(graphStore, new ObjectMapper());
    }

    @Test
    void createEdgeWithMetadata_honorsImportedBidirectional_overEdgeTypeDefault() {
        // A HIERARCHICAL edge would default to bidirectional=false; the imported metaJson says true,
        // and that must win (M-3) — otherwise a bidirectional edge becomes directional after a clone.
        String metaJson = "{\"bidirectional\":true,"
                + "\"sharedEntitiesJson\":\"[\\\"Acme\\\"]\","
                + "\"similarityScore\":0.91,\"label\":\"co-occurs\"}";

        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", metaJson, null, 42L);

        ArgumentCaptor<Boolean> bidi = ArgumentCaptor.forClass(Boolean.class);
        // factSheetId 42 → segmented graph "factsheet_42"
        verify(graphStore).addEdge(eq("factsheet_42"), eq("a"), eq("b"), eq(1.0), anyString(),
                bidi.capture(), any(), any(), any());
        assertTrue(bidi.getValue(), "[M-3] imported bidirectional=true must override the HIERARCHICAL default");
        assertTrue(edge.getBidirectional());
        assertEquals(42L, edge.getFactSheetId(), "[M-1] factSheetId surfaced on the edge");
        assertEquals("[\"Acme\"]", edge.getSharedEntitiesJson(), "[M-4] shared-entity payload surfaced");
        assertEquals(0.91, edge.getSimilarityScore(), "[M-4] similarity score surfaced");
        assertEquals("co-occurs", edge.getLabel(), "[M-7] explicit label surfaced");
    }

    @Test
    void createEdgeWithMetadata_defaultsBidirectionalFromEdgeType_whenNotImported() {
        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", null, null, null);

        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("a"), eq("b"), eq(1.0), anyString(),
                eq(false), any(), any(), any());
        assertFalse(edge.getBidirectional(), "HIERARCHICAL with no imported flag stays directional");
    }

    @Test
    void createEdgeWithMetadata_surfacesTypedProvenanceFromMetaJson() {
        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", "{\"provenanceType\":\"INFERRED\"}", null, null);
        assertEquals(EdgeProvenance.INFERRED, edge.getProvenanceType(),
                "[M-10] typed provenance classification restored from metaJson");
    }

    @Test
    void createEdgeWithMetadata_usesEdgeProvenanceParam_whenMetaJsonHasNoProvenanceType() {
        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", null, EdgeProvenance.EXTRACTED, null);
        assertEquals(EdgeProvenance.EXTRACTED, edge.getProvenanceType(),
                "[M-10] typed provenance falls back to the EdgeProvenance param");
    }
}
