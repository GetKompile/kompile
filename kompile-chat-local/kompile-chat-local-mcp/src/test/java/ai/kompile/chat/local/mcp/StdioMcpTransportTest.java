package ai.kompile.chat.local.mcp;

import ai.kompile.chat.local.GraphToolBackend;
import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMcpTransportTest {

    @Test
    void malformedLineDoesNotTerminateTheSessionOrPolluteDiagnostics() throws Exception {
        String input = "{bad\n"
                + MiniJson.write(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "initialize",
                        "params", Map.of())) + "\n"
                + MiniJson.write(Map.of(
                        "jsonrpc", "2.0",
                        "method", "notifications/initialized")) + "\n";

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        try (GraphMcpServer server = new GraphMcpServer(new FakeBackend());
             PrintStream error = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            new StdioMcpTransport(server).run(
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    output,
                    error);
        }

        String[] responses = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(2, responses.length);
        assertEquals(-32700L,
                object(MiniJson.parseObject(responses[0]).get("error")).get("code"));
        assertEquals("2024-11-05",
                object(MiniJson.parseObject(responses[1]).get("result")).get("protocolVersion"));
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class FakeBackend implements GraphToolBackend {
        @Override
        public String catalogJson() {
            return "[{\"name\":\"graph_reasoning_query\","
                    + "\"description\":\"Query graph\","
                    + "\"parameters\":{\"type\":\"object\",\"properties\":{}}}]";
        }

        @Override
        public String execute(String toolName, String argsJson) {
            return "{\"status\":\"OK\"}";
        }

        @Override
        public void close() {
        }
    }
}
