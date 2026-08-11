package ai.kompile.app.subprocess;

import ai.kompile.app.llm.pipeline.LoadRequest;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServingSubprocessHttpServerTest {

    private final ObjectMapper objectMapper = JsonUtils.newStandardMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private ServingSubprocessHttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void exposesTheFiveControllerContracts() throws Exception {
        RecordingApi api = new RecordingApi();
        start(api, 1024, 4096);

        HttpResponse<String> load = request(
                "POST",
                "/api/llm/load",
                "{\"modelId\":\"trace-model\",\"stagingUrl\":\"http://staging\",\"options\":{\"maxTokens\":64}}");
        assertEquals(202, load.statusCode());
        assertEquals("trace-model", objectMapper.readTree(load.body()).get("modelId").asText());
        assertNotNull(api.loadedRequest);
        assertEquals("http://staging", api.loadedRequest.getStagingUrl());
        assertEquals(64, api.loadedRequest.getOptions().get("maxTokens"));

        HttpResponse<String> status = request("GET", "/api/llm/status", null);
        assertEquals(200, status.statusCode());
        assertEquals("direct", objectMapper.readTree(status.body()).get("mode").asText());
        assertEquals("preserved", status.headers().firstValue("X-Serving-Test").orElseThrow());

        HttpResponse<String> generate = request(
                "POST",
                "/api/llm/generate",
                "{\"prompt\":\"hello native serving\",\"maxTokens\":16}");
        assertEquals(200, generate.statusCode());
        assertEquals("hello native serving", objectMapper.readTree(generate.body()).get("prompt").asText());
        assertEquals(16, api.generateRequest.get("maxTokens"));

        HttpResponse<String> chat = request(
                "POST",
                "/api/llm/chat",
                "{\"request\":{\"messages\":[{\"role\":\"user\",\"content\":\"extract\"}],"
                        + "\"tools\":[],\"addGenerationPrompt\":true,"
                        + "\"toolDefinitionFormat\":\"FLAT\",\"toolCallFormat\":\"NATIVE\"},"
                        + "\"maxTokens\":32}");
        assertEquals(200, chat.statusCode());
        assertEquals(32, api.chatRequest.get("maxTokens"));

        HttpResponse<String> unload = request("POST", "/api/llm/unload", "");
        assertEquals(200, unload.statusCode());
        assertEquals(true, objectMapper.readTree(unload.body()).get("unloaded").asBoolean());
    }

    @Test
    void enforcesExactPathsMethodsAndNoWildcardCors() throws Exception {
        start(new RecordingApi(), 1024, 4096);

        HttpResponse<String> wrongMethod = request("GET", "/api/llm/load", null);
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST, OPTIONS", wrongMethod.headers().firstValue("Allow").orElseThrow());

        HttpResponse<String> nestedPath = request("POST", "/api/llm/load/extra", "{}");
        assertEquals(404, nestedPath.statusCode());

        HttpResponse<String> options = request("OPTIONS", "/api/llm/generate", null);
        assertEquals(204, options.statusCode());
        assertEquals(true, options.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

        HttpResponse<String> nestedOptions = request("OPTIONS", "/api/llm/generate/extra", null);
        assertEquals(404, nestedOptions.statusCode());
    }

    @Test
    void rejectsMalformedAndOversizedRequests() throws Exception {
        start(new RecordingApi(), 32, 4096);

        HttpResponse<String> malformed = request("POST", "/api/llm/generate", "{");
        assertEquals(400, malformed.statusCode());
        assertEquals("invalid JSON request", objectMapper.readTree(malformed.body()).get("error").asText());

        String oversizedBody = "{\"prompt\":\"" + "x".repeat(100) + "\"}";
        HttpResponse<String> oversized = request("POST", "/api/llm/generate", oversizedBody);
        assertEquals(413, oversized.statusCode());
        assertEquals("request exceeds byte limit",
                objectMapper.readTree(oversized.body()).get("error").asText());
    }

    @Test
    void rejectsMissingOrUnsupportedJsonContentTypes() throws Exception {
        start(new RecordingApi(), 1024, 4096);

        HttpResponse<String> missing = request(
                "POST", "/api/llm/generate", "{}", null);
        assertEquals(415, missing.statusCode());

        HttpResponse<String> text = request(
                "POST", "/api/llm/load", "{}", "text/plain");
        assertEquals(415, text.statusCode());

        HttpResponse<String> vendorJson = request(
                "POST", "/api/llm/generate", "{\"prompt\":\"ok\"}", "application/problem+json");
        assertEquals(200, vendorJson.statusCode());
    }

    @Test
    void capsSerializedResponses() throws Exception {
        start(new RecordingApi(), 1024, 8);

        HttpResponse<String> response = request("GET", "/api/llm/status", null);
        assertEquals(507, response.statusCode());
        assertEquals("", response.body());
        assertEquals(true, response.headers().firstValue("Content-Type").isEmpty());
    }

    @Test
    void abortsSerializationAsSoonAsTheResponseLimitIsCrossed() throws Exception {
        AtomicInteger valuesSerialized = new AtomicInteger();
        RecordingApi api = new RecordingApi() {
            @Override
            public ResponseEntity<Map<String, Object>> status() {
                return ResponseEntity.ok(Map.of(
                        "values", new CountingIterable(valuesSerialized, 1_000_000)));
            }
        };
        start(api, 1024, 128);

        HttpResponse<String> response = request("GET", "/api/llm/status", null);
        assertEquals(507, response.statusCode());
        assertEquals("response exceeds byte limit",
                objectMapper.readTree(response.body()).get("error").asText());
        // Jackson has a fixed internal generator buffer, so it may pull several
        // hundred values before the capped output stream sees a flush. It must
        // still stop far short of walking the million-item source.
        assertEquals(true, valuesSerialized.get() < 10_000);
    }

    private void start(
            ServingSubprocessHttpServer.Api api,
            long maxRequestBytes,
            long maxResponseBytes) throws Exception {
        server = ServingSubprocessHttpServer.start(
                "127.0.0.1",
                0,
                objectMapper,
                api,
                maxRequestBytes,
                maxResponseBytes,
                2,
                4);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return request(method, path, body, "application/json");
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String body,
            String contentType) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, publisher);
        if (contentType != null) {
            requestBuilder.header("Content-Type", contentType);
        }
        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class RecordingApi implements ServingSubprocessHttpServer.Api {
        private LoadRequest loadedRequest;
        private Map<String, Object> generateRequest;
        private Map<String, Object> chatRequest;

        @Override
        public ResponseEntity<Map<String, Object>> load(LoadRequest request) {
            loadedRequest = request;
            return ResponseEntity.status(202).body(Map.of(
                    "loaded", true,
                    "modelId", request.getModelId(),
                    "options", request.getOptions()));
        }

        @Override
        public ResponseEntity<Map<String, Object>> status() {
            return ResponseEntity.ok()
                    .header("X-Serving-Test", "preserved")
                    .body(Map.of("mode", "direct", "loaded", false));
        }

        @Override
        public ResponseEntity<Map<String, Object>> generate(Map<String, Object> request) {
            generateRequest = request;
            return ResponseEntity.ok(Map.of("prompt", request.get("prompt")));
        }

        @Override
        public ResponseEntity<Map<String, Object>> chat(Map<String, Object> request) {
            chatRequest = request;
            return ResponseEntity.ok(Map.of("finishReason", "completed"));
        }

        @Override
        public ResponseEntity<Map<String, Object>> unload() {
            return ResponseEntity.ok(Map.of("unloaded", true));
        }
    }

    private static final class CountingIterable implements Iterable<String> {
        private final AtomicInteger serialized;
        private final int size;

        private CountingIterable(AtomicInteger serialized, int size) {
            this.serialized = serialized;
            this.size = size;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < size;
                }

                @Override
                public String next() {
                    index++;
                    serialized.incrementAndGet();
                    return "bounded-response-value";
                }
            };
        }
    }
}
