package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth;
import com.sun.net.httpserver.HttpServer;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SetupWizardDiscoveryAuthTest {
    @Test
    void discoveryAndRefreshUseSessionAuthEvenWithoutNamedCredential() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"fixture-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1") {
                @Override public RequestAuth resolveRequestAuth() {
                    return RequestAuth.apiKey("selected-session-key");
                }
            };
            assertEquals(ModelDiscovery.Status.SUCCESS,
                    SetupWizard.modelDiscovery("custom", null, config).status());
            assertEquals("Bearer selected-session-key", authorization.get());
            assertEquals(ModelDiscovery.Status.SUCCESS,
                    SetupWizard.refreshModelDiscovery("custom", null, config).status());
            assertEquals("Bearer selected-session-key", authorization.get());
            assertEquals(ModelDiscovery.Status.SUCCESS,
                    SetupWizard.modelDiscovery("custom", "new-explicit-key", config).status());
            assertEquals("Bearer new-explicit-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingOrRejectedCredentialsNeverOpenManualModelPrompt() {
        LineReader reader = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                    throw new AssertionError("Authentication failure must not prompt for a model");
                });
        for (boolean rejected : new boolean[]{false, true}) {
            ChatConfig config = new ChatConfig("openai", null, null, null) {
                @Override public RequestAuth resolveRequestAuth() {
                    if (rejected) throw new AuthenticationException("openai");
                    return null;
                }
            };
            config.setAuthenticationMethod("api-key");
            var discovery = SetupWizard.modelDiscovery("openai", null, config);
            assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, discovery.status());
            assertTrue(ModelCatalogSelection.authenticationBlocked(discovery));
            assertNull(SetupWizard.selectModel(reader, "openai", null, config));
        }
    }

    @Test
    void forbiddenModelListingIsExplicitAndNeverPromptsForAModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"error\":\"private-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1") {
                @Override public RequestAuth resolveRequestAuth() { return RequestAuth.apiKey("fixture-key"); }
            };
            var result = SetupWizard.modelDiscovery("custom", null, config);
            assertEquals(ModelDiscovery.Status.FORBIDDEN, result.status());
            assertTrue(result.message().contains("HTTP 403"));
            assertTrue(result.message().contains("permissions"));
            assertFalse(result.message().contains("private-token"));
            // A null reader also ensures this path cannot solicit a manual model.
            assertNull(SetupWizard.selectModel(null, "custom", null, config));
        } finally {
            server.stop(0);
        }
    }
}
