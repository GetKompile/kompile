package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.cli.common.http.KompileHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelControlPlaneClientTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesProviderDescriptorsFromAdminApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/channel-integrations/providers", exchange -> {
            if (!TOKEN.equals(exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] body = """
                    [{"id":"telegram","displayName":"Telegram","description":"Bot API",
                      "capabilities":["INBOUND","OUTBOUND"],"settings":[],"secrets":[]}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()),
                TOKEN);

        assertEquals("telegram", client.providers().get(0).id());
        assertDoesNotThrow(client::requireSecureControlPlane);
    }

    @Test
    void refusesCredentialWritesOverRemotePlaintextHttp() {
        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://channels.example.test:8080"), TOKEN);
        ChannelControlPlaneClient deceptiveLoopback = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.attacker.example:8080"), TOKEN);

        assertThrows(IllegalStateException.class, client::requireSecureControlPlane);
        assertThrows(IllegalStateException.class, deceptiveLoopback::requireSecureControlPlane);
    }

    @Test
    void sendsAuthenticatedMutationHeadersForTelegramPairing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/channel-integrations/connections/ops/telegram/pairings", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            assertEquals("1",
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.REQUEST_HEADER));
            byte[] body = """
                    {"pairingId":"6b4bc3a0-8d76-4a38-96dd-f4df63d3c3ff",
                     "code":"once","command":"/pair once",
                     "expiresAt":"2026-01-01T00:10:00Z"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()),
                TOKEN);

        var pairing = client.startTelegramPairing("ops");

        assertEquals("/pair once", pairing.command());
    }

    @Test
    void credentialFetchIsBearerAuthenticatedAndDeserializesMappedView() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/channel-integrations/connections/ops/credential", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            byte[] body = """
                    {"providerId":"discord","secrets":{"botToken":"runtime-only"},
                     "properties":{"allowedChannelIds":["C01"]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        var credential = client.credential("ops");

        assertEquals("discord", credential.providerId());
        assertEquals("runtime-only", credential.secrets().get("botToken"));
    }

    @Test
    void bearerMintsOneTimeWebLoginCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/channel-integrations/browser-sessions", exchange -> {
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            assertEquals("1",
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.REQUEST_HEADER));
            byte[] body = """
                    {"code":"one-time-code","expiresAt":"2026-01-01T00:05:00Z"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        assertEquals("one-time-code", client.browserLogin().code());
    }

    @Test
    void harnessAuthLoginAndDeliveryStayAuthenticatedAndSecretFree() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/channel-integrations/providers/slack/auth", exchange -> respond(
                exchange, """
                        {"providerId":"slack","mode":"OAUTH_PARTIAL","loginSupported":true,
                         "oauthConnected":true,"channelScopesGranted":true,
                         "requiredScopes":["chat:write"],"grantedScopes":["chat:write"],
                         "missingCredentialFields":[],"credentialSources":{"botToken":"oauth:slack"},
                         "guidance":"app token remains separate"}
                        """));
        server.createContext("/api/oauth/slack/authorize", exchange -> {
            assertEquals("purpose=channel", exchange.getRequestURI().getQuery());
            respond(exchange, "{\"authorizationUrl\":\"https://slack.example/authorize\"}");
        });
        server.createContext("/api/channel-integrations/connections/ops/deliver", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("1", exchange.getRequestHeaders().getFirst(
                    ChannelControlHeaders.REQUEST_HEADER));
            respond(exchange, "{\"accepted\":true,\"message\":\"accepted\"}");
        });
        server.start();
        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        assertTrue(client.providerAuth("slack").credentialReady());
        assertEquals("https://slack.example/authorize",
                client.initiateOAuthLogin("slack").path("authorizationUrl").asText());
        assertTrue(client.deliver(
                "ops", new ChannelTestRequest("C01", "hello")).accepted());
    }

    @Test
    void secretBearingMutationDoesNotEchoServerErrorBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/channel-integrations/connections/ops/deliver", exchange -> {
            String echoedSecret = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = echoedSecret.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ChannelControlPlaneClient client = new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        IOException failure = assertThrows(IOException.class, () -> client.deliver(
                "ops", new ChannelTestRequest("C01", "sensitive-message")));

        assertEquals("Channel API returned HTTP 400", failure.getMessage());
        assertFalse(failure.getMessage().contains("sensitive-message"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        assertEquals(TOKEN,
                exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
