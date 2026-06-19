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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphComparison;
import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for graph health metrics over time + cross-graph comparison (Phase 7). Mirrors the
 * {@link GraphMaintenanceRestController} conventions (same module, fact-sheet-scoped paths). The UI
 * (health dashboard + compare view) is delivered separately in the Graphs-workspace phase.
 */
@RestController
@RequestMapping("/api/graph-health")
public class GraphHealthRestController {

    private final GraphHealthService healthService;

    public GraphHealthRestController(GraphHealthService healthService) {
        this.healthService = healthService;
    }

    /** Current health vector for a fact sheet's graph (computed live, not persisted). */
    @GetMapping("/{factSheetId}")
    public ResponseEntity<GraphHealthSnapshot> compute(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(healthService.computeSnapshot(factSheetId));
    }

    /** Compute and append a snapshot to the fact sheet's health time series. */
    @PostMapping("/{factSheetId}/snapshot")
    public ResponseEntity<GraphHealthSnapshot> snapshot(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(healthService.persistSnapshot(factSheetId));
    }

    /** The fact sheet's persisted health time series, oldest first. */
    @GetMapping("/{factSheetId}/history")
    public ResponseEntity<List<GraphHealthSnapshot>> history(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(healthService.listHistory(factSheetId));
    }

    /** Compare two fact sheets' graphs: shared/only-in-A/only-in-B entities + each side's metrics. */
    @GetMapping("/compare")
    public ResponseEntity<GraphComparison> compare(@RequestParam Long a, @RequestParam Long b) {
        return ResponseEntity.ok(healthService.compareGraphs(a, b));
    }
}
