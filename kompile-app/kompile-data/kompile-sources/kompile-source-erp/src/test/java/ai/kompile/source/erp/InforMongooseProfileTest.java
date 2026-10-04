package ai.kompile.source.erp;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class InforMongooseProfileTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "/TENANT/CSI/IDORequestService/ido/";
    private HttpServer server;
    private String origin;
    private final List<URI> requests = new ArrayList<>();
    private final List<String> methods = new ArrayList<>(), auth = new ArrayList<>(), configurations = new ArrayList<>();
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
            configurations.add(exchange.getRequestHeaders().getFirst("X-Infor-MongooseConfig"));
            byte[] bytes = response.apply(requests.size()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    private DocumentSourceDescriptor source(Map<String, Object> extra) {
        var properties = new LinkedHashMap<String, Object>(Map.of("entitySet", "UserNames", "select", "Username",
                "keyFields", "UserId", "tenant", "site-config", "accessToken", "private-token"));
        properties.putAll(extra);
        return DocumentSourceDescriptor.builder().type(DocumentSourceDescriptor.SourceType.INFOR_MONGOOSE)
                .pathOrUrl(origin + ROOT).metadata(properties).build();
    }
    private static String page(String rows, boolean more, String bookmark) {
        var node = MAPPER.createObjectNode();
        try { node.set("Items", MAPPER.readTree(rows)); } catch (Exception e) { throw new IllegalArgumentException(e); }
        node.put("Success", true); node.put("MoreRowsExist", more); node.put("Bookmark", bookmark); node.putNull("Message");
        return node.toString();
    }
    private static Map<String, String> query(URI uri) {
        var values = new LinkedHashMap<String, String>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            assertNull(values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        }
        return values;
    }
    @Test void bookmarkPagingProjectsKeysBindsConfigurationAndShrinksFinalRequest() throws Exception {
        String bookmark = "<B><P><p>UserId</p></P><L><v>2&clm=Write#?</v></L></B>";
        respond(n -> page(n == 1 ? "[{\"UserId\":1,\"Username\":\"Alice\"},{\"UserId\":2}]" : "[{\"UserId\":3}]", true, bookmark));
        var docs = new CamelErpDocumentLoader().load(source(Map.of("pageSize", 2, "maxRecords", 3)));
        assertEquals(3, docs.size()); assertEquals(2, requests.size());
        for (URI uri : requests) assertEquals(ROOT + "load/UserNames", uri.getPath());
        var first = query(requests.get(0)); var second = query(requests.get(1));
        assertEquals("UserId,Username", first.get("properties")); assertEquals("UserId", first.get("orderby"));
        assertEquals("true", first.get("readonly")); assertEquals("FIRST", first.get("loadtype"));
        assertEquals("2", first.get("recordcap")); assertFalse(first.containsKey("bookmark"));
        assertEquals("NEXT", second.get("loadtype")); assertEquals("1", second.get("recordcap"));
        assertEquals(bookmark, second.get("bookmark")); assertFalse(second.containsKey("clm"));
        assertEquals(List.of("GET", "GET"), methods); assertEquals(List.of("Bearer private-token", "Bearer private-token"), auth);
        assertEquals(List.of("site-config", "site-config"), configurations);
        assertEquals(origin + ROOT + "load/UserNames", docs.get(0).getMetadata().get("source_url"));
        assertFalse(docs.toString().contains("private-token")); assertFalse(docs.toString().contains(bookmark));
    }
    @Test void emptyCollectionAndServerClampedPagesAreValid() throws Exception {
        respond(n -> page(n == 1 ? "[{\"UserId\":1}]" : "[]", n == 1, "bookmark"));
        assertEquals(1, new CamelErpDocumentLoader().load(source(Map.of("pageSize", 2))).size());
        assertEquals(2, requests.size());
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "success-missing", "success-string", "more-missing", "more-string", "bookmark-missing", "bookmark-object", "bookmark-blank", "bookmark-long", "empty-more", "oversized", "wrong-items", "wrong-row", "missing-key", "object-key"})
    void invalidEnvelopesFailEvenAtIntentionalCap(String field) {
        respond(n -> {
            try {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(page("[{\"UserId\":1}]", true, "bookmark"));
                switch (field) {
                    case "failed" -> { node.put("Success", false); node.put("Message", "private-token"); }
                    case "success-missing" -> node.remove("Success");
                    case "success-string" -> node.put("Success", "true");
                    case "more-missing" -> node.remove("MoreRowsExist");
                    case "more-string" -> node.put("MoreRowsExist", "false");
                    case "bookmark-missing" -> node.remove("Bookmark");
                    case "bookmark-object" -> node.putObject("Bookmark");
                    case "bookmark-blank" -> node.put("Bookmark", " ");
                    case "bookmark-long" -> node.put("Bookmark", "x".repeat(16385));
                    case "empty-more" -> node.set("Items", MAPPER.readTree("[]"));
                    case "oversized" -> node.set("Items", MAPPER.readTree("[{\"UserId\":1},{\"UserId\":2}]"));
                    case "wrong-items" -> node.putObject("Items");
                    case "wrong-row" -> node.set("Items", MAPPER.readTree("[1]"));
                    case "missing-key" -> node.set("Items", MAPPER.readTree("[{\"Username\":\"Alice\"}]"));
                    default -> node.set("Items", MAPPER.readTree("[{\"UserId\":{}}]"));
                }
                return node.toString();
            } catch (Exception e) { throw new IllegalArgumentException(e); }
        });
        var error = assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("maxRecords", 1))));
        assertFalse(error.toString().contains("private-token")); assertEquals(1, requests.size());
    }
    @Test void repeatedBookmarksAndDuplicateKeysFailWithoutPartialResults() {
        respond(n -> page("[{\"UserId\":" + n + "}]", true, "same-bookmark"));
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("pageSize", 1))));
        assertEquals(2, requests.size());
        requests.clear(); server.removeContext("/");
        respond(n -> page("[{\"UserId\":1}]", n == 1, "next"));
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("pageSize", 1))));
        assertEquals(2, requests.size());
    }
    @Test void pageBudgetFailsButRecordCapIsIntentional() throws Exception {
        respond(n -> page("[{\"UserId\":1}]", true, "next"));
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(Map.of("pageSize", 1, "maxPages", 1))));
        requests.clear();
        assertEquals(1, new CamelErpDocumentLoader().load(source(Map.of("maxRecords", 1))).size());
        assertEquals(1, requests.size());
    }
    @Test void unsafeOptionsAndMissingConfigurationFailBeforeRequests() {
        for (var extra : List.<Map<String, Object>>of(Map.of("select", "*"), Map.of("select", "Username/child"),
                Map.of("keyFields", ""), Map.of("tenant", ""), Map.of("tenant", "config\r\nInjected: true"),
                Map.of("filter", "UserId > 0"), Map.of("orderBy", "Username"), Map.of("entitySet", "../invoke"),
                Map.of("clm", "Write"), Map.of("pqc", "Write"), Map.of("loadtype", "LAST"), Map.of("readonly", false),
                Map.of("accessToken", ""), Map.of("username", "reader", "password", "password", "accessToken", "")))
            assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source(extra)));
        var wrongRoot = source(Map.of()); wrongRoot.setPathOrUrl(origin + "/M3/");
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(wrongRoot));
        assertTrue(requests.isEmpty());
    }
    @Test void configurationAndCompositeKeysSeparateStableIdentities() throws Exception {
        respond(n -> page("[{\"UserId\":1,\"Site\":\"A\",\"accessToken\":\"leaked\"}]", false, null));
        var loader = new CamelErpDocumentLoader();
        var a = loader.load(source(Map.of("keyFields", "UserId,Site"))).get(0);
        var b = loader.load(source(Map.of("keyFields", "UserId,Site", "tenant", "other-config"))).get(0);
        assertNotEquals(a.getMetadata().get("source_path"), b.getMetadata().get("source_path"));
        assertFalse(a.getText().contains("leaked"));
    }
}
