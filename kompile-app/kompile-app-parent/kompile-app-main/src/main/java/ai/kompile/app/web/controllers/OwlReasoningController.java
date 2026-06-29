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

import ai.kompile.app.ontology.OwlReasoningService;
import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import ai.kompile.app.web.dto.ontology.OwlReasoningResponse;
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

    public OwlReasoningController(OwlReasoningService owlReasoningService) {
        this.owlReasoningService = owlReasoningService;
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
     * Run OWL classification (realization) on demand and persist the results — inferred is-a types +
     * has-a transitive-closure edges — returning a summary. Auto-provisions a structural ontology if
     * none is bound.
     *
     * @param factSheetId the fact sheet to classify
     * @return HTTP 200 with the {@link OwlClassificationResponse} body
     */
    @PostMapping("/classify")
    public ResponseEntity<OwlClassificationResponse> classify(@RequestParam long factSheetId) {
        return ResponseEntity.ok(owlReasoningService.classify(factSheetId));
    }
}
