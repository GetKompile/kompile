package ai.kompile.cli.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteCommandTest {
    private static final String KEY = "fixture-private-key";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService workers;
    private final CountDownLatch release = new CountDownLatch(1);
    @TempDir Path temporary;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.start();
    }
    @AfterEach void stop() throws Exception {
        release.countDown(); server.stop(0); workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void postsPreparedRequestAndStreamsTextAndOrderedUsage() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> authentication = new AtomicReference<>();
        server.createContext("/ok/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            authentication.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("POST", exchange.getRequestMethod());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"remote-✓\"}}],\"usage\":{\"prompt_tokens\":2}}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        Result result = run("ok", "--prompt", "hello", "--system", "be precise");
        assertEquals(0, result.code(), result.output() + result.error());
        JsonNode parsed = mapper.readTree(result.output());
        assertEquals("remote-✓", parsed.path("text").asText());
        assertEquals("completed", parsed.path("status").asText());
        assertTrue(parsed.path("terminalEventSeen").asBoolean());
        assertEquals(2, parsed.path("usage").size());
        assertEquals(2, parsed.path("usage").path(0).path("prompt_tokens").asInt());
        assertEquals("Bearer " + KEY, authentication.get());
        assertEquals("fixture", request.get().path("model").asText());
        assertTrue(request.get().path("stream").asBoolean());
        assertTrue(request.get().path("stream_options").path("include_usage").asBoolean());
        assertEquals("system", request.get().path("messages").path(0).path("role").asText());
        assertEquals("hello", request.get().path("messages").path(1).path("content").asText());
        assertFalse(result.output().contains(KEY));
    }

    @Test void returnsCorrelatedToolEnvelopeWithoutExecutingAnything() throws Exception {
        Path artifact = temporary.resolve("must-not-exist");
        Path tools = temporary.resolve("tools.json");
        Files.writeString(tools, "[{\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"parameters\":{\"type\":\"object\"}}}]");
        server.createContext("/tools/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            assertEquals("write_file", request.path("tools").path(0).path("function").path("name").asText());
            String args = mapper.writeValueAsString(mapper.createObjectNode().put("path", artifact.toString()));
            var chunk = mapper.createObjectNode();
            var delta = chunk.putArray("choices").addObject().putObject("delta");
            delta.putArray("tool_calls").addObject().put("index", 0).put("id", "call-7")
                    .putObject("function").put("name", "write_file").put("arguments", args);
            byte[] body = ("data: " + chunk + "\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        Result result = run("tools", "--prompt", "write", "--tools-file", tools.toString());
        assertEquals(0, result.code(), result.output() + result.error());
        JsonNode parsed = mapper.readTree(result.output());
        assertFalse(parsed.path("toolsExecuted").asBoolean(true));
        assertEquals("call-7", parsed.path("toolCalls").path(0).path("id").asText());
        assertEquals(artifact.toString(), parsed.path("toolCalls").path(0).path("arguments").path("path").asText());
        assertFalse(Files.exists(artifact));
    }

    @Test void deadlineCancelsBeforeHeaders() throws Exception {
        server.createContext("/headers/chat/completions", exchange -> {
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        long started = System.nanoTime();
        Result result = run("headers", "--prompt", "wait", "--timeout-ms", "200");
        assertEquals(130, result.code(), result.output() + result.error());
        assertEquals("cancelled", mapper.readTree(result.output()).path("status").asText());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5);
    }

    @Test void deadlineClosesBlockedResponseBody() throws Exception {
        server.createContext("/body/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        long started = System.nanoTime();
        Result result = run("body", "--prompt", "wait", "--timeout-ms", "400");
        assertEquals(130, result.code(), result.output() + result.error());
        assertTrue(mapper.readTree(result.output()).path("cancelled").asBoolean());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5);
    }

    @Test void httpFailureRedactsCredentialsAndDoesNotReportCompletion() throws Exception {
        server.createContext("/failure/chat/completions", exchange -> {
            byte[] body = ("{\"error\":\"denied " + KEY + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        Result result = run("failure", "--prompt", "hello");
        assertEquals(1, result.code());
        assertEquals("failed", mapper.readTree(result.output()).path("status").asText());
        assertFalse(result.output().contains(KEY)); assertFalse(result.error().contains(KEY));
    }

    @Test void unsupportedLocalProviderFailsBeforeNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/unused/chat/completions", exchange -> { requests.incrementAndGet(); exchange.close(); });
        Result result = run("unused", "--prompt", "hello", "--provider", "kompile-local");
        assertEquals(2, result.code());
        assertTrue(result.output().contains("Unsupported provider"));
        assertEquals(0, requests.get());
    }

    @Test void rejectsNonArrayPreparedMessagesAndDoesNotInventAgentExec() throws Exception {
        Path messages = temporary.resolve("messages.json"); Files.writeString(messages, "{}");
        assertEquals(2, run("unused", "--messages-file", messages.toString()).code());
        var command = RemoteCommand.commandLine(name -> KEY);
        command.setErr(new PrintWriter(new StringWriter()));
        assertEquals(2, command.execute("exec"));
    }

    @Test void preparedToolResultMessagesRemainCallerOwnedWireData() throws Exception {
        Path messages = temporary.resolve("messages.json");
        Files.writeString(messages, "[{\"role\":\"tool\",\"tool_call_id\":\"existing-17\",\"content\":\"caller-owned\"}]");
        server.createContext("/prepared/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            assertEquals("existing-17", request.path("messages").path(0).path("tool_call_id").asText());
            assertEquals("caller-owned", request.path("messages").path(0).path("content").asText());
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"consumed\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        Result result = run("prepared", "--messages-file", messages.toString());
        assertEquals(0, result.code(), result.output() + result.error());
        assertEquals("consumed", mapper.readTree(result.output()).path("text").asText());
    }

    @Test void partialEofDoesNotReportSuccessfulCompletion() throws Exception {
        server.createContext("/eof/chat/completions", exchange -> {
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        Result result = run("eof", "--prompt", "hello");
        assertEquals(1, result.code());
        assertEquals("failed", mapper.readTree(result.output()).path("status").asText());
    }

    @Test void rejectsPlaintextRemoteEndpointBeforeCredentialOrNetworkUse() throws Exception {
        AtomicInteger credentials = new AtomicInteger();
        var command = RemoteCommand.commandLine(name -> { credentials.incrementAndGet(); return KEY; });
        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.getSubcommands().get("request").setOut(new PrintWriter(output));
        assertEquals(2, command.execute("request", "--base-url", "http://example.invalid/v1", "--model", "fixture", "--prompt", "hello", "--json"));
        assertTrue(output.toString().contains("HTTPS"));
        assertEquals(0, credentials.get());
    }

    @Test void runtimeClasspathDoesNotContainHostOrModelImplementations() {
        for (String name : List.of("ai.kompile.cli.main.MainCommand", "ai.kompile.cli.main.chat.agent.AgenticChatLoop",
                "ai.kompile.cli.main.chat.LocalServingRuntimePool", "ai.kompile.cli.common.util.JsonUtils",
                "ai.kompile.core.llm.StructuredChatLanguageModel", "ai.kompile.graph.reasoning.unified.UnifiedGraph")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name, false, getClass().getClassLoader()), name);
        }
    }

    private Result run(String route, String... options) {
        var command = RemoteCommand.commandLine(name -> KEY);
        StringWriter output = new StringWriter(); StringWriter error = new StringWriter();
        command.setOut(new PrintWriter(output)); command.setErr(new PrintWriter(error));
        command.getSubcommands().get("request").setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
        var arguments = new ArrayList<>(List.of("request", "--base-url", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/" + route, "--model", "fixture", "--json"));
        arguments.addAll(List.of(options));
        return new Result(command.execute(arguments.toArray(String[]::new)), output.toString(), error.toString());
    }
    private record Result(int code, String output, String error) { }
}
