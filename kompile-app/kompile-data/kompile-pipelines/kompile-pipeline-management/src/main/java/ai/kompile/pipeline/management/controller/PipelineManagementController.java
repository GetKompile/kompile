package ai.kompile.pipeline.management.controller;

import ai.kompile.pipeline.management.dto.PipelineSummaryDto;
import ai.kompile.pipeline.management.dto.StepSchemaDto;
import ai.kompile.pipeline.management.dto.ValidationResult;
import ai.kompile.pipeline.management.service.PipelineManagementService;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read/write definition management only; pipeline execution is available through stdio MCP. */
@RestController
@RequestMapping("/api/pipelines")
public class PipelineManagementController {
    private final PipelineManagementService service;

    public PipelineManagementController(PipelineManagementService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PipelineSummaryDto>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UnifiedPipelineDefinition> get(@PathVariable String id) {
        UnifiedPipelineDefinition definition = service.getUnified(id);
        return definition == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(definition);
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<UnifiedPipelineDefinition>> versions(@PathVariable String id) {
        return ResponseEntity.ok(service.versions(id));
    }

    @PostMapping
    public ResponseEntity<UnifiedPipelineDefinition> create(
            @RequestBody UnifiedPipelineDefinition definition) {
        return ResponseEntity.ok(service.saveUnified(definition));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UnifiedPipelineDefinition> update(
            @PathVariable String id,
            @RequestBody UnifiedPipelineDefinition definition) {
        definition.setPipelineId(id);
        return ResponseEntity.ok(service.saveUnified(definition));
    }

    @PostMapping("/{id}/versions/{version}/promote")
    public ResponseEntity<UnifiedPipelineDefinition> promote(
            @PathVariable String id,
            @PathVariable long version,
            @RequestParam(required = false) Long expectedActiveVersion) {
        return ResponseEntity.ok(service.promote(id, version, expectedActiveVersion));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("archived", service.delete(id)));
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(
            @RequestBody UnifiedPipelineDefinition definition) {
        return ResponseEntity.ok(service.validate(definition));
    }

    @GetMapping("/steps/available")
    public ResponseEntity<List<StepSchemaDto>> availableSteps() {
        return ResponseEntity.ok(service.getAvailableStepTypes());
    }
}
