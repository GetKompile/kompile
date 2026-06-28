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

import ai.kompile.crawl.graph.GraphHydrationOrchestrator;
import ai.kompile.crawl.graph.HydrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-runs individual graph-hydration stages — DERIVATION (PSL/reasoning grounding cascade),
 * PRUNE/COMPACT, ONTOLOGY-CONFORMANCE — against the EXISTING persisted graph of a fact sheet,
 * WITHOUT re-crawling.
 *
 * <p>Each stage delegates to the same {@link GraphHydrationOrchestrator} entry points the unified
 * crawl uses at its tail, so a re-run re-projects the live graph through
 * {@code GraphToFactStoreProjector} and re-runs the cascade exactly as a crawl would — it just
 * skips the (~25–30 min) loading/extraction/embedding stages in front of it. This is the per-step
 * resumability the crawl mandate calls for, and it makes the reasoning/enrichment stage iterable in
 * seconds instead of a full pipeline.
 *
 * <p>The orchestrator is optional ({@code required = false}) so the controller degrades to HTTP 503
 * rather than failing context startup if the crawl-graph reasoning beans are absent in a given
 * deployment profile.
 */
@RestController
@RequestMapping("/api/graph/hydration")
public class GraphHydrationController {

    private static final Logger log = LoggerFactory.getLogger(GraphHydrationController.class);

    @Autowired(required = false)
    private GraphHydrationOrchestrator hydrationOrchestrator;

    /**
     * Re-run ONLY the DERIVATION stage (graph → FactStore projection + PSL/reasoning grounding
     * cascade) on the existing graph. Returns the {@link HydrationResult} (relationsDerived,
     * retractedAtomCount, runId, …); the projector also logs the hard/soft atom breakdown so the
     * "why 0 derived" cause (all edges hard-1.0 vs genuine fixed-point) is visible in the job log.
     */
    @PostMapping("/{factSheetId}/derivation")
    public ResponseEntity<?> rerunDerivation(@PathVariable("factSheetId") long factSheetId) {
        if (hydrationOrchestrator == null) {
            return ResponseEntity.status(503).body("GraphHydrationOrchestrator not available");
        }
        log.info("[GraphHydration] Re-running DERIVATION-only on existing graph for factSheet={}", factSheetId);
        HydrationResult result = hydrationOrchestrator.runDerivationOnly(factSheetId);
        return ResponseEntity.ok(result);
    }

    /** Re-run ONLY the PRUNE/COMPACT stage on the existing graph. */
    @PostMapping("/{factSheetId}/prune")
    public ResponseEntity<?> rerunPrune(@PathVariable("factSheetId") long factSheetId) {
        if (hydrationOrchestrator == null) {
            return ResponseEntity.status(503).body("GraphHydrationOrchestrator not available");
        }
        log.info("[GraphHydration] Re-running PRUNE-only on existing graph for factSheet={}", factSheetId);
        HydrationResult result = hydrationOrchestrator.runPruneOnly(factSheetId);
        return ResponseEntity.ok(result);
    }

    /** Re-run ONLY the ONTOLOGY-CONFORMANCE stage on the existing graph. */
    @PostMapping("/{factSheetId}/ontology-conformance")
    public ResponseEntity<?> rerunOntologyConformance(@PathVariable("factSheetId") long factSheetId) {
        if (hydrationOrchestrator == null) {
            return ResponseEntity.status(503).body("GraphHydrationOrchestrator not available");
        }
        log.info("[GraphHydration] Re-running ONTOLOGY-CONFORMANCE-only on existing graph for factSheet={}", factSheetId);
        HydrationResult result = hydrationOrchestrator.runOntologyConformanceOnly(factSheetId);
        return ResponseEntity.ok(result);
    }
}
