package ai.kompile.app.tools;

import ai.kompile.compute.graph.config.ComputeGraphConfig;
import ai.kompile.compute.graph.config.ComputeGraphConfigService;
import ai.kompile.compute.graph.engine.ComputeGraphEngine;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ComputeGraphTool {

    private static final Logger logger = LoggerFactory.getLogger(ComputeGraphTool.class);

    private final ComputeGraphEngine engine;
    private final ArtifactStore artifactStore;
    private final ComputeGraphConfigService configService;

    @Autowired
    public ComputeGraphTool(
            @Autowired(required = false) ComputeGraphEngine engine,
            @Autowired(required = false) ArtifactStore artifactStore,
            @Autowired(required = false) ComputeGraphConfigService configService) {
        this.engine = engine;
        this.artifactStore = artifactStore;
        this.configService = configService;
        logger.info("ComputeGraphTool initialized");
    }

    // ==================== Input Records ====================

    public record GetComputeGraphConfigInput() {}

    public record UpdateComputeGraphConfigInput(
            Boolean enabled,
            Boolean scriptingEnabled,
            Boolean droolsEnabled,
            Boolean droolsInferenceEnabled,
            Long defaultMaxCpuTimeMs,
            Long defaultMaxHeapMemoryBytes,
            Integer defaultMaxStackFrames,
            Boolean defaultAllowIO,
            Boolean defaultAllowNetwork,
            Boolean defaultAllowHostAccess,
            Integer maxRuleFiringsPerNode,
            Integer maxRuleFiringsTotal
    ) {}

    public record ExecuteComputeGraphInput(
            ComputeGraph graph,
            Map<String, Object> inputs
    ) {}

    public record ExecuteSingleNodeInput(
            ComputeGraph graph,
            String nodeId,
            Map<String, Object> inputs
    ) {}

    public record ValidateComputeGraphInput(
            ComputeGraph graph
    ) {}

    public record GetComputeGraphStatusInput() {}

    public record ListArtifactsInput(String executionId) {}

    public record GetNodeArtifactsInput(String executionId, String nodeId) {}

    public record DeleteArtifactsInput(String executionId) {}

    // ==================== Tool Methods ====================

    @Tool(name = "get_compute_graph_config",
            description = "Gets the current compute graph engine configuration including enabled status, scripting/drools flags, resource limits, and security settings.")
    public Map<String, Object> getComputeGraphConfig(GetComputeGraphConfigInput input) {
        try {
            if (configService == null) {
                return Map.of("status", "error", "error", "ComputeGraphConfigService not available");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            ComputeGraphConfig config = configService.getConfig();
            result.put("enabled", config.isEnabled());
            result.put("scriptingEnabled", config.isScriptingEnabled());
            result.put("droolsEnabled", config.isDroolsEnabled());
            result.put("droolsInferenceEnabled", config.isDroolsInferenceEnabled());
            result.put("defaultMaxCpuTimeMs", config.getDefaultMaxCpuTimeMs());
            result.put("defaultMaxHeapMemoryBytes", config.getDefaultMaxHeapMemoryBytes());
            result.put("defaultMaxStackFrames", config.getDefaultMaxStackFrames());
            result.put("defaultAllowIO", config.isDefaultAllowIO());
            result.put("defaultAllowNetwork", config.isDefaultAllowNetwork());
            result.put("defaultAllowHostAccess", config.isDefaultAllowHostAccess());
            result.put("maxRuleFiringsPerNode", config.getMaxRuleFiringsPerNode());
            result.put("maxRuleFiringsTotal", config.getMaxRuleFiringsTotal());
            return result;
        } catch (Exception e) {
            logger.error("Error getting compute graph config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "update_compute_graph_config",
            description = "Updates the compute graph engine configuration. Only non-null fields are applied. Controls engine enable/disable, scripting/drools backends, resource limits (CPU time, heap memory, stack frames), and security settings (IO, network, host access).")
    public Map<String, Object> updateComputeGraphConfig(UpdateComputeGraphConfigInput input) {
        try {
            if (configService == null) {
                return Map.of("status", "error", "error", "ComputeGraphConfigService not available");
            }
            ComputeGraphConfig current = configService.getConfig();
            ComputeGraphConfig.ComputeGraphConfigBuilder builder = current.toBuilder();

            if (input.enabled() != null) builder.enabled(input.enabled());
            if (input.scriptingEnabled() != null) builder.scriptingEnabled(input.scriptingEnabled());
            if (input.droolsEnabled() != null) builder.droolsEnabled(input.droolsEnabled());
            if (input.droolsInferenceEnabled() != null) builder.droolsInferenceEnabled(input.droolsInferenceEnabled());
            if (input.defaultMaxCpuTimeMs() != null) builder.defaultMaxCpuTimeMs(input.defaultMaxCpuTimeMs());
            if (input.defaultMaxHeapMemoryBytes() != null) builder.defaultMaxHeapMemoryBytes(input.defaultMaxHeapMemoryBytes());
            if (input.defaultMaxStackFrames() != null) builder.defaultMaxStackFrames(input.defaultMaxStackFrames());
            if (input.defaultAllowIO() != null) builder.defaultAllowIO(input.defaultAllowIO());
            if (input.defaultAllowNetwork() != null) builder.defaultAllowNetwork(input.defaultAllowNetwork());
            if (input.defaultAllowHostAccess() != null) builder.defaultAllowHostAccess(input.defaultAllowHostAccess());
            if (input.maxRuleFiringsPerNode() != null) builder.maxRuleFiringsPerNode(input.maxRuleFiringsPerNode());
            if (input.maxRuleFiringsTotal() != null) builder.maxRuleFiringsTotal(input.maxRuleFiringsTotal());

            ComputeGraphConfig updated = configService.updateConfig(builder.build());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Compute graph configuration updated");
            result.put("enabled", updated.isEnabled());
            return result;
        } catch (Exception e) {
            logger.error("Error updating compute graph config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_compute_graph_status",
            description = "Gets the current status of the compute graph engine including which backends (scripting, drools) are enabled.")
    public Map<String, Object> getComputeGraphStatus(GetComputeGraphStatusInput input) {
        try {
            if (configService == null) {
                return Map.of("status", "error", "error", "ComputeGraphConfigService not available");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("enabled", configService.isEnabled());
            result.put("scriptingEnabled", configService.isScriptingEnabled());
            result.put("droolsEnabled", configService.isDroolsEnabled());
            result.put("droolsInferenceEnabled", configService.isDroolsInferenceEnabled());
            return result;
        } catch (Exception e) {
            logger.error("Error getting compute graph status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "execute_compute_graph",
            description = "Executes a compute graph with the given inputs. The graph defines nodes (JavaScript, Python, Drools rules, expressions, or passthrough) connected by directed edges with optional conditions and data mappings. Returns per-node results, final outputs, and any artifacts produced.")
    public Map<String, Object> executeComputeGraph(ExecuteComputeGraphInput input) {
        try {
            if (engine == null) {
                return Map.of("status", "error", "error", "ComputeGraphEngine not available");
            }
            if (configService != null && !configService.isEnabled()) {
                return Map.of("status", "error", "error", "Compute graph engine is disabled. Enable it via update_compute_graph_config.");
            }
            GraphExecutionResult execResult = engine.execute(input.graph(), input.inputs());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("executionId", execResult.getExecutionId());
            result.put("graphId", execResult.getGraphId());
            result.put("executionStatus", execResult.getStatus().name());
            result.put("executionOrder", execResult.getExecutionOrder());
            result.put("finalOutputs", execResult.getFinalOutputs());
            result.put("totalDuration", execResult.getTotalDuration() != null ? execResult.getTotalDuration().toMillis() + "ms" : null);
            result.put("skippedNodes", execResult.getSkippedNodes());

            // Summarize per-node results
            Map<String, Object> nodeResults = new LinkedHashMap<>();
            if (execResult.getNodeResults() != null) {
                for (Map.Entry<String, ExecutionResult> entry : execResult.getNodeResults().entrySet()) {
                    ExecutionResult nr = entry.getValue();
                    Map<String, Object> nodeInfo = new LinkedHashMap<>();
                    nodeInfo.put("status", nr.getStatus().name());
                    nodeInfo.put("outputs", nr.getOutputs());
                    if (nr.getError() != null) nodeInfo.put("error", nr.getError());
                    if (nr.getConsoleOutput() != null && !nr.getConsoleOutput().isEmpty()) {
                        nodeInfo.put("consoleOutput", nr.getConsoleOutput());
                    }
                    if (nr.getDuration() != null) nodeInfo.put("duration", nr.getDuration().toMillis() + "ms");
                    nodeResults.put(entry.getKey(), nodeInfo);
                }
            }
            result.put("nodeResults", nodeResults);
            return result;
        } catch (Exception e) {
            logger.error("Error executing compute graph: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "execute_single_compute_node",
            description = "Executes a single node within a compute graph in isolation for testing and debugging. Provide the full graph definition, the target node ID, and inputs.")
    public Map<String, Object> executeSingleNode(ExecuteSingleNodeInput input) {
        try {
            if (engine == null) {
                return Map.of("status", "error", "error", "ComputeGraphEngine not available");
            }
            if (configService != null && !configService.isEnabled()) {
                return Map.of("status", "error", "error", "Compute graph engine is disabled.");
            }
            GraphExecutionResult execResult = engine.executeSingleNode(
                    input.graph(), input.nodeId(), input.inputs());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("executionId", execResult.getExecutionId());
            result.put("executionStatus", execResult.getStatus().name());
            result.put("finalOutputs", execResult.getFinalOutputs());
            if (execResult.getNodeResults() != null && execResult.getNodeResults().containsKey(input.nodeId())) {
                ExecutionResult nr = execResult.getNodeResults().get(input.nodeId());
                result.put("nodeOutputs", nr.getOutputs());
                if (nr.getError() != null) result.put("error", nr.getError());
                if (nr.getConsoleOutput() != null && !nr.getConsoleOutput().isEmpty()) {
                    result.put("consoleOutput", nr.getConsoleOutput());
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("Error executing single node: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "validate_compute_graph",
            description = "Validates a compute graph definition without executing it. Checks for cycles, missing node executors, script syntax errors, and structural issues.")
    public Map<String, Object> validateComputeGraph(ValidateComputeGraphInput input) {
        try {
            if (engine == null) {
                return Map.of("status", "error", "error", "ComputeGraphEngine not available");
            }
            String error = engine.validate(input.graph());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("valid", error == null);
            if (error != null) result.put("errors", error);
            return result;
        } catch (Exception e) {
            logger.error("Error validating compute graph: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_compute_artifacts",
            description = "Lists all computation artifacts produced by a specific graph execution. Artifacts include stored outputs, computed data, and binary content.")
    public Map<String, Object> listArtifacts(ListArtifactsInput input) {
        try {
            if (artifactStore == null) {
                return Map.of("status", "error", "error", "ArtifactStore not available");
            }
            List<ComputeArtifact> artifacts = artifactStore.getByExecutionId(input.executionId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("executionId", input.executionId());
            result.put("count", artifacts.size());
            List<Map<String, Object>> artifactList = new ArrayList<>();
            for (ComputeArtifact a : artifacts) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", a.getId());
                info.put("nodeId", a.getNodeId());
                info.put("name", a.getName());
                info.put("contentType", a.getContentType());
                info.put("sizeBytes", a.getSizeBytes());
                if (a.getData() != null) info.put("data", a.getData());
                artifactList.add(info);
            }
            result.put("artifacts", artifactList);
            return result;
        } catch (Exception e) {
            logger.error("Error listing artifacts: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_compute_node_artifacts",
            description = "Gets computation artifacts for a specific node within an execution.")
    public Map<String, Object> getNodeArtifacts(GetNodeArtifactsInput input) {
        try {
            if (artifactStore == null) {
                return Map.of("status", "error", "error", "ArtifactStore not available");
            }
            List<ComputeArtifact> artifacts = artifactStore.getByExecutionAndNode(
                    input.executionId(), input.nodeId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", artifacts.size());
            List<Map<String, Object>> artifactList = new ArrayList<>();
            for (ComputeArtifact a : artifacts) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", a.getId());
                info.put("name", a.getName());
                info.put("contentType", a.getContentType());
                info.put("sizeBytes", a.getSizeBytes());
                if (a.getData() != null) info.put("data", a.getData());
                artifactList.add(info);
            }
            result.put("artifacts", artifactList);
            return result;
        } catch (Exception e) {
            logger.error("Error getting node artifacts: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_compute_artifacts",
            description = "Deletes all computation artifacts for a specific graph execution.")
    public Map<String, Object> deleteArtifacts(DeleteArtifactsInput input) {
        try {
            if (artifactStore == null) {
                return Map.of("status", "error", "error", "ArtifactStore not available");
            }
            artifactStore.deleteByExecutionId(input.executionId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Artifacts deleted for execution: " + input.executionId());
            return result;
        } catch (Exception e) {
            logger.error("Error deleting artifacts: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
