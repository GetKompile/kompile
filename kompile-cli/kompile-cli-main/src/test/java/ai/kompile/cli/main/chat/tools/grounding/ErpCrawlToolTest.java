package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ErpCrawlToolTest {
    @TempDir Path projectRoot;
    private final ObjectMapper mapper = new ObjectMapper();
    private String previousAdmissionMode;

    @BeforeEach
    void setUp() {
        previousAdmissionMode = System.getProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off");
    }

    @AfterEach
    void tearDown() {
        if (previousAdmissionMode == null) {
            System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        } else {
            System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, previousAdmissionMode);
        }
    }

    @Test
    void erpSourcesAlwaysDispatchLocallyIncludingNestedRequests() {
        for (String type : Set.of("SAP_NETWEAVER", "sap-netweaver", "ODATA", "odata",
                "DYNAMICS365", "dynamics365", "NETSUITE", "netsuite", "ODOO", "odoo", "SALESFORCE", "salesforce")) {
            for (String array : Set.of("documents", "sources")) {
                ObjectNode request = mapper.createObjectNode();
                request.putArray(array).addObject().put("sourceType", type);
                assertTrue(CrawlDocumentsTool.requiresLocalExecution(request), request.toString());
                ObjectNode nested = mapper.createObjectNode();
                nested.set("config", request);
                assertTrue(CrawlDocumentsTool.requiresLocalExecution(nested), nested.toString());
            }
        }
        ObjectNode remote = mapper.createObjectNode();
        remote.putArray("documents").addObject().put("sourceType", "URL");
        assertFalse(CrawlDocumentsTool.requiresLocalExecution(remote));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ODATA", "DYNAMICS365", "NETSUITE", "ODOO", "SALESFORCE"})
    void crawlsErpThroughChatIntoLocalSearchWithoutPersistingSecrets(String type) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String rootPath = switch (type) {
            case "DYNAMICS365" -> "/data/";
            case "NETSUITE" -> "/services/rest/record/v1/";
            case "ODOO" -> "/json/2/";
            case "SALESFORCE" -> "/services/data/v60.0/";
            default -> "/erp/";
        };
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext(rootPath, exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            String record = "{\"ID\":\"42\",\"Id\":\"42\",\"id\":\"42\",\"description\":\"erp-cobalt-puffin-marker\"}";
            String response = switch (type) {
                case "NETSUITE" -> exchange.getRequestURI().getPath().endsWith("/42") ? record
                        : "{\"items\":[{\"id\":\"42\"}],\"count\":1,\"offset\":0,\"hasMore\":false}";
                case "ODOO" -> "[" + record + "]";
                case "SALESFORCE" -> "{\"records\":[" + record + "],\"done\":true}";
                default -> "{\"value\":[" + record + "]}";
            };
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            for (String tool : Set.of("crawl_documents", "knowledge_search")) {
                permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
            }
            ToolContext context = new ToolContext("erp-crawl-fixture",
                    AgentConfig.builder("offline-worker").enabledTools(Set.of("*")).build(),
                    permissions, projectRoot, new ToolRegistry(mapper));
            ObjectNode request = mapper.createObjectNode().put("async", false);
            request.putObject("knowledgeBase").put("name", "erp-fixture");
            ObjectNode source = request.putArray("documents").addObject().put("sourceType", type);
            ObjectNode properties = source.putObject("properties")
                    .put("serviceRoot", "http://127.0.0.1:" + server.getAddress().getPort() + rootPath)
                    .put("entitySet", "Orders").put("accessToken", "erp-fixture-secret");
            if (Set.of("ODOO", "SALESFORCE").contains(type)) properties.put("select", "description");
            ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals("ODOO".equals(type) ? "POST" : "GET", method.get());
            assertEquals("Bearer erp-fixture-secret", authorization.get());
            String knowledgeBase = (String) result.getMetadata().get("knowledgeBase");
            Path crawl = projectRoot.resolve("data/crawls").resolve(knowledgeBase);
            String persisted = Files.readString(crawl.resolve("mcp-request.json"));
            String documents = Files.readString(crawl.resolve("documents.jsonl"));
            assertFalse(persisted.contains("erp-fixture-secret"), persisted);
            assertFalse(documents.contains("erp-fixture-secret"), documents);
            assertTrue(documents.contains("erp.entitySet"), documents);
            JsonNode document = mapper.readTree(documents.lines().findFirst().orElseThrow());
            String markdown = Files.readString(projectRoot.resolve(document.path("markdownPath").asText()));
            assertTrue(markdown.contains("erp-cobalt-puffin-marker"), markdown);
            assertFalse(markdown.contains("erp-fixture-secret"), markdown);
            ToolResult search = new KnowledgeSearchCliTool((String) null, mapper).execute(
                    mapper.createObjectNode().put("query", "erp-cobalt-puffin-marker")
                            .put("knowledgeBase", knowledgeBase), context);
            assertFalse(search.isError(), search.getOutput());
            assertTrue(search.getOutput().contains("erp-cobalt-puffin-marker"), search.getOutput());
        } finally {
            server.stop(0);
        }
    }
}
