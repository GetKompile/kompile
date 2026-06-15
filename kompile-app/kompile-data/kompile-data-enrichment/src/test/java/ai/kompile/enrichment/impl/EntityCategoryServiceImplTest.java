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
package ai.kompile.enrichment.impl;

import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.EntityCategory;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityCategoryServiceImplTest {

    @Mock
    private EntityCategoryRepository categoryRepository;

    @Mock
    private GraphNodeRepository nodeRepository;

    private EntityCategoryServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EntityCategoryServiceImpl(categoryRepository, nodeRepository, objectMapper);
    }

    // ─── toSlug ───────────────────────────────────────────────────────────────

    @Test
    void toSlugBasic() {
        assertEquals("financial-instruments", EntityCategoryServiceImpl.toSlug("Financial Instruments"));
    }

    @Test
    void toSlugSpecialChars() {
        // "Café & Résumé" → NFD normalization strips diacritics → "Cafe & Resume"
        // then non-alphanumeric collapses to "-" and leading/trailing stripped
        String result = EntityCategoryServiceImpl.toSlug("Café & Résumé");
        assertEquals("cafe-resume", result);
    }

    @Test
    void toSlugNull() {
        assertEquals("unknown", EntityCategoryServiceImpl.toSlug(null));
    }

    // ─── create ───────────────────────────────────────────────────────────────

    @Test
    void createCategoryGeneratesSlug() {
        Long factSheetId = 1L;

        when(categoryRepository.existsByFactSheetIdAndCategoryId(factSheetId, "my-category"))
                .thenReturn(false);
        when(categoryRepository.save(any(EntityCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EntityCategory result = service.create(factSheetId, "My Category", "desc", null, "#ff0000");

        assertEquals("my-category", result.getCategoryId());
        assertEquals("USER_DEFINED", result.getSource());
        assertEquals("My Category", result.getLabel());
    }

    @Test
    void createCategoryDeduplicatesSlug() {
        Long factSheetId = 1L;

        // Slug already exists → should append a UUID suffix
        when(categoryRepository.existsByFactSheetIdAndCategoryId(eq(factSheetId), eq("my-category")))
                .thenReturn(true);
        when(categoryRepository.save(any(EntityCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EntityCategory result = service.create(factSheetId, "My Category", null, null, null);

        assertNotEquals("my-category", result.getCategoryId(),
                "Duplicate slug should have a suffix appended");
        assertTrue(result.getCategoryId().startsWith("my-category-"),
                "Deduplicated slug should start with original slug followed by '-'");
    }

    // ─── update ───────────────────────────────────────────────────────────────

    @Test
    void updateCategoryChangesFields() {
        EntityCategory existing = EntityCategory.builder()
                .id(1L)
                .categoryId("finance")
                .label("Finance")
                .color("#aabbcc")
                .factSheetId(1L)
                .source("USER_DEFINED")
                .build();

        when(categoryRepository.findByCategoryId("finance")).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(EntityCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        EntityCategory result = service.update("finance", "Finance Updated", "New desc", null, "#112233");

        assertEquals("Finance Updated", result.getLabel());
        assertEquals("#112233", result.getColor());
        assertEquals("New desc", result.getDescription());
    }

    // ─── delete / uncategorize ────────────────────────────────────────────────

    @Test
    void deleteUncategorizesEntities() throws Exception {
        Long factSheetId = 1L;
        String catId = "finance";
        String catLabel = "Finance";

        EntityCategory cat = EntityCategory.builder()
                .id(1L).categoryId(catId).label(catLabel).factSheetId(factSheetId).source("USER_DEFINED").build();

        // Entity whose metadataJson has taxonomyCategory=Finance
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY).title("Bond").externalId("ext-1")
                .metadataJson("{\"taxonomyCategory\":\"Finance\",\"taxonomyDomain\":\"Finance\"}")
                .factSheetId(factSheetId).edgeCount(0).build();

        when(categoryRepository.findByCategoryId(catId)).thenReturn(Optional.of(cat));
        // getEntitiesInCategory calls findByFactSheetIdAndNodeType internally
        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(nodeRepository.save(any(GraphNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.delete(catId);

        ArgumentCaptor<GraphNode> savedCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(nodeRepository).save(savedCaptor.capture());
        GraphNode saved = savedCaptor.getValue();

        JsonNode meta = objectMapper.readTree(saved.getMetadataJson());
        assertFalse(meta.has("taxonomyCategory"), "taxonomyCategory should be removed after delete");
        assertFalse(meta.has("taxonomyDomain"), "taxonomyDomain should be removed after delete");
        verify(categoryRepository).deleteByCategoryId(catId);
    }

    // ─── assignEntitiesToCategory ─────────────────────────────────────────────

    @Test
    void assignEntitiesToCategoryWritesMetadata() throws Exception {
        Long factSheetId = 1L;
        String catId = "finance";
        String nodeId = UUID.randomUUID().toString();

        EntityCategory cat = EntityCategory.builder()
                .id(1L).categoryId(catId).label("Finance")
                .factSheetId(factSheetId).source("USER_DEFINED")
                .parentCategoryId(null)   // root — domain = own label
                .build();

        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY).title("Bond").externalId("ext-1")
                .factSheetId(factSheetId).edgeCount(0).build();

        when(categoryRepository.findByCategoryId(catId)).thenReturn(Optional.of(cat));
        when(nodeRepository.findByNodeId(nodeId)).thenReturn(Optional.of(entity));
        when(nodeRepository.save(any(GraphNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.assignEntitiesToCategory(catId, List.of(nodeId));

        ArgumentCaptor<GraphNode> savedCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(nodeRepository).save(savedCaptor.capture());
        GraphNode saved = savedCaptor.getValue();

        JsonNode meta = objectMapper.readTree(saved.getMetadataJson());
        assertEquals("Finance", meta.path("taxonomyCategory").asText());
        assertEquals("Finance", meta.path("taxonomyDomain").asText());
    }

    // ─── removeEntitiesFromCategory ───────────────────────────────────────────

    @Test
    void removeEntitiesFromCategoryRemovesMetadata() throws Exception {
        String nodeId = UUID.randomUUID().toString();

        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY).title("Bond").externalId("ext-1")
                .metadataJson("{\"taxonomyCategory\":\"Finance\",\"taxonomyDomain\":\"Finance\",\"entity_type\":\"BOND\"}")
                .factSheetId(1L).edgeCount(0).build();

        when(nodeRepository.findByNodeId(nodeId)).thenReturn(Optional.of(entity));
        when(nodeRepository.save(any(GraphNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.removeEntitiesFromCategory("any-cat", List.of(nodeId));

        ArgumentCaptor<GraphNode> savedCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(nodeRepository).save(savedCaptor.capture());
        GraphNode saved = savedCaptor.getValue();

        JsonNode meta = objectMapper.readTree(saved.getMetadataJson());
        assertFalse(meta.has("taxonomyCategory"), "taxonomyCategory should be removed");
        assertFalse(meta.has("taxonomyDomain"), "taxonomyDomain should be removed");
        // Other fields should be untouched
        assertEquals("BOND", meta.path("entity_type").asText(),
                "entity_type should remain after removing category");
    }

    // ─── importFromTaxonomy ───────────────────────────────────────────────────

    @Test
    void importFromTaxonomyCreatesAutoDiscovered() throws Exception {
        Long factSheetId = 5L;

        TaxonomyNode domain = TaxonomyNode.builder()
                .id("d1").label("Finance").level(TaxonomyNode.TaxonomyLevel.DOMAIN).build();
        TaxonomyNode category = TaxonomyNode.builder()
                .id("c1").label("Instruments").parentId("d1")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY)
                .entityTypes(List.of("BOND", "STOCK")).build();

        String json = objectMapper.writeValueAsString(List.of(domain, category));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(1L).factSheetId(factSheetId).taxonomyJson(json).version(1).build();

        // No existing categories → all new
        when(categoryRepository.findByFactSheetIdAndCategoryId(eq(factSheetId), anyString()))
                .thenReturn(Optional.empty());
        when(categoryRepository.save(any(EntityCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.importFromTaxonomy(factSheetId, taxonomy);

        ArgumentCaptor<EntityCategory> savedCaptor = ArgumentCaptor.forClass(EntityCategory.class);
        // domain and category nodes are saved (ENTITY_TYPE nodes are skipped)
        verify(categoryRepository, times(2)).save(savedCaptor.capture());

        List<EntityCategory> saved = savedCaptor.getAllValues();
        assertTrue(saved.stream().allMatch(c -> "AUTO_DISCOVERED".equals(c.getSource())),
                "Imported categories should have source=AUTO_DISCOVERED");
    }

    @Test
    void importFromTaxonomyPreservesUserDefined() throws Exception {
        Long factSheetId = 6L;

        TaxonomyNode domain = TaxonomyNode.builder()
                .id("d1").label("Finance").level(TaxonomyNode.TaxonomyLevel.DOMAIN).build();

        String json = objectMapper.writeValueAsString(List.of(domain));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(2L).factSheetId(factSheetId).taxonomyJson(json).version(1).build();

        // Existing USER_DEFINED category with slug "finance"
        EntityCategory existing = EntityCategory.builder()
                .id(10L).factSheetId(factSheetId)
                .categoryId("finance").label("Finance")
                .source("USER_DEFINED").build();

        when(categoryRepository.findByFactSheetIdAndCategoryId(factSheetId, "finance"))
                .thenReturn(Optional.of(existing));

        service.importFromTaxonomy(factSheetId, taxonomy);

        // USER_DEFINED category should NOT be saved/overwritten
        verify(categoryRepository, never()).save(any());
    }
}
