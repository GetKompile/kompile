package ai.kompile.chat.local.cli;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMcpCliTest {

    @Test
    void exposesTheRealLocalGraphCatalogOverStdio() throws Exception {
        String input = message(1, "initialize", Map.of())
                + "\n" + message(2, "tools/list", Map.of()) + "\n";

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream error = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            exitCode = GraphMcpCli.run(
                    new String[0],
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    output,
                    error);
        }

        assertEquals(0, exitCode);
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).isEmpty());

        String[] responses = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(2, responses.length);
        Map<String, Object> listResult = object(MiniJson.parseObject(responses[1]).get("result"));
        List<?> tools = (List<?>) listResult.get("tools");
        assertEquals(16, tools.size());
        assertTrue(tools.stream()
                .map(GraphMcpCliTest::object)
                .anyMatch(tool -> "graph_reasoning_query".equals(tool.get("name"))));
        assertTrue(tools.stream()
                .map(GraphMcpCliTest::object)
                .allMatch(tool -> tool.get("inputSchema") instanceof Map<?, ?>));
    }

    @Test
    void invalidArgumentsFailBeforeWritingProtocolOutput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream error = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            exitCode = GraphMcpCli.run(
                    new String[]{"--unknown"},
                    new ByteArrayInputStream(new byte[0]),
                    output,
                    error);
        }

        assertEquals(2, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).isEmpty());
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("Unknown argument"));
    }

    private static String message(Object id, String method, Map<String, Object> params) {
        return MiniJson.write(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
