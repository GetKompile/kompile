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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.ModelLifecycleManager.GpuShortfallException;
import ai.kompile.app.services.ModelLifecycleManager.HoldLifetime;
import ai.kompile.app.services.ModelLifecycleManager.JobGpuHold;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.FailureReason;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.RestartConfig;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.RestartStatus;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every attempt of a vector population task — its first and each OOM restart, which reuse its taskId —
 * runs on the placement the task was launched with, whatever the launcher's shared placement says by
 * then. The task's GPU row is released only by whoever acquired it: never by the launcher for a row the
 * scheduler holds, and once by the launcher for its own row, when the task ends. Cancelling reaches
 * whichever attempt is current or pending. Each attempt's verdict (completed, failed, cancelled, stuck) is
 * reported once, before any kill, and the caller hears it once that attempt has exited. A child's protocol
 * has a pipe of its own, which native output written to fd 1 can't reach; a child that still writes its
 * protocol to fd 1 has it read from stderr. A fake native executable records each child's command line and
 * memory-cap env.
 */
@Timeout(60)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake native executable is a POSIX shell script")
class VectorPopulationSubprocessLauncherPlacementTest {

    private static final String TASK = "vecpop-task";
    private static final String LAUNCH_RECORD = ".launch";
    private static final String DISPATCH = "--subprocess=vector-population";
    private static final String CANCELLED_BY_USER = "Vector population cancelled by user";
    private static final String PROCESS_CANCELLED = "Process cancelled";
    private static final String STOPPED_FOR_SHUTDOWN = "Vector population stopped: the application is shutting down";
    // The mocked config's stale threshold is 0 seconds
    private static final String UNRESPONSIVE = "Process became unresponsive (no heartbeat for 0 seconds)";

    private static final String COMPLETED_MESSAGE = SubprocessMessage.MESSAGE_PREFIX
            + "{\"type\":\"COMPLETED\",\"taskId\":\"t\",\"documentsLoaded\":1,\"chunksCreated\":1,"
            + "\"chunksEmbedded\":1,\"documentsIndexed\":1,\"tokensProcessed\":1,\"totalTokensInIndex\":1,"
            + "\"totalDurationMs\":1,\"indexPath\":null,\"phaseDurations\":{}}";

    // What an attempt of the fake executable does; its `protocol` prints a protocol line
    private static final String COMPLETES = "protocol '" + COMPLETED_MESSAGE + "'; exit 0";
    private static final String OOM_KILLED = "exit 137";
    private static final String RUNS_UNTIL_KILLED = "exec sleep 60";

    @TempDir
    Path tmp;

    private final VectorPopulationProgressTracker vpTracker = mock(VectorPopulationProgressTracker.class);
    private final IngestProgressTracker ingestTracker = mock(IngestProgressTracker.class);
    private final SubprocessConfigService configService = mock(SubprocessConfigService.class);
    private final SubprocessRestartManager restartManager = mock(SubprocessRestartManager.class);
    private final ModelLifecycleManager lifecycle = mock(ModelLifecycleManager.class);
    private final Map<String, JobGpuHold> holds = new ConcurrentHashMap<>();
    private final GpuDevice freeDevice = GpuDevice.local(0, 1, "fake-gpu", 1L << 30);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    /** Every progress update broadcast to the UI, in order. */
    private final List<Map<String, Object>> broadcasts = new CopyOnWriteArrayList<>();

    private Path executable;
    private Path records;
    private String userHome;
    private String userDir;
    private SubprocessLifecycleManager lifecycleManager;
    private VectorPopulationSubprocessLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        // Subprocess logs go under user.dir — keep it and user.home in the temp dir
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        System.setProperty("user.dir", Files.createDirectories(tmp.resolve("work/.kompile")).getParent().toString());
        records = Files.createDirectories(tmp.resolve("records"));
        executable = tmp.resolve("kompile-vector-population");

        when(configService.shouldUseNativeExecutableMode()).thenReturn(true);
        when(configService.useUnifiedExecutable("vector-population")).thenReturn(true);
        when(configService.getSubprocessTypeFlag()).thenReturn("--subprocess=");
        when(configService.getHeapSize()).thenReturn("1g");

