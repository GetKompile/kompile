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
package ai.kompile.enrichment.impl.search;

import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.TaxonomyNode;
import ai.kompile.enrichment.repository.DomainTaxonomyRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnrichmentSearchServiceTest {

    @Mock
    private DomainTaxonomyRepository taxonomyRepository;

    @Mock
    private GraphNodeRepository nodeRepository;

    private EnrichmentSearchService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EnrichmentSearchService(taxonomyRepository, nodeRepository, objectMapper);
    }

    // ─── browseTaxonomy ────────────────────────────────────────────────────────

    @Test
    void browseTaxonomyReturnsParsedNodes() throws Exception {
        Long factSheetId = 1L;

        TaxonomyNode domain = TaxonomyNode.builder()
                .id("d1")
                .label("Finance")
                .level(TaxonomyNode.TaxonomyLevel.DOMAIN)
                .build();
        TaxonomyNode category = TaxonomyNode.builder()
                .id("c1")
                .label("Instruments")
                .parentId("d1")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY)
                .entityTypes(List.of("BOND", "STOCK"))
                .build();

        String json = objectMapper.writeValueAsString(List.of(domain, category));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(1L).factSheetId(factSheetId).taxonomyJson(json).version(1).build();

        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId))
                .thenReturn(Optional.of(taxonomy));

        List<TaxonomyNode> result = service.browseTaxonomy(factSheetId);

        assertEquals(2, result.size(), "Should return both taxonomy nodes");
        assertEquals("Finance", result.get(0).getLabel());
        assertEquals("Instruments", result.get(1).getLabel());
    }

    @Test
    void browseTaxonomyEmptyWhenNone() {
        Long factSheetId = 99L;

        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId))
                .thenReturn(Optional.empty());

        List<TaxonomyNode> result = service.browseTaxonomy(factSheetId);

        assertTrue(result.isEmpty(), "Should return empty list when no taxonomy exists");
    }

    @Test
    void browseChildrenFiltersToParent() throws Exception {
        Long factSheetId = 2L;

        TaxonomyNode domain = TaxonomyNode.builder()
                .id("d1").label("HR").level(TaxonomyNode.TaxonomyLevel.DOMAIN).build();
        TaxonomyNode child1 = TaxonomyNode.builder()
                .id("c1").label("People").parentId("d1").level(TaxonomyNode.TaxonomyLevel.CATEGORY).build();
        TaxonomyNode child2 = TaxonomyNode.builder()
                .id("c2").label("Roles").parentId("d1").level(TaxonomyNode.TaxonomyLevel.CATEGORY).build();
        TaxonomyNode orphan = TaxonomyNode.builder()
                .id("c3").label("Finance").level(TaxonomyNode.TaxonomyLevel.DOMAIN).build();

        String json = objectMapper.writeValueAsString(List.of(domain, child1, child2, orphan));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(2L).factSheetId(factSheetId).taxonomyJson(json).version(1).build();

        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId))
                .thenReturn(Optional.of(taxonomy));

        List<TaxonomyNode> children = service.browseChildren(factSheetId, "d1");

        assertEquals(2, children.size(), "Should only return children of d1");
        assertTrue(children.stream().anyMatch(n -> "People".equals(n.getLabel())));
        assertTrue(children.stream().anyMatch(n -> "Roles".equals(n.getLabel())));
        assertFalse(children.stream().anyMatch(n -> "Finance".equals(n.getLabel())),
                "Finance has no parent, should not be returned as a child of d1");
    }

    // ─── searchByCategory ─────────────────────────────────────────────────────

    @Test
    void searchByCategoryFilters() {
        Long factSheetId = 3L;

        GraphNode finance1 = entityNode(1L, "Finance Bond Report", "{\"taxonomyCategory\":\"Finance\"}");
        GraphNode finance2 = entityNode(2L, "Finance Stock Report", "{\"taxonomyCategory\":\"Finance\"}");
        GraphNode hr = entityNode(3L, "HR People Report", "{\"taxonomyCategory\":\"HR\"}");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(finance1, finance2, hr));

        List<GraphNode> result = service.searchByCategory(factSheetId, "Finance", null, 100);

        assertEquals(2, result.size(), "Should return exactly 2 Finance entities");
        assertTrue(result.stream().allMatch(n -> n.getTitle().contains("Finance")));
    }

    @Test
    void searchByCategoryWithTextQuery() {
        Long factSheetId = 4L;

        GraphNode bond = entityNode(1L, "Bond Issuance Report", "{\"taxonomyCategory\":\"Finance\"}");
        GraphNode stock = entityNode(2L, "Stock Market Analysis", "{\"taxonomyCategory\":\"Finance\"}");
        GraphNode hrNode = entityNode(3L, "People Management", "{\"taxonomyCategory\":\"HR\"}");

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(bond, stock, hrNode));

        // Filter by category=Finance AND text containing "bond"
        List<GraphNode> result = service.searchByCategory(factSheetId, "Finance", "bond", 100);

        assertEquals(1, result.size(), "Should return only the bond entity matching both filters");
        assertEquals("Bond Issuance Report", result.get(0).getTitle());
    }

    // ─── getCategoryFacets ────────────────────────────────────────────────────

    @Test
    void getCategoryFacetsCountsCorrectly() {
        Long factSheetId = 5L;

        GraphNode f1 = entityNode(1L, "Finance Entity 1", "{\"taxonomyCategory\":\"Finance\"}");
        GraphNode f2 = entityNode(2L, "Finance Entity 2", "{\"taxonomyCategory\":\"Finance\"}");
        // No taxonomyCategory → Uncategorized
        GraphNode nocat = entityNode(3L, "Uncategorized Entity", null);

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(f1, f2, nocat));

        Map<String, Long> facets = service.getCategoryFacets(factSheetId);

        assertEquals(2L, facets.get("Finance"), "Expected 2 Finance entities");
        assertEquals(1L, facets.get("Uncategorized"), "Expected 1 Uncategorized entity");
        assertEquals(2, facets.size(), "Expected exactly 2 distinct category buckets");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private GraphNode entityNode(Long id, String title, String metadataJson) {
        return GraphNode.builder()
                .id(id)
                .nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title(title)
                .externalId("ext-" + id)
                .metadataJson(metadataJson)
                .edgeCount(0)
                .factSheetId(1L)
                .build();
    }
}
