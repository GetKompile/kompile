package ai.kompile.cli.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in HTTPS qualification of the real shaded JAR and an already-built production native CLI.
 * First run RemoteNativeImageTest with a fresh production image, then run this test with
 * -Dkompile.remoteExecutable=/absolute/path/to/target/native-cli-smoke/kompile-remote.
 * The module's target/kompile-cli-remote-0.1.0-SNAPSHOT-exec.jar must also be packaged already.
 * This fixture never builds an image, changes global trust, or weakens certificate/hostname checks.
 */
@EnabledIfSystemProperty(named = "kompile.remoteExecutable", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
class RemoteNativeHttpsTest {
    private static final String MAIN = "ai.kompile.cli.remote.RemoteCommand";
    private static final String KEY_ENV = "KOMPILE_TLS_FIXTURE_KEY";
    private static final String KEY = "tls-fixture-private-literal-only";
    private static final String STORE_PASSWORD = "fixture-only-password";
    private static final String PROMPT = "TLS request-✓-世界";
    private static final String TEXT = "secure-✓-世界";
    private static final String PARTIAL = "partial-✓-世界";
    private static final int DEADLINE_MILLIS = 1800;
    private static final Duration CLI_LIMIT = Duration.ofSeconds(15);
    private static final Duration KEYTOOL_LIMIT = Duration.ofSeconds(30);
    private static final int MAX_LOG_BYTES = 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // Only synthetic fixture keys/certificates live here; retain them on failure for diagnosis.
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    @Test
    void productionJarAndNativeHonorHttpsTrustHostnameAndBodyDeadline() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path jar = basedir.resolve("target/kompile-cli-remote-0.1.0-SNAPSHOT-exec.jar");
        Path binary = Path.of(System.getProperty("kompile.remoteExecutable"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assertTrue(binary.isAbsolute(), "kompile.remoteExecutable must be an absolute production executable path");
        assertTrue(Files.isRegularFile(binary) && Files.isExecutable(binary), "Missing production native CLI: " + binary);
        assertTrue(Files.isExecutable(java), "Required java.home/bin/java: " + java);
        assertTrue(Files.isExecutable(keytool), "Required java.home/bin/keytool: " + keytool);
        assertTrue(Files.isRegularFile(jar), "Package the actual production shaded executable first: " + jar);
        try (JarFile packaged = new JarFile(jar.toFile())) {
            assertNotNull(packaged.getManifest(), "Production executable must have a manifest");
            assertEquals(MAIN, packaged.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(packaged.getJarEntry(MAIN.replace('.', '/') + ".class"));
        }

        Path evidence = Files.createTempDirectory(Files.createDirectories(basedir.resolve("target/native-cli-https")), "run-");
        // The private parent directory protects keytool output from creation onward, not just after generation.
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(evidence.resolve("fixture-location.txt"), "Synthetic TLS fixtures: " + temporary
                + "\nProduction JAR: " + jar + "\nProduction native CLI: " + binary + "\n", StandardCharsets.UTF_8);
        Identity primary = identity(basedir, evidence, keytool, "primary", "localhost", "dns:localhost,ip:127.0.0.1");
        Identity other = identity(basedir, evidence, keytool, "other", "wrong.invalid", "dns:wrong.invalid");
        assertFalse(Arrays.equals(primary.certificate().getEncoded(), other.certificate().getEncoded()),
                "The rejection fixture must have an independent certificate");
        assertFalse(Arrays.equals(primary.certificate().getPublicKey().getEncoded(), other.certificate().getPublicKey().getEncoded()),
                "The two fixture CAs must have independent keys");

        // Deliberately sequential: keytool, production JAR, and native executable never overlap.
        for (Launcher launcher : List.of(new Launcher("jar", List.of(java.toString()), jar),
                new Launcher("native", List.of(binary.toString()), null))) {
            verifyTrusted(basedir, evidence, launcher, primary);
            verifyRejected(basedir, evidence, launcher, primary, other, false);
            verifyRejected(basedir, evidence, launcher, other, other, true);
            verifyBodyDeadline(basedir, evidence, launcher, primary);
        }
    }

    private Identity identity(Path basedir, Path evidence, Path keytool, String label, String commonName, String san)
            throws Exception {
        Path keys = temporary.resolve(label + "-keys.p12");
        ChildResult generated = run(basedir, evidence, "keytool-" + label, List.of(keytool.toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
                "-validity", "2", "-dname", "CN=" + commonName, "-ext", "SAN=" + san, "-ext", "BC=ca:true",
                "-ext", "KU=digitalSignature,keyEncipherment,keyCertSign", "-ext", "EKU=serverAuth",
                "-storetype", "PKCS12", "-keystore", keys.toString(), "-storepass", STORE_PASSWORD,
                "-keypass", STORE_PASSWORD, "-noprompt"), KEYTOOL_LIMIT, false);
        assertEquals(0, generated.code(), generated::diagnostics);
        Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rw-------"));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keys)) { keyStore.load(input, STORE_PASSWORD.toCharArray()); }
        Certificate certificate = keyStore.getCertificate("fixture");
        assertNotNull(certificate, generated::diagnostics);
        certificate.verify(certificate.getPublicKey());
        Path trust = temporary.resolve(label + "-trust.p12");
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, STORE_PASSWORD.toCharArray());
        trustStore.setCertificateEntry("fixture", certificate);
        try (var output = Files.newOutputStream(trust)) { trustStore.store(output, STORE_PASSWORD.toCharArray()); }
        Files.setPosixFilePermissions(trust, PosixFilePermissions.fromString("rw-------"));
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        return new Identity(trust, certificate, serverContext);
    }

    private void verifyTrusted(Path basedir, Path evidence, Launcher launcher, Identity primary) throws Exception {
        try (Fixture fixture = new Fixture(primary, false)) {
            ChildResult child = request(basedir, evidence, launcher, "trusted", fixture, primary.trust(), 8000);
            JsonNode result = result(child, 0, "completed", false, true);
            assertEquals(TEXT, result.path("text").asText(), child::diagnostics);
            fixture.assertRequest();
        }
    }

    private void verifyRejected(Path basedir, Path evidence, Launcher launcher, Identity serverIdentity,
                                Identity trustIdentity, boolean hostname) throws Exception {
        try (Fixture fixture = new Fixture(serverIdentity, false)) {
            ChildResult child = request(basedir, evidence, launcher, hostname ? "hostname-rejected" : "ca-rejected",
                    fixture, trustIdentity.trust(), 8000);
            JsonNode result = result(child, 1, "failed", false, false);
            assertEquals("", result.path("text").asText(), child::diagnostics);
            String error = result.path("error").asText().toLowerCase(Locale.ROOT);
            assertTrue(hostname
                            ? error.contains("subject alternative") || error.contains("no name matching") || error.contains("hostname")
                            : error.contains("pkix") || error.contains("certification path") || error.contains("trust anchor"),
                    () -> "Expected " + (hostname ? "hostname" : "CA trust") + " rejection, not an unrelated failure. "
                            + child.diagnostics());
            fixture.assertNoHttpRequest();
        }
    }

    private void verifyBodyDeadline(Path basedir, Path evidence, Launcher launcher, Identity primary) throws Exception {
        try (Fixture fixture = new Fixture(primary, true)) {
            ChildResult child = request(basedir, evidence, launcher, "body-deadline", fixture, primary.trust(), DEADLINE_MILLIS);
            JsonNode result = result(child, 130, "cancelled", true, false);
            assertTrue(child.elapsed().compareTo(Duration.ofSeconds(10)) < 0,
                    () -> "HTTPS body deadline did not exit within 10s; fixture stalls for 20s. " + child.diagnostics());
            fixture.assertRequest();
            assertTrue(fixture.bodyFlushed.await(1, TimeUnit.SECONDS), "Fixture never flushed its partial HTTPS body");
            assertEquals(PARTIAL, result.path("text").asText(),
                    () -> "Deadline must interrupt a consumed HTTPS body, not merely the TLS handshake. " + child.diagnostics());
            fixture.assertPeerClosed();
        }
    }

    private ChildResult request(Path basedir, Path evidence, Launcher launcher, String label, Fixture fixture,
                                Path trust, int timeoutMillis) throws Exception {
        List<String> command = new ArrayList<>(launcher.prefix());
        // Both launchers receive identical runtime trust properties, before -jar or application arguments.
        // If the provided native image cannot consume these, qualification must fail; never bypass TLS.
        command.addAll(List.of("-Djavax.net.ssl.trustStore=" + trust, "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-Djavax.net.ssl.trustStorePassword=" + STORE_PASSWORD));
        if (launcher.jar() != null) command.addAll(List.of("-jar", launcher.jar().toString()));
        command.addAll(List.of("request", "--base-url", fixture.apiRoot(), "--model", "fixture", "--prompt", PROMPT,
                "--api-key-env", KEY_ENV, "--timeout-ms", Integer.toString(timeoutMillis), "--json"));
        return run(basedir, evidence, launcher.name() + "-" + label, command, CLI_LIMIT, true);
    }

    private JsonNode result(ChildResult child, int code, String status, boolean cancelled, boolean terminal) throws Exception {
        String stdout = Files.readString(child.stdout(), StandardCharsets.UTF_8);
        String stderr = Files.readString(child.stderr(), StandardCharsets.UTF_8);
        assertFalse(stdout.contains(KEY), "Fixture credential leaked to stdout: " + child.stdout());
        assertFalse(stderr.contains(KEY), "Fixture credential leaked to stderr: " + child.stderr());
        assertEquals(code, child.code(), child::diagnostics);
        JsonNode result = mapper.readTree(stdout);
        assertNotNull(result, child::diagnostics);
        assertTrue(result.isObject(), child::diagnostics);
        assertEquals(status, result.path("status").asText(), child::diagnostics);
        assertTrue(result.path("text").isTextual(), child::diagnostics);
        assertTrue(result.path("toolCalls").isArray(), child::diagnostics);
        assertTrue(result.path("toolCalls").isEmpty(), child::diagnostics);
        assertTrue(result.path("usage").isArray(), child::diagnostics);
        assertTrue(result.path("toolsExecuted").isBoolean(), child::diagnostics);
        assertFalse(result.path("toolsExecuted").asBoolean(), child::diagnostics);
        assertTrue(result.path("cancelled").isBoolean(), child::diagnostics);
        assertEquals(cancelled, result.path("cancelled").asBoolean(), child::diagnostics);
        assertTrue(result.path("terminalEventSeen").isBoolean(), child::diagnostics);
        assertEquals(terminal, result.path("terminalEventSeen").asBoolean(), child::diagnostics);
        return result;
    }

    private static ChildResult run(Path basedir, Path evidence, String label, List<String> command,
                                   Duration limit, boolean credential) throws Exception {
        Path stdout = evidence.resolve(label + ".stdout.log");
        Path stderr = evidence.resolve(label + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(basedir.toFile());
        // Isolate only owned children; no mutation of this JVM's trust, environment, or global launch configuration.
        for (String option : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(option);
        }
        builder.environment().remove(KEY_ENV);
        if (credential) builder.environment().put(KEY_ENV, KEY);
        long started = System.nanoTime();
        Process child = builder.start();
        Set<ProcessHandle> descendants = new HashSet<>();
        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        ExecutorService readers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "remote-https-log-capture");
            thread.setDaemon(true);
            return thread;
        });
        ChildResult result;
        try {
            readers.execute(() -> capture(child.getInputStream(), stdout, captureFailure));
            readers.execute(() -> capture(child.getErrorStream(), stderr, captureFailure));
            readers.shutdown();
            child.getOutputStream().close();
            while (true) {
                child.descendants().forEach(descendants::add);
                assertNull(captureFailure.get(), () -> "Owned child log capture failed; logs: " + evidence + ": " + captureFailure.get());
                long remaining = limit.toNanos() - (System.nanoTime() - started);
                assertTrue(remaining > 0, () -> "Owned child exceeded " + limit + "; logs retained in " + evidence
                        + "\n" + excerpt(stdout) + "\n" + excerpt(stderr));
                if (child.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break;
            }
            result = new ChildResult(child.exitValue(), stdout, stderr, Duration.ofNanos(System.nanoTime() - started));
        } finally {
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // Never terminate by name: only this owned child and descendants observed from it.
            child.descendants().forEach(descendants::add);
            descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            if (child.isAlive()) child.destroyForcibly();
            readers.shutdown();
            try {
                assertTrue(child.waitFor(Math.max(1, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "Owned child did not terminate; logs: " + evidence);
                for (ProcessHandle descendant : descendants) {
                    while (descendant.isAlive() && System.nanoTime() < cleanupDeadline) TimeUnit.MILLISECONDS.sleep(10);
                    assertFalse(descendant.isAlive(), "Owned descendant did not terminate: " + descendant.pid());
                }
                assertTrue(readers.awaitTermination(Math.max(1, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "Owned child log readers did not terminate; logs: " + evidence);
            } finally {
                child.getInputStream().close();
                child.getErrorStream().close();
                readers.shutdownNow();
            }
        }
        assertNull(captureFailure.get(), () -> "Owned child log capture failed; logs: " + evidence + ": " + captureFailure.get());
        return result;
    }

    private static void capture(InputStream input, Path file, AtomicReference<Throwable> failure) {
        try (input; var output = Files.newOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int retained = 0;
            for (int size; (size = input.read(buffer)) != -1;) {
                int keep = Math.min(size, MAX_LOG_BYTES - retained);
                output.write(buffer, 0, keep);
                retained += keep;
                if (keep < size) failure.compareAndSet(null, new IOException("Owned child exceeded 1 MiB log bound: " + file));
            }
        } catch (IOException problem) { failure.compareAndSet(null, problem); }
    }

    private static String excerpt(Path file) {
        try (var input = Files.newInputStream(file)) {
            return file + "\n" + new String(input.readNBytes(16 * 1024), StandardCharsets.UTF_8).replace(KEY, "[redacted]");
        } catch (IOException problem) { return file + ": " + problem; }
    }

    private record Launcher(String name, List<String> prefix, Path jar) { }
    private record Identity(Path trust, Certificate certificate, SSLContext serverContext) { }
    private record ChildResult(int code, Path stdout, Path stderr, Duration elapsed) {
        String diagnostics() { return "Exit " + code + " after " + elapsed + "\n" + excerpt(stdout) + "\n" + excerpt(stderr); }
    }

    private final class Fixture implements AutoCloseable {
        private final HttpsServer server;
        private final ExecutorService workers;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<JsonNode> body = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private final AtomicReference<String> protocol = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch bodyFlushed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch peerClosed = new CountDownLatch(1);
        private volatile boolean closing;

        Fixture(Identity identity, boolean stalled) throws IOException {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            workers = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "remote-native-https-fixture");
                thread.setDaemon(true);
                return thread;
            });
            try {
                server.setHttpsConfigurator(new HttpsConfigurator(identity.serverContext()));
                server.setExecutor(workers);
                server.createContext("/", exchange -> serve((HttpsExchange) exchange, stalled));
                server.start();
            } catch (RuntimeException | Error problem) {
                server.stop(0);
                workers.shutdownNow();
                throw problem;
            }
        }

        String apiRoot() { return "https://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

        private void serve(HttpsExchange exchange, boolean stalled) {
            try {
                requests.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                method.set(exchange.getRequestMethod());
                path.set(exchange.getRequestURI().toString());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                protocol.set(exchange.getSSLSession().getProtocol());
                byte[] request = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
                if (request.length > 64 * 1024) throw new IOException("Fixture request exceeds 64 KiB");
                body.set(mapper.readTree(request));
                received.countDown();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                if (stalled) {
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        output.write(event("{\"choices\":[{\"delta\":{\"content\":\"" + PARTIAL + "\"}}]}").getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        bodyFlushed.countDown();
                        if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Fixture stall expired instead of cancellation");
                        // Probe only after the test has observed child exit, before stopping the server.
                        // An IOException proves the peer closed the HTTPS connection, not fixture teardown.
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        try {
                            byte[] probe = (":" + "x".repeat(4096) + "\n\n").getBytes(StandardCharsets.UTF_8);
                            while (System.nanoTime() < until) {
                                output.write(probe);
                                output.flush();
                                TimeUnit.MILLISECONDS.sleep(20);
                            }
                            throw new AssertionError("Cancelled HTTPS peer did not close the body connection");
                        } catch (IOException expectedPeerClosure) { peerClosed.countDown(); }
                    }
                } else {
                    byte[] response = (event("{\"choices\":[{\"delta\":{\"content\":\"" + TEXT + "\"}}]}")
                            + event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}") + event("[DONE]")).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        int split = 0;
                        while (split < response.length && response[split] >= 0) split++;
                        if (split < response.length) {
                            output.write(response, 0, ++split); // First byte of a multibyte character in its own TLS write.
                            output.flush();
                            output.write(response, split, response.length - split);
                        } else output.write(response);
                    }
                }
            } catch (Throwable problem) {
                if (!closing && !(problem instanceof IOException && peerClosed.getCount() == 0)) {
                    failure.compareAndSet(null, problem);
                }
            } finally {
                received.countDown();
                exchange.close();
            }
        }

        void assertRequest() throws InterruptedException, IOException {
            assertTrue(received.await(1, TimeUnit.SECONDS), "HTTPS fixture received no HTTP request");
            assertHealthy();
            assertEquals(1, requests.get(), "One request expected; no retries or agent loop");
            assertEquals("POST", method.get());
            assertEquals("/v1/chat/completions", path.get());
            assertTrue(("Bearer " + KEY).equals(authorization.get()), "Expected only the synthetic child-env credential");
            assertNotNull(contentType.get());
            assertTrue(contentType.get().startsWith("application/json"));
            assertNotNull(protocol.get(), "HTTP handler must have a negotiated TLS session");
            assertTrue(protocol.get().startsWith("TLS"));
            JsonNode sent = body.get();
            assertNotNull(sent);
            assertEquals("fixture", sent.path("model").asText());
            assertEquals(mapper.readTree("[{\"role\":\"user\",\"content\":\"" + PROMPT + "\"}]"), sent.path("messages"));
            assertTrue(sent.path("stream").asBoolean());
            assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
        }

        void assertNoHttpRequest() throws InterruptedException {
            assertFalse(received.await(200, TimeUnit.MILLISECONDS), "TLS rejection must precede HTTP handler delivery");
            assertEquals(0, requests.get(), "TLS rejection delivered an HTTP request");
            assertNull(authorization.get(), "TLS rejection delivered authentication");
            assertNull(body.get(), "TLS rejection delivered an HTTP body");
            assertHealthy();
        }

        void assertPeerClosed() throws InterruptedException {
            release.countDown();
            assertTrue(peerClosed.await(5, TimeUnit.SECONDS), "Cancelled child did not close its HTTPS body connection");
            assertHealthy();
        }

        void assertHealthy() { assertNull(failure.get(), () -> "HTTPS fixture failed: " + failure.get()); }

        @Override public void close() throws InterruptedException {
            closing = true;
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Owned HTTPS fixture executor did not terminate");
            assertHealthy();
        }
    }

    private static String event(String data) { return "data: " + data + "\n\n"; }
}
