package ai.kompile.app.web.security;

import ai.kompile.app.services.agent.ProductChatSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductChatHttpFilterTest {
    private static final String DOMAIN = "/api/agents/chat/product/domain/12345678-1234-1234-1234-123456789abc";
    private static final String COMMAND = DOMAIN + "/api/tactical-boards/1/commands";
    private static final String RUN = "harness-product-" + "a".repeat(32);
    @Test void bootRegistersTheOncePerRequestFilterForErrorDispatch() {
        assertTrue(new FilterRegistrationBean<>(enabled()).determineDispatcherTypes().contains(DispatcherType.ERROR));
    }

    @Test void containerErrorDispatchKeepsTypedStatusWithoutGenericDetails() throws Exception {
        for (int status : new int[]{400, 409, 410, 503}) {
            MockHttpServletRequest request = local("POST", "/error");
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private-fixture-error");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean genericErrorController = new AtomicBoolean();
            enabled().doFilter(request, response, (r, s) -> genericErrorController.set(true));
            assertEquals(status, response.getStatus());
            assertFalse(genericErrorController.get());
            assertFalse(response.getContentAsString().contains("private-fixture-error"));
            assertNotNull(response.getHeader("Content-Security-Policy"));
        }
    }

    @Test void invalidContainerErrorStatusCannotBecomeSuccess() throws Exception {
        for (Object status : new Object[]{null, 200, 700, "409"}) {
            MockHttpServletRequest request = local("POST", "/error");
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
            MockHttpServletResponse response = new MockHttpServletResponse();
            enabled().doFilter(request, response, (r, s) -> fail("Generic error controller must remain absent"));
            assertEquals(500, response.getStatus());
        }
    }

    @Test void nestedContainerErrorDispatchAlsoKeepsConflict() throws Exception {
        ProductChatHttpFilter filter = enabled();
        MockHttpServletRequest request = local("GET", "/api/agents/chat/product");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> {
            request.setRequestURI("/error"); request.setMethod("POST");
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 409);
            filter.doFilter(request, response, (ignored, result) -> fail("Nested error cannot reach generic controller"));
        });
        assertEquals(409, response.getStatus());
    }

    @Test void refusesWildcardBindAndDnsRebindingAuthorities() {
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(true);
        ProductChatHttpFilter filter = new ProductChatHttpFilter(products, new MockEnvironment(), new ObjectMapper());
        assertThrows(IllegalStateException.class, filter::verifyLoopbackBind);
        ProductChatHttpFilter bound = new ProductChatHttpFilter(products,
                new MockEnvironment().withProperty("server.address", "127.0.0.1"), new ObjectMapper());
        assertDoesNotThrow(bound::verifyLoopbackBind);
        MockHttpServletRequest request = local("GET", "/api/agents/chat/product");
        assertTrue(ProductChatHttpFilter.safeAuthority(request));
        request.addHeader("Origin", "http://127.0.0.1:8081"); assertTrue(ProductChatHttpFilter.safeAuthority(request));
        request.removeHeader("Origin"); request.addHeader("Origin", "https://evil.example");
        assertFalse(ProductChatHttpFilter.safeAuthority(request));
        request.removeHeader("Origin"); request.removeHeader("Host"); request.addHeader("Host", "rebind.example:8081");
        assertFalse(ProductChatHttpFilter.safeAuthority(request));
        request.removeHeader("Host"); request.addHeader("Host", "127.0.0.1:8082");
        assertFalse(ProductChatHttpFilter.safeAuthority(request));
        request.removeHeader("Host"); request.addHeader("Host", "127.0.0.1:8081"); request.addHeader("X-Forwarded-Host", "127.0.0.1:8081");
        assertFalse(ProductChatHttpFilter.safeAuthority(request));
    }

    @Test void genericConfigurationNativeAndUnknownDomainApisAreNotMounted() {
        assertTrue(ProductChatHttpFilter.approvedRoute("GET", "/api/agents/chat/product"));
        assertTrue(ProductChatHttpFilter.approvedRoute("POST", "/api/agents/chat/stream"));
        for (String path : new String[]{"/api/folders", "/api/projects", "/api/agents/chat/session/setup", "/api/agents/chat/compact",
                "/api/agents/native", "/api/chat/config", "/api/roles", "/api/agents/chat/capabilities",
                "/api/agents/chat/product/domain/http://169.254.169.254", "/api/agents/chat/product/domain/redirect",
                "/api/agents/chat/product/domain/tactical-board"}) {
            assertFalse(ProductChatHttpFilter.approvedRoute("GET", path), path);
            assertFalse(ProductChatHttpFilter.approvedRoute("POST", path), path);
        }
        assertFalse(ProductChatHttpFilter.approvedRoute("PUT", "/api/agents/chat/product/session"));
        assertFalse(ProductChatHttpFilter.approvedRoute("GET", "/api/agents/chat/product/assets/../secret"));
    }

    @Test void contentApprovalRoutesAreExactAndBodiesCannotSelectToolsOrWorkers() throws Exception {
        String base = "/api/agents/chat/product/content-approvals";
        assertTrue(ProductChatHttpFilter.approvedRoute("GET", base));
        assertTrue(ProductChatHttpFilter.approvedRoute("POST", base + "/pending-1"));
        for (String method : List.of("GET", "PUT", "DELETE", "PATCH"))
            assertFalse(ProductChatHttpFilter.approvedRoute(method, base + "/pending-1"));
        assertFalse(ProductChatHttpFilter.approvedRoute("POST", base));
        for (String id : List.of("../x", "a/b", "a%2Fb", "a.b", "a".repeat(129)))
            assertFalse(ProductChatHttpFilter.approvedRoute("POST", base + "/" + id));
        for (String body : List.of("{\"allow\":true}", "{\"allow\":false}", "{\"allow\":\"true\"}",
                "{\"allow\":true,\"tool\":\"crawl_documents\"}", "{\"allow\":true,\"generation\":\"foreign\"}",
                "{\"allow\":true,\"approved\":true}", "{\"allow\":true,\"allow\":false}", "{}", "null",
                "{\"allow\":true} {}", "x".repeat(513))) {
            MockHttpServletRequest request = local("POST", base + "/pending-1"); request.setContentType("application/json");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean reached = new AtomicBoolean();
            enabled().doFilter(request, response, (r, s) -> reached.set(true));
            boolean valid = body.equals("{\"allow\":true}") || body.equals("{\"allow\":false}");
            assertEquals(valid, reached.get(), body);
            if (!valid) assertEquals(body.length() > 512 ? 413 : 400, response.getStatus());
        }
        MockHttpServletRequest foreign = local("POST", base + "/pending-1"); foreign.addHeader("Origin", "https://foreign.invalid");
        foreign.setContentType("application/json"); foreign.setContent("{\"allow\":true}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        enabled().doFilter(foreign, response, (r, s) -> fail("Cross-origin approval must not reach controls"));
        assertEquals(403, response.getStatus());
    }

    @Test void fullFilterDeniesBeforeAnyGenericControllerAndBoundsBodies() throws Exception {
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(true);
        ProductChatHttpFilter filter = new ProductChatHttpFilter(products,
                new MockEnvironment().withProperty("server.address", "127.0.0.1"), new ObjectMapper());
        AtomicBoolean reached = new AtomicBoolean();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(local("GET", "/api/folders"), denied, (r, s) -> reached.set(true));
        assertEquals(404, denied.getStatus()); assertFalse(reached.get());
        MockHttpServletResponse matrixPath = new MockHttpServletResponse();
        filter.doFilter(local("GET", "/api;ignored/folders"), matrixPath, (r, s) -> reached.set(true));
        assertEquals(404, matrixPath.getStatus()); assertFalse(reached.get());
        MockHttpServletRequest body = local("POST", "/api/agents/chat/stream");
        body.setContentType("application/json"); body.setContent(new byte[131_073]);
        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        filter.doFilter(body, tooLarge, (r, s) -> reached.set(true));
        assertEquals(413, tooLarge.getStatus()); assertFalse(reached.get());
        MockHttpServletRequest duplicate = local("POST", "/api/agents/chat/stream"); duplicate.setContentType("application/json");
        duplicate.setContent("{\"message\":\"safe\",\"message\":\"escape\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse invalid = new MockHttpServletResponse();
        filter.doFilter(duplicate, invalid, (r, s) -> reached.set(true));
        assertEquals(400, invalid.getStatus()); assertFalse(reached.get());
        MockHttpServletRequest controls = local("POST", "/api/agents/chat/control/" + RUN); controls.setContentType("application/json");
        controls.setContent("{\"action\":\"set_model\",\"model\":\"escape\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(controls, refused, (r, s) -> reached.set(true));
        assertEquals(403, refused.getStatus()); assertFalse(reached.get());
    }

    @Test void onlyTheFourExactSdkRouteAndMethodPatternsAreEnabled() {
        for (String path : List.of(DOMAIN + "/api/tactical-boards/1",
                DOMAIN + "/api/world-sim/2/story-candidates", DOMAIN + "/api/world-sim/3/decision-workbench")) {
            assertTrue(ProductChatHttpFilter.approvedRoute("GET", path), path);
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
                assertFalse(ProductChatHttpFilter.approvedRoute(method, path), method + path);
        }
        assertTrue(ProductChatHttpFilter.approvedRoute("POST", COMMAND));
        for (String method : List.of("GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
            assertFalse(ProductChatHttpFilter.approvedRoute(method, COMMAND), method);
        for (String path : List.of(DOMAIN, DOMAIN + "/api/tactical-boards", DOMAIN + "/api/tactical-boards/0",
                DOMAIN + "/api/tactical-boards/01", DOMAIN + "/api/tactical-boards/-1",
                DOMAIN + "/api/tactical-boards/10000000000000000", DOMAIN + "/api/tactical-boards/1/export",
                DOMAIN + "/api/world-sim/1", DOMAIN + "/api/world-sim/1/commands", COMMAND + "/extra",
                DOMAIN.replace("12345678", "ABCDEFAB") + "/api/tactical-boards/1",
                "/api/agents/chat/product/domain/not-a-runtime/api/tactical-boards/1")) {
            assertFalse(ProductChatHttpFilter.approvedRoute("GET", path), path);
            assertFalse(ProductChatHttpFilter.approvedRoute("POST", path), path);
        }
        assertTrue(ProductChatHttpFilter.approvedRoute("GET", "/api/agents/chat/events/" + RUN));
        assertTrue(ProductChatHttpFilter.approvedRoute("POST", "/api/agents/chat/cancel/" + RUN));
        assertFalse(ProductChatHttpFilter.approvedRoute("POST", "/api/agents/chat/cancel/harness-product-random-generation"));
    }

    @Test void transcriptGetCanReachOnlyTheFixedIdentityControllerWhileGenericHistoryStaysClosed() throws Exception {
        ProductChatHttpFilter filter = enabled(); AtomicBoolean reached = new AtomicBoolean();
        String path = "/api/agents/chat/product/transcript";
        MockHttpServletRequest request = local("GET", path);
        request.setQueryString("sessionId=server-owned&workingDirectory=/fixed");
        request.addParameter("sessionId", "server-owned"); request.addParameter("workingDirectory", "/fixed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> reached.set(true));
        assertTrue(reached.get()); assertEquals(200, response.getStatus());
        assertNotNull(response.getHeader("Content-Security-Policy"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertFalse(ProductChatHttpFilter.approvedRoute("POST", path));
        for (String other : List.of("/api/workspace/chat/history", "/api/folders/history", "/api/agents/chat/history",
                "/api/agents/chat/transcript", "/api/providers", "/api/agents", "/api/roles"))
            assertFalse(ProductChatHttpFilter.approvedRoute("GET", other), other);
        MockHttpServletRequest unexpected = local("GET", path);
        unexpected.setQueryString("sessionId=x&provider=escape"); unexpected.addParameter("sessionId", "x");
        unexpected.addParameter("provider", "escape");
        MockHttpServletResponse invalid = new MockHttpServletResponse(); reached.set(false);
        filter.doFilter(unexpected, invalid, (r, s) -> reached.set(true));
        assertEquals(400, invalid.getStatus()); assertFalse(reached.get());
        MockHttpServletRequest duplicate = local("GET", path);
        duplicate.setQueryString("sessionId=x&sessionId=y"); duplicate.addParameter("sessionId", "x", "y");
        MockHttpServletResponse duplicates = new MockHttpServletResponse();
        filter.doFilter(duplicate, duplicates, (r, s) -> reached.set(true));
        assertEquals(400, duplicates.getStatus()); assertFalse(reached.get());
    }

    @Test void domainCommandsPassBoundedRawUtf8BytesUnchangedInsteadOfUsingTheSessionBodyGuard() throws Exception {
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(true);
        ProductChatHttpFilter filter = new ProductChatHttpFilter(products,
                new MockEnvironment().withProperty("server.address", "127.0.0.1"), new ObjectMapper());
        byte[] json = "{ \"note\" : \"é\", \"command\" : \"hold\" }".getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(json, 150_000); Arrays.fill(body, json.length, body.length, (byte) ' ');
        MockHttpServletRequest request = local("POST", COMMAND);
        request.setContentType("application/json; charset=\"UTF-8\""); request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (r, s) -> {
            reached.set(true);
            assertEquals(150_000, r.getContentLengthLong());
            assertArrayEquals(body, r.getInputStream().readAllBytes());
        });
        assertEquals(200, response.getStatus()); assertTrue(reached.get());
        verify(products, never()).validateBody(any());
        assertNotNull(response.getHeader("Content-Security-Policy"));
    }

    @Test void domainPostBoundsUnknownLengthBodiesRejectsQueriesAndNeverOpensOtherDomainPaths() throws Exception {
        ProductChatHttpFilter filter = enabled(); AtomicBoolean reached = new AtomicBoolean();
        MockHttpServletRequest huge = new MockHttpServletRequest("POST", COMMAND) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        makeLocal(huge); huge.setContentType("application/json"); huge.setContent(new byte[150_001]);
        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        filter.doFilter(huge, tooLarge, (r, s) -> reached.set(true));
        assertEquals(413, tooLarge.getStatus()); assertFalse(reached.get());
        MockHttpServletRequest query = local("POST", COMMAND); query.setContentType("application/json");
        query.setQueryString("campaignId=1"); query.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(query, refused, (r, s) -> reached.set(true));
        assertEquals(400, refused.getStatus()); assertFalse(reached.get());
        MockHttpServletRequest wrongPath = local("POST", DOMAIN + "/api/anything");
        wrongPath.setContentType("application/json"); wrongPath.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse closed = new MockHttpServletResponse();
        filter.doFilter(wrongPath, closed, (r, s) -> reached.set(true));
        assertEquals(404, closed.getStatus()); assertFalse(reached.get());
    }

    @Test void exactDomainGetQueriesReachTheControllerForRawSdkValidation() throws Exception {
        ProductChatHttpFilter filter = enabled();
        for (String route : List.of(DOMAIN + "/api/tactical-boards/1?campaignId=2",
                DOMAIN + "/api/world-sim/1/story-candidates?campaignId=2&limit=24",
                DOMAIN + "/api/world-sim/1/decision-workbench?campaignId=2")) {
            String[] parts = route.split("\\?", 2);
            MockHttpServletRequest request = local("GET", parts[0]); request.setQueryString(parts[1]);
            AtomicBoolean reached = new AtomicBoolean(); MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (r, s) -> {
                reached.set(true); assertEquals(parts[1], ((HttpServletRequest) r).getQueryString());
            });
            assertEquals(200, response.getStatus()); assertTrue(reached.get());
        }
    }

    @Test void jsonMediaTypesCannotUsePrefixSpoofingParametersOrContentEncoding() throws Exception {
        ProductChatHttpFilter filter = enabled();
        for (String path : List.of(COMMAND, "/api/agents/chat/stream")) {
            for (String type : List.of("application/json-evil", "application/json;bogus=1", "application/json;charset=UTF-16",
                    "application/json;charset=not-a-charset", "text/json", "application/*", "application/json; charset=\"unclosed")) {
                MockHttpServletRequest request = local("POST", path); request.setContentType("application/json");
                request.setContent("{}".getBytes(StandardCharsets.UTF_8));
                MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean reached = new AtomicBoolean();
                // Do not let MockHttpServletRequest parse malformed media types before the real guard.
                filter.doFilter(withContentTypes(request, List.of(type)), response, (r, s) -> reached.set(true));
                assertEquals(415, response.getStatus(), type); assertFalse(reached.get(), type);
            }
        }
        for (String header : List.of("Content-Type", "Content-Encoding")) {
            MockHttpServletRequest request = local("POST", COMMAND); request.setContentType("application/json");
            if (header.equals("Content-Encoding")) request.addHeader(header, "gzip");
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            HttpServletRequest checked = header.equals("Content-Type")
                    ? withContentTypes(request, List.of("application/json", "application/json")) : request;
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(checked, response, (r, s) -> fail("Ambiguous/encoded body must not reach a controller"));
            assertEquals(415, response.getStatus());
        }
        MockHttpServletRequest session = local("POST", "/api/agents/chat/product/session");
        session.setContentType("application/json"); session.setContent("null".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(session, refused, (r, s) -> fail("Session controls require an empty body"));
        assertEquals(400, refused.getStatus());
    }

    private static HttpServletRequest withContentTypes(HttpServletRequest request, List<String> types) {
        return new HttpServletRequestWrapper(request) {
            @Override public String getContentType() { return types.get(0); }
            @Override public Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Content-Type") ? Collections.enumeration(types) : super.getHeaders(name);
            }
        };
    }

    private static ProductChatHttpFilter enabled() {
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(true);
        return new ProductChatHttpFilter(products,
                new MockEnvironment().withProperty("server.address", "127.0.0.1"), new ObjectMapper());
    }

    @Test void ordinaryProcessRemainsUnchangedWithoutProductProfile() throws Exception {
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(false);
        ProductChatHttpFilter filter = new ProductChatHttpFilter(products, new MockEnvironment(), new ObjectMapper());
        assertDoesNotThrow(filter::verifyLoopbackBind);
        AtomicBoolean reached = new AtomicBoolean(); MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/chat/config"), response, (r, s) -> reached.set(true));
        assertTrue(reached.get()); assertNull(response.getHeader("Content-Security-Policy"));
    }

    private static MockHttpServletRequest local(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        makeLocal(request);
        return request;
    }

    private static void makeLocal(MockHttpServletRequest request) {
        request.setRemoteAddr("127.0.0.1"); request.setLocalAddr("127.0.0.1");
        request.setLocalPort(8081); request.setScheme("http"); request.addHeader("Host", "127.0.0.1:8081");
    }
}
