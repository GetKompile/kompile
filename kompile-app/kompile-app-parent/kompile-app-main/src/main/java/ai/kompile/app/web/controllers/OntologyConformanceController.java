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

import ai.kompile.app.ontology.GraphOntologyBindingService;
import ai.kompile.app.web.dto.ontology.GraphConformanceReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces ontology conformance of a fact sheet's knowledge graph: validates the graph's ENTITY
 * nodes against the OntologySchema bound to that fact sheet and returns a {@link GraphConformanceReport}.
 *
 * <p>Lives in {@code ai.kompile.app.web.controllers} so it is covered by the GlobalExceptionHandler,
 * and shares the {@code /api/process/ontology} prefix with the derivation endpoints.
 */
@RestController
@RequestMapping("/api/process/ontology")
public class OntologyConformanceController {

    private final GraphOntologyBindingService bindingService;

    public OntologyConformanceController(GraphOntologyBindingService bindingService) {
        this.bindingService = bindingService;
    }

    /**
     * Validate the fact sheet's graph against its bound ontology.
     *
     * @param factSheetId the fact sheet whose graph to check
     * @return the conformance report ({@code ontologyBound=false} if nothing is bound)
     */
    @GetMapping("/conformance")
    public ResponseEntity<GraphConformanceReport> checkConformance(@RequestParam Long factSheetId) {
        return ResponseEntity.ok(bindingService.checkConformance(factSheetId));
    }

    /**
     * Bind a governing ontology to a fact sheet's graph (the priority-1 explicit binding). The next
     * {@code /conformance} call validates against it. The binding is persisted in the Lucene graph.
     *
     * @param factSheetId      the fact sheet whose graph to bind
     * @param ontologySchemaId the ontology id to bind
     * @param ontologyVersion  the ontology version, or omitted/null for the latest
     */
    @PutMapping("/binding")
    public ResponseEntity<Map<String, Object>> bind(@RequestParam Long factSheetId,
                                                     @RequestParam String ontologySchemaId,
                                                     @RequestParam(required = false) Integer ontologyVersion) {
        GraphOntologyBindingService.OntologyBinding binding =
                bindingService.bindOntology(factSheetId, ontologySchemaId, ontologyVersion);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factSheetId", binding.factSheetId());
        body.put("descriptorNodeId", binding.descriptorNodeId());
        body.put("ontologySchemaId", binding.ontologySchemaId());
        body.put("ontologyVersion", binding.ontologyVersion());
        return ResponseEntity.ok(body);
    }

    /** Clear the explicit ontology binding on the fact sheet's graph. */
    @DeleteMapping("/binding")
    public ResponseEntity<Void> unbind(@RequestParam Long factSheetId) {
        bindingService.unbindOntology(factSheetId);
        return ResponseEntity.noContent().build();
    }
}
