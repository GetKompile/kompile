package ai.kompile.source.erp;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class OracleErpProfilesTest {
    HttpServer server;
    String origin;
    List<String> paths = new ArrayList<>(), methods = new ArrayList<>(), auth = new ArrayList<>(), bodies = new ArrayList<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    void respond(Function<String, String> response) {
        server.createContext("/", e -> {
            paths.add(URLDecoder.decode(e.getRequestURI().toString(), StandardCharsets.UTF_8));
            methods.add(e.getRequestMethod()); auth.add(e.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = response.apply(paths.get(paths.size() - 1)).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, data.length);
            try (var out = e.getResponseBody()) { out.write(data); }
        });
    }
    static String root(String type) {
        return switch (type) {
            case "ORACLE_FUSION" -> "/fscmRestApi/resources/11.13.18.05/";
            case "ORACLE_EBS" -> "/webservices/rest/autoinvoice/";
            case "JD_EDWARDS" -> "/jderest/v2/dataservice/table/";
            default -> throw new IllegalArgumentException();
        };
    }
    DocumentSourceDescriptor source(String type, Map<String, Object> extra) {
        var p = new LinkedHashMap<String, Object>(Map.of("entitySet", type.equals("JD_EDWARDS") ? "F0101" : "INVOICES", "keyFields", "ID"));
        if (type.equals("ORACLE_EBS")) { p.put("username", "reader"); p.put("password", "private-secret"); }
        else p.put("accessToken", "private-secret");
        p.putAll(extra);
        return DocumentSourceDescriptor.builder().type(DocumentSourceDescriptor.SourceType.valueOf(type))
                .pathOrUrl(origin + root(type)).metadata(p).build();
    }
    String fusion(String rows, int offset, int limit, boolean more) {
        return "{\"items\":" + rows + ",\"offset\":" + offset + ",\"limit\":" + limit + ",\"hasMore\":" + more + "}";
    }
    String ebs(String output, int offset, int limit, int count, int total) {
        return "{\"OutputParameters\":{\"Summary\":{\"Offset\":\"" + offset + "\",\"Limit\":\"" + limit
                + "\",\"GetCount\":\"" + count + "\",\"TotalCount\":\"" + total + "\"},\"Result\":{\"Output\":" + output + "}}}";
    }
    String jde(String rows, int count, boolean more) {
        return "{\"fs_DATABROWSE_F0101\":{\"data\":{\"gridData\":{\"rowset\":" + rows
                + ",\"summary\":{\"records\":" + count + ",\"moreRecords\":" + more + "}}},\"errors\":[],\"warnings\":[]},\"sysErrors\":[]}";
    }
    @Test void fusionComputedOffsetsProjectionCompositeKeysAndClampedPage() throws Exception {
        respond(path -> fusion("[{\"ID\":1,\"Company\":\"" + (path.contains("offset=0") ? "A" : "B")
                + "\",\"Name\":\"sample\",\"links\":[{\"href\":\"https://evil.invalid\"}],\"password\":\"hidden\"}]",
                path.contains("offset=0") ? 0 : 1, 1, path.contains("offset=0")));
        var docs = new CamelErpDocumentLoader().load(source("ORACLE_FUSION", Map.of("keyFields", "ID,Company", "select", "Name", "pageSize", 2)));
        assertEquals(2, docs.size()); assertNotEquals(docs.get(0).getMetadata().get("source_path"), docs.get(1).getMetadata().get("source_path"));
        assertTrue(paths.get(0).contains("fields=ID,Company,Name")); assertTrue(paths.get(0).contains("onlyData=true&orderBy=ID,Company"));
        assertTrue(paths.get(1).contains("offset=1")); assertEquals(List.of("GET", "GET"), methods);
        assertEquals(List.of("Bearer private-secret", "Bearer private-secret"), auth);
        assertFalse(docs.toString().contains("evil.invalid")); assertFalse(docs.toString().contains("hidden"));
    }
    @Test void fusionRecordCapDoesNotFollowLinks() throws Exception {
        respond(path -> fusion("[{\"ID\":1}]", 0, 1, true));
        assertEquals(1, new CamelErpDocumentLoader().load(source("ORACLE_FUSION", Map.of("maxRecords", 1))).size());
        assertEquals(1, paths.size());
    }
    @ParameterizedTest @ValueSource(strings = {"offset", "limit", "hasMore", "count", "empty"})
    void fusionRejectsMalformedPaginationEvenAtCap(String field) {
        respond(path -> switch (field) {
            case "offset" -> fusion("[{\"ID\":1}]", 1, 1, false);
            case "limit" -> fusion("[{\"ID\":1}]", 0, 2, false);
            case "hasMore" -> "{\"items\":[{\"ID\":1}],\"offset\":0,\"limit\":1}";
            case "count" -> "{\"items\":[{\"ID\":1}],\"offset\":0,\"limit\":1,\"hasMore\":false,\"count\":0}";
            default -> fusion("[]", 0, 1, true);
        });
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ORACLE_FUSION", Map.of("maxRecords", 1))));
        assertEquals(1, paths.size());
    }
    @Test void ebsInterfaceSingletonAndRepeatedOutputWithComputedPaging() throws Exception {
        respond(path -> path.contains("offset=0") ? ebs("{\"INVOICES_REC\":{\"ID\":1}}", 0, 1, 1, 3)
                : ebs("[{\"INVOICES_REC\":{\"ID\":2}},{\"INVOICES_REC\":{\"ID\":3}}]", 1, 2, 2, 3));
        var docs = new CamelErpDocumentLoader().load(source("ORACLE_EBS", Map.of("select", "Name", "pageSize", 2)));
        assertEquals(3, docs.size()); assertTrue(paths.get(0).contains("INVOICES/?limit=2&offset=0&select=ID,Name"));
        assertTrue(paths.get(1).contains("offset=1")); assertEquals(List.of("GET", "GET"), methods);
        assertEquals(List.of("Basic " + Base64.getEncoder().encodeToString("reader:private-secret".getBytes(StandardCharsets.UTF_8)),
                "Basic " + Base64.getEncoder().encodeToString("reader:private-secret".getBytes(StandardCharsets.UTF_8))), auth);
        assertEquals(List.of("", ""), bodies);
    }
    @Test void ebsRecordArrayAndEmptyResult() throws Exception {
        respond(path -> paths.size() == 1 ? ebs("{\"INVOICES_REC\":[{\"ID\":1},{\"ID\":2}]}", 0, 100, 2, 2)
                : ebs("null", 0, 100, 0, 0));
        assertEquals(2, new CamelErpDocumentLoader().load(source("ORACLE_EBS", Map.of())).size());
        assertTrue(new CamelErpDocumentLoader().load(source("ORACLE_EBS", Map.of())).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"wrong-record", "count", "offset", "limit", "total", "empty"})
    void ebsRejectsWrongEnvelopeAndBrokenSummary(String field) {
        respond(path -> switch (field) {
            case "wrong-record" -> ebs("{\"OTHER_REC\":{\"ID\":1}}", 0, 1, 1, 1);
            case "count" -> ebs("{\"INVOICES_REC\":{\"ID\":1}}", 0, 1, 0, 1);
            case "offset" -> ebs("{\"INVOICES_REC\":{\"ID\":1}}", 2, 1, 1, 3);
            case "limit" -> ebs("{\"INVOICES_REC\":{\"ID\":1}}", 0, 2, 1, 1);
            case "total" -> ebs("{\"INVOICES_REC\":{\"ID\":1}}", 0, 1, 1, 0);
            default -> ebs("null", 0, 1, 0, 3);
        });
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ORACLE_EBS", Map.of("maxRecords", 1))));
        assertEquals(1, paths.size());
    }
    @Test void jdeOneBoundedTableGetUsesStableRowKeysNotSessionIds() throws Exception {
        respond(path -> jde("[{\"F0101_AN8\":42,\"F0101_ALPH\":\"Acme\"}]", 1, true));
        var source = source("JD_EDWARDS", Map.of("keyFields", "F0101_AN8", "maxRecords", 1));
        var docs = new CamelErpDocumentLoader().load(source);
        assertEquals(1, docs.size()); assertTrue(docs.get(0).getText().contains("Acme"));
        assertEquals(root("JD_EDWARDS") + "F0101?$limit=1", paths.get(0));
        assertEquals(List.of("GET"), methods); assertEquals(List.of(""), bodies);
        assertEquals(docs.get(0).getMetadata().get("source_path"), new CamelErpDocumentLoader().load(source).get(0).getMetadata().get("source_path"));
    }
    @ParameterizedTest @ValueSource(strings = {"errors", "sysErrors", "short", "count", "moreRecords", "oversize"})
    void jdeRejectsErrorsAndIncompleteSamples(String field) {
        respond(path -> switch (field) {
            case "errors" -> jde("[{\"ID\":1}]", 1, false).replace("\"errors\":[]", "\"errors\":[{\"message\":\"private-secret\"}]");
            case "sysErrors" -> jde("[{\"ID\":1}]", 1, false).replace("\"sysErrors\":[]", "\"sysErrors\":[\"private-secret\"]");
            case "short" -> jde("[]", 0, true);
            case "count" -> jde("[{\"ID\":1}]", 0, false);
            case "moreRecords" -> jde("[{\"ID\":1}]", 1, false).replace("\"moreRecords\":false", "\"moreRecords\":\"false\"");
            default -> jde("[{\"ID\":1},{\"ID\":2}]", 2, false);
        });
        var e = assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("JD_EDWARDS", Map.of("maxRecords", 1))));
        assertFalse(e.toString().contains("private-secret")); assertEquals(1, paths.size());
    }
    @ParameterizedTest @ValueSource(strings = {"ORACLE_FUSION", "ORACLE_EBS", "JD_EDWARDS"})
    void oracleProfilesRejectUnsafeScopeAndMissingAuthentication(String type) {
        for (var extra : List.<Map<String, Object>>of(Map.of("keyFields", ""), Map.of("keyFields", "ID/evil"), Map.of("entitySet", "../write"),
                Map.of("filter", "anything"), Map.of("select", "ID from Other"), Map.of("method", "POST")))
            assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source(type, extra)));
        var wrong = source(type, Map.of()); wrong.setPathOrUrl(origin + "/wrong/");
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(wrong));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source(type, Map.of("username", "", "password", "", "accessToken", ""))));
        assertEquals(0, paths.size());
    }
    @ParameterizedTest @ValueSource(strings = {"ORACLE_FUSION", "ORACLE_EBS", "JD_EDWARDS"})
    void oversizedPaginationNumbersCannotWrapToValidCounts(String type) {
        respond(path -> switch (type) {
            case "ORACLE_FUSION" -> fusion("[{\"ID\":1}]", 0, 1, false).replace("\"offset\":0", "\"offset\":18446744073709551616");
            case "ORACLE_EBS" -> ebs("{\"INVOICES_REC\":{\"ID\":1}}", 0, 1, 1, 1).replace("\"TotalCount\":\"1\"", "\"TotalCount\":\"18446744073709551616\"");
            default -> jde("[{\"ID\":1}]", 1, false).replace("\"records\":1", "\"records\":18446744073709551617");
        });
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source(type, Map.of("maxRecords", 1))));
    }
    @Test void ebsBudgetFailsAndCapIsIntentional() throws Exception {
        respond(path -> ebs("{\"INVOICES_REC\":{\"ID\":1}}", 0, 1, 1, 2));
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ORACLE_EBS", Map.of("pageSize", 1, "maxPages", 1))));
        paths.clear();
        assertEquals(1, new CamelErpDocumentLoader().load(source("ORACLE_EBS", Map.of("maxRecords", 1))).size());
        assertEquals(1, paths.size());
    }
    @Test void fusionAndJdeBasicAuthUsesOnlyHeaders() throws Exception {
        respond(path -> path.contains("fscmRestApi") ? fusion("[{\"ID\":1}]", 0, 100, false) : jde("[{\"ID\":1}]", 1, false));
        for (String type : List.of("ORACLE_FUSION", "JD_EDWARDS"))
            assertEquals(1, new CamelErpDocumentLoader().load(source(type, Map.of("accessToken", "", "username", "reader", "password", "private-secret"))).size());
        assertTrue(auth.stream().allMatch(value -> value.startsWith("Basic ")));
        assertTrue(paths.stream().noneMatch(path -> path.contains("reader") || path.contains("private-secret")));
        assertEquals(List.of("GET", "GET"), methods); assertEquals(List.of("", ""), bodies);
    }
    @Test void vendorSpecificAuthAndOptions() {
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("ORACLE_EBS", Map.of("username", "", "password", "", "accessToken", "token"))));
        for (String option : List.of("select", "pageSize", "maxPages", "orderBy"))
            assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("JD_EDWARDS", Map.of(option, "1"))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("ORACLE_EBS", Map.of("orderBy", "ID"))));
        assertThrows(IllegalArgumentException.class, () -> ErpSourceConfiguration.from(source("ORACLE_FUSION", Map.of("orderBy", "ID;action"))));
        for (String type : List.of("ORACLE_FUSION", "JD_EDWARDS"))
            assertNotNull(ErpSourceConfiguration.from(source(type, Map.of("accessToken", "", "username", "reader", "password", "secret"))));
    }
    @Test void fusionPageBudgetAndDuplicateKeysFailWithoutPartialSuccess() {
        respond(path -> fusion("[{\"ID\":1}]", paths.size() - 1, 1, true));
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ORACLE_FUSION", Map.of("pageSize", 1, "maxPages", 1))));
        paths.clear();
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ORACLE_FUSION", Map.of("pageSize", 1))));
        assertEquals(2, paths.size());
    }
    @ParameterizedTest @ValueSource(strings = {"\" \"", "null", "{}"})
    void oracleKeysMustBeNonblankNonNullScalars(String key) {
        respond(path -> fusion("[{\"ID\":" + key + "}]", 0, 100, false));
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ORACLE_FUSION", Map.of())));
    }
}
