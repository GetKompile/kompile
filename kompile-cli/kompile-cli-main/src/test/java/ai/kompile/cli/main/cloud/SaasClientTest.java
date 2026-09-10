package ai.kompile.cli.main.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaasClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profile", this::handleProfile);
        server.createContext("/missing", exchange -> respond(exchange, 404, "{\"error\":\"missing\"}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void putSendsJsonAndBearerToken() throws Exception {
        SaasClient client = new SaasClient(baseUrl, "test-token");
        JsonNode response = client.put("/profile", "{\"expectedVersion\":0}");

        assertEquals("PUT", method.get());
        assertEquals("Bearer test-token", authorization.get());
        assertEquals("{\"expectedVersion\":0}", requestBody.get());
        assertEquals(1, response.path("version").asInt());
    }

    @Test
    void exposesHttpStatusAndBodyForConflictHandling() {
        SaasClient client = new SaasClient(baseUrl, "test-token");
        SaasClient.HttpException error = assertThrows(SaasClient.HttpException.class,
                () -> client.get("/missing"));
        assertEquals(404, error.getStatusCode());
        assertTrue(error.getResponseBody().contains("missing"));
    }

    @Test
    void cloudCommandRegistersSettingsManagementSurface() {
        CommandLine commandLine = new CommandLine(new CloudCommand());
        assertNotNull(commandLine.getSubcommands().get("settings"));
        assertNotNull(commandLine.getSubcommands().get("profiles"));
        CommandLine settings = commandLine.getSubcommands().get("settings");
        assertTrue(settings.getSubcommands().keySet()
                .containsAll(java.util.Set.of("list", "get", "push", "pull", "delete")));
    }

    @Test
    void settingsCommandsMakeConflictsActionableAndRejectFalseConfirmation() {
        SaasClient.HttpException conflict = new SaasClient.HttpException(
                409, "conflict", "{\"currentVersion\":7}");
        assertEquals(1, CloudSettingsCommand.reportCloudFailure(
                "store", "default", 6, conflict));

        CommandLine settings = new CommandLine(new CloudSettingsCommand());
        assertEquals(2, settings.execute("delete", "default", "--yes=false"));
        assertThrows(IllegalArgumentException.class,
                () -> CloudSettingsBundleService.validateDescription("token=live-secret"));
        assertThrows(IllegalArgumentException.class,
                () -> CloudSettingsBundleService.validateDescription("safe\u001b[31m"));
        assertEquals("safe?[31m", CloudSettingsCommand.safeDisplay("safe\u001b[31m"));
    }

    private void handleProfile(HttpExchange exchange) throws IOException {
        method.set(exchange.getRequestMethod());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        respond(exchange, 200, "{\"version\":1}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
