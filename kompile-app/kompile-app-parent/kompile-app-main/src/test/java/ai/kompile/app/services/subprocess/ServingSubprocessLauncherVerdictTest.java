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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.cli.common.logs.AgentLogReader;
import ai.kompile.cli.common.logs.SubprocessLogMetadata;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A serving child ends once, and however it ends it is reported and cleaned up. A launch that fails
 * deletes the files made for it. A child that exits before it is ready, or after it has served, is
 * reported with its exit code and the cause from its output, and its run log ends FAILED. A stop
 * does not wait on a child that will not unload, and a killed child is gone before its GPU row is
 * released. No child starts once the launcher is shut down. A launch that fails once its child has
 * started stops that child, and a stop that fails part-way does not refuse the next load.
 * {@link FakeServingChild} runs through the launcher's real launch, readiness, exit, and stop paths.
 */
@Timeout(60)
class ServingSubprocessLauncherVerdictTest {

    private static final String MODEL_ID = "fake-model";
    /** Well inside the 5 s the launcher waits for its child's output once the child has exited. */
    private static final long SLOW_OUTPUT_READ_MS = 100;
    private static final String RESTART_THREAD = "serving-watchdog-restart";
    /** What the JVM reports when the machine has no room for another thread. */
    private static final String NO_NATIVE_THREAD = "unable to create native thread";

    @TempDir
    Path tmp;

    private String userHome;
    private String userDir;
    private Path workDir;
    private int port;
    private FakeChildLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        // Keep anything written under user.home or user.dir, the run logs among it, in the temp dir
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        workDir = Files.createDirectories(tmp.resolve("work/.kompile")).getParent();
        System.setProperty("user.dir", workDir.toString());

