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
package ai.kompile.enrichment.impl.organize;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityTypeAnalysisServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    private EntityTypeAnalysisService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EntityTypeAnalysisService(nodeRepository, edgeRepository, objectMapper);
    }

    // ─── getEntityTypeCounts ───────────────────────────────────────────────────

    @Test
    void analyzeEntityTypesCountsCorrectly() throws Exception {
        Long factSheetId = 1L;

        GraphNode person1 = entityNode(1L, "{\"entity_type\":\"PERSON\"}");
        GraphNode person2 = entityNode(2L, "{\"entity_type\":\"PERSON\"}");
        GraphNode org    = entityNode(3L, "{\"entity_type\":\"ORGANIZATION\"}");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(person1, person2, org));

        Map<String, Long> counts = service.getEntityTypeCounts(factSheetId);

        assertEquals(2L, counts.get("PERSON"), "Expected 2 PERSON entities");
        assertEquals(1L, counts.get("ORGANIZATION"), "Expected 1 ORGANIZATION entity");
        assertEquals(2, counts.size(), "Expected exactly 2 distinct entity types");
    }

    @Test
    void analyzeEntityTypesNoMetadata() throws Exception {
        Long factSheetId = 2L;

        // Entity with null metadataJson → no entity_type → counted as UNKNOWN
        GraphNode nullMeta = entityNode(1L, null);
        // Entity with blank entity_type → also UNKNOWN
        GraphNode blankType = entityNode(2L, "{\"entity_type\":\"\"}");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(nullMeta, blankType));

        Map<String, Long> counts = service.getEntityTypeCounts(factSheetId);

        // nullMeta: extractEntityType returns null → counted as UNKNOWN
        // blankType: entity_type is blank → also counted as UNKNOWN
        assertEquals(2L, counts.get("UNKNOWN"), "Both null and blank entity_type should be counted as UNKNOWN");
        assertFalse(counts.containsKey("PERSON"), "Should not contain PERSON");
    }

    // ─── getTypeCoOccurrence ───────────────────────────────────────────────────

    @Test
    void analyzeCoOccurrenceFindsConnectedTypes() throws Exception {
        Long factSheetId = 3L;

        GraphNode person = entityNode(10L, "{\"entity_type\":\"PERSON\"}");
        person.setNodeId("node-person");

        GraphNode org = entityNode(20L, "{\"entity_type\":\"ORGANIZATION\"}");
        org.setNodeId("node-org");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(person, org));

        // Edge connecting person → org
        GraphEdge edge = GraphEdge.builder()
                .id(100L)
                .edgeId(UUID.randomUUID().toString())
                .sourceNode(person)
                .targetNode(org)
                .edgeType(EdgeType.SHARED_ENTITY)
                .weight(1.0)
                .factSheetId(factSheetId)
                .build();

        // When we look up edges for person (id=10), return the edge
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(10L)).thenReturn(List.of(edge));
        // When we look up edges for org (id=20), return the same edge (bidirectional lookup)
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(20L)).thenReturn(List.of(edge));

        Map<String, Set<String>> coOccurrence = service.getTypeCoOccurrence(factSheetId);

        assertTrue(coOccurrence.containsKey("PERSON"), "PERSON should appear in co-occurrence map");
        assertTrue(coOccurrence.get("PERSON").contains("ORGANIZATION"),
                "PERSON should co-occur with ORGANIZATION");
        assertTrue(coOccurrence.containsKey("ORGANIZATION"), "ORGANIZATION should appear in co-occurrence map");
        assertTrue(coOccurrence.get("ORGANIZATION").contains("PERSON"),
                "ORGANIZATION should co-occur with PERSON");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private GraphNode entityNode(Long id, String metadataJson) {
        return GraphNode.builder()
                .id(id)
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("entity-" + id)
                .externalId("ext-" + id)
                .metadataJson(metadataJson)
                .edgeCount(0)
                .factSheetId(1L)
                .build();
    }
}
