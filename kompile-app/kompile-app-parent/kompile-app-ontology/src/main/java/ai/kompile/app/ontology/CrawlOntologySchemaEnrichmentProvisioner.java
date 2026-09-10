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
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.conformance.OntologyAutoProvisioner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Crawl-facing ontology enrichment hook.
 *
 * <p>The lower crawl module only knows about {@link OntologyAutoProvisioner}. This app-main
 * implementation makes the crawl {@code deriveOntology} pass do the same work as the manual
 * "Generate schema & types" action: auto-provision a structural ontology, run OWL-RL over the real
 * crawled graph, and persist inferred entity types/type hierarchy metadata back onto entity nodes.</p>
 */
@Slf4j
@Primary
@Service
public class CrawlOntologySchemaEnrichmentProvisioner implements OntologyAutoProvisioner {

    private final OntologySchemaEnrichmentService schemaEnrichmentService;
    private final GraphOntologyBindingService graphOntologyBindingService;

    public CrawlOntologySchemaEnrichmentProvisioner(
            OntologySchemaEnrichmentService schemaEnrichmentService,
            GraphOntologyBindingService graphOntologyBindingService) {
        this.schemaEnrichmentService = schemaEnrichmentService;
        this.graphOntologyBindingService = graphOntologyBindingService;
    }

    @Override
    public void provisionOntology(long factSheetId) {
        provisionOntology(factSheetId, null);
    }

    @Override
    public void provisionOntology(long factSheetId, GraphSchema graphSchema) {
        try {
            graphOntologyBindingService.withOntologyMutationLock(
                    factSheetId, () -> provisionOntologyLocked(factSheetId, graphSchema));
        } catch (RuntimeException e) {
            log.warn("Crawl schema enrichment failed for factSheet={} (continuing crawl): {}",
                    factSheetId, e.toString());
        }
    }

    private void provisionOntologyLocked(long factSheetId, GraphSchema graphSchema) {
        try {
            if (graphSchema != null) {
                if (graphOntologyBindingService.mergeFrozenGraphSchema(
                        factSheetId, graphSchema).isEmpty()) {
                    log.warn("Frozen crawl schema could not be persisted for factSheet={}; "
                                    + "continuing best-effort OWL enrichment against any existing binding",
                            factSheetId);
                }
            }
            OwlClassificationResponse response = schemaEnrichmentService.generateSchemaAndTypes(factSheetId);
            if (!response.isOntologyBound()) {
                log.info("Crawl schema enrichment skipped for factSheet={}: no ontology could be provisioned",
                        factSheetId);
                return;
            }
            log.info("Crawl schema enrichment complete for factSheet={}: ontology='{}', inferredTypes={}, "
                            + "inferredRelations={}, entitiesClassified={}, edgesMaterialized={}",
                    factSheetId,
                    response.getOntologyName(),
                    response.getInferredTypeCount(),
                    response.getInferredRelationCount(),
                    response.getEntitiesClassified(),
                    response.getEdgesMaterialized());
        } catch (RuntimeException e) {
            log.warn("Crawl schema enrichment transaction failed for factSheet={} (continuing crawl): {}",
                    factSheetId, e.toString());
        }
    }
}
