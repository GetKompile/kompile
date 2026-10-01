/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.embedding.anserini;

import ai.kompile.embedding.anserini.config.EmbeddingRestartConfig;
import ai.kompile.embedding.anserini.config.EmbeddingRestartConfigService;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Device-error failover of the embedding model. The embedding child exits with DEVICE_ERROR (78)
 * when its CUDA context is poisoned, e.g. by error 700.
 *
 * <p>A DEVICE_ERROR exit used to end the lane at once, although a new process gets a new CUDA
 * context. The model's restart policy now restarts it like any other crash, until the churn
 * ceiling pauses restarts.
 *
 * <p>Once the launcher gave up on a child, the model still counted itself initialized: every embed
 * returned nothing and the auto-init poll stopped, so nothing brought the lane back. The model now
 * treats a lane its launcher gave up on as down, and the next ensureInitialized() releases it, so a
 * new launcher, with a new CUDA context, takes over once restarts are allowed.
 *
 * <p>A crash that arrives during a GPU preemption is declined for the preemption, not given up on.
 * The model used to pause restarts for it too, which kept the lane down after the preemption ended.
 *
 * <p>The child is a shell script that records its start and exits 78 on its first request, so no
 * ND4J is loaded on either side. A test that lets the model's own start path run first checks that
 * restarts are paused: that path would otherwise launch a real embedding subprocess.
 */
@DisplayName("AnseriniEmbeddingModelImpl device-error failover")
class DeviceErrorFailoverTest {

    private static final int DEVICE_ERROR = EmbeddingSubprocessLauncher.DEVICE_ERROR_EXIT_CODE;

    /** The first line of the crash reason the launcher builds for a DEVICE_ERROR exit. */
    private static final String DEVICE_ERROR_REASON = "Exit code: " + DEVICE_ERROR
            + " (device error: CUDA context poisoned, e.g. error 700; a new process gets a new context)";

    @TempDir
    Path tempDir;

    private final List<EmbeddingSubprocessLauncher> launchers = new ArrayList<>();
    private Path script;
    private String savedUserHome;

