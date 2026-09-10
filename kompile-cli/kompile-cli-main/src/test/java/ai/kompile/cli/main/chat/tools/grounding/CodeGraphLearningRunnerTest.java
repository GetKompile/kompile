/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.codeindex.CodeGraphReasoningConfig;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGraphLearningRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void automaticReuseRunsExecutorOnceAndManualRunForcesIt() throws Exception {
        Path graphPath = writeGraph();
        AtomicInteger calls = new AtomicInteger();
        CodeGraphLearningRunner runner = runner(calls, null, null);
        CodeGraphReasoningConfig config = config();

        assertEquals("COMPLETED", runner.runAutomatic(graphPath, config, "build").status());
        assertEquals("SKIPPED_UNCHANGED",
                runner.runAutomatic(graphPath, config, "build").status());
        assertEquals(1, calls.get());

        assertEquals("COMPLETED", runner.run(graphPath, config).status());
        assertEquals(2, calls.get());
    }

    @Test
    void concurrentIdenticalAutomaticRequestsRunLearningOnceAndReuseReceipt() throws Exception {
        Path graphPath = writeGraph();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CodeGraphLearningRunner runner = new CodeGraphLearningRunner(
                (graph, plan, workingDirectory, crawlJobId) -> {
                    calls.incrementAndGet();
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("bounded learning release timed out");
                    }
                    graph.meta("testLearningRun", calls.get());
                    return new ProjectLocalLearningSubprocessExecutor.Result(
                            graph, "TEST", null, null, null);
                });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<CodeGraphLearningRunner.LearningSummary> first =
                    workers.submit(() -> runner.runAutomatic(graphPath, config(), "build"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<CodeGraphLearningRunner.LearningSummary> second = workers.submit(() -> {
                secondEntered.countDown();
                return runner.runAutomatic(graphPath, config(), "build");
            });
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> second.get(250, TimeUnit.MILLISECONDS),
                    "the second identical request should remain pending while the first learns");
            assertEquals(1, calls.get(),
                    "the pending duplicate must not invoke the learning executor");
            release.countDown();

            assertEquals("COMPLETED", first.get(5, TimeUnit.SECONDS).status());
            assertEquals("SKIPPED_UNCHANGED", second.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void interruptedLearningReturnRestoresCancellationAndPublishesNothing() throws Exception {
        Path graphPath = writeGraph();
        CodeGraphLearningRunner runner = new CodeGraphLearningRunner(
                (graph, plan, workingDirectory, crawlJobId) -> {
                    graph.meta("shouldNotPersist", true);
                    Thread.currentThread().interrupt();
                    return new ProjectLocalLearningSubprocessExecutor.Result(
                            graph, "TEST", null, null, null);
                });
        CodeGraphReasoningConfig config = config();
        Path configPath = CodeGraphReasoningConfig.projectConfigPath(tempDir);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, config.toJson());

        try {
            CodeGraphLearningRunner.ConfiguredResult result =
                    runner.runConfigured(tempDir, graphPath, CodeGraphReasoningConfig.TRIGGER_BUILD);
            assertEquals("FAILED", result.status());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "configured cancellation must restore the interrupt flag");
            Thread.interrupted();
            assertFalse(Files.exists(CodeGraphLearningRunner.receiptPath(graphPath)));
            assertFalse(UnifiedGraph.load(graphPath).meta().containsKey("shouldNotPersist"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void configurationStructuralAndJournalChangesInvalidateReceipt() throws Exception {
        Path graphPath = writeGraph();
        AtomicInteger calls = new AtomicInteger();
        CodeGraphLearningRunner runner = runner(calls, null, null);
        CodeGraphReasoningConfig config = config();

        runner.runAutomatic(graphPath, config, "build");

        CodeGraphReasoningConfig changedConfig = config();
        changedConfig.setKgeDim(64);
        assertEquals("COMPLETED", runner.runAutomatic(graphPath, changedConfig, "build").status());
        assertEquals(2, calls.get());

        UnifiedGraph structural = UnifiedGraph.load(graphPath).meta("structuralMutation", "yes");
        structural.save(graphPath, KGraphCompatibilityPolicy.COMPACT_V3);
        assertEquals("COMPLETED", runner.runAutomatic(graphPath, changedConfig, "build").status());
        assertEquals(3, calls.get());

        UnifiedGraphArchive.Link assertion = new UnifiedGraphArchive.Link(
                1, "r2", "a", "b", "CALLS", 1.0, 1.0, true, false,
                Set.of(), null, Map.of(), null);
        UnifiedGraphMutationJournal.appendAssertion(graphPath, Set.of(), assertion, null);
        assertEquals("COMPLETED", runner.runAutomatic(graphPath, changedConfig, "build").status());
        assertEquals(4, calls.get());
        assertTrue(UnifiedGraph.load(graphPath).relation("r2").isPresent(),
                "journal assertion was lost during compact-before-learn");
        assertFalse(Files.exists(UnifiedGraphMutationJournal.pathFor(graphPath)),
                "a captured journal must be compacted before the next learned publication");
    }

    @Test
    void concurrentMutationSupersedesLearningWithoutHoldingWriterLock() throws Exception {
        Path graphPath = writeGraph();
        CodeGraphLearningRunner baselineRunner = runner(new AtomicInteger(), null, null);
        assertEquals("COMPLETED", baselineRunner.runAutomatic(graphPath, config(), "build").status());

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        CodeGraphLearningRunner runner = new CodeGraphLearningRunner(
                (graph, plan, workingDirectory, crawlJobId) -> {
                    calls.incrementAndGet();
                    started.countDown();
                    release.await();
                    returned.countDown();
                    graph.meta("staleLearning", true);
                    return new ProjectLocalLearningSubprocessExecutor.Result(
                            graph, "TEST", null, null, null);
                });
        ExecutorService learner = Executors.newSingleThreadExecutor();
        ExecutorService writers = Executors.newSingleThreadExecutor();
        try {
            Future<CodeGraphLearningRunner.LearningSummary> future =
                    learner.submit(() -> runner.run(graphPath, config()));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            Future<Boolean> independentWriter = writers.submit(
                    () -> LocalProjectGraphBackend.withGraphWriteLock(graphPath, () -> true));
            assertTrue(independentWriter.get(5, TimeUnit.SECONDS),
                    "learning must not hold the canonical writer lock");
            assertFalse(returned.await(250, TimeUnit.MILLISECONDS),
                    "the learner must remain blocked until explicitly released");

            Future<Void> archiveReplacement = writers.submit(() -> {
                LocalProjectGraphBackend.withGraphWriteLock(graphPath, () -> {
                    UnifiedGraph replacement = UnifiedGraph.load(graphPath)
                            .meta("archiveGeneration", "generation-2");
                    replacement.save(graphPath, KGraphCompatibilityPolicy.COMPACT_V3);
                    return null;
                });
                return null;
            });
            archiveReplacement.get(5, TimeUnit.SECONDS);

            Future<Void> journalMutation = writers.submit(() -> {
                UnifiedGraphArchive.Link assertion = new UnifiedGraphArchive.Link(
                        1, "r2", "a", "b", "CALLS", 1.0, 1.0, true, false,
                        Set.of(), null, Map.of(), null);
                UnifiedGraphMutationJournal.appendAssertion(
                        graphPath, Set.of(), assertion, null);
                UnifiedGraphArchive.Link retraction = new UnifiedGraphArchive.Link(
                        0, "r1", "a", "b", "CALLS", 1.0, 1.0, true, false,
                        Set.of(), null, Map.of(), null);
                UnifiedGraphMutationJournal.appendRetractions(graphPath, Set.of(retraction));
                return null;
            });
            journalMutation.get(5, TimeUnit.SECONDS);
            release.countDown();

            assertTrue(returned.await(5, TimeUnit.SECONDS));
            assertEquals("SUPERSEDED", future.get(5, TimeUnit.SECONDS).status());
            UnifiedGraph persisted = UnifiedGraph.load(graphPath);
            assertEquals("generation-2", persisted.meta().get("archiveGeneration"));
            assertTrue(persisted.relation("r2").isPresent());
            assertFalse(persisted.relation("r1").isPresent());
            assertFalse(Files.exists(CodeGraphLearningRunner.receiptPath(graphPath)),
                    "stale learning must not leave the prior receipt valid");
        } finally {
            release.countDown();
            learner.shutdownNow();
            writers.shutdownNow();
        }
    }

    @Test
    void missingOrCorruptReceiptConservativelyReruns() throws Exception {
        Path graphPath = writeGraph();
        AtomicInteger calls = new AtomicInteger();
        CodeGraphLearningRunner runner = runner(calls, null, null);
        CodeGraphReasoningConfig config = config();

        runner.runAutomatic(graphPath, config, "build");
        Files.delete(CodeGraphLearningRunner.receiptPath(graphPath));
        assertEquals("COMPLETED", runner.runAutomatic(graphPath, config, "build").status());
        assertEquals(2, calls.get());

        Files.writeString(CodeGraphLearningRunner.receiptPath(graphPath), "not-json");
        assertEquals("COMPLETED", runner.runAutomatic(graphPath, config, "build").status());
        assertEquals(3, calls.get());
    }

    private CodeGraphLearningRunner runner(
            AtomicInteger calls, CountDownLatch started, CountDownLatch release) {
        return new CodeGraphLearningRunner((graph, plan, workingDirectory, crawlJobId) -> {
            calls.incrementAndGet();
            if (started != null) started.countDown();
            if (release != null) release.await(5, TimeUnit.SECONDS);
            graph.meta("testLearningRun", calls.get());
            return new ProjectLocalLearningSubprocessExecutor.Result(
                    graph, "TEST", null, null, null);
        });
    }

    private CodeGraphReasoningConfig config() {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig();
        config.setEnabled(true);
        config.setKgeTraining(false);
        config.setMinGraphSize(0);
        config.setTriggers(java.util.List.of(CodeGraphReasoningConfig.TRIGGER_BUILD));
        return config.normalize();
    }

    private Path writeGraph() throws Exception {
        Path graphPath = tempDir.resolve("graph.kgraph");
        new UnifiedGraph()
                .graphId("runner-test")
                .meta("codeIndexGeneration.project", "generation-1")
                .addEntity("a", "CLASS", "A")
                .addEntity("b", "CLASS", "B")
                .addRelation("r1", "a", "b", "CALLS", 1.0)
                .save(graphPath, KGraphCompatibilityPolicy.COMPACT_V3);
        return graphPath;
    }
}
