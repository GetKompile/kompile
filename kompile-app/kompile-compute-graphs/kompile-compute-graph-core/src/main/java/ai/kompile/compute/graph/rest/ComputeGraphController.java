package ai.kompile.compute.graph.rest;

import ai.kompile.compute.graph.config.ComputeGraphConfig;
import ai.kompile.compute.graph.config.ComputeGraphConfigService;
import ai.kompile.compute.graph.engine.ComputeGraphEngine;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.ArtifactStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for managing compute graph configuration and executing compute graphs.
 * All configuration is done through this API (accessible from the UI).
 */
@Slf4j
@RestController
@RequestMapping("/api/compute-graph")
@RequiredArgsConstructor
public class ComputeGraphController {

    private final ComputeGraphEngine engine;
    private final ArtifactStore artifactStore;
    private final ComputeGraphConfigService configService;
    private final List<NodeExecutor> nodeExecutors;

    // ==================== Configuration Endpoints ====================

    /**
     * Get the current compute graph configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<ComputeGraphConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Update the compute graph configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<ComputeGraphConfig> updateConfig(@RequestBody ComputeGraphConfig config) {
        return ResponseEntity.ok(configService.updateConfig(config));
    }

    /**
     * Get current status of the compute graph engine.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        ComputeGraphConfig config = configService.getConfig();
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", config.isEnabled());
        status.put("scriptingEnabled", config.isScriptingEnabled());
        status.put("droolsEnabled", config.isDroolsEnabled());
        status.put("droolsInferenceEnabled", config.isDroolsInferenceEnabled());
        status.put("xircuitsEnabled", config.isXircuitsEnabled());
        status.put("n8nEnabled", config.isN8nEnabled());
        return ResponseEntity.ok(status);
    }

    /**
     * Probe actual runtime availability of each compute backend.
     * Unlike /status (which reports config flags), this checks whether the required
     * classes are on the classpath and which NodeExecutor beans are registered.
     */
    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> getAvailability() {
        Map<String, Object> availability = new LinkedHashMap<>();

        // Collect which NodeExecutionTypes are actually serviced by registered executors
        Set<NodeExecutionType> serviced = nodeExecutors.stream()
                .flatMap(e -> e.supportedTypes().stream())
                .collect(Collectors.toSet());

        // JavaScript (GraalVM Polyglot)
        availability.put("javascript", buildBackendInfo(
                "GraalVM Polyglot JS",
                isClassPresent("org.graalvm.polyglot.Context"),
                serviced.contains(NodeExecutionType.JAVASCRIPT),
                getGraalVmVersion()));

        // Python (Python4J / CPython)
        availability.put("python", buildBackendInfo(
                "Python4J (CPython via JavaCPP)",
                isClassPresent("org.nd4j.python4j.PythonExecutioner"),
                serviced.contains(NodeExecutionType.PYTHON),
                null));

        // Drools Rules
        availability.put("drools_rule", buildBackendInfo(
                "Drools / KIE",
                isClassPresent("org.kie.api.KieServices"),
                serviced.contains(NodeExecutionType.DROOLS_RULE),
                null));

        // Drools Inference
        availability.put("drools_inference", buildBackendInfo(
                "Drools Inference Engine",
                isClassPresent("org.kie.api.KieServices"),
                serviced.contains(NodeExecutionType.DROOLS_INFERENCE),
                null));

        // Drools Decision Table
        availability.put("drools_decision_table", buildBackendInfo(
                "Drools Decision Tables",
                isClassPresent("org.kie.api.KieServices"),
                serviced.contains(NodeExecutionType.DROOLS_DECISION_TABLE),
                null));

        // Expression (SpEL - always available via spring-expression)
        availability.put("expression", buildBackendInfo(
                "Spring Expression Language (SpEL)",
                isClassPresent("org.springframework.expression.ExpressionParser"),
                serviced.contains(NodeExecutionType.EXPRESSION),
                null));

        // Passthrough (always available from core)
        availability.put("passthrough", buildBackendInfo(
                "Passthrough (no-op)",
                true,
                serviced.contains(NodeExecutionType.PASSTHROUGH),
                null));

        // Excel Compute
        availability.put("excel", buildBackendInfo(
                "Excel Compute (LLM formula conversion)",
                isClassPresent("ai.kompile.compute.graph.excel.ExcelNodeExecutor"),
                serviced.contains(NodeExecutionType.EXCEL),
                null));

        // Apache Camel
        availability.put("camel_route", buildBackendInfo(
                "Apache Camel Routes",
                isClassPresent("org.apache.camel.CamelContext"),
                serviced.contains(NodeExecutionType.CAMEL_ROUTE),
                null));

        // Xircuits
        availability.put("xircuits", buildBackendInfo(
                "Xircuits Workflow Engine",
                isClassPresent("ai.kompile.compute.graph.xircuits.XircuitsNodeExecutor"),
                serviced.contains(NodeExecutionType.XIRCUITS),
                null));

        // n8n
        availability.put("n8n", buildBackendInfo(
                "n8n Workflow Automation",
                isClassPresent("ai.kompile.compute.graph.n8n.N8nNodeExecutor"),
                serviced.contains(NodeExecutionType.N8N),
                null));

        // Summary
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backends", availability);
        result.put("registeredExecutors", nodeExecutors.stream()
                .map(e -> Map.of(
                        "class", e.getClass().getSimpleName(),
                        "types", e.supportedTypes().stream()
                                .map(Enum::name)
                                .collect(Collectors.toList())))
                .collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildBackendInfo(String name, boolean classpathAvailable,
                                                  boolean executorRegistered, String version) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("classpathAvailable", classpathAvailable);
        info.put("executorRegistered", executorRegistered);
        info.put("ready", classpathAvailable && executorRegistered);
        if (version != null) {
            info.put("version", version);
        }
        return info;
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String getGraalVmVersion() {
        try {
            Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine", false, getClass().getClassLoader());
            Object engine = engineClass.getMethod("create").invoke(null);
            String version = (String) engineClass.getMethod("getVersion").invoke(engine);
            engineClass.getMethod("close").invoke(engine);
            return version;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Execution Endpoints ====================

    /**
     * Execute a compute graph with the given inputs.
     */
    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody ExecuteRequest request) {
        if (!configService.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Compute graph engine is disabled. Enable it in Settings → Compute Graph."));
        }
        GraphExecutionResult result = engine.execute(request.getGraph(), request.getInputs());
        return ResponseEntity.ok(result);
    }

    /**
     * Execute a single node in isolation for testing/debugging.
     */
    @PostMapping("/execute-node")
    public ResponseEntity<?> executeNode(@RequestBody ExecuteNodeRequest request) {
        if (!configService.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Compute graph engine is disabled. Enable it in Settings → Compute Graph."));
        }
        GraphExecutionResult result = engine.executeSingleNode(
                request.getGraph(), request.getNodeId(), request.getInputs());
        return ResponseEntity.ok(result);
    }

    /**
     * Validate a graph definition without executing it.
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody ComputeGraph graph) {
        String error = engine.validate(graph);
        if (error == null) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.ok(Map.of("valid", false, "errors", error));
        }
    }

    // ==================== Artifact Endpoints ====================

    /**
     * Retrieve artifacts for a given execution.
     */
    @GetMapping("/artifacts/{executionId}")
    public ResponseEntity<List<ComputeArtifact>> getArtifacts(@PathVariable String executionId) {
        return ResponseEntity.ok(artifactStore.getByExecutionId(executionId));
    }

    /**
     * Retrieve artifacts for a specific node in an execution.
     */
    @GetMapping("/artifacts/{executionId}/{nodeId}")
    public ResponseEntity<List<ComputeArtifact>> getNodeArtifacts(
            @PathVariable String executionId, @PathVariable String nodeId) {
        return ResponseEntity.ok(artifactStore.getByExecutionAndNode(executionId, nodeId));
    }

    /**
     * Delete all artifacts for an execution.
     */
    @DeleteMapping("/artifacts/{executionId}")
    public ResponseEntity<Void> deleteArtifacts(@PathVariable String executionId) {
        artifactStore.deleteByExecutionId(executionId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Request DTOs ====================

    @lombok.Data
    public static class ExecuteRequest {
        private ComputeGraph graph;
        private Map<String, Object> inputs;
    }

    @lombok.Data
    public static class ExecuteNodeRequest {
        private ComputeGraph graph;
        private String nodeId;
        private Map<String, Object> inputs;
    }
}
