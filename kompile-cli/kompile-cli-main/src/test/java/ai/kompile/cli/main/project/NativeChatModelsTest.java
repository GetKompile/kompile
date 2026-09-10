/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NativeChatModelsTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void previewDoesNotAuthenticateMigrateOrSendRequestsAndSwitchDoesNotInheritModel() throws Exception {
        Path settings = configure("custom", "http://127.0.0.1:1/v1");
        String before = Files.readString(settings);
        Map<String, Object> preview = NativeChatModels.probe(root, "custom", "selected", "text", false, Duration.ofSeconds(1));
        assertEquals("UNKNOWN", preview.get("readiness"));
        assertEquals("NOT_CHECKED", preview.get("authentication"));
        assertEquals("selected", preview.get("model"));
        assertFalse(mapper.writeValueAsString(preview).contains("fixture-secret"));
        assertFalse(mapper.writeValueAsString(preview).contains("127.0.0.1"));
        assertEquals(before, Files.readString(settings));
        assertEquals("OPENAI_RESPONSES", NativeChatModels.resolve(root, "codex", "explicit-codex-model").preview().get("protocol"));
        assertEquals("ANTHROPIC_MESSAGES", NativeChatModels.resolve(root, "claude", "explicit-claude-model").preview().get("protocol"));
        assertEquals(before, Files.readString(settings));
    }

    @Test
    void thinkingOverrideIsRequestScopedAndOmissionInherits() throws Exception {
        Path settings = configure("custom", "http://127.0.0.1:1/v1");
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(settings.toFile());
        config.put("thinking", "medium");
        mapper.writeValue(settings.toFile(), config);
        String before = Files.readString(settings);
        assertEquals("xhigh", NativeChatModels.resolve(root, "custom", "selected", "xhigh").thinking());
        assertEquals("medium", NativeChatModels.resolve(root, "custom", "selected").thinking());
        assertEquals("xhigh", NativeChatModels.probe(root, "custom", "selected", "xhigh",
                "text", false, Duration.ofSeconds(1)).get("thinking"));
        assertEquals(before, Files.readString(settings));
    }

    @Test
    void catalogDoesNotRequireAConfiguredModelOrConstrainManualSelection() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            assertEquals("Bearer fixture-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = "{\"data\":[{\"id\":\"small-text\"},{\"id\":\"large-vision\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        try {
            Path settings = configure("custom", url(server));
            var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(settings.toFile());
            config.remove("model");
            mapper.writeValue(settings.toFile(), config);
            String before = Files.readString(settings);
            JsonNode catalog = mapper.valueToTree(NativeChatModels.listModels(root, "chat:custom"));
            assertEquals("custom", catalog.path("provider").asText());
            assertEquals("SUCCESS", catalog.path("catalogStatus").asText());
            assertEquals(List.of("small-text", "large-vision"),
                    catalog.path("models").findValuesAsText("id"));
            assertFalse(catalog.has("configuredModel"));
            assertTrue(catalog.path("manualModelSelectionAllowed").asBoolean());
            assertEquals("UNKNOWN", catalog.path("readiness").asText());
            for (JsonNode model : catalog.path("models")) assertEquals("UNKNOWN", model.path("modelSupport").asText());
            assertFalse(catalog.toString().contains("fixture-secret"));
            assertFalse(catalog.toString().contains("127.0.0.1"));
            assertEquals("private-unlisted-model", NativeChatModels.resolve(root, "custom", "private-unlisted-model").model());
            assertEquals(List.of("/v1/models"), paths, "Selecting an exact id must not rerun discovery or infer");
            assertEquals(before, Files.readString(settings), "Discovery must not switch the user's selected model");
        } finally { server.stop(0); }
    }

    @Test
    void catalogEmptyAndAuthenticationFailureAreDistinctAndNeverEchoRemoteErrors() throws Exception {
        for (int status : List.of(200, 401)) {
            HttpServer server = server(exchange -> {
                byte[] body = (status == 200 ? "{\"data\":[]}"
                        : "{\"error\":\"fixture-secret private response\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            try {
                configure("custom", url(server));
                Map<String, Object> catalog = NativeChatModels.listModels(root, null);
                assertEquals(status == 200 ? "SUCCESS_EMPTY" : "AUTH_REQUIRED", catalog.get("catalogStatus"));
                assertEquals(List.of(), catalog.get("models"));
                assertEquals("configured-model", catalog.get("configuredModel"));
                assertEquals(true, catalog.get("manualModelSelectionAllowed"));
                assertEquals("UNKNOWN", catalog.get("readiness"));
                assertFalse(catalog.toString().contains("fixture-secret"));
                assertFalse(catalog.toString().contains("private response"));
            } finally { server.stop(0); }
        }
    }

    @Test
    void capabilitiesAreOperationAndWireSpecificNotBlanketModelClaims() throws Exception {
        configure("custom", "http://127.0.0.1:1/v1");
        NativeChatModels.Selection custom = NativeChatModels.resolve(root, null, null);
        Map<String, Object> customProvider = NativeChatModels.providers().stream()
                .filter(entry -> "custom".equals(entry.get("provider"))).findFirst().orElseThrow();
        assertEquals("AVAILABLE", customProvider.get("nativeChatAdapter"));
        assertEquals("NOT_PROBED", customProvider.get("modelCapabilities"));
        for (String unsupported : List.of("embedding", "tensor", "learning", "tools", "json_schema", "made-up")) {
            assertThrows(IOException.class, () -> custom.requireSupported(unsupported));
            assertThrows(IOException.class, () -> NativeChatModels.probe(root, null, null, unsupported, true, Duration.ofSeconds(1)));
        }
        assertDoesNotThrow(() -> custom.requireSupported("image"));
        assertThrows(IOException.class, () -> NativeChatModels.resolve(root, "claude", "model").requireSupported("json_schema"));
        assertDoesNotThrow(() -> NativeChatModels.resolve(root, "codex", "model").requireSupported("json_schema"));
        assertThrows(IOException.class, () -> NativeChatModels.resolve(root, "not-a-provider", "model"));
        assertThrows(IOException.class, () -> NativeChatModels.resolve(root, "opencode", "model"));
    }

    @Test
    void syntheticTextProbeRequiresOptInAndExactContract() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            String prompt = request.path("messages").get(1).path("content").asText();
            String marker = prompt.substring(prompt.indexOf("probe-"));
            respond(exchange, chatResponse(marker));
        });
        try {
            configure("custom", url(server));
            NativeChatModels.probe(root, null, null, "text", false, Duration.ofSeconds(2));
            assertTrue(requests.isEmpty());
            Map<String, Object> result = NativeChatModels.probe(root, null, "probe-model", "text", true, Duration.ofSeconds(2));
            assertEquals("PASSED", result.get("probeStatus"));
            assertEquals("PROBED", result.get("readiness"));
            JsonNode capabilities = mapper.valueToTree(result.get("capabilities"));
            assertEquals("PROBED", capabilities.path("text").path("modelSupport").asText());
            assertEquals("UNKNOWN", capabilities.path("image").path("modelSupport").asText());
            assertEquals("NOT_APPLICABLE", capabilities.path("embedding").path("modelSupport").asText());
            assertEquals("probe-model", requests.get(0).path("model").asText());
            assertFalse(requests.get(0).has("tools"));
        } finally { server.stop(0); }
    }

    @Test
    void graphProbeRequiresTheExtractedRelationNotJustAcceptedJson() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            String prompt = request.path("messages").get(1).path("content").asText();
            String organization = prompt.substring(prompt.indexOf("probe-"), prompt.indexOf(". Return"));
            var graph = mapper.createObjectNode();
            graph.putArray("entities").addObject().put("name", "Ada").put("type", "PERSON");
            graph.withArray("entities").addObject().put("name", organization).put("type", "ORGANIZATION");
            var relations = graph.putArray("relations");
            if (requests.size() == 1) relations.addObject().put("source", "Ada")
                    .put("target", organization).put("type", "WORKS_AT");
            respond(exchange, chatResponse(graph.toString()));
        });
        try {
            configure("custom", url(server));
            Map<String, Object> passed = NativeChatModels.probe(root, null, null,
                    "graph_extraction", true, Duration.ofSeconds(2));
            assertEquals("PASSED", passed.get("probeStatus"));
            assertEquals("REQUEST_ACCEPTED", passed.get("authentication"));
            JsonNode capabilities = mapper.valueToTree(passed.get("capabilities"));
            assertEquals("PROBED", capabilities.path("graph_extraction").path("modelSupport").asText());
            assertEquals("UNKNOWN", capabilities.path("image").path("modelSupport").asText(),
                    "A graph smoke test must not qualify another operation");
            Map<String, Object> missingRelation = NativeChatModels.probe(root, null, null,
                    "graph_extraction", true, Duration.ofSeconds(2));
            assertEquals("INCONCLUSIVE", missingRelation.get("probeStatus"));
            assertEquals("UNKNOWN", missingRelation.get("readiness"));
            assertEquals(2, requests.size());
            for (JsonNode request : requests) {
                assertFalse(request.has("tools"));
                assertFalse(request.has("response_format"), "Graph extraction uses the text contract");
            }
        } finally { server.stop(0); }
    }

    @Test
    void exactModelsHaveIndependentTextAndVisionProbeEvidence() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            JsonNode content = request.path("messages").get(1).path("content");
            String output;
            if (content.isTextual()) {
                output = content.asText().substring(content.asText().indexOf("probe-"));
            } else {
                String observed = observedColors(content);
                assertFalse(content.toString().contains(observed), "The answer must exist only in pixels");
                output = request.path("model").asText().equals("vision-candidate") ? observed : "RED";
            }
            respond(exchange, chatResponse(output));
        });
        try {
            configure("custom", url(server));
            for (String model : List.of("vision-candidate", "text-candidate")) {
                Map<String, Object> text = NativeChatModels.probe(root, "custom", model, "text", true, Duration.ofSeconds(2));
                assertEquals("PASSED", text.get("probeStatus"));
                assertEquals(model, requests.get(requests.size() - 1).path("model").asText());
                JsonNode textCapabilities = mapper.valueToTree(text.get("capabilities"));
                assertEquals("PROBED", textCapabilities.path("text").path("modelSupport").asText());
                assertEquals("UNKNOWN", textCapabilities.path("image").path("modelSupport").asText());
                for (String operation : List.of("image", "pdf")) {
                    Map<String, Object> probe = NativeChatModels.probe(root, "custom", model,
                            operation, true, Duration.ofSeconds(2));
                    boolean vision = model.equals("vision-candidate");
                    assertEquals(vision ? "PASSED" : "INCONCLUSIVE", probe.get("probeStatus"));
                    assertEquals(operation, probe.get("operation"));
                    assertEquals(model, probe.get("model"));
                    assertEquals(model, requests.get(requests.size() - 1).path("model").asText());
                    JsonNode capabilities = mapper.valueToTree(probe.get("capabilities"));
                    assertEquals(vision ? "PROBED" : "UNKNOWN", capabilities.path(operation).path("modelSupport").asText());
                    assertEquals("UNKNOWN", capabilities.path("text").path("modelSupport").asText());
                    assertEquals("UNKNOWN", capabilities.path(operation.equals("image") ? "pdf" : "image").path("modelSupport").asText());
                }
            }
            assertEquals(6, requests.size());
        } finally { server.stop(0); }
    }

    private String observedColors(JsonNode content) throws IOException {
        String dataUrl = content.get(0).path("image_url").path("url").asText();
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1))));
        try {
            assertEquals(432, image.getWidth());
            assertEquals(96, image.getHeight());
            Map<Integer, String> palette = Map.of(0xFF0000, "RED", 0x00FF00, "GREEN", 0x0000FF, "BLUE",
                    0xFFFF00, "YELLOW", 0x000000, "BLACK", 0xFFFFFF, "WHITE");
            List<String> colors = new ArrayList<>();
            for (int tile = 0; tile < 6; tile++) {
                String color = palette.get(image.getRGB(tile * 72 + 36, 48) & 0xFFFFFF);
                assertNotNull(color);
                colors.add(color);
            }
            return String.join(",", colors);
        } finally { image.flush(); }
    }

    @Test
    void strictSchemaProbesUseNativeWireFieldsAndRejectNonconformingJson() throws Exception {
        for (String provider : List.of("openai", "openai-codex")) {
            List<JsonNode> requests = new CopyOnWriteArrayList<>();
            AtomicReference<String> output = new AtomicReference<>("{\"ok\":true}");
            HttpServer server = server(exchange -> {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                respond(exchange, provider.equals("openai") ? chatResponse(output.get())
                        : "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":"
                        + mapper.writeValueAsString(output.get()) + "}\n\nevent: response.completed\ndata: "
                        + "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");
            });
            try {
                configure(provider, url(server));
                assertEquals("PASSED", NativeChatModels.probe(root, null, null,
                        "json_schema", true, Duration.ofSeconds(2)).get("probeStatus"));
                JsonNode format = provider.equals("openai") ? requests.get(0).path("response_format")
                        : requests.get(0).path("text").path("format");
                assertEquals("json_schema", format.path("type").asText());
                JsonNode contract = provider.equals("openai") ? format.path("json_schema") : format;
                assertTrue(contract.path("strict").asBoolean());
                assertEquals("boolean", contract.path("schema").path("properties").path("ok").path("type").asText());
                output.set("{\"ok\":\"true\"}");
                Map<String, Object> wrongType = NativeChatModels.probe(root, null, null,
                        "json_schema", true, Duration.ofSeconds(2));
                assertEquals("INCONCLUSIVE", wrongType.get("probeStatus"));
                assertEquals("UNKNOWN", wrongType.get("readiness"));
                assertEquals(2, requests.size());
            } finally { server.stop(0); }
        }
    }

    @Test
    void codexAndClaudeUseNativeWireFormatsWithoutCliFallback() throws Exception {
        for (String provider : List.of("openai-codex", "anthropic")) {
            List<String> paths = new CopyOnWriteArrayList<>();
            List<JsonNode> requests = new CopyOnWriteArrayList<>();
            HttpServer server = server(exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                requests.add(mapper.readTree(exchange.getRequestBody()));
                String body = provider.equals("openai-codex")
                        ? "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"native-ok\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                        : "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"native-ok\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
                respond(exchange, body);
            });
            try {
                configure(provider, provider.equals("anthropic")
                        ? "http://127.0.0.1:" + server.getAddress().getPort() : url(server));
                assertEquals("native-ok", NativeChatModels.complete(root,
                        provider.equals("anthropic") ? "claude" : "codex", "exact-model", "hello", "system", Duration.ofSeconds(3)));
                assertEquals(provider.equals("anthropic") ? "/v1/messages" : "/v1/codex/responses", paths.get(0));
                assertEquals("exact-model", requests.get(0).path("model").asText());
                assertFalse(requests.get(0).has("tools"));
            } finally { server.stop(0); }
        }
    }

    @Test
    void wholeRequestDeadlineAndOutputBoundAreEnforced() throws Exception {
        HttpServer slow = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            respond(exchange, chatResponse("late"));
        });
        try {
            configure("custom", url(slow));
            assertThrows(TimeoutException.class, () -> NativeChatModels.complete(root, null, null,
                    "input", "system", Duration.ofMillis(50)));
        } finally { slow.stop(0); }
        HttpServer verbose = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, chatResponse("x".repeat(2000)));
        });
        try {
            configure("custom", url(verbose));
            Exception failure = assertThrows(Exception.class, () -> NativeChatModels.call(root,
                    NativeChatModels.resolve(root, null, null), "input", "system", List.of(), null, Duration.ofSeconds(2), 100));
            assertTrue(failure.getMessage().contains("maxResponseChars"), failure.toString());
        } finally { verbose.stop(0); }
    }

    @Test
    void providerFailureCannotReturnPartialTextOrEchoSecrets() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] error = "{\"error\":{\"message\":\"fixture-secret and private source\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, error.length);
            try (var out = exchange.getResponseBody()) { out.write(error); }
        });
        try {
            configure("custom", url(server));
            Exception failure = assertThrows(Exception.class, () -> NativeChatModels.complete(root, null, null,
                    "private source", "system", Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains("HTTP=400"));
            assertFalse(failure.getMessage().contains("fixture-secret"));
            assertFalse(failure.getMessage().contains("private source"));
            Map<String, Object> probe = NativeChatModels.probe(root, null, null, "image", true, Duration.ofSeconds(2));
            assertEquals("FAILED", probe.get("probeStatus"));
            assertEquals("REQUEST_FAILED", probe.get("failureKind"));
            assertEquals("UNKNOWN", probe.get("readiness"));
            assertEquals("NOT_ESTABLISHED", probe.get("authentication"));
            JsonNode capabilities = mapper.valueToTree(probe.get("capabilities"));
            assertEquals("UNKNOWN", capabilities.path("image").path("modelSupport").asText());
            assertFalse(probe.toString().contains("fixture-secret"));
            assertFalse(probe.toString().contains("private source"));
        } finally { server.stop(0); }
    }

    @Test
    void nativeFailureIncludesSafeProviderSchemaDetailsWithoutEchoingCredentials() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"error\":{\"message\":\"Invalid schema: minItems is not permitted\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"invalid_json_schema\","
                    + "\"param\":\"text.format.schema\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        try {
            configure("custom", url(server));
            Exception failure = assertThrows(Exception.class, () -> NativeChatModels.complete(
                    root, null, null, "schema prepass", "system", Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains("Invalid schema: minItems is not permitted"),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("invalid_json_schema"), failure.getMessage());
            assertFalse(failure.getMessage().contains("fixture-secret"));
            assertFalse(failure.getMessage().contains("schema prepass"));
        } finally { server.stop(0); }
    }

    @Test
    void documentBindingWinsOverDefaultAndCredentialsCannotBeInline() throws Exception {
        configure("custom", "http://127.0.0.1:1/v1");
        var pipeline = new LocalCrawlCapabilities.ResolvedPipeline("inline", "CHAT_MODEL", "text", "no-op", 0, 0,
                Map.of("modelBindings", Map.of("default", "bound"), "modelDefinitions", Map.of("bound",
                        Map.of("source", "chat", "provider", "claude", "modelId", "bound-model"))),
                Map.of("type", "CHAT_MODEL", "provider", "custom", "modelId", "default-model",
                        "modelBindings", Map.of("default", "stale-default")));
        Map<String, Object> preview = ChatModelPipelineRunner.previewSelection(root, pipeline);
        assertEquals("anthropic", preview.get("provider"));
        assertEquals("bound-model", preview.get("model"));
        assertThrows(IOException.class, () -> NativeChatModels.rejectInlineCredentials(Map.of("provider", Map.of("apiKey", "secret"))));
        assertDoesNotThrow(() -> NativeChatModels.rejectInlineCredentials(Map.of("jsonSchema", Map.of("properties", Map.of("token", Map.of("type", "string"))))));
    }

    @Test
    void shorthandModelReferencesUseTheirProviderAndUnsupportedDocumentContractsFailClosed() throws Exception {
        configure("custom", "http://127.0.0.1:1/v1");
        for (String shorthand : List.of("modelId", "modelSetId", "vlmModel")) {
            var pipeline = new LocalCrawlCapabilities.ResolvedPipeline("inline", "CHAT_MODEL", "text", "no-op", 0, 0,
                    Map.of(shorthand, "ref", "modelDefinitions", Map.of("ref",
                            Map.of("source", "chat", "provider", "claude", "modelId", "actual-model"))),
                    Map.of("type", "CHAT_MODEL"));
            assertEquals("anthropic", ChatModelPipelineRunner.previewSelection(root, pipeline).get("provider"));
            assertEquals("actual-model", ChatModelPipelineRunner.previewSelection(root, pipeline).get("model"));
        }
        for (String field : List.of("tools", "pipelineDefinition", "modelRuntime", "requiredToolChoice")) {
            var pipeline = new LocalCrawlCapabilities.ResolvedPipeline("inline", "CHAT_MODEL", "text", "no-op", 0, 0,
                    Map.of(), Map.of("type", "CHAT_MODEL", field, Map.of()));
            assertThrows(IOException.class, () -> ChatModelPipelineRunner.extractText(root, "hello", pipeline, null));
        }
    }

    private Path configure(String provider, String baseUrl) throws Exception {
        Path path = Files.createDirectories(root.resolve(".kompile")).resolve("chat-config.json");
        // Deliberately legacy/in-memory auth: the resolver must not migrate this into the user's store.
        mapper.writeValue(path.toFile(), Map.of("provider", provider, "model", "configured-model",
                "baseUrl", baseUrl, "apiKey", "fixture-secret"));
        return path;
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    private String chatResponse(String text) throws IOException {
        return "data: {\"choices\":[{\"delta\":{\"content\":" + mapper.writeValueAsString(text)
                + "},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    }
    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
