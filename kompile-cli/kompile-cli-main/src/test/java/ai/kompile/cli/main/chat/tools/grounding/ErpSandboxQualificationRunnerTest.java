package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.source.ErpConnectionCredentials;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ErpSandboxQualificationRunnerTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean badRequest = new AtomicBoolean();
    private final AtomicBoolean fail = new AtomicBoolean();
    private HttpServer server;
    private String root;
    private String admission;
    private static final String SECRET = "qualification-fixture-secret";
    private static final String MARKER = "qualification-cobalt-puffin";

    @BeforeEach
    void setUp() throws Exception {
        admission = System.getProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/entity/Default/24.200.001/Customer", exchange -> {
            requests.incrementAndGet();
            if (!exchange.getRequestMethod().equals("GET")
                    || !java.util.Objects.equals("Bearer " + SECRET, exchange.getRequestHeaders().getFirst("Authorization"))) badRequest.set(true);
            String query = exchange.getRequestURI().getQuery();
            String body;
            if (fail.get()) body = "{\"error\":\"" + SECRET + "\"}";
            else if (query.contains("$skip=0")) body = row("QA1");
            else if (query.contains("$skip=1")) body = row("QA2");
            else body = "[]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail.get() ? 503 : 200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort() + "/entity/Default/24.200.001/";
        ErpConnectionCredentials.save(CredentialStore.create(), "ACUMATICA", "sandbox-reader", root, null, null, SECRET, 0);
    }
    private String row(String key) {
        return "[{\"CustomerID\":{\"value\":\"" + key + "\"},\"Description\":{\"value\":\"" + MARKER + "-" + key
                + "\"},\"Echo\":{\"value\":\"" + SECRET + "\"}}]";
    }
    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (admission == null) System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        else System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, admission);
    }
    private ObjectNode config() {
        ObjectNode config = mapper.createObjectNode().put("sourceType", "ACUMATICA").put("productVersion", "24.200.001");
        config.putObject("properties").put("serviceRoot", root).put("connectionName", "sandbox-reader")
                .put("entitySet", "Customer").put("keyFields", "CustomerID").put("select", "Description,Echo")
                .put("maxRecords", 3).put("pageSize", 1).put("maxPages", 4)
                .put("timeoutMillis", 1000).put("maxResponseBytes", 4096);
        for (String key : new String[]{"QA1", "QA2"}) {
            ObjectNode record = config.withArray("expectedRecords").addObject().put("searchText", MARKER + "-" + key);
            record.putObject("keys").put("CustomerID", key);
        }
        return config;
    }
    private ObjectNode run(ObjectNode config) throws Exception {
        return ErpSandboxQualificationRunner.run(config, dir.resolve("workspace"), dir.resolve("receipt.json"), false);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{\"sourceType\":\"ACUMATICA\",\"sourceType\":\"ODATA\"}", "{} {}"})
    void rejectsAmbiguousJsonBeforeNetwork(String json) throws Exception {
        var parsed = ErpSandboxQualificationRunner.parse(json.getBytes(StandardCharsets.UTF_8));
        var receipt = ErpSandboxQualificationRunner.run(parsed, dir.resolve("workspace"), dir.resolve("receipt.json"), false);
        assertEquals("configuration", receipt.path("failedStage").asText());
        assertEquals(0, requests.get());
    }
    @Test
    void receiptNeverCopiesVersionShapedSecretEvenOnFailure() throws Exception {
        var config = config().put("productVersion", SECRET);
        var receipt = run(config);
        assertEquals("FAIL", receipt.path("status").asText());
        assertFalse(Files.readString(dir.resolve("receipt.json")).contains(SECRET));
        assertEquals(0, requests.get());
    }
    @Test
    void searchMarkerMustBelongToMatchingEvidenceChunk() {
        var hit = mapper.createObjectNode().put("documentId", "first").put("chunkId", "one");
        String output = "### 1. source\nDocument: first | Chunk: one\nunrelated\n\n### 2. source\nDocument: second | Chunk: two\nmarker";
        assertFalse(ErpSandboxQualificationRunner.matchingChunkContains(output, hit, "marker"));
        assertTrue(ErpSandboxQualificationRunner.matchingChunkContains(output.replace("unrelated", "marker"), hit, "marker"));
    }
    @Test
    void repeatMustPreserveMaterializedDocumentIds() {
        var first = new ErpSandboxQualificationRunner.Snapshot(Map.of("erp:key", "original"), Map.of());
        var changed = new ErpSandboxQualificationRunner.Snapshot(Map.of("erp:key", "changed"), Map.of());
        assertFalse(ErpSandboxQualificationRunner.stableIdentities(first, changed));
        assertTrue(ErpSandboxQualificationRunner.stableIdentities(first, first));
    }
    @Test
    void realCamelCrawlSearchRepeatCapAndRedactionProduceSanitizedReceipt() throws Exception {
        var receipt = run(config());
        assertEquals("PASS_BOUNDED_READ", receipt.path("status").asText(), receipt.toString());
        assertEquals(2, receipt.path("recordCount").asInt());
        assertTrue(receipt.path("beyondConfiguredPageSize").asBoolean());
        assertFalse(receipt.path("paginationRequestsObserved").asBoolean());
        assertEquals(7, requests.get()); // baseline 3, repeat 3, capped 1; no binding-negative HTTP request
        assertFalse(badRequest.get());
        String report = Files.readString(dir.resolve("receipt.json"));
        for (String privateValue : new String[]{SECRET, root, MARKER, "QA1", "sandbox-reader"})
            assertFalse(report.contains(privateValue));
    }
    @ParameterizedTest
    @ValueSource(strings = {"secret", "unknown", "nested", "nestedOptional", "missingBound", "zero", "fraction", "keys", "duplicate", "notEnoughPages", "recordBudget", "pageBudget", "byteBudget"})
    void rejectsUnsafeOrIncompleteConfigurationBeforeNetwork(String mutation) throws Exception {
        ObjectNode config = config();
        ObjectNode p = (ObjectNode) config.path("properties");
        switch (mutation) {
            case "secret" -> p.put("accessToken", SECRET);
            case "unknown" -> config.put("unexpected", "value");
            case "nested" -> p.putObject("serviceRoot").put("accessToken", SECRET);
            case "nestedOptional" -> p.putObject("filter").put("value", "nested");
            case "recordBudget" -> p.put("maxRecords", 101);
            case "pageBudget" -> p.put("maxPages", 21);
            case "byteBudget" -> p.put("maxResponseBytes", 1_048_577);
            case "missingBound" -> p.remove("timeoutMillis");
            case "zero" -> p.put("maxRecords", 0);
            case "fraction" -> p.put("pageSize", 1.5);
            case "keys" -> p.put("keyFields", "CustomerID,CustomerID");
            case "duplicate" -> config.withArray("expectedRecords").set(1, config.path("expectedRecords").get(0).deepCopy());
            case "notEnoughPages" -> p.put("pageSize", 2);
        }
        var receipt = run(config);
        assertEquals("FAIL", receipt.path("status").asText());
        assertEquals("configuration", receipt.path("failedStage").asText());
        assertEquals(0, requests.get());
        assertFalse(receipt.toString().contains(SECRET));
    }
    @Test
    void liveModeRejectsLoopbackHttpBeforeNetwork() throws Exception {
        var receipt = ErpSandboxQualificationRunner.run(config(), dir.resolve("workspace"), dir.resolve("receipt.json"), true);
        assertEquals("configuration", receipt.path("failedStage").asText());
        assertEquals(0, requests.get());
    }
    @Test
    void mismatchedNamedRootFailsWithoutNetworkOrSensitiveDiagnostics() throws Exception {
        var config = config();
        ((ObjectNode) config.path("properties")).put("serviceRoot", root.replace("Default", "Other"));
        var receipt = run(config);
        assertEquals("credentials", receipt.path("failedStage").asText());
        assertEquals(0, requests.get());
        assertFalse(receipt.toString().contains(root));
    }
    @ParameterizedTest
    @ValueSource(strings = {"key", "search"})
    void missingExpectedKeyOrSearchMarkerCannotPass(String missing) throws Exception {
        var config = config();
        ObjectNode expected = (ObjectNode) config.path("expectedRecords").get(1);
        if (missing.equals("key")) ((ObjectNode) expected.path("keys")).put("CustomerID", "NONEXISTENT");
        else expected.put("searchText", "NONEXISTENT-MARKER");
        var receipt = run(config);
        assertEquals("expectedKeysAndSearch", receipt.path("failedStage").asText());
        assertEquals("FAIL", receipt.path("status").asText());
    }
    @Test
    void failedRereadPreservesPriorMarkdownAndDoesNotEchoResponseSecret() throws Exception {
        assertEquals("PASS_BOUNDED_READ", run(config()).path("status").asText());
        Map<Path, String> before = markdown();
        assertFalse(before.isEmpty());
        fail.set(true);
        var receipt = run(config());
        assertEquals("baselineCrawl", receipt.path("failedStage").asText());
        assertEquals(before, markdown());
        assertFalse(Files.readString(dir.resolve("receipt.json")).contains(SECRET));
    }
    private Map<Path, String> markdown() throws Exception {
        Map<Path, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(dir.resolve("workspace/baseline"))) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".md")).toList())
                result.put(path, Files.readString(path));
        }
        return result;
    }
}
