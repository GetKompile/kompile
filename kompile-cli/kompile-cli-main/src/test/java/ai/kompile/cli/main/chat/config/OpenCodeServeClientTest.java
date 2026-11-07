package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void textlessFinalMessageSurfacesTheTurnsRealProviderError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/session/session-err/message", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {"info":{"id":"m-final","role":"assistant","parentID":"msg_u1","finish":"stop",
                      "error":{"name":"APIError",
                        "data":{"message":"rate limit exceeded, retry later","isRetryable":true}}},
                     "parts":[{"type":"step-start"},{"type":"reasoning","text":"hmm"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-err")) {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> client.send("opencode-go/deepseek-v4-pro", null, null,
                                "hello", ignored -> { }));

                assertTrue(failure.getMessage().contains("rate limit exceeded"),
                        "the provider's own error must surface, got: " + failure.getMessage());
                assertTrue(failure.getMessage().contains("APIError"),
                        "the named error carries the context, got: " + failure.getMessage());
                assertFalse(failure.getMessage().contains("no assistant text"),
                        "the blank-text error must not mask the real failure, got: "
                                + failure.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void textlessFinalMessageReconcilesTheTurnAnswerFromTheSession() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/session/session-rec/message", exchange -> {
            byte[] response;
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                response = """
                        [
                          {"info":{"id":"msg_old","role":"assistant","parentID":"msg_u0"},
                           "parts":[{"type":"text","text":"stale earlier turn"}]},
                          {"info":{"id":"msg_u1","role":"user"},
                           "parts":[{"type":"text","text":"check the port"}]},
                          {"info":{"id":"msg_a1","role":"assistant","parentID":"msg_u1"},
                           "parts":[{"type":"text","text":"Checking the port now."},
                                    {"type":"tool","tool":"bash",
                                     "state":{"status":"completed","output":"8081"}}]},
                          {"info":{"id":"msg_a2","role":"assistant","parentID":"msg_u1","finish":"tool-calls"},
                           "parts":[{"type":"step-start"}]}
                        ]
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                response = """
                        {"info":{"id":"msg_a2","role":"assistant","parentID":"msg_u1",
                          "finish":"tool-calls"},
                         "parts":[{"type":"step-start"}]}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-rec")) {
                String text = client.send("opencode-go/deepseek-v4-pro", null, null,
                        "check the port", ignored -> { });

                assertEquals("Checking the port now.", text,
                        "the turn's earlier assistant text reconciles the blank final message");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void textlessTurnWithNothingToReconcileFailsWithShapeDiagnostics() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/session/session-step/message", exchange -> {
            byte[] response;
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                response = """
                        [{"info":{"id":"msg_a2","role":"assistant","parentID":"msg_u1","finish":"tool-calls"},
                          "parts":[{"type":"step-start"},{"type":"tool","tool":"bash"}]}]
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                response = """
                        {"info":{"id":"msg_a2","role":"assistant","parentID":"msg_u1","finish":"tool-calls",
                          "tokens":{"input":13939,"output":4,"reasoning":18}},
                         "parts":[{"type":"step-start"},{"type":"tool","tool":"bash"}]}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-step")) {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> client.send("opencode-go/deepseek-v4-pro", null, null,
                                "hello", ignored -> { }));

                assertTrue(failure.getMessage().contains("returned no assistant text"),
                        failure.getMessage());
                assertTrue(failure.getMessage().contains("finish=tool-calls"),
                        "the finish reason explains a tool-only step message, got: "
                                + failure.getMessage());
                assertTrue(failure.getMessage().contains("tool×1"),
                        "the part overview shows the turn ran tools, got: " + failure.getMessage());
                assertTrue(failure.getMessage().contains("tokens in=13939 out=4 reasoning=18"),
                        "the token counters show whether the model spoke, got: "
                                + failure.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void describeErrorReadsTheNamedErrorEnvelope() throws Exception {
        assertNull(OpenCodeServeClient.describeError(objectMapper.createObjectNode()));
        assertEquals("unknown failure", OpenCodeServeClient.describeError(
                objectMapper.readTree("{\"error\":{\"data\":{}}}")));
        assertEquals("APIError: connection reset",
                OpenCodeServeClient.describeError(objectMapper.readTree(
                        "{\"error\":{\"name\":\"APIError\",\"data\":{\"message\":\"connection reset\"}}}")));
        assertEquals("MessageAbortedError", OpenCodeServeClient.describeError(objectMapper.readTree(
                "{\"error\":{\"name\":\"MessageAbortedError\"}}")));
        assertEquals("mined message", OpenCodeServeClient.describeError(objectMapper.readTree(
                "{\"error\":{\"message\":\"mined message\"}}")));
    }

    @Test
    void extractAssistantTextByParentJoinsTheTurnsAssistantSteps() throws Exception {
        String messages = """
                [
                  {"info":{"id":"msg_old","role":"assistant","parentID":"msg_u0"},
                   "parts":[{"type":"text","text":"stale earlier turn"}]},
                  {"info":{"id":"msg_a0","role":"assistant","parentID":"msg_u1",
                    "error":{"name":"MessageAbortedError"}},
                   "parts":[{"type":"text","text":"died with the abort"}]},
                  {"info":{"id":"msg_a1","role":"assistant","parentID":"msg_u1"},
                   "parts":[{"type":"reasoning","text":"hmm"},{"type":"text","text":"first thing"}]},
                  {"info":{"id":"msg_a2","role":"assistant","parentID":"msg_u1","finish":"tool-calls"},
                   "parts":[{"type":"text","text":"final answer"}]}
                ]
                """;

        assertEquals("first thing\nfinal answer", OpenCodeServeClient
                .extractAssistantTextByParent(objectMapper, messages, "msg_u1"));
        assertEquals("", OpenCodeServeClient
                .extractAssistantTextByParent(objectMapper, messages, "msg_unknown"));
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
    void restTurnSendsAttachedImagesAsFilePartsAfterThePrompt() throws Exception {
        AtomicReference<String> turnBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                for (int i = 0; i < 60; i++) {
                    sse.write(' ');
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
            turnBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = """
                    {"info":{"id":"m-final","role":"assistant","tokens":
                      {"input":7,"output":2,"cache":{"read":0,"write":0}}},
                     "parts":[{"type":"text","text":"a scanned page"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        AttachmentInput image = new AttachmentInput(
                "/tmp/page.png", "image/png", true, "cGFnZQ==", null);
        AttachmentInput notes = new AttachmentInput(
                "/tmp/notes.txt", "text/plain", false, null, "margin notes");
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-9")) {
                assertEquals("a scanned page", client.send("opencode-go/deepseek-v4-pro", null,
                        "system instructions", "what is this", List.of(image, notes), ignored -> { }, null));
            }
            JsonNode parts = objectMapper.readTree(turnBody.get()).path("parts");
            assertEquals(3, parts.size(), parts.toString());
            assertEquals("text", parts.get(0).path("type").asText());
            assertTrue(parts.get(0).path("text").asText().contains("what is this"), parts.toString());
            assertEquals("file", parts.get(1).path("type").asText());
            assertEquals("image/png", parts.get(1).path("mime").asText());
            assertEquals("page.png", parts.get(1).path("filename").asText());
            assertEquals("data:image/png;base64,cGFnZQ==", parts.get(1).path("url").asText());
            assertEquals("[File: /tmp/notes.txt]\nmargin notes", parts.get(2).path("text").asText());
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
    void afterOpenCodeCompactsTheNextPromptCarriesTheMemoryFilesAndSkillsTheSessionUsed(
            @TempDir Path project) throws Exception {
        Path memory = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memory);
        Files.writeString(memory.resolve("build_rule.md"), "Build from the repo root.\n");
        List<String> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch framesWritten = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                if (connections.incrementAndGet() == 1) {
                    String[] frames = {
                            "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                    + "\"sessionID\":\"session-c\",\"part\":{\"type\":\"tool\","
                                    + "\"callID\":\"call-1\",\"tool\":\"kompile_memory\","
                                    + "\"state\":{\"status\":\"completed\","
                                    + "\"input\":{\"action\":\"read\",\"file\":\"build_rule.md\"},"
                                    + "\"output\":\"Build from the repo root.\"}}}}",
                            "data: {\"type\":\"session.compacted\",\"properties\":{\"sessionID\":\"session-c\"}}"};
                    for (String frame : frames) {
                        sse.write((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                        sse.flush();
                    }
                }
                framesWritten.countDown();
                Thread.sleep(4000);
            } catch (IOException | InterruptedException expected) {
                // client stop() closed the stream or the window elapsed
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-c/message", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            prompts.add(body.path("parts").path(0).path("text").asText());
            try {
                if (prompts.size() == 1) {
                    framesWritten.await(5, TimeUnit.SECONDS);
                    Thread.sleep(500); // let the client handle the frames before the turn settles
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = """
                    {"info":{"id":"m"},"parts":[{"type":"text","text":"ok"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, project, HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-c")) {
            client.send("opencode-go/deepseek-v4-pro", null, null,
                    "<skill name=\"fix\">\nFix build or test failures\n</skill>\n\nfix the build", null, null);
            client.send("opencode-go/deepseek-v4-pro", null, null, "continue", null, null);
            client.send("opencode-go/deepseek-v4-pro", null, null, "and then", null, null);
        } finally {
            server.stop(0);
        }

        assertEquals(3, prompts.size(), prompts.toString());
        assertFalse(prompts.get(0).contains("<kompile_compaction_reload>"), prompts.get(0));
        String reloaded = prompts.get(1);
        assertTrue(reloaded.startsWith("<kompile_compaction_reload>"), reloaded);
        assertTrue(reloaded.contains("### project/build_rule.md\nBuild from the repo root."), reloaded);
        assertTrue(reloaded.contains("<skill name=\"fix\">\nFix build or test failures\n</skill>"), reloaded);
        assertTrue(reloaded.endsWith("\n\ncontinue"), reloaded);
        assertEquals("and then", prompts.get(2), "the reload rides only the first prompt after compaction");
    }

    @Test
    void theReloadFileSitsBesideItsLaunchConfigSoTheLaunchSweepClaimsIt() {
        Path config = Path.of("/tmp/mcp-launch/kompile-mcp-opencode-4242-abc.json");
        assertEquals(Path.of("/tmp/mcp-launch/kompile-mcp-opencode-4242-abc-compaction-reload.json"),
                OpenCodeServeClient.compactionReloadFile(config));
    }

    @Test
    void restTurnRoutesReasoningDeltasToTheThinkingLane() throws Exception {
        // OpenCode streams reasoning deltas over the same field="text" wire
        // shape as answer text; only the part's type separates the lanes.
        List<String> streamed = new ArrayList<>();
        List<String> thinking = new ArrayList<>();
        // The POST answer must not race the streamed deltas: respond only once
        // both expected answer deltas reached the consumer. The exchanges wait
        // on each other, so the mock needs a concurrent executor — the default
        // dispatcher serializes them and self-deadlocks on the wait.
        CountDownLatch answerDeltasSeen = new CountDownLatch(2);
        ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(handlers);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                String[] frames = {
                        // Full-part updates register the types: reasoning first.
                        "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                + "\"sessionID\":\"session-think\",\"part\":{\"type\":\"reasoning\","
                                + "\"id\":\"prt-r\",\"text\":\"\"}}}",
                        "data: {\"type\":\"message.part.delta\",\"properties\":{"
                                + "\"sessionID\":\"session-think\",\"field\":\"text\","
                                + "\"partID\":\"prt-r\",\"delta\":\"hmm\"}}",
                        "data: {\"type\":\"message.part.delta\",\"properties\":{"
                                + "\"sessionID\":\"session-think\",\"field\":\"text\","
                                + "\"partID\":\"prt-r\",\"delta\":\" let me see\"}}",
                        // Then the answer part, plus a delta with an unknown
                        // part id (no prior update) that must stay a legacy
                        // answer delta, not be guessed away.
                        "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                + "\"sessionID\":\"session-think\",\"part\":{\"type\":\"text\","
                                + "\"id\":\"prt-t\",\"text\":\"\"}}}",
                        "data: {\"type\":\"message.part.delta\",\"properties\":{"
                                + "\"sessionID\":\"session-think\",\"field\":\"text\","
                                + "\"partID\":\"prt-t\",\"delta\":\"The\"}}",
                        "data: {\"type\":\"message.part.delta\",\"properties\":{"
                                + "\"sessionID\":\"session-think\",\"field\":\"text\","
                                + "\"partID\":\"prt-unknown\",\"delta\":\" answer\"}}",
                        "data: {\"type\":\"message.part.updated\",\"properties\":{"
                                + "\"sessionID\":\"other-session\",\"part\":{\"type\":\"reasoning\","
                                + "\"id\":\"prt-other\",\"text\":\"\"}}}",
                        "data: {\"type\":\"message.part.delta\",\"properties\":{"
                                + "\"sessionID\":\"other-session\",\"field\":\"text\","
                                + "\"partID\":\"prt-other\",\"delta\":\"noise\"}}"
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
        server.createContext("/session/session-think/message", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                answerDeltasSeen.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = """
                    {"info":{"id":"m1"},"parts":[{"type":"text","text":"final"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            try (OpenCodeServeClient client = new OpenCodeServeClient(
                    objectMapper, Path.of("."), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "session-think")) {
                OpenCodeServeClient.ActivityListener listener =
                        new OpenCodeServeClient.ActivityListener() {
                            @Override
                            public void onToolStart(String callId, String name, String input) { }

                            @Override
                            public void onToolComplete(String callId, String name, String output,
                                                       int exitCode, boolean error) { }

                            @Override
                            public void onTokenUsage(long input, long output,
                                                     long cacheRead, long cacheCreation) { }

                        @Override
                        public void onThinking(String reasoningDelta) {
                            thinking.add(reasoningDelta);
                        }
                    };
                String text = client.send("opencode-go/deepseek-v4-pro", null,
                        null, "think then answer", delta -> {
                            streamed.add(delta);
                            answerDeltasSeen.countDown();
                        }, listener);

                assertEquals("final", text);
                // Reasoning deltas never leak into the answer stream.
                assertEquals("The answer", String.join("", streamed));
                assertEquals(List.of("hmm", " let me see"), thinking);
            }
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void restTurnAutoRejectsNativePermissionRequestsAndKeepsTheTurnAlive() throws Exception {
        // OpenCode parks the native turn on an unanswered permission ask (its
        // default action is "ask" for kind/action combos without a matching
        // rule, e.g. reading a directory outside the workspace). Kompile's
        // client is not interactive: it must reject the pending request and
        // let the turn finish instead of hanging until the turn deadline.
        List<String> permissionAsks = new CopyOnWriteArrayList<>();
        AtomicReference<String> replyBody = new AtomicReference<>();
        AtomicInteger replyCalls = new AtomicInteger();
        CountDownLatch replyReceived = new CountDownLatch(1);
        ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(handlers);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                sse.write(("data: {\"type\":\"permission.asked\",\"properties\":{"
                        + "\"sessionID\":\"session-perm\",\"id\":\"per-1\","
                        + "\"permission\":\"external_directory\","
                        + "\"patterns\":[\"/home/x/.kompile/memory/*\"]}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                sse.flush();
                Thread.sleep(2000); // keep the lane open through the turn
            } catch (IOException | InterruptedException expected) {
                // client stop() closed the stream or the window elapsed
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-perm/permissions/per-1", exchange -> {
            replyBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            replyCalls.incrementAndGet();
            replyReceived.countDown();
            byte[] body = "{\"id\":\"per-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/session/session-perm/message", exchange -> {
            // Answer only after the ask was rejected, so the ordering is
            // deterministic: the rejection happened mid-turn.
            exchange.getRequestBody().readAllBytes();
            try {
                replyReceived.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = """
                    {"info":{"id":"m1","role":"assistant","tokens":
                      {"input":6,"output":2,"cache":{"read":0,"write":0}}},
                     "parts":[{"type":"text","text":"unblocked"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-perm")) {
            OpenCodeServeClient.ActivityListener listener =
                    new OpenCodeServeClient.ActivityListener() {
                        @Override
                        public void onToolStart(String callId, String name, String input) { }

                        @Override
                        public void onToolComplete(String callId, String name, String output,
                                                   int exitCode, boolean error) { }

                        @Override
                        public void onTokenUsage(long input, long output,
                                                 long cacheRead, long cacheCreation) { }

                        @Override
                        public void onPermissionRequest(String requestId, String permission,
                                                        List<String> patterns) {
                            permissionAsks.add(requestId + ":" + permission + ":" + patterns);
                        }
                    };

            assertEquals("unblocked", client.send("opencode-go/deepseek-v4-pro", null,
                    null, "read the memory dir", ignored -> { }, listener),
                    "a rejected permission ask must not park the turn");
            assertEquals(List.of("per-1:external_directory:[/home/x/.kompile/memory/*]"),
                    permissionAsks);
            assertEquals(1, replyCalls.get(), "the pending ask is answered exactly once");
            assertEquals("{\"response\":\"reject\"}", replyBody.get(),
                    "kompile's client rejects; provider tools run under grants instead");
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void restTurnAbortsStuckTurnWhenTheProviderReAsksARejectedPermission() throws Exception {
        // With the launch grants in place a rejected ask must never recur; a
        // repeat means the turn is parked in a permission loop, so the client
        // aborts it on the server instead of burning the turn deadline.
        List<String> permissionAsks = new CopyOnWriteArrayList<>();
        AtomicInteger replyCalls = new AtomicInteger();
        AtomicInteger abortCalls = new AtomicInteger();
        ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(handlers);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                String ask = "data: {\"type\":\"permission.asked\",\"properties\":{"
                        + "\"sessionID\":\"session-stuck\",\"id\":\"per-2\","
                        + "\"permission\":\"external_directory\","
                        + "\"patterns\":[\"/home/x/.kompile/memory/*\"]}}\n\n";
                sse.write(ask.getBytes(StandardCharsets.UTF_8));
                sse.flush();
                Thread.sleep(600); // provider: the rejection came, ask again anyway
                sse.write(ask.getBytes(StandardCharsets.UTF_8));
                sse.flush();
                Thread.sleep(4000); // the abort path closes this lane first
            } catch (IOException | InterruptedException expected) {
                // client stop() closed the stream or the window elapsed
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-stuck/permissions/per-2", exchange -> {
            replyCalls.incrementAndGet();
            byte[] body = "{\"id\":\"per-2\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/session/session-stuck/abort", exchange -> {
            abortCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/session/session-stuck/message", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(4000); // the turn never completes; the abort wins
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-stuck")) {
            OpenCodeServeClient.ActivityListener listener =
                    new OpenCodeServeClient.ActivityListener() {
                        @Override
                        public void onToolStart(String callId, String name, String input) { }

                        @Override
                        public void onToolComplete(String callId, String name, String output,
                                                   int exitCode, boolean error) { }

                        @Override
                        public void onTokenUsage(long input, long output,
                                                 long cacheRead, long cacheCreation) { }

                        @Override
                        public void onPermissionRequest(String requestId, String permission,
                                                        List<String> patterns) {
                            permissionAsks.add(requestId);
                        }
                    };

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client.send("opencode-go/deepseek-v4-pro", null,
                            null, "read the memory dir", ignored -> { }, listener));

            assertTrue(failure.getMessage().contains("stuck on permission request"),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("external_directory"),
                    failure.getMessage());
            assertEquals(List.of("per-2", "per-2"), permissionAsks,
                    "both asks reach the activity listener");
            assertEquals(2, replyCalls.get(), "each ask is replied with a rejection");
            assertEquals(1, abortCalls.get(), "the stuck turn is aborted on the server");
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void heartbeatFramesDoNotCountAsTurnProgressForTheIdleWatchdog() throws Exception {
        // server.heartbeat frames mean "socket alive", not "turn progressed".
        // If the event lane kept refreshing lastActivity on them, the idle
        // watchdog could never fire while a turn was genuinely stuck, and the
        // client would silently wait out the full turn deadline instead.
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                for (int i = 0; i < 10; i++) {
                    sse.write(("data: {\"type\":\"server.heartbeat\"}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    sse.flush();
                    Thread.sleep(100);
                }
                Thread.sleep(400); // quiet window after the last heartbeat
            } catch (IOException | InterruptedException expected) {
                // client stop() closed the stream or the window elapsed
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-hb")) {
            Class<?> type = Class.forName(OpenCodeServeClient.class.getName() + "$EventBusListener");
            var constructor = type.getDeclaredConstructor(OpenCodeServeClient.class, String.class,
                    java.util.function.Consumer.class, OpenCodeServeClient.ActivityListener.class);
            constructor.setAccessible(true);
            Object listener = constructor.newInstance(client, "session-hb", null,
                    usageListener(new ArrayList<>(), new CountDownLatch(0)));
            var run = type.getDeclaredMethod("run");
            run.setAccessible(true);
            run.invoke(listener); // consumes the heartbeat window, exits at stream end

            var lastActivity = type.getDeclaredMethod("lastActivityNanos");
            lastActivity.setAccessible(true);
            long lastRefresh = (Long) lastActivity.invoke(listener);
            long silenceMillis = (System.nanoTime() - lastRefresh) / 1_000_000;
            assertTrue(silenceMillis >= 1_000,
                    "a whole window of heartbeats must not advance turn liveness; "
                            + "last refresh was only " + silenceMillis + "ms ago");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void launchPermissionsGrantProviderToolsWithoutInteractiveAsks() throws Exception {
        // opencode's default for an unevaluated permission kind is "ask", which
        // parks a non-interactive client's turn forever. Kompile launches the
        // serve with Claude's --dangerously-skip-permissions parity: allow all,
        // ahead of the permission choices this launch carries.
        try (OpenCodeServeClient client = new OpenCodeServeClient(objectMapper, Path.of("."))) {
            Map<String, String> bare = new HashMap<>();
            client.applyPermissionGrants(bare);
            JsonNode merged = objectMapper.readTree(bare.get("OPENCODE_CONFIG_CONTENT"));
            assertEquals("allow", merged.path("permission").path("*").asText(""),
                    "the launch config must grant provider tools by default");

            Map<String, String> explicit = new HashMap<>();
            explicit.put("OPENCODE_CONFIG_CONTENT",
                    "{\"theme\":\"dark\",\"permission\":{\"bash\":{\"*\":\"ask\"}}}");
            client.applyPermissionGrants(explicit);
            JsonNode granted = objectMapper.readTree(explicit.get("OPENCODE_CONFIG_CONTENT"));
            assertEquals("dark", granted.path("theme").asText(""),
                    "existing launch config must survive the grants pass");
            assertEquals("ask", granted.path("permission").path("bash").path("*").asText(""),
                    "explicit permission choices must own their kinds");
            assertEquals(List.of("*", "bash"), fieldNames(granted.path("permission")),
                    "opencode's last matching rule wins, so the wildcard grant must come first");

            Map<String, String> ownWildcard = new HashMap<>();
            ownWildcard.put("OPENCODE_CONFIG_CONTENT", "{\"permission\":{\"bash\":\"deny\",\"*\":\"ask\"}}");
            client.applyPermissionGrants(ownWildcard);
            assertEquals("{\"permission\":{\"bash\":\"deny\",\"*\":\"ask\"}}",
                    ownWildcard.get("OPENCODE_CONFIG_CONTENT"),
                    "a launch's own wildcard choice must own the launch");
        }
    }

    @Test
    void kompileLaunchKeepsNativeBashDeniedAfterTheWildcardGrant() throws Exception {
        // The server boot injects Kompile's tools first (native bash denied, its shell
        // work routed to Kompile's MCP tools), then grants. Granted before the deny,
        // the wildcard allow would win and re-enable opencode's unmanaged bash tool.
        try (OpenCodeServeClient client = new OpenCodeServeClient(objectMapper, Path.of("."))) {
            Map<String, String> environment = new HashMap<>();
            environment.put("OPENCODE_CONFIG_CONTENT",
                    "{\"mcp\":{\"kompile\":{\"type\":\"local\"}},\"permission\":{\"bash\":\"deny\"}}");
            client.applyPermissionGrants(environment);
            JsonNode content = objectMapper.readTree(environment.get("OPENCODE_CONFIG_CONTENT"));
            assertEquals(List.of("*", "bash"), fieldNames(content.path("permission")));
            assertEquals("deny", content.path("permission").path("bash").asText(""));
            assertEquals("local", content.path("mcp").path("kompile").path("type").asText(""));
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void restTurnReportsEachModelRequestOfTheSessionAsAStep() throws Exception {
        List<Integer> steps = new CopyOnWriteArrayList<>();
        CountDownLatch lastFrameSeen = new CountDownLatch(1);
        CountDownLatch turnOver = new CountDownLatch(1);
        ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(handlers);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream sse = exchange.getResponseBody();
            try {
                for (String frame : List.of(
                        stepPart("session-steps", "{\"type\":\"step-start\",\"id\":\"prt-1\"}"),
                        // OpenCode may send a part again when it updates it.
                        stepPart("session-steps", "{\"type\":\"step-start\",\"id\":\"prt-1\"}"),
                        stepPart("session-steps", "{\"type\":\"step-finish\",\"id\":\"prt-2\",\"reason\":\"tool-calls\"}"),
                        stepPart("session-steps", "{\"type\":\"step-start\",\"id\":\"prt-3\"}"),
                        // A subagent's child session runs steps of its own.
                        stepPart("child-session", "{\"type\":\"step-start\",\"id\":\"prt-9\"}"),
                        "{\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"session-steps\","
                                + "\"info\":{\"role\":\"assistant\",\"tokens\":{\"input\":5,\"output\":1,"
                                + "\"cache\":{\"read\":0,\"write\":0}}}}}")) {
                    sse.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                sse.flush();
                turnOver.await(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException expected) {
                // The client closed the stream when the turn ended.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-steps/message", exchange -> {
            try {
                // Answer once the event lane delivered its last frame.
                lastFrameSeen.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = """
                    {"info":{"id":"m1"},"parts":[{"type":"text","text":"stepped"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-steps")) {
            OpenCodeServeClient.ActivityListener listener = new OpenCodeServeClient.ActivityListener() {
                @Override
                public void onToolStart(String callId, String name, String input) { }

                @Override
                public void onToolComplete(String callId, String name, String output,
                                           int exitCode, boolean error) { }

                @Override
                public void onTokenUsage(long input, long output, long cacheRead, long cacheCreation) {
                    lastFrameSeen.countDown();
                }

                @Override
                public void onSteps(int count) {
                    steps.add(count);
                }
            };

            assertEquals("stepped", client.send("opencode-go/deepseek-v4-pro", null,
                    null, "step twice", ignored -> { }, listener));
            assertEquals(List.of(1, 1), steps, "one step per step-start part of this session");
        } finally {
            turnOver.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    private static String stepPart(String sessionId, String part) {
        // Native OpenCode nests the session id inside the part, not properties.
        return "{\"type\":\"message.part.updated\",\"properties\":{\"part\":{\"sessionID\":\""
                + sessionId + "\"," + part.substring(1) + "}}";
    }

    @Test
    void usageSnapshotsDeduplicateByMessageAndReconcileFinalRestResponse() throws Exception {
        List<String> usage = new CopyOnWriteArrayList<>();
        CountDownLatch messagesSeen = new CountDownLatch(3);
        CountDownLatch turnOver = new CountDownLatch(1);
        ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(handlers);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                for (String info : List.of(
                        usageInfo("other-session", "other", 999, 999),
                        usageInfo("session-usage", "m1", 20, 75),
                        usageInfo("session-usage", "m1", 20, 75), // repeated snapshot
                        usageInfo("session-usage", "m1", 30, 80), // larger snapshot
                        usageInfo("session-usage", "m1", 20, 75), // stale snapshot
                        usageInfo("session-usage", "m2", 20, 75))) { // distinct request, same counts
                    String frame = "data: {\"type\":\"message.updated\",\"properties\":{\"info\":" + info + "}}\n\n";
                    exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));
                }
                exchange.getResponseBody().flush();
                turnOver.await(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException expected) {
                // Client closed the SSE lane when the turn ended.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/session/session-usage/message", exchange -> {
            try {
                messagesSeen.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String response = "{\"info\":" + usageInfo("session-usage", "m2", 20, 100)
                    + ",\"parts\":[{\"type\":\"text\",\"text\":\"done\"}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-usage")) {
            assertEquals("done", client.send("opencode-go/deepseek-v4-pro", null, null,
                    "hello", ignored -> { }, usageListener(usage, messagesSeen)));
            assertEquals(List.of("20:75:1000:200", "10:5:0:0", "20:75:1000:200", "0:25:0:0"), usage);
        } finally {
            turnOver.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void finalRestUsageSurvivesUnavailableEventBus() throws Exception {
        List<String> usage = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/session/session-rest/message", exchange -> {
            String response = "{\"info\":" + usageInfo("session-rest", "m1", 20, 75)
                    + ",\"parts\":[{\"type\":\"text\",\"text\":\"done\"}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "session-rest")) {
            assertEquals("done", client.send("opencode-go/deepseek-v4-pro", null, null,
                    "hello", ignored -> { }, usageListener(usage, new CountDownLatch(0))));
            assertEquals(List.of("20:75:1000:200"), usage);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stoppedEventSubscriptionCannotAttributeLateUsageToANewerTurn() throws Exception {
        List<String> usage = new CopyOnWriteArrayList<>();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:1", "session-late")) {
            Class<?> type = Class.forName(OpenCodeServeClient.class.getName() + "$EventBusListener");
            var constructor = type.getDeclaredConstructor(OpenCodeServeClient.class, String.class,
                    java.util.function.Consumer.class, OpenCodeServeClient.ActivityListener.class);
            constructor.setAccessible(true);
            Object listener = constructor.newInstance(client, "session-late", null,
                    usageListener(usage, new CountDownLatch(0)));
            var handle = type.getDeclaredMethod("handleEvent", String.class);
            handle.setAccessible(true);
            var stop = type.getDeclaredMethod("stop");
            stop.setAccessible(true);
            String event = "{\"type\":\"message.updated\",\"properties\":{\"info\":"
                    + usageInfo("session-late", "m1", 20, 75) + "}}";
            handle.invoke(listener, event);
            stop.invoke(listener); // also models stopping before HTTP connection completes
            handle.invoke(listener, event.replace("m1", "m2"));
            assertEquals(List.of("20:75:1000:200"), usage);
        }
    }

    @Test
    void contextUsesLatestRequestWithMergedCacheNotTurnTotalsOrLateEarlierMessages() throws Exception {
        List<Long> contexts = new CopyOnWriteArrayList<>();
        List<String> usage = new CopyOnWriteArrayList<>();
        try (OpenCodeServeClient client = new OpenCodeServeClient(
                objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:1", "context-session")) {
            var activity = new OpenCodeServeClient.ActivityListener() {
                public void onToolStart(String id, String name, String input) { }
                public void onToolComplete(String id, String name, String out, int exit, boolean error) { }
                public void onTokenUsage(long input, long output, long read, long write) {
                    usage.add(input + ":" + output + ":" + read + ":" + write);
                }
                public void onContextUsage(long input) { contexts.add(input); }
            };
            Class<?> type = Class.forName(OpenCodeServeClient.class.getName() + "$EventBusListener");
            var constructor = type.getDeclaredConstructor(OpenCodeServeClient.class, String.class,
                    java.util.function.Consumer.class, OpenCodeServeClient.ActivityListener.class);
            constructor.setAccessible(true);
            Object listener = constructor.newInstance(client, "context-session", null, activity);
            var handle = type.getDeclaredMethod("handleTokens", com.fasterxml.jackson.databind.JsonNode.class);
            handle.setAccessible(true);
            handle.invoke(listener, objectMapper.readTree(usageInfo("context-session", "m1", 100, 20)));
            handle.invoke(listener, objectMapper.readTree(usageInfo("context-session", "m2", 200, 30)));
            // An output-only partial snapshot must preserve the measured cached prefix.
            handle.invoke(listener, objectMapper.readTree("""
                    {"sessionID":"context-session","id":"m2","role":"assistant","tokens":{"output":40}}
                    """));
            handle.invoke(listener, objectMapper.readTree(usageInfo("context-session", "m1", 150, 50)));
            handle.invoke(listener, objectMapper.readTree(usageInfo("other-session", "m3", 9000, 50)));
            assertEquals(List.of(1300L, 1400L, 1400L), contexts);
            assertEquals(List.of("100:20:1000:200", "200:30:1000:200", "0:10:0:0", "50:30:0:0"), usage);
            var stop = type.getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(listener);
        }
    }

    private static String usageInfo(String session, String id, long input, long output) {
        return """
                {"sessionID":"%s","id":"%s","role":"assistant",
                 "tokens":{"input":%d,"output":%d,"reasoning":50,"cache":{"read":1000,"write":200}}}
                """.formatted(session, id, input, output).strip().replace("\n", "");
    }

    private static OpenCodeServeClient.ActivityListener usageListener(List<String> usage, CountDownLatch seen) {
        return new OpenCodeServeClient.ActivityListener() {
            @Override
            public void onToolStart(String callId, String name, String input) { }

            @Override
            public void onToolComplete(String callId, String name, String output, int exitCode, boolean error) { }

            @Override
            public void onTokenUsage(long input, long output, long read, long write) {
                usage.add(input + ":" + output + ":" + read + ":" + write);
                seen.countDown();
            }
        };
    }

    @Test
    void cancellingATurnAbortsItOnTheServer() throws Exception {
        // Dropping only the HTTP request left OpenCode running the turn and its
        // tools; the cancel must reach the server's abort endpoint.
        ExecutorService turnThread = Executors.newSingleThreadExecutor();
        try (HangingOpenCodeServer server = new HangingOpenCodeServer("session-cancel");
             OpenCodeServeClient client = server.client(objectMapper)) {
            AtomicBoolean cancelled = new AtomicBoolean();
            client.setCancellationCheck(cancelled::get);
            Future<String> turn = turnThread.submit(() -> client.send(
                    "opencode-go/deepseek-v4-pro", null, null, "run a long tool", ignored -> { }));
            assertTrue(server.turnStarted.await(10, TimeUnit.SECONDS),
                    "the turn never reached the server");

            cancelled.set(true);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> turn.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(1, server.abortCalls.get(),
                    "a cancelled turn must be aborted on the server");
        } finally {
            turnThread.shutdownNow();
        }
    }

    @Test
    void interruptingATurnAbortsItOnTheServer() throws Exception {
        ExecutorService turnThread = Executors.newSingleThreadExecutor();
        try (HangingOpenCodeServer server = new HangingOpenCodeServer("session-interrupt");
             OpenCodeServeClient client = server.client(objectMapper)) {
            Future<String> turn = turnThread.submit(() -> client.send(
                    "opencode-go/deepseek-v4-pro", null, null, "run a long tool", ignored -> { }));
            assertTrue(server.turnStarted.await(10, TimeUnit.SECONDS),
                    "the turn never reached the server");

            turn.cancel(true);

            assertTrue(server.aborted.await(10, TimeUnit.SECONDS),
                    "an interrupted turn must be aborted on the server");
        } finally {
            turnThread.shutdownNow();
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
