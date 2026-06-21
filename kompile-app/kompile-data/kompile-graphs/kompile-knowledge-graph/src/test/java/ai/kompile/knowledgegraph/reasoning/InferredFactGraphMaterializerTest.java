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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [L-6] Verifies inferred facts are persisted into the graph: binary predicates become INFERRED
 * edges, unary predicates become node attributes, and facts whose entities can't be resolved are
 * skipped (not silently materialized against nothing).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InferredFactGraphMaterializerTest {

    @Mock
    private KnowledgeGraphService graphService;

    private InferredFactGraphMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new InferredFactGraphMaterializer(graphService, new ObjectMapper());
    }

    @Test
    void materialize_binaryAtom_createsInferredEdgeWithSoftTruthAndProvenance() {
        GraphNode a = GraphNode.builder().nodeId("ua").externalId("a").nodeType(NodeLevel.ENTITY).build();
        GraphNode b = GraphNode.builder().nodeId("ub").externalId("b").nodeType(NodeLevel.ENTITY).build();
        when(graphService.getNodeByExternalIdInFactSheet(eq("a"), any(), eq(1L))).thenReturn(Optional.of(a));
        when(graphService.getNodeByExternalIdInFactSheet(eq("b"), any(), eq(1L))).thenReturn(Optional.of(b));

        InferredFact fact = InferredFact.of("Causes(a, b)", 0.83, List.of(), List.of("rule1"), "run-7", 1L);

        var result = materializer.materialize(List.of(fact), 1L);

        assertEquals(1, result.edgesCreated());
        assertEquals(0, result.skipped());
        ArgumentCaptor<String> metaJson = ArgumentCaptor.forClass(String.class);
        verify(graphService).createEdgeWithMetadata(
                eq("ua"), eq("ub"), eq(EdgeType.USER_DEFINED),
                eq(0.83),                 // weight = soft-truth value
                eq("Causes"),             // predicate → relationType
                any(),                    // description
                metaJson.capture(),
                eq(EdgeProvenance.INFERRED),
                eq(1L));
        assertTrue(metaJson.getValue().contains("INFERRED"), "edge metadata carries provenanceType=INFERRED");
        assertTrue(metaJson.getValue().contains("run-7"), "edge metadata carries the inference run id");
    }

    @Test
    void materialize_unaryAtom_setsInferredNodeAttribute() {
        GraphNode alice = GraphNode.builder().nodeId("ua").externalId("alice").nodeType(NodeLevel.ENTITY).build();
        when(graphService.getNodeByExternalIdInFactSheet(eq("alice"), any(), eq(1L))).thenReturn(Optional.of(alice));

        InferredFact fact = InferredFact.of("Fraudulent(alice)", 0.9, List.of(), List.of(), "run-1", 1L);

        var result = materializer.materialize(List.of(fact), 1L);

        assertEquals(1, result.attributesSet());
        assertEquals(0, result.edgesCreated());
        verify(graphService).updateNode(eq("ua"), isNull(), isNull(), anyMap());
    }

    @Test
    void materialize_unresolvableEndpoint_skipsRatherThanInventing() {
        when(graphService.getNodeByExternalIdInFactSheet(any(), any(), eq(1L))).thenReturn(Optional.empty());

        InferredFact fact = InferredFact.of("Causes(x, y)", 0.5, List.of(), List.of(), "run-1", 1L);

        var result = materializer.materialize(List.of(fact), 1L);

        assertEquals(0, result.edgesCreated());
        assertEquals(1, result.skipped());
    }
}
