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
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    void systemPromptContainsNoCopyableOntologyPlaceholders() {
        String prompt = OntologyDerivationService.systemPromptContract();

        assertTrue(prompt.contains("0.45 through 1.0"));
        assertTrue(prompt.contains("source-grounded UPPERCASE_WITH_UNDERSCORES verb phrase"));
        assertFalse(prompt.contains("VERB_PHRASE_IN_CAPS"));
        assertFalse(prompt.contains("PascalCaseTypeName"));
        assertFalse(prompt.contains("\"confidence\": 0.0"));
        assertFalse(prompt.contains("\"description\": \"string\""));
        for (String splitPrompt : OntologyDerivationService.splitPromptContracts()) {
            assertFalse(splitPrompt.contains("PascalCaseTypeName"));
            assertFalse(splitPrompt.contains("VERB_PHRASE_IN_CAPS"));
            assertFalse(splitPrompt.contains("\"name\": \"string\""));
        }
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("engine adds the identifier and primary key")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("Classify exactly one engine-fixed validation rule")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("Select the violation action for exactly one")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("2 = TRANSACTIONAL: a business record")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("1 = ASSERTION: a general business or record condition")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("fixed action is halt")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("zero, one, or many relationships")));
        assertTrue(OntologyDerivationService.splitPromptContracts().stream()
                .anyMatch(contract -> contract.contains("2 = halt")));
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
    void derive_splitModelPathFixesTypesBeforeFieldsAndMapsRelationOrdinals() {
        ExtractionLlmServiceRegistry registry = mock(ExtractionLlmServiceRegistry.class);
        ExtractionLlmService svc = mock(ExtractionLlmService.class);
        when(registry.getOrFallback("split-provider")).thenReturn(svc);
        when(svc.isAvailable()).thenReturn(true);
        when(svc.getId()).thenReturn("split-provider");
        when(svc.getEffectiveModel()).thenReturn("small-model");
        when(svc.complete(anyString())).thenReturn("""
                {"entityTypes":[
                  {"name":"Revenue","description":"Revenue metric","classification":"METRIC",
                   "confidence":0.9,"parentType":"Invoice"},
                  {"name":"Invoice","description":"Invoice record","classification":"TRANSACTIONAL",
                   "confidence":0.88,"parentType":"Revenue"}]}
                """, """
                {"selectedOrdinal":5}
                """, """
                {"selectedOrdinal":2}
                """, """
                {"fields":[
                  {"name":"id","type":"STRING","required":true,"primaryKey":true,
                   "description":"Identifier"},
                  {"name":"Revenue","type":"STRING","required":false,"primaryKey":false,
                   "description":"Wrongly repeated entity type"},
                  {"name":"amount","type":"DECIMAL","required":true,"primaryKey":false,
                   "description":"Recorded amount"}]}
                """, """
                {"fields":[
                  {"name":"invoiceNumber","type":"STRING","required":true,"primaryKey":true,
                   "description":"Invoice identifier"}]}
                """, """
                {"relationshipTypes":[
                  {"type":"recorded on","sourceOrdinal":1,"targetOrdinal":2,
                   "cardinality":"MANY_TO_ONE","transitive":false,
                   "description":"Revenue is recorded on an invoice"},
                  {"type":"recorded on","sourceOrdinal":2,"targetOrdinal":1,
                   "cardinality":"ONE_TO_MANY","transitive":false,
                   "description":"Unsupported inverse duplicate"}]}
                """, """
                {"relationshipTypes":[
                  {"type":"recorded on","sourceOrdinal":1,"targetOrdinal":2,
                   "cardinality":"MANY_TO_ONE","transitive":false,
                   "description":"Revenue is recorded on an invoice"}]}
                """, """
                {"globalRules":[{"name":"Revenue amount positive",
                  "expression":"Revenue.amount must be positive",
                  "description":"Revenue amount must be positive"}]}
                """, """
                {"selectedOrdinal":1}
                """, """
                {"selectedOrdinal":1,"escalateTo":"Finance"}
                """, """
                {"selectedOrdinal":2,"escalateTo":null}
                """, """
                {"selectedOrdinal":3}
                """);
        service.setExtractionRegistry(registry);

        OntologySchema schema = service.derive(
                reqWithModel(1L, "split-provider", "small-model",
                        "Revenue has amount field. Invoice has invoiceNumber field. "
                                + "Revenue is recorded on Invoice. "
                                + "Revenue.amount must be positive; halt on violation."));

        assertEquals(List.of("Revenue", "Invoice"), schema.getEntityTypes().stream()
                .map(EntityTypeDefinition::getName).toList());
        assertTrue(schema.getEntityTypes().stream().allMatch(type -> type.getParentType() == null),
                "hierarchy is not part of entity discovery and reciprocal model parents must be ignored");
        assertEquals(List.of("id", "amount"), schema.getEntityTypes().get(0).getFields().stream()
                .map(field -> field.getName()).toList());
        assertEquals(List.of("id", "invoiceNumber"), schema.getEntityTypes().get(1).getFields().stream()
                .map(field -> field.getName()).toList());
        assertTrue(schema.getEntityTypes().get(0).getFields().get(0).isPrimaryKey());
        assertFalse(schema.getEntityTypes().get(0).getFields().get(1).isPrimaryKey(),
                "the engine, not the model, owns the primary key");
        assertEquals(1, schema.getRelationshipTypes().size());
        assertEquals("RECORDED_ON", schema.getRelationshipTypes().get(0).getType());
        assertEquals("Revenue", schema.getRelationshipTypes().get(0).getSourceEntityType());
        assertEquals("Invoice", schema.getRelationshipTypes().get(0).getTargetEntityType());
        assertEquals(1, schema.getGlobalRules().size());
        assertEquals("ASSERTION", schema.getGlobalRules().get(0).getRuleType().name());
        assertEquals("ERROR", schema.getGlobalRules().get(0).getSeverity().name());
        assertEquals("halt", schema.getGlobalRules().get(0).getOnViolation());
        assertNull(schema.getGlobalRules().get(0).getEscalateTo(),
                "a non-escalation action cannot retain a model-invented escalation target");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(svc, times(12)).complete(prompts.capture());
        assertTrue(prompts.getAllValues().get(0).contains("Identify only the entity types"));
        assertFalse(prompts.getAllValues().get(0).contains("sourceOrdinal"));
        assertTrue(prompts.getAllValues().get(1).contains("Classify exactly one engine-fixed entity type"));
        assertTrue(prompts.getAllValues().get(1).contains("- name: Revenue"));
        assertTrue(prompts.getAllValues().get(1).contains("6 = ACTOR: a person, role, team, or organization"));
        assertTrue(prompts.getAllValues().get(2).contains("- name: Invoice"));
        assertTrue(prompts.getAllValues().get(3).contains("ENGINE-FIXED ENTITY TYPE"));
        assertTrue(prompts.getAllValues().get(3).contains("ENGINE-GROUNDED DOMAIN FIELD BALLOT"));
        assertTrue(prompts.getAllValues().get(3).contains("- amount"));
        assertTrue(prompts.getAllValues().get(3).contains("engine adds id and owns primaryKey"));
        assertTrue(prompts.getAllValues().get(4).contains("- name: Invoice"));
        assertTrue(prompts.getAllValues().get(4).contains("- invoiceNumber"));
        assertTrue(prompts.getAllValues().get(5).contains("ordinal=1 | name=Revenue"));
        assertFalse(prompts.getAllValues().get(5).contains("sourceEntityType"),
                "the model chooses endpoint ordinals; the engine writes canonical type names");
        assertTrue(prompts.getAllValues().get(6).contains("Correct one rejected relationship extraction"));
        assertTrue(prompts.getAllValues().get(6).contains("duplicates or reverses an earlier RECORDED_ON"));
        assertTrue(prompts.getAllValues().get(6).contains("Preserve every distinct relationship"));
        assertTrue(prompts.getAllValues().get(7).contains("ENGINE-FIXED ONTOLOGY"));
        assertTrue(prompts.getAllValues().get(7).contains("Revenue -[RECORDED_ON]-> Invoice"));
        assertTrue(prompts.getAllValues().get(7).contains("ENGINE-GROUNDED EXECUTABLE EXPRESSION BALLOT"));
        assertTrue(prompts.getAllValues().get(7).contains("Revenue.amount must be positive"));
        assertTrue(prompts.getAllValues().get(7).contains("Do not classify ruleType"),
                "rule-core discovery must explicitly defer enum classification");
        assertFalse(prompts.getAllValues().get(7).contains("BUDGET_LIMIT"),
                "rule-type enum choices belong only to the classification call");
        assertTrue(prompts.getAllValues().get(8).contains("ENGINE-FIXED RULE CORE"));
        assertTrue(prompts.getAllValues().get(8).contains("Revenue.amount must be positive"));
        assertTrue(prompts.getAllValues().get(8).contains("ordinary record-field validation"));
        assertTrue(prompts.getAllValues().get(9).contains("selectedOrdinal"));
        assertTrue(prompts.getAllValues().get(9).contains("2 = halt"));
        assertTrue(prompts.getAllValues().get(9).contains("halt on violation"));
        assertTrue(prompts.getAllValues().get(9).contains("severity is a separate task"));
        assertFalse(prompts.getAllValues().get(9).contains("Revenue is recorded on Invoice"),
                "the action shard must exclude unrelated relationship evidence");
        assertFalse(prompts.getAllValues().get(9).contains("Additional guidance from the user"),
                "the action shard must not re-inject the full guidance block");
        assertTrue(prompts.getAllValues().get(10).contains("Correct one rejected validation-rule action"));
        assertTrue(prompts.getAllValues().get(10).contains("ENGINE-VALIDATED REQUIRED SELECTION"));
        assertTrue(prompts.getAllValues().get(10).contains("- selectedOrdinal=2"));
        assertTrue(prompts.getAllValues().get(10)
                .contains("source evidence explicitly requires selectedOrdinal=2 (halt)"));
        assertTrue(prompts.getAllValues().get(11).contains("ENGINE-FIXED VIOLATION ACTION"));
        assertTrue(prompts.getAllValues().get(11).contains("- onViolation: halt"));
        assertTrue(prompts.getAllValues().get(11).contains("3 = ERROR"));
    }

    @Test
    void derive_skipsFieldModelCallWhenAlgorithmicBallotIsEmpty() {
        ExtractionLlmServiceRegistry registry = mock(ExtractionLlmServiceRegistry.class);
        ExtractionLlmService svc = mock(ExtractionLlmService.class);
        when(registry.getOrFallback("split-provider")).thenReturn(svc);
        when(svc.isAvailable()).thenReturn(true);
        when(svc.getId()).thenReturn("split-provider");
        when(svc.getEffectiveModel()).thenReturn("small-model");
        when(svc.complete(anyString())).thenReturn("""
                {"entityTypes":[{"name":"Approver","description":"Designated approver",
                  "classification":"ACTOR","confidence":0.9}]}
                """, """
                {"selectedOrdinal":6}
                """);
        service.setExtractionRegistry(registry);

        OntologySchema schema = service.derive(new DeriveOntologyRequest(
                1L, "Approval Ontology", "Define only Approver.", 1, false, false,
                List.of(), List.of("Approver"), 10, "split-provider", "small-model"));

        assertEquals(List.of("id"), schema.getEntityTypes().get(0).getFields().stream()
                .map(field -> field.getName()).toList());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(svc, times(2)).complete(prompts.capture());
        assertTrue(prompts.getAllValues().stream()
                .noneMatch(prompt -> prompt.contains("Select domain fields for exactly one")));
    }

    @Test
    void derive_classificationValidationCorrectsStrongRoleMismatch() {
        ExtractionLlmServiceRegistry registry = mock(ExtractionLlmServiceRegistry.class);
        ExtractionLlmService svc = mock(ExtractionLlmService.class);
        when(registry.getOrFallback("split-provider")).thenReturn(svc);
        when(svc.isAvailable()).thenReturn(true);
        when(svc.getId()).thenReturn("split-provider");
        when(svc.getEffectiveModel()).thenReturn("small-model");
        when(svc.complete(anyString())).thenReturn("""
                {"entityTypes":[{"name":"Approver","description":"Designated approver",
                  "confidence":0.9}]}
                """, """
                {"selectedOrdinal":2}
                """, """
                {"selectedOrdinal":6}
                """);
        service.setExtractionRegistry(registry);

        OntologySchema schema = service.derive(new DeriveOntologyRequest(
                1L, "Approval Ontology", "Define only Approver.", 1, false, false,
                List.of(), List.of("Approver"), 10, "split-provider", "small-model"));

        assertEquals(EntityClassification.ACTOR, schema.getEntityTypes().get(0).getClassification());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(svc, times(3)).complete(prompts.capture());
        assertTrue(prompts.getAllValues().get(2).contains("Correct one rejected entity classification"));
        assertTrue(prompts.getAllValues().get(2).contains("requires ordinal 6 (ACTOR)"));
    }

    @Test
    void derive_relationshipFeedbackPreservesMultipleDistinctRelationsAndSalvagesValidGroups() {
        ExtractionLlmServiceRegistry registry = mock(ExtractionLlmServiceRegistry.class);
        ExtractionLlmService svc = mock(ExtractionLlmService.class);
        when(registry.getOrFallback("split-provider")).thenReturn(svc);
        when(svc.isAvailable()).thenReturn(true);
        when(svc.getId()).thenReturn("split-provider");
        when(svc.getEffectiveModel()).thenReturn("small-model");
        when(svc.complete(anyString())).thenReturn("""
                {"entityTypes":[
                  {"name":"Forecast","description":"Forecast record","confidence":0.9},
                  {"name":"Approver","description":"Approval role","confidence":0.9},
                  {"name":"Region","description":"Forecast region","confidence":0.9}]}
                """, """
                {"selectedOrdinal":2}
                """, """
                {"selectedOrdinal":6}
                """, """
                {"selectedOrdinal":1}
                """, """
                {"relationshipTypes":[
                  {"type":"APPROVED_BY","sourceOrdinal":2,"targetOrdinal":1,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"wrong inverse"},
                  {"type":"APPROVED_BY","sourceOrdinal":1,"targetOrdinal":2,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"Forecast approved by Approver"},
                  {"type":"APPLIES_TO","sourceOrdinal":1,"targetOrdinal":3,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"Forecast applies to Region"}]}
                """, """
                {"relationshipTypes":[
                  {"type":"APPROVED_BY","sourceOrdinal":2,"targetOrdinal":1,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"wrong inverse"},
                  {"type":"APPROVED_BY","sourceOrdinal":1,"targetOrdinal":2,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"Forecast approved by Approver"},
                  {"type":"APPLIES_TO","sourceOrdinal":1,"targetOrdinal":3,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"Forecast applies to Region"}]}
                """);
        service.setExtractionRegistry(registry);

        OntologySchema schema = service.derive(new DeriveOntologyRequest(
                1L, "Approval Ontology",
                "Define only Forecast, Approver, and Region. A Forecast is approved by an Approver. "
                        + "A Forecast applies to a Region.",
                3, true, false, List.of(), List.of("Forecast", "Approver", "Region"), 10,
                "split-provider", "small-model"));

        assertEquals(2, schema.getRelationshipTypes().size(),
                "validation salvage must not collapse the full response to one relation");
        assertTrue(schema.getRelationshipTypes().stream().anyMatch(relationship ->
                "APPROVED_BY".equals(relationship.getType())
                        && "Forecast".equals(relationship.getSourceEntityType())
                        && "Approver".equals(relationship.getTargetEntityType())));
        assertTrue(schema.getRelationshipTypes().stream().anyMatch(relationship ->
                "APPLIES_TO".equals(relationship.getType())
                        && "Forecast".equals(relationship.getSourceEntityType())
                        && "Region".equals(relationship.getTargetEntityType())));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(svc, times(6)).complete(prompts.capture());
        assertTrue(prompts.getAllValues().get(5).contains("source must be the non-ACTOR object"));
        assertTrue(prompts.getAllValues().get(5).contains("zero, one, or many distinct supported"));
    }

    @Test
    void derive_relationshipValidationFailureDoesNotDiscardEarlierStages() {
        ExtractionLlmServiceRegistry registry = mock(ExtractionLlmServiceRegistry.class);
        ExtractionLlmService svc = mock(ExtractionLlmService.class);
        when(registry.getOrFallback("split-provider")).thenReturn(svc);
        when(svc.isAvailable()).thenReturn(true);
        when(svc.getId()).thenReturn("split-provider");
        when(svc.getEffectiveModel()).thenReturn("small-model");
        String malformedRelationships = """
                {"relationshipTypes":[
                  {"type":"APPROVED_BY","sourceOrdinal":2,"targetOrdinal":1,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"wrong inverse"}},
                  {"type":"APPROVED_BY","sourceOrdinal":1,"targetOrdinal":2,
                   "cardinality":"MANY_TO_ONE","transitive":false,"description":"correct"}]}
                """;
        when(svc.complete(anyString())).thenReturn("""
                {"entityTypes":[
                  {"name":"Forecast","description":"Forecast record","confidence":0.9},
                  {"name":"Approver","description":"Approval role","confidence":0.9}]}
                """, """
                {"selectedOrdinal":2}
                """, """
                {"selectedOrdinal":6}
                """, """
                {"fields":[{"name":"region","type":"STRING","required":true,
                  "description":"Forecast region"}]}
                """, malformedRelationships, malformedRelationships);
        service.setExtractionRegistry(registry);

        OntologySchema schema = service.derive(new DeriveOntologyRequest(
                1L, "Approval Ontology",
                "Define only Forecast and Approver. Forecast has region field. "
                        + "A Forecast is approved by an Approver.",
                2, true, false, List.of(), List.of("Forecast", "Approver"), 10,
                "split-provider", "small-model"));

        assertEquals(List.of("id", "region"), schema.getEntityTypes().get(0).getFields().stream()
                .map(field -> field.getName()).toList());
        assertTrue(schema.getRelationshipTypes() == null || schema.getRelationshipTypes().isEmpty());
        assertEquals("llm:split-provider", schema.getMetadata().get("generationMethod"),
                "a rejected relationship stage must not replace valid earlier model stages structurally");
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(svc, times(6)).complete(prompts.capture());
        assertTrue(prompts.getAllValues().get(5).contains("response is not valid JSON"));
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
    void derive_withoutLlm_buildsParentTypesFromCrawlHierarchyMetadata() {
        when(graphService.getVisualizationData(eq(1L), anyInt(), anyInt()))
                .thenReturn(new FactSheetGraphService.GraphVisualizationData(
                        List.of(Map.of(
                                "id", "wine-red",
                                "type", "ENTITY",
                                "label", "Cabernet",
                                "metadata", Map.of(
                                        "entity_category", "Wine",
                                        "entity_type", "Red Wine"))),
                        List.of(),
                        Map.of()));

        OntologySchema schema = service.derive(req(1L, null, null, null));

        Map<String, EntityTypeDefinition> byName = schema.getEntityTypes().stream()
                .collect(java.util.stream.Collectors.toMap(EntityTypeDefinition::getName, e -> e));
        assertTrue(byName.containsKey("RedWine"));
        assertTrue(byName.containsKey("Wine"));
        assertEquals("Wine", byName.get("RedWine").getParentType());
        assertTrue(byName.get("RedWine").getConfidence() > 0.55d,
                "crawl-backed type should get a stronger structural confidence than generic concepts");
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
        assertEquals("赤ワイン", OntologyDerivationService.toPascalCase("赤ワイン"));
        assertEquals("VinRouge", OntologyDerivationService.toPascalCase("vin rouge"));
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
        return reqWithModel(factSheetId, provider, model, null);
    }

    private static DeriveOntologyRequest reqWithModel(Long factSheetId, String provider, String model,
                                                       String guidance) {
        return new DeriveOntologyRequest(
                factSheetId, null, guidance, null, null, null, null, null, null, provider, model);
    }
}
