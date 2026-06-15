package ai.kompile.graphchangetracking.controller;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import ai.kompile.graphchangetracking.service.GraphUpdatePipelineConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/graph/pipelines")
public class GraphUpdatePipelineController {

    private final GraphUpdatePipelineConfigService pipelineService;

    public GraphUpdatePipelineController(GraphUpdatePipelineConfigService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public ResponseEntity<List<GraphUpdatePipelineConfig>> listPipelines() {
        return ResponseEntity.ok(pipelineService.listAll());
    }

    @PostMapping
    public ResponseEntity<GraphUpdatePipelineConfig> createPipeline(
            @RequestBody GraphUpdatePipelineConfig config) {
        return ResponseEntity.ok(pipelineService.create(config));
    }

    @GetMapping("/{pipelineId}")
    public ResponseEntity<GraphUpdatePipelineConfig> getPipeline(@PathVariable String pipelineId) {
        return pipelineService.getById(pipelineId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{pipelineId}")
    public ResponseEntity<GraphUpdatePipelineConfig> updatePipeline(
            @PathVariable String pipelineId,
            @RequestBody GraphUpdatePipelineConfig update) {
        return pipelineService.update(pipelineId, update)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{pipelineId}")
    public ResponseEntity<Void> deletePipeline(@PathVariable String pipelineId) {
        if (pipelineService.delete(pipelineId)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{pipelineId}/enable")
    public ResponseEntity<GraphUpdatePipelineConfig> enablePipeline(@PathVariable String pipelineId) {
        return pipelineService.setEnabled(pipelineId, true)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{pipelineId}/disable")
    public ResponseEntity<GraphUpdatePipelineConfig> disablePipeline(@PathVariable String pipelineId) {
        return pipelineService.setEnabled(pipelineId, false)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
