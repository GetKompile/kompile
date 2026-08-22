package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalServingRuntimePoolTest {
    @TempDir
    Path tempDir;

    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private Path model;
    private Path tokenizer;

    @BeforeEach
    void configurePool() throws Exception {
        LocalServingRuntimePool.resetForTests();
        LocalServingRuntimePool.setIdleMillisForTests(60_000);
        LocalServingRuntimePool.setMaxRuntimesForTests(2);
        model = Files.writeString(tempDir.resolve("model.gguf"), "model");
        tokenizer = Files.writeString(tempDir.resolve("tokenizer.json"), "{}");
        LocalServingRuntimePool.setStarterForTests(request -> result(request));
    }

    @AfterEach
    void resetPool() {
        LocalServingRuntimePool.resetForTests();
    }

    @Test
    void compatibleCrawlAndHarnessLeasesReuseOneRuntime() throws Exception {
        Process process;
        try (var first = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of("dspEnabled", true), 5)) {
            process = first.process();
            assertTrue(first.isAlive());
        }
        try (var second = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of("dspEnabled", true), 5)) {
            assertSame(process, second.process());
            assertEquals(1, starts.get());
        }

        assertEquals(1, LocalServingRuntimePool.pooledCount());
        assertEquals(0, stops.get(), "last release keeps a compatible model warm");
    }

    @Test
    void perJobCorrelationDoesNotChangeTheResidentRuntimeCompatibilityKey() throws Exception {
        Process process;
        try (var first = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of(
                        "dspEnabled", true,
                        "crawlJobId", "local-11111111-1111-4111-8111-111111111111",
                        "knowledgeBaseId", "one",
                        "projectRoot", tempDir.resolve("one").toString()), 5)) {
            process = first.process();
        }
        try (var second = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of(
                        "dspEnabled", true,
                        "crawlJobId", "local-22222222-2222-4222-8222-222222222222",
                        "knowledgeBaseId", "two",
                        "projectRoot", tempDir.resolve("two").toString()), 5)) {
            assertSame(process, second.process());
        }
        assertEquals(1, starts.get());
    }

    @Test
    void compatibilityKeyIncludesRuntimeOptionsAndArtifacts() throws Exception {
        try (var first = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of("dspEnabled", true), 5);
             var second = LocalServingRuntimePool.acquire(
                     "model", model, tokenizer, Map.of("dspEnabled", false), 5)) {
            assertFalse(first.process() == second.process());
        }
        assertEquals(2, starts.get());

        Files.writeString(model, "model-v2");
        try (var changed = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of("dspEnabled", true), 5)) {
            assertTrue(changed.isAlive());
        }
        assertEquals(3, starts.get());
    }

    @Test
    void failedStartupCanBeRetriedForTheSameKey() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        LocalServingRuntimePool.setStarterForTests(request -> {
            if (attempts.incrementAndGet() == 1) {
                throw new KompileLocalServingBootstrap.BootstrapException("startup failed");
            }
            return result(request);
        });

        try {
            LocalServingRuntimePool.acquire("model", model, tokenizer, Map.of(), 5);
            fail("expected the first startup to fail");
        } catch (KompileLocalServingBootstrap.BootstrapException expected) {
            assertEquals("startup failed", expected.getMessage());
        }

        try (var recovered = LocalServingRuntimePool.acquire(
                "model", model, tokenizer, Map.of(), 5)) {
            assertTrue(recovered.isAlive());
        }
        assertEquals(2, attempts.get());
    }

    private KompileLocalServingBootstrap.StartupResult result(
            LocalServingRuntimePool.RuntimeRequest request) {
        starts.incrementAndGet();
        FakeProcess process = new FakeProcess(stops);
        return new KompileLocalServingBootstrap.StartupResult(
                request.modelId(),
                request.modelPath() == null ? model : request.modelPath(),
                request.tokenizerPath(),
                java.net.URI.create("http://127.0.0.1:" + (9000 + starts.get())),
                new KompileLocalServingBootstrap.LauncherArtifact(model, true),
                process,
                null,
                null);
    }

    private static final class FakeProcess extends Process {
        private final AtomicInteger stops;
        private volatile boolean alive = true;

        private FakeProcess(AtomicInteger stops) {
            this.stops = stops;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            if (alive) {
                alive = false;
                stops.incrementAndGet();
            }
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
