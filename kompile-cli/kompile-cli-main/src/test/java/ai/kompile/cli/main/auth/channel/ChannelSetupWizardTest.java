package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.cli.common.http.KompileHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ChannelSetupWizard} end to end against an in-process admin control
 * plane using a scripted dumb terminal, mirroring the ChannelControlPlaneClientTest
 * stub-server style. The wizard's prompt order is deterministic, so a fixed script
 * of newline-separated answers exercises the full happy path and cancellation.
 */
class ChannelSetupWizardTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final Map<String, String> capturedBodies = new ConcurrentHashMap<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void wizardWalksProviderEngineSettingsSecretsAndCreatesTheConnection() throws Exception {
        startControlPlane();
        ScriptedTerminal terminal = new ScriptedTerminal(List.of(
                "1",        // provider → demo
                "1",        // engine → REACT
                "",         // agent → default jarvis
                "",         // setting appUrl → default value (engine does not support model)
                "",         // setting teamId → default value
                "s3cr3t",   // secret botToken (required, masked)
                "",         // secret appToken (optional) → skipped
                "",         // connection name → provider id default
                "",         // enable now → default yes
                "n"));      // test message → no

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertEquals("demo", outcome.connectionName());
        assertTrue(outcome.running());
        assertNull(outcome.testTarget());
        assertEquals("jarvis", outcome.connection().agentId());
        assertFalse(terminal.output().contains("s3cr3t"), "masked secret leaked to terminal output");