    @BeforeEach
    void setUp() throws IOException {
        // Crash diagnostics go to user.home; the run log goes to the nearest .kompile above the
        // working directory.
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tempDir.resolve("home")).toString());
        Files.createDirectories(tempDir.resolve(".kompile"));

        // Stands in for the native binary and ignores its flags.
        script = tempDir.resolve("device-error.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "echo started >> \"$FAKE_DIR/launches\"\n"
                + "read line\n"
                + "exit " + DEVICE_ERROR + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @AfterEach
    void tearDown() {
        try {
            for (EmbeddingSubprocessLauncher launcher : launchers) {
                launcher.stop();
            }
        } finally {
            System.setProperty("user.home", savedUserHome);
        }
    }

    @Test
    @DisplayName("a DEVICE_ERROR exit is restarted with backoff until the churn ceiling pauses restarts")
    void aDeviceErrorExitIsRestartedUntilTheChurnCeiling() {
        AnseriniEmbeddingModelImpl model = newModel();
        EmbeddingSubprocessLauncher.RestartPolicyCallback policy = model.createRestartPolicyCallbackForTest();

        EmbeddingSubprocessLauncher.RestartConfiguration first =
                policy.shouldRestart("t", DEVICE_ERROR, DEVICE_ERROR_REASON, 1);
        assertNotNull(first, "a new process gets a new CUDA context");
        assertEquals("DEVICE_ERROR", first.reason());
        assertEquals(5_000, first.backoffMs());

        EmbeddingSubprocessLauncher.RestartConfiguration second =
                policy.shouldRestart("t", DEVICE_ERROR, DEVICE_ERROR_REASON, 2);
        assertNotNull(second);
        assertEquals(10_000, second.backoffMs());
        assertFalse(model.isRestartsPaused());

        assertNull(policy.shouldRestart("t", DEVICE_ERROR, DEVICE_ERROR_REASON, 3),
                "a context poisoned again after every restart is not restarted forever");
        assertTrue(model.isRestartsPaused());
    }

    @Test
    @DisplayName("a lane the launcher gave up on is not ready, and ensureInitialized() releases it")
    void aLaneTheLauncherGaveUpOnIsReleased() throws Exception {
        AnseriniEmbeddingModelImpl model = newModel();
        overADeadLane(model);

        // Nothing has released the model yet
        assertTrue(model.initialized);
        assertFalse(model.isInitialized(), "a lane the launcher gave up on is not ready");
        assertEquals(Boolean.FALSE, model.getSubprocessStatus().get("initialized"));
        // Only while the model is loaded: its dimensions then come from the load, not the registry
        assertEquals(Boolean.FALSE, model.getModelInfo().get("initialized"));

        assertFalse(model.initializeIfNeeded());
        assertFalse(model.initialized, "the dead lane was released");
        assertEquals("NOT_INITIALIZED", model.getSubprocessStatus().get("modelSource"));
        assertEquals("IDLE", model.getLoadingPhase());
        String message = model.getLoadingMessage();
        assertNotNull(message);
        assertTrue(message.contains("Exit code: " + DEVICE_ERROR), message);
        assertTrue(String.valueOf(model.getSubprocessStatus().get("lastCrashReason"))
                .contains("Exit code: " + DEVICE_ERROR), "the status still says why the lane went down");
        assertTrue(model.isRestartDeferred());
        assertEquals(1, launches(), "no child is started while restarts are paused");
    }

    @Test
    @DisplayName("switching to the loaded model over a lane the launcher gave up on does not report it loaded")
    void switchingOverADeadLaneDoesNotReportTheModelLoaded() throws Exception {
        AnseriniEmbeddingModelImpl model = newModel();
        overADeadLane(model);

        assertFalse(model.switchModel("bge-base-en-v1.5"));
        assertFalse(model.initialized);
        assertEquals(1, launches(), "no child is started while restarts are paused");
    }

    @Test
    @DisplayName("the auto-init poll goes on for a model whose lane the launcher gave up on")
    void thePollGoesOnOverADeadLane() throws Exception {
        AnseriniEmbeddingModelImpl model = newModel();
        // No model policy: the launcher gives up on its own, and restarts stay allowed. Neither
        // initializeIfNeeded() nor switchModel() may run here: they would start a real subprocess.
        EmbeddingSubprocessLauncher launcher = deviceErrorLauncher();
        launcher.setRestartConfig(0, 10, 1.0);
        model.subprocessLauncher = launcher;
        model.initialized = true;

        launcher.start();
        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        await(launcher::isLaneUnavailable, "the lane to be marked down");

        assertFalse(model.isRestartsPaused());
        assertTrue(model.shouldContinuePolling(), "the poll is what brings up a new lane");
        assertFalse(model.isInitialized());
    }

    @Test
    @DisplayName("a restart declined for a preemption does not pause restarts")
    void aRestartDeclinedForAPreemptionDoesNotPauseRestarts() {
        AnseriniEmbeddingModelImpl model = newModel();
        EmbeddingSubprocessLauncher.RestartPolicyCallback policy = model.createRestartPolicyCallbackForTest();
        // No launcher yet, so the preemption stops nothing. Neither resumeFromPreemption() nor
        // resumeRestarts() may run here: they would start a real subprocess.
        model.suspendForPreemption("test preemption");

        // A crash handler that was already running when the preemption stopped the lane
        assertNull(policy.shouldRestart("t", DEVICE_ERROR, DEVICE_ERROR_REASON, 1));
        policy.onRestartExhausted("t", 1, DEVICE_ERROR_REASON);

        assertFalse(model.isRestartsPaused(), "restarts would stay paused after the preemption ends");
    }

    @Test
    @DisplayName("preemption during configuration lookup neither consumes a failure nor pauses restarts")
    void preemptionDuringPolicyEvaluationDoesNotTripGovernor() throws Exception {
        AnseriniEmbeddingModelImpl model = newModel();
        model.setConsecutiveFailuresForTest(2);
        CountDownLatch readingConfig = new CountDownLatch(1);
        CountDownLatch releaseConfig = new CountDownLatch(1);
        model.restartConfigService = new EmbeddingRestartConfigService(tempDir.toString()) {
            @Override
            public EmbeddingRestartConfig getConfig() {
                readingConfig.countDown();
                try {
                    if (!releaseConfig.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("configuration lookup was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return EmbeddingRestartConfig.defaults();
            }
        };
        EmbeddingSubprocessLauncher.RestartPolicyCallback policy = model.createRestartPolicyCallbackForTest();
        FutureTask<EmbeddingSubprocessLauncher.RestartConfiguration> decision = new FutureTask<>(
                () -> policy.shouldRestart("t", DEVICE_ERROR, DEVICE_ERROR_REASON, 1));
        Thread worker = new Thread(decision, "preempted-restart-policy-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(readingConfig.await(5, TimeUnit.SECONDS));
            model.suspendForPreemption("preempted while reading configuration");
        } finally {
            releaseConfig.countDown();
        }
        assertNull(decision.get(10, TimeUnit.SECONDS));
        assertFalse(model.isRestartsPaused());
        assertEquals(2, model.consecutiveFailures.get(), "preemption is not a restart failure");
    }

    @Test
    @DisplayName("an obsolete policy configuration lookup cannot trip the replacement's governor")
    void anObsoletePolicyCannotTripTheReplacementGovernor() throws Exception {
        AnseriniEmbeddingModelImpl model = newModel();
        model.subprocessLauncher = deviceErrorLauncher();
        model.setConsecutiveFailuresForTest(2);
        CountDownLatch readingConfig = new CountDownLatch(1);
        CountDownLatch releaseConfig = new CountDownLatch(1);
        model.restartConfigService = new EmbeddingRestartConfigService(tempDir.toString()) {
            @Override
            public EmbeddingRestartConfig getConfig() {
                readingConfig.countDown();
                try {
                    if (!releaseConfig.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("configuration lookup was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return EmbeddingRestartConfig.defaults();
            }
        };
        EmbeddingSubprocessLauncher.RestartPolicyCallback old = model.createRestartPolicyCallbackForTest();
        FutureTask<EmbeddingSubprocessLauncher.RestartConfiguration> decision = new FutureTask<>(
                () -> old.shouldRestart("old", DEVICE_ERROR, DEVICE_ERROR_REASON, 1));
        Thread worker = new Thread(decision, "obsolete-restart-policy-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(readingConfig.await(5, TimeUnit.SECONDS));
            model.cleanup();
            model.subprocessLauncher = deviceErrorLauncher();
            model.subprocessLauncher.setRestartPolicyCallback(model.createRestartPolicyCallbackForTest());
        } finally {
            releaseConfig.countDown();
        }
        assertNull(decision.get(10, TimeUnit.SECONDS));
        assertFalse(model.isRestartsPaused(), "the old lane must not pause its replacement");
        assertEquals(2, model.consecutiveFailures.get(), "the old lane must not consume the new lane's budget");
    }

    @Test
    @DisplayName("late exhaustion from a replaced launcher cannot pause its replacement")
    void obsoleteExhaustionCannotPauseItsReplacement() {
        AnseriniEmbeddingModelImpl model = newModel();
        model.subprocessLauncher = deviceErrorLauncher();
        EmbeddingSubprocessLauncher.RestartPolicyCallback old = model.createRestartPolicyCallbackForTest();
        model.cleanup();
        model.subprocessLauncher = deviceErrorLauncher();
        EmbeddingSubprocessLauncher.RestartPolicyCallback current = model.createRestartPolicyCallbackForTest();

        old.onRestartExhausted("old", 3, DEVICE_ERROR_REASON);
        assertFalse(model.isRestartsPaused());
        assertNotNull(current.shouldRestart("current", DEVICE_ERROR, DEVICE_ERROR_REASON, 1));
        assertEquals(1, model.consecutiveFailures.get());
    }

    @Test
    @DisplayName("cleanup invalidates a policy even before a launcher exists")
    void cleanupInvalidatesAPolicyBeforeALauncherExists() {
        AnseriniEmbeddingModelImpl model = newModel();
        EmbeddingSubprocessLauncher.RestartPolicyCallback old = model.createRestartPolicyCallbackForTest();
        model.cleanup();

        assertNull(old.shouldRestart("old", DEVICE_ERROR, DEVICE_ERROR_REASON, 1));
        old.onRestartExhausted("old", 3, DEVICE_ERROR_REASON);
        assertFalse(model.isRestartsPaused());
        assertEquals(0, model.consecutiveFailures.get());
    }

    @Test
    @DisplayName("preemption followed by resume does not revive an old exhaustion callback")
    void resumeDoesNotReviveAnOldPolicy() {
        AnseriniEmbeddingModelImpl model = newModel();
        EmbeddingSubprocessLauncher.RestartPolicyCallback old = model.createRestartPolicyCallbackForTest();
        model.restartConfigService = new EmbeddingRestartConfigService(tempDir.toString()) {
            @Override
            public EmbeddingRestartConfig getConfig() {
                // Resume reaches the normal initialization gate, but must not spawn a real child.
                return EmbeddingRestartConfig.builder().autoRestartEnabled(false).build();
            }
        };
        model.suspendForPreemption("test preemption");
        assertFalse(model.resumeFromPreemption());
        assertFalse(model.isRestartsPaused());

        old.onRestartExhausted("old", 3, DEVICE_ERROR_REASON);
        assertFalse(model.isRestartsPaused(), "preemption ending must not revive an obsolete callback");
        assertEquals(0, model.consecutiveFailures.get());
    }

    private static AnseriniEmbeddingModelImpl newModel() {
        AnseriniEmbeddingModelImpl model = new AnseriniEmbeddingModelImpl("bge-base-en-v1.5", 32, 64, null);
        model.resetRestartGovernorForTest();
        return model;
    }

    /**
     * Put a loaded model over a lane its launcher gave up on, as it ends in production: the child
     * exits DEVICE_ERROR, and the model's policy declines the restart at the churn ceiling and pauses
     * restarts. The pause keeps the model's own start path from launching a real subprocess.
     */
    private EmbeddingSubprocessLauncher overADeadLane(AnseriniEmbeddingModelImpl model) throws Exception {
        model.setConsecutiveFailuresForTest(2); // one short of the churn ceiling
        EmbeddingSubprocessLauncher launcher = deviceErrorLauncher();
        model.subprocessLauncher = launcher;
        launcher.setRestartPolicyCallback(model.createRestartPolicyCallbackForTest());
        // As a successful load leaves it
        model.modelSource = AnseriniEmbeddingModelImpl.ModelSource.REGISTRY;
        model.loadingPhase = AnseriniEmbeddingModelImpl.LoadingPhase.COMPLETE;
        model.embeddingDimensions = 768;
        model.initialized = true;

        launcher.start();
        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        await(launcher::isLaneUnavailable, "the lane to be marked down");
        assertTrue(model.isRestartsPaused(), "restarts must be paused before the model's start path runs");
        return launcher;
    }

    private EmbeddingSubprocessLauncher deviceErrorLauncher() {
        EmbeddingSubprocessLauncher launcher = EmbeddingSubprocessLauncher.builder()
                .launchMode(EmbeddingSubprocessLauncher.LaunchMode.NATIVE_EXECUTABLE)
                .nativeExecutablePath(script.toString())
                .classpath(List.of("unused-in-native-mode"))
                .workingDirectory(tempDir)
                .environment(Map.of("FAKE_DIR", tempDir.toString()))
                .localModelOnly(true)
                .loadModelTimeoutMs(30_000)
                .build();
        launchers.add(launcher);
        return launcher;
    }

    /** How many children the script has started. */
    private int launches() throws IOException {
        Path launches = tempDir.resolve("launches");
        return Files.exists(launches) ? Files.readAllLines(launches).size() : 0;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}
