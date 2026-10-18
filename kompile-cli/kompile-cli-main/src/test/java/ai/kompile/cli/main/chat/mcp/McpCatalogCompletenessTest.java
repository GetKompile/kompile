/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only focused protocol qualification; the child is plain Java/Jackson, not the CLI or an app. */
@TemporaryUserHome
@Timeout(30)
class McpCatalogCompletenessTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path workspace;

    @Test
    void everyToolIsReachableAcrossBoundedSchemaPagesWithoutExposingExcludedNestedChat() throws Exception {
        try (Harness harness = product("full")) {
            for (boolean schemas : List.of(false, true)) {
                Set<String> ids = new HashSet<>(), names = new HashSet<>();
                int offset = 0, pages = 0;
                while (true) {
                    ObjectNode request = MAPPER.createObjectNode().put("kind", "tool").put("offset", offset)
                            .put("limit", 70).put("includeSchemas", schemas);
                    ToolResult result = harness.search(request);
                    assertFalse(result.isError());
                    assertEquals("", result.getTitle(), "wire JSON must not have a text title prefix");
                    assertTrue(result.getOutput().length() <= McpBundleToolLoader.MAX_CATALOG_PAGE_CHARS);
                    assertFalse(result.getOutput().contains("\n"), "a page must be compact complete JSON, not a line preview");
                    JsonNode page = MAPPER.readTree(result.getOutput());
                    assertEquals(1, page.path("catalogVersion").asInt());
                    assertEquals(347, page.path("total").asInt());
                    assertEquals(offset, page.path("offset").asInt());
                    assertTrue(page.path("complete").asBoolean());
                    assertTrue(page.path("errors").isEmpty());
                    assertEquals(page.path("entries").size(), page.path("returned").asInt());
                    for (JsonNode entry : page.path("entries")) {
                        assertTrue(ids.add(entry.path("id").asText()), "duplicate id across pages");
                        assertTrue(names.add(entry.path("name").asText()), "duplicate name across pages");
                        assertEquals("elthoria", entry.path("server").asText());
                        assertEquals(schemas, entry.has("inputSchema"));
                        if (schemas) assertEquals("string", entry.path("inputSchema").path("properties").path("value").path("type").asText());
                        assertNotEquals("elthoria_chat", entry.path("name").asText());
                    }
                    pages++;
                    if (page.path("nextOffset").isNull()) {
                        assertFalse(page.path("truncated").asBoolean());
                        break;
                    }
                    assertTrue(page.path("truncated").asBoolean());
                    int next = page.path("nextOffset").asInt();
                    assertTrue(next > offset);
                    offset = next;
                    assertTrue(pages < 348);
                }
                assertEquals(347, ids.size());
                assertEquals(347, names.size());
                assertTrue(pages > 1);
            }
            assertEquals(0, harness.page("tool", "elthoria_chat").path("total").asInt());
            assertTrue(harness.search(MAPPER.createObjectNode().put("query", "elthoria_chat")).getOutput()
                    .contains("No MCP tools matched"));
            String legacy = harness.search(MAPPER.createObjectNode().put("query", "tool_346")).getOutput();
            assertTrue(legacy.startsWith("mcp__elthoria__tool_346 - MCP elthoria/tool_346:"));
            assertTrue(legacy.contains("\n  inputSchema: {\"type\":\"object\""));
            assertTrue(harness.registry.ids().stream().noneMatch(id -> id.startsWith("mcp_prompt__") || id.startsWith("mcp_resource__")));
            assertEquals(1, Files.readAllLines(workspace.resolve("launches")).size());
            assertEquals(10, harness.methods().stream().filter("tools/list"::equals).count());
        }
    }

    @Test
    void includeAllowlistAndExcludeDenylistApplyBeforeBothCatalogModes() throws Exception {
        try (Harness harness = product("included")) {
            JsonNode page = harness.page("tool", "");
            assertTrue(page.path("complete").asBoolean());
            assertEquals(2, page.path("total").asInt());
            Set<String> names = new HashSet<>();
            for (JsonNode entry : page.path("entries")) names.add(entry.path("name").asText());
            assertEquals(Set.of("get_play_connection", "tool_346"), names);
            String legacy = harness.search(MAPPER.createObjectNode().put("query", "")).getOutput();
            assertFalse(legacy.contains("elthoria_chat"));
            assertFalse(legacy.contains("tool_345"));
            assertTrue(harness.call("mcp__elthoria__tool_345", MAPPER.createObjectNode()).isError());
        }
    }

    @Test
    void promptsResourcesAndLegacyToolsShareOnePinnedChildAndPromptsStayResultData() throws Exception {
        Harness harness = product("full");
        try (harness) {
            JsonNode prompts = harness.page("prompt", "");
            assertTrue(prompts.path("complete").asBoolean());
            assertEquals(2, prompts.path("total").asInt());
            for (String kind : List.of("prompt", "resource")) {
                Set<String> paged = new HashSet<>();
                int offset = 0;
                while (true) {
                    JsonNode page = MAPPER.readTree(harness.search(MAPPER.createObjectNode().put("kind", kind)
                            .put("offset", offset).put("limit", 1).put("includeSchemas", true)).getOutput());
                    assertTrue(page.path("complete").asBoolean());
                    assertEquals(1, page.path("returned").asInt());
                    assertTrue(paged.add(page.path("entries").get(0).path("id").asText()));
                    if (page.path("nextOffset").isNull()) break;
                    assertEquals(++offset, page.path("nextOffset").asInt());
                }
                assertEquals("prompt".equals(kind) ? 2 : 4, paged.size());
            }
            JsonNode prompt = harness.page("prompt", "scene_guide").path("entries").get(0);
            assertEquals("mcp_prompt__elthoria__scene_guide", prompt.path("id").asText());
            assertEquals("topic", prompt.path("inputSchema").path("required").get(0).asText());
            ToolResult promptResult = harness.call(prompt.path("id").asText(), MAPPER.createObjectNode().put("topic", "The harbor"));
            assertFalse(promptResult.isError());
            JsonNode data = MAPPER.readTree(promptResult.getOutput());
            assertTrue(data.path("dataOnly").asBoolean());
            assertEquals("The harbor", data.path("result").path("arguments").path("topic").asText());
            assertEquals("assistant", data.path("result").path("messages").get(0).path("role").asText());
            assertTrue(promptResult.getMetadata().isEmpty(), "no provider/role/system prompt metadata");
            long pid = data.path("result").path("pid").asLong();

            JsonNode resources = harness.page("resource", "");
            assertTrue(resources.path("complete").asBoolean());
            assertEquals(4, resources.path("total").asInt());
            Set<String> unique = new HashSet<>();
            for (JsonNode entry : resources.path("entries")) assertTrue(unique.add(entry.path("id").asText()));
            JsonNode concrete = resource(resources, "world://catalog/rules");
            JsonNode template = resource(resources, "world://catalog/entry/{key}");
            for (String id : List.of(concrete.path("id").asText(), template.path("id").asText())) {
                ObjectNode arguments = MAPPER.createObjectNode();
                if (id.equals(template.path("id").asText())) arguments.putObject("variables").put("key", "harbor_1");
                ToolResult result = harness.call(id, arguments);
                assertFalse(result.isError(), result.getOutput());
                JsonNode read = MAPPER.readTree(result.getOutput()).path("result");
                assertEquals(pid, read.path("pid").asLong());
                assertEquals(id.equals(template.path("id").asText()) ? "world://catalog/entry/harbor_1" : "world://catalog/rules",
                        read.path("contents").get(0).path("uri").asText());
            }
            for (String alias : List.of("mcp__elthoria__tool_346", "elthoria/tool_346", "tool_346")) {
                ToolResult legacy = harness.call(alias, MAPPER.createObjectNode().put("value", "old-wire"));
                assertFalse(legacy.isError());
                JsonNode payload = MAPPER.readTree(legacy.getOutput());
                assertEquals(pid, payload.path("pid").asLong());
                assertEquals("old-wire", payload.path("arguments").path("value").asText());
                assertEquals("{\"kept\":true}", legacy.getMetadata().get("structuredContent"));
            }
            assertEquals(1, Files.readAllLines(workspace.resolve("launches")).size());
            assertEquals(1, harness.methods().stream().filter("initialize"::equals).count());
            assertEquals(2, harness.methods().stream().filter("prompts/list"::equals).count());
            assertEquals(2, harness.methods().stream().filter("resources/list"::equals).count());
            assertEquals(2, harness.methods().stream().filter("resources/templates/list"::equals).count());
        }
        assertTrue(harness.loader.productCleanupConfirmed());
        assertEquals("confirmed", Files.readString(workspace.resolve("cooperative-close")));
    }

    @Test
    void sdkLegacyResourceAliasesRequireMatchingAdvertisedTemplatesAndExpandOnlySafeVariables() throws Exception {
        Harness harness = product("sdk-alias");
        try (harness) {
            JsonNode page = harness.page("resource", "");
            assertTrue(page.path("complete").asBoolean(), page.toString());
            assertEquals(6, page.path("total").asInt(), "Two concrete resources and four templates, not legacy alias duplicates");
            assertTrue(resource(page, "world://catalog/rules").has("uri"));
            assertTrue(resource(page, "world://catalog/map").has("uri"));
            Set<String> ids = new HashSet<>();
            for (JsonNode entry : page.path("entries")) assertTrue(ids.add(entry.path("id").asText()));
            for (String suffix : List.of("", "/stats")) {
                JsonNode template = resource(page, "campaign://characters/{id}" + suffix);
                assertFalse(template.has("uri"));
                assertEquals("string", template.path("inputSchema").path("properties").path("variables")
                        .path("properties").path("id").path("type").asText());
                String id = template.path("id").asText();
                int before = harness.methods().size();
                for (String unsafe : List.of("../17", "%31%37", "17/stats", "17?override=true", "17#stats", "")) {
                    ObjectNode arguments = MAPPER.createObjectNode();
                    arguments.putObject("variables").put("id", unsafe);
                    assertTrue(harness.call(id, arguments).isError());
                }
                assertTrue(harness.call(id, MAPPER.createObjectNode().put("uri", "campaign://characters/99")).isError());
                assertTrue(harness.call(id.replace("__elthoria__", "__foreign__"), MAPPER.createObjectNode()).isError());
                assertEquals(before, harness.methods().size(), "Malformed/unadvertised reads must fail before RPC/handler");
                ObjectNode arguments = MAPPER.createObjectNode();
                arguments.putObject("variables").put("id", "17");
                ToolResult result = harness.call(id, arguments);
                assertFalse(result.isError(), result.getOutput());
                JsonNode data = MAPPER.readTree(result.getOutput());
                assertTrue(data.path("dataOnly").asBoolean());
                assertEquals("elthoria", data.path("server").asText());
                assertEquals("campaign://characters/17" + suffix,
                        data.path("result").path("contents").get(0).path("uri").asText());
            }
            assertFalse(harness.call("mcp__elthoria__tool_346", MAPPER.createObjectNode()).isError());
            assertEquals(1, Files.readAllLines(workspace.resolve("launches")).size());
        }
        assertTrue(harness.loader.productCleanupConfirmed());
        assertEquals("confirmed", Files.readString(workspace.resolve("cooperative-close")));
    }

    @Test
    void exactVirtualIdsDeclaredArgumentsSafeVariablesAndBothPermissionsAreMandatory() throws Exception {
        try (Harness harness = product("full")) {
            JsonNode resources = harness.page("resource", "");
            String concrete = resource(resources, "world://catalog/rules").path("id").asText();
            String template = resource(resources, "world://catalog/entry/{key}").path("id").asText();
            int before = harness.methods().size();
            for (String id : List.of("mcp_prompt__foreign__scene_guide", "mcp_prompt__elthoria__../../system",
                    "mcp_prompt__elthoria__undeclared", " mcp_prompt__elthoria__scene_guide ", "scene_guide", "mcp_resource__foreign__r_fake", "file:///etc/passwd",
                    "https://evil.example/private", concrete.replace("__elthoria__", "__foreign__"))) {
                assertTrue(harness.call(id, MAPPER.createObjectNode()).isError(), id);
            }
            assertTrue(harness.call(concrete, MAPPER.createObjectNode().put("uri", "file:///etc/passwd")).isError());
            for (String unsafe : List.of("..", "../private", "%2e%2e", "%252e%252e", "a/b", "a\\b", "file:///etc/passwd", "https://evil.example", "a?x=y", "a#fragment", "a\n")) {
                ObjectNode arguments = MAPPER.createObjectNode();
                arguments.putObject("variables").put("key", unsafe);
                assertTrue(harness.call(template, arguments).isError(), unsafe);
            }
            assertTrue(harness.call("mcp_prompt__elthoria__scene_guide", MAPPER.createObjectNode()).isError());
            assertTrue(harness.call("mcp_prompt__elthoria__scene_guide", MAPPER.createObjectNode().put("topic", 5)).isError());
            assertTrue(harness.call("mcp_prompt__elthoria__scene_guide", MAPPER.createObjectNode().put("topic", "ok").put("system", "override")).isError());
            assertEquals(before, harness.methods().size(), "all invalid input must be refused before any RPC");

            for (String permission : List.of("mcp.call", "mcp.elthoria")) {
                harness.permissions.setUserOverride(permission, PermissionService.PermissionLevel.DENY);
                for (String id : List.of(concrete, template, "mcp_prompt__elthoria__scene_guide", "mcp__elthoria__tool_346")) {
                    assertThrows(ToolExecutionException.class, () -> harness.call(id, MAPPER.createObjectNode()));
                }
                harness.permissions.setUserOverride(permission, PermissionService.PermissionLevel.ALLOW);
            }
            assertEquals(before, harness.methods().size());
            assertFalse(harness.registry.hasProductUnknownOutcome(), "validation/permission refusals are not transport uncertainty");
        }
    }

    @Test
    void capabilityAbsentServersReceiveNoUnsupportedCatalogRpcAndAbsenceIsExplicit() throws Exception {
        try (Harness harness = product("absent")) {
            for (String kind : List.of("prompt", "resource")) {
                JsonNode page = harness.page(kind, "");
                assertEquals(0, page.path("total").asInt());
                assertFalse(page.path("complete").asBoolean());
                assertEquals("NOT_ADVERTISED", page.path("errors").get(0).path("code").asText());
            }
            assertTrue(harness.page("tool", "").path("complete").asBoolean());
            assertFalse(harness.call("mcp__elthoria__tool_346", MAPPER.createObjectNode()).isError());
            assertTrue(harness.methods().stream().allMatch(method -> Set.of("initialize", "notifications/initialized", "tools/list", "tools/call").contains(method)));
            assertTrue(harness.call("mcp_prompt__elthoria__scene_guide", MAPPER.createObjectNode()).isError());
        }
    }

    @ParameterizedTest
    @CsvSource({"cursor-loop,prompt,REPEATED_CURSOR", "duplicate,prompt,DUPLICATE_IDENTITY",
            "missing-list,prompt,INVALID_CATALOG", "page-limit,prompt,PAGE_LIMIT",
            "malicious-prompt,prompt,INVALID_IDENTITY", "malicious-uri,resource,INVALID_URI",
            "authority-template,resource,UNSAFE_TEMPLATE", "sdk-alias-unadvertised,resource,UNADVERTISED_TEMPLATE",
            "sdk-alias-conflict,resource,CONFLICTING_TEMPLATE", "sdk-alias-authority,resource,UNSAFE_TEMPLATE",
            "sdk-alias-operator,resource,UNSAFE_TEMPLATE"})
    void invalidOrPartialCatalogsAreTypedBoundedAndNeverAdvertisedAsComplete(String mode, String kind, String code) throws Exception {
        try (Harness harness = product(mode)) {
            ToolResult result = harness.search(MAPPER.createObjectNode().put("kind", kind));
            assertFalse(result.isError(), "catalog state is data; invocation itself succeeded");
            assertTrue(result.getOutput().length() < 2048);
            JsonNode page = MAPPER.readTree(result.getOutput());
            assertFalse(page.path("complete").asBoolean());
            assertEquals(0, page.path("total").asInt());
            assertTrue(page.path("entries").isEmpty(), "atomic family inventory must not leak partial entries");
            assertEquals(code, page.path("errors").get(0).path("code").asText());
        }
    }

    @Test
    void oversizedSchemasAndInvalidPageOptionsProduceExplicitErrorsNotSlicedJson() throws Exception {
        try (Harness harness = product("huge-schema")) {
            JsonNode page = harness.page("tool", "tool_346");
            assertEquals(1, page.path("total").asInt());
            assertFalse(page.path("complete").asBoolean());
            assertEquals("ENTRY_TOO_LARGE", page.path("errors").get(0).path("code").asText());
            assertEquals("mcp__elthoria__tool_346", page.path("errors").get(0).path("id").asText());
            ToolResult withoutSchemas = harness.search(MAPPER.createObjectNode().put("kind", "tool").put("query", "tool_346"));
            assertEquals(1, MAPPER.readTree(withoutSchemas.getOutput()).path("returned").asInt());
            for (String invalid : List.of("{\"kind\":\"other\"}", "{\"offset\":-1}", "{\"limit\":0}", "{\"limit\":101}",
                    "{\"offset\":1.5}", "{\"offset\":2147483648}", "{\"includeSchemas\":\"true\"}")) {
                ToolResult error = harness.search(MAPPER.readTree(invalid));
                assertTrue(error.isError());
                assertEquals("INVALID_PAGE", MAPPER.readTree(error.getOutput()).path("error").path("code").asText());
            }
        }
    }

    @Test
    void duplicateToolMetadataIsRejectedAndNewOperationsKeepStickyTransportQuarantine() throws Exception {
        try (McpStdioClient client = client("duplicate-tools")) {
            client.initialize();
            IOException failure = assertThrows(IOException.class, client::listTools);
            assertTrue(failure.getMessage().contains("DUPLICATE_IDENTITY"));
        }
        try (McpStdioClient client = client("malformed-call")) {
            client.initialize();
            client.listTools();
            client.listPrompts();
            client.listResources();
            client.listResourceTemplates();
            IOException first = assertThrows(IOException.class, () -> client.callTool("tool_346", null));
            for (var operation : List.<org.junit.jupiter.api.function.Executable>of(client::listTools, client::listPrompts,
                    client::listResources, client::listResourceTemplates, client::listResourceCatalog, () -> client.getPrompt("scene_guide", null),
                    () -> client.readResource("world://catalog/rules"))) {
                IOException refused = assertThrows(IOException.class, operation);
                assertTrue(refused.getMessage().contains("poisoned"));
                assertSame(first, refused.getCause());
            }
            assertTrue(ProcessHandle.of(client.childProcessPid()).orElseThrow().isAlive());
        }
    }

    private static JsonNode resource(JsonNode page, String uri) {
        for (JsonNode entry : page.path("entries")) {
            if (uri.equals(entry.path("uri").asText()) || uri.equals(entry.path("uriTemplate").asText())) return entry;
        }
        throw new AssertionError("resource missing: " + uri);
    }

    private McpStdioClient client(String mode) throws Exception {
        return new McpStdioClient(MAPPER, javaCommand(), fixtureArgs(mode), Map.of(), workspace,
                5, "elthoria", null, true);
    }

    private Harness product(String mode) throws Exception {
        ObjectNode bundle = MAPPER.createObjectNode();
        ObjectNode server = bundle.putObject("mcpServers").putObject("elthoria").put("command", javaCommand());
        var args = server.putArray("args");
        fixtureArgs(mode).forEach(args::add);
        server.putArray("excludeTools").add("elthoria_chat");
        if ("included".equals(mode)) server.putArray("includeTools").add("get_play_connection").add("tool_346").add("elthoria_chat");
        Files.writeString(workspace.resolve("role.md"), "Frozen fixture role");
        Files.writeString(workspace.resolve("bundle.json"), bundle.toString());
        ObjectNode manifest = MAPPER.createObjectNode().put("schemaVersion", ProductChatPolicy.SCHEMA).put("id", "play")
                .put("title", "Catalog fixture").put("role", "gm").put("roleFile", "role.md")
                .put("mcpConfigFile", "bundle.json").put("assetsDirectory", "assets")
                .put("idleTimeoutSeconds", 1800).put("turnTimeoutSeconds", 300);
        manifest.putArray("nativeTools").add("mcp_tool_search").add("mcp_tool_call");
        manifest.putArray("mcpServers").add("elthoria");
        manifest.putObject("readCapabilities").put("connection", "mcp__elthoria__get_play_connection");
        Files.writeString(workspace.resolve("policy.json"), manifest.toString());
        ProductChatPolicy policy = ProductChatPolicy.load(workspace.resolve("policy.json"), workspace);
        // Neither a mutable installed file nor a workspace server may extend the frozen snapshot.
        String foreign = "{\"mcpServers\":{\"foreign\":{\"command\":\"must-never-start\"}}}";
        Files.writeString(workspace.resolve("bundle.json"), foreign);
        Files.writeString(workspace.resolve(".mcp.json"), foreign);
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(MAPPER));
        registry.freezeProductPolicy(policy, Set.of("*"));
        McpBundleToolLoader loader = McpBundleToolLoader.loadProduct(policy, registry, null);
        PermissionService permissions = new PermissionService();
        for (String key : List.of("mcp.discover", "mcp.call", "mcp.elthoria"))
            permissions.setUserOverride(key, PermissionService.PermissionLevel.ALLOW);
        ToolContext context = new ToolContext("catalog-test", null, permissions, workspace, registry);
        registry.openProductTurn();
        return new Harness(loader, registry, permissions, context);
    }

    private List<String> fixtureArgs(String mode) {
        return List.of("-Xmx64m", "-XX:ActiveProcessorCount=2", "-Duser.home=" + System.getProperty("user.home"), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                McpCatalogFixture.class.getName(), workspace.toString(), mode);
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private final class Harness implements AutoCloseable {
        final McpBundleToolLoader loader;
        final ToolRegistry registry;
        final PermissionService permissions;
        final ToolContext context;
        Harness(McpBundleToolLoader loader, ToolRegistry registry, PermissionService permissions, ToolContext context) {
            this.loader = loader;
            this.registry = registry;
            this.permissions = permissions;
            this.context = context;
        }
        ToolResult search(JsonNode request) throws Exception { return registry.get("mcp_tool_search").execute(request, context); }
        JsonNode page(String kind, String query) throws Exception {
            return MAPPER.readTree(search(MAPPER.createObjectNode().put("kind", kind).put("query", query)
                    .put("includeSchemas", true)).getOutput());
        }
        ToolResult call(String id, JsonNode arguments) throws ToolExecutionException {
            ObjectNode request = MAPPER.createObjectNode().put("tool", id);
            request.set("arguments", arguments);
            return registry.get("mcp_tool_call").execute(request, context);
        }
        List<String> methods() throws Exception {
            List<String> methods = new ArrayList<>();
            for (String line : Files.readAllLines(workspace.resolve("requests.jsonl"))) methods.add(MAPPER.readTree(line).path("method").asText());
            return methods;
        }
        @Override public void close() { registry.awaitProductQuiescence(); loader.close(); }
    }
}
