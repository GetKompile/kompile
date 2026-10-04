package ai.kompile.source.erp;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class AcumaticaProfileTest {
    private static final String ROOT = "/entity/Default/24.200.001/";
    private HttpServer server;
    private String origin;
    private final List<URI> requests = new ArrayList<>();
    private final List<String> methods = new ArrayList<>(), auth = new ArrayList<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private void respond(Function<Integer, String> response) {
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI());
            methods.add(exchange.getRequestMethod());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.apply(requests.size()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    private DocumentSourceDescriptor source(Map<String, Object> extra) {
        var properties = new LinkedHashMap<String, Object>(Map.of("entitySet", "Customer",
                "keyFields", "CustomerID", "accessToken", "private-token"));
        properties.putAll(extra);
        return DocumentSourceDescriptor.builder().type(DocumentSourceDescriptor.SourceType.ACUMATICA)
                .pathOrUrl(origin + ROOT).metadata(properties).build();
    }
    private static String row(int id) { return "{\"CustomerID\":{\"value\":\"C" + id + "\"}}"; }
    private static Map<String, String> query(URI uri) {
        var values = new LinkedHashMap<String, String>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            assertNull(values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        }
        return values;
    }
    @Test void computedPagingProjectsKeysAndShrinksFinalRequest() throws Exception {
        respond(n -> n == 1 ? "[" + row(1) + "," + row(2) + "]" : "[" + row(3) + "]");
        var docs = new CamelErpDocumentLoader().load(source(Map.of("pageSize", 2, "maxRecords", 3,
                "select", "CustomerID,CustomerName,CustomerName")));
        assertEquals(3, docs.size()); assertEquals(2, requests.size());
        for (URI uri : requests) assertEquals(ROOT + "Customer", uri.getPath());
        assertEquals(Map.of("$top", "2", "$skip", "0", "$select", "CustomerID,CustomerName"), query(requests.get(0)));
        assertEquals(Map.of("$top", "1", "$skip", "2", "$select", "CustomerID,CustomerName"), query(requests.get(1)));
        assertEquals(List.of("GET", "GET"), methods);
        assertEquals(List.of("Bearer private-token", "Bearer private-token"), auth);
        assertEquals(origin + ROOT + "Customer", docs.get(0).getMetadata().get("source_url"));
        assertFalse(docs.toString().contains("private-token"));
    }
    @Test void clampedShortPagesContinueUntilEmptyWithoutFollowingLinks() throws Exception {
        respond(n -> n < 3 ? "[{\"CustomerID\":{\"value\":\"C" + n + "\"},\"_links\":{\"next\":\"https://attacker.example/\"}}]" : "[]");
        assertEquals(2, new CamelErpDocumentLoader().load(source(Map.of("pageSize", 3))).size());
        assertEquals(3, requests.size());
        assertEquals("1", query(requests.get(1)).get("$skip"));
        assertEquals("2", query(requests.get(2)).get("$skip"));
        for (URI uri : requests) assertEquals(ROOT + "Customer", uri.getPath());
        assertFalse(query(requests.get(0)).containsKey("$select"));
    }
    @Test void emptyCollectionIsValid() throws Exception {
        respond(n -> "[]");
        assertTrue(new CamelErpDocumentLoader().load(source(Map.of())).isEmpty());
        assertEquals(1, requests.size());
    }
    @ParameterizedTest @ValueSource(strings = {"wrapper", "wrong-row", "oversized", "missing-key", "flat-key", "null-key", "object-key", "blank-key", "entity-error", "field-error", "object-error"})
    void invalidResponsesFailEvenAtIntentionalCap(String kind) {
        respond(n -> switch (kind) {
            case "wrapper" -> "{\"value\":[" + row(1) + "]}";
            case "wrong-row" -> "[1]";
            case "oversized" -> "[" + row(1) + "," + row(2) + "]";
            case "missing-key" -> "[{\"id\":\"session-uuid\"}]";
            case "flat-key" -> "[{\"CustomerID\":\"C1\"}]";
            case "null-key" -> "[{\"CustomerID\":{\"value\":null}}]";
            case "object-key" -> "[{\"CustomerID\":{\"value\":{}}}]";
            case "blank-key" -> "[{\"CustomerID\":{\"value\":\" \"}}]";
            case "entity-error" -> "[{\"CustomerID\":{\"value\":\"C1\"},\"error\":\"private-token\"}]";
            case "field-error" -> "[{\"CustomerID\":{\"value\":\"C1\"},\"CustomerName\":{\"value\":\"A\",\"error\":\"private-token\"}}]";
            default -> "[{\"CustomerID\":{\"value\":\"C1\"},\"error\":{}}]";
        });
        var error = assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("maxRecords", 1))));
        assertFalse(error.toString().contains("private-token")); assertEquals(1, requests.size());
    }
    @Test void duplicateBusinessKeysFailWithoutPartialResults() {
        respond(n -> "[" + row(1) + "]");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("pageSize", 1))));
        assertEquals(2, requests.size());
    }
    @Test void pageBudgetFailsButRecordCapIsIntentional() throws Exception {
        respond(n -> "[" + row(n) + "]");
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("pageSize", 1, "maxPages", 1))));
        requests.clear();
        assertEquals(1, new CamelErpDocumentLoader().load(source(Map.of("maxRecords", 1))).size());
        assertEquals(1, requests.size());
    }
    @Test void businessIdentityIgnoresSessionFieldsAndBindsScope() throws Exception {
        respond(n -> "[{\"CustomerID\":{\"value\":\"C1\"},\"Branch\":{\"value\":2},\"id\":\"session-" + n
                + "\",\"rowNumber\":" + n + ",\"error\":\"\",\"CustomerName\":{\"value\":\"Alice\",\"error\":null},"
                + "\"accessToken\":{\"value\":\"leaked\"},\"files\":[\"session-attachment\"],\"_links\":{\"self\":\"session-link\"}}]");
        var loader = new CamelErpDocumentLoader();
        var a = loader.load(source(Map.of("keyFields", "CustomerID,Branch", "maxRecords", 1))).get(0);
        var b = loader.load(source(Map.of("keyFields", "CustomerID,Branch", "maxRecords", 1))).get(0);
        var c = loader.load(source(Map.of("keyFields", "CustomerID,Branch", "maxRecords", 1, "tenant", "other-company"))).get(0);
        assertEquals(a.getMetadata().get("source_path"), b.getMetadata().get("source_path"));
        assertNotEquals(a.getMetadata().get("source_path"), c.getMetadata().get("source_path"));
        assertTrue(a.getText().contains("Alice"));
        assertFalse(a.getText().contains("session-")); assertFalse(a.getText().contains("leaked"));
    }
    @Test void unsafeOptionsAndAuthenticationFailBeforeRequests() {
        for (var extra : List.<Map<String, Object>>of(Map.of("select", "*"), Map.of("select", "Name/child"),
                Map.of("keyFields", ""), Map.of("keyFields", "CustomerID/child"), Map.of("filter", "CustomerID eq 'C1'"),
                Map.of("orderBy", "CustomerID"), Map.of("entitySet", "../auth/login"), Map.of("expand", "Contacts"),
                Map.of("custom", "Fields"), Map.of("method", "Release"), Map.of("accessToken", ""),
                Map.of("username", "reader", "password", "password", "accessToken", "")))
            assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source(extra)));
        var wrongRoot = source(Map.of()); wrongRoot.setPathOrUrl(origin + "/entity/auth/");
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(wrongRoot));
        assertTrue(requests.isEmpty());
    }
}
