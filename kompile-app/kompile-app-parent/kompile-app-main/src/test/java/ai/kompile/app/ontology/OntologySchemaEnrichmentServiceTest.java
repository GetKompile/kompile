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

import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologySchemaEnrichmentServiceTest {

    @Test
    void generateSchemaAndTypes_rerunsOwlOnlyWhenTypeInductionChangedSchema() {
        OwlReasoningService owl = mock(OwlReasoningService.class);
        OntologyTypeInductionService induction = mock(OntologyTypeInductionService.class);
        OwlClassificationResponse first = response(1);
        OwlClassificationResponse second = response(2);
        when(owl.classify(7L)).thenReturn(first, second);
        when(induction.enrichAfterOwl(7L)).thenReturn(new OntologyTypeInductionResult(true, 1, 1, 2));

        OwlClassificationResponse result = new OntologySchemaEnrichmentService(owl, induction)
                .generateSchemaAndTypes(7L);

        assertSame(second, result);
        verify(owl, times(2)).classify(7L);
    }

    private static OwlClassificationResponse response(int types) {
        return OwlClassificationResponse.builder()
                .factSheetId(7L)
                .ontologyBound(true)
                .ontologyName("ontology")
                .inferredTypeCount(types)
                .consistent(true)
                .reasonerActive(true)
                .build();
    }
}
