package ai.kompile.cli.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in qualification of the production request CLI, not a substitute fixture main.
 * Package first, then run surefire:test -Dtest=RemoteNativeImageTest
 * -Dkompile.nativeImageExecutable=/absolute/path/to/native-image.
 * Only loopback HTTP is exercised: no model, agent loop, real tool runner, full host,
 * security-policy or HTTPS/trust-store qualification is implied.
 */
@EnabledIfSystemProperty(named = "kompile.nativeImageExecutable", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
class RemoteNativeImageTest {
    private static final String MAIN = "ai.kompile.cli.remote.RemoteCommand";
    private static final String KEY_ENV = "KOMPILE_REMOTE_FIXTURE_KEY";
    private static final String KEY = "native-cli-fixture-private-key";
    private static final String API_PATH = "/api/fixture/chat/completions";
    private static final long MAX_LOG_BYTES = 8L * 1024 * 1024;
    private static final Duration CLI_LIMIT = Duration.ofSeconds(15);
    private static final int DEADLINE_MILLIS = 1200;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void productionShadedJarAndNativeImageHonorTheRequestContract() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path classes = basedir.resolve("target/classes");
        Path jar = basedir.resolve("target/kompile-cli-remote-0.1.0-SNAPSHOT-exec.jar");
        Path runtimeFile = basedir.resolve("target/native-runtime-classpath.txt");
        assertTrue(Files.isRegularFile(classes.resolve(MAIN.replace('.', '/') + ".class")),
                "Package the production RemoteCommand before opting in");
        assertTrue(Files.isRegularFile(jar), "Required production shaded executable: " + jar);
        assertTrue(Files.isRegularFile(runtimeFile),
                "Package must generate target/native-runtime-classpath.txt with the runtime-scoped dependency goal");
        String runtime = Files.readString(runtimeFile, StandardCharsets.UTF_8).trim();
        assertRuntimeBoundary(runtime, basedir);
        try (JarFile packaged = new JarFile(jar.toFile())) {
            assertNotNull(packaged.getManifest(), "Production executable must have its own manifest");
            assertEquals(MAIN, packaged.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(packaged.getJarEntry(MAIN.replace('.', '/') + ".class"));
        }

        Path nativeImage = Path.of(System.getProperty("kompile.nativeImageExecutable")).toAbsolutePath();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        assertTrue(Files.isExecutable(nativeImage), "Provided native-image is not executable: " + nativeImage);
        assertTrue(Files.isExecutable(java), "Required java.home/bin/java: " + java);
        Path output = Files.createDirectories(basedir.resolve("target/native-cli-smoke"));
        // Keep each run's complete logs and prepared wire inputs, especially on assertion/build failure.
        Path evidence = Files.createTempDirectory(output, "run-");
        Path binary = output.resolve("kompile-remote");
        ProcessResult build = run(basedir, List.of(nativeImage.toString(), "--no-fallback", "-O0",
                "-J-Xmx2048m", "-H:NumberOfThreads=2", "-cp", classes + File.pathSeparator + runtime,
                "-o", binary.toString(), MAIN), evidence, "build", Duration.ofSeconds(240));
        assertEquals(0, build.code(), build::diagnostics);
        assertTrue(Files.isExecutable(binary), build::diagnostics);

        // Deliberately one test and sequential calls: builder, shaded JAR, native binary never overlap.
        for (Launcher launcher : List.of(new Launcher("jar", List.of(java.toString(), "-jar", jar.toString())),
                new Launcher("native", List.of(binary.toString())))) {
            verifyPrompt(basedir, evidence, launcher);
            verifyPreparedMessagesAndToolEnvelope(basedir, evidence, launcher);
            for (Reply reply : List.of(Reply.HTTP_ERROR, Reply.PROTOCOL_ERROR, Reply.REFUSAL,
                    Reply.TRUNCATED, Reply.PREMATURE_EOF)) {
                verifyFailure(basedir, evidence, launcher, reply);
            }
            verifyUnsupportedProviders(basedir, evidence, launcher);
            verifyDeadline(basedir, evidence, launcher, Reply.BLOCK_HEADERS);
            verifyDeadline(basedir, evidence, launcher, Reply.BLOCK_BODY);
        }
    }

    private void assertRuntimeBoundary(String runtime, Path basedir) {
        assertFalse(runtime.isBlank(), "Runtime-scoped dependency classpath must not be empty");
        for (String entry : runtime.split(Pattern.quote(File.pathSeparator), -1)) {
            assertFalse(entry.isBlank(), "Empty runtime entry would implicitly include the working directory");
            String path = entry.replace('\\', '/').toLowerCase(Locale.ROOT);
            for (String excluded : List.of("/kompile-cli-main", "/kompile-cli-common", "/kompile-utils",
                    "/kompile-app-", "/kompile-graph", "/kompile-model", "/kompile-core",
                    "/org/eclipse/deeplearning4j/", "/deeplearning4j-", "/nd4j-", "/datavec-",
                    "/test-classes", "/junit-", "/junit/")) {
                assertFalse(path.contains(excluded), "Host/model/test dependency leaked into native runtime: " + entry);
            }
            Path dependency = Path.of(entry);
            if (!dependency.isAbsolute()) dependency = basedir.resolve(dependency);
            assertTrue(Files.isRegularFile(dependency), "Missing runtime dependency: " + dependency);
        }
    }

    private void verifyPrompt(Path basedir, Path evidence, Launcher launcher) throws Exception {
        try (Fixture fixture = new Fixture(Reply.TEXT, null)) {
            ProcessResult child = request(basedir, evidence, launcher, "prompt", fixture, 5000,
                    "--prompt", "hello-世界", "--system", "be precise");
            JsonNode result = result(child, 0, "completed", false, true);
            assertEquals("remote-✓-世界", result.path("text").asText(), child::diagnostics);
            assertTrue(result.path("toolCalls").isEmpty(), child::diagnostics);
            assertEquals(mapper.readTree("[{\"prompt_tokens\":2},{\"completion_tokens\":3}]"),
                    result.path("usage"), child::diagnostics);
            JsonNode sent = fixture.assertRequest();
            assertEquals(mapper.readTree("[{\"role\":\"system\",\"content\":\"be precise\"},"
                    + "{\"role\":\"user\",\"content\":\"hello-世界\"}]"), sent.path("messages"));
        }
    }

    private void verifyPreparedMessagesAndToolEnvelope(Path basedir, Path evidence, Launcher launcher) throws Exception {
        Path marker = evidence.resolve(launcher.name() + "-tool-must-not-run");
        JsonNode messages = mapper.readTree("[{\"role\":\"system\",\"content\":\"wire history\"},"
                + "{\"role\":\"user\",\"content\":\"prepared-世界\"}]");
        JsonNode tools = mapper.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"fixture_write\","
                + "\"description\":\"A wire definition only; no runner is installed\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}}]");
        Path messagesFile = evidence.resolve(launcher.name() + "-messages.json");
        Path toolsFile = evidence.resolve(launcher.name() + "-tools.json");
        Files.writeString(messagesFile, mapper.writeValueAsString(messages), StandardCharsets.UTF_8);
        Files.writeString(toolsFile, mapper.writeValueAsString(tools), StandardCharsets.UTF_8);
        JsonNode arguments = mapper.createObjectNode().put("path", marker.toString()).put("text", "tool-✓");
        try (Fixture fixture = new Fixture(Reply.TOOLS, arguments)) {
            ProcessResult child = request(basedir, evidence, launcher, "prepared-tools", fixture, 5000,
                    "--messages-file", messagesFile.toString(), "--tools-file", toolsFile.toString());
            JsonNode result = result(child, 0, "completed", false, true);
            assertEquals("", result.path("text").asText(), child::diagnostics);
            assertEquals(1, result.path("toolCalls").size(), child::diagnostics);
            JsonNode call = result.path("toolCalls").get(0);
            assertEquals("call-fixture-7", call.path("id").asText(), child::diagnostics);
            assertEquals("fixture_write", call.path("name").asText(), child::diagnostics);
            assertEquals(arguments, call.path("arguments"), child::diagnostics);
            assertTrue(Files.notExists(marker), "Wire tool envelope unexpectedly created " + marker);
            JsonNode sent = fixture.assertRequest();
            assertEquals(messages, sent.path("messages"));
            assertEquals(tools, sent.path("tools"));
        }
    }

    private void verifyFailure(Path basedir, Path evidence, Launcher launcher, Reply reply) throws Exception {
        try (Fixture fixture = new Fixture(reply, null)) {
            ProcessResult child = request(basedir, evidence, launcher, reply.name().toLowerCase(Locale.ROOT),
                    fixture, 5000, "--prompt", "failure fixture");
            boolean terminal = reply == Reply.REFUSAL || reply == Reply.TRUNCATED;
            JsonNode result = result(child, 1, "failed", false, terminal);
            assertTrue(result.path("error").isTextual(), child::diagnostics);
            assertFalse(result.path("error").asText().isBlank(), child::diagnostics);
            assertEquals(reply == Reply.REFUSAL, result.path("refusal").asBoolean(), child::diagnostics);
            assertEquals(reply == Reply.TRUNCATED, result.path("truncated").asBoolean(), child::diagnostics);
            assertEquals(switch (reply) {
                case REFUSAL -> "fixture refusal";
                case TRUNCATED, PREMATURE_EOF -> "partial-✓";
                default -> "";
            }, result.path("text").asText(), child::diagnostics);
            if (reply == Reply.HTTP_ERROR) {
                assertTrue(result.path("error").asText().contains("HTTP 401"), child::diagnostics);
                assertTrue(result.path("error").asText().contains("[redacted]"), child::diagnostics);
            }
            if (reply == Reply.PROTOCOL_ERROR) {
                assertTrue(result.path("error").asText().contains("fixture protocol error"), child::diagnostics);
            }
            fixture.assertRequest();
        }
    }

    private void verifyUnsupportedProviders(Path basedir, Path evidence, Launcher launcher) throws Exception {
        try (Fixture fixture = new Fixture(Reply.TEXT, null)) {
            for (String provider : List.of("kompile-local", "openai")) {
                ProcessResult child = request(basedir, evidence, launcher, "reject-" + provider, fixture, 5000,
                        "--prompt", "must not send", "--provider", provider);
                JsonNode result = result(child, 2, "failed", false, false);
                assertTrue(result.path("error").asText().contains("Unsupported provider"), child::diagnostics);
            }
            assertEquals(0, fixture.requests.get(), "Unsupported providers must fail before contacting the fixture");
            fixture.assertHealthy();
        }
    }

    private void verifyDeadline(Path basedir, Path evidence, Launcher launcher, Reply reply) throws Exception {
        try (Fixture fixture = new Fixture(reply, null)) {
            ProcessResult child = request(basedir, evidence, launcher, reply.name().toLowerCase(Locale.ROOT),
                    fixture, DEADLINE_MILLIS, "--prompt", "wait for deadline");
            JsonNode result = result(child, 130, "cancelled", true, false);
            assertTrue(child.elapsed().compareTo(Duration.ofSeconds(10)) < 0,
                    () -> "Deadline did not exit within 10s (fixture blocks for 20s): " + child.diagnostics());
            fixture.assertRequest();
            assertTrue(fixture.blocked.await(1, TimeUnit.SECONDS), "Fixture never reached its blocked I/O stage");
            assertEquals(reply == Reply.BLOCK_BODY ? "partial-✓" : "", result.path("text").asText(),
                    child::diagnostics);
        }
    }

    private ProcessResult request(Path basedir, Path evidence, Launcher launcher, String label, Fixture fixture,
                                  int timeoutMillis, String... options) throws Exception {
        List<String> command = new ArrayList<>(launcher.command());
        command.addAll(List.of("request", "--base-url", fixture.apiRoot(), "--model", "fixture",
                "--api-key-env", KEY_ENV, "--timeout-ms", Integer.toString(timeoutMillis), "--json"));
        command.addAll(List.of(options));
        return run(basedir, command, evidence, launcher.name() + "-" + label, CLI_LIMIT);
    }

    private JsonNode result(ProcessResult child, int code, String status, boolean cancelled, boolean terminal)
            throws Exception {
        assertEquals(code, child.code(), child::diagnostics);
        String stdout = Files.readString(child.stdout(), StandardCharsets.UTF_8);
        String stderr = Files.readString(child.stderr(), StandardCharsets.UTF_8);
        assertFalse(stdout.contains(KEY), "Credential leaked to stdout: " + child.stdout());
        assertFalse(stderr.contains(KEY), "Credential leaked to stderr: " + child.stderr());
        JsonNode result = mapper.readTree(stdout);
        assertNotNull(result, child::diagnostics);
        assertTrue(result.isObject(), child::diagnostics);
        assertEquals(status, result.path("status").asText(), child::diagnostics);
        assertTrue(result.path("text").isTextual(), child::diagnostics);
        assertTrue(result.path("toolCalls").isArray(), child::diagnostics);
        assertTrue(result.path("usage").isArray(), child::diagnostics);
        assertTrue(result.path("toolsExecuted").isBoolean(), child::diagnostics);
        assertFalse(result.path("toolsExecuted").asBoolean(), child::diagnostics);
        assertTrue(result.path("cancelled").isBoolean(), child::diagnostics);
        assertEquals(cancelled, result.path("cancelled").asBoolean(), child::diagnostics);
        assertTrue(result.path("terminalEventSeen").isBoolean(), child::diagnostics);
        assertEquals(terminal, result.path("terminalEventSeen").asBoolean(), child::diagnostics);
        return result;
    }

    private static ProcessResult run(Path basedir, List<String> command, Path evidence, String label,
                                     Duration limit) throws Exception {
        Path stdout = evidence.resolve(label + ".stdout.log");
        Path stderr = evidence.resolve(label + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(basedir.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().put(KEY_ENV, KEY);
        // No inherited Java launch options/proxies may turn this loopback smoke into an external request.
        for (String option : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
            builder.environment().remove(option);
        }
        long started = System.nanoTime();
        Process child = builder.start();
        Set<ProcessHandle> descendants = new HashSet<>();
        try {
            child.getOutputStream().close();
            while (true) {
                child.descendants().forEach(descendants::add);
                assertLogBound(stdout);
                assertLogBound(stderr);
                long remaining = limit.toNanos() - (System.nanoTime() - started);
                assertTrue(remaining > 0, () -> "Owned process exceeded " + limit + "; logs retained in "
                        + evidence + "\n" + excerpt(stdout) + "\n" + excerpt(stderr));
                if (child.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) break;
            }
            assertLogBound(stdout);
            assertLogBound(stderr);
            return new ProcessResult(child.exitValue(), stdout, stderr, Duration.ofNanos(System.nanoTime() - started));
        } finally {
            // Never kill by name: terminate only this invocation and its own descendants.
            child.descendants().forEach(descendants::add);
            descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            if (child.isAlive()) child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Owned process did not terminate; logs: " + evidence);
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (ProcessHandle descendant : descendants) {
                while (descendant.isAlive() && System.nanoTime() < cleanupDeadline) {
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                assertFalse(descendant.isAlive(), "Owned descendant did not terminate: " + descendant.pid());
            }
        }
    }

    private static void assertLogBound(Path file) throws IOException {
        assertTrue(!Files.exists(file) || Files.size(file) <= MAX_LOG_BYTES,
                "Owned process exceeded 8 MiB log bound; retained: " + file);
    }

    private static String excerpt(Path file) {
        try (var input = Files.newInputStream(file)) {
            return file + "\n" + new String(input.readNBytes(16 * 1024), StandardCharsets.UTF_8).replace(KEY, "[redacted]");
        } catch (IOException failure) {
            return file + ": " + failure;
        }
    }

    private record Launcher(String name, List<String> command) { }
    private record ProcessResult(int code, Path stdout, Path stderr, Duration elapsed) {
        String diagnostics() { return "Exit " + code + " after " + elapsed + "\n" + excerpt(stdout) + "\n" + excerpt(stderr); }
    }
    private enum Reply { TEXT, TOOLS, HTTP_ERROR, PROTOCOL_ERROR, REFUSAL, TRUNCATED, PREMATURE_EOF,
        BLOCK_HEADERS, BLOCK_BODY }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers;
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<JsonNode> body = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();

        Fixture(Reply reply, JsonNode arguments) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            workers = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "remote-native-http-fixture");
                thread.setDaemon(true);
                return thread;
            });
            try {
                server.setExecutor(workers);
                server.createContext("/", exchange -> serve(exchange, reply, arguments));
                server.start();
            } catch (RuntimeException | Error startFailure) {
                server.stop(0);
                workers.shutdownNow();
                throw startFailure;
            }
        }

        String apiRoot() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/fixture/"; }

        private void serve(HttpExchange exchange, Reply reply, JsonNode arguments) {
            try {
                requests.incrementAndGet();
                method.set(exchange.getRequestMethod());
                path.set(exchange.getRequestURI().toString());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] request = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
                if (request.length > 64 * 1024) throw new IOException("Fixture request exceeds 64 KiB");
                body.set(mapper.readTree(request));
                received.countDown();
                if (reply == Reply.BLOCK_HEADERS) {
                    block();
                    return;
                }
                if (reply == Reply.HTTP_ERROR) {
                    byte[] denied = ("{\"error\":\"fixture denied " + KEY + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(401, denied.length);
                    try (var output = exchange.getResponseBody()) { output.write(denied); }
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                if (reply == Reply.BLOCK_BODY) {
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        output.write(event("{\"choices\":[{\"delta\":{\"content\":\"partial-✓\"}}]}")
                                .getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        block();
                    }
                } else {
                    byte[] payload = response(reply, arguments).getBytes(StandardCharsets.UTF_8);
                    // Fixed length avoids a trailing chunk write racing a client that stops at [DONE].
                    exchange.sendResponseHeaders(200, payload.length);
                    try (var output = exchange.getResponseBody()) {
                        int split = 0;
                        while (split < payload.length && payload[split] >= 0) split++;
                        if (split < payload.length) {
                            // Flush the first byte of a multibyte UTF-8 character separately.
                            output.write(payload, 0, ++split);
                            output.flush();
                            output.write(payload, split, payload.length - split);
                        } else output.write(payload);
                    }
                }
            } catch (Throwable problem) {
                // close() may interrupt/close an intentionally stalled exchange after child cancellation.
                if (release.getCount() != 0) failure.compareAndSet(null, problem);
            } finally {
                received.countDown();
                exchange.close();
            }
        }

        private void block() throws InterruptedException {
            blocked.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, new AssertionError("Fixture block expired instead of child cancellation"));
            }
        }

        JsonNode assertRequest() throws InterruptedException {
            assertTrue(received.await(1, TimeUnit.SECONDS), "Fixture received no request");
            assertHealthy();
            assertEquals(1, requests.get(), "Exactly one request expected; no retries/agent loop");
            assertEquals("POST", method.get());
            assertEquals(API_PATH, path.get(), "CLI must append /chat/completions to the API root");
            assertEquals("Bearer " + KEY, authorization.get());
            assertNotNull(contentType.get());
            assertTrue(contentType.get().startsWith("application/json"));
            JsonNode sent = body.get();
            assertNotNull(sent);
            assertEquals("fixture", sent.path("model").asText());
            assertTrue(sent.path("messages").isArray());
            assertTrue(sent.path("stream").asBoolean());
            assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
            return sent;
        }

        void assertHealthy() { assertNull(failure.get(), () -> "HTTP fixture failed: " + failure.get()); }

        @Override public void close() throws InterruptedException {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Owned fixture executor did not terminate");
            assertHealthy();
        }
    }

    private String response(Reply reply, JsonNode arguments) throws IOException {
        return switch (reply) {
            case TEXT -> event("{\"choices\":[{\"delta\":{\"content\":\"remote-✓-世界\"}}],\"usage\":{\"prompt_tokens\":2}}")
                    + event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":3}}")
                    + event("[DONE]");
            case TOOLS -> {
                var chunk = mapper.createObjectNode();
                chunk.putArray("choices").addObject().putObject("delta").putArray("tool_calls").addObject()
                        .put("index", 0).put("id", "call-fixture-7").put("type", "function")
                        .putObject("function").put("name", "fixture_write")
                        .put("arguments", mapper.writeValueAsString(arguments));
                yield event(mapper.writeValueAsString(chunk))
                        + event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}") + event("[DONE]");
            }
            case PROTOCOL_ERROR -> event("{\"error\":{\"message\":\"fixture protocol error\"}}");
            case REFUSAL -> event("{\"choices\":[{\"delta\":{\"refusal\":\"fixture refusal\"}}]}")
                    + event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}") + event("[DONE]");
            case TRUNCATED -> event("{\"choices\":[{\"delta\":{\"content\":\"partial-✓\"},\"finish_reason\":\"length\"}]}")
                    + event("[DONE]");
            case PREMATURE_EOF -> event("{\"choices\":[{\"delta\":{\"content\":\"partial-✓\"}}]}");
            default -> throw new IllegalArgumentException("Not a finite SSE fixture: " + reply);
        };
    }

    private static String event(String data) { return "data: " + data + "\n\n"; }
}
