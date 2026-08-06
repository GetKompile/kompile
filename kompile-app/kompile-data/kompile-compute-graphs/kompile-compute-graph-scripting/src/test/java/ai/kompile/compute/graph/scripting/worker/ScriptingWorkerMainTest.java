package ai.kompile.compute.graph.scripting.worker;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.scripting.PythonSubprocessNodeExecutor;
import ai.kompile.compute.graph.scripting.client.ScriptingWorkerNodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScriptingWorkerMainTest {

    private static final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void executesRealJavaScriptRuntime() throws Exception {
        JsonNode response = run(request(NodeExecutionType.JAVASCRIPT,
                "_output = {answer: value * 2};", Map.of("value", 21)));

        assertEquals("COMPLETED", response.path("status").asText());
        assertEquals(42, response.path("outputs").path("answer").asInt());
    }

    @Test
    void executesRealPythonRuntime() throws Exception {
        assumeTrue(PythonSubprocessNodeExecutor.isPythonAvailable());
        JsonNode response = run(request(NodeExecutionType.PYTHON,
                "_result = value + 5", Map.of("value", 37)));

        assertEquals("COMPLETED", response.path("status").asText());
        assertEquals(42, response.path("outputs").path("_result").asInt());
    }

    private static ObjectNode request(NodeExecutionType type, String script,
                                      Map<String, Object> inputs) {
        ObjectNode request = mapper.createObjectNode();
        request.put("operation", "execute");
        request.put("executionId", "worker-test");
        request.set("inputs", mapper.valueToTree(inputs));
        request.set("globalState", mapper.createObjectNode());
        ObjectNode node = request.putObject("node");
        node.put("id", "script-node");
        node.put("name", "script-node");
        node.put("executionType", type.name());
        node.put("script", script);
        node.set("parameters", mapper.createObjectNode());
        node.set("inputBindings", mapper.createObjectNode());
        node.set("outputBindings", mapper.createObjectNode());
        node.set("metadata", mapper.createObjectNode());
        return request;
    }

    private static JsonNode run(ObjectNode request) throws Exception {
        byte[] input = mapper.writeValueAsBytes(request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream protocol = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            exitCode = ScriptingWorkerMain.run(new ByteArrayInputStream(input), protocol);
        }
        assertEquals(0, exitCode, output.toString(StandardCharsets.UTF_8));
        String line = output.toString(StandardCharsets.UTF_8).strip();
        String encoded = line.substring(ScriptingWorkerNodeExecutor.RESULT_PREFIX.length());
        return mapper.readTree(Base64.getDecoder().decode(encoded));
    }
}
