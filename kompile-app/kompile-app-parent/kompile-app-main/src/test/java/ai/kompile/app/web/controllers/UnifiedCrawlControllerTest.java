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
import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnifiedCrawlControllerTest {

    @Test
    void historyRerunRunsSchemaEnrichmentForEnrichmentStep() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        OwlClassificationResponse expected = response();
        when(schema.generateSchemaAndTypes(7L)).thenReturn(expected);
        setSchemaEnrichmentService(controller, schema);

        OwlClassificationResponse result = invokeSchemaHistoryHook(controller, "ENRICHMENT", 7L);

        assertSame(expected, result);
        verify(schema).generateSchemaAndTypes(7L);
    }

    @Test
    void historyRerunDoesNotRunSchemaEnrichmentForStandaloneReasoningSteps() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        setSchemaEnrichmentService(controller, schema);

        OwlClassificationResponse result = invokeSchemaHistoryHook(controller, "DERIVATION", 7L);

        assertNull(result);
        verifyNoInteractions(schema);
    }

    private static void setSchemaEnrichmentService(UnifiedCrawlController controller,
                                                   OntologySchemaEnrichmentService service) throws Exception {
        Field field = UnifiedCrawlController.class.getDeclaredField("schemaEnrichmentService");
        field.setAccessible(true);
        field.set(controller, service);
    }

    private static OwlClassificationResponse invokeSchemaHistoryHook(UnifiedCrawlController controller,
                                                                     String step,
                                                                     Long factSheetId) throws Exception {
        Method method = UnifiedCrawlController.class.getDeclaredMethod(
                "runSchemaEnrichmentForHistoryRerun", String.class, Long.class);
        method.setAccessible(true);
        return (OwlClassificationResponse) method.invoke(controller, step, factSheetId);
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
