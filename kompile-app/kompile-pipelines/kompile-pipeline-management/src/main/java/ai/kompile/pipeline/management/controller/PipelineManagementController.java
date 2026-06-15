package ai.kompile.pipeline.management.controller;

import ai.kompile.pipeline.management.dto.*;
import ai.kompile.pipeline.management.service.PipelineManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<CreatePipelineRequest> get(@PathVariable String id) {
        CreatePipelineRequest request = service.get(id);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(request);
    }

    @PostMapping
    public ResponseEntity<PipelineSummaryDto> create(@RequestBody CreatePipelineRequest request) {
        return ResponseEntity.ok(service.save(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PipelineSummaryDto> update(@PathVariable String id, @RequestBody CreatePipelineRequest request) {
        request.setPipelineId(id);
        return ResponseEntity.ok(service.save(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> delete(@PathVariable String id) {
        boolean deleted = service.delete(id);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@RequestBody CreatePipelineRequest request) {
        return ResponseEntity.ok(service.validate(request));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<PipelineExecutionResult> executeSync(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> input) {
        return ResponseEntity.ok(service.executeSync(id, input));
    }

    @PostMapping("/{id}/execute-async")
    public ResponseEntity<Map<String, String>> executeAsync(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> input) {
        String executionId = service.executeAsync(id, input);
        return ResponseEntity.ok(Map.of("executionId", executionId));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<PipelineExecutionResult> getAsyncResult(@PathVariable String executionId) {
        return ResponseEntity.ok(service.getAsyncResult(executionId));
    }

    @PostMapping("/{id}/serve")
    public ResponseEntity<Map<String, Object>> serve(@PathVariable String id) {
        boolean success = service.servePipeline(id);
        return ResponseEntity.ok(Map.of("pipelineId", id, "serving", success));
    }

    @PostMapping("/{id}/unserve")
    public ResponseEntity<Map<String, Object>> unserve(@PathVariable String id) {
        boolean success = service.unservePipeline(id);
        return ResponseEntity.ok(Map.of("pipelineId", id, "serving", false, "wasServing", success));
    }

    @GetMapping("/serving/status")
    public ResponseEntity<Map<String, Boolean>> servingStatus() {
        return ResponseEntity.ok(service.getServingStatus());
    }

    @PostMapping("/serving/{id}/invoke")
    public ResponseEntity<PipelineExecutionResult> invokeServed(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> input) {
        return ResponseEntity.ok(service.invokeServed(id, input));
    }

    @GetMapping("/steps/available")
    public ResponseEntity<List<StepSchemaDto>> availableSteps() {
        return ResponseEntity.ok(service.getAvailableStepTypes());
    }
}
