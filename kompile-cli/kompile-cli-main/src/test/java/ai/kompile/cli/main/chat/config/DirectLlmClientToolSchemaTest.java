package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tools.PatchTool;
import ai.kompile.cli.main.chat.tools.ReadBatchTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DirectLlmClientToolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiKeyAndSubscriptionRequestsAcceptRealPatchAndReadBatchSchemas() throws Exception {
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(new PatchTool());
        registry.register(new ReadBatchTool());
        AgentConfig agent = AgentConfig.builder("test")
                .enabledTools(Set.of("patch", "read_batch")).build();
        ArrayNode definitions = registry.buildDirectToolDefinitions(agent);
        ArrayNode original = definitions.deepCopy();

        for (String provider : List.of("openai", "openai-codex")) {
            JsonNode request = captureRequest(provider, definitions, provider.equals("openai"));
            JsonNode patch = function(request, "patch");
            JsonNode parameters = patch.path("parameters");
            assertEquals("object", parameters.path("type").asText());
            assertEquals(new PatchTool().parameterSchema().path("properties"),
                    parameters.path("properties"));
            assertTrue(parameters.path("required").isEmpty(), "aliases remain optional");
            assertEquals(new ReadBatchTool().parameterSchema(),
                    function(request, "read_batch").path("parameters"),
                    "nested anyOf must survive intact");
            if (provider.equals("openai")) {
                for (String keyword : List.of("anyOf", "oneOf", "allOf")) {
                    assertFalse(parameters.has(keyword), provider + ": " + keyword);
                }
                assertTrue(parameters.path("description").asText().contains("unified_diff"));
                assertTrue(patch.path("strict").isBoolean());
                assertFalse(patch.path("strict").asBoolean());
            } else {
                assertEquals(new PatchTool().parameterSchema(), parameters,
                        "subscription Responses keeps the full MCP schema, as Codex does");
                assertTrue(patch.path("strict").isNull());
            }
            assertEquals(original, definitions, "serialization must not mutate registry schemas");
        }
    }

    @Test
    void otherChatCompletionsProvidersKeepTheirOriginalSchema() throws Exception {
        ArrayNode definitions = mapper.createArrayNode();
        definitions.addObject().put("name", "patch")
                .set("inputSchema", new PatchTool().parameterSchema());
        JsonNode patch = function(captureRequest("deepseek", definitions, false), "patch");
        assertEquals(definitions.get(0).path("inputSchema"), patch.path("parameters"));
        assertFalse(patch.has("strict"));
    }

    @Test
    void rootCompositionsRetainBranchFieldsNestedSchemasAndLiteralData() throws Exception {
        JsonNode original = mapper.readTree("""
                {"description":"Choose arguments", "required":["common"],
                 "properties":{"common":{"type":"string"},
                   "literal":{"type":"object","default":{"anyOf":[1,2]}},
                   "nested":{"anyOf":[{"type":"string"},{"type":"number"}]}},
                 "anyOf":[{"properties":{"value":{"type":"string"}},"required":["value"]},
                          {"properties":{"value":{"type":"number"},"other":{"type":"boolean"}}}],
                 "oneOf":[{"required":["common"]},{"required":["other"]}],
                 "allOf":[{"properties":{"extra":{"type":"integer"}},
                           "allOf":[{"properties":{"deep":{"type":"string"}}}]}]}
                """);
        JsonNode snapshot = original.deepCopy();
        ObjectNode wire = OpenAiToolSchema.parameters(original);
        assertEquals("object", wire.path("type").asText());
        assertEquals(original.path("required"), wire.path("required"));
        assertEquals(original.path("properties").path("nested"), wire.path("properties").path("nested"));
        assertEquals(original.path("properties").path("literal"), wire.path("properties").path("literal"));
        assertEquals(mapper.readTree("[{\"type\":\"string\"},{\"type\":\"number\"}]"),
                wire.path("properties").path("value").path("anyOf"));
        assertEquals("integer", wire.path("properties").path("extra").path("type").asText());
        assertEquals("string", wire.path("properties").path("deep").path("type").asText());
        assertEquals("boolean", wire.path("properties").path("other").path("type").asText());
        assertTrue(wire.path("description").asText().startsWith("Choose arguments\n"));
        for (String keyword : List.of("anyOf", "oneOf", "allOf")) {
            assertFalse(wire.has(keyword));
            assertTrue(wire.path("description").asText().contains(keyword));
        }
        assertEquals(snapshot, original);
    }

    @Test
    void missingAndNullSchemasProduceEmptyObjectParameters() {
        for (JsonNode schema : List.of(mapper.missingNode(), mapper.nullNode(), mapper.createObjectNode())) {
            ObjectNode result = OpenAiToolSchema.parameters(schema);
            assertEquals("object", result.path("type").asText());
            assertTrue(result.path("properties").isObject());
            assertTrue(result.path("properties").isEmpty());
        }
    }

    private JsonNode captureRequest(String provider, ArrayNode definitions, boolean validate) throws Exception {
        boolean codex = provider.equals("openai-codex");
        String endpoint = codex ? "/codex/responses" : "/chat/completions";
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(endpoint, exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            captured.set(request);
            boolean invalid = false;
            if (validate) {
                for (JsonNode tool : request.path("tools")) {
                    JsonNode parameters = (tool.has("function") ? tool.path("function") : tool)
                            .path("parameters");
                    invalid |= !"object".equals(parameters.path("type").asText())
                            || parameters.has("anyOf") || parameters.has("oneOf") || parameters.has("allOf");
                }
            }
            String response = invalid
                    ? "{\"error\":{\"message\":\"Invalid schema for function 'patch'\",\"code\":\"invalid_function_parameters\"}}"
                    : codex
                    ? "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                      + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                      + "data: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", invalid ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(invalid ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (DirectLlmClient client = new DirectLlmClient(new ChatConfig(provider, "test-key", "gpt-5",
                "http://127.0.0.1:" + server.getAddress().getPort()), mapper)) {
            client.setOutputConsumer(ignored -> { });
            DirectLlmClient.StreamResult result = client.streamChat("hello", "system", definitions, null);
            assertFalse(result.failed, result.text);
            assertEquals("ok", result.text);
            return captured.get();
        } finally {
            server.stop(0);
        }
    }

    private JsonNode function(JsonNode request, String name) {
        for (JsonNode tool : request.path("tools")) {
            JsonNode function = tool.has("function") ? tool.path("function") : tool;
            if (name.equals(function.path("name").asText())) return function;
        }
        throw new AssertionError("Missing tool " + name);
    }
}