        JsonNode created = MAPPER.readTree(capturedBodies.get("POST /api/channel-integrations/connections"));
        assertEquals("demo", created.path("name").asText());
        assertEquals("demo", created.path("providerId").asText());
        assertEquals("REACT", created.path("engine").asText());
        assertEquals("jarvis", created.path("agentId").asText());
        assertTrue(created.path("enabled").asBoolean());
        assertEquals("s3cr3t", created.path("secrets").path("botToken").asText());
        assertFalse(created.path("secrets").has("appToken"));
        assertEquals("https://demo.example", created.path("settings").path("appUrl").asText());
        assertEquals(42, created.path("settings").path("teamId").asInt());
    }

    @Test
    void cancellingTheProviderMenuCreatesNothing() throws Exception {
        startControlPlane();
        ScriptedTerminal terminal = new ScriptedTerminal(List.of("cancel"));

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertNull(outcome);
        assertNull(capturedBodies.get("POST /api/channel-integrations/connections"));
    }

    @Test
    void creatingDisabledSkipsReadinessAndTestDelivery() throws Exception {
        startControlPlane();
        ScriptedTerminal terminal = new ScriptedTerminal(List.of(
                "1",        // provider → demo
                "1",        // engine → REACT
                "",         // agent → default
                "",         // setting appUrl → default
                "",         // setting teamId → default
                "s3cr3t",   // secret botToken
                "",         // secret appToken → skipped
                "",         // connection name → default
                "n"));      // enable now → no

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertEquals("demo", outcome.connectionName());
        assertFalse(outcome.running());
        assertNull(outcome.testTarget());
        assertFalse(MAPPER.readTree(
                        capturedBodies.get("POST /api/channel-integrations/connections"))
                .path("enabled").asBoolean());
        assertNull(capturedBodies.get("POST /api/channel-integrations/connections/demo/test"));
    }

    @Test
    void unavailableEngineStopsBeforeCredentialsOrConnectionCreation() throws Exception {
        startControlPlane(false);
        ScriptedTerminal terminal = new ScriptedTerminal(List.of("1"));

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertNull(outcome);
        assertNull(capturedBodies.get("POST /api/channel-integrations/connections"));
    }

    @Test
    void blankMenuInputRequiresAnExplicitSelection() {
        ScriptedTerminal terminal = new ScriptedTerminal(List.of("", "2"));

        int selected = ChannelSetupWizard.selectNumbered(
                terminal.reader(), "Select one:", List.of("First", "Second"));

        assertEquals(1, selected);
    }

    @Test
    void endOfInputAtEnablePromptCancelsWithoutCreatingAConnection() throws Exception {
        startControlPlane();
        ScriptedTerminal terminal = new ScriptedTerminal(List.of(
                "1", "1", "", "", "", "s3cr3t", "", ""));

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertNull(outcome);
        assertNull(capturedBodies.get("POST /api/channel-integrations/connections"));
    }

    @Test
    void oauthLoginEnablesOAuthSettingAndStillRequiresSeparateRuntimeSecret() throws Exception {
        startControlPlane(true, true);
        ScriptedTerminal terminal = new ScriptedTerminal(List.of(
                "1",        // provider → demo
                "1",        // OAuth menu → sign in
                "",         // authorization completed
                "1",        // engine → REACT
                "",         // agent → default
                "",         // useOAuth → effective default true
                "",         // botToken → reuse OAuth source
                "xapp",     // appToken → still runtime-required
                "",         // connection name → default
                "",         // enable now → yes
                "n"));      // no test delivery

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertTrue(outcome.running());
        JsonNode created = MAPPER.readTree(
                capturedBodies.get("POST /api/channel-integrations/connections"));
        assertTrue(created.path("settings").path("useOAuth").asBoolean());
        assertFalse(created.path("secrets").has("botToken"));
        assertEquals("xapp", created.path("secrets").path("appToken").asText());
    }

    @Test
    void runningConnectionCanSendWizardTestDelivery() throws Exception {
        startControlPlane();
        ScriptedTerminal terminal = new ScriptedTerminal(List.of(
                "1", "1", "", "", "", "s3cr3t", "", "", "", "y", "C01"));

        ChannelSetupWizard.SetupOutcome outcome = ChannelSetupWizard.runWithReader(
                client(), terminal.reader());

        assertTrue(outcome.running());
        assertEquals("C01", outcome.testTarget());
        assertTrue(outcome.testAccepted());
        assertEquals("delivered", outcome.testMessage());
        JsonNode delivery = MAPPER.readTree(
                capturedBodies.get("POST /api/channel-integrations/connections/demo/test"));
        assertEquals("C01", delivery.path("target").asText());
    }

    // ── Scripted dumb terminal ─────────────────────────────────────────────

    /** Feeds a fixed script of lines to a jline dumb terminal, one per readLine call. */
    private static final class ScriptedTerminal {
        private final LineReader reader;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ScriptedTerminal(List<String> script) {
            try {
                String joined = String.join("\n", script) + "\n";
                // A DumbTerminal over piped streams is used instead of
                // TerminalBuilder.dumb(true) because a real PTY on the host makes the
                // builder attach to the live terminal and ignore the scripted streams.
                this.reader = LineReaderBuilder.builder()
                        .terminal(new DumbTerminal(
                                new ByteArrayInputStream(joined.getBytes(StandardCharsets.UTF_8)),
                                output))
                        .build();
            } catch (Exception error) {
                throw new IllegalStateException("Could not build scripted terminal", error);
            }
        }

        LineReader reader() {
            return reader;
        }

        String output() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    // ── In-process admin control plane ─────────────────────────────────────

    private ChannelControlPlaneClient client() {
        return new ChannelControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()),
                TOKEN);
    }

    private void startControlPlane() throws Exception {
        startControlPlane(true);
    }

    private void startControlPlane(boolean engineAvailable) throws Exception {
        startControlPlane(engineAvailable, false);
    }

    private void startControlPlane(boolean engineAvailable, boolean oauthFlow) throws Exception {
        AtomicBoolean oauthAuthorized = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/channel-integrations/providers", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            respond(exchange, oauthFlow ? """
                    [{"id":"demo","displayName":"Demo Channel","description":"OAuth stub",
                      "capabilities":["INBOUND","OUTBOUND"],
                      "settings":[
                        {"name":"useOAuth","label":"Use connected OAuth","type":"BOOLEAN",
                         "required":false,"defaultValue":false,"description":"Use OAuth token",
                         "environmentHint":null}],
                      "secrets":[
                        {"name":"botToken","label":"Bot token","type":"STRING","required":false,
                         "defaultValue":null,"description":"OAuth-derived token","environmentHint":null},
                        {"name":"appToken","label":"App token","type":"STRING","required":false,
                         "defaultValue":null,"description":"Separate runtime token","environmentHint":null}]}]
                    """ : """
                    [{"id":"demo","displayName":"Demo Channel","description":"Stub",
                      "capabilities":["INBOUND","OUTBOUND"],
                      "settings":[
                        {"name":"appUrl","label":"App URL","type":"STRING","required":true,
                         "defaultValue":"https://demo.example","description":"Base URL","environmentHint":null},
                        {"name":"teamId","label":"Team ID","type":"INTEGER","required":true,
                         "defaultValue":42,"description":"Workspace id","environmentHint":null}],
                      "secrets":[
                        {"name":"botToken","label":"Bot token","type":"STRING","required":true,
                         "defaultValue":null,"description":"Secret value; accepted only on write operations.",
                         "environmentHint":"DEMO_BOT_TOKEN"},
                        {"name":"appToken","label":"App token","type":"STRING","required":false,
                         "defaultValue":null,"description":"Secret value; accepted only on write operations.",
                         "environmentHint":null}]}]
                    """);
        });
        server.createContext("/api/channel-integrations/providers/demo/auth", exchange -> {
            if (oauthFlow) {
                boolean connected = oauthAuthorized.get();
                respond(exchange, ("""
                        {"providerId":"demo","mode":"OAUTH_PARTIAL","loginSupported":true,
                         "oauthConnected":%s,"channelScopesGranted":%s,
                         "requiredScopes":["chat:write"],"grantedScopes":%s,
                         "missingCredentialFields":%s,
                         "credentialSources":%s,
                         "guidance":"OAuth supplies botToken; appToken remains separate."}
                        """).formatted(
                                connected,
                                connected,
                                connected ? "[\"chat:write\"]" : "[]",
                                connected ? "[\"appToken\"]" : "[\"botToken\",\"appToken\"]",
                                connected ? "{\"botToken\":\"oauth:demo\"}" : "{}"));
                return;
            }
            respond(exchange, """
                        {"providerId":"demo","mode":"MANUAL_SECRET","loginSupported":false,
                         "oauthConnected":false,"channelScopesGranted":false,
                         "requiredScopes":[],"grantedScopes":[],
                         "missingCredentialFields":["botToken"],
                         "credentialSources":{},
                         "guidance":"Create a bot and paste its token."}
                        """);
        });
        if (oauthFlow) {
            server.createContext("/api/oauth/demo/authorize", exchange -> {
                oauthAuthorized.set(true);
                respond(exchange, "{\"authorizationUrl\":\"https://demo.example/authorize\"}");
            });
        }
        server.createContext("/api/channel-integrations/engines", exchange ->
                respond(exchange, ("""
                        [{"engine":"REACT","displayName":"ReAct agent",
                          "description":"Tool-using agent","supportsAgent":true,"supportsModel":false,
                          "available":%s,"status":"%s"}]
                        """).formatted(engineAvailable, engineAvailable ? "ready" : "not installed")));
        server.createContext("/api/channel-integrations/connections", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String requestBody = body(exchange);
                capturedBodies.put("POST /api/channel-integrations/connections", requestBody);
                boolean enabled = MAPPER.readTree(requestBody).path("enabled").asBoolean();
                respond(exchange, connectionJson(
                        "demo", enabled ? "RUNNING" : "DISABLED", enabled));
                return;
            }
            respond(exchange, "[]");
        });
        server.createContext("/api/channel-integrations/connections/demo", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, connectionJson("demo", "RUNNING", true));
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.createContext("/api/channel-integrations/connections/demo/test", exchange -> {
            capturedBodies.put("POST /api/channel-integrations/connections/demo/test", body(exchange));
            respond(exchange, "{\"accepted\":true,\"message\":\"delivered\"}");
        });
        server.start();
    }

    private static String connectionJson(String name, String runtimeState, boolean enabled) {
        return "{\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"name\":\"" + name + "\","
                + "\"providerId\":\"demo\","
                + "\"engine\":\"REACT\","
                + "\"agentId\":\"jarvis\","
                + "\"model\":null,"
                + "\"enabled\":" + enabled + ","
                + "\"runtimeState\":\"" + runtimeState + "\","
                + "\"settings\":{\"appUrl\":\"https://demo.example\",\"teamId\":42},"
                + "\"configuredSecrets\":[\"botToken\"],"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-01-01T00:00:00Z\","
                + "\"lastError\":null}";
    }

    private static String body(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
