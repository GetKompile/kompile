/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.subprocess;

import ai.kompile.app.learning.subprocess.LearningSubprocessMessage;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.app.subprocess.SubprocessRegistry.SubprocessInfo;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KgeTrainingExecutor.KgeTrainingResult;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.core.reasoning.ReasoningLearningExecutor;
import ai.kompile.core.reasoning.ReasoningLearningExecutor.LearningResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A learning run gets one verdict: the first of the child's own report, a stall, a timeout, an
 * interrupted caller, or the child's exit. Nothing the child writes after the verdict changes it.
 * The run then ends once: the files it made for its child are deleted (a completed KGE run's
 * embeddings are its caller's) and no child is left running. {@link FakeLearningChild} runs through
 * the launcher's real command line and reports through the real reporter.
 */
@Timeout(60)
class LearningSubprocessLauncherVerdictTest {

    /** Well inside the 5 s the launcher waits for its child's output once the child has exited. */
    private static final long SLOW_REPORT_READ_MS = 500;
    private static final long FACT_SHEET_ID = 7L;
    private static final List<Triple> TRIPLES = List.of(new Triple("a", "R", "b"));
    private static final KGEmbeddingConfig CONFIG = KGEmbeddingConfig.builder().build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private final ExecutorService callers = Executors.newCachedThreadPool();
    private String userHome;
    private String userDir;
    private FakeChildLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        // Keep anything written under user.home or user.dir in the temp dir
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        System.setProperty("user.dir", Files.createDirectories(tmp.resolve("work/.kompile")).getParent().toString());

        launcher = new FakeChildLauncher();
        // Spring sets both from their property defaults
        launcher.jobTimeoutMs = 30_000L;
        launcher.staleTimeoutMs = 120_000L;
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            launcher.shutdown();
            for (Map.Entry<String, Process> child : launcher.children.entrySet()) {
                assertTrue(child.getValue().waitFor(10, TimeUnit.SECONDS),
                        "the child of " + child.getKey() + " is still running");
            }
        } finally {
            callers.shutdownNow();
            System.setProperty("user.home", userHome);
            System.setProperty("user.dir", userDir);
            // A failing test may leave its files behind; they are removed by exact path only
            for (Map<String, Path> files : launcher.runFiles.values()) {
                for (Path file : files.values()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void childFailureIsTheVerdictNotItsExit() throws Exception {
        // The child exits while the launcher is still reading its report
        launcher.reportReadDelayMs = SLOW_REPORT_READ_MS;

        KgeTrainingResult result = train("fail");

        assertFalse(result.success());
        assertEquals(FakeLearningChild.CHILD_FAILURE, result.errorMessage());
        awaitRunEnded(onlyRunId());
    }

    @Test
    void completionIsTheVerdictAndItsEmbeddingsAreTheCallers() throws Exception {
        // The child exits while the launcher is still reading its report
        launcher.reportReadDelayMs = SLOW_REPORT_READ_MS;

        KgeTrainingResult result = train("complete");

        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, result.entityCount());
        assertEquals(1, result.relationCount());
        assertEquals(0.25, result.finalLoss());
        String runId = onlyRunId();
        Path embeddings = launcher.runFiles.get(runId).get("outputEmbeddingsPath");
        assertEquals(embeddings, result.serializedEmbeddingsPath());
        awaitRunEnded(runId, "outputEmbeddingsPath");
        // Nothing that ends the run afterwards takes the embeddings from the caller
        Thread.sleep(300);
        assertEquals(FakeLearningChild.TRAINED_EMBEDDINGS, Files.readString(embeddings));
    }

    @Test
    void cleanExitWithoutCompletionFails() throws Exception {
        KgeTrainingResult result = train("exit0-silent");

        assertFalse(result.success());
        assertEquals("KGE subprocess exited with code 0 without a completion message", result.errorMessage());
        awaitRunEnded(onlyRunId());
    }

    @Test
    void nonZeroExitFailsWithItsCode() throws Exception {
        KgeTrainingResult result = train("exit7");

        assertFalse(result.success());
        assertEquals("KGE subprocess exited with code 7 without a completion message", result.errorMessage());
        awaitRunEnded(onlyRunId());
    }

    @Test
    void outOfMemoryExitSaysSo() throws Exception {
        KgeTrainingResult result = train("oom");

        assertFalse(result.success());
        assertEquals("KGE subprocess ran out of memory (exit code 3)", result.errorMessage());
        awaitRunEnded(onlyRunId());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a stopped child runs its shutdown hooks only on POSIX")
    void timeoutIsTheVerdictEvenIfTheChildCompletesWhileItIsStopped() throws Exception {
        launcher.jobTimeoutMs = 5_000L;

        KgeTrainingResult result = train("complete-on-stop");

        assertFalse(result.success());
        assertEquals("KGE subprocess timed out after 5000ms", result.errorMessage());
        String runId = onlyRunId();
        Process child = launcher.children.get(runId);
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child was stopped");
        // Process.destroy closes the pipes its report would have come through; its exit code tells instead
        assertEquals(0, child.exitValue(), "the child completed while it was stopped");
        assertEquals(List.of(runId + ": timeout after 5000ms"), launcher.stops);
        // The embeddings of the discarded completion go with the failed run
        awaitRunEnded(runId);
    }

    @Test
    void stalledRunFailsOnceAndItsChildIsStopped() throws Exception {
        launcher.staleTimeoutMs = 1_000L;
        Future<KgeTrainingResult> result = callers.submit(() -> train("no-heartbeat"));
        Process child = awaitOnlyChild();
        Thread.sleep(1_500);

        // Another check comes round while the first is still stopping the child
        launcher.checkWhileStopping.set(true);
        launcher.checkForStaleProcesses();

        KgeTrainingResult verdict = result.get(20, TimeUnit.SECONDS);
        assertFalse(verdict.success());
        assertTrue(verdict.errorMessage().startsWith("KGE subprocess stalled (no heartbeat for "),
                verdict.errorMessage());
        String runId = onlyRunId();
        assertEquals(List.of(runId + ": stale (no heartbeat)"), launcher.stops);
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child was stopped");
        awaitRunEnded(runId);
    }

    @Test
    void interruptedCallerStopsTheChildAndKeepsItsInterrupt() throws Exception {
        AtomicReference<KgeTrainingResult> verdict = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            verdict.set(train("hang"));
            interruptKept.set(Thread.currentThread().isInterrupted());
        }, "learning-verdict-caller");
        caller.start();
        Process child = awaitOnlyChild();

        caller.interrupt();
        caller.join(20_000);

        assertFalse(caller.isAlive(), "the caller is still waiting");
        assertEquals("KGE subprocess wait was interrupted", verdict.get().errorMessage());
        assertTrue(interruptKept.get(), "the caller's interrupt was cleared");
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child was stopped");
        String runId = onlyRunId();
        assertEquals(List.of(runId + ": caller interrupted"), launcher.stops);
        awaitRunEnded(runId);
    }

    @Test
    void memoryWatchdogRestartIsTheVerdictAndStopsTheChild() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        launcher.useRegistry(registry);
        Future<KgeTrainingResult> result = callers.submit(() -> train("hang"));
        Process child = awaitOnlyChild();

        // What the RSS watchdog does for a child over its limit
        String watchdogId = registry.listAll().stream()
                .filter(info -> info.pid() == child.pid())
                .map(SubprocessInfo::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the child is not registered"));
        registry.getRestartHandler(watchdogId)
                .orElseThrow(() -> new AssertionError("no restart handler under " + watchdogId))
                .requestRestart("RSS over the limit");

        KgeTrainingResult verdict = result.get(20, TimeUnit.SECONDS);
        assertFalse(verdict.success());
        assertEquals("KGE subprocess stopped: RSS over the limit", verdict.errorMessage());
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child was stopped");
        String runId = onlyRunId();
        assertEquals(List.of(runId + ": restart: RSS over the limit"), launcher.stops);
        awaitRunEnded(runId);
    }

    @Test
    void launchAfterShutdownIsRefusedAndLeavesNothingBehind() throws Exception {
        launcher.shutdown();

        KgeTrainingResult kge = launcher.trainOutOfProcess(null, FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE,
                CONFIG, TRIPLES, Map.of("a", vector(1f, 2f)), Map.of("R", vector(5f, 6f)), null);
        LearningResult psl = learnPsl();

        assertFalse(kge.success());
        assertEquals("Launch error: Subprocess 'learning' launcher is shutting down", kge.errorMessage());
        assertFalse(psl.success());
        assertEquals("Launch error: Subprocess 'learning' launcher is shutting down", psl.errorMessage());
        assertEquals(2, launcher.runIds.size(), "runs: " + launcher.runIds);
        assertTrue(launcher.runFiles.get(launcher.runIds.get(0)).containsKey("warmStartEmbeddingsPath"),
                "the refused KGE run had written its warm start");
        for (String runId : launcher.runIds) {
            assertEquals(Map.of(), filesLeft(runId), "files of " + runId);
        }
        assertEquals(Set.of(), launcher.watchedRuns.keySet());
        assertEquals(Map.of(), launcher.children);
    }

    @Test
    void runsStartedInOneMillisecondGetTheirOwnIds() throws Exception {
        // A refused launch still names its run, and is quick enough for racing callers to start
        // several runs in one millisecond
        launcher.shutdown();
        CyclicBarrier together = new CyclicBarrier(4);
        List<Future<?>> racers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            racers.add(callers.submit(() -> {
                together.await(20, TimeUnit.SECONDS);
                for (int launch = 0; launch < 25; launch++) {
                    train("complete");
                }
                return null;
            }));
        }
        for (Future<?> racer : racers) {
            racer.get(40, TimeUnit.SECONDS);
        }

        Map<String, Long> runsPerMillisecond = launcher.runIds.stream()
                .collect(Collectors.groupingBy(runId -> runId.split("-")[2], Collectors.counting()));
        assertTrue(runsPerMillisecond.values().stream().anyMatch(runs -> runs > 1),
                "no two runs started in one millisecond: " + launcher.runIds);
        assertEquals(launcher.runIds.size(), Set.copyOf(launcher.runIds).size(), "run ids: " + launcher.runIds);
        for (String runId : launcher.runIds) {
            assertEquals(Map.of(), filesLeft(runId), "files of " + runId);
        }
        assertEquals(Set.of(), launcher.watchedRuns.keySet());
    }

    @Test
    void warmStartVectorsReachTheChild() throws Exception {
        // A view reads past its own values in its buffer; only its copy holds exactly them
        INDArray copy = vector(3f, 4f);
        DataBuffer shared = buffer(9f, 9f, 3f, 4f);
        INDArray view = mock(INDArray.class);
        when(view.isView()).thenReturn(true);
        when(view.offset()).thenReturn(2L);
        when(view.length()).thenReturn(2L);
        when(view.data()).thenReturn(shared);
        when(view.dup()).thenReturn(copy);
        Map<String, INDArray> entities = new LinkedHashMap<>();
        entities.put("a", vector(1f, 2f));
        entities.put("b", view);
        entities.put("untrained", null);
        launcher.scenario = "echo-warm-start";

        KgeTrainingResult result = launcher.trainOutOfProcess(null, FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE,
                CONFIG, TRIPLES, entities, Map.of("R", vector(5f, 6f)), null);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(JSON.readTree("{\"entities\":{\"a\":[1.0,2.0],\"b\":[3.0,4.0]},\"relations\":{\"R\":[5.0,6.0]}}"),
                JSON.readTree(result.serializedEmbeddingsPath().toFile()));
        awaitRunEnded(onlyRunId(), "outputEmbeddingsPath");
    }

    @Test
    void pslCompletionIsTheVerdict() throws Exception {
        // The child exits while the launcher is still reading its report
        launcher.reportReadDelayMs = SLOW_REPORT_READ_MS;
        launcher.scenario = "complete";

        LearningResult result = learnPsl();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0.125, result.finalLoss());
        assertEquals(3, result.iterations());
        awaitRunEnded(onlyRunId());
    }

    @Test
    void pslChildFailureIsTheVerdictNotItsExit() throws Exception {
        // The child exits while the launcher is still reading its report
        launcher.reportReadDelayMs = SLOW_REPORT_READ_MS;
        launcher.scenario = "fail";

        LearningResult result = learnPsl();

        assertFalse(result.success());
        assertEquals(FakeLearningChild.CHILD_FAILURE, result.errorMessage());
        awaitRunEnded(onlyRunId());
    }

    @Test
    void mebnNonZeroExitFailsWithItsCode() throws Exception {
        launcher.scenario = "exit7";

        LearningResult result = launcher.runMebnLearning(null, FACT_SHEET_ID, List.of("a->b"), List.of(0.5),
                new double[][]{{1.0}}, new double[][]{{1.0}}, tmp.resolve("mebn-weights.json").toString(),
                1, 0.1, null);

        assertFalse(result.success());
        assertEquals("MEBN subprocess exited with code 7 without a completion message", result.errorMessage());
        awaitRunEnded(onlyRunId());
    }

    @Test
    void progressBehindTheVerdictIsNotPassedOn() throws Exception {
        List<Integer> epochsPassedOn = new CopyOnWriteArrayList<>();
        launcher.scenario = "fail-then-progress";

        KgeTrainingResult result = launcher.trainOutOfProcess("job", FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE,
                CONFIG, TRIPLES, (jobId, epoch, totalEpochs, loss) -> epochsPassedOn.add(epoch));

        assertEquals(FakeLearningChild.CHILD_FAILURE, result.errorMessage());
        String runId = onlyRunId();
        awaitRunEnded(runId);
        assertEquals(200, reportsOf(runId).stream().filter(LearningSubprocessMessage.Progress.class::isInstance).count(),
                "progress the launcher read after the verdict");
        assertEquals(List.of(), epochsPassedOn);
    }

    @Test
    void pslProgressBehindTheVerdictIsNotPassedOn() throws Exception {
        List<Integer> epochsPassedOn = new CopyOnWriteArrayList<>();
        launcher.scenario = "fail-then-progress";

        LearningResult result = learnPsl("job", (jobId, epoch, totalEpochs, loss) -> epochsPassedOn.add(epoch));

        assertEquals(FakeLearningChild.CHILD_FAILURE, result.errorMessage());
        String runId = onlyRunId();
        awaitRunEnded(runId);
        assertEquals(200, reportsOf(runId).stream().filter(LearningSubprocessMessage.Progress.class::isInstance).count(),
                "progress the launcher read after the verdict");
        assertEquals(List.of(), epochsPassedOn);
    }

    @Test
    void concurrentRunsKeepTheirOwnFiles() throws Exception {
        CyclicBarrier together = new CyclicBarrier(3);
        List<Future<KgeTrainingResult>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(callers.submit(() -> {
                together.await(20, TimeUnit.SECONDS);
                return train("complete");
            }));
        }

        Set<Path> embeddings = new HashSet<>();
        for (Future<KgeTrainingResult> result : results) {
            KgeTrainingResult verdict = result.get(40, TimeUnit.SECONDS);
            assertTrue(verdict.success(), verdict.errorMessage());
            embeddings.add(verdict.serializedEmbeddingsPath());
            assertEquals(FakeLearningChild.TRAINED_EMBEDDINGS, Files.readString(verdict.serializedEmbeddingsPath()));
        }
        assertEquals(3, embeddings.size(), "embeddings: " + embeddings);
        assertEquals(3, Set.copyOf(launcher.runIds).size(), "run ids: " + launcher.runIds);
        for (String runId : launcher.runIds) {
            awaitRunEnded(runId, "outputEmbeddingsPath");
        }
    }

    // ==================== helpers ====================

    private KgeTrainingResult train(String scenario) {
        launcher.scenario = scenario;
        return launcher.trainOutOfProcess(FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE, CONFIG, TRIPLES);
    }

    private LearningResult learnPsl() throws IOException {
        return learnPsl(null, null);
    }

    private LearningResult learnPsl(String crawlJobId, ReasoningLearningExecutor.ProgressCallback callback)
            throws IOException {
        String weightStore = Files.createDirectories(tmp.resolve("psl-weights")).toString();
        return launcher.runPslLearning(crawlJobId, FACT_SHEET_ID, List.of("0.5: A(X) -> B(X)"), Map.of("A(x)", 1.0),
                List.of("B(x)"), Map.of("B(x)", 1.0), "program", weightStore, 1, 0.1, 1e-3, 16, 42L,
                0.0, 0.5, null, callback);
    }

    private String onlyRunId() {
        assertEquals(1, launcher.runIds.size(), "runs: " + launcher.runIds);
        return launcher.runIds.get(0);
    }

    private Process awaitOnlyChild() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (launcher.children.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, launcher.children.size(), "children: " + launcher.children.keySet());
        return launcher.children.values().iterator().next();
    }

    /**
     * A run has ended once the files it made for its child are gone; deleting them is the last
     * thing it does, after it has come off the stall watch and out of the launcher's active runs.
     * The files named in {@code kept} are handed to the caller instead.
     */
    private void awaitRunEnded(String runId, String... kept) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!filesLeft(runId, kept).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(Map.of(), filesLeft(runId, kept), "the run " + runId + " never ended");
        assertFalse(launcher.watchedRuns.containsKey(runId), "the ended run " + runId + " is still watched for stalls");
        assertFalse(launcher.isActive(runId), "the ended run " + runId + " is still an active run");
    }

    private Map<String, Path> filesLeft(String runId, String... kept) {
        Set<String> handedOver = Set.of(kept);
        Map<String, Path> left = new TreeMap<>();
        launcher.runFiles.getOrDefault(runId, Map.of()).forEach((name, file) -> {
            if (!handedOver.contains(name) && Files.exists(file)) {
                left.put(name, file);
            }
        });
        return left;
    }

    /** The reports the launcher read from a run's child, in the order it read them. */
    private List<LearningSubprocessMessage> reportsOf(String runId) throws JsonProcessingException {
        List<LearningSubprocessMessage> reports = new ArrayList<>();
        for (String payload : launcher.reports.getOrDefault(runId, List.of())) {
            reports.add(JSON.readValue(payload, LearningSubprocessMessage.class));
        }
        return reports;
    }

    /** A vector whose buffer holds exactly its values. */
    private static INDArray vector(float... values) {
        DataBuffer data = buffer(values);
        INDArray vector = mock(INDArray.class);
        when(vector.length()).thenReturn((long) values.length);
        when(vector.data()).thenReturn(data);
        return vector;
    }

    private static DataBuffer buffer(float... values) {
        DataBuffer buffer = mock(DataBuffer.class);
        when(buffer.length()).thenReturn((long) values.length);
        when(buffer.asFloat()).thenReturn(values);
        return buffer;
    }

    /** The real launcher with {@link FakeLearningChild} as its child, recording what each run did. */
    static final class FakeChildLauncher extends LearningSubprocessLauncher {

        /** Args-file entries naming files the launcher made for a run. A PSL or MEBN weights path is its caller's. */
        private static final List<String> RUN_FILE_KEYS =
                List.of("triplesFilePath", "outputEmbeddingsPath", "warmStartEmbeddingsPath", "inputFilePath");

        volatile String scenario = "complete";
        final List<String> runIds = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, Path>> runFiles = new ConcurrentHashMap<>();
        final Map<String, List<String>> reports = new ConcurrentHashMap<>();
        final Map<String, Process> children = new ConcurrentHashMap<>();
        final List<String> stops = new CopyOnWriteArrayList<>();
        /** Set to have the next stop run another stale check first, while that run's child is still alive. */
        final AtomicBoolean checkWhileStopping = new AtomicBoolean();
        /**
         * How late the launcher reads each report of its child, as a busy launcher can: the child is
         * then gone before its last lines are read, and the launcher must read them before it judges
         * the exit.
         */
        volatile long reportReadDelayMs;

        @Override
        protected String getMainClass() {
            return FakeLearningChild.class.getName();
        }

        @Override
        protected int getHeapMb() {
            return 64;
        }

        @Override
        protected List<String> getExtraJvmArgs() {
            return List.of("-D" + FakeLearningChild.SCENARIO_PROPERTY + "=" + scenario);
        }

        @Override
        protected ManagedRun startProcess(String runId, String jobId, List<String> programArgs,
                                          StructuredLineHandler structuredHandler) throws IOException {
            // Recorded before the launch, so a refused launch is seen too
            runIds.add(runId);
            runFiles.put(runId, filesNamedIn(Path.of(programArgs.get(0))));
            List<String> read = new CopyOnWriteArrayList<>();
            reports.put(runId, read);
            ManagedRun run = super.startProcess(runId, jobId, programArgs, payload -> {
                readLate();
                read.add(payload);
                structuredHandler.onStructured(payload);
            });
            children.put(runId, run.process());
            return run;
        }

        /** Whether the launcher still holds the run among its active runs. */
        boolean isActive(String runId) {
            return activeRuns.containsKey(runId);
        }

        /** Wires the registry the memory watchdog reads, as Spring does in the app. */
        void useRegistry(SubprocessRegistry registry) {
            subprocessRegistry = registry;
        }

        private void readLate() {
            try {
                Thread.sleep(reportReadDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        protected void stop(String runId, String reason) {
            stops.add(runId + ": " + reason);
            if (checkWhileStopping.getAndSet(false)) {
                checkForStaleProcesses();
            }
            super.stop(runId, reason);
        }

        private static Map<String, Path> filesNamedIn(Path argsFile) throws IOException {
            Map<String, Path> files = new TreeMap<>();
            files.put("argsFile", argsFile);
            Map<?, ?> args = JSON.readValue(argsFile.toFile(), Map.class);
            for (String key : RUN_FILE_KEYS) {
                if (args.get(key) instanceof String file) {
                    files.put(key, Path.of(file));
                }
            }
            return files;
        }
    }
}
