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
package ai.kompile.knowledgegraph.reasoning.controller;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphMaterializer;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphMaterializer.MaterializationResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [L-6] REST entry point for persisting probabilistic inferences back into the knowledge graph as
 * {@code INFERRED} edges/attributes, so they accumulate across sessions rather than living only for
 * the duration of an inference request. An inference flow posts the facts it produced; they are
 * materialized into the live store via {@link InferredFactGraphMaterializer}.
 */
@RestController
@RequestMapping("/api/knowledge-graph/inferred-facts")
public class InferredFactMaterializationController {

    private final InferredFactGraphMaterializer materializer;

    public InferredFactMaterializationController(InferredFactGraphMaterializer materializer) {
        this.materializer = materializer;
    }

    /**
     * Request payload mirroring the persistable fields of {@link InferredFact} — kept separate from
     * the lib record so the HTTP boundary doesn't depend on its (zone-bearing) {@code inferredAt} or
     * its validating constructor; the timestamp is stamped server-side.
     */
    public record InferredFactRequest(
            String atomKey,
            Double value,
            Double confidence,
            List<String> supportingFactKeys,
            List<String> supportingRuleIds,
            String runId,
            Long version) {
    }

    @PostMapping("/{factSheetId}/materialize")
    public ResponseEntity<Map<String, Object>> materialize(@PathVariable Long factSheetId,
                                                           @RequestBody List<InferredFactRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No inferred facts supplied"));
        }
        List<InferredFact> facts = new ArrayList<>(requests.size());
        for (InferredFactRequest r : requests) {
            if (r.atomKey() == null || r.atomKey().isBlank()) {
                continue;
            }
            double value = clamp(r.value() == null ? 0.0 : r.value());
            double confidence = clamp(r.confidence() == null ? value : r.confidence());
            facts.add(new InferredFact(
                    r.atomKey(),
                    value,
                    confidence,
                    r.supportingFactKeys(),
                    r.supportingRuleIds(),
                    r.runId() == null || r.runId().isBlank() ? "manual" : r.runId(),
                    r.version() == null ? 1L : r.version(),
                    Instant.now()));
        }

        MaterializationResult result = materializer.materialize(facts, factSheetId);
        return ResponseEntity.ok(Map.of(
                "edgesCreated", result.edgesCreated(),
                "attributesSet", result.attributesSet(),
                "skipped", result.skipped(),
                "total", result.total()));
    }

    /** Soft-truth values must be in [0,1]; clamp defensively so a stray request can't 400 the batch. */
    private static double clamp(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
