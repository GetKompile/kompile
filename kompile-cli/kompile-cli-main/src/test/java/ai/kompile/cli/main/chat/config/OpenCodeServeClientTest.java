package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenCodeServeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesOpaqueProviderAndModelIds() {
        OpenCodeServeClient.ModelReference reference =
                OpenCodeServeClient.parseModelReference("opencode-go/deepseek-v4-pro");

        assertEquals("opencode-go", reference.providerId());
        assertEquals("deepseek-v4-pro", reference.modelId());
        assertEquals("opencode-go/deepseek-v4-pro", reference.asWireValue());
    }

    @Test
    void rejectsModelIdsWithoutTheNativeProviderPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeServeClient.parseModelReference("deepseek-v4-pro"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeServeClient.parseModelReference("opencode-go/"));
    }

    @Test
    void extractsOnlyNativeTextEventsFromJsonOutput() throws Exception {
        String response = """
                {"type":"text","text":"one"}
                {"type":"reasoning","text":"secret"}
                {"type":"text","text":"two"}
                """;

        assertEquals("onetwo", OpenCodeServeClient.extractText(objectMapper, response));
    }

    @Test
    void extractsTextPartsFromNestedNativeEvents() throws Exception {
        String response = """
                {"parts":[
                  {"type":"reasoning","text":"secret"},
                  {"type":"text","text":"answer"}
                ]}
                """;

        assertEquals("answer", OpenCodeServeClient.extractText(objectMapper, response));
    }

    @Test
    void nativeSummarizeUsesSessionEndpointAndReturnsPortableSummary() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/session/session-1/summarize", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = "true".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/session/session-1/message", exchange -> {
            byte[] response = """
                    [{"parts":[{"type":"compaction","summary":"native session summary"}]}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/session/session-1", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-1")) {
            OpenCodeServeClient.NativeSummary result =
                    client.summarize("opencode-go/deepseek-v4-pro");

            assertTrue(result.applied());
            assertEquals("native session summary", result.summary());
            assertTrue(requestBody.get().contains("\"providerID\":\"opencode-go\""));
            assertTrue(requestBody.get().contains("\"modelID\":\"deepseek-v4-pro\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizesNativeTextToolsAndUsageWithoutMixingToolOutputIntoAssistantText() {
        OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:1", "session-1");
        StringBuilder assistant = new StringBuilder();
        List<String> activity = new ArrayList<>();
        OpenCodeServeClient.ActivityListener listener =
                new OpenCodeServeClient.ActivityListener() {
                    @Override
                    public void onToolStart(String callId, String name, String input) {
                        activity.add("start:" + callId + ":" + name);
                    }

                    @Override
                    public void onToolComplete(String callId, String name, String output,
                                               int exitCode, boolean error) {
                        activity.add("complete:" + callId + ":" + output);
                    }

                    @Override
                    public void onTokenUsage(long input, long output,
                                             long cacheRead, long cacheCreation) {
                        activity.add("usage:" + input + ":" + output + ":" + cacheRead);
                    }
                };
        var parser = new ai.kompile.cli.main.chat.PassthroughStreamParser();
        var started = new HashSet<String>();
        var completed = new HashSet<String>();

        client.processProviderLine(
                "{\"type\":\"text\",\"part\":{\"text\":\"answer\"}}",
                parser, assistant, ignored -> { }, listener, started, completed);
        client.processProviderLine(
                "{\"type\":\"tool_use\",\"part\":{\"callID\":\"call-1\","
                        + "\"tool\":\"bash\",\"state\":{\"status\":\"running\","
                        + "\"input\":{\"command\":\"pwd\"}}}}",
                parser, assistant, ignored -> { }, listener, started, completed);
        client.processProviderLine(
                "{\"type\":\"tool_use\",\"part\":{\"callID\":\"call-1\","
                        + "\"tool\":\"bash\",\"state\":{\"status\":\"completed\","
                        + "\"input\":{\"command\":\"pwd\"},"
                        + "\"output\":[{\"type\":\"text\",\"text\":\"tool output\"}],"
                        + "\"metadata\":{\"exit\":0}}}}",
                parser, assistant, ignored -> { }, listener, started, completed);
        client.processProviderLine(
                "{\"type\":\"step_finish\",\"part\":{\"tokens\":{"
                        + "\"input\":12,\"output\":3,\"cache\":{\"read\":4}}}}",
                parser, assistant, ignored -> { }, listener, started, completed);

        assertEquals("answer", assistant.toString());
        assertEquals(List.of(
                "start:call-1:bash",
                "complete:call-1:tool output",
                "usage:12:3:4"), activity);
    }
}
