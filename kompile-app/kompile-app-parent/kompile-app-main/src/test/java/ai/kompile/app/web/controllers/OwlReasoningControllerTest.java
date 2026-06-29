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
package ai.kompile.app.web.controllers;

import ai.kompile.app.ontology.OntologySchemaEnrichmentService;
import ai.kompile.app.ontology.OntologyTypeInductionResult;
import ai.kompile.app.ontology.OntologyTypeInductionService;
import ai.kompile.app.ontology.OwlReasoningService;
import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OwlReasoningControllerTest {

    @Test
    void classifyUsesFullSchemaEnrichmentService() {
        OwlReasoningService owl = mock(OwlReasoningService.class);
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        OntologyTypeInductionService induction = mock(OntologyTypeInductionService.class);
        OwlClassificationResponse expected = response();
        when(schema.generateSchemaAndTypes(7L)).thenReturn(expected);

        ResponseEntity<OwlClassificationResponse> result =
                new OwlReasoningController(owl, schema, induction).classify(7L);

        assertSame(expected, result.getBody());
        verify(schema).generateSchemaAndTypes(7L);
        verifyNoInteractions(owl, induction);
    }

    @Test
    void classifyOwlOnlyUsesOwlServiceDirectly() {
        OwlReasoningService owl = mock(OwlReasoningService.class);
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        OntologyTypeInductionService induction = mock(OntologyTypeInductionService.class);
        OwlClassificationResponse expected = response();
        when(owl.classify(7L)).thenReturn(expected);

        ResponseEntity<OwlClassificationResponse> result =
                new OwlReasoningController(owl, schema, induction).classifyOwlOnly(7L);

        assertSame(expected, result.getBody());
        verify(owl).classify(7L);
        verifyNoInteractions(schema, induction);
    }

    @Test
    void induceTypesUsesTypeInductionServiceDirectly() {
        OwlReasoningService owl = mock(OwlReasoningService.class);
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        OntologyTypeInductionService induction = mock(OntologyTypeInductionService.class);
        OntologyTypeInductionResult expected = new OntologyTypeInductionResult(true, 2, 1, 3);
        when(induction.enrichAfterOwl(7L)).thenReturn(expected);

        ResponseEntity<OntologyTypeInductionResult> result =
                new OwlReasoningController(owl, schema, induction).induceTypes(7L);

        assertSame(expected, result.getBody());
        verify(induction).enrichAfterOwl(7L);
        verifyNoInteractions(owl, schema);
    }

    private static OwlClassificationResponse response() {
        return OwlClassificationResponse.builder()
                .factSheetId(7L)
                .ontologyBound(true)
                .ontologyName("ontology")
                .inferredTypeCount(1)
                .inferredRelationCount(1)
                .entitiesClassified(1)
                .edgesMaterialized(1)
                .consistent(true)
                .reasonerActive(true)
                .build();
    }
}
