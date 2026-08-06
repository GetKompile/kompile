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
package ai.kompile.app.ontology;

import ai.kompile.app.web.dto.ontology.TypedEntityMatchResponse;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyTypeInductionServiceTest {

    private GraphOntologyBindingService bindingService;
    private ProcessEngineService processEngineService;
    private KnowledgeGraphService knowledgeGraphService;
    private OntologyTypeInductionService service;

    @BeforeEach
    void setUp() {
        bindingService = mock(GraphOntologyBindingService.class);
        processEngineService = mock(ProcessEngineService.class);
        knowledgeGraphService = mock(KnowledgeGraphService.class);
        service = new OntologyTypeInductionService(bindingService, processEngineService, knowledgeGraphService);
    }

    @Test
    void systemPromptContainsNoCopyableTypePlaceholders() {
        String prompt = OntologyTypeInductionService.systemPromptContract();

        assertTrue(prompt.contains("0.45 through 1.0"));
        assertTrue(prompt.contains("{\"entityTypes\":[]}"));
        assertFalse(prompt.contains("CanonicalTypeName"));
        assertFalse(prompt.contains("ExistingOrNewParentType"));
        assertFalse(prompt.contains("\"confidence\": 0.0"));
    }

    @Test
    void enrichAfterOwl_withoutLlm_preservesObservedRawLabelsAsAliases() throws Exception {
        OntologySchema schema = OntologySchema.builder()
                .id("wine").version(1).name("Wine")
                .entityTypes(List.of(EntityTypeDefinition.builder().name("RedWine").build()))
                .build();
        when(bindingService.resolveActiveOntology(7L)).thenReturn(Optional.of(schema));
        when(knowledgeGraphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY))
                .thenReturn(List.of(entity("red-1", "Bordeaux", Map.of("entity_type", "Red Wine"))));
        when(processEngineService.updateOntology(eq("wine"), org.mockito.ArgumentMatchers.any(OntologySchema.class)))
                .thenAnswer(inv -> {
            OntologySchema updated = inv.getArgument(1);
            updated.setVersion(2);
            return updated;
        });

        OntologyTypeInductionResult result = service.enrichAfterOwl(7L);

        assertTrue(result.changed());
        ArgumentCaptor<OntologySchema> schemaCaptor = ArgumentCaptor.forClass(OntologySchema.class);
        verify(processEngineService).updateOntology(eq("wine"), schemaCaptor.capture());
        EntityTypeDefinition redWine = schemaCaptor.getValue().getEntityTypes().get(0);
        assertEquals(List.of("Red Wine"), redWine.getAliases());
        verify(bindingService).bindOntology(7L, "wine", 2);
    }

    @Test
    void enrichAfterOwl_withLlmAddsMissingCanonicalTypesAndLocalizedAliases() throws Exception {
        OntologySchema schema = OntologySchema.builder()
                .id("wine").version(1).name("Wine")
                .entityTypes(List.of(EntityTypeDefinition.builder().name("Wine").aliases(List.of("vin")).build()))
                .build();
        when(bindingService.resolveActiveOntology(7L)).thenReturn(Optional.of(schema));
        when(knowledgeGraphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY))
                .thenReturn(List.of(entity("red-1", "Bordeaux", Map.of(
                        "entity_type", "vin rouge",
                        "entity_category", "vin"))));
        when(processEngineService.updateOntology(eq("wine"), org.mockito.ArgumentMatchers.any(OntologySchema.class)))
                .thenAnswer(inv -> {
            OntologySchema updated = inv.getArgument(1);
            updated.setVersion(2);
            return updated;
        });
        LLMChat llm = mock(LLMChat.class, RETURNS_DEEP_STUBS);
        when(llm.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("""
                {
                  "entityTypes": [
                    {
                      "name": "RedWine",
                      "parentType": "Wine",
                      "description": "A red wine category found in crawled source text.",
                      "aliases": ["vin rouge"],
                      "localizedLabels": {"fr": "vin rouge"},
                      "confidence": 0.91
                    }
                  ]
                }
                """);
        service.setLlmChat(llm);

        OntologyTypeInductionResult result = service.enrichAfterOwl(7L);

        assertTrue(result.changed());
        ArgumentCaptor<OntologySchema> schemaCaptor = ArgumentCaptor.forClass(OntologySchema.class);
        verify(processEngineService).updateOntology(eq("wine"), schemaCaptor.capture());
        Map<String, EntityTypeDefinition> byName = schemaCaptor.getValue().getEntityTypes().stream()
                .collect(java.util.stream.Collectors.toMap(EntityTypeDefinition::getName, e -> e));
        assertTrue(byName.containsKey("RedWine"));
        assertEquals("Wine", byName.get("RedWine").getParentType());
        assertTrue(byName.get("RedWine").getAliases().contains("vin rouge"));
        assertEquals("vin rouge", byName.get("RedWine").getLocalizedLabels().get("fr"));
        verify(bindingService).bindOntology(7L, "wine", 2);
    }

    @Test
    void findEntitiesByType_resolvesAliasesAndInheritedSubtypes() throws Exception {
        OntologySchema schema = OntologySchema.builder()
                .id("wine").version(1).name("Wine")
                .entityTypes(List.of(
                        EntityTypeDefinition.builder().name("Wine").aliases(List.of("vin")).build(),
                        EntityTypeDefinition.builder().name("RedWine").parentType("Wine")
                                .aliases(List.of("vin rouge")).build()))
                .build();
        when(bindingService.resolveActiveOntology(7L)).thenReturn(Optional.of(schema));
        when(knowledgeGraphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(
                entity("red-1", "Bordeaux", Map.of("entity_type", "RedWine")),
                entity("wine-1", "Generic wine", Map.of("owlInferredTypes", List.of("Wine")))));

        TypedEntityMatchResponse inherited = service.findEntitiesByType(7L, "vin", true, 0.0);
        assertEquals("Wine", inherited.getResolvedType());
        assertEquals(2, inherited.getMatchCount());

        TypedEntityMatchResponse alias = service.findEntitiesByType(7L, "vin rouge", false, 0.0);
        assertEquals("RedWine", alias.getResolvedType());
        assertEquals(1, alias.getMatchCount());
        assertEquals("red-1", alias.getMatches().get(0).getNodeId());
    }

    private static GraphNode entity(String id, String title, Map<String, Object> metadata)
            throws JsonProcessingException {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .externalId(id)
                .title(title)
                .metadataJson(JsonUtils.standardMapper().writeValueAsString(metadata))
                .build();
    }
}
