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

    public OntologySchemaEnrichmentService(OwlReasoningService owlReasoningService,
                                           OntologyTypeInductionService typeInductionService) {
        this.owlReasoningService = owlReasoningService;
        this.typeInductionService = typeInductionService;
    }

    public OwlClassificationResponse generateSchemaAndTypes(long factSheetId) {
        OwlClassificationResponse firstPass = owlReasoningService.classify(factSheetId);
        if (!firstPass.isOntologyBound()) {
            return firstPass;
        }
        OntologyTypeInductionResult induction = typeInductionService.enrichAfterOwl(factSheetId);
        if (!induction.changed()) {
            return firstPass;
        }
        log.info("Re-running OWL classification after type induction for factSheet={}: aliasesAdded={}, typesAdded={}",
                factSheetId, induction.aliasesAdded(), induction.typesAdded());
        return owlReasoningService.classify(factSheetId);
    }
}
