/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMemoryEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void denseAutostartPolicyRequiresExplicitTrue() {
        // Default: encoder does NOT auto-start (GPU/ND4J must stay cold at boot).
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart(null, null));
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart("", null));
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart(null, "false"));
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart("invalid", "true"));
        // Explicit opt-in only.
        assertTrue(SemanticMemoryEngine.resolveDenseAutostart("true", null));
        assertTrue(SemanticMemoryEngine.resolveDenseAutostart(" TRUE ", null));
        assertTrue(SemanticMemoryEngine.resolveDenseAutostart(null, "true"));
        // Property wins over environment.
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart("false", "true"));
    }

    @Test
    void denseEncoderDoesNotAutostartWithoutOptIn() {
        // denseEnabled=true but autostart=false (the default): the ND4J/CUDA
        // backend must NOT initialize at boot. Mode advertises the on-demand action.
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir.resolve("lazy"), true, false);
        try {
            engine.initialize();
            assertFalse(engine.isDenseMode());
            assertTrue(engine.getEncoderMode().contains("not started"));
            assertTrue(engine.getEncoderMode().contains("load_encoder"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void startDenseEncoderIsIdempotentAndDisabledRespectsOptOut() throws Exception {
        SemanticMemoryEngine disabled = new SemanticMemoryEngine(tempDir.resolve("optout"), false, true);
        try {
            disabled.initialize();
            disabled.startDenseEncoder();
            assertFalse(disabled.isDenseMode());
            assertEquals("tfidf (dense disabled)", disabled.getEncoderMode());
        } finally {
            disabled.shutdown();
        }

        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var started = new java.util.concurrent.CountDownLatch(1);
        SemanticMemoryEngine lazy = new SemanticMemoryEngine(tempDir.resolve("lazy2"), true, false) {
            @Override void initializeDenseEncoder() {
                starts.incrementAndGet();
                started.countDown();
            }
        };
        try {
            lazy.initialize();
            lazy.startDenseEncoder();
            lazy.startDenseEncoder(); // Exercise the real production idempotence guard, not native inference.
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, starts.get());
            // Encoder load is asynchronous; mode must not claim dense until ready.
            assertFalse(lazy.isDenseMode() && !lazy.getEncoderMode().startsWith("samediff:"));
        } finally {
            lazy.shutdown();
        }
    }

    @Test
    void densePolicyDefaultsToEnabledUnlessExplicitlyFalse() {
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, null));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, "true"));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, ""));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, "invalid"));
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled(null, "false"));
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled(null, " FALSE "));
    }

    @Test
    void densePolicyPropertyTakesPrecedenceOverEnvironment() {
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled("false", "true"));
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled(" FALSE ", null));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled("true", "false"));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled("", "false"));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled("invalid", "false"));
    }

    @Test
    void disabledDenseModeRemainsLexicalAfterRefreshAndRepeatedInitialize() throws Exception {
        Path project = tempDir.resolve("refresh-project");
        SemanticMemoryEngine engine = new SemanticMemoryEngine(project, false);
        try {
            engine.initialize();
            assertEquals("tfidf (dense disabled)", engine.getEncoderMode());
            assertFalse(engine.isDenseMode());

            Path memoryDir = project.resolve(".kompile").resolve("memory");
            Files.createDirectories(memoryDir);
            Path memoryFile = memoryDir.resolve("refresh.md");
            Files.writeString(memoryFile, "denseoptoutrefreshsentinel");

            // Exercise the periodic callback without sleeping for the refresh interval.
            refresh(engine);

            Object refreshedIndex = lexicalIndex(engine);
            engine.initialize();
            assertSame(refreshedIndex, lexicalIndex(engine));
            List<SemanticMemoryEngine.RetrievedMemory> refreshed =
                    engine.query("denseoptoutrefreshsentinel", 5, 0.01);
            assertFalse(refreshed.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(), refreshed.get(0).entry.sourcePath);

            engine.indexTurn("dense-disabled", 1, "user", "denseoptoutturnsentinel");
            assertEquals("turn:dense-disabled:1",
                    engine.query("denseoptoutturnsentinel", 5, 0.01).get(0).entry.id);
            assertFalse(engine.isDenseMode());
            assertEquals("tfidf (dense disabled)", engine.getEncoderMode());
            assertTrue(engine.stats().contains("mode: tfidf (dense disabled)"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void denseBackfillCannotPublishAnOldVectorAfterReplacementOrDeletion() throws Exception {
        for (boolean deleted : List.of(false, true)) {
            Path dir = Files.createDirectories(tempDir.resolve("dense-race-" + deleted));
            Path file = dir.resolve("entry.md");
            Files.writeString(file, "oldvectorsentinel");
            SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
            PausedEncoder encoder = new PausedEncoder();
            java.util.concurrent.FutureTask<Void> backfill = null;
            try {
                engine.addWatchDir(dir);
                refresh(engine);
                var encoderField = SemanticMemoryEngine.class.getDeclaredField("encoder");
                encoderField.setAccessible(true);
                encoderField.set(engine, encoder);
                var methodField = SemanticMemoryEngine.class.getDeclaredField("encodeMethod");
                methodField.setAccessible(true);
                methodField.set(engine, PausedEncoder.class.getMethod("encode", String.class));
                var dense = SemanticMemoryEngine.class.getDeclaredField("useDenseEmbeddings");
                dense.setAccessible(true);
                dense.setBoolean(engine, true);
                var reindex = SemanticMemoryEngine.class.getDeclaredMethod("reindexWithDenseEncoder");
                reindex.setAccessible(true);
                backfill = new java.util.concurrent.FutureTask<>(() -> { reindex.invoke(engine); return null; });
                Thread loader = new Thread(backfill, "semantic-vector-publication-test");
                loader.setDaemon(true);
                loader.start();
                assertTrue(encoder.started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                if (deleted) {
                    Files.delete(file);
                } else {
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    Files.writeString(file, "newvectorsentinel");
                    Files.setLastModifiedTime(file, FileTime.fromMillis(modified - 2_000));
                }
                refresh(engine);
                encoder.release.countDown();
                backfill.get(5, java.util.concurrent.TimeUnit.SECONDS);
                Map<?, ?> vectors = (Map<?, ?>) field(engine, "denseVectors");
                if (deleted) assertTrue(vectors.isEmpty(), "deleted entries must not regain an orphan vector");
                else assertEquals(2f, ((float[]) vectors.get("file:" + file.toAbsolutePath().normalize()))[0],
                        "the loader's obsolete vector must not overwrite the replacement");
            } finally {
                encoder.release.countDown();
                if (backfill != null && !backfill.isDone()) backfill.cancel(true);
                engine.shutdown();
            }
        }
    }

    public static final class PausedEncoder {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        public float[] encode(String text) throws InterruptedException {
            if (text.contains("oldvectorsentinel")) {
                started.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("loader not released");
                return new float[]{1f};
            }
            return new float[]{2f};
        }
    }

    @Test
    void unchangedRefreshReusesIndexAcrossAllDirectories() throws Exception {
        Path firstDir = Files.createDirectories(tempDir.resolve("first"));
        Path secondDir = Files.createDirectories(tempDir.resolve("second"));
        Files.writeString(firstDir.resolve("first.md"), "refreshreusefirst");
        Files.writeString(secondDir.resolve("second.md"), "refreshreusesecond");
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
        try {
            // Explicit directories avoid depending on the real user's global memory corpus.
            engine.addWatchDir(firstDir);
            engine.addWatchDir(secondDir);
            engine.addWatchDir(tempDir.resolve("not-created"));
            refresh(engine);
            assertEquals(1L, generation(engine));
            Object originalIndex = lexicalIndex(engine);
            var original = engine.query("refreshreusefirst", 5, 0.01).get(0);

            for (int i = 0; i < 3; i++) refresh(engine);

            assertSame(originalIndex, lexicalIndex(engine));
            var repeated = engine.query("refreshreusefirst", 5, 0.01).get(0);
            assertSame(original.entry, repeated.entry);
            assertEquals(original.similarity, repeated.similarity);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void refreshBatchesChangesAndDeletionsIntoOneRebuild() throws Exception {
        Path firstDir = Files.createDirectories(tempDir.resolve("first"));
        Path secondDir = Files.createDirectories(tempDir.resolve("second"));
        Path laterDir = tempDir.resolve("later");
        Path changed = firstDir.resolve("changed.md");
        Path deleted = secondDir.resolve("deleted.md");
        Files.writeString(changed, "beforechangesentinel");
        Files.writeString(deleted, "deletedfilesentinel");
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
        try {
            engine.addWatchDir(firstDir);
            engine.addWatchDir(secondDir);
            engine.addWatchDir(laterDir);
            refresh(engine);
            assertEquals(1L, generation(engine));

            long originalModified = Files.getLastModifiedTime(changed).toMillis();
            Files.writeString(changed, "afterchangesentinel");
            // A restored file can have an older timestamp; no sleep or clock granularity assumption.
            Files.setLastModifiedTime(changed, FileTime.fromMillis(originalModified - 2_000));
            Files.delete(deleted);
            Files.createDirectories(laterDir);
            Path added = laterDir.resolve("added.md");
            Files.writeString(added, "laterdirectorysentinel");
            refresh(engine);

            assertEquals(2L, generation(engine));
            assertEquals(2, engine.size());
            assertTrue(engine.query("beforechangesentinel deletedfilesentinel", 5, 0.01).isEmpty());
            assertEquals(changed, engine.query("afterchangesentinel", 5, 0.01).get(0).entry.sourcePath);
            assertEquals(added, engine.query("laterdirectorysentinel", 5, 0.01).get(0).entry.sourcePath);

            Files.writeString(changed, " \n\t");
            Files.setLastModifiedTime(changed, FileTime.fromMillis(originalModified - 4_000));
            Files.delete(added);
            Files.delete(laterDir);
            refresh(engine);

            assertEquals(3L, generation(engine));
            assertEquals(0, engine.size());
            assertTrue(engine.query("afterchangesentinel laterdirectorysentinel", 5, 0.01).isEmpty());
            assertTrue(((Map<?, ?>) field(lexicalIndex(engine), "documentFrequency")).isEmpty());
            Object emptyIndex = lexicalIndex(engine);
            refresh(engine);
            assertSame(emptyIndex, lexicalIndex(engine));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void queriesUseLastCompleteSnapshotBetweenDirectoryScans() throws Exception {
        Path memoryDir = Files.createDirectories(tempDir.resolve("snapshot"));
        Path file = memoryDir.resolve("snapshot.md");
        Files.writeString(file, "snapshotalphasentinel");
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
        try {
            engine.addWatchDir(memoryDir);
            refresh(engine);
            Object originalIndex = lexicalIndex(engine);
            var original = engine.query("snapshotalphasentinel", 5, 0.01).get(0);
            long modified = Files.getLastModifiedTime(file).toMillis();
            Files.writeString(file, "snapshotbetasentinel");
            Files.setLastModifiedTime(file, FileTime.fromMillis(modified - 2_000));

            // Exercise the unpublished state between a scan's mutations and the refresh's rebuild.
            var scanDirectory = SemanticMemoryEngine.class.getDeclaredMethod("scanDirectory", Path.class);
            scanDirectory.setAccessible(true);
            scanDirectory.invoke(engine, memoryDir);
            assertSame(originalIndex, lexicalIndex(engine));
            var duringScan = engine.query("snapshotalphasentinel", 5, 0.01).get(0);
            assertSame(original.entry, duringScan.entry);
            assertEquals(original.similarity, duringScan.similarity);
            assertTrue(engine.query("snapshotbetasentinel", 5, 0.01).isEmpty());

            refresh(engine);
            assertEquals(2L, generation(engine));
            assertTrue(engine.query("snapshotalphasentinel", 5, 0.01).isEmpty());
            assertEquals(file, engine.query("snapshotbetasentinel", 5, 0.01).get(0).entry.sourcePath);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void sparseVectorsGrowWithPresentTermsNotCorpusVocabulary() throws Exception {
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
        try {
            engine.indexTurn("sparse", 0, "user", "anchor anchor");
            Object originalIndex = lexicalIndex(engine);
            for (int i = 1; i <= 64; i++) {
                engine.indexTurn("sparse", i, "user", "uniqueterm" + i);
            }

            Object index = lexicalIndex(engine);
            Map<?, ?> vocabulary = (Map<?, ?>) field(index, "documentFrequency");
            Map<?, ?> vectors = (Map<?, ?>) field(index, "vectors");
            assertEquals(66, vocabulary.size()); // user, anchor, and 64 unique terms
            assertEquals(65, vectors.size());
            int totalWeights = 0;
            for (Object vector : vectors.values()) {
                assertEquals(2, ((String[]) field(vector, "terms")).length);
                int weightCount = ((double[]) field(vector, "weights")).length;
                assertEquals(2, weightCount);
                totalWeights += weightCount;
            }
            assertEquals(130, totalWeights);
            assertEquals(1, ((Map<?, ?>) field(originalIndex, "vectors")).size());
            assertEquals("turn:sparse:0", engine.query("anchor", 5, 0.01).get(0).entry.id);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void sparseCosinePreservesTfidfFormulaRankingAndUnknownTermProjection() {
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
        try {
            engine.indexTurn("ranking", 1, "user", "alpha alpha beta");
            engine.indexTurn("ranking", 2, "user", "alpha gamma gamma gamma");
            engine.indexTurn("ranking", 3, "user", "delta");
            var results = engine.query("ALPHA beta beta unknownqueryterm", 10, 0);
            assertEquals(List.of("turn:ranking:1", "turn:ranking:2", "turn:ranking:3"),
                    results.stream().map(result -> result.entry.id).toList());

            // Dense reference: TF * log(N / DF + 1), with the original vocabulary projection.
            double user = Math.log(2);
            double alpha = Math.log(3.0 / 2 + 1);
            double beta = Math.log(4);
            double queryNorm = Math.sqrt(alpha * alpha + 4 * beta * beta);
            double firstNorm = Math.sqrt(user * user + 4 * alpha * alpha + beta * beta);
            double secondNorm = Math.sqrt(user * user + alpha * alpha + 9 * beta * beta);
            assertEquals((2 * alpha * alpha + 2 * beta * beta) / (queryNorm * firstNorm),
                    results.get(0).similarity, 1e-12);
            assertEquals(alpha * alpha / (queryNorm * secondNorm), results.get(1).similarity, 1e-12);
            assertEquals(0, results.get(2).similarity);
            assertEquals(results.get(0).similarity,
                    engine.query("alpha beta beta", 1, 0).get(0).similarity);
            assertTrue(engine.query("unknownqueryterm", 5, 0.01).isEmpty());
            assertTrue(engine.query("the and", 5, 0.01).isEmpty());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void replacingSessionTurnImmediatelyRebuildsAndSurvivesFileRefresh() throws Exception {
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir, false);
        try {
            engine.addWatchDir(tempDir.resolve("missing"));
            engine.indexTurn("replacement", 1, "user", "sessionoldsentinel");
            engine.indexTurn("replacement", 1, "user", "sessionnewsentinel");
            assertEquals(2L, generation(engine));
            assertEquals(1, engine.size());
            assertTrue(engine.query("sessionoldsentinel", 5, 0.01).isEmpty());
            assertEquals("turn:replacement:1", engine.query("sessionnewsentinel", 5, 0.01).get(0).entry.id);
            Object index = lexicalIndex(engine);
            refresh(engine);
            assertSame(index, lexicalIndex(engine));
            assertEquals(1, engine.size());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void indexesActiveProjectAndKeepsTfidfVocabularyStable() throws Exception {
        Path project = tempDir.resolve("project");
        Path memoryDir = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryFile = memoryDir.resolve("project-sentinel.md");
        Files.writeString(memoryFile, """
                ---
                name: Project semantic sentinel
                description: Scope-specific retrieval marker
                type: project
                ---
                stable-vector-sentinel is only stored in this active project.
                """);

        SemanticMemoryEngine engine = new SemanticMemoryEngine(project, false);
        try {
            engine.initialize();
            assertFalse(engine.isDenseMode());
            assertEquals("tfidf (dense disabled)", engine.getEncoderMode());

            List<SemanticMemoryEngine.RetrievedMemory> initial =
                    engine.query("stable vector sentinel", 5, 0.01);
            assertFalse(initial.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(),
                    initial.get(0).entry.sourcePath.toAbsolutePath().normalize());

            engine.indexTurn("stable-test", 1, "user",
                    "unrelated vocabulary should not invalidate the project sentinel");

            List<SemanticMemoryEngine.RetrievedMemory> afterTurn =
                    engine.query("stable vector sentinel", 5, 0.01);
            assertFalse(afterTurn.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(),
                    afterTurn.get(0).entry.sourcePath.toAbsolutePath().normalize());
        } finally {
            engine.shutdown();
        }
    }

    private static void refresh(SemanticMemoryEngine engine) throws Exception {
        var lastRefreshTime = SemanticMemoryEngine.class.getDeclaredField("lastRefreshTime");
        lastRefreshTime.setAccessible(true);
        lastRefreshTime.setLong(engine, 0L);
        var refreshIndex = SemanticMemoryEngine.class.getDeclaredMethod("refreshIndex");
        refreshIndex.setAccessible(true);
        refreshIndex.invoke(engine);
    }

    private static Object lexicalIndex(SemanticMemoryEngine engine) throws Exception {
        return field(engine, "tfidfIndex");
    }

    private static long generation(SemanticMemoryEngine engine) throws Exception {
        return (long) field(lexicalIndex(engine), "generation");
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
