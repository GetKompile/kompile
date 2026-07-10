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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the full schema/type generation action used by crawl enrichment and the UI.
 *
 * <p>Order matters: OWL first materializes what the current ontology already entails; LLM/alias
 * induction then fills schema richness gaps; OWL runs again only when the schema changed so new
 * parent links and canonical types surface on entities.</p>
 */
@Slf4j
@Service
public class OntologySchemaEnrichmentService {

    private final OwlReasoningService owlReasoningService;
    private final OntologyTypeInductionService typeInductionService;
    private final RelationSchemaResolutionService relationSchemaResolutionService;

    public OntologySchemaEnrichmentService(OwlReasoningService owlReasoningService,
                                           OntologyTypeInductionService typeInductionService,
                                           RelationSchemaResolutionService relationSchemaResolutionService) {
        this.owlReasoningService = owlReasoningService;
        this.typeInductionService = typeInductionService;
        this.relationSchemaResolutionService = relationSchemaResolutionService;
    }

    public OwlClassificationResponse generateSchemaAndTypes(long factSheetId) {
        OwlClassificationResponse firstPass = owlReasoningService.classify(factSheetId);
        if (!firstPass.isOntologyBound()) {
            return firstPass;
        }
        OntologyTypeInductionResult induction = typeInductionService.enrichAfterOwl(factSheetId);
        // Relations resolve AFTER type induction so canonical class names (and fresh aliases) are
        // in place for domain/range alias-matching.
        RelationSchemaResolutionService.Result relations =
                relationSchemaResolutionService.resolveRelationSchema(factSheetId);
        if (!induction.changed() && !relations.changed()) {
            return firstPass;
        }
        log.info("Re-running OWL classification after schema enrichment for factSheet={}: "
                        + "aliasesAdded={}, typesAdded={}, relationsAdded={}, relationsEnriched={}",
                factSheetId, induction.aliasesAdded(), induction.typesAdded(),
                relations.definitionsAdded(), relations.definitionsEnriched());
        return owlReasoningService.classify(factSheetId);
    }
}
