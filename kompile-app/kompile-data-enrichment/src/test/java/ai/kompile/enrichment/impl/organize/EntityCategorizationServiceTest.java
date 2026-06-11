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

import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.TaxonomyNode;
import ai.kompile.enrichment.repository.EntityCategoryRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityCategorizationServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private EntityCategoryRepository categoryRepository;

    private EntityCategorizationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EntityCategorizationService(nodeRepository, categoryRepository, objectMapper);
    }

    // ─── categorizeEntities ────────────────────────────────────────────────────

    @Test
    void categorizeEntitiesWritesMetadata() throws Exception {
        Long factSheetId = 1L;

        // Taxonomy: domain "HR" → category "People" → entity type "PERSON"
        TaxonomyNode domain = TaxonomyNode.builder()
                .id("domain-hr")
                .label("HR")
                .level(TaxonomyNode.TaxonomyLevel.DOMAIN)
                .build();
        TaxonomyNode category = TaxonomyNode.builder()
                .id("cat-people")
                .label("People")
                .parentId("domain-hr")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY)
                .entityTypes(List.of("PERSON"))
                .build();

        String taxonomyJson = objectMapper.writeValueAsString(List.of(domain, category));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(1L)
                .factSheetId(factSheetId)
                .taxonomyJson(taxonomyJson)
                .version(1)
                .build();

        GraphNode entity = entityNode(1L, "{\"entity_type\":\"PERSON\"}");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(categoryRepository.findByFactSheetIdOrderBySortOrder(factSheetId))
                .thenReturn(List.of());
        when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.categorizeEntities(factSheetId, taxonomy);

        assertEquals(1, count, "One entity should have been categorized");

        ArgumentCaptor<List<GraphNode>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(savedCaptor.capture());
        GraphNode saved = savedCaptor.getValue().get(0);

        JsonNode meta = objectMapper.readTree(saved.getMetadataJson());
        assertEquals("People", meta.path("taxonomyCategory").asText(),
                "taxonomyCategory should be set to the category label 'People'");
        assertEquals("HR", meta.path("taxonomyDomain").asText(),
                "taxonomyDomain should be set to the domain label 'HR'");
    }

    @Test
    void categorizeEntitiesHandlesUnknownType() throws Exception {
        Long factSheetId = 2L;

        // Taxonomy with no entry for ROBOT
        TaxonomyNode domain = TaxonomyNode.builder()
                .id("domain-hr")
                .label("HR")
                .level(TaxonomyNode.TaxonomyLevel.DOMAIN)
                .build();
        TaxonomyNode category = TaxonomyNode.builder()
                .id("cat-people")
                .label("People")
                .parentId("domain-hr")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY)
                .entityTypes(List.of("PERSON"))
                .build();

        String taxonomyJson = objectMapper.writeValueAsString(List.of(domain, category));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(2L)
                .factSheetId(factSheetId)
                .taxonomyJson(taxonomyJson)
                .version(1)
                .build();

        // Entity with unknown type ROBOT — not in taxonomy
        GraphNode entity = entityNode(1L, "{\"entity_type\":\"ROBOT\"}");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(categoryRepository.findByFactSheetIdOrderBySortOrder(factSheetId))
                .thenReturn(List.of());
        when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.categorizeEntities(factSheetId, taxonomy);

        assertEquals(1, count, "Unknown-type entity should still be categorized as Uncategorized");

        ArgumentCaptor<List<GraphNode>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(savedCaptor.capture());
        GraphNode saved = savedCaptor.getValue().get(0);

        JsonNode meta = objectMapper.readTree(saved.getMetadataJson());
        assertEquals("Uncategorized", meta.path("taxonomyCategory").asText(),
                "Unknown entity type should fall back to taxonomyCategory=Uncategorized");
    }

    @Test
    void categorizeEntitiesNullTaxonomy() {
        Long factSheetId = 3L;

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entityNode(1L, "{\"entity_type\":\"PERSON\"}")));
        when(categoryRepository.findByFactSheetIdOrderBySortOrder(factSheetId))
                .thenReturn(List.of());
        when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // null taxonomy → all entities fall to Uncategorized, still categorized
        int count = service.categorizeEntities(factSheetId, null);

        // Service categorizes everything to Uncategorized even when taxonomy is null
        assertEquals(1, count, "Entities should be categorized as Uncategorized when taxonomy is null");
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
