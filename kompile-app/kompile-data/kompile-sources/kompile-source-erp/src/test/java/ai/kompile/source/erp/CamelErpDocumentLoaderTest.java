package ai.kompile.source.erp;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CamelErpDocumentLoaderTest {
    HttpServer server;
    String root;
    AtomicInteger calls;
    List<String> methods, auth;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        root = "http://127.0.0.1:" + server.getAddress().getPort() + "/service/";
        calls = new AtomicInteger(); methods = new ArrayList<>(); auth = new ArrayList<>();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    void respond(int status, String first, String second) {
        server.createContext("/service/Records", e -> {
            int n = calls.incrementAndGet();
            methods.add(e.getRequestMethod()); auth.add(e.getRequestHeaders().getFirst("Authorization"));
            assertEquals(0, e.getRequestBody().readAllBytes().length);
            byte[] bytes = (n == 1 ? first : second).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(status, bytes.length);
            e.getResponseBody().write(bytes); e.close();
        });
    }
    DocumentSourceDescriptor source(String type, Map<String,Object> extra) {
        Map<String,Object> p = new LinkedHashMap<>(extra); p.put("entitySet", "Records");
        return DocumentSourceDescriptor.builder().type(DocumentSourceDescriptor.SourceType.valueOf(type))
                .pathOrUrl(root).metadata(p).build();
    }
    @Test void odataPagesGetAuthDedupAndSecretRedaction() throws Exception {
        respond(200, "{\"value\":[{\"ID\":1,\"password\":\"hidden\",\"note\":\"private-token\"}],\"@odata.nextLink\":\"" + root + "Records?$skiptoken=abc\"}",
                "{\"value\":[{\"ID\":1,\"name\":\"updated\"},{\"ID\":2,\"name\":\"second\"}]}");
        var docs = new CamelErpDocumentLoader().load(source("ODATA", Map.of("accessToken","private-token")));
        assertEquals(2, docs.size()); assertEquals(List.of("GET", "GET"), methods);
        assertEquals(List.of("Bearer private-token", "Bearer private-token"), auth);
        assertTrue(docs.get(0).getText().contains("updated"));
        assertFalse(docs.toString().contains("private-token"));
        assertTrue(docs.get(0).getMetadata().get("source_path").toString().startsWith("erp:"));
    }
    @Test void sapV2AndPasswordWhitespacePreserved() throws Exception {
        respond(200, "{\"d\":{\"results\":[{\"__metadata\":{\"uri\":\"entity(1)\"},\"name\":\"first\",\"password\":\"hidden\"}],\"__next\":\"" + root + "Records?$skip=1\"}}",
                "{\"d\":{\"results\":[{\"__metadata\":{\"uri\":\"entity(2)\"},\"name\":\"second\"}]}}");
        var docs = new CamelErpDocumentLoader().load(source("SAP_NETWEAVER", Map.of("username","reader","password"," pass ","sapClient","100")));
        assertEquals(2, docs.size());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("reader: pass ".getBytes(StandardCharsets.UTF_8)), auth.get(0));
        assertFalse(docs.get(0).getText().contains("hidden"));
        assertFalse(docs.get(0).getText().contains("__metadata"));
    }
    @Test void recordLimitStopsBeforeNextPage() throws Exception {
        respond(200,"{\"value\":[{\"ID\":1},{\"ID\":2}],\"@odata.nextLink\":\"" + root + "Records?$skip=2\"}","{}");
        assertEquals(1,new CamelErpDocumentLoader().load(source("ODATA",Map.of("maxRecords",1))).size());
        assertEquals(1,calls.get());
    }
    @Test void pageBudgetIsFailureNotPartialSuccess() {
        respond(200,"{\"value\":[{\"ID\":1}],\"@odata.nextLink\":\"" + root + "Records?$skip=1\"}","{}");
        var e = assertThrows(Exception.class,()->new CamelErpDocumentLoader().load(source("ODATA",Map.of("maxPages",1))));
        assertTrue(e.getMessage().contains("budget")); assertEquals(1,calls.get());
    }
    @Test void foreignContinuationNeverReceivesCredentials() {
        respond(200,"{\"value\":[{\"ID\":1}],\"@odata.nextLink\":\"https://foreign.invalid/Records\"}","{}");
        assertThrows(IllegalArgumentException.class,()->new CamelErpDocumentLoader().load(source("ODATA",Map.of("accessToken","private-token"))));
        assertEquals(1,calls.get());
    }
    @Test void redirectAndHttpFailureDoNotExposeRemotePayload() {
        server.createContext("/service/Records", e -> {
            calls.incrementAndGet(); e.getResponseHeaders().set("Location", root + "elsewhere");
            byte[] bytes="private-token error".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(302,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.createContext("/service/elsewhere",e->{ fail("Redirect followed"); e.close(); });
        var e=assertThrows(Exception.class,()->new CamelErpDocumentLoader().load(source("ODATA",Map.of("accessToken","private-token"))));
        assertTrue(e.getMessage().contains("302")); assertFalse(e.toString().contains("private-token"));assertNull(e.getCause());
        assertEquals(1,calls.get());
    }
    @Test void responseCapAndMissingIdentityFail() {
        respond(200,"{\"value\":[{\"name\":\"anonymous\"}]}","{\"value\":[{\"name\":\"anonymous\"}]}");
        assertThrows(Exception.class,()->new CamelErpDocumentLoader().load(source("ODATA",Map.of("maxResponseBytes",5))));
        assertThrows(Exception.class,()->new CamelErpDocumentLoader().load(source("ODATA",Map.of())));
    }
    @Test void malformedContinuationFailsInsteadOfPublishingPartialSnapshot() {
        respond(200,"{\"value\":[{\"ID\":1}],\"@odata.nextLink\":{}}","{}");
        var failure = assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ODATA", Map.of())));
        assertTrue(failure.getMessage().contains("continuation"));
        assertEquals(1, calls.get());
    }
    @Test void chunkedResponseCannotBypassByteCap() {
        server.createContext("/service/Records", e -> {
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().write("{\"value\":[{\"ID\":1}]}".getBytes(StandardCharsets.UTF_8));
            e.close();
        });
        assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ODATA", Map.of("maxResponseBytes", 5))));
    }
    @Test void compressedResponseFailsClosed() {
        server.createContext("/service/Records", e -> {
            e.getResponseHeaders().set("Content-Encoding", "gzip");
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().write("private-token".getBytes(StandardCharsets.UTF_8));
            e.close();
        });
        var failure = assertThrows(Exception.class, () -> new CamelErpDocumentLoader().load(source("ODATA", Map.of("accessToken", "private-token"))));
        assertFalse(failure.toString().contains("private-token"));
        assertNull(failure.getCause());
    }
    @Test void configurationRejectsRoutesUnsafeUrlsAndChangedScope() {
        for(String url:List.of("http://example.com/service", "https://user:secret@example.com/service", "https://example.com/service?password=x","direct:route"))
            assertThrows(IllegalArgumentException.class,()->ErpSourceConfiguration.serviceRoot(url));
        assertThrows(IllegalArgumentException.class,()->ErpSourceConfiguration.from(source("ODATA",Map.of("route","from(...)"))));
        var c=ErpSourceConfiguration.from(source("ODATA",Map.of("filter","active eq true","select","ID")));
        assertThrows(IllegalArgumentException.class,()->c.continuation(c.firstPage(),root+"Records?$select=secret"));
        assertThrows(IllegalArgumentException.class,()->c.continuation(c.firstPage(),root+"Other?$skip=1"));
        assertThrows(IllegalArgumentException.class,()->c.continuation(c.firstPage(),root+"Records?$top=100000"));
        assertTrue(c.continuation(c.firstPage(),root+"Records?$skiptoken=a").toString().contains("%24filter="));
    }
    @Test void stableKeysSurvivePropertyOrderAndRespectTenant() throws Exception {
        respond(200,"{\"value\":[{\"ID\":1,\"name\":\"old\"}]}","{\"value\":[{\"name\":\"new\",\"ID\":1}]}");
        var loader=new CamelErpDocumentLoader();
        var a=loader.load(source("ODATA",Map.of("keyFields","ID","tenant","a"))).get(0);
        var b=loader.load(source("ODATA",Map.of("keyFields","ID","tenant","a"))).get(0);
        var c=loader.load(source("ODATA",Map.of("keyFields","ID","tenant","b"))).get(0);
        assertEquals(a.getMetadata().get("source_path"),b.getMetadata().get("source_path"));
        assertNotEquals(a.getMetadata().get("source_path"),c.getMetadata().get("source_path"));
    }
    @Test void interruptedBeforeNetwork() {
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class,()->new CamelErpDocumentLoader().load(source("ODATA",Map.of()))); }
        finally { Thread.interrupted(); }
        assertEquals(0,calls.get());
    }
}
