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
package ai.kompile.enrichment.impl.taxonomy;

import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.TaxonomyNode;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.StepType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps taxonomy domains to ProcessDefinition/ProcessPhase/ProcessStep.
 * Each top-level domain becomes a ProcessDefinition with categories as phases
 * and entity types as steps.
 */
@Service
public class TaxonomyProcessDefinitionService {
    private static final Logger log = LoggerFactory.getLogger(TaxonomyProcessDefinitionService.class);

    private final ObjectMapper objectMapper;

    public TaxonomyProcessDefinitionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Generate ProcessDefinition objects from the taxonomy.
     */
    public List<ProcessDefinition> generateProcessDefinitions(DomainTaxonomy taxonomy) {
        if (taxonomy == null || taxonomy.getTaxonomyJson() == null) return List.of();

        try {
            List<TaxonomyNode> nodes = objectMapper.readValue(
                    taxonomy.getTaxonomyJson(), new TypeReference<List<TaxonomyNode>>() {});

            // Group by parent
            Map<String, List<TaxonomyNode>> childrenOf = new HashMap<>();
            Map<String, TaxonomyNode> nodeMap = new HashMap<>();
            List<TaxonomyNode> domains = new ArrayList<>();

            for (TaxonomyNode node : nodes) {
                nodeMap.put(node.getId(), node);
                if (node.getLevel() == TaxonomyNode.TaxonomyLevel.DOMAIN) {
                    domains.add(node);
                }
                if (node.getParentId() != null) {
                    childrenOf.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
                }
            }

            List<ProcessDefinition> definitions = new ArrayList<>();
            for (TaxonomyNode domain : domains) {
                ProcessDefinition pd = buildProcessDefinition(domain, childrenOf);
                definitions.add(pd);
            }

            log.info("Generated {} process definitions from taxonomy v{}", definitions.size(), taxonomy.getVersion());
            return definitions;
        } catch (Exception e) {
            log.error("Failed to generate process definitions from taxonomy: {}", e.getMessage());
            return List.of();
        }
    }

    private ProcessDefinition buildProcessDefinition(TaxonomyNode domain,
                                                     Map<String, List<TaxonomyNode>> childrenOf) {
        List<TaxonomyNode> categories = childrenOf.getOrDefault(domain.getId(), List.of());

        List<ProcessPhase> phases = new ArrayList<>();
        int phaseOrder = 1;
        for (TaxonomyNode category : categories) {
            List<TaxonomyNode> entityTypes = childrenOf.getOrDefault(category.getId(), List.of());
            List<ProcessStep> steps = new ArrayList<>();
            int stepOrder = 1;
            for (TaxonomyNode entityType : entityTypes) {
                steps.add(ProcessStep.builder()
                        .id("step-" + entityType.getId())
                        .name(entityType.getLabel())
                        .description("Process entity type: " + entityType.getLabel())
                        .stepType(StepType.AUTO)
                        .metadata(Map.of("entityTypes",
                                entityType.getEntityTypes() != null ? entityType.getEntityTypes() : List.of()))
                        .build());
                stepOrder++;
            }

            phases.add(ProcessPhase.builder()
                    .id("phase-" + category.getId())
                    .name(category.getLabel())
                    .order(phaseOrder++)
                    .steps(steps)
                    .build());
        }

        return ProcessDefinition.builder()
                .id("pd-" + domain.getId())
                .name(domain.getLabel() + " Process")
                .version(1)
                .status(ProcessStatus.DRAFT)
                .phases(phases)
                .metadata(Map.of(
                        "source", "taxonomy-auto-generated",
                        "domainId", domain.getId(),
                        "domainDescription", domain.getDescription() != null ? domain.getDescription() : ""
                ))
                .build();
    }
}
