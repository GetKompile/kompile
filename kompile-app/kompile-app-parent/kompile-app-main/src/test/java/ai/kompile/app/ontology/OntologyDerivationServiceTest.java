/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.ontology;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.web.dto.ontology.DeriveOntologyRequest;
import ai.kompile.app.web.dto.ontology.OntologyCandidatesResponse;
import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.service.FactSheetGraphService;
import ai.kompile.process.ontology.EntityClassification;
import ai.kompile.process.ontology.OntologySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OntologyDerivationService} — covers the LLM path, the structural fallback,
 * graph-grounded candidates, and the error contracts. The knowledge-graph + fact-sheet services and
 * the optional {@link LLMChat} are all mocked, so these run without a Spring context or a live model.
 */
class OntologyDerivationServiceTest {

    private static final String VALID_LLM_JSON = """
            {
              "name": "ignored-overwritten",
              "entityTypes": [
                {
                  "name": "Revenue",
                  "description": "Revenue metric",
                  "classification": "METRIC",
                  "fields": [
                    { "name": "id", "type": "STRING", "primaryKey": true, "required": true },
                    { "name": "amount", "type": "DECIMAL", "required": true }
                  ]
                }
              ],
              "relationshipTypes": [],
              "globalRules": []
            }
            """;

    private FactSheetGraphService graphService;
    private FactSheetService factSheetService;
    private OntologyDerivationService service;

    @BeforeEach
    void setUp() {
        graphService = mock(FactSheetGraphService.class);
        factSheetService = mock(FactSheetService.class);
        service = new OntologyDerivationService(graphService, factSheetService);

        FactSheet sheet = FactSheet.builder().id(1L).name("Acme").description("Acme corp data").build();
        when(factSheetService.getSheetById(1L)).thenReturn(Optional.of(sheet));

        when(graphService.getGraphStatistics(1L)).thenReturn(Map.of(
                "totalNodes", 10,
                "entityCount", 5,
                "documentCount", 2,
                "distinctConcepts", 4,
                "totalEdges", 6,
                "edgesByType", Map.of("SHARED_ENTITY", 6, "HIERARCHICAL", 3)));
        when(graphService.getTopConcepts(eq(1L), anyInt())).thenReturn(List.of(
                Map.of("name", "Revenue", "totalMentions", 12),
                Map.of("name", "Invoice", "totalMentions", 8)));
        when(graphService.getVisualizationData(eq(1L), anyInt(), anyInt()))
                .thenReturn(new FactSheetGraphService.GraphVisualizationData(List.of(), List.of(), Map.of()));
    }