        // An OOM-killed attempt is restarted after a short backoff
        when(restartManager.shouldRestart(anyString(), any())).thenReturn(true);
        when(restartManager.getRestartConfig(anyString(), any(FailureReason.class), anyBoolean(), anyLong(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(i -> new RestartConfig(i.getArgument(0), 1, 3, 200L, "1g", 1L << 30, 2L << 30,
                        4, 4, 1, 4, false, false, null));
        when(restartManager.getRestartStatus(anyString()))
                .thenAnswer(i -> new RestartStatus(i.getArgument(0), 0, 3, false, null, false));

        when(lifecycle.getActiveJobHolds()).thenAnswer(i -> Map.copyOf(holds));
        when(lifecycle.hasJobGpuHold(anyString())).thenAnswer(i -> holds.containsKey(i.<String>getArgument(0)));
        doAnswer(i -> {
            holds.remove(i.<String>getArgument(0));
            return null;
        }).when(lifecycle).releaseGpuForJob(anyString());
        doAnswer(i -> {
            holds.remove(i.<String>getArgument(0));
            return null;
        }).when(lifecycle).releaseGpuForVectorPopulation(anyString());

        SubprocessCommandBuilder commandBuilder = spy(new SubprocessCommandBuilder(configService));
        // The child's ND4J environment comes from this JVM's ND4J, which a unit test never initializes
        doNothing().when(commandBuilder).propagateNd4jEnvironment(any(), any());
        VectorPopulationStatsConverter statsConverter = new VectorPopulationStatsConverter(restartManager);
        SubprocessOutputHandler outputHandler =
                new SubprocessOutputHandler(vpTracker, ingestTracker, null, null, statsConverter);
        doAnswer(i -> {
            broadcasts.add(new LinkedHashMap<>(i.<Map<String, Object>>getArgument(1)));
            return null;
        }).when(messaging).convertAndSend(anyString(), any(Object.class));
        lifecycleManager = new SubprocessLifecycleManager(restartManager, configService,
                vpTracker, ingestTracker, null, statsConverter);
        launcher = new VectorPopulationSubprocessLauncher(messaging, null, null, null, configService, null, null,
                vpTracker, ingestTracker, restartManager, null, null, commandBuilder, outputHandler,
                lifecycleManager, statsConverter);
        launcher.modelLifecycleManager = lifecycle;
    }

    @AfterEach
    void tearDown() {
        launcher.shutdownAll();
        System.setProperty("user.home", userHome);
        System.setProperty("user.dir", userDir);
    }

    @Test
    void launcherAcquiredRowIsReleasedOnceWhenTheTaskEnds() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(OOM_KILLED, completesAfter(go));
        acquirable(TASK, freeDevice);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, null);
        verify(vpTracker, timeout(15_000).times(2)).startTask(eq(TASK), any(), any());
        // One row for the task, acquired before its first child was built; the restart runs on it
        verify(lifecycle).acquireGpuForJob(eq(TASK), eq(JobResourceProfiles.VECTOR_POPULATION.serviceType()),
                anyString(), eq(HoldLifetime.BOUNDED), eq(JobResourceProfiles.VECTOR_POPULATION.peakGpuMemoryBytes()),
                isNull());
        verify(lifecycle, never()).releaseGpuForVectorPopulation(any());
        assertTrue(lifecycle.hasJobGpuHold(TASK));

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(lifecycle, timeout(10_000)).releaseGpuForVectorPopulation(TASK);
        verify(lifecycle, after(1500).times(1)).releaseGpuForVectorPopulation(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));

