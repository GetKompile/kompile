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

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.crawl.graph.simulation.SimulationRunService;
import ai.kompile.crawl.graph.simulation.SimulationRunService.RunMode;
import ai.kompile.crawl.graph.simulation.SimulationRunService.RunSnapshot;
import ai.kompile.crawl.graph.simulation.SimulationRunService.StartSpec;
import ai.kompile.crawl.graph.simulation.SimulationRunService.TruthOverlay;
import ai.kompile.knowledgegraph.service.FactSheetGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST surface for the Graph Simulator ("Graph Lab"): sandbox fact sheets hydrated from
 * synthetic scenarios, reasoned over by the existing hydration cascade, scored against planted
 * ground truth. See {@code docs/architecture/graph-simulator-design.md}.
 *
 * <p>This controller owns the fact-sheet ROW lifecycle (create on start, delete on dispose —
 * guarded by the simulator description marker so it can never delete a non-sandbox sheet);
 * everything graph-side lives in {@link SimulationRunService} (crawl-graph). Live progress is
 * served by the existing {@code /api/crawl-events/stream/{runId}} SSE channel.</p>
 */
@RestController
@RequestMapping("/api/graph-sim")
public class GraphSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(GraphSimulatorController.class);

    /** Description marker identifying simulator sandboxes; the dispose guard checks it. */
    public static final String SIM_SHEET_MARKER = "Graph Simulator sandbox";

    private final SimulationRunService simulationRunService;
    private final FactSheetService factSheetService;

    @Autowired(required = false)
    @Nullable
    private FactSheetGraphService factSheetGraphService;

    public GraphSimulatorController(SimulationRunService simulationRunService,
                                    FactSheetService factSheetService) {
        this.simulationRunService = simulationRunService;
        this.factSheetService = factSheetService;
    }

    /** Start request; every field except scenarioId has a sensible default. */
    public record StartRunRequest(
            String scenarioId,
            Long seed,
            Map<String, Object> params,
            String mode,                    // ALL | STEP | PLAY (default ALL)
            Integer reasonEveryK,           // PLAY cadence; <=0 = reason only at the end
            Set<String> enabledStages,      // empty = all hydration stages
            Double confidencePruneThreshold,
            Boolean dryRun,
            String name) {                  // optional sheet-name override
    }

    @GetMapping("/scenarios")
    public ResponseEntity<List<Map<String, Object>>> scenarios() {
        return ResponseEntity.ok(simulationRunService.listScenarios());
    }

    @GetMapping("/runs")
    public ResponseEntity<List<RunSnapshot>> runs() {
        return ResponseEntity.ok(simulationRunService.listRuns());
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunSnapshot> run(@PathVariable String runId) {
        return ResponseEntity.ok(simulationRunService.getRun(runId));
    }

    @PostMapping("/runs")
    public ResponseEntity<?> start(@RequestBody StartRunRequest request) {
        if (request == null || request.scenarioId() == null || request.scenarioId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "scenarioId is required"));
        }
        long seed = request.seed() != null ? request.seed() : 42L;
        RunMode mode = parseMode(request.mode());
        int reasonEveryK = request.reasonEveryK() != null ? request.reasonEveryK()
                : (mode == RunMode.PLAY ? 1 : 0);
        StartSpec spec = new StartSpec(
                request.scenarioId(), seed,
                request.params(), mode, reasonEveryK,
                request.enabledStages(),
                request.confidencePruneThreshold() != null ? request.confidencePruneThreshold() : 0.4,
                Boolean.TRUE.equals(request.dryRun()));

        String sheetName = (request.name() != null && !request.name().isBlank())
                ? request.name()
                : "Sim: " + request.scenarioId() + " #" + seed + "-" + (System.currentTimeMillis() % 100_000);
        FactSheet sheet;
        try {
            sheet = factSheetService.createSheet(sheetName,
                    SIM_SHEET_MARKER + " | scenario=" + request.scenarioId() + " seed=" + seed,
                    "#7c4dff", "science");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        try {
            RunSnapshot snapshot = simulationRunService.startRun(sheet.getId(), spec);
            log.info("Graph-simulator run {} started on sandbox fact sheet {} ('{}')",
                    snapshot.runId(), sheet.getId(), sheetName);
            return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Roll the sandbox sheet back so failed starts don't leak sheets.
            try {
                factSheetService.deleteSheet(sheet.getId());
            } catch (RuntimeException cleanup) {
                log.warn("Failed to clean up sandbox sheet {} after start failure: {}",
                        sheet.getId(), cleanup.getMessage());
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/runs/{runId}/step")
    public ResponseEntity<RunSnapshot> step(@PathVariable String runId) {
        return ResponseEntity.ok(simulationRunService.step(runId));
    }

    @PostMapping("/runs/{runId}/play")
    public ResponseEntity<RunSnapshot> play(@PathVariable String runId) {
        return ResponseEntity.ok(simulationRunService.play(runId));
    }

    @PostMapping("/runs/{runId}/pause")
    public ResponseEntity<RunSnapshot> pause(@PathVariable String runId) {
        return ResponseEntity.ok(simulationRunService.pause(runId));
    }

    @PostMapping("/runs/{runId}/reason")
    public ResponseEntity<RunSnapshot> reason(@PathVariable String runId) {
        return ResponseEntity.ok(simulationRunService.reasonNow(runId));
    }

    @GetMapping("/runs/{runId}/ground-truth")
    public ResponseEntity<TruthOverlay> groundTruth(@PathVariable String runId) {
        return ResponseEntity.ok(simulationRunService.groundTruth(runId));
    }

    /**
     * Dispose a run AND its sandbox fact sheet + graph. Refuses when the sheet does not carry
     * the simulator marker (defense against ever deleting a real sheet through this path).
     */
    @DeleteMapping("/runs/{runId}")
    public ResponseEntity<?> dispose(@PathVariable String runId) {
        long factSheetId = simulationRunService.factSheetIdOf(runId);
        FactSheet sheet = factSheetService.getSheetById(factSheetId).orElse(null);
        if (sheet != null && (sheet.getDescription() == null
                || !sheet.getDescription().contains(SIM_SHEET_MARKER))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Fact sheet " + factSheetId + " is not a simulator sandbox — refusing to delete"));
        }
        simulationRunService.dispose(runId);
        if (factSheetGraphService != null) {
            try {
                factSheetGraphService.clearGraph(factSheetId);
            } catch (RuntimeException e) {
                log.warn("Failed to clear sandbox graph for fact sheet {}: {}", factSheetId, e.getMessage());
            }
        }
        if (sheet != null) {
            try {
                factSheetService.deleteSheet(factSheetId);
            } catch (RuntimeException e) {
                log.warn("Failed to delete sandbox fact sheet {}: {}", factSheetId, e.getMessage());
            }
        }
        return ResponseEntity.noContent().build();
    }

    /** Keep the sandbox as a permanent sheet: drops the run bookkeeping, keeps sheet + graph. */
    @PostMapping("/runs/{runId}/promote")
    public ResponseEntity<Map<String, Object>> promote(@PathVariable String runId) {
        long factSheetId = simulationRunService.factSheetIdOf(runId);
        simulationRunService.dispose(runId);
        return ResponseEntity.ok(Map.of("factSheetId", factSheetId, "kept", true));
    }

    private static RunMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) return RunMode.ALL;
        try {
            return RunMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RunMode.ALL;
        }
    }
}
