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
import ai.kompile.app.web.dto.ontology.OwlReasoningResponse;
import ai.kompile.app.web.dto.ontology.TypedEntityMatchResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the OWL 2 RL reasoning pipeline.
 *
 * <p>Lives in {@code ai.kompile.app.web.controllers} so it is automatically covered by the
 * {@link ai.kompile.app.web.GlobalExceptionHandler} and receives structured error bodies on
 * failure. The {@code /api/graph-ontology} prefix groups it with other graph-ontology endpoints
 * the Angular frontend expects.</p>
 *
 * <p>A parallel frontend agent codes against the exact JSON shape defined by
 * {@link OwlReasoningResponse} — do not rename fields without a coordinated frontend change.</p>
 */
@RestController
@RequestMapping("/api/graph-ontology")
public class OwlReasoningController {

    private final OwlReasoningService owlReasoningService;
    private final OntologySchemaEnrichmentService schemaEnrichmentService;
    private final OntologyTypeInductionService typeInductionService;

    public OwlReasoningController(OwlReasoningService owlReasoningService,
                                  OntologySchemaEnrichmentService schemaEnrichmentService,
                                  OntologyTypeInductionService typeInductionService) {
        this.owlReasoningService = owlReasoningService;
        this.schemaEnrichmentService = schemaEnrichmentService;
        this.typeInductionService = typeInductionService;
    }

    /**
     * Run OWL 2 RL reasoning over the ontology bound to a fact sheet and return a structured
     * snapshot.
     *
     * <p>When no ontology is bound to the fact sheet, returns HTTP 200 with
     * {@code ontologyBound=false} and {@code reasonerActive=false} — not an error.</p>
     *
     * <p>JSON response shape (see {@link OwlReasoningResponse} for full field documentation):
     * <pre>
     * {
     *   "factSheetId":             number,
     *   "ontologyBound":           boolean,
     *   "ontologyName":            string | null,
     *   "classCount":              number,
     *   "objectPropertyCount":     number,
     *   "dataPropertyCount":       number,
     *   "axiomCount":              number,
     *   "entailmentsMaterialized": number,
     *   "consistent":              boolean,
     *   "inconsistencies":         [{ "description": string }],
     *   "sampleEntailments":       [string],
     *   "reasonerActive":          boolean
     * }
     * </pre>
     *
     * @param factSheetId the fact sheet whose bound ontology to reason over
     * @return HTTP 200 with the {@link OwlReasoningResponse} body
     */
    @GetMapping("/owl")
    public ResponseEntity<OwlReasoningResponse> owlReasoning(
            @RequestParam long factSheetId) {
        OwlReasoningResponse response = owlReasoningService.reason(factSheetId);
        return ResponseEntity.ok(response);
    }

    /**
     * Run the full schema/type generation pass on demand and persist the results: OWL first, LLM
     * schema/type enrichment for gaps, then OWL again if the schema changed. Auto-provisions a
     * structural ontology if none is bound.
     *
     * @param factSheetId the fact sheet to classify
     * @return HTTP 200 with the {@link OwlClassificationResponse} body
     */
    @PostMapping("/classify")
    public ResponseEntity<OwlClassificationResponse> classify(@RequestParam long factSheetId) {
        return ResponseEntity.ok(schemaEnrichmentService.generateSchemaAndTypes(factSheetId));
    }

    /**
     * Run only OWL classification/realization on demand and persist inferred is-a types plus has-a
     * transitive-closure edges. This intentionally skips the LLM schema enrichment pass.
     */
    @PostMapping("/owl/classify")
    public ResponseEntity<OwlClassificationResponse> classifyOwlOnly(@RequestParam long factSheetId) {
        return ResponseEntity.ok(owlReasoningService.classify(factSheetId));
    }

    /**
     * Run only the post-OWL LLM/schema induction pass. Clients that need fresh inferred
     * relationships should run OWL classification again after this endpoint reports changes.
     */
    @PostMapping("/types/induce")
    public ResponseEntity<OntologyTypeInductionResult> induceTypes(@RequestParam long factSheetId) {
        return ResponseEntity.ok(typeInductionService.enrichAfterOwl(factSheetId));
    }

    /**
     * Return entities that have a type directly or through schema/OWL inheritance. The query type is
     * resolved through canonical names, aliases, and localized labels.
     */
    @GetMapping("/types/entities")
    public ResponseEntity<TypedEntityMatchResponse> entitiesByType(@RequestParam long factSheetId,
                                                                   @RequestParam String type,
                                                                   @RequestParam(defaultValue = "true")
                                                                   boolean includeInherited,
                                                                   @RequestParam(defaultValue = "0.0")
                                                                   double minConfidence) {
        return ResponseEntity.ok(typeInductionService.findEntitiesByType(
                factSheetId, type, includeInherited, minConfidence));
    }
}
