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
import ai.kompile.process.workflow.ProcessStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaxonomyProcessDefinitionServiceTest {

    private TaxonomyProcessDefinitionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new TaxonomyProcessDefinitionService(objectMapper);
    }

    // ─── generateProcessDefinitions ───────────────────────────────────────────

    @Test
    void generateProcessDefinitionsFromTaxonomy() throws Exception {
        // One domain → 2 categories, each with 2 entity type nodes
        TaxonomyNode domain = TaxonomyNode.builder()
                .id("d1").label("Finance").level(TaxonomyNode.TaxonomyLevel.DOMAIN).build();

        TaxonomyNode cat1 = TaxonomyNode.builder()
                .id("c1").label("Instruments").parentId("d1")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY).build();
        TaxonomyNode cat2 = TaxonomyNode.builder()
                .id("c2").label("Counterparties").parentId("d1")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY).build();

        TaxonomyNode et1a = TaxonomyNode.builder()
                .id("et1a").label("Bond").parentId("c1")
                .level(TaxonomyNode.TaxonomyLevel.ENTITY_TYPE)
                .entityTypes(List.of("BOND")).build();
        TaxonomyNode et1b = TaxonomyNode.builder()
                .id("et1b").label("Stock").parentId("c1")
                .level(TaxonomyNode.TaxonomyLevel.ENTITY_TYPE)
                .entityTypes(List.of("STOCK")).build();

        TaxonomyNode et2a = TaxonomyNode.builder()
                .id("et2a").label("Bank").parentId("c2")
                .level(TaxonomyNode.TaxonomyLevel.ENTITY_TYPE)
                .entityTypes(List.of("BANK")).build();
        TaxonomyNode et2b = TaxonomyNode.builder()
                .id("et2b").label("Broker").parentId("c2")
                .level(TaxonomyNode.TaxonomyLevel.ENTITY_TYPE)
                .entityTypes(List.of("BROKER")).build();

        String json = objectMapper.writeValueAsString(
                List.of(domain, cat1, cat2, et1a, et1b, et2a, et2b));

        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(1L).factSheetId(10L).taxonomyJson(json).version(1).build();

        List<ProcessDefinition> definitions = service.generateProcessDefinitions(taxonomy);

        assertEquals(1, definitions.size(), "One domain → one ProcessDefinition");

        ProcessDefinition pd = definitions.get(0);
        assertEquals(2, pd.getPhases().size(), "Two categories → two ProcessPhases");

        for (ProcessPhase phase : pd.getPhases()) {
            assertEquals(2, phase.getSteps().size(),
                    "Each category has 2 entity types → 2 steps per phase");
        }
    }

    @Test
    void generateProcessDefinitionsNullTaxonomy() {
        List<ProcessDefinition> result = service.generateProcessDefinitions(null);

        assertTrue(result.isEmpty(), "Null taxonomy should produce empty list");
    }

    @Test
    void generateProcessDefinitionsEmptyJson() throws Exception {
        String json = objectMapper.writeValueAsString(List.of());
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(2L).factSheetId(10L).taxonomyJson(json).version(1).build();

        List<ProcessDefinition> result = service.generateProcessDefinitions(taxonomy);

        assertTrue(result.isEmpty(), "Empty taxonomy JSON array should produce empty list");
    }

    @Test
    void processDefinitionHasCorrectMetadata() throws Exception {
        TaxonomyNode domain = TaxonomyNode.builder()
                .id("d-finance")
                .label("Finance")
                .description("Financial domain")
                .level(TaxonomyNode.TaxonomyLevel.DOMAIN)
                .build();

        TaxonomyNode cat = TaxonomyNode.builder()
                .id("c-instruments").label("Instruments").parentId("d-finance")
                .level(TaxonomyNode.TaxonomyLevel.CATEGORY).build();

        String json = objectMapper.writeValueAsString(List.of(domain, cat));
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .id(3L).factSheetId(10L).taxonomyJson(json).version(2).build();

        List<ProcessDefinition> result = service.generateProcessDefinitions(taxonomy);

        assertEquals(1, result.size());
        ProcessDefinition pd = result.get(0);

        assertEquals(ProcessStatus.DRAFT, pd.getStatus(),
                "Generated process definitions should have DRAFT status");
        assertEquals("taxonomy-auto-generated", pd.getMetadata().get("source"),
                "metadata.source should be 'taxonomy-auto-generated'");
        assertEquals("d-finance", pd.getMetadata().get("domainId"),
                "metadata.domainId should match the domain node id");
    }
}
