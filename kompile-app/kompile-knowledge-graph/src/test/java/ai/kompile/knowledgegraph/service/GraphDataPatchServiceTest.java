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
package ai.kompile.knowledgegraph.service;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphDataPatchServiceTest {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Mock
    private GraphNodeRepository nodeRepository;

    private ObjectMapper objectMapper;
    private GraphDataPatchService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new GraphDataPatchService(nodeRepository, objectMapper);
    }

    @Test
    void patchNodeMetadata_dryRunReturnsSamplesWithoutSaving() throws Exception {
        GraphNode matching = node("n1", "Gross Margin", NodeLevel.ENTITY, 1L,
                Map.of("entity_type", "APPROVAL_ROLE", "legacy", true));
        GraphNode unrelated = node("n2", "Revenue", NodeLevel.ENTITY, 1L,
                Map.of("entity_type", "APPROVAL_ROLE"));
        when(nodeRepository.findByFactSheetIdAndNodeType(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(matching, unrelated));

        GraphDataPatchService.PatchResult result = service.patchNodeMetadata(new GraphDataPatchService.PatchRequest(
                1L,
                false,
                true,
                null,
                List.of(new GraphDataPatchService.PatchRule(
                        "custom-category",
                        "ENTITY",
                        "APPROVAL_ROLE",
                        List.of("Gross Margin"),
                        null,
                        Map.of(),
                        List.of(),
                        Map.of("entity_category", "FREE_CASH_FLOW_MARGIN"),
                        List.of("legacy")))));

        assertTrue(result.dryRun());
        assertEquals(2, result.scannedCount());
        assertEquals(1, result.matchedCount());
        assertEquals(1, result.changedCount());
        assertEquals(0, result.updatedCount());
        assertEquals(0, result.unchangedCount());
        assertEquals(1, result.samples().size());
        assertTrue(result.samples().get(0).beforeMetadata().containsKey("legacy"));
        assertFalse(result.samples().get(0).afterMetadata().containsKey("legacy"));
        assertEquals("FREE_CASH_FLOW_MARGIN",
                result.samples().get(0).afterMetadata().get("entity_category"));
        assertTrue(readMetadata(matching).containsKey("legacy"));
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void patchNodeMetadata_appliesScopedMetadataPatch() throws Exception {
        GraphNode matching = node("n1", "Gross Margin", NodeLevel.ENTITY, 1L,
                Map.of("entity_type", "APPROVAL_ROLE"));
        when(nodeRepository.findByFactSheetIdAndNodeType(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(matching));
        when(nodeRepository.save(any(GraphNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GraphDataPatchService.PatchResult result = service.patchNodeMetadata(new GraphDataPatchService.PatchRequest(
                1L,
                false,
                false,
                null,
                List.of(new GraphDataPatchService.PatchRule(
                        "custom-category",
                        "ENTITY",
                        "APPROVAL_ROLE",
                        List.of("Gross Margin"),
                        null,
                        Map.of(),
                        List.of(),
                        Map.of("custom_category", "FREE_CASH_FLOW_MARGIN"),
                        List.of()))));

        assertEquals(1, result.matchedCount());
        assertEquals(1, result.changedCount());
        assertEquals(1, result.updatedCount());
        assertEquals("FREE_CASH_FLOW_MARGIN", readMetadata(matching).get("custom_category"));
        verify(nodeRepository).save(matching);
    }

    @Test
    void patchNodeMetadata_requiresFactSheetUnlessGlobalIsExplicit() {
        GraphDataPatchService.PatchRequest request = new GraphDataPatchService.PatchRequest(
                null,
                false,
                false,
                null,
                List.of(new GraphDataPatchService.PatchRule(
                        "custom-category",
                        "ENTITY",
                        null,
                        List.of("Gross Margin"),
                        null,
                        Map.of(),
                        List.of(),
                        Map.of("entity_category", "FREE_CASH_FLOW_MARGIN"),
                        List.of())));

        assertThrows(IllegalArgumentException.class, () -> service.patchNodeMetadata(request));
        verifyNoInteractions(nodeRepository);
    }

    @Test
    void patchNodeMetadata_supportsGlobalOptInAndNestedMetadataPaths() throws Exception {
        GraphNode formulaCell = node("n1", "F12", NodeLevel.ENTITY, null,
                Map.of("properties", Map.of(
                        "entity_subtype", "formula_cell",
                        "stale", true)));
        when(nodeRepository.findByNodeType(NodeLevel.ENTITY)).thenReturn(List.of(formulaCell));
        when(nodeRepository.save(any(GraphNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GraphDataPatchService.PatchResult result = service.patchNodeMetadata(new GraphDataPatchService.PatchRequest(
                null,
                true,
                false,
                null,
                List.of(new GraphDataPatchService.PatchRule(
                        "nested-custom-category",
                        "ENTITY",
                        null,
                        List.of(),
                        null,
                        Map.of("properties.entity_subtype", "formula_cell"),
                        List.of("properties.stale"),
                        Map.of("properties.custom_category", "FPNA_FORMULA_CELL"),
                        List.of("properties.stale")))));

        assertTrue(result.allowGlobal());
        assertEquals(1, result.scannedCount());
        assertEquals(1, result.updatedCount());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) readMetadata(formulaCell).get("properties");
        assertEquals("FPNA_FORMULA_CELL", properties.get("custom_category"));
        assertFalse(properties.containsKey("stale"));
    }

    private GraphNode node(String nodeId, String title, NodeLevel nodeLevel, Long factSheetId,
                           Map<String, Object> metadata) throws Exception {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        node.setExternalId("ext-" + nodeId);
        node.setNodeType(nodeLevel);
        node.setTitle(title);
        node.setFactSheetId(factSheetId);
        node.setMetadataJson(objectMapper.writeValueAsString(metadata));
        return node;
    }

    private LinkedHashMap<String, Object> readMetadata(GraphNode node) throws Exception {
        return objectMapper.readValue(node.getMetadataJson(), MAP_TYPE);
    }
}