        port = freePort();
        ServiceEndpointsConfigManager endpoints =
                new ServiceEndpointsConfigManager(tmp.resolve(ServiceEndpointsConfigManager.FILENAME));
        // Staging too, so nothing here can reach a staging server running on this machine
        endpoints.update(Map.of(
                ServiceEndpointsConfigManager.STAGING_URL_KEY,
                "http://" + ServingSubprocessLauncher.SERVING_BIND_HOST + ":" + freePort(),
                ServiceEndpointsConfigManager.SERVING_URL_KEY,
                "http://" + ServingSubprocessLauncher.SERVING_BIND_HOST + ":" + port));
        launcher = new FakeChildLauncher(tmp);
        launcher.setEndpointConfigManager(endpoints);
        launcher.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            launcher.shutdown();
            for (Process child : launcher.children) {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child " + child.pid() + " is still running");
            }
        } finally {
            // A failing test may leave its child or files behind; they are removed by exact handle and path only
            for (Process child : launcher.children) {
                if (child.isAlive()) {
                    child.toHandle().destroyForcibly();
                }
            }
            System.setProperty("user.home", userHome);
            System.setProperty("user.dir", userDir);
            for (Path argsFile : launcher.argsFiles) {
                Files.deleteIfExists(argsFile);
            }
        }
    }

    @Test
    void launchThatCannotStartItsChildDeletesItsFiles() throws Exception {
        launcher.missingExecutable = true;

        assertThrows(IOException.class, this::load);

        assertEquals(List.of(), launcher.children);
        assertLaunchFilesDeleted(0);
        assertFalse(launcher.isRunning());

        // Nothing of the failed launch is in the way of the next one
        launcher.missingExecutable = false;
        load();
        assertTrue(launcher.isRunning());
        assertEquals(MODEL_ID, launcher.getActiveModelId());
    }

    @Test
    void launchThatFailsWithAnErrorDeletesItsFiles() throws Exception {
        launcher.startError = new OutOfMemoryError(NO_NATIVE_THREAD);

        OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, this::load);

        assertEquals(NO_NATIVE_THREAD, failure.getMessage());
        assertEquals(List.of(), launcher.children);
        assertLaunchFilesDeleted(0);
        assertFalse(launcher.isRunning());
    }

    @Test
    void launchThatFailsOnceItsChildStartedStopsTheChild() throws Exception {
        // Fails between the start of the child and its readiness, as starting its output readers can
        SubprocessRegistry registry = mock(SubprocessRegistry.class);
        doThrow(new OutOfMemoryError(NO_NATIVE_THREAD))
                .when(registry).register(anyString(), any(Process.class), anyString());
        launcher.subprocessRegistry = registry;

        assertThrows(OutOfMemoryError.class, this::load);

        Process child = onlyChild();
        assertFalse(child.isAlive(), "the child was left running");
        assertFalse(launcher.isRunning());
        assertLaunchFilesDeleted(0);
        SubprocessLogMetadata run = runOf(child);
        assertEquals("FAILED", run.getState());
        assertTrue(run.getErrorMessage().contains(NO_NATIVE_THREAD), run.getErrorMessage());
    }

    @Test
    void noChildStartsOnceTheLauncherIsShutDown() throws Exception {
        launcher.shutdown();

        assertThrows(IllegalStateException.class, this::load);

        assertEquals(List.of(), launcher.argsFiles);
        assertEquals(List.of(), launcher.children);
    }

    @Test
    void watchdogRestartAfterShutdownStartsNoChild() throws Exception {
        load();
        Process child = onlyChild();

        launcher.shutdown();
        launcher.requestRestart("memory watchdog");
        awaitNoRestartThread();

        assertEquals(List.of(child), launcher.children);
        assertFalse(launcher.isRunning());
        assertLaunchFilesDeleted(0);
    }

    @Test
    void exitBeforeReadyIsReportedWithTheCauseFromItsOutput() throws Exception {
        launcher.scenario = FakeServingChild.CRASH_BEFORE_READY;
        // The child is gone before its output has been read; the launcher reads to the end before it reports
        launcher.outputReadDelayMs = SLOW_OUTPUT_READ_MS;

        IOException failure = assertThrows(IOException.class, this::load);

        assertTrue(failure.getMessage().contains("exited prematurely with code " + FakeServingChild.EXIT_BEFORE_READY),
                failure.getMessage());
        assertTrue(failure.getMessage().contains(FakeServingChild.MISSING_NATIVE_LIBRARY), failure.getMessage());
        assertFalse(launcher.isRunning());
        assertLaunchFilesDeleted(0);
        SubprocessLogMetadata run = runOf(onlyChild());
        assertEquals("FAILED", run.getState());
        assertEquals(FakeServingChild.EXIT_BEFORE_READY, run.getExitCode());
        assertTrue(run.getErrorMessage().contains(FakeServingChild.MISSING_NATIVE_LIBRARY), run.getErrorMessage());
    }

    @Test
    void exitAfterReadyIsReportedOnceAndTheNextLoadStartsAFreshChild() throws Exception {
        load();
        Process child = onlyChild();

        askChildToExit();
        assertTrue(child.waitFor(20, TimeUnit.SECONDS), "the child did not exit");
        awaitLaunchFilesDeleted(0);

        assertFalse(launcher.isRunning());
        assertNull(launcher.getActiveModelId());
        SubprocessLogMetadata ended = runOf(child);
        assertEquals("FAILED", ended.getState());
        assertEquals(FakeServingChild.EXIT_AFTER_READY, ended.getExitCode());
        assertTrue(ended.getErrorMessage().contains("exited unexpectedly with code " + FakeServingChild.EXIT_AFTER_READY),
                ended.getErrorMessage());
        assertTrue(ended.getErrorMessage().contains(FakeServingChild.DEVICE_FAULT), ended.getErrorMessage());

        // A stop after the exit leaves its verdict alone
        launcher.stop();
        assertEquals(ended, runOf(child));

        load();
        assertEquals(2, launcher.children.size());
        assertTrue(launcher.isRunning());
        assertEquals(MODEL_ID, launcher.getActiveModelId());
    }

    @Test
    void stopDoesNotWaitOnAChildThatNeverUnloads() throws Exception {
        launcher.scenario = FakeServingChild.HANG_UNLOAD;
        launcher.unloadTimeout = Duration.ofSeconds(1);
        load();
        Process child = onlyChild();

        long started = System.nanoTime();
        launcher.stop();
        Duration stopping = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(stopping.compareTo(Duration.ofSeconds(10)) < 0, "stop took " + stopping);
        assertFalse(child.isAlive());
        assertLaunchFilesDeleted(0);
    }

    @Test
    void stopThatFailsPartWayDoesNotRefuseTheNextLoad() throws Exception {
        SubprocessRegistry registry = mock(SubprocessRegistry.class);
        // The first stop fails after its child is gone, as it can on a machine out of memory
        doThrow(new OutOfMemoryError("Java heap space")).doNothing()
                .when(registry).deregister("serving");
        launcher.subprocessRegistry = registry;
        load();
        Process first = onlyChild();

        assertThrows(OutOfMemoryError.class, launcher::stop);
        assertFalse(first.isAlive());

        load();
        assertEquals(2, launcher.children.size());
        assertTrue(launcher.isRunning());
        assertEquals(MODEL_ID, launcher.getActiveModelId());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a child can ignore a terminate request only on POSIX")
    void killedChildIsGoneBeforeItsGpuRowIsReleased() throws Exception {
        List<Boolean> childAliveAtRelease = new CopyOnWriteArrayList<>();
        ResourceAwareJobScheduler scheduler = mock(ResourceAwareJobScheduler.class);
        when(scheduler.cancel(anyString())).thenAnswer(invocation -> {
            childAliveAtRelease.add(launcher.children.get(0).isAlive());
            return true;
        });
        launcher.resourceScheduler = scheduler;
        launcher.scenario = FakeServingChild.IGNORE_TERM;
        // The kill takes effect late, as it can for a child still releasing its device
        launcher.killedExitDelayMs = 500;
        load();

        launcher.stop();

        assertEquals(List.of(false), childAliveAtRelease);
        assertLaunchFilesDeleted(0);
    }

    // ==================== helpers ====================

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String load() throws IOException, InterruptedException {
        return launcher.loadModel(MODEL_ID, tmp.resolve("model").toString(), null);
    }

    private Process onlyChild() {
        assertEquals(1, launcher.children.size(), "children: " + launcher.children);
        return launcher.children.get(0);
    }

    private void askChildToExit() throws IOException {
        HttpURLConnection exit = (HttpURLConnection) URI.create("http://" + ServingSubprocessLauncher.SERVING_BIND_HOST
                + ":" + port + FakeServingChild.EXIT_PATH).toURL().openConnection();
        exit.setRequestMethod("POST");
        try {
            assertEquals(200, exit.getResponseCode());
        } finally {
            exit.disconnect();
        }
    }

    /** The files made for a launch are gone. */
    private void assertLaunchFilesDeleted(int launch) {
        Path argsFile = launcher.argsFiles.get(launch);
        Path tempDir = launcher.tempDirs.get(launch);
        assertFalse(Files.exists(argsFile), "args file left behind: " + argsFile);
        assertFalse(Files.exists(tempDir), "temp dir left behind: " + tempDir);
    }

    /** Deleting the files made for a launch is the last thing the report of its child's exit does. */
    private void awaitLaunchFilesDeleted(int launch) throws InterruptedException {
        Path argsFile = launcher.argsFiles.get(launch);
        Path tempDir = launcher.tempDirs.get(launch);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while ((Files.exists(argsFile) || Files.exists(tempDir)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertLaunchFilesDeleted(launch);
    }

    private static void awaitNoRestartThread() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (restartThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(restartThreadAlive(), "the watchdog restart is still running");
    }

    private static boolean restartThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && RESTART_THREAD.equals(thread.getName()));
    }

    /** The run log the launcher kept for a child. */
    private SubprocessLogMetadata runOf(Process child) {
        List<SubprocessLogMetadata> runs = AgentLogReader.listSubprocessRuns(workDir,
                new AgentLogReader.SubprocessRunFilter("serving", null, null, null));
        return runs.stream()
                .filter(run -> Long.valueOf(child.pid()).equals(run.getPid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no run log for PID " + child.pid() + " among " + runs));
    }

    /** The real launcher with {@link FakeServingChild} as its child, recording what each launch made. */
    static final class FakeChildLauncher extends ServingSubprocessLauncher {

        private final Path tmp;
        volatile String scenario = FakeServingChild.READY;
        /** Set to name a Java executable that does not exist, so the child cannot start. */
        volatile boolean missingExecutable;
        /**
         * How late the launcher reads each line of its child's output, as a busy launcher can: the
         * child is then gone before its last lines are read.
         */
        volatile long outputReadDelayMs;
        /** How long a kill takes to stop the child; 0 kills it at once. */
        volatile long killedExitDelayMs;
        /** Thrown in place of starting the child, when set. */
        volatile Error startError;
        final List<Path> argsFiles = new CopyOnWriteArrayList<>();
        final List<Path> tempDirs = new CopyOnWriteArrayList<>();
        final List<Process> children = new CopyOnWriteArrayList<>();

        FakeChildLauncher(Path tmp) {
            this.tmp = tmp;
        }

        @Override
        List<String> buildCommand(Path argsFile, Nd4jEnvironmentConfig nd4jConfig) throws IOException {
            argsFiles.add(argsFile);
            // The native libraries a real child extracts
            subprocessTempDir = Files.createTempDirectory(tmp, "serving-subprocess-javacpp-");
            Files.writeString(subprocessTempDir.resolve("libjnind4jcuda.so"), "");
            tempDirs.add(subprocessTempDir);
            Path java = missingExecutable
                    ? tmp.resolve("missing").resolve("java")
                    : Path.of(System.getProperty("java.home"), "bin", "java");
            return List.of(java.toString(), "-Xmx64m",
                    "-D" + FakeServingChild.SCENARIO_PROPERTY + "=" + scenario,
                    "-cp", System.getProperty("java.class.path"),
                    FakeServingChild.class.getName(), argsFile.toAbsolutePath().toString());
        }

        @Override
        Process startProcess(ProcessBuilder pb) throws IOException {
            if (startError != null) {
                throw startError;
            }
            Process child = super.startProcess(pb);
            Process started = killedExitDelayMs > 0 ? new SlowToDieProcess(child, killedExitDelayMs) : child;
            children.add(started);
            return started;
        }

        @Override
        void recordRecentOutput(String line) {
            if (outputReadDelayMs > 0) {
                try {
                    Thread.sleep(outputReadDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.recordRecentOutput(line);
        }
    }

    /** A child that dies some time after it is killed. Everything else goes to the real child. */
    static final class SlowToDieProcess extends Process {

        private final Process child;
        private final long killDelayMs;

        SlowToDieProcess(Process child, long killDelayMs) {
            this.child = child;
            this.killDelayMs = killDelayMs;
        }

        @Override
        public Process destroyForcibly() {
            Thread kill = new Thread(() -> {
                try {
                    Thread.sleep(killDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    child.destroyForcibly();
                }
            }, "slow-to-die-kill");
            kill.setDaemon(true);
            kill.start();
            return this;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            // The launcher knows its child by this process, so the exit is reported with it
            return child.onExit().thenApply(exited -> this);
        }

        @Override
        public OutputStream getOutputStream() {
            return child.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return child.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return child.getErrorStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return child.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return child.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return child.exitValue();
        }

        @Override
        public void destroy() {
            child.destroy();
        }

        @Override
        public boolean supportsNormalTermination() {
            return child.supportsNormalTermination();
        }

        @Override
        public boolean isAlive() {
            return child.isAlive();
        }

        @Override
        public long pid() {
            return child.pid();
        }

        @Override
        public ProcessHandle toHandle() {
            return child.toHandle();
        }
    }
}
