package ai.kompile.tool.workflow;

import ai.kompile.compute.graph.engine.ComputeGraphEngine;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.WorkflowFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP tools for managing, inspecting, and executing Xircuits and n8n workflows.
 * Provides LLM-accessible operations for the full workflow lifecycle.
 */
@Component
@ConditionalOnBean(ComputeGraphEngine.class)
public class WorkflowTool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    private final WorkflowFileStore workflowStore;
    private final ComputeGraphEngine graphEngine;
    private final ObjectMapper objectMapper;

    public WorkflowTool(WorkflowFileStore workflowStore,
                         ComputeGraphEngine graphEngine) {
        this.workflowStore = workflowStore;
        this.graphEngine = graphEngine;
        this.objectMapper = new ObjectMapper();
    }

    // ---- Input Records ----

    public record ListWorkflowsInput(String engineType) {}
    public record GetWorkflowInput(String engineType, String name) {}
    public record SaveWorkflowInput(String engineType, String name, String content,
                                     String description, List<String> tags) {}
    public record DeleteWorkflowInput(String engineType, String name) {}
    public record InspectWorkflowInput(String engineType, String content) {}
    public record ExecuteWorkflowInput(String engineType, String name,
                                        Map<String, Object> inputs, Integer timeoutSeconds) {}

    // ---- Tool Methods ----

    @Tool(name = "workflow_list",
          description = "List all saved workflow definitions. Optionally filter by engine type. " +
                  "engineType: 'xircuits' for Python visual workflows, 'n8n' for JavaScript workflow automation. " +
                  "Omit engineType to list all workflows across both engines.")
    public Map<String, Object> listWorkflows(ListWorkflowsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ComputeArtifact> workflows;
            if (input.engineType() != null && !input.engineType().isBlank()) {
                workflows = workflowStore.list(input.engineType());
            } else {
                workflows = workflowStore.listAll();
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (ComputeArtifact artifact : workflows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", artifact.getId());
                item.put("name", artifact.getName());
                item.put("contentType", artifact.getContentType());
                item.put("sizeBytes", artifact.getSizeBytes());
                if (artifact.getMetadata() != null) {
                    item.put("description", artifact.getMetadata().get("description"));
                    item.put("tags", artifact.getMetadata().get("tags"));
                    item.put("updatedAt", artifact.getMetadata().get("updatedAt"));
                }
                items.add(item);
            }

            result.put("workflows", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "workflow_get",
          description = "Retrieve a saved workflow definition by engine type and name. " +
                  "Returns the full workflow JSON content and metadata. " +
                  "engineType: 'xircuits' or 'n8n'. name: the workflow name.")
    public Map<String, Object> getWorkflow(GetWorkflowInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Optional<String> content = workflowStore.load(input.engineType(), input.name());
            if (content.isEmpty()) {
                result.put("status", "not_found");
                result.put("error", "Workflow not found: " + input.engineType() + "/" + input.name());
                return result;
            }

            Optional<Map<String, Object>> meta = workflowStore.loadMetadata(input.engineType(), input.name());
            result.put("engineType", input.engineType());
            result.put("name", input.name());
            result.put("content", content.get());
            result.put("metadata", meta.orElse(Map.of()));
            result.put("status", "success");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "workflow_save",
          description = "Save or update a workflow definition. " +
                  "engineType: 'xircuits' for Python visual workflows, 'n8n' for JavaScript automation workflows. " +
                  "name: workflow name (used as filename). " +
                  "content: the full workflow JSON string. " +
                  "description: optional human-readable description. " +
                  "tags: optional list of tags for categorization.")
    public Map<String, Object> saveWorkflow(SaveWorkflowInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (input.description() != null) metadata.put("description", input.description());
            if (input.tags() != null) metadata.put("tags", input.tags());

            ComputeArtifact artifact = workflowStore.save(
                    input.engineType(), input.name(), input.content(), metadata);

            result.put("id", artifact.getId());
            result.put("name", artifact.getName());
            result.put("storagePath", artifact.getStorageUri());
            result.put("sizeBytes", artifact.getSizeBytes());
            result.put("status", "success");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "workflow_delete",
          description = "Delete a saved workflow definition. " +
                  "engineType: 'xircuits' or 'n8n'. name: the workflow name.")
    public Map<String, Object> deleteWorkflow(DeleteWorkflowInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean deleted = workflowStore.delete(input.engineType(), input.name());
            result.put("deleted", deleted);
            result.put("status", deleted ? "success" : "not_found");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "workflow_inspect",
          description = "Inspect a workflow definition to extract its structure without executing it. " +
                  "Returns the list of nodes, connections, argument/trigger nodes, and credential requirements. " +
                  "engineType: 'xircuits' or 'n8n'. content: the workflow JSON string to inspect.")
    public Map<String, Object> inspectWorkflow(InspectWorkflowInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var tree = objectMapper.readTree(input.content());
            result.put("engineType", input.engineType());

            if ("xircuits".equals(input.engineType())) {
                inspectXircuits(tree, result);
            } else if ("n8n".equals(input.engineType())) {
                inspectN8n(tree, result);
            } else {
                result.put("status", "error");
                result.put("error", "Unknown engine type: " + input.engineType() + ". Use 'xircuits' or 'n8n'.");
                return result;
            }

            result.put("status", "success");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", "Failed to inspect workflow: " + e.getMessage());
        }
        return result;
    }

    @Tool(name = "workflow_execute",
          description = "Execute a saved workflow by name. The workflow is loaded from the store, " +
                  "wrapped in a compute graph node, and executed via the compute graph engine. " +
                  "engineType: 'xircuits' or 'n8n'. name: the workflow name. " +
                  "inputs: optional key-value map of input data passed to the workflow. " +
                  "timeoutSeconds: optional timeout (default 300).")
    public Map<String, Object> executeWorkflow(ExecuteWorkflowInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Optional<String> content = workflowStore.load(input.engineType(), input.name());
            if (content.isEmpty()) {
                result.put("status", "not_found");
                result.put("error", "Workflow not found: " + input.engineType() + "/" + input.name());
                return result;
            }

            // Build a compute graph with a single workflow node
            NodeExecutionType execType = "xircuits".equals(input.engineType())
                    ? NodeExecutionType.XIRCUITS : NodeExecutionType.N8N;

            Map<String, Object> params = new HashMap<>();
            if (input.timeoutSeconds() != null) {
                params.put("timeoutSeconds", input.timeoutSeconds());
            }

            ComputeNode workflowNode = ComputeNode.builder()
                    .id("workflow-" + UUID.randomUUID())
                    .name(input.name())
                    .executionType(execType)
                    .script(content.get())
                    .parameters(params)
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("exec-" + UUID.randomUUID())
                    .name("Execute " + input.name())
                    .nodes(List.of(workflowNode))
                    .edges(List.of())
                    .build();

            Map<String, Object> inputs = input.inputs() != null ? input.inputs() : Map.of();
            GraphExecutionResult execResult = graphEngine.execute(graph, inputs);

            result.put("executionId", execResult.getExecutionId());
            result.put("executionStatus", execResult.getStatus().name());
            result.put("executionOrder", execResult.getExecutionOrder());

            if (execResult.getFinalOutputs() != null && !execResult.getFinalOutputs().isEmpty()) {
                result.put("outputs", execResult.getFinalOutputs());
            }
            if (execResult.getError() != null) {
                result.put("error", execResult.getError());
            }

            // Include per-node results
            if (execResult.getNodeResults() != null) {
                Map<String, Object> nodeResults = new LinkedHashMap<>();
                execResult.getNodeResults().forEach((nodeId, nodeResult) -> {
                    Map<String, Object> nr = new LinkedHashMap<>();
                    nr.put("status", nodeResult.getStatus().name());
                    nr.put("duration", nodeResult.getDuration() != null
                            ? nodeResult.getDuration().toMillis() + "ms" : null);
                    if (nodeResult.getConsoleOutput() != null) {
                        nr.put("consoleOutput", nodeResult.getConsoleOutput());
                    }
                    if (nodeResult.getError() != null) {
                        nr.put("error", nodeResult.getError());
                    }
                    if (nodeResult.getOutputs() != null) {
                        nr.put("outputs", nodeResult.getOutputs());
                    }
                    nodeResults.put(nodeId, nr);
                });
                result.put("nodeResults", nodeResults);
            }

            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error executing workflow {}/{}", input.engineType(), input.name(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Private Helpers ----

    private void inspectXircuits(com.fasterxml.jackson.databind.JsonNode tree, Map<String, Object> result) {
        result.put("id", tree.path("id").asText(null));
        var layers = tree.path("layers");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        List<Map<String, Object>> arguments = new ArrayList<>();

        if (layers.isArray() && layers.size() >= 2) {
            layers.get(0).path("models").fields().forEachRemaining(e -> {
                var link = e.getValue();
                links.add(Map.of(
                        "id", link.path("id").asText(),
                        "type", link.path("type").asText(),
                        "source", link.path("source").asText(),
                        "target", link.path("target").asText()));
            });

            layers.get(1).path("models").fields().forEachRemaining(e -> {
                var node = e.getValue();
                String name = node.path("name").asText();
                nodes.add(Map.of(
                        "id", node.path("id").asText(),
                        "name", name,
                        "componentType", node.path("extras").path("type").asText(""),
                        "sourcePath", node.path("extras").path("path").asText("")));

                if (name != null && name.startsWith("Argument ")) {
                    Map<String, Object> arg = new LinkedHashMap<>();
                    arg.put("fullName", name);
                    int p1 = name.indexOf('('), p2 = name.indexOf(')'), c = name.indexOf("): ");
                    if (p1 >= 0 && p2 > p1 && c >= 0) {
                        arg.put("type", name.substring(p1 + 1, p2));
                        arg.put("name", name.substring(c + 3).trim());
                    }
                    arguments.add(arg);
                }
            });
        }

        result.put("nodes", nodes);
        result.put("links", links);
        result.put("arguments", arguments);
        result.put("nodeCount", nodes.size());
        result.put("linkCount", links.size());
    }

    private void inspectN8n(com.fasterxml.jackson.databind.JsonNode tree, Map<String, Object> result) {
        result.put("id", tree.path("id").asText(null));
        result.put("name", tree.path("name").asText(null));
        result.put("active", tree.path("active").asBoolean(false));

        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> credentialTypes = new LinkedHashSet<>();
        String triggerNode = null;

        var nodesArr = tree.path("nodes");
        if (nodesArr.isArray()) {
            for (var node : nodesArr) {
                String type = node.path("type").asText("");
                String name = node.path("name").asText("");
                boolean isTrigger = type.toLowerCase().contains("trigger")
                        || "n8n-nodes-base.webhook".equals(type)
                        || "n8n-nodes-base.cron".equals(type);

                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("name", name);
                nodeInfo.put("type", type);
                nodeInfo.put("disabled", node.path("disabled").asBoolean(false));
                nodeInfo.put("isTrigger", isTrigger);
                nodes.add(nodeInfo);

                if (isTrigger && triggerNode == null) triggerNode = name;

                var creds = node.path("credentials");
                if (creds.isObject()) {
                    creds.fieldNames().forEachRemaining(credentialTypes::add);
                }
            }
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        var conns = tree.path("connections");
        if (conns.isObject()) {
            conns.fields().forEachRemaining(src -> {
                src.getValue().fields().forEachRemaining(typeEntry -> {
                    var ports = typeEntry.getValue();
                    if (ports.isArray()) {
                        for (int i = 0; i < ports.size(); i++) {
                            var targets = ports.get(i);
                            if (targets != null && targets.isArray()) {
                                for (var t : targets) {
                                    edges.add(Map.of(
                                            "source", src.getKey(),
                                            "target", t.path("node").asText(),
                                            "type", typeEntry.getKey()));
                                }
                            }
                        }
                    }
                });
            });
        }

        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("triggerNode", triggerNode);
        result.put("requiredCredentialTypes", new ArrayList<>(credentialTypes));
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());
    }
}
