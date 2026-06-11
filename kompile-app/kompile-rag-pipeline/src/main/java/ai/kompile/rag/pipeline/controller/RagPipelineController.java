package ai.kompile.rag.pipeline.controller;

import ai.kompile.rag.pipeline.domain.RagPipelineDefinition;
import ai.kompile.rag.pipeline.service.RagPipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/rag-pipelines")
@CrossOrigin(origins = "*")
public class RagPipelineController {

    private final RagPipelineService ragPipelineService;

    public RagPipelineController(RagPipelineService ragPipelineService) {
        this.ragPipelineService = ragPipelineService;
    }

    @GetMapping
    public ResponseEntity<List<RagPipelineDefinition>> listAll() {
        return ResponseEntity.ok(ragPipelineService.listAll());
    }

    @GetMapping("/templates")
    public ResponseEntity<List<RagPipelineDefinition>> getTemplates() {
        return ResponseEntity.ok(ragPipelineService.getTemplates());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RagPipelineDefinition> getById(@PathVariable String id) {
        return ragPipelineService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RagPipelineDefinition> create(@RequestBody RagPipelineDefinition def) {
        try {
            return ResponseEntity.ok(ragPipelineService.create(def));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<RagPipelineDefinition> update(@PathVariable String id,
                                                         @RequestBody RagPipelineDefinition def) {
        try {
            return ResponseEntity.ok(ragPipelineService.update(id, def));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        try {
            ragPipelineService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{id}/model-status")
    public ResponseEntity<RagPipelineService.PipelineModelStatus> getModelStatus(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ragPipelineService.getModelStatus(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<RagPipelineService.RagPipelineResult> execute(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(ragPipelineService.execute(id, query));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
