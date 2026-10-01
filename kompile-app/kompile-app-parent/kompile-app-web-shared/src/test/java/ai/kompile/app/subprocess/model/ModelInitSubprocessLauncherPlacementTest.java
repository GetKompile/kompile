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

package ai.kompile.app.subprocess.model;

import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.ModelLifecycleManager.GpuShortfallException;
import ai.kompile.app.services.ModelLifecycleManager.HoldLifetime;
import ai.kompile.app.services.ModelLifecycleManager.JobGpuHold;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher.ModelInitResult;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher.ModelInitStatus;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A model init runs on a GPU only with a row in the GPU ledger behind it. When the scheduler has not
 * reserved one, the launcher acquires the task's row itself, pins the child to that row's CUDA device with
 * the profile's memory cap, and releases the row exactly once, after the child is gone — however the init
 * ends. A GPU with no room right now fails the init as retriable instead of running it on CPU; with no GPU
 * to acquire it runs on CPU. A row the scheduler holds is the scheduler's to release. An init's outcome is
 * reported once: the child's own COMPLETED or FAILED when it sends one, else the launcher's verdict from the
 * timeout or the exit code. What the child wrote before it ended is read before the launcher judges it, and a
 * cancel or kill leaves it to be read. A child on the protocol channel sends its messages on a pipe of their own,
 * which native output can't reach; one from before the channel, or not on it, is read wherever its messages
 * arrive, behind native output on the same line included. No child starts after shutdown, and one starting as
 * the launcher shuts down is killed. A fake native executable records each child's pid, memory-cap env and
 * command line.
 */
@Timeout(60)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake native executable is a POSIX shell script")
class ModelInitSubprocessLauncherPlacementTest {

    private static final String TASK = "model-init-task";
    private static final String MODEL = "model-id";
    private static final String LAUNCH_RECORD = ".launch";
    private static final String DISPATCH = "--subprocess=model-init";
    private static final Pattern ARGS_FILE = Pattern.compile("model-init-args-[0-9]+\\.json");
    private static final String RUNS_UNTIL_KILLED = "exec sleep 60";

    @TempDir
    Path tmp;

    private final SubprocessConfigService configService = mock(SubprocessConfigService.class);
    private final ModelLifecycleManager lifecycle = mock(ModelLifecycleManager.class);
    private final Map<String, JobGpuHold> holds = new ConcurrentHashMap<>();
    /** The pids of the recorded children still running when each task's row was released */
    private final Map<String, Set<Long>> aliveAtRelease = new ConcurrentHashMap<>();
    private final List<ModelInitMessage.Failed> failures = new CopyOnWriteArrayList<>();
    /** Numbered as on a box where nvidia-smi lists this GPU first and CUDA numbers it 1 */
    private final GpuDevice freeDevice = GpuDevice.local(0, 1, "fake-gpu", 1L << 30);
    /** CUDA device 0, which nvidia-smi lists second */
    private final GpuDevice cudaZero = GpuDevice.local(1, 0, "fake-gpu-0", 24L << 30);

    private Path executable;
    private Path records;
    private String userHome;
    private String userDir;
    private ModelInitSubprocessLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        // Subprocess logs go under user.dir — keep it and user.home in the temp dir
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        System.setProperty("user.dir", Files.createDirectories(tmp.resolve("work/.kompile")).getParent().toString());
        records = Files.createDirectories(tmp.resolve("records"));
        executable = tmp.resolve("kompile-model-init");

        when(configService.shouldUseNativeExecutableMode()).thenReturn(true);
        when(configService.useUnifiedExecutable("model-init")).thenReturn(true);
        when(configService.getSubprocessTypeFlag()).thenReturn("--subprocess=");

        when(lifecycle.getActiveJobHolds()).thenAnswer(i -> Map.copyOf(holds));
        when(lifecycle.hasJobGpuHold(anyString())).thenAnswer(i -> holds.containsKey(i.<String>getArgument(0)));
        doAnswer(i -> {
            releaseRow(i.getArgument(0));
            return null;
        }).when(lifecycle).releaseGpuForModelInit(anyString());
        doAnswer(i -> {
            releaseRow(i.getArgument(0));
            return null;
        }).when(lifecycle).releaseGpuForJob(anyString());

