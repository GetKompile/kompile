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

import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityNormalizationServiceTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EnrichmentAuditService auditService;

    private EntityNormalizationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EntityNormalizationService(knowledgeGraphService, auditService, objectMapper);
    }

    private static final EnrichmentConfig DEFAULT_CONFIG = EnrichmentConfig.builder().build();

    // ─── Title case normalization ──────────────────────────────────────────────

    @Test
    void normalizeTitleCaseForPerson() throws Exception {
        Long factSheetId = 1L;
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_type", "PERSON");
        String nodeId = UUID.randomUUID().toString();
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title("john doe")
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-1").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-1", DEFAULT_CONFIG);

        assertEquals(1, normalized);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).updateNode(eq(nodeId), titleCaptor.capture(), any(), metaCaptor.capture());
        assertEquals("John Doe", titleCaptor.getValue());
    }

    @Test
    void normalizeTitleCaseForOrganization() throws Exception {
        Long factSheetId = 2L;
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_type", "ORGANIZATION");
        String nodeId = UUID.randomUUID().toString();
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title("ACME CORP")
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-2").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-2", DEFAULT_CONFIG);

        assertEquals(1, normalized);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).updateNode(eq(nodeId), titleCaptor.capture(), any(), metaCaptor.capture());
        assertEquals("Acme Corp", titleCaptor.getValue());
    }

    @Test
    void normalizeRemovesControlChars() throws Exception {
        Long factSheetId = 3L;
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_type", "PERSON");
        // Title with a control char (ASCII 0x01 = SOH)
        String titleWithControl = "JohnDoe";
        String nodeId = UUID.randomUUID().toString();
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title(titleWithControl)
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-3").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-3", DEFAULT_CONFIG);

        assertEquals(1, normalized);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).updateNode(eq(nodeId), titleCaptor.capture(), any(), metaCaptor.capture());
        assertFalse(titleCaptor.getValue().contains(""),
                "Control character should be stripped from title");
    }

    @Test
    void normalizeSkipsNonTitleCaseTypes() throws Exception {
        Long factSheetId = 4L;
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_type", "DOCUMENT");
        String originalTitle = "annual REPORT 2024";
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title(originalTitle)
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-4").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-4", DEFAULT_CONFIG);

        // DOCUMENT type is not in TITLE_CASE_TYPES — title should not be title-cased.
        // No updateNode should happen because the title already matches its cleaned form.
        assertEquals(0, normalized, "DOCUMENT entity type should not trigger title-casing");
        verify(knowledgeGraphService, never()).updateNode(any(), any(), any(), any());
    }

    // ─── Alias deduplication ───────────────────────────────────────────────────

    @Test
    void normalizeDeduplicatesAliases() throws Exception {
        Long factSheetId = 5L;
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_type", "ORGANIZATION");
        ArrayNode aliases = meta.putArray("aliases");
        aliases.add("Acme");
        aliases.add("acme");
        aliases.add("ACME");

        String nodeId = UUID.randomUUID().toString();
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title("Acme Corp")  // already title-cased, no title change
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-5").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-5", DEFAULT_CONFIG);

        assertEquals(1, normalized, "Alias deduplication should count as a normalization change");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService).updateNode(eq(nodeId), any(), any(), metaCaptor.capture());

        // After dedup: only one unique alias (case-insensitive), first form "Acme" kept
        Map<String, Object> savedMeta = metaCaptor.getValue();
        @SuppressWarnings("unchecked")
        List<String> savedAliases = (List<String>) savedMeta.get("aliases");
        assertNotNull(savedAliases, "aliases key should be present in persisted metadata");
        assertEquals(1, savedAliases.size(),
                "Duplicate aliases (case-insensitive) should be collapsed to one entry");
        assertEquals("Acme", savedAliases.get(0),
                "First occurrence should be kept");
    }

    // ─── Entity type inference ─────────────────────────────────────────────────

    @Test
    void normalizeInfersEntityType() throws Exception {
        Long factSheetId = 6L;
        // metadataJson exists but lacks entity_type
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("source", "llm");
        // description contains the keyword "company" — should infer ORGANIZATION
        String nodeId = UUID.randomUUID().toString();
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title("Globex")
                .description("Globex is a company specializing in widgets")
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-6").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-6", DEFAULT_CONFIG);

        assertEquals(1, normalized, "Missing entity_type should be inferred and counted as a change");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeGraphService).updateNode(eq(nodeId), any(), any(), metaCaptor.capture());
        Map<String, Object> savedMeta = metaCaptor.getValue();
        assertEquals("ORGANIZATION", savedMeta.get("entity_type"),
                "entity_type should be inferred as ORGANIZATION from description keyword");
    }

    // ─── No-change path ────────────────────────────────────────────────────────

    @Test
    void normalizeNoChangesReturnsZero() throws Exception {
        Long factSheetId = 7L;
        // Entity is already clean: proper title, unique alias, entity_type present
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_type", "PERSON");
        ArrayNode aliases = meta.putArray("aliases");
        aliases.add("Jane Smith");

        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("Jane Smith")  // already title-cased for PERSON
                .description("a person")
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-7").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-7", DEFAULT_CONFIG);

        assertEquals(0, normalized, "Already-clean entity should not be counted as normalized");
        verify(knowledgeGraphService, never()).updateNode(any(), any(), any(), any());
    }

    // ─── entity_category recognized as semantic type ───────────────────────────

    /**
     * Verifies the fix in EntityNormalizationService / GraphNodeTypes.resolveEntityType:
     * when a node's semantic type is stored under metadata key "entity_category" (not "entity_type"),
     * the service must still (a) apply title-casing for PERSON nodes, and (b) promote the
     * category value into "entity_type" in the persisted metadata.
     */
    @Test
    void normalizeRecognizesEntityCategory() throws Exception {
        Long factSheetId = 8L;
        // Deliberately set entity_category instead of entity_type — this is the producer variant
        // that was previously invisible to the normalizer.
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("entity_category", "PERSON");
        // No "entity_type" key present.

        String nodeId = UUID.randomUUID().toString();
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title("alice brown")   // lowercase — PERSON should trigger title-casing
                .metadataJson(objectMapper.writeValueAsString(meta))
                .externalId("ext-8").factSheetId(factSheetId).build();

        when(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));

        int normalized = service.normalize(factSheetId, "job-8", DEFAULT_CONFIG);

        // At minimum one change: title-casing "alice brown" → "Alice Brown".
        // The service may also write entity_type from entity_category.
        assertEquals(1, normalized,
                "Node with entity_category=PERSON and lowercase title should be normalized");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService).updateNode(eq(nodeId), titleCaptor.capture(), any(), metaCaptor.capture());

        // Title must be title-cased — entity_category=PERSON is in TITLE_CASE_TYPES.
        assertEquals("Alice Brown", titleCaptor.getValue(),
                "PERSON entity (via entity_category) should have its title title-cased");

        // entity_type must be populated from the category so downstream consumers see it.
        Map<String, Object> savedMeta = metaCaptor.getValue();
        String resolvedType = (String) savedMeta.get("entity_type");
        assertEquals("PERSON", resolvedType,
                "entity_type should be promoted from entity_category=PERSON in the persisted metadata");
    }
}
