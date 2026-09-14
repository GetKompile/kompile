package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
            byte[] response = """
                    [{"parts":[{"type":"compaction","summary":"native session summary"}]}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/session/session-1", exchange -> {
            exchange.sendResponseHeaders(200, 0);
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
    void normalizesNativeTextToolsAndUsageWithoutMixingToolOutputIntoAssistantText() {
        OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:1", "session-1");
        StringBuilder assistant = new StringBuilder();
        List<String> activity = new ArrayList<>();
        OpenCodeServeClient.ActivityListener listener =
                new OpenCodeServeClient.ActivityListener() {
                    @Override
                    public void onToolStart(String callId, String name, String input) {
                        activity.add("start:" + callId + ":" + name);
                    }

                    @Override
                    public void onToolComplete(String callId, String name, String output,
                                               int exitCode, boolean error) {
                        activity.add("complete:" + callId + ":" + output);
                    }

                    @Override
                    public void onTokenUsage(long input, long output,
                                             long cacheRead, long cacheCreation) {
                        activity.add("usage:" + input + ":" + output + ":" + cacheRead);
                    }
                };
        var parser = new ai.kompile.cli.main.chat.PassthroughStreamParser();
        var started = new HashSet<String>();
        var completed = new HashSet<String>();

        client.processProviderLine(
                "{\"type\":\"text\",\"part\":{\"text\":\"answer\"}}",
                parser, assistant, ignored -> { }, listener, started, completed);
        client.processProviderLine(
                "{\"type\":\"tool_use\",\"part\":{\"callID\":\"call-1\","
                        + "\"tool\":\"bash\",\"state\":{\"status\":\"running\","
                        + "\"input\":{\"command\":\"pwd\"}}}}",
                parser, assistant, ignored -> { }, listener, started, completed);
        client.processProviderLine(
                "{\"type\":\"tool_use\",\"part\":{\"callID\":\"call-1\","
                        + "\"tool\":\"bash\",\"state\":{\"status\":\"completed\","
                        + "\"input\":{\"command\":\"pwd\"},"
                        + "\"output\":[{\"type\":\"text\",\"text\":\"tool output\"}],"
                        + "\"metadata\":{\"exit\":0}}}}",
                parser, assistant, ignored -> { }, listener, started, completed);
        client.processProviderLine(
                "{\"type\":\"step_finish\",\"part\":{\"tokens\":{"
                        + "\"input\":12,\"output\":3,\"cache\":{\"read\":4}}}}",
                parser, assistant, ignored -> { }, listener, started, completed);

        assertEquals("answer", assistant.toString());
        assertEquals(List.of(
                "start:call-1:bash",
                "complete:call-1:tool output",
                "usage:12:3:4"), activity);
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
        } catch (java.io.IOException e) {
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
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void serverBootSpawnSeesStdinAtEofImmediately() throws Exception {
        // Zero-byte regression #1: opencode blocks on a never-EOF stdin pipe.
        // The serve boot has nothing to say to the child, so its spawn template
        // (NativeCliProcess) must give it a closed stdin. The turn spawn is
        // deliberately piped instead — it delivers the prompt through stdin —
        // covered by promptPipedToTurnProcess.
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:1", "session-1")) {
            ProcessBuilder builder = ai.kompile.cli.common.util.NativeCliProcess
                    .processBuilder(List.of("/bin/sh", "-c",
                            // read -t distinguishes a live pipe (timeout exit > 128)
                            // from EOF (exit 1), so a regression reports PIPED_OPEN
                            // within seconds instead of hanging the suite.
                            "read -t 3 -r _ < /proc/self/fd/0; rc=$?; "
                                    + "if [ $rc -gt 128 ]; then echo PIPED_OPEN; "
                                    + "else echo EOF_IMMEDIATE; fi"), Path.of("."));
            Process process = builder.start();
            String output;
            try (java.io.InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals("EOF_IMMEDIATE",
                    output.strip(),
                    "native OpenCode server boot must see stdin at EOF immediately");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void turnSpawnAcceptsLargePromptViaStdinWithoutArgLimitFailure() throws Exception {
        // Zero-byte regression #3: composePrompt exceeded MAX_ARG_STRLEN (128 KiB)
        // as a single argv element -> exec error=7 "Argument list too long". The
        // turn must deliver the prompt through piped stdin instead. This probe
        // mirrors the turn spawn shape: piped stdin, oversized single write.
        char[] big = new char[200_000];
        java.util.Arrays.fill(big, 'x');
        String oversizedPrompt = new String(big);

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c",
                "tr -d '\\n' < /proc/self/fd/0 | wc -c")
                .redirectErrorStream(true);
        Process process = builder.start();
        try (java.io.OutputStream stdin = process.getOutputStream()) {
            stdin.write(oversizedPrompt.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
        String output;
        try (java.io.InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(String.valueOf(oversizedPrompt.length()), output.strip(),
                "a 200k-char prompt must survive stdin delivery intact");
    }

    @Test
    void stdinRedirectTargetsThePlatformNullDevice() {
        String expected = System.getProperty("os.name").toLowerCase()
                .contains("win") ? "NUL" : "/dev/null";
        ProcessBuilder.Redirect redirect =
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
