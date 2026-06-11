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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.EntityCategory;
import ai.kompile.enrichment.domain.TaxonomyNode;
import ai.kompile.enrichment.repository.DomainTaxonomyRepository;
import ai.kompile.enrichment.repository.EntityCategoryRepository;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Louvain community detection + LLM → 3-level domain taxonomy.
 */
@Service
public class DomainTaxonomyDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(DomainTaxonomyDiscoveryService.class);

    private final EntityTypeAnalysisService typeAnalysisService;
    private final DomainTaxonomyRepository taxonomyRepository;
    private final EntityCategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private GraphAlgorithmService graphAlgorithmService;

    @Autowired(required = false)
    private LLMChat llmChat;

    public DomainTaxonomyDiscoveryService(EntityTypeAnalysisService typeAnalysisService,
                                          DomainTaxonomyRepository taxonomyRepository,
                                          EntityCategoryRepository categoryRepository,
                                          ObjectMapper objectMapper) {
        this.typeAnalysisService = typeAnalysisService;
        this.taxonomyRepository = taxonomyRepository;
        this.categoryRepository = categoryRepository;
        this.objectMapper = objectMapper;
    }

    public DomainTaxonomy discoverTaxonomy(Long factSheetId, EnrichmentConfig config) {
        Map<String, Long> typeCounts = typeAnalysisService.getEntityTypeCounts(factSheetId);
        Map<String, Set<String>> coOccurrence = typeAnalysisService.getTypeCoOccurrence(factSheetId);

        // Run Louvain community detection
        Map<String, Integer> communityAssignments = Collections.emptyMap();
        if (graphAlgorithmService != null) {
            try {
                communityAssignments = graphAlgorithmService.louvainCommunities(factSheetId, 20);
            } catch (Exception e) {
                log.warn("Louvain community detection failed, proceeding without: {}", e.getMessage());
            }
        }

        // Load existing user-defined categories
        List<EntityCategory> existingUserCategories = categoryRepository
                .findByFactSheetIdAndSource(factSheetId, "USER_DEFINED");

        // Build taxonomy via LLM
        List<TaxonomyNode> taxonomyNodes = generateTaxonomyViaLLM(
                typeCounts, coOccurrence, communityAssignments, existingUserCategories, config);

        // Compute version
        int nextVersion = taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId)
                .map(t -> t.getVersion() + 1)
                .orElse(1);

        // Persist
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .factSheetId(factSheetId)
                .version(nextVersion)
                .build();
        try {
            taxonomy.setTaxonomyJson(objectMapper.writeValueAsString(taxonomyNodes));
        } catch (Exception e) {
            log.error("Failed to serialize taxonomy nodes: {}", e.getMessage());
            taxonomy.setTaxonomyJson("[]");
        }
        taxonomy = taxonomyRepository.save(taxonomy);

        log.info("Discovered taxonomy v{} for factSheet {} with {} nodes",
                nextVersion, factSheetId, taxonomyNodes.size());
        return taxonomy;
    }

    private List<TaxonomyNode> generateTaxonomyViaLLM(Map<String, Long> typeCounts,
                                                       Map<String, Set<String>> coOccurrence,
                                                       Map<String, Integer> communities,
                                                       List<EntityCategory> existingUserCategories,
                                                       EnrichmentConfig config) {
        if (llmChat == null) {
            log.warn("LLMChat not available, generating placeholder taxonomy from entity types");
            return generateFallbackTaxonomy(typeCounts);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Given the following entity types extracted from a document collection:\n");
        typeCounts.forEach((type, count) ->
                prompt.append(String.format("  %s: %d entities\n", type, count)));

        if (!coOccurrence.isEmpty()) {
            prompt.append("\nEntity type co-occurrence (types frequently connected by relationships):\n");
            coOccurrence.forEach((type, neighbors) ->
                    prompt.append(String.format("  %s <-> %s\n", type, String.join(", ", neighbors))));
        }

        if (!communities.isEmpty()) {
            // Group entity types by community
            Map<Integer, Set<String>> communityTypes = new HashMap<>();
            communities.forEach((nodeId, community) ->
                    communityTypes.computeIfAbsent(community, k -> new LinkedHashSet<>()));
            prompt.append("\nCommunity clusters detected (Louvain algorithm): ")
                    .append(communityTypes.size()).append(" communities\n");
        }

        if (!existingUserCategories.isEmpty()) {
            prompt.append("\nExisting user-defined categories (DO NOT remove or rename these):\n");
            for (EntityCategory cat : existingUserCategories) {
                prompt.append(String.format("  %s: %s\n", cat.getLabel(),
                        cat.getDescription() != null ? cat.getDescription() : ""));
            }
        }

        prompt.append("\nGenerate a domain taxonomy with 3 levels:\n");
        prompt.append("- DOMAIN: broad business domains\n");
        prompt.append("- CATEGORY: functional categories within a domain\n");
        prompt.append("- ENTITY_TYPE: the leaf entity types from the input, assigned to exactly one category\n");
        prompt.append("- Preserve all user-defined categories exactly as given; add new ones as needed\n");
        prompt.append("- Maximum depth: ").append(config.getTaxonomyMaxDepth()).append("\n");
        prompt.append("- Only include entity types with ≥").append(config.getTaxonomyMinEntityTypeCount()).append(" entities\n");
        prompt.append("\nReturn ONLY a JSON array of TaxonomyNode objects with NO markdown formatting:\n");
        prompt.append("[{\"id\": \"...\", \"label\": \"...\", \"description\": \"...\", \"parentId\": null, ");
        prompt.append("\"level\": \"DOMAIN\"|\"CATEGORY\"|\"ENTITY_TYPE\", \"entityTypes\": [\"...\", ...]}]\n");

        try {
            String response = llmChat.prompt()
                    .system("You are a data taxonomy expert. Return ONLY valid JSON arrays, no explanatory text.")
                    .user(prompt.toString())
                    .call()
                    .content();

            // Strip any markdown code fences
            response = response.trim();
            if (response.startsWith("```")) {
                response = response.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            return objectMapper.readValue(response, new TypeReference<List<TaxonomyNode>>() {});
        } catch (Exception e) {
            log.error("LLM taxonomy generation failed, using fallback: {}", e.getMessage());
            return generateFallbackTaxonomy(typeCounts);
        }
    }

    private List<TaxonomyNode> generateFallbackTaxonomy(Map<String, Long> typeCounts) {
        List<TaxonomyNode> nodes = new ArrayList<>();
        String domainId = "domain-general";
        nodes.add(TaxonomyNode.builder()
                .id(domainId)
                .label("General")
                .description("Auto-generated domain for all entity types")
                .level(TaxonomyNode.TaxonomyLevel.DOMAIN)
                .entityTypes(new ArrayList<>(typeCounts.keySet()))
                .build());

        for (Map.Entry<String, Long> entry : typeCounts.entrySet()) {
            nodes.add(TaxonomyNode.builder()
                    .id("type-" + entry.getKey().toLowerCase().replace(" ", "-"))
                    .label(entry.getKey())
                    .description("Entity type: " + entry.getKey())
                    .parentId(domainId)
                    .level(TaxonomyNode.TaxonomyLevel.ENTITY_TYPE)
                    .entityTypes(List.of(entry.getKey()))
                    .entityCount(entry.getValue())
                    .build());
        }
        return nodes;
    }
}
