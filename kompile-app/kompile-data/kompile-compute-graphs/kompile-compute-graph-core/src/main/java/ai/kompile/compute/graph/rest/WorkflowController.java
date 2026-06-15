package ai.kompile.compute.graph.rest;

import ai.kompile.compute.graph.model.ComputeArtifact;
import ai.kompile.compute.graph.store.WorkflowFileStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/**
 * REST API for managing workflow definition files (Xircuits and n8n).
 * Provides CRUD operations plus introspection into workflow structure.
 */
@Slf4j
@RestController("computeGraphWorkflowController")
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowFileStore workflowStore;

    // ==================== CRUD Endpoints ====================

    /**
     * List all workflows, optionally filtered by engine type.
     */
    @GetMapping
    public ResponseEntity<List<ComputeArtifact>> listWorkflows(
            @RequestParam(required = false) String engineType) throws IOException {
        if (engineType != null) {
            return ResponseEntity.ok(workflowStore.list(engineType));
        }
        return ResponseEntity.ok(workflowStore.listAll());
    }

    /**
     * Get a workflow definition by engine type and name.
     */
    @GetMapping("/{engineType}/{name}")
    public ResponseEntity<?> getWorkflow(@PathVariable String engineType,
                                          @PathVariable String name) throws IOException {
        Optional<String> content = workflowStore.load(engineType, name);
        if (content.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<Map<String, Object>> meta = workflowStore.loadMetadata(engineType, name);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("engineType", engineType);
        response.put("name", name);
        response.put("metadata", meta.orElse(Map.of()));
        response.put("content", content.get());
        return ResponseEntity.ok(response);
    }

    /**
     * Save a workflow definition.
     */
    @PostMapping("/{engineType}/{name}")
    public ResponseEntity<ComputeArtifact> saveWorkflow(
            @PathVariable String engineType,
            @PathVariable String name,
            @RequestBody SaveWorkflowRequest request) throws IOException {
        ComputeArtifact artifact = workflowStore.save(engineType, name,
                request.getContent(), request.getMetadata());
        return ResponseEntity.ok(artifact);
    }

    /**
     * Update a workflow definition.
     */
    @PutMapping("/{engineType}/{name}")
    public ResponseEntity<ComputeArtifact> updateWorkflow(
            @PathVariable String engineType,
            @PathVariable String name,
            @RequestBody SaveWorkflowRequest request) throws IOException {
        if (!workflowStore.exists(engineType, name)) {
            return ResponseEntity.notFound().build();
        }
        ComputeArtifact artifact = workflowStore.save(engineType, name,
                request.getContent(), request.getMetadata());
        return ResponseEntity.ok(artifact);
    }

    /**
     * Delete a workflow definition.
     */
    @DeleteMapping("/{engineType}/{name}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable String engineType,
                                                @PathVariable String name) throws IOException {
        boolean deleted = workflowStore.delete(engineType, name);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ==================== Introspection Endpoints ====================

    /**
     * Inspect a Xircuits workflow — extract nodes, links, argument nodes, and execution flow.
     */
    @PostMapping("/inspect/xircuits")
    public ResponseEntity<?> inspectXircuits(@RequestBody String workflowJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = om.readTree(workflowJson);

            Map<String, Object> inspection = new LinkedHashMap<>();
            inspection.put("engineType", "xircuits");

            // Extract basic info
            inspection.put("id", tree.path("id").asText(null));

            // Parse nodes layer
            var layers = tree.path("layers");
            List<Map<String, Object>> nodeInfos = new ArrayList<>();
            List<Map<String, Object>> linkInfos = new ArrayList<>();
            List<Map<String, Object>> arguments = new ArrayList<>();

            if (layers.isArray() && layers.size() >= 2) {
                // Layer 0: links
                var linksModels = layers.get(0).path("models");
                linksModels.fields().forEachRemaining(entry -> {
                    var link = entry.getValue();
                    Map<String, Object> linkInfo = new LinkedHashMap<>();
                    linkInfo.put("id", link.path("id").asText());
                    linkInfo.put("type", link.path("type").asText());
                    linkInfo.put("source", link.path("source").asText());
                    linkInfo.put("sourcePort", link.path("sourcePort").asText());
                    linkInfo.put("target", link.path("target").asText());
                    linkInfo.put("targetPort", link.path("targetPort").asText());
                    linkInfos.add(linkInfo);
                });

                // Layer 1: nodes
                var nodesModels = layers.get(1).path("models");
                nodesModels.fields().forEachRemaining(entry -> {
                    var node = entry.getValue();
                    Map<String, Object> nodeInfo = new LinkedHashMap<>();
                    nodeInfo.put("id", node.path("id").asText());
                    nodeInfo.put("name", node.path("name").asText());
                    String compType = node.path("extras").path("type").asText(null);
                    nodeInfo.put("componentType", compType);
                    nodeInfo.put("sourcePath", node.path("extras").path("path").asText(null));

                    // Extract ports
                    List<Map<String, String>> ports = new ArrayList<>();
                    node.path("ports").forEach(port -> {
                        Map<String, String> portInfo = new LinkedHashMap<>();
                        portInfo.put("name", port.path("name").asText());
                        portInfo.put("label", port.path("label").asText());
                        portInfo.put("dataType", port.path("dataType").asText());
                        portInfo.put("direction", port.path("in").asBoolean() ? "input" : "output");
                        ports.add(portInfo);
                    });
                    nodeInfo.put("ports", ports);
                    nodeInfos.add(nodeInfo);

                    // Collect argument nodes
                    String nodeName = node.path("name").asText();
                    if (nodeName != null && nodeName.startsWith("Argument ")) {
                        Map<String, Object> argInfo = new LinkedHashMap<>();
                        argInfo.put("nodeId", node.path("id").asText());
                        argInfo.put("fullName", nodeName);
                        // Parse "Argument (type): name"
                        int parenStart = nodeName.indexOf('(');
                        int parenEnd = nodeName.indexOf(')');
                        int colonIdx = nodeName.indexOf("): ");
                        if (parenStart >= 0 && parenEnd > parenStart && colonIdx >= 0) {
                            argInfo.put("type", nodeName.substring(parenStart + 1, parenEnd));
                            argInfo.put("name", nodeName.substring(colonIdx + 3).trim());
                        }
                        arguments.add(argInfo);
                    }
                });
            }

            inspection.put("nodes", nodeInfos);
            inspection.put("links", linkInfos);
            inspection.put("arguments", arguments);
            inspection.put("nodeCount", nodeInfos.size());
            inspection.put("linkCount", linkInfos.size());

            return ResponseEntity.ok(inspection);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to parse Xircuits workflow: " + e.getMessage()));
        }
    }

    /**
     * Inspect an n8n workflow — extract nodes, connections, trigger node, and credential requirements.
     */
    @PostMapping("/inspect/n8n")
    public ResponseEntity<?> inspectN8n(@RequestBody String workflowJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = om.readTree(workflowJson);

            Map<String, Object> inspection = new LinkedHashMap<>();
            inspection.put("engineType", "n8n");
            inspection.put("id", tree.path("id").asText(null));
            inspection.put("name", tree.path("name").asText(null));
            inspection.put("active", tree.path("active").asBoolean(false));

            // Parse nodes
            List<Map<String, Object>> nodeInfos = new ArrayList<>();
            String triggerNodeName = null;
            Set<String> credentialTypes = new LinkedHashSet<>();

            var nodesArray = tree.path("nodes");
            if (nodesArray.isArray()) {
                for (var node : nodesArray) {
                    Map<String, Object> nodeInfo = new LinkedHashMap<>();
                    nodeInfo.put("id", node.path("id").asText());
                    nodeInfo.put("name", node.path("name").asText());
                    nodeInfo.put("type", node.path("type").asText());
                    nodeInfo.put("typeVersion", node.path("typeVersion").asDouble());
                    nodeInfo.put("disabled", node.path("disabled").asBoolean(false));

                    // Check position
                    if (node.has("position") && node.path("position").isArray()) {
                        nodeInfo.put("position", List.of(
                                node.path("position").get(0).asInt(),
                                node.path("position").get(1).asInt()));
                    }

                    // Check credentials
                    var creds = node.path("credentials");
                    if (creds.isObject()) {
                        List<String> nodeCredTypes = new ArrayList<>();
                        creds.fieldNames().forEachRemaining(ct -> {
                            nodeCredTypes.add(ct);
                            credentialTypes.add(ct);
                        });
                        nodeInfo.put("credentialTypes", nodeCredTypes);
                    }

                    // Detect trigger node
                    String type = node.path("type").asText("");
                    boolean isTrigger = type.toLowerCase().contains("trigger")
                            || type.equals("n8n-nodes-base.webhook")
                            || type.equals("n8n-nodes-base.cron")
                            || type.equals("n8n-nodes-base.start");
                    nodeInfo.put("isTrigger", isTrigger);
                    if (isTrigger && triggerNodeName == null) {
                        triggerNodeName = node.path("name").asText();
                    }

                    nodeInfos.add(nodeInfo);
                }
            }

            // Parse connections — flatten to edge list
            List<Map<String, Object>> edges = new ArrayList<>();
            var connections = tree.path("connections");
            if (connections.isObject()) {
                connections.fields().forEachRemaining(sourceEntry -> {
                    String sourceName = sourceEntry.getKey();
                    var typeMap = sourceEntry.getValue();
                    typeMap.fields().forEachRemaining(typeEntry -> {
                        String connType = typeEntry.getKey();
                        var outputPorts = typeEntry.getValue();
                        if (outputPorts.isArray()) {
                            for (int outIdx = 0; outIdx < outputPorts.size(); outIdx++) {
                                var targets = outputPorts.get(outIdx);
                                if (targets != null && targets.isArray()) {
                                    for (var target : targets) {
                                        Map<String, Object> edge = new LinkedHashMap<>();
                                        edge.put("source", sourceName);
                                        edge.put("sourceOutput", outIdx);
                                        edge.put("target", target.path("node").asText());
                                        edge.put("targetInput", target.path("index").asInt(0));
                                        edge.put("connectionType", connType);
                                        edges.add(edge);
                                    }
                                }
                            }
                        }
                    });
                });
            }

            inspection.put("nodes", nodeInfos);
            inspection.put("edges", edges);
            inspection.put("triggerNode", triggerNodeName);
            inspection.put("requiredCredentialTypes", new ArrayList<>(credentialTypes));
            inspection.put("nodeCount", nodeInfos.size());
            inspection.put("edgeCount", edges.size());

            // Settings
            if (tree.has("settings") && tree.path("settings").isObject()) {
                inspection.put("settings", om.convertValue(tree.path("settings"), Map.class));
            }

            return ResponseEntity.ok(inspection);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to parse n8n workflow: " + e.getMessage()));
        }
    }

    // ==================== Request DTOs ====================

    @lombok.Data
    public static class SaveWorkflowRequest {
        private String content;
        private Map<String, Object> metadata;
    }
}