    @Test
    void derive_withLlm_parsesSchemaAndStampsProvenance() {
        LLMChat llm = mock(LLMChat.class, RETURNS_DEEP_STUBS);
        when(llm.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(VALID_LLM_JSON);
        service.setLlmChat(llm);

        OntologySchema schema = service.derive(req(1L, null, "focus on revenue", null));

        assertNotNull(schema.getEntityTypes());
        assertEquals(1, schema.getEntityTypes().size());
        assertEquals("Revenue", schema.getEntityTypes().get(0).getName());
        assertEquals(EntityClassification.METRIC, schema.getEntityTypes().get(0).getClassification());
        // Name is resolved from the fact sheet, not the model's placeholder.
        assertEquals("Acme Ontology", schema.getName());
        assertEquals("llm", schema.getMetadata().get("generationMethod"));
        assertEquals(1L, schema.getMetadata().get("derivedFromFactSheetId"));
    }

    @Test
    void derive_withoutLlm_buildsStructuralSchemaFromSeeds() {
        // No LLM configured.
        OntologySchema schema = service.derive(req(1L, "My Ontology", null,
                List.of("Customer Account", "Sales Order")));

        assertEquals("My Ontology", schema.getName());
        assertEquals("structural", schema.getMetadata().get("generationMethod"));
        List<String> names = schema.getEntityTypes().stream().map(et -> et.getName()).toList();
        assertTrue(names.contains("CustomerAccount"), "seed should be PascalCased");
        assertTrue(names.contains("SalesOrder"));
        // Structural entities get a primary-key id field.
        assertTrue(schema.getEntityTypes().get(0).getFields().stream().anyMatch(f -> f.isPrimaryKey()));
    }

    @Test
    void derive_whenLlmReturnsGarbage_fallsBackToStructural() {
        LLMChat llm = mock(LLMChat.class, RETURNS_DEEP_STUBS);
        when(llm.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("I'm sorry, I can't help with that.");
        service.setLlmChat(llm);

        OntologySchema schema = service.derive(req(1L, null, null, null));

        assertEquals("structural-fallback", schema.getMetadata().get("generationMethod"));
        // Falls back to the top graph concepts (Revenue, Invoice).
        List<String> names = schema.getEntityTypes().stream().map(et -> et.getName()).toList();
        assertTrue(names.contains("Revenue"));
    }

    @Test
    void candidates_mapsConceptsStatsAndClassifications() {
        OntologyCandidatesResponse resp = service.candidates(1L, 60);

        assertTrue(resp.graphAvailable());
        assertEquals("Acme", resp.factSheetName());
        assertEquals(5L, resp.entityCount());
        assertEquals(2, resp.candidateEntityTypes().size());
        assertEquals("Revenue", resp.candidateEntityTypes().get(0).suggestedEntityName());
        assertEquals(6, resp.classifications().size());
        assertEquals(2, resp.relationshipHints().size());
    }

    @Test
    void derive_unknownFactSheet_throwsIllegalArgument() {
        when(factSheetService.getSheetById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.derive(req(99L, null, null, null)));
    }

    @Test
    void derive_emptyGraphNoLlmNoSeeds_throwsIllegalState() {
        FactSheet empty = FactSheet.builder().id(2L).name("Empty").build();
        when(factSheetService.getSheetById(2L)).thenReturn(Optional.of(empty));
        when(graphService.getGraphStatistics(2L)).thenReturn(Map.of("totalNodes", 0));
        when(graphService.getTopConcepts(eq(2L), anyInt())).thenReturn(List.of());
        when(graphService.getVisualizationData(eq(2L), anyInt(), anyInt()))
                .thenReturn(new FactSheetGraphService.GraphVisualizationData(List.of(), List.of(), Map.of()));

        assertThrows(IllegalStateException.class, () -> service.derive(req(2L, null, null, null)));
    }

    @Test
    void extractJsonObject_stripsFencesAndProse() {
        String fenced = "Here you go:\n```json\n{\"name\":\"x\"}\n```\nthanks!";
        assertEquals("{\"name\":\"x\"}", OntologyDerivationService.extractJsonObject(fenced));
    }

    @Test
    void toPascalCase_normalizesConceptNames() {
        assertEquals("CustomerAccount", OntologyDerivationService.toPascalCase("customer account"));
        assertEquals("SalesOrder", OntologyDerivationService.toPascalCase("sales-order"));
        assertEquals("Entity1Thing", OntologyDerivationService.toPascalCase("1 thing"));
    }

    @Test
    void derive_withRegistryProvider_routesToChosenProviderAndModel() {
        ExtractionLlmServiceRegistry registry = mock(ExtractionLlmServiceRegistry.class);
        ExtractionLlmService svc = mock(ExtractionLlmService.class);
        when(registry.getOrFallback("claude-cli")).thenReturn(svc);
        when(svc.isAvailable()).thenReturn(true);
        when(svc.getId()).thenReturn("claude-cli");
        when(svc.getEffectiveModel()).thenReturn("claude-opus-4-8");
        when(svc.complete(anyString())).thenReturn(VALID_LLM_JSON);
        service.setExtractionRegistry(registry);

        OntologySchema schema = service.derive(reqWithModel(1L, "claude-cli", "claude-opus-4-8"));

        assertEquals("llm:claude-cli", schema.getMetadata().get("generationMethod"));
        assertEquals("Revenue", schema.getEntityTypes().get(0).getName());
        verify(svc).setModelOverride("claude-opus-4-8");
        verify(svc).complete(anyString());
    }

    @Test
    void derive_streamsLogsAndTranscriptToProgress() {
        LLMChat llm = mock(LLMChat.class, RETURNS_DEEP_STUBS);
        when(llm.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(VALID_LLM_JSON);
        service.setLlmChat(llm);

        List<String> logs = new ArrayList<>();
        String[] captured = new String[2];
        DerivationProgress progress = new DerivationProgress() {
            @Override
            public void log(String message) {
                logs.add(message);
            }

            @Override
            public void transcript(String provider, String model, String prompt, String response) {
                captured[0] = provider;
                captured[1] = response;
            }
        };

        service.derive(req(1L, null, null, null), progress);

        assertFalse(logs.isEmpty(), "progress should receive live log lines");
        assertEquals("default", captured[0]);
        assertEquals(VALID_LLM_JSON, captured[1]);
    }

    private static DeriveOntologyRequest req(Long factSheetId, String name, String guidance, List<String> seeds) {
        return new DeriveOntologyRequest(
                factSheetId, name, guidance, null, null, null, null, seeds, null, null, null);
    }

    private static DeriveOntologyRequest reqWithModel(Long factSheetId, String provider, String model) {
        return new DeriveOntologyRequest(
                factSheetId, null, null, null, null, null, null, null, null, provider, model);
    }
}
