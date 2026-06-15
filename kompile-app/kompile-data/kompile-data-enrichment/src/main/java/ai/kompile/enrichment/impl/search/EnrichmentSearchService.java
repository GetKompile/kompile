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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Taxonomy-aware search: browse by category, search with category filter, facets.
 */
@Service
public class EnrichmentSearchService {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentSearchService.class);

    private final DomainTaxonomyRepository taxonomyRepository;
    private final GraphNodeRepository nodeRepository;
    private final ObjectMapper objectMapper;

    public EnrichmentSearchService(DomainTaxonomyRepository taxonomyRepository,
                                   GraphNodeRepository nodeRepository,
                                   ObjectMapper objectMapper) {
        this.taxonomyRepository = taxonomyRepository;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Browse the full taxonomy tree.
     */
    public List<TaxonomyNode> browseTaxonomy(Long factSheetId) {
        return taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId)
                .map(this::parseTaxonomy)
                .orElse(List.of());
    }

    /**
     * Browse children of a specific taxonomy node.
     */
    public List<TaxonomyNode> browseChildren(Long factSheetId, String parentId) {
        List<TaxonomyNode> all = browseTaxonomy(factSheetId);
        if (parentId == null) {
            return all.stream()
                    .filter(n -> n.getParentId() == null)
                    .collect(Collectors.toList());
        }
        return all.stream()
                .filter(n -> parentId.equals(n.getParentId()))
                .collect(Collectors.toList());
    }

    /**
     * Search entities by category and/or text query.
     */
    public List<GraphNode> searchByCategory(Long factSheetId, String categoryLabel, String query, int maxResults) {
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);

        return entities.stream()
                .filter(e -> {
                    if (categoryLabel != null && !categoryLabel.isBlank()) {
                        String category = extractMetadataField(e, "taxonomyCategory");
                        if (!categoryLabel.equals(category)) return false;
                    }
                    if (query != null && !query.isBlank()) {
                        String q = query.toLowerCase();
                        return (e.getTitle() != null && e.getTitle().toLowerCase().contains(q)) ||
                                (e.getDescription() != null && e.getDescription().toLowerCase().contains(q));
                    }
                    return true;
                })
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Get category facets (distinct taxonomyCategory values with counts).
     */
    public Map<String, Long> getCategoryFacets(Long factSheetId) {
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
        Map<String, Long> facets = new LinkedHashMap<>();
        for (GraphNode entity : entities) {
            String category = extractMetadataField(entity, "taxonomyCategory");
            if (category != null && !category.isBlank()) {
                facets.merge(category, 1L, Long::sum);
            } else {
                facets.merge("Uncategorized", 1L, Long::sum);
            }
        }
        return facets;
    }

    /**
     * Get entity type facets for dual-axis filtering.
     */
    public Map<String, Long> getEntityTypeFacets(Long factSheetId) {
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
        Map<String, Long> facets = new LinkedHashMap<>();
        for (GraphNode entity : entities) {
            String type = extractMetadataField(entity, "entity_type");
            if (type != null && !type.isBlank()) {
                facets.merge(type, 1L, Long::sum);
            } else {
                facets.merge("UNKNOWN", 1L, Long::sum);
            }
        }
        return facets;
    }

    private List<TaxonomyNode> parseTaxonomy(DomainTaxonomy taxonomy) {
        try {
            return objectMapper.readValue(taxonomy.getTaxonomyJson(), new TypeReference<List<TaxonomyNode>>() {});
        } catch (Exception e) {
            log.error("Failed to parse taxonomy JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractMetadataField(GraphNode node, String field) {
        if (node.getMetadataJson() == null) return null;
        try {
            JsonNode meta = objectMapper.readTree(node.getMetadataJson());
            JsonNode value = meta.path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