        List<Launch> launches = launchesOf(TASK, 2);
        assertEquals(List.of("first", "restart"), launches.stream().map(Launch::attempt).sorted().toList());
        for (Launch launch : launches) {
            assertLaunchedOn(onFreeDevice(), launch);
        }
    }

    @Test
    void restartKeepsTheSchedulersGpuRow() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(OOM_KILLED, completesAfter(go));
        SubprocessPlacement placement = schedulerHeld(TASK, 1, 3L << 30);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, placement);
        verify(vpTracker, timeout(15_000).times(2)).startTask(eq(TASK), any(), any());
        // The first attempt has ended and the restart is running: the row is still the scheduler's
        assertTrue(lifecycle.hasJobGpuHold(TASK));
        verify(lifecycle, never()).releaseGpuForVectorPopulation(any());

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(lifecycle, after(1500).never()).releaseGpuForVectorPopulation(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
        assertTrue(lifecycle.hasJobGpuHold(TASK));
        for (Launch launch : launchesOf(TASK, 2)) {
            assertLaunchedOn(placement, launch);
        }
    }

    @Test
    void taskThatCannotGetAGpuRowRunsOnCpu() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        when(lifecycle.acquireGpuForJob(eq(TASK), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenThrow(new GpuShortfallException("no room on any device"));

        // A GPU placement without a row behind it is never used as-is
        assertTrue(launch(TASK, SubprocessPlacement.gpu(1, 3L << 30)).get(20, TimeUnit.SECONDS).success());

        Launch launch = launchesOf(TASK, 1).get(0);
        assertEquals(nativeCommandOn(SubprocessPlacement.cpu()), launch.commandBeforeArgsFile());
        assertTrue(launch.command().stream().noneMatch(arg -> arg.startsWith("-Dnd4j.placement.defaultDevice=")));
        verify(lifecycle, after(1500).never()).releaseGpuForVectorPopulation(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
    }

    @Test
    void restartRunsOnTheFirstAttemptsPlacement() throws Exception {
        Path oom = tmp.resolve("oom");
        installExecutable(oomKilledAfter(oom), COMPLETES);
        SubprocessPlacement placement = schedulerHeld(TASK, 1, 3L << 30);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, placement);
        // The shared placement moving on to another task must not reach this task's restart
        launcher.applyPlacement(SubprocessPlacement.gpu(0, 1L << 20));
        Files.createFile(oom);

        assertTrue(result.get(30, TimeUnit.SECONDS).success());
        for (Launch launch : launchesOf(TASK, 2)) {
            assertLaunchedOn(placement, launch);
        }
    }

    @Test
    void cancelWhileARestartIsPendingEndsTheTaskWithoutRelaunching() throws Exception {
        installExecutable(OOM_KILLED, COMPLETES);
        acquirable(TASK, freeDevice);
        // Cancel as the restart is scheduled, before its attempt starts
        doAnswer(i -> {
            launcher.cancelVectorPopulation(TASK);
            return null;
        }).when(ingestTracker).notifyRestartScheduled(eq(TASK), any(), any(), anyInt(), anyInt(), anyLong(), any(),
                anyBoolean(), any(), any(), any());

        VectorPopulationResult cancelled = launch(TASK, null).get(20, TimeUnit.SECONDS);
        assertFalse(cancelled.success());
        assertEquals(CANCELLED_BY_USER, cancelled.errorMessage());
        // The restart's backoff passes without a relaunch
        verify(vpTracker, after(2500).times(1)).startTask(eq(TASK), any(), any());
        assertEquals(1, launches().size());
        verify(lifecycle, timeout(10_000)).releaseGpuForVectorPopulation(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForVectorPopulation(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));
    }

    @Test
    void cancelReachesTheRunningRestart() throws Exception {
        installExecutable(OOM_KILLED, RUNS_UNTIL_KILLED);
        acquirable(TASK, freeDevice);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, null);
        verify(vpTracker, timeout(15_000).times(2)).startTask(eq(TASK), any(), any());
        // The restart is running once it has recorded its launch; a cancel before that kills it unrecorded
        launchesOf(TASK, 2);

        assertTrue(launcher.cancelVectorPopulation(TASK));

        VectorPopulationResult cancelled = result.get(20, TimeUnit.SECONDS);
        assertFalse(cancelled.success());
        assertEquals(PROCESS_CANCELLED, cancelled.errorMessage());
        verify(lifecycle, timeout(10_000)).releaseGpuForVectorPopulation(TASK);
        verify(lifecycle, after(1500).times(1)).releaseGpuForVectorPopulation(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));
        assertEquals(2, launches().size());
    }

    @Test
    void shutdownReleasesTheLaunchersRowOnce() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);
        acquirable(TASK, freeDevice);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, null);
        verify(vpTracker, timeout(10_000)).startTask(eq(TASK), any(), any());

        launcher.shutdownAll();

        VectorPopulationResult cancelled = result.get(20, TimeUnit.SECONDS);
        assertFalse(cancelled.success());
        assertEquals(PROCESS_CANCELLED, cancelled.errorMessage());
        verify(lifecycle, after(1500).times(1)).releaseGpuForVectorPopulation(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));
    }

    @Test
    void attemptThatFailsToStartReleasesTheRowAndDeletesItsArgsFile() throws Exception {
        String taskId = "spawn-failure-" + UUID.randomUUID();
        acquirable(taskId, freeDevice);
        when(configService.getExecutablePathForType("vector-population"))
                .thenReturn(tmp.resolve("missing-executable").toString());

        CompletableFuture<VectorPopulationResult> result = launch(taskId, null);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        verify(lifecycle, timeout(10_000)).releaseGpuForVectorPopulation(taskId);
        verify(lifecycle, after(1000).times(1)).releaseGpuForVectorPopulation(any());
        assertFalse(lifecycle.hasJobGpuHold(taskId));
        List<String> left = argsFilesOf(taskId);
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }

    @Test
    void nativeCommandCarriesThePlacementBeforeTheDispatchToken() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        SubprocessPlacement placement = schedulerHeld(TASK, 1, 3L << 30);

        assertTrue(launch(TASK, placement).get(20, TimeUnit.SECONDS).success());

        Launch launch = launchesOf(TASK, 1).get(0);
        List<String> command = launch.command();
        int dispatch = command.indexOf(DISPATCH);
        assertEquals(command.size() - 2, dispatch, "the args file follows the dispatch token: " + command);
        List<String> flags = command.subList(1, dispatch);
        assertTrue(flags.contains("-Dnd4j.placement.defaultDevice=1"), "device pin: " + command);
        assertTrue(flags.contains("-D" + SubprocessBackendFlags.MAX_DEVICE_MEMORY_PROPERTY + "=" + (3L << 30)),
                "memory cap: " + command);
        assertEquals(Long.toString(3L << 30), launch.maxDeviceBytesEnv());
    }

    @Test
    void legacyLaunchRunsOnTheAppliedPlacement() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        SubprocessPlacement placement = schedulerHeld(TASK, 1, 3L << 30);
        launcher.applyPlacement(placement);

        assertTrue(launcher.launchVectorPopulation(TASK, tmp.resolve(TASK + "-kw").toString(),
                tmp.resolve(TASK + "-vec").toString(), Map.of()).get(20, TimeUnit.SECONDS).success());

        assertLaunchedOn(placement, launchesOf(TASK, 1).get(0));
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void concurrentLaunchesEachRunOnTheirOwnPlacement() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        SubprocessPlacement onA = schedulerHeld("vecpop-a", 0, 1L << 30);
        SubprocessPlacement onB = schedulerHeld("vecpop-b", 1, 3L << 30);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier together = new CyclicBarrier(2);
            // Each caller leaves the launcher's shared placement set to the other task's
            Future<CompletableFuture<VectorPopulationResult>> a = callers.submit(() -> {
                together.await();
                launcher.applyPlacement(onB);
                return launch("vecpop-a", onA);
            });
            Future<CompletableFuture<VectorPopulationResult>> b = callers.submit(() -> {
                together.await();
                launcher.applyPlacement(onA);
                return launch("vecpop-b", onB);
            });
            assertTrue(a.get(20, TimeUnit.SECONDS).get(20, TimeUnit.SECONDS).success());
            assertTrue(b.get(20, TimeUnit.SECONDS).get(20, TimeUnit.SECONDS).success());
        } finally {
            callers.shutdownNow();
        }

        assertLaunchedOn(onA, launchesOf("vecpop-a", 1).get(0));
        assertLaunchedOn(onB, launchesOf("vecpop-b", 1).get(0));
    }

    @Test
    void completionMessageBehindABacklogOfOutputIsNotLost() throws Exception {
        doAnswer(i -> {
            Thread.sleep(2);
            return null;
        }).when(vpTracker).sendLog(eq(TASK), eq("STDOUT"), anyString(), anyString());

        // A child the launcher did not wrap shares its protocol's pipe with its other output
        assertCompletionBehindABacklogIsNotLost(Child.UNWRAPPED);
    }

    @Test
    void completionMessageBehindABacklogOfStderrIsNotLost() throws Exception {
        doAnswer(i -> {
            Thread.sleep(2);
            return null;
        }).when(vpTracker).sendLog(eq(TASK), eq("STDERR"), anyString(), anyString());

        // A wrapped child that writes its protocol to fd 1 has it on stderr, behind its other output
        assertCompletionBehindABacklogIsNotLost(Child.LEGACY);
    }

    private void assertCompletionBehindABacklogIsNotLost(Child child) throws Exception {
        // ~50 KB of log lines ahead of the completion message: all of it fits in the pipe, so the child
        // has exited long before the slowed reader gets to the message
        installExecutable(child, "i=0; while [ $i -lt 250 ]; do printf '%s\\n' '" + "x".repeat(200) + "'; "
                + "i=$((i+1)); done; " + COMPLETES, COMPLETES);

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        verify(vpTracker, after(1000).times(1)).startTask(eq(TASK), any(), any());
        assertEquals(1, launches().size());
    }

    @Test
    void nativeOutputOfAChildOnTheChannelIsItsStderr() throws Exception {
        // libnd4j prints to fd 1 beneath the child's System.setOut redirect, and its text may lack a newline.
        // The launcher made fd 1 the stderr pipe; the protocol has a pipe of its own.
        installExecutable("printf '%s' '[native] no newline '; " + COMPLETES, "exit 3");

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        verify(vpTracker, timeout(5_000)).sendLog(TASK, "STDERR", "INFO", "[native] no newline ");
        verify(vpTracker, never()).sendLog(eq(TASK), eq("STDOUT"), anyString(), anyString());
        assertEquals(1, launches().size());
    }

    @Test
    void completionBehindNativeOutputOnTheSameLineIsTheTasksVerdict() throws Exception {
        // A child the launcher did not wrap: native text shares the protocol's pipe, and may lack a newline
        installExecutable(Child.UNWRAPPED, "printf '%s' '[native] no newline '; " + COMPLETES, "exit 3");

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        // The native text is still the child's output
        verify(vpTracker, timeout(5_000)).sendLog(TASK, "STDOUT", "INFO", "[native] no newline ");
        assertEquals(1, launches().size());
    }

    @Test
    void completionOnStderrBehindNativeOutputOnTheSameLineIsTheTasksVerdict() throws Exception {
        // A wrapped child that writes its protocol to fd 1, a build from before the channel, has it on stderr
        installExecutable(Child.LEGACY, "printf '%s' '[native] no newline '; " + COMPLETES, "exit 3");

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        verify(vpTracker, timeout(5_000)).sendLog(TASK, "STDERR", "INFO", "[native] no newline ");
        // The report is the task's verdict, not log text
        verify(vpTracker, never()).sendLog(eq(TASK), eq("STDERR"), anyString(), contains(SubprocessMessage.MESSAGE_PREFIX));
        assertEquals(1, launches().size());
    }

    @Test
    void stderrOfAChildOnTheChannelIsNeverItsVerdict() throws Exception {
        // Once the child has said its protocol goes to the channel, a protocol line on its stderr is text it
        // quoted, such as a log of a message
        String quoted = "quoted in a log";
        installExecutable("printf '%s\\n' '" + failed(quoted, "java.io.IOException") + "' >&2; sleep 0.3; " + COMPLETES,
                "exit 3");

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        verify(vpTracker, timeout(5_000)).sendLog(eq(TASK), eq("STDERR"), anyString(), contains(quoted));
        verify(vpTracker, never()).failTask(any(), any(), any());
        verify(ingestTracker, never()).failTask(any(), any(), any(), any());
        assertEquals(1, launches().size());
    }

    @Test
    void oomOnStderrDoesNotTurnALaterNonOomFailureIntoARecovery() throws Exception {
        Path go = tmp.resolve("go");
        String locked = "Index directory is locked";
        installExecutable("printf '%s\\n' 'java.lang.OutOfMemoryError: Java heap space' >&2; "
                + "while [ ! -f '" + go + "' ]; do sleep 0.05; done; "
                + "protocol '" + failed(locked, "java.io.IOException") + "'; exit 1", COMPLETES);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, SubprocessPlacement.cpu());
        // The OOM line has been read before the failure is written
        verify(vpTracker, timeout(10_000)).sendLog(eq(TASK), eq("STDERR"), anyString(), contains("OutOfMemoryError"));
        Files.createFile(go);

        VectorPopulationResult failure = result.get(20, TimeUnit.SECONDS);
        assertFalse(failure.success());
        assertEquals(locked, failure.errorMessage());
        verify(vpTracker, after(1000).times(1)).failTask(eq(TASK), any(), eq(locked));
        verify(ingestTracker, times(1)).failTask(eq(TASK), any(), any(), eq(locked));
        verify(vpTracker, never()).cancelTask(any(), any());
        verify(vpTracker, times(1)).startTask(eq(TASK), any(), any());
        assertEquals(1, launches().size());
        // The UI hears that the task failed, not that a recovery is coming
        assertTrue(broadcasts.stream().anyMatch(b -> "FAILED".equals(b.get("phase")) && locked.equals(b.get("message"))),
                "broadcasts: " + broadcasts);
        assertTrue(broadcasts.stream().noneMatch(b -> "RECOVERY_SCHEDULED".equals(b.get("phase"))),
                "broadcasts: " + broadcasts);
    }

    @Test
    void liveCancelIsReportedOnce() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, SubprocessPlacement.cpu());
        verify(vpTracker, timeout(10_000)).startTask(eq(TASK), any(), any());

        assertTrue(launcher.cancelVectorPopulation(TASK));

        VectorPopulationResult cancelled = result.get(20, TimeUnit.SECONDS);
        assertFalse(cancelled.success());
        assertEquals(PROCESS_CANCELLED, cancelled.errorMessage());
        // Reported by the killed attempt's exit, and not by the cancel as well
        verify(vpTracker, after(1000).times(1)).cancelTask(eq(TASK), anyString());
        verify(ingestTracker, times(1)).cancelTask(eq(TASK), any(), any(), anyString(), any());
        verify(vpTracker, never()).failTask(any(), any(), any());
        verify(ingestTracker, never()).failTask(any(), any(), any(), any());
    }

    @Test
    void unresponsiveAttemptGetsOneStuckVerdictBeforeItIsKilled() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);
        when(restartManager.shouldRestart(TASK, FailureReason.STALLED_NO_HEARTBEAT)).thenReturn(false);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, SubprocessPlacement.cpu());
        verify(vpTracker, timeout(10_000)).startTask(eq(TASK), any(), any());
        // It has recorded its launch, so the kill doesn't end it unrecorded
        launchesOf(TASK, 1);
        // It has sent no heartbeat since it started
        Thread.sleep(50);
        lifecycleManager.checkStaleProcesses();

        VectorPopulationResult stuck = result.get(20, TimeUnit.SECONDS);
        assertFalse(stuck.success());
        assertEquals(UNRESPONSIVE, stuck.errorMessage());
        // The kill that follows the verdict is not reported as a cancel
        verify(vpTracker, after(1000).times(1)).failTask(eq(TASK), any(), eq(UNRESPONSIVE));
        verify(ingestTracker, times(1)).failTask(eq(TASK), any(), any(), eq(UNRESPONSIVE));
        verify(vpTracker, never()).cancelTask(any(), any());
        verify(ingestTracker, never()).cancelTask(any(), any(), any(), any(), any());
        assertEquals(1, launches().size());
    }

    @Test
    void memoryKillThresholdFailureIsRestarted() throws Exception {
        // The child's memory watchdog reports its kill threshold, then exits 137
        installExecutable("protocol '" + failed("Memory kill threshold exceeded after embedding",
                "MemoryKillThreshold") + "'; exit 137", COMPLETES);

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(30, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        verify(vpTracker, timeout(10_000).times(2)).startTask(eq(TASK), any(), any());
        launchesOf(TASK, 2);
        verify(vpTracker, never()).failTask(any(), any(), any());
        verify(ingestTracker, never()).failTask(any(), any(), any(), any());
        assertTrue(broadcasts.stream().anyMatch(b -> "RECOVERY_SCHEDULED".equals(b.get("phase"))),
                "broadcasts: " + broadcasts);
        assertTrue(broadcasts.stream().noneMatch(b -> "FAILED".equals(b.get("phase"))), "broadcasts: " + broadcasts);
    }

    @Test
    void outOfMemoryNoticeOnFd1RestartsTheTask() throws Exception {
        // -XX:+ExitOnOutOfMemoryError prints its notice to fd 1, the stderr pipe once the child is wrapped,
        // then exits 3
        installExecutable("printf '%s\\n' 'Terminating due to java.lang.OutOfMemoryError: Java heap space'; exit 3",
                COMPLETES);

        VectorPopulationResult result = launch(TASK, SubprocessPlacement.cpu()).get(30, TimeUnit.SECONDS);

        assertTrue(result.success(), "result: " + result);
        verify(vpTracker, timeout(10_000).times(2)).startTask(eq(TASK), any(), any());
        launchesOf(TASK, 2);
        verify(vpTracker, never()).failTask(any(), any(), any());
        verify(ingestTracker, never()).failTask(any(), any(), any(), any());
        // Restarted as out of memory, not as an unexplained exit
        assertTrue(broadcasts.stream().anyMatch(b -> "RESTARTING".equals(b.get("phase"))
                        && b.get("stats") instanceof Map<?, ?> stats
                        && "OUT_OF_MEMORY".equals(String.valueOf(stats.get("failureReason")))),
                "broadcasts: " + broadcasts);
        assertTrue(broadcasts.stream().noneMatch(b -> "FAILED".equals(b.get("phase"))), "broadcasts: " + broadcasts);
    }

    @Test
    void resultWaitsForTheChildToExitAfterItsCompletionMessage() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(completesThenExitsAfter(go), COMPLETES);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, SubprocessPlacement.cpu());
        verify(vpTracker, timeout(10_000)).completeTask(eq(TASK), any());
        // After COMPLETED the child still flushes its index on its device, and a caller frees the task's
        // GPU row once it has the result
        assertThrows(TimeoutException.class, () -> result.get(1, TimeUnit.SECONDS));

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
    }

    @Test
    void launchAfterShutdownNeverStarts() throws Exception {
        String taskId = "after-shutdown-" + UUID.randomUUID();
        acquirable(taskId, freeDevice);
        installExecutable(COMPLETES, COMPLETES);
        launcher.shutdownAll();

        VectorPopulationResult result = launch(taskId, null).get(20, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertEquals("Cancelled before start", result.errorMessage());
        assertTrue(launches().isEmpty());
        verify(vpTracker, never()).startTask(any(), any(), any());
        verify(lifecycle, timeout(10_000)).releaseGpuForVectorPopulation(taskId);
        verify(lifecycle, after(1000).times(1)).releaseGpuForVectorPopulation(any());
        List<String> left = argsFilesOf(taskId);
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }

    @Test
    void shutdownWhileARestartIsBeingScheduledEndsTheTaskAndDeletesItsArgsFile() throws Exception {
        String taskId = "shutdown-restart-" + UUID.randomUUID();
        installExecutable(OOM_KILLED, COMPLETES);
        acquirable(taskId, freeDevice);
        // The application shuts down as the restart is scheduled, so the stopped scheduler rejects it
        doAnswer(i -> {
            launcher.shutdownAll();
            return null;
        }).when(ingestTracker).notifyRestartScheduled(eq(taskId), any(), any(), anyInt(), anyInt(), anyLong(), any(),
                anyBoolean(), any(), any(), any());

        VectorPopulationResult stopped = launch(taskId, null).get(20, TimeUnit.SECONDS);

        assertFalse(stopped.success());
        assertEquals(STOPPED_FOR_SHUTDOWN, stopped.errorMessage());
        verify(vpTracker, after(2500).times(1)).startTask(eq(taskId), any(), any());
        launchesOf(taskId, 1);
        verify(vpTracker, times(1)).cancelTask(taskId, STOPPED_FOR_SHUTDOWN);
        verify(vpTracker, never()).failTask(any(), any(), any());
        verify(ingestTracker, never()).failTask(any(), any(), any(), any());
        verify(lifecycle, timeout(10_000)).releaseGpuForVectorPopulation(taskId);
        verify(lifecycle, after(1000).times(1)).releaseGpuForVectorPopulation(any());
        awaitNoArgsFiles(taskId);
    }

    @Test
    void cancelAfterTheCompletionMessageLeavesTheFlushingChildAlone() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(completesThenExitsAfter(go), COMPLETES);

        CompletableFuture<VectorPopulationResult> result = launch(TASK, SubprocessPlacement.cpu());
        verify(vpTracker, timeout(10_000)).completeTask(eq(TASK), any());

        // The task has completed: there is nothing to cancel, and its child goes on flushing its index
        assertFalse(launcher.cancelVectorPopulation(TASK));
        assertThrows(TimeoutException.class, () -> result.get(1, TimeUnit.SECONDS));

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(vpTracker, never()).cancelTask(any(), any());
        verify(ingestTracker, never()).cancelTask(any(), any(), any(), any(), any());
    }

    @Test
    void cancelRacingTheCompletionMessageKeepsTheChildsOutcome() throws Exception {
        // A child the launcher did not wrap: its COMPLETED report waits in the pipe behind a line of its other
        // output that the reader is still forwarding
        Path go = tmp.resolve("go");
        Path reported = tmp.resolve("reported");
        installExecutable(Child.UNWRAPPED, "printf 'hold\\n'; while [ ! -f '" + go + "' ]; do sleep 0.05; done; "
                + "protocol '" + COMPLETED_MESSAGE + "'; : > '" + reported + "'; exec sleep 60", COMPLETES);
        CountDownLatch readerHeld = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        doAnswer(i -> {
            readerHeld.countDown();
            releaseReader.await(20, TimeUnit.SECONDS);
            return null;
        }).when(vpTracker).sendLog(eq(TASK), eq("STDOUT"), anyString(), eq("hold"));

        CompletableFuture<VectorPopulationResult> result = launch(TASK, SubprocessPlacement.cpu());
        assertTrue(readerHeld.await(10, TimeUnit.SECONDS));
        try {
            // Written while the reader is held, so the report is still in the pipe, not in the reader's buffer
            Files.createFile(go);
            // The report is written before the cancel, which could otherwise stop the child first
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(reported) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(reported), "the child wrote its report");
            // Stops the child, which had already reported completing
            assertTrue(launcher.cancelVectorPopulation(TASK));
            // A watcher that judged the exit without draining the reader would have judged by now
            Thread.sleep(300);
        } finally {
            releaseReader.countDown();
        }

        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(vpTracker, timeout(5_000)).completeTask(eq(TASK), any());
        verify(vpTracker, after(1000).never()).cancelTask(any(), any());
        verify(ingestTracker, never()).cancelTask(any(), any(), any(), any(), any());
    }

    @Test
    void shutdownDeletesTheArgsFileOfAnAttemptItStopsBeforeItsWatcherRuns() throws Exception {
        String taskId = "shutdown-args-" + UUID.randomUUID();
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);
        // The stopped attempt's watcher is held at its verdict, as if the JVM exited right after shutdown
        CountDownLatch watcherHeld = new CountDownLatch(1);
        CountDownLatch releaseWatcher = new CountDownLatch(1);
        doAnswer(i -> {
            watcherHeld.countDown();
            releaseWatcher.await(20, TimeUnit.SECONDS);
            return null;
        }).when(vpTracker).cancelTask(taskId, PROCESS_CANCELLED);

        launch(taskId, SubprocessPlacement.cpu());
        verify(vpTracker, timeout(10_000)).startTask(eq(taskId), any(), any());
        try {
            launcher.shutdownAll();

            assertTrue(watcherHeld.await(10, TimeUnit.SECONDS), "the stopped attempt's watcher reached its verdict");
            List<String> left = argsFilesOf(taskId);
            assertTrue(left.isEmpty(), "args files left behind: " + left);
        } finally {
            releaseWatcher.countDown();
        }
    }

    /** One recorded child launch: which run of the executable it was, its {@code SD_MAX_DEVICE_BYTES} and its command line. */
    private record Launch(String attempt, String maxDeviceBytesEnv, List<String> command) {
        String argsFileName() {
            return Path.of(command.get(command.size() - 1)).getFileName().toString();
        }

        List<String> commandBeforeArgsFile() {
            return command.subList(0, command.size() - 1);
        }
    }

    /** Where a fake child writes its protocol lines. */
    private enum Child {
        /**
         * A current child: when its launcher set up the protocol channel, it says so on stderr and writes
         * its protocol there; otherwise to fd 1.
         */
        CHANNEL("fd=${" + SubprocessProtocolChannel.ENV_PROTOCOL_FD + ":-1}; [ \"$fd\" = 1 ] || printf '%s\\n' '"
                + SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE + "'\"$fd\" >&2"),
        /** A build from before the channel: its protocol goes to fd 1, the stderr pipe once it is wrapped. */
        LEGACY("fd=1"),
        /**
         * A child its launcher did not wrap, whose fd 1 is the stdout pipe. The test can't keep the
         * launcher from wrapping it, so it undoes the wrapper's layout.
         */
        UNWRAPPED("fd=${" + SubprocessProtocolChannel.ENV_PROTOCOL_FD + ":-}; if [ -n \"$fd\" ]; then unset "
                + SubprocessProtocolChannel.ENV_PROTOCOL_FD + "; eval \"exec 1>&$fd $fd>&-\"; fi; fd=1");

        /** Shell that sets {@code fd} to the fd the child writes its protocol to. */
        final String setup;

        Child(String setup) {
            this.setup = setup;
        }
    }

    /** {@link #installExecutable(Child, String, String)} for a child that writes its protocol to the channel. */
    private void installExecutable(String firstAttempt, String restartAttempt) throws IOException {
        installExecutable(Child.CHANNEL, firstAttempt, restartAttempt);
    }

    /**
     * Installs the fake native vector population executable. Every attempt records its launch, then runs
     * {@code firstAttempt} — or {@code restartAttempt} on any later run. Restarts reuse the taskId, so the
     * first run marks itself with a directory. In both, {@code protocol} prints a protocol line where
     * {@code child} writes them.
     */
    private void installExecutable(Child child, String firstAttempt, String restartAttempt) throws IOException {
        Files.writeString(executable, String.join("\n",
                "#!/bin/sh",
                "if mkdir '" + records + "/first' 2>/dev/null; then attempt=first; else attempt=restart; fi",
                "rec=$(mktemp '" + records + "/attempt-XXXXXX')",
                "{ printf '%s\\n' \"$attempt\" \"$" + SubprocessBackendFlags.MAX_DEVICE_BYTES_ENV + "\" \"$0\"; "
                        + "for a in \"$@\"; do printf '%s\\n' \"$a\"; done; } > \"$rec\"",
                "mv \"$rec\" \"$rec" + LAUNCH_RECORD + "\"",
                child.setup,
                "protocol() { printf '%s\\n' \"$1\" >&\"$fd\"; }",
                "case \"$attempt\" in",
                "  first) " + firstAttempt + " ;;",
                "  *) " + restartAttempt + " ;;",
                "esac",
                ""));
        assertTrue(executable.toFile().setExecutable(true));
        when(configService.getExecutablePathForType("vector-population")).thenReturn(executable.toString());
    }

    /** An attempt that completes once {@code signal} exists. */
    private static String completesAfter(Path signal) {
        return "while [ ! -f '" + signal + "' ]; do sleep 0.05; done; " + COMPLETES;
    }

    /** An attempt that is OOM-killed once {@code signal} exists. */
    private static String oomKilledAfter(Path signal) {
        return "while [ ! -f '" + signal + "' ]; do sleep 0.05; done; " + OOM_KILLED;
    }

    /** An attempt that writes its completion message, then runs on until {@code signal} exists. */
    private static String completesThenExitsAfter(Path signal) {
        return "protocol '" + COMPLETED_MESSAGE + "'; while [ ! -f '" + signal + "' ]; do sleep 0.05; done; exit 0";
    }

    /** A FAILED message as the child's protocol prints it. */
    private static String failed(String errorMessage, String errorType) {
        return SubprocessMessage.MESSAGE_PREFIX + "{\"type\":\"FAILED\",\"taskId\":\"t\",\"phase\":\"EMBEDDING\","
                + "\"errorMessage\":\"" + errorMessage + "\",\"errorType\":\"" + errorType + "\",\"stackTrace\":null}";
    }

    /** A GPU placement for a task whose row the scheduler holds, as the scheduler passes it. */
    private SubprocessPlacement schedulerHeld(String taskId, int cudaDevice, long capBytes) {
        GpuDevice device = GpuDevice.local(cudaDevice, cudaDevice, "gpu-" + cudaDevice, 8L << 30);
        holds.put(taskId, new JobGpuHold(taskId, JobResourceProfiles.VECTOR_POPULATION.serviceType(), device,
                Instant.now(), "scheduler"));
        return SubprocessPlacement.gpu(device.cudaRuntimeIndex(), ModelLifecycleManager.clampToDevice(capBytes, device));
    }

    /** Lets the launcher acquire the task's own row on {@code device}. */
    private void acquirable(String taskId, GpuDevice device) {
        when(lifecycle.acquireGpuForJob(eq(taskId), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenAnswer(i -> {
                    holds.put(taskId, new JobGpuHold(taskId, JobResourceProfiles.VECTOR_POPULATION.serviceType(),
                            device, Instant.now(), "launcher"));
                    return device;
                });
    }

    /** The placement of a task on the row the launcher acquires for it on {@link #freeDevice}. */
    private SubprocessPlacement onFreeDevice() {
        return SubprocessPlacement.gpu(freeDevice.cudaRuntimeIndex(), ModelLifecycleManager.clampToDevice(
                JobResourceProfiles.VECTOR_POPULATION.peakGpuMemoryBytes(), freeDevice));
    }

    private CompletableFuture<VectorPopulationResult> launch(String taskId, SubprocessPlacement placement) {
        return launcher.launchVectorPopulation(taskId, tmp.resolve(taskId + "-kw").toString(),
                tmp.resolve(taskId + "-vec").toString(), Map.of(), placement);
    }

    /** The command of a child on {@code placement}, up to its args file. */
    private List<String> nativeCommandOn(SubprocessPlacement placement) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(SubprocessBackendFlags.jvmFlags(placement, BackendPreference.INHERIT));
        command.add(DISPATCH);
        return command;
    }

    private void assertLaunchedOn(SubprocessPlacement placement, Launch launch) {
        assertEquals(nativeCommandOn(placement), launch.commandBeforeArgsFile());
        assertEquals(Long.toString(placement.maxDeviceMemoryBytes()), launch.maxDeviceBytesEnv());
    }

    private List<Launch> launches() throws IOException {
        List<Launch> launches = new ArrayList<>();
        try (Stream<Path> files = Files.list(records)) {
            for (Path file : files.filter(f -> f.toString().endsWith(LAUNCH_RECORD)).toList()) {
                List<String> lines = Files.readAllLines(file);
                launches.add(new Launch(lines.get(0), lines.get(1), lines.subList(2, lines.size())));
            }
        }
        return launches;
    }

    /**
     * The launches of a task's attempts — each attempt's args file is named after the taskId. An attempt
     * records its launch as it starts, so this waits for {@code expected} records.
     */
    private List<Launch> launchesOf(String taskId, int expected) throws Exception {
        Pattern argsFile = Pattern.compile("vector-pop-args-" + Pattern.quote(taskId) + "[0-9]+\\.json");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<Launch> matching;
        while ((matching = launches().stream()
                .filter(launch -> argsFile.matcher(launch.argsFileName()).matches())
                .toList()).size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, matching.size(), "launches of " + taskId);
        return matching;
    }

    /** The names of a task's args files left in the temp dir. Only names: the files carry credentials. */
    private static List<String> argsFilesOf(String taskId) throws IOException {
        Pattern argsFile = Pattern.compile("vector-pop-args-" + Pattern.quote(taskId) + "[0-9]+\\.json");
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> argsFile.matcher(name).matches())
                    .toList();
        }
    }

    /** Waits for a task's args files to be deleted, which happens once its last attempt has exited. */
    private static void awaitNoArgsFiles(String taskId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> left;
        while (!(left = argsFilesOf(taskId)).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }
}
