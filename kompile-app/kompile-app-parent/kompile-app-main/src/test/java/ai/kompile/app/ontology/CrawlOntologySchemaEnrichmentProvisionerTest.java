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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlOntologySchemaEnrichmentProvisionerTest {

    @Test
    void provisionOntology_runsFullOwlClassificationMaterializationPass() {
        OntologySchemaEnrichmentService schemaEnrichmentService = mock(OntologySchemaEnrichmentService.class);
        when(schemaEnrichmentService.generateSchemaAndTypes(7L)).thenReturn(OwlClassificationResponse.builder()
                .factSheetId(7L)
                .ontologyBound(true)
                .ontologyName("crawl-derived")
                .inferredTypeCount(3)
                .inferredRelationCount(1)
                .entitiesClassified(2)
                .edgesMaterialized(1)
                .reasonerActive(true)
                .consistent(true)
                .build());

        GraphOntologyBindingService bindingService = mock(GraphOntologyBindingService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(bindingService).withOntologyMutationLock(eq(7L), any(Runnable.class));
        new CrawlOntologySchemaEnrichmentProvisioner(schemaEnrichmentService, bindingService)
                .provisionOntology(7L);
        verify(bindingService).withOntologyMutationLock(eq(7L), any(Runnable.class));

        verify(schemaEnrichmentService).generateSchemaAndTypes(7L);
    }

    @Test
    void provisionOntology_doesNotThrowIntoCrawlPipelineWhenClassificationFails() {
        OntologySchemaEnrichmentService schemaEnrichmentService = mock(OntologySchemaEnrichmentService.class);
        when(schemaEnrichmentService.generateSchemaAndTypes(7L)).thenThrow(new IllegalStateException("bad graph"));

        GraphOntologyBindingService bindingService = mock(GraphOntologyBindingService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(bindingService).withOntologyMutationLock(eq(7L), any(Runnable.class));
        new CrawlOntologySchemaEnrichmentProvisioner(schemaEnrichmentService, bindingService)
                .provisionOntology(7L);
        verify(bindingService).withOntologyMutationLock(eq(7L), any(Runnable.class));

        verify(schemaEnrichmentService).generateSchemaAndTypes(7L);
    }
}
