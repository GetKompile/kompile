package ai.kompile.source.erp;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemainingErpProfilesTest {
    HttpServer server;
    String origin;
    List<String> paths = new ArrayList<>(), methods = new ArrayList<>(), auth = new ArrayList<>(), databases = new ArrayList<>(), bodies = new ArrayList<>();
    ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    void respond(String path, java.util.function.Function<String, String> response) {
        server.createContext(path, e -> {
            paths.add(e.getRequestURI().toString()); methods.add(e.getRequestMethod());
            auth.add(e.getRequestHeaders().getFirst("Authorization"));
            databases.add(e.getRequestHeaders().getFirst("X-Odoo-Database"));
            bodies.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = response.apply(e.getRequestURI().toString()).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, data.length);
            try (var out = e.getResponseBody()) { out.write(data); }
        });
    }
    DocumentSourceDescriptor source(String type, String entity, Map<String, Object> extra) {
        String path = switch (type) {
            case "DYNAMICS365" -> "/data/";
            case "NETSUITE" -> "/services/rest/record/v1/";
            case "ODOO" -> "/json/2/";
            case "SALESFORCE" -> "/services/data/v60.0/";
            default -> throw new IllegalArgumentException();
        };
        Map<String, Object> props = new LinkedHashMap<>(Map.of("entitySet", entity, "accessToken", "fixture-private-token"));
        props.putAll(extra);
        return DocumentSourceDescriptor.builder().type(DocumentSourceDescriptor.SourceType.valueOf(type))
                .pathOrUrl(origin + path).metadata(props).build();
    }
    @Test void dynamicsOdataPagesCompositeKeysAndScope() throws Exception {
        respond("/data/Customers", path -> paths.size() == 1
                ? "{\"value\":[{\"Account\":\"1\",\"Company\":\"A\"}],\"@odata.nextLink\":\"/data/Customers?$skiptoken=abc\"}"
                : "{\"value\":[{\"Account\":\"1\",\"Company\":\"B\"}]}");
        var docs = new CamelErpDocumentLoader().load(source("DYNAMICS365", "Customers", Map.of("keyFields", "Account,Company", "filter", "Company eq 'A'")));
        assertEquals(2, docs.size()); assertNotEquals(docs.get(0).getMetadata().get("source_path"), docs.get(1).getMetadata().get("source_path"));
        assertEquals(List.of("GET", "GET"), methods);
        assertTrue(URLDecoder.decode(paths.get(1), StandardCharsets.UTF_8).contains("$filter=Company eq 'A'"));
        assertEquals(List.of("Bearer fixture-private-token", "Bearer fixture-private-token"), auth);
    }
    @Test void netSuiteListsThenReadsBoundedDetailsWithoutFollowingLinks() throws Exception {
        respond("/services/rest/record/v1/customer", path -> {
            if (path.endsWith("/1")) return "{\"id\":\"1\",\"companyName\":\"Alpha\",\"password\":\"hidden\",\"links\":[{\"href\":\"https://evil.invalid/\"}]}";
            if (path.endsWith("/2")) return "{\"id\":\"2\",\"companyName\":\"Beta\"}";
            if (path.contains("offset=1")) return "{\"items\":[{\"id\":\"2\"}],\"count\":1,\"offset\":1,\"hasMore\":false}";
            return "{\"items\":[{\"id\":\"1\",\"links\":[{\"href\":\"https://evil.invalid/\"}]}],\"count\":1,\"offset\":0,\"hasMore\":true}";
        });
        var docs = new CamelErpDocumentLoader().load(source("NETSUITE", "customer", Map.of("pageSize", 1)));
        assertEquals(2, docs.size()); assertEquals(4, paths.size());
        assertTrue(docs.get(0).getText().contains("Alpha")); assertFalse(docs.toString().contains("evil.invalid")); assertFalse(docs.toString().contains("hidden"));
        paths.clear(); methods.clear(); bodies.clear();
        assertEquals(1, new CamelErpDocumentLoader().load(source("NETSUITE", "customer", Map.of("maxRecords", 1))).size());
        assertEquals(2, paths.size()); assertEquals(List.of("GET", "GET"), methods); assertEquals(List.of("", ""), bodies);
    }
    @Test void netSuiteMalformedPaginationStopsBeforeDetails() {
        respond("/services/rest/record/v1/customer", path -> "{\"items\":[{\"id\":\"1\"}],\"count\":1,\"offset\":0,\"hasMore\":true}");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("NETSUITE", "customer", Map.of("pageSize", 2))));
        assertEquals(1, paths.size());
    }
    @Test void netSuiteInvalidIdNeverBecomesARequestPath() {
        respond("/services/rest/record/v1/customer", path -> "{\"items\":[{\"id\":\"../secret\"}],\"count\":1,\"offset\":0,\"hasMore\":false}");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("NETSUITE", "customer", Map.of())));
        assertEquals(1, paths.size());
    }
    @Test void netSuiteDetailIdentityMustMatchListId() {
        respond("/services/rest/record/v1/customer", path -> path.endsWith("/1") ? "{\"id\":\"wrong\"}"
                : "{\"items\":[{\"id\":\"1\"}],\"count\":1,\"offset\":0,\"hasMore\":false}");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("NETSUITE", "customer", Map.of())));
        assertEquals(2, paths.size());
    }
    @Test void odooFixedPostProjectionDatabaseAndOffsets() throws Exception {
        respond("/json/2/res.partner/search_read", path -> paths.size() == 1
                ? "[{\"id\":1,\"name\":\"Alpha\",\"apiKey\":\"hidden\"}]" : "[]");
        var docs = new CamelErpDocumentLoader().load(source("ODOO", "res.partner", Map.of("select", "name,email", "tenant", "finance", "pageSize", 1)));
        assertEquals(1, docs.size()); assertEquals(List.of("POST", "POST"), methods); assertEquals(List.of("finance", "finance"), databases);
        JsonNode first = mapper.readTree(bodies.get(0));
        assertEquals(List.of("id", "name", "email"), mapper.convertValue(first.path("fields"), List.class));
        assertTrue(first.path("domain").isArray()); assertEquals(0, first.path("domain").size());
        assertEquals(1, first.path("limit").asInt()); assertEquals(0, first.path("offset").asInt()); assertEquals("id asc", first.path("order").asText());
        assertEquals(1, mapper.readTree(bodies.get(1)).path("offset").asInt()); assertFalse(docs.toString().contains("hidden"));
    }
    @Test void odooPageBudgetFailsInsteadOfPartialSuccess() {
        respond("/json/2/res.partner/search_read", path -> "[{\"id\":1}]");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ODOO", "res.partner", Map.of("select", "name", "pageSize", 1, "maxPages", 1))));
        assertEquals(1, paths.size());
    }
    @Test void salesforceGeneratedQueryThenQueryMoreUsesId() throws Exception {
        respond("/services/data/v60.0/query", path -> paths.size() == 1
                ? "{\"records\":[{\"Id\":\"001\",\"Name\":\"Alpha\",\"attributes\":{\"url\":\"irrelevant\"}}],\"done\":false,\"nextRecordsUrl\":\"/services/data/v60.0/query/locator-1\"}"
                : "{\"records\":[{\"Id\":\"002\",\"Name\":\"Beta\"}],\"done\":true}");
        var docs = new CamelErpDocumentLoader().load(source("SALESFORCE", "Account", Map.of("select", "Name", "maxRecords", 10)));
        assertEquals(2, docs.size()); assertEquals(List.of("GET", "GET"), methods);
        assertEquals("/services/data/v60.0/query?q=SELECT Id,Name FROM Account ORDER BY Id LIMIT 10", URLDecoder.decode(paths.get(0), StandardCharsets.UTF_8));
        assertEquals("/services/data/v60.0/query/locator-1", paths.get(1)); assertFalse(docs.toString().contains("irrelevant"));
    }
    @Test void salesforceRejectsUnsafeContinuationBeforeSendingCredential() {
        respond("/services/data/v60.0/query", path -> "{\"records\":[{\"Id\":\"001\"}],\"done\":false,\"nextRecordsUrl\":\"https://foreign.invalid/services/data/v60.0/query/abc\"}");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("SALESFORCE", "Account", Map.of("select", "Name"))));
        assertEquals(1, paths.size());
    }
    @Test void salesforceMalformedDoneAndMissingContinuationFail() {
        respond("/services/data/v60.0/query", path -> paths.size() == 1 ? "{\"records\":[],\"done\":\"false\"}" : "{\"records\":[],\"done\":false}");
        for (int n = 0; n < 2; n++) assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("SALESFORCE", "Account", Map.of("select", "Name"))));
    }
    @Test void salesforceMalformedEnvelopeCannotHideBehindRecordCap() {
        respond("/services/data/v60.0/query", path -> paths.size() == 1
                ? "{\"records\":[{\"Id\":\"001\"}],\"done\":\"false\"}"
                : "{\"records\":[{\"Id\":\"001\"}],\"done\":false,\"nextRecordsUrl\":\"https://foreign.invalid/services/data/v60.0/query/id\"}");
        for (int n = 0; n < 2; n++) assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(
                source("SALESFORCE", "Account", Map.of("select", "Name", "maxRecords", 1))));
        assertEquals(2, paths.size());
    }
    @Test void vendorRecordIdentityMustBeANonblankScalar() {
        respond("/json/2/res.partner/search_read", path -> paths.size() == 1 ? "[{\"id\":{\"nested\":1}}]" : "[{\"id\":\" \"}]");
        for (int n = 0; n < 2; n++) assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(
                source("ODOO", "res.partner", Map.of("select", "name"))));
    }
    @Test void profilesRejectWrongRootsBasicCredentialsAndArbitraryOperations() {
        for (String type : List.of("DYNAMICS365", "NETSUITE", "ODOO", "SALESFORCE")) {
            var extra = new LinkedHashMap<String,Object>(Map.of("select", "Name", "accessToken", "", "username", "reader", "password", "secret"));
            assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source(type, "Account", extra)));
            var source = source(type, "Account", Map.of("select", "Name")); source.setPathOrUrl(origin + "/wrong/");
            assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source));
        }
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("ODOO", "res.partner/write", Map.of("select", "name"))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("ODOO", "res.partner", Map.of("select", "name", "method", "write"))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("ODOO", "res.partner", Map.of("select", "name", "tenant", "db\r\ninjected"))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("SALESFORCE", "Account", Map.of("select", "Id FROM User"))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("NETSUITE", "customer", Map.of("pageSize", 1001))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("SALESFORCE", "Account", Map.of("select", "Name", "filter", "anything"))));
        assertEquals(0, paths.size());
    }
    @Test void salesforceContinuationOnlyAllowsOpaqueLocatorAtSameRoot() {
        var config = ErpSourceConfiguration.from(source("SALESFORCE", "Account", Map.of("select", "Name")));
        for (String next : List.of("/services/data/v61.0/query/id", "/services/data/v60.0/sobjects/Account", "/services/data/v60.0/query/id?q=other", "/services/data/v60.0/query/%2fsecret", "/services/data/v60.0/query/id#fragment", "/services/data/v60.0/query/../query/id"))
            assertThrows(IllegalArgumentException.class, () -> config.continuation(config.firstPage(), next), next);
    }
}
