/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalEmbeddingRuntimeTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @TempDir
    Path projectRoot;

    @AfterEach
    void resetRuntimes() {
        LocalEmbeddingRuntime.resetForTests();
    }

    @Test
    void fingerprintChangesWhenSameSizeArtifactIsReplacedWithPreservedMtime() throws Exception {
        Path model = writeModelRegistry();
        String first = LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper);
        FileTime originalTime = Files.getLastModifiedTime(model);

        Files.writeString(model, "bbbb", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(model, originalTime);

        String second = LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper);
        assertNotEquals(first, second);
    }

    @Test
    void explicitLocalPathsBypassRegistryModelIdSymlink() throws Exception {
        Path model = writeModelRegistry();
        Path outside = Files.createTempDirectory(projectRoot.getParent(), "foreign-model-");
        Files.writeString(outside.resolve("model.sdz"), "aaaa", StandardCharsets.UTF_8);
        Files.writeString(outside.resolve("vocab.txt"), "token", StandardCharsets.UTF_8);
        Files.createSymbolicLink(projectRoot.resolve("data/models/test-encoder"), outside);

        Map<String, String> config = LocalEmbeddingRuntime.modelConfig(projectRoot, mapper);
        assertEquals(model.toRealPath().toString(), config.get("modelPath"));
        assertEquals("LOCAL_PROJECT", config.get("modelSource"));
    }

    @Test
    void fingerprintUsesTheChildLoadersTokenizerJsonFallback() throws Exception {
        Path model = writeModelRegistry();
        Path encoder = model.getParent();
        Files.delete(encoder.resolve("vocab.txt"));
        Files.writeString(encoder.resolve("tokenizer.json"), "{}", StandardCharsets.UTF_8);

        assertNotNull(LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper));
    }

    @Test
    void projectBatchContractIsPartOfTheRuntimeFingerprint() throws Exception {
        writeModelRegistry();
        Path config = Files.createDirectories(projectRoot.resolve("config"))
                .resolve("embedding-anserini-config.json");
        Files.writeString(config, """
                {"modelIdentifier":"test-encoder","baseOptimalBatchSize":2,
                 "baseMaxBatchSize":4,"absoluteMaxBatchSize":4}
                """, StandardCharsets.UTF_8);
        String first = LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper);

        Files.writeString(config, """
                {"modelIdentifier":"test-encoder","baseOptimalBatchSize":3,
                 "baseMaxBatchSize":6,"absoluteMaxBatchSize":6}
                """, StandardCharsets.UTF_8);

        assertNotEquals(first, LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper));
    }

    @Test
    void projectEmbeddingConfigWinsOverTheFirstEncoderInTheManifest() throws Exception {
        writeModelRegistry();
        Path models = projectRoot.resolve("data/models");
        Path selected = Files.createDirectories(models.resolve("selected-encoder"));
        Path selectedModel = Files.writeString(selected.resolve("model.sdz"), "selected");
        Files.writeString(selected.resolve("tokenizer.json"), "{}");
        Files.writeString(models.resolve("registry.json"), """
                {"models":{
                  "test-encoder":{"type":"dense_encoder","path":"encoders/test-encoder",
                                  "model_file":"model.sdz","vocab_file":"vocab.txt"},
                  "selected-encoder":{"type":"dense_encoder","path":"selected-encoder",
                                      "model_file":"model.sdz","vocab_file":"tokenizer.json"}
                }}
                """, StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("kompile.project.json"), """
                {"schemaVersion":1,"projectId":"selection-test","name":"selection-test",
                 "models":[
                   {"id":"test-encoder","modelId":"test-encoder","role":"ENCODER",
                    "path":"data/models/encoders/test-encoder/model.sdz","lifecycle":"ACTIVE"},
                   {"id":"selected-encoder","modelId":"selected-encoder","role":"ENCODER",
                    "path":"data/models/selected-encoder/model.sdz","lifecycle":"ACTIVE"}
                 ]}
                """, StandardCharsets.UTF_8);
        Path config = Files.createDirectories(projectRoot.resolve("config"))
                .resolve("embedding-anserini-config.json");
        Files.writeString(config, "{\"modelIdentifier\":\"selected-encoder\"}",
                StandardCharsets.UTF_8);

        Map<String, String> runtimeConfig = LocalEmbeddingRuntime.modelConfig(projectRoot, mapper);

        assertEquals(selectedModel.toRealPath().toString(), runtimeConfig.get("modelPath"));
    }

    @Test
    void idleShutdownWindowResetsAfterEveryUse() throws Exception {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(500, process);

        try (LocalProjectRagSearch.EmbeddingRuntime first = runtime.acquireForTests(false)) {
            assertEquals(1, first.embedBatch(List.of("first")).size());
        }
        Thread.sleep(300);

        try (LocalProjectRagSearch.EmbeddingRuntime second = runtime.acquireForTests(false)) {
            assertEquals(1, second.embedBatch(List.of("second")).size());
            Thread.sleep(300);
            assertEquals(0, process.stops.get(),
                    "reuse must invalidate the previous idle deadline");
        }

        Thread.sleep(300);
        assertEquals(0, process.stops.get(),
                "the latest use must receive a complete idle window");
        awaitTrue(() -> process.stops.get() == 1, 2_000);
    }

    @Test
    void idleShutdownWaitsForAnInFlightRequestAfterLeaseClose() throws Exception {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        CompletableFuture<List<float[]>> response = new CompletableFuture<>();
        process.nextResponse = response;
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(250, process);
        LocalProjectRagSearch.EmbeddingRuntime lease = runtime.acquireForTests(false);

        CompletableFuture<List<float[]>> request = CompletableFuture.supplyAsync(() -> {
            try {
                return lease.embedBatch(List.of("blocked"));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        assertTrue(process.requestStarted.await(1, TimeUnit.SECONDS));
        lease.close();
        Thread.sleep(400);
        assertEquals(0, process.stops.get(), "an in-flight request must keep the process alive");

        response.complete(List.of(new float[]{1.0f}));
        assertEquals(1, request.get(1, TimeUnit.SECONDS).size());
        Thread.sleep(100);
        assertEquals(0, process.stops.get(),
                "request completion must start a fresh idle window");
        awaitTrue(() -> process.stops.get() == 1, 2_000);
    }

    @Test
    void idleExpirySuppressesAnOwnerlessRestart() throws Exception {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(25, process);
        try (LocalProjectRagSearch.EmbeddingRuntime ignored = runtime.acquireForTests(false)) {
            process.running.set(false);
            process.alive.set(false);
        }

        awaitTrue(() -> process.stops.get() == 1, 2_000);
    }

    @Test
    void crawlCloseRequestRemainsStickyUntilTheFinalLeaseEnds() {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(60_000, process);
        LocalProjectRagSearch.EmbeddingRuntime reusable = runtime.acquireForTests(false);
        LocalProjectRagSearch.EmbeddingRuntime crawl = runtime.acquireForTests(true);

        crawl.close();
        assertEquals(0, process.stops.get(), "another active lease must defer shutdown");
        reusable.close();

        assertEquals(1, process.stops.get(),
                "the one-shot crawl request must close after the final shared lease");
    }

    @Test
    void concurrentLeaseCloseStopsTheProcessOnlyOnce() {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(60_000, process);
        LocalProjectRagSearch.EmbeddingRuntime lease = runtime.acquireForTests(true);

        CompletableFuture.allOf(
                CompletableFuture.runAsync(lease::close),
                CompletableFuture.runAsync(lease::close)).join();

        assertEquals(1, process.stops.get());
    }

    @Test
    void zeroIdleTimeoutKeepsAReusableProcessAlive() throws Exception {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(0, process);
        try (LocalProjectRagSearch.EmbeddingRuntime ignored = runtime.acquireForTests(false)) {
            // Releasing this lease must not schedule a reap when the reusable timeout is disabled.
        }

        Thread.sleep(100);
        assertEquals(0, process.stops.get());
        assertEquals(0, LocalEmbeddingRuntime.queuedIdleReapTasksForTests());
    }

    @Test
    void cancelledIdleDeadlinesDoNotAccumulate() {
        FakeEmbeddingProcess process = new FakeEmbeddingProcess();
        LocalEmbeddingRuntime runtime = LocalEmbeddingRuntime.runtimeForTests(60_000, process);

        for (int i = 0; i < 20; i++) {
            try (LocalProjectRagSearch.EmbeddingRuntime ignored =
                         runtime.acquireForTests(false)) {
                assertEquals(0, LocalEmbeddingRuntime.queuedIdleReapTasksForTests());
            }
            assertEquals(1, LocalEmbeddingRuntime.queuedIdleReapTasksForTests());
        }
    }

    private Path writeModelRegistry() throws Exception {
        Path models = Files.createDirectories(projectRoot.resolve("data/models"));
        Path encoder = Files.createDirectories(models.resolve("encoders/test-encoder"));
        Path model = encoder.resolve("model.sdz");
        Files.writeString(model, "aaaa", StandardCharsets.UTF_8);
        Files.writeString(encoder.resolve("vocab.txt"), "token", StandardCharsets.UTF_8);
        Files.writeString(models.resolve("registry.json"), """
                {
                  "models": {
                    "test-encoder": {
                      "type": "dense_encoder",
                      "path": "encoders/test-encoder",
                      "model_file": "model.sdz",
                      "vocab_file": "vocab.txt",
                      "status": "staged"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("kompile.project.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "local-rag-test",
                  "name": "local-rag-test",
                  "models": [{
                    "id": "test-encoder",
                    "modelId": "test-encoder",
                    "registryModelId": "test-encoder",
                    "role": "ENCODER",
                    "lifecycle": "ACTIVE",
                    "metadata": {"registry.type": "dense_encoder"}
                  }]
                }
                """, StandardCharsets.UTF_8);
        return model;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("condition was not satisfied before timeout");
    }

    private static final class FakeEmbeddingProcess
            implements LocalEmbeddingRuntime.EmbeddingProcess {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger stops = new AtomicInteger();
        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private volatile CompletableFuture<List<float[]>> nextResponse =
                CompletableFuture.completedFuture(List.of(new float[]{1.0f}));

        @Override
        public CompletableFuture<List<float[]>> embedBatch(List<String> texts, long timeoutMs) {
            requestStarted.countDown();
            return nextResponse;
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public boolean isProcessAlive() {
            return alive.get();
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            running.set(false);
            alive.set(false);
        }
    }
}