        launcher = new ModelInitSubprocessLauncher();
        launcher.modelLifecycleManager = lifecycle;
        launcher.subprocessConfigService = configService;
        launcher.timeoutMinutes = 5;
        launcher.heapSize = "1g";
        launcher.javaPath = "java";
    }

    @AfterEach
    void tearDown() {
        launcher.cleanup();
        System.setProperty("user.home", userHome);
        System.setProperty("user.dir", userDir);
    }

    @Test
    void launcherAcquiresTheTasksRowPinsTheChildToItAndReleasesItOnceAfterExit() throws Exception {
        installExecutable(completes());
        acquirable(TASK, freeDevice);

        ModelInitResult result = launch(TASK).get(20, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertNotNull(result.completedMessage(), "the child's completion message was read");
        verify(lifecycle).acquireGpuForJob(eq(TASK), eq(JobResourceProfiles.MODEL_INIT.serviceType()), anyString(),
                eq(HoldLifetime.BOUNDED), eq(JobResourceProfiles.MODEL_INIT.peakGpuMemoryBytes()), isNull());
        // Pinned by the device's CUDA ordinal, not its nvidia-smi index, with the profile's cap clamped to it
        assertTrue(JobResourceProfiles.MODEL_INIT.peakGpuMemoryBytes() > freeDevice.totalMemoryBytes());
        assertEquals(SubprocessPlacement.gpu(1, 1L << 30), onFreeDevice());
        Launch launch = awaitLaunches(1).get(0);
        assertLaunchedOn(onFreeDevice(), launch);
        List<String> command = launch.command();
        int dispatch = command.indexOf(DISPATCH);
        assertEquals(command.size() - 2, dispatch, "the args file follows the dispatch token: " + command);
        List<String> flags = command.subList(1, dispatch);
        assertTrue(flags.contains("-Dnd4j.placement.defaultDevice=1"), "device pin: " + command);
        assertTrue(flags.contains("-D" + SubprocessBackendFlags.MAX_DEVICE_MEMORY_PROPERTY + "=" + (1L << 30)),
                "memory cap: " + command);
        assertTrue(ARGS_FILE.matcher(launch.argsFile().getFileName().toString()).matches(), "args file: " + command);

        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertReleasedAfterExit(TASK, launch);
        assertFalse(lifecycle.hasJobGpuHold(TASK));
        // The args file carries credentials: only its name is looked at
        assertFalse(Files.exists(launch.argsFile()));
    }

    @Test
    void gpuWithNoRoomFailsTheInitAsRetriableWithoutStartingAChild() throws Exception {
        installExecutable(completes());
        notAcquirable(TASK, new GpuShortfallException("no room on any device"));
        Set<String> argsFilesBefore = argsFileNames();

        CompletableFuture<ModelInitResult> result = launch(TASK);

        ExecutionException failed = assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        assertInstanceOf(GpuShortfallException.class, failed.getCause().getCause());
        // Not run on CPU instead: its optimization cache would be fingerprinted for the wrong backend
        assertEquals(List.of(), launches());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).retriable());
        ModelInitStatus status = launcher.getCurrentStatus();
        assertEquals(ModelInitStatus.Status.FAILED, status.status());
        assertTrue(status.errorRetriable());
        verify(lifecycle, after(1000).never()).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertEquals(Set.of(), argsFilesLeftSince(argsFilesBefore));
    }

    @Test
    void initWithNoGpuToAcquireRunsOnCpu() throws Exception {
        installExecutable(completes());
        notAcquirable(TASK, new IllegalStateException("ModelLifecycleManager is not running"));

        assertTrue(launch(TASK).get(20, TimeUnit.SECONDS).success());

        Launch launch = awaitLaunches(1).get(0);
        assertEquals(nativeCommandOn(SubprocessPlacement.cpu()), launch.commandBeforeArgsFile());
        assertTrue(launch.command().stream().noneMatch(arg -> arg.startsWith("-Dnd4j.placement.defaultDevice=")));
        verify(lifecycle, after(1000).never()).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
    }

    @Test
    void scheduledInitRunsOnItsPlacementAndLeavesTheSchedulersRow() throws Exception {
        installExecutable(completes());
        acquirable(TASK, freeDevice);
        SubprocessPlacement placement = schedulerHeld(TASK, cudaZero, 3L << 30);
        launcher.applyPlacement(placement);

        assertTrue(launch(TASK).get(20, TimeUnit.SECONDS).success());

        assertLaunchedOn(placement, awaitLaunches(1).get(0));
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
        verify(lifecycle, after(1000).never()).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertTrue(lifecycle.hasJobGpuHold(TASK));
    }

    @Test
    void heldRowWithoutAPlacementPinsTheChildToTheRowsDevice() throws Exception {
        installExecutable(completes());
        acquirable(TASK, freeDevice);
        schedulerHeld(TASK, cudaZero, 3L << 30);

        assertTrue(launch(TASK).get(20, TimeUnit.SECONDS).success());

        assertLaunchedOn(SubprocessPlacement.gpu(cudaZero.cudaRuntimeIndex(), ModelLifecycleManager.clampToDevice(
                JobResourceProfiles.MODEL_INIT.peakGpuMemoryBytes(), cudaZero)), awaitLaunches(1).get(0));
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
        verify(lifecycle, after(1000).never()).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertTrue(lifecycle.hasJobGpuHold(TASK));
    }

    @Test
    void cpuPlacementRunsOnCpuWithoutAcquiringARow() throws Exception {
        installExecutable(completes());
        acquirable(TASK, freeDevice);
        launcher.applyPlacement(SubprocessPlacement.cpu());

        assertTrue(launch(TASK).get(20, TimeUnit.SECONDS).success());

        assertEquals(nativeCommandOn(SubprocessPlacement.cpu()), awaitLaunches(1).get(0).commandBeforeArgsFile());
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
        verify(lifecycle, after(1000).never()).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
    }

    @Test
    void childThatFailsToStartReleasesTheRowAndDeletesItsArgsFile() throws Exception {
        acquirable(TASK, freeDevice);
        when(configService.getExecutablePathForType("model-init"))
                .thenReturn(tmp.resolve("missing-executable").toString());
        Set<String> argsFilesBefore = argsFileNames();

        CompletableFuture<ModelInitResult> result = launch(TASK);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));
        assertEquals(Set.of(), argsFilesLeftSince(argsFilesBefore));
    }

    @Test
    void cancelledInitReleasesItsRowOnceTheChildIsGone() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED);
        acquirable(TASK, freeDevice);

        CompletableFuture<ModelInitResult> result = launch(TASK);
        Launch launch = awaitLaunches(1).get(0);
        awaitTracked(TASK);

        assertTrue(launcher.cancelInitialization(TASK));

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        // Killed by the cancel, which exits it 137 like the OOM killer — but a cancelled init is not retried
        assertEquals(1, failures.size(), "failures: " + failures);
        assertFalse(failures.get(0).retriable());
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertReleasedAfterExit(TASK, launch);
        assertFalse(lifecycle.hasJobGpuHold(TASK));
        assertFalse(Files.exists(launch.argsFile()));
        assertFalse(launcher.cancelInitialization(TASK), "the ended init is no longer tracked");
    }

    @Test
    void timedOutInitIsKilledAndLeavesNoRowHandleOrArgsFile() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED);
        acquirable(TASK, freeDevice);
        // A zero timeout expires as soon as the child is running
        launcher.timeoutMinutes = 0;
        Set<String> argsFilesBefore = argsFileNames();

        CompletableFuture<ModelInitResult> result = launch(TASK);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        // Reported once, as retriable: the init may finish when retried
        assertEquals(1, failures.size(), "failures: " + failures);
        assertTrue(failures.get(0).retriable());
        ModelInitStatus status = launcher.getCurrentStatus();
        assertEquals(ModelInitStatus.Status.FAILED, status.status());
        assertTrue(status.errorRetriable());
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));
        assertFalse(launcher.cancelInitialization(TASK), "the timed-out init is no longer tracked");
        assertEquals(Set.of(), argsFilesLeftSince(argsFilesBefore));
    }

    @Test
    void failureTheChildReportsIsTheInitsVerdictReportedOnce() throws Exception {
        // The child's memory guard aborts the init as retriable, then the child exits 1
        ModelInitMessage.Failed aborted = ModelInitMessage.failed(TASK, MODEL, ModelInitMessage.Phase.CREATING_ENCODER,
                "Memory threshold exceeded after encoder creation - aborting to prevent OOM", "MemoryThreshold",
                null, true);
        installExecutable(reports(aborted) + "; exit 1");
        acquirable(TASK, freeDevice);

        CompletableFuture<ModelInitResult> result = launch(TASK);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        // Not reported again from the exit code, whose verdict would not be retriable
        assertEquals(List.of(aborted), failures);
        ModelInitStatus status = launcher.getCurrentStatus();
        assertEquals(ModelInitStatus.Status.FAILED, status.status());
        assertEquals(ModelInitMessage.Phase.CREATING_ENCODER, status.phase());
        assertEquals(aborted.errorMessage(), status.errorMessage());
        assertTrue(status.errorRetriable());
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        assertReleasedAfterExit(TASK, awaitLaunches(1).get(0));
    }

    @Test
    void childKilledWithoutReportingFailsOnceAsRetriable() throws Exception {
        // Ended as the OOM killer ends it: no FAILED message, exit 137
        installExecutable("kill -9 $$");
        acquirable(TASK, freeDevice);

        CompletableFuture<ModelInitResult> result = launch(TASK);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        assertEquals(1, failures.size(), "failures: " + failures);
        assertTrue(failures.get(0).retriable(), "a killed init may succeed on retry");
        ModelInitStatus status = launcher.getCurrentStatus();
        assertEquals(ModelInitStatus.Status.FAILED, status.status());
        assertEquals(failures.get(0).errorMessage(), status.errorMessage());
        assertTrue(status.errorRetriable());
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        assertReleasedAfterExit(TASK, awaitLaunches(1).get(0));
    }

    @Test
    void childThatReportedItsModelReadySucceedsWhateverItsExitCode() throws Exception {
        // It crashes tearing down after the model is ready
        installExecutable(reports(completed()) + "; exit 1");
        acquirable(TASK, freeDevice);

        ModelInitResult result = launch(TASK).get(20, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertEquals(completed(), result.completedMessage());
        assertEquals(List.of(), failures);
        assertEquals(ModelInitStatus.Status.COMPLETED, launcher.getCurrentStatus().status());
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
    }

    @Test
    void completionBehindNativeOutputOnTheSameLineIsTheInitsVerdict() throws Exception {
        // A child the launcher did not wrap: libnd4j prints to fd 1 beneath the child's System.setOut redirect,
        // the protocol's pipe, and its text may lack a newline
        installExecutable(Child.UNWRAPPED, "printf '%s' '[native] no newline '; " + reports(completed()) + "; exit 3");
        launcher.applyPlacement(SubprocessPlacement.cpu());

        ModelInitResult result = launch(TASK).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), () -> "result: " + result);
        assertEquals(completed(), result.completedMessage());
        assertEquals(List.of(), failures);
        assertEquals(ModelInitStatus.Status.COMPLETED, launcher.getCurrentStatus().status());
    }

    @Test
    void nativeOutputOfAChildOnTheProtocolChannelGoesToItsStderr() throws Exception {
        // What libnd4j prints to fd 1 reaches the stderr pipe, never the line of a message
        installExecutable("printf '%s' '[native] no newline '; " + completes());
        launcher.applyPlacement(SubprocessPlacement.cpu());

        ModelInitResult result = launch(TASK).get(20, TimeUnit.SECONDS);

        assertEquals(completed(), result.completedMessage(), () -> "result: " + result);
        assertEquals(List.of(protocolLine(completed())), runLog(TASK, AgentLogRecord.Stream.STDOUT));
        List<String> stderr = runLog(TASK, AgentLogRecord.Stream.STDERR);
        assertTrue(stderr.contains("[native] no newline "), "stderr: " + stderr);
    }

    @Test
    void completionOnStderrBehindNativeOutputOnTheSameLineIsTheInitsVerdict() throws Exception {
        // A build from before the channel writes its messages to fd 1 — the stderr pipe once it is wrapped, where
        // native output without a newline can precede one on the same line
        installExecutable(Child.LEGACY, "printf '%s' '[native] no newline '; " + reports(completed()) + "; exit 3");
        launcher.applyPlacement(SubprocessPlacement.cpu());

        ModelInitResult result = launch(TASK).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), () -> "result: " + result);
        assertEquals(completed(), result.completedMessage());
        assertEquals(List.of(), failures);
    }

    @Test
    void messageOnTheStderrOfAChildOnTheProtocolChannelIsNotItsVerdict() throws Exception {
        // Once the child said its channel is open, stderr is only output: here a log line quoting a failure
        ModelInitMessage.Failed quoted = ModelInitMessage.failed(TASK, MODEL, ModelInitMessage.Phase.CREATING_ENCODER,
                "quoted in a log", "IOException", null, false);
        // The pause lets the stderr reader get to the quote before the completion arrives on the other pipe
        installExecutable("printf '%s\\n' '" + protocolLine(quoted) + "' >&2; sleep 0.3; "
                + reports(completed()) + "; exit 3");
        launcher.applyPlacement(SubprocessPlacement.cpu());

        ModelInitResult result = launch(TASK).get(20, TimeUnit.SECONDS);

        assertTrue(result.success(), () -> "result: " + result);
        assertEquals(completed(), result.completedMessage());
        assertEquals(List.of(), failures);
    }

    @Test
    void messageLeftOnStderrWhenTheChildExitsIsReadBeforeTheInitIsJudged() throws Exception {
        // A build from before the channel: its completion waits on the stderr pipe behind a progress update the
        // listener is still handling when the child exits 1
        installExecutable(Child.LEGACY, reports(progress()) + "; " + reports(completed()) + "; exit 1");
        launcher.applyPlacement(SubprocessPlacement.cpu());
        CountDownLatch readerHeld = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);

        CompletableFuture<ModelInitResult> result = launch(TASK, holdsReader(readerHeld, releaseReader));
        try {
            assertTrue(readerHeld.await(15, TimeUnit.SECONDS), "the progress update reached the listener");
            awaitExit(awaitLaunches(1).get(0));
            // A launcher that judged the exit without draining stderr would have judged by now
            Thread.sleep(300);
        } finally {
            releaseReader.countDown();
        }

        ModelInitResult ended = result.get(20, TimeUnit.SECONDS);
        assertTrue(ended.success(), () -> "result: " + ended);
        assertEquals(completed(), ended.completedMessage());
        assertEquals(List.of(), failures);
    }

    @Test
    void cancelRacingTheCompletionMessageKeepsTheChildsOutcome() throws Exception {
        // The child reports its model ready while the reader is still passing on its progress, and is cancelled
        // before the reader gets to the report
        Path go = tmp.resolve("go");
        Path reported = tmp.resolve("reported");
        installExecutable(reports(progress()) + "; while [ ! -f '" + go + "' ]; do sleep 0.05; done; "
                + reports(completed()) + "; : > '" + reported + "'; " + RUNS_UNTIL_KILLED);
        launcher.applyPlacement(SubprocessPlacement.cpu());
        CountDownLatch readerHeld = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);

        CompletableFuture<ModelInitResult> result = launch(TASK, holdsReader(readerHeld, releaseReader));
        try {
            assertTrue(readerHeld.await(15, TimeUnit.SECONDS), "the progress update reached the listener");
            Launch launch = awaitLaunches(1).get(0);
            awaitTracked(TASK);
            // Written while the reader is held, so the report is still in the pipe, not in the reader's buffer
            Files.createFile(go);
            // The report is written before the cancel, which could otherwise stop the child first
            awaitFile(reported);
            // Kills the child, which had already reported its model ready
            assertTrue(launcher.cancelInitialization(TASK));
            awaitExit(launch);
            // A launcher that judged the exit without draining the reader would have judged by now
            Thread.sleep(300);
        } finally {
            releaseReader.countDown();
        }

        ModelInitResult ended = result.get(20, TimeUnit.SECONDS);
        assertTrue(ended.success(), () -> "result: " + ended);
        assertEquals(completed(), ended.completedMessage());
        assertEquals(List.of(), failures);
    }

    @Test
    void cleanupReleasesOnlyTheLaunchersRowOnceItsChildIsGone() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED);
        acquirable("own-row", freeDevice);
        SubprocessPlacement scheduled = schedulerHeld("scheduled", cudaZero, 3L << 30);
        // Only "scheduled" has a row behind this placement; "own-row" acquires its own
        launcher.applyPlacement(scheduled);

        CompletableFuture<ModelInitResult> scheduledInit = launch("scheduled");
        CompletableFuture<ModelInitResult> ownInit = launch("own-row");
        List<Launch> launches = awaitLaunches(2);
        awaitTracked("scheduled");
        awaitTracked("own-row");

        launcher.cleanup();

        assertThrows(ExecutionException.class, () -> scheduledInit.get(20, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> ownInit.get(20, TimeUnit.SECONDS));
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit("own-row");
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertTrue(lifecycle.hasJobGpuHold("scheduled"), "the scheduler's row is the scheduler's to release");
        assertLaunchedOn(scheduled, launchOn(launches, scheduled));
        Launch own = launchOn(launches, onFreeDevice());
        assertLaunchedOn(onFreeDevice(), own);
        assertReleasedAfterExit("own-row", own);
        for (Launch launch : launches) {
            assertFalse(Files.exists(launch.argsFile()));
        }
    }

    @Test
    void shutdownWhileAnInitStartsKillsItsChildAndReleasesTheRowOnceItIsGone() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED);
        // The launcher shuts down while the init acquires its row: its child starts after the shutdown
        when(lifecycle.acquireGpuForJob(eq(TASK), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenAnswer(i -> {
                    holds.put(TASK, new JobGpuHold(TASK, i.<String>getArgument(1), freeDevice, Instant.now(), "launcher"));
                    launcher.cleanup();
                    return freeDevice;
                });
        Set<String> argsFilesBefore = argsFileNames();

        CompletableFuture<ModelInitResult> result = launch(TASK);

        // Not left running for the shutdown to miss: the init fails at once instead of waiting out its timeout
        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        verify(lifecycle, timeout(10_000)).releaseGpuForModelInit(TASK);
        verify(lifecycle, after(1000).times(1)).releaseGpuForModelInit(any());
        assertFalse(lifecycle.hasJobGpuHold(TASK));
        // The child may be killed before it records its launch; one that did was gone before the release
        for (Launch launch : launches()) {
            assertReleasedAfterExit(TASK, launch);
        }
        assertEquals(Set.of(), argsFilesLeftSince(argsFilesBefore));
        assertEquals(1, failures.size(), "failures: " + failures);
        assertFalse(failures.get(0).retriable());
    }

    @Test
    void initLaunchedAfterShutdownStartsNothing() throws Exception {
        installExecutable(completes());
        acquirable(TASK, freeDevice);
        launcher.cleanup();

        CompletableFuture<ModelInitResult> result = launch(TASK);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
        assertEquals(List.of(), launches());
        assertEquals(1, failures.size(), "failures: " + failures);
        assertFalse(failures.get(0).retriable());
        assertEquals(ModelInitStatus.Status.FAILED, launcher.getCurrentStatus().status());
    }

    /** One recorded child launch: its pid, its {@code SD_MAX_DEVICE_BYTES} and its command line. */
    private record Launch(long pid, String maxDeviceBytesEnv, List<String> command) {
        Path argsFile() {
            return Path.of(command.get(command.size() - 1));
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

    /** {@link #installExecutable(Child, String)} for a child that writes its protocol to the channel. */
    private void installExecutable(String scenario) throws IOException {
        installExecutable(Child.CHANNEL, scenario);
    }

    /**
     * Installs the fake native model init executable: every child records its launch, then runs {@code scenario},
     * in which {@code protocol} prints a protocol line where {@code child} writes them.
     */
    private void installExecutable(Child child, String scenario) throws IOException {
        Files.writeString(executable, String.join("\n",
                "#!/bin/sh",
                "rec=$(mktemp '" + records + "/launch-XXXXXX')",
                "{ printf '%s\\n' \"$$\" \"$" + SubprocessBackendFlags.MAX_DEVICE_BYTES_ENV + "\" \"$0\"; "
                        + "for a in \"$@\"; do printf '%s\\n' \"$a\"; done; } > \"$rec\"",
                "mv \"$rec\" \"$rec" + LAUNCH_RECORD + "\"",
                child.setup,
                "protocol() { printf '%s\\n' \"$1\" >&\"$fd\"; }",
                scenario,
                ""));
        assertTrue(executable.toFile().setExecutable(true));
        when(configService.getExecutablePathForType("model-init")).thenReturn(executable.toString());
    }

    /** A child that reports its model ready and exits. */
    private static String completes() throws IOException {
        return reports(completed()) + "; exit 0";
    }

    private static ModelInitMessage completed() {
        return ModelInitMessage.completed(TASK, MODEL, "REGISTRY", "BGE", 768, 512, 10L, Map.of(), null);
    }

    private static ModelInitMessage progress() {
        return ModelInitMessage.progress(TASK, MODEL, ModelInitMessage.Phase.STARTING, 10, "starting");
    }

    /** A scenario step that writes {@code message} where the child writes its protocol. */
    private static String reports(ModelInitMessage message) throws IOException {
        return "protocol '" + protocolLine(message) + "'";
    }

    /** The protocol line the child writes for {@code message}. */
    private static String protocolLine(ModelInitMessage message) throws IOException {
        return ModelInitMessage.MESSAGE_PREFIX + JsonUtils.standardMapper().writeValueAsString(message);
    }

    private CompletableFuture<ModelInitResult> launch(String taskId) {
        return launch(taskId, null);
    }

    private CompletableFuture<ModelInitResult> launch(String taskId,
                                                      Consumer<ModelInitMessage.Progress> progressListener) {
        return launcher.launchModelInit(ModelInitSubprocessArgs.builder()
                .taskId(taskId)
                .modelIdentifier(MODEL)
                .skipValidation(true)
                .build(), progressListener, null, failures::add);
    }

    /**
     * A progress listener that holds the reader passing an update on until {@code release} opens, counting
     * {@code held} down once it holds it.
     */
    private static Consumer<ModelInitMessage.Progress> holdsReader(CountDownLatch held, CountDownLatch release) {
        return progress -> {
            held.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** The lines the task's run log, which the launcher keeps under {@code user.dir}, recorded from {@code stream}. */
    private static List<String> runLog(String taskId, AgentLogRecord.Stream stream) throws IOException {
        Path log = LogPaths.subprocessLogFile(Path.of(System.getProperty("user.dir")), "model-init", taskId).toPath();
        List<String> lines = new ArrayList<>();
        for (String entry : Files.readAllLines(log)) {
            AgentLogRecord record = JsonUtils.standardMapper().readValue(entry, AgentLogRecord.class);
            if (record.getStream() == stream) {
                lines.add(record.getLine());
            }
        }
        return lines;
    }

    /** A GPU placement for a task whose row the scheduler holds on {@code device}, as the scheduler passes it. */
    private SubprocessPlacement schedulerHeld(String taskId, GpuDevice device, long capBytes) {
        holds.put(taskId, new JobGpuHold(taskId, JobResourceProfiles.MODEL_INIT.serviceType(), device,
                Instant.now(), "scheduler"));
        return SubprocessPlacement.gpu(device.cudaRuntimeIndex(), ModelLifecycleManager.clampToDevice(capBytes, device));
    }

    /** Lets the launcher acquire the task's own row on {@code device}. */
    private void acquirable(String taskId, GpuDevice device) {
        when(lifecycle.acquireGpuForJob(eq(taskId), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenAnswer(i -> {
                    holds.put(taskId, new JobGpuHold(taskId, i.<String>getArgument(1), device, Instant.now(), "launcher"));
                    return device;
                });
    }

    /** Makes acquiring the task's row fail with {@code failure}. */
    private void notAcquirable(String taskId, RuntimeException failure) {
        when(lifecycle.acquireGpuForJob(eq(taskId), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenThrow(failure);
    }

    /** The placement of a task on the row the launcher acquires for it on {@link #freeDevice}. */
    private SubprocessPlacement onFreeDevice() {
        return SubprocessPlacement.gpu(freeDevice.cudaRuntimeIndex(), ModelLifecycleManager.clampToDevice(
                JobResourceProfiles.MODEL_INIT.peakGpuMemoryBytes(), freeDevice));
    }

    /** Releases the task's row, noting which recorded children were still running at that moment. */
    private void releaseRow(String taskId) throws IOException {
        Set<Long> alive = launches().stream()
                .map(Launch::pid)
                .filter(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .collect(Collectors.toSet());
        aliveAtRelease.merge(taskId, alive,
                (earlier, later) -> Stream.concat(earlier.stream(), later.stream()).collect(Collectors.toSet()));
        holds.remove(taskId);
    }

    private void assertReleasedAfterExit(String taskId, Launch launch) {
        Set<Long> alive = aliveAtRelease.get(taskId);
        assertNotNull(alive, "the row of " + taskId + " was never released");
        assertFalse(alive.contains(launch.pid()), "the row of " + taskId + " was released while its child ran");
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

    private Launch launchOn(List<Launch> launches, SubprocessPlacement placement) {
        List<String> expected = nativeCommandOn(placement);
        return launches.stream()
                .filter(launch -> launch.commandBeforeArgsFile().equals(expected))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no launch on " + placement + ": " + launches));
    }

    private List<Launch> launches() throws IOException {
        List<Launch> launches = new ArrayList<>();
        try (Stream<Path> files = Files.list(records)) {
            for (Path file : files.filter(f -> f.toString().endsWith(LAUNCH_RECORD)).toList()) {
                List<String> lines = Files.readAllLines(file);
                launches.add(new Launch(Long.parseLong(lines.get(0)), lines.get(1), lines.subList(2, lines.size())));
            }
        }
        return launches;
    }

    /** Waits for {@code expected} children to have recorded their launch — each does as it starts. */
    private List<Launch> awaitLaunches(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<Launch> launches;
        while ((launches = launches()).size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, launches.size(), "launches: " + launches);
        return launches;
    }

    /** Waits until the recorded child is gone. */
    private static void awaitExit(Launch launch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (isAlive(launch.pid()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(isAlive(launch.pid()), "child " + launch.pid() + " is still running");
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Waits for the child to create {@code file}. */
    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(file), file + " was never created");
    }

    /** Waits until the launcher tracks the task's running child, where cancel and cleanup find it. */
    private void awaitTracked(String taskId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!launcher.activeProcesses.containsKey(taskId) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(launcher.activeProcesses.containsKey(taskId), taskId + " was never tracked");
    }

    /** Names of the model init args files in the temp dir — only names: args files carry credentials. */
    private static Set<String> argsFileNames() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> ARGS_FILE.matcher(name).matches())
                    .collect(Collectors.toSet());
        }
    }

    /** Args files written since {@code before} that are still there. */
    private static Set<String> argsFilesLeftSince(Set<String> before) throws IOException {
        Set<String> left = new HashSet<>(argsFileNames());
        left.removeAll(before);
        return left;
    }
}
