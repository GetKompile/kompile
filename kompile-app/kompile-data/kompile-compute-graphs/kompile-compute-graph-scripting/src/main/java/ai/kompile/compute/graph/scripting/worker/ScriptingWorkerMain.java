package ai.kompile.compute.graph.scripting.worker;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionLimits;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.scripting.Python4jNodeExecutor;
import ai.kompile.compute.graph.scripting.PythonSubprocessNodeExecutor;
import ai.kompile.compute.graph.scripting.ScriptingNodeExecutor;
import ai.kompile.compute.graph.scripting.client.ScriptingWorkerNodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * One-shot JVM entry point for JavaScript and Python compute-node execution.
 *
 * <p>One JSON request is read from stdin. Exactly one base64-encoded protocol response is written
 * to the original stdout; application and language-runtime output is redirected to stderr so it
 * cannot corrupt the response. A fresh process provides the same isolation and killability as the
 * other Kompile managed subprocesses without making GraalJS/Truffle reachable to native-image.</p>
 */
public final class ScriptingWorkerMain {

    private static final ObjectMapper mapper = JsonUtils.standardMapper();

    private ScriptingWorkerMain() {
    }

    public static void main(String[] args) {
        PrintStream protocolOut = System.out;
        System.setOut(System.err);
        int exitCode = run(System.in, protocolOut);
        protocolOut.flush();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(InputStream input, PrintStream protocolOut) {
        JsonNode request = null;
        try {
            request = mapper.readTree(input);
            if (request == null || !request.isObject()) {
                throw new IllegalArgumentException("Scripting worker request must be a JSON object");
            }

            ComputeNode node = nodeFrom(request.path("node"));
            String executionId = request.path("executionId").asText("scripting-worker");
            String operation = request.path("operation").asText("execute");
            ObjectNode response;
            if ("validate".equals(operation)) {
                String validationError = executorFor(node.getExecutionType()).validate(node);
                response = responseFor(ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(executionId)
                        .status(ExecutionStatus.COMPLETED)
                        .error(validationError)
                        .startedAt(Instant.now())
                        .completedAt(Instant.now())
                        .duration(Duration.ZERO)
                        .build());
            } else if ("execute".equals(operation)) {
                response = responseFor(execute(request, node, executionId));
            } else {
                throw new IllegalArgumentException("Unsupported scripting worker operation: " + operation);
            }
            writeResponse(protocolOut, response);
            return 0;
        } catch (Throwable throwable) {
            String nodeId = request != null ? request.path("node").path("id").asText("unknown") : "unknown";
            String executionId = request != null ? request.path("executionId").asText("unknown") : "unknown";
            ExecutionResult failure = ExecutionResult.failure(
                    nodeId, executionId, message(throwable), stackTrace(throwable));
            try {
                writeResponse(protocolOut, responseFor(failure));
            } catch (Throwable protocolFailure) {
                protocolFailure.printStackTrace(System.err);
                throwable.printStackTrace(System.err);
                return 2;
            }
            return 1;
        }
    }

    private static ExecutionResult execute(JsonNode request, ComputeNode node, String executionId) {
        Map<String, Object> inputs = objectMap(request.path("inputs"));
        Map<String, Object> globalState = objectMap(request.path("globalState"));
        ComputeGraph graph = ComputeGraph.builder()
                .id("scripting-worker-graph")
                .name("scripting-worker-graph")
                .globalParameters(globalState)
                .build();
        ExecutionContext context = new ExecutionContext(executionId, graph, null);
        return executorFor(node.getExecutionType()).execute(node, inputs, context);
    }

    private static NodeExecutor executorFor(NodeExecutionType executionType) {
        return switch (executionType) {
            case JAVASCRIPT -> new ScriptingNodeExecutor();
            case PYTHON -> pythonExecutor();
            default -> throw new IllegalArgumentException(
                    "Scripting worker does not support execution type " + executionType);
        };
    }

    private static NodeExecutor pythonExecutor() {
        String mode = System.getProperty("kompile.scripting.python.mode");
        if (mode == null || mode.isBlank()) {
            mode = System.getenv("KOMPILE_SCRIPTING_PYTHON_MODE");
        }
        if (mode != null && ("python4j".equalsIgnoreCase(mode) || "embedded".equalsIgnoreCase(mode))) {
            return new Python4jNodeExecutor();
        }
        // Correctness-first default: the short-lived worker delegates to a short-lived system
        // Python process. Python4J remains available through the explicit mode above.
        return new PythonSubprocessNodeExecutor();
    }

    private static ComputeNode nodeFrom(JsonNode nodeJson) {
        if (!nodeJson.isObject()) {
            throw new IllegalArgumentException("Scripting worker request is missing node");
        }
        NodeExecutionType executionType = NodeExecutionType.valueOf(
                requiredText(nodeJson, "executionType"));
        return ComputeNode.builder()
                .id(textOr(nodeJson, "id", "script"))
                .name(textOr(nodeJson, "name", "script"))
                .description(nullableText(nodeJson, "description"))
                .executionType(executionType)
                .script(nullableText(nodeJson, "script"))
                .parameters(objectMap(nodeJson.path("parameters")))
                .inputBindings(stringMap(nodeJson.path("inputBindings")))
                .outputBindings(stringMap(nodeJson.path("outputBindings")))
                .metadata(objectMap(nodeJson.path("metadata")))
                .limits(limitsFrom(nodeJson.path("limits")))
                .build();
    }

    private static ExecutionLimits limitsFrom(JsonNode limitsJson) {
        ExecutionLimits defaults = ExecutionLimits.defaults();
        if (!limitsJson.isObject()) {
            return defaults;
        }
        JsonNode cpu = limitsJson.get("maxCpuTimeMillis");
        Duration maxCpuTime = cpu == null || cpu.isNull()
                ? null : Duration.ofMillis(cpu.asLong(defaults.getMaxCpuTime().toMillis()));
        return ExecutionLimits.builder()
                .maxCpuTime(maxCpuTime)
                .maxHeapMemoryBytes(limitsJson.path("maxHeapMemoryBytes")
                        .asLong(defaults.getMaxHeapMemoryBytes()))
                .maxStackFrames(limitsJson.path("maxStackFrames").asInt(defaults.getMaxStackFrames()))
                .allowIO(limitsJson.path("allowIO").asBoolean(defaults.isAllowIO()))
                .allowNetwork(limitsJson.path("allowNetwork").asBoolean(defaults.isAllowNetwork()))
                .allowHostAccess(limitsJson.path("allowHostAccess").asBoolean(defaults.isAllowHostAccess()))
                .build();
    }

    private static ObjectNode responseFor(ExecutionResult result) {
        ObjectNode response = mapper.createObjectNode();
        putNullable(response, "nodeId", result.getNodeId());
        putNullable(response, "executionId", result.getExecutionId());
        response.put("status", result.getStatus().name());
        response.set("outputs", mapper.valueToTree(result.getOutputs() != null ? result.getOutputs() : Map.of()));
        putNullable(response, "error", result.getError());
        putNullable(response, "stackTrace", result.getStackTrace());
        putNullable(response, "consoleOutput", result.getConsoleOutput());
        if (result.getDuration() == null) {
            response.putNull("durationMillis");
        } else {
            response.put("durationMillis", result.getDuration().toMillis());
        }
        if (result.getStartedAt() == null) {
            response.putNull("startedAtEpochMillis");
        } else {
            response.put("startedAtEpochMillis", result.getStartedAt().toEpochMilli());
        }
        if (result.getCompletedAt() == null) {
            response.putNull("completedAtEpochMillis");
        } else {
            response.put("completedAtEpochMillis", result.getCompletedAt().toEpochMilli());
        }
        return response;
    }

    private static void writeResponse(PrintStream protocolOut, ObjectNode response) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(response));
        protocolOut.println(ScriptingWorkerNodeExecutor.RESULT_PREFIX + encoded);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return (Map<String, Object>) mapper.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return (Map<String, String>) (Map<?, ?>) mapper.convertValue(node, Map.class);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Scripting worker node is missing " + field);
        }
        return value;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = nullableText(node, field);
        return value != null ? value : fallback;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.toString() : message;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
