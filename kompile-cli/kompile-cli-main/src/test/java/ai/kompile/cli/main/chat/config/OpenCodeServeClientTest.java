package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenCodeServeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesOpaqueProviderAndModelIds() {
        OpenCodeServeClient.ModelReference reference =
                OpenCodeServeClient.parseModelReference("opencode-go/deepseek-v4-pro");

        assertEquals("opencode-go", reference.providerId());
        assertEquals("deepseek-v4-pro", reference.modelId());
        assertEquals("opencode-go/deepseek-v4-pro", reference.asWireValue());
    }

    @Test
    void rejectsModelIdsWithoutTheNativeProviderPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeServeClient.parseModelReference("deepseek-v4-pro"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeServeClient.parseModelReference("opencode-go/"));
    }

    @Test
    void extractsOnlyNativeTextEventsFromJsonOutput() throws Exception {
        String response = """
                {"type":"text","text":"one"}
                {"type":"reasoning","text":"secret"}
                {"type":"text","text":"two"}
                """;

        assertEquals("onetwo", OpenCodeServeClient.extractText(objectMapper, response));
    }

    @Test
    void extractsTextPartsFromNestedNativeEvents() throws Exception {
        String response = """
                {"parts":[
                  {"type":"reasoning","text":"secret"},
                  {"type":"text","text":"answer"}
                ]}
                """;

        assertEquals("answer", OpenCodeServeClient.extractText(objectMapper, response));
    }

    @Test
    void nativeSummarizeUsesSessionEndpointAndReturnsPortableSummary() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/session/session-1/summarize", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = "true".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/session/session-1/message", exchange -> {
            byte[] response;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                response = "true".getBytes(StandardCharsets.UTF_8);
            } else {
                response = """
                        {"info":{"id":"m2","role":"assistant"},
                         "parts":[{"type":"compaction","summary":"native session summary"}]}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/session/session-1", exchange -> {
            byte[] body = """
                    {"info":{"id":"m2","role":"assistant"},
                     "parts":[{"type":"compaction","summary":"native session summary"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-1")) {
            OpenCodeServeClient.NativeSummary result =
                    client.summarize("opencode-go/deepseek-v4-pro");

            assertTrue(result.applied());
            assertEquals("native session summary", result.summary());
            assertTrue(requestBody.get().contains("\"providerID\":\"opencode-go\""));
            assertTrue(requestBody.get().contains("\"modelID\":\"deepseek-v4-pro\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restTurnPostsProviderModelVariantPromptAndReturnsAssistantText() throws Exception {
        AtomicReference<String> turnBody = new AtomicReference<>();
        AtomicReference<String> turnPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            // Keep the SSE lane open for a bounded window; the client closes it on
            // turn end. The POST future is authoritative, so silence is expected.
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                for (int i = 0; i < 60; i++) {
                    sse.write(' ');  // SSE comment-free keepalive byte
                    sse.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException expected) {
                // client stop() closed the stream or the window elapsed
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-9/message", exchange -> {
            turnPath.set(exchange.getRequestURI().getPath());
            turnBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = """
                    {"info":{"id":"m-final","role":"assistant","tokens":
                      {"input":7,"output":2,"cache":{"read":0,"write":0}}},
                     "parts":[{"type":"step-start"},{"type":"text","text":"rest answer"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-9")) {
                String text = client.send("opencode-go/deepseek-v4-pro", "xhigh",
                        "system instructions", "hello", ignored -> { });

                assertEquals("rest answer", text);
                assertEquals("/session/session-9/message", turnPath.get());
                String body = turnBody.get();
                assertTrue(body.contains("\"providerID\":\"opencode-go\""), body);
                assertTrue(body.contains("\"modelID\":\"deepseek-v4-pro\""), body);
                assertTrue(body.contains("\"variant\":\"xhigh\""), body);
                assertTrue(body.contains("system instructions"), body);
                assertTrue(body.contains("hello"), body);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restTurnSurfacesToolActivityFromTheEventBus() throws Exception {
        List<String> streamed = new ArrayList<>();
        List<String> activity = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                // Tool lifecycle + streamed text delta for this session.
                String[] frames = {
                        "data: {\"type\":\"message.part.delta\",\"properties\":{"
                                + "\"sessionID\":\"session-tool\",\"field\":\"text\","
                                + "\"delta\":\"stream\"}}",
                        "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                + "\"sessionID\":\"session-tool\",\"part\":{\"type\":\"tool\","
                                + "\"callID\":\"call-1\",\"tool\":\"bash\","
                                + "\"state\":{\"status\":\"pending\","
                                + "\"input\":{\"command\":\"pwd\"}}}}}",
                        "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                + "\"sessionID\":\"session-tool\",\"part\":{\"type\":\"tool\","
                                + "\"callID\":\"call-1\",\"tool\":\"bash\","
                                + "\"state\":{\"status\":\"completed\","
                                + "\"output\":\"pwd out\","
                                + "\"metadata\":{\"exit\":0}}}}}",
                        "data: {\"type\":\"message.updated\",\"properties\":{"
                                + "\"sessionID\":\"session-tool\",\"info\":{\"role\":\"assistant\","
                                + "\"tokens\":{\"input\":12,\"output\":3,"
                                + "\"cache\":{\"read\":4,\"write\":0}}}}}",
                        // Other-session traffic must be filtered out.
                        "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                + "\"sessionID\":\"other-session\",\"part\":{\"type\":\"tool\","
                                + "\"callID\":\"call-x\",\"tool\":\"bash\","
                                + "\"state\":{\"status\":\"completed\",\"output\":\"x\"}}}}"
                };
                for (String frame : frames) {
                    sse.write((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                    sse.flush();
                    Thread.sleep(50);
                }
                Thread.sleep(4000); // keep lane open through the POST, then end
            } catch (IOException | InterruptedException expected) {
                // client stop() closed the stream or the window elapsed
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-tool/message", exchange -> {
            byte[] response = """
                    {"info":{"id":"m1"},"parts":[{"type":"text","text":"tool answer"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-tool")) {
                OpenCodeServeClient.ActivityListener listener =
                        new OpenCodeServeClient.ActivityListener() {
                            @Override
                            public void onToolStart(String callId, String name, String input) {
                                activity.add("start:" + callId + ":" + name);
                            }

                            @Override
                            public void onToolComplete(String callId, String name, String output,
                                                       int exitCode, boolean error) {
                                activity.add("complete:" + callId + ":" + output
                                        + ":" + exitCode + ":" + error);
                            }

                            @Override
                            public void onTokenUsage(long input, long output,
                                                     long cacheRead, long cacheCreation) {
                                activity.add("usage:" + input + ":" + output + ":" + cacheRead);
                            }
                        };
                String text = client.send("opencode-go/deepseek-v4-pro", null,
                        null, "run pwd", streamed::add, listener);

                assertEquals("tool answer", text);
                assertEquals("stream", String.join("", streamed));
                assertEquals(List.of(
                        "start:call-1:bash",
                        "complete:call-1:pwd out:0:false",
                        "usage:12:3:4"), activity);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sessionCreateFailureFailsAsTurnNotStarted() throws Exception {
        // The regression: a pre-turn transport failure (deadline exceeded, server
        // unreachable) must surface as TurnNotStartedException so the caller can
        // distinguish it from a turn that already mutated the native session.
        // Connection-refused is the deterministic, fast form of that failure.
        java.net.ServerSocket occupied;
        try {
            occupied = new java.net.ServerSocket(0);
        } catch (IOException e) {
            return; // no loopback available in this environment
        }
        int deadPort = occupied.getLocalPort();
        occupied.close();

        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + deadPort, null)) {
            OpenCodeServeClient.TurnNotStartedException failure =
                    assertThrows(OpenCodeServeClient.TurnNotStartedException.class,
                            () -> client.send("opencode-go/deepseek-v4-pro", null,
                                    null, "hello", ignored -> { }));
            assertTrue(failure.getMessage().contains("session creation failed"));
        }
    }

    @Test
    void sendsTurnsAgainstAnAttachedServerWithoutSpawningItsOwn() throws Exception {
        AtomicReference<String> seenSessionPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/global/health", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/session", exchange -> {
            seenSessionPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"id\":\"attach-session\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), null)) {
                java.lang.reflect.Method ensureServer = OpenCodeServeClient.class
                        .getDeclaredMethod("ensureServer");
                ensureServer.setAccessible(true);
                ensureServer.invoke(client);
                assertEquals(null, seenSessionPath.get(),
                        "ensureServer must not create a session");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stdinRedirectTargetsThePlatformNullDevice() {
        String expected = System.getProperty("os.name").toLowerCase()
                .contains("win") ? "NUL" : "/dev/null";
        java.lang.ProcessBuilder.Redirect redirect =
                ai.kompile.cli.common.util.NativeCliProcess.nullDeviceRedirect();
        assertTrue(redirect.file() != null
                        && expected.equals(redirect.file().getPath()),
                "stdin redirect must use " + expected + ", got " + redirect.file());
    }

    @Test
    void transportPinsHttp11ToAvoidNativeH2cUpgradeHang() throws Exception {
        // The second zero-byte regression: Java HttpClient's default h2c upgrade
        // makes OpenCode's Bun server accept POST /session but never respond, so
        // session creation timed out after 30s on every attempt (the DB row was
        // even persisted server-side). The transport must pin HTTP/1.1, matching
        // curl's wire behavior, which never exhibited the hang.
        OpenCodeServeClient client = new OpenCodeServeClient(objectMapper, Path.of("."));
        java.lang.reflect.Field http = OpenCodeServeClient.class
                .getDeclaredField("httpClient");
        http.setAccessible(true);
        HttpClient transport = (HttpClient) http.get(client);
        assertEquals(HttpClient.Version.HTTP_1_1, transport.version(),
                "OpenCode transport must pin HTTP/1.1 to avoid the Bun h2c "
                        + "upgrade hang");
        client.close();
    }
}
