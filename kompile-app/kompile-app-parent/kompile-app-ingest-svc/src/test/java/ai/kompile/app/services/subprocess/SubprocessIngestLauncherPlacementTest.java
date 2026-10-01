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
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.subprocess.SubprocessHandle.SubprocessResult;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every attempt of an ingest job runs on the placement the job was launched with, whatever the
 * launcher's shared placement says by then. The job's GPU row is released only by whoever acquired
 * it — never by the launcher for a row the scheduler holds, and once by the launcher for its own row,
 * when the job ends. Cancelling the job reaches whichever attempt is current or pending. A job gets one
 * verdict, reported once — the first of the child's own report, a stall, a cancel or its exit — and
 * its result waits for the child to be gone. A child's protocol has a pipe of its own, which native
 * output written to fd 1 can't reach; a child that still writes its protocol to fd 1 has it read from
 * stderr. A fake native executable records each child's command line and memory-cap env.
 */
@Timeout(60)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake native executable is a POSIX shell script")
class SubprocessIngestLauncherPlacementTest {

    private static final String JOB = "ingest-job";
    private static final String RETRY = JOB + "-retry1";
    private static final String LAUNCH_RECORD = ".launch";

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

    private final IngestProgressTracker progressTracker = mock(IngestProgressTracker.class);
    private final SubprocessConfigService configService = mock(SubprocessConfigService.class);
    private final ModelLifecycleManager lifecycle = mock(ModelLifecycleManager.class);
    private final Map<String, JobGpuHold> holds = new ConcurrentHashMap<>();
    private final GpuDevice freeDevice = GpuDevice.local(0, 1, "fake-gpu", 1L << 30);

    private Path executable;
    private Path records;
    private String userHome;
    private String userDir;
    private SubprocessIngestLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        // Checkpoints go under user.home and subprocess logs under user.dir — keep both in the temp dir
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        System.setProperty("user.dir", Files.createDirectories(tmp.resolve("work/.kompile")).getParent().toString());
        records = Files.createDirectories(tmp.resolve("records"));
        executable = tmp.resolve("kompile-ingest");

        when(configService.shouldUseNativeExecutableMode()).thenReturn(true);
        when(configService.useUnifiedExecutable("ingest")).thenReturn(true);
        when(configService.getSubprocessTypeFlag()).thenReturn("--subprocess=");

        when(lifecycle.getActiveJobHolds()).thenAnswer(i -> Map.copyOf(holds));
        when(lifecycle.hasJobGpuHold(anyString())).thenAnswer(i -> holds.containsKey(i.<String>getArgument(0)));
        doAnswer(i -> {
            holds.remove(i.<String>getArgument(0));
            return null;
        }).when(lifecycle).releaseGpuForJob(anyString());

        launcher = new SubprocessIngestLauncher(progressTracker, null, null, null, null, null,
                configService, null, null, null, null);
        launcher.modelLifecycleManager = lifecycle;
    }

    @AfterEach
    void tearDown() {
        launcher.shutdownAll();
        System.setProperty("user.home", userHome);
        System.setProperty("user.dir", userDir);
    }

    @Test
    void concurrentLaunchesEachRunOnTheirOwnPlacement() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        SubprocessPlacement onA = schedulerHeld("ingest-a", 0, 1L << 30);
        SubprocessPlacement onB = schedulerHeld("ingest-b", 1, 3L << 30);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier together = new CyclicBarrier(2);
            // Each caller leaves the launcher's shared placement set to the other job's
            Future<CompletableFuture<SubprocessResult>> a = callers.submit(() -> {
                together.await();
                launcher.applyPlacement(onB);
                return launch("ingest-a", onA);
            });
            Future<CompletableFuture<SubprocessResult>> b = callers.submit(() -> {
                together.await();
                launcher.applyPlacement(onA);
                return launch("ingest-b", onB);
            });
            assertTrue(a.get(20, TimeUnit.SECONDS).get(20, TimeUnit.SECONDS).success());
            assertTrue(b.get(20, TimeUnit.SECONDS).get(20, TimeUnit.SECONDS).success());
        } finally {
            callers.shutdownNow();
        }

        assertLaunchedOn(onA, launchOf("ingest-a"));
        assertLaunchedOn(onB, launchOf("ingest-b"));
    }

    @Test
    void oomRetryRunsOnTheFirstAttemptsPlacement() throws Exception {
        installExecutable(OOM_KILLED, COMPLETES);
        SubprocessPlacement placement = schedulerHeld(JOB, 1, 3L << 30);

        CompletableFuture<SubprocessResult> result = launch(JOB, placement);
        // The shared placement moving on to another job must not reach this job's retry
        launcher.applyPlacement(SubprocessPlacement.gpu(0, 1L << 20));

        assertTrue(result.get(30, TimeUnit.SECONDS).success());
        assertLaunchedOn(placement, launchOf(JOB));
        assertLaunchedOn(placement, launchOf(RETRY));
    }

    @Test
    void retryKeepsTheSchedulersGpuRow() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(OOM_KILLED, completesAfter(go));
        SubprocessPlacement placement = schedulerHeld(JOB, 1, 3L << 30);

        CompletableFuture<SubprocessResult> result = launch(JOB, placement);
        verify(progressTracker, timeout(15_000)).startTask(eq(RETRY), any(), any());
        // The first attempt has ended and the retry is running: the row is still the scheduler's
        assertTrue(lifecycle.hasJobGpuHold(JOB));
        verify(lifecycle, never()).releaseGpuForJob(any());

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(lifecycle, after(1500).never()).releaseGpuForJob(any());
        verify(lifecycle, never()).releaseGpuForIngest(any());
        verify(lifecycle, never()).acquireGpuForJob(any(), any(), any(), any(), anyLong(), any());
        assertTrue(lifecycle.hasJobGpuHold(JOB));
        assertLaunchedOn(placement, launchOf(RETRY));
    }

    @Test
    void launcherAcquiredRowIsReleasedOnceWhenTheJobEnds() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(OOM_KILLED, completesAfter(go));
        acquirable(JOB, freeDevice);

        CompletableFuture<SubprocessResult> result = launch(JOB, null);
        verify(progressTracker, timeout(15_000)).startTask(eq(RETRY), any(), any());
        // One row for the job, acquired before its first child was built; the retry runs on it
        verify(lifecycle).acquireGpuForJob(eq(JOB), eq(JobResourceProfiles.INGEST.serviceType()), anyString(),
                eq(HoldLifetime.BOUNDED), eq(JobResourceProfiles.INGEST.peakGpuMemoryBytes()), isNull());
        verify(lifecycle, never()).releaseGpuForJob(any());

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(lifecycle, timeout(10_000)).releaseGpuForJob(JOB);
        verify(lifecycle, after(1500).times(1)).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(JOB));

        assertLaunchedOn(onFreeDevice(), launchOf(JOB));
        assertLaunchedOn(onFreeDevice(), launchOf(RETRY));
    }

    @Test
    void jobThatCannotGetAGpuRowRunsOnCpu() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        when(lifecycle.acquireGpuForJob(eq(JOB), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenThrow(new GpuShortfallException("no room on any device"));

        // A GPU placement without a row behind it is never used as-is
        assertTrue(launch(JOB, SubprocessPlacement.gpu(1, 3L << 30)).get(20, TimeUnit.SECONDS).success());

        Launch launch = launchOf(JOB);
        assertEquals(nativeCommandOn(SubprocessPlacement.cpu()), launch.commandBeforeArgsFile());
        assertTrue(launch.command().stream().noneMatch(arg -> arg.startsWith("-Dnd4j.placement.defaultDevice=")));
        verify(lifecycle, after(1500).never()).releaseGpuForJob(any());
    }

    @Test
    void cancelByJobIdReachesTheRunningRetry() throws Exception {
        installExecutable(OOM_KILLED, RUNS_UNTIL_KILLED);
        acquirable(JOB, freeDevice);

        CompletableFuture<SubprocessResult> result = launch(JOB, null);
        verify(progressTracker, timeout(15_000)).startTask(eq(RETRY), any(), any());
        assertLaunchedOn(onFreeDevice(), launchOf(RETRY));

        assertTrue(launcher.cancelIngest(JOB));

        SubprocessResult cancelled = result.get(20, TimeUnit.SECONDS);
        assertTrue(cancelled.cancelled());
        assertFalse(cancelled.success());
        assertEquals(RETRY, cancelled.taskId());
        verify(lifecycle, timeout(10_000)).releaseGpuForJob(JOB);
        verify(lifecycle, after(1500).times(1)).releaseGpuForJob(any());
        assertEquals(2, launches().size());
    }

    @Test
    void cancelWhileARetryIsPendingEndsTheJobWithoutRelaunching() throws Exception {
        installExecutable(OOM_KILLED, COMPLETES);
        acquirable(JOB, freeDevice);
        // Cancel as the retry is scheduled, before its attempt starts
        doAnswer(i -> {
            launcher.cancelIngest(JOB);
            return null;
        }).when(progressTracker).sendLog(eq(JOB), eq("RETRY"), anyString());

        CompletableFuture<SubprocessResult> result = launch(JOB, null);

        SubprocessResult cancelled = result.get(20, TimeUnit.SECONDS);
        assertTrue(cancelled.cancelled());
        assertEquals("Cancelled by user", cancelled.errorMessage());
        // The retry's delay passes without a relaunch
        verify(progressTracker, after(2500).never()).startTask(eq(RETRY), any(), any());
        assertEquals(1, launches().size());
        verify(lifecycle, times(1)).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(JOB));
    }

    @Test
    void shutdownReleasesTheLaunchersRowOnce() throws Exception {
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);
        acquirable(JOB, freeDevice);

        CompletableFuture<SubprocessResult> result = launch(JOB, null);
        verify(progressTracker, timeout(10_000)).startTask(eq(JOB), any(), any());

        launcher.shutdownAll();

        assertTrue(result.get(20, TimeUnit.SECONDS).cancelled());
        verify(lifecycle, after(1500).times(1)).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(JOB));
    }

    @Test
    void shutdownAsARetryStartsEndsTheJob() throws Exception {
        // Its own ids: the args-file check below must not see another test's files
        String jobId = "shutdown-retry-start-" + UUID.randomUUID();
        String retryId = jobId + "-retry1";
        installExecutable(OOM_KILLED, COMPLETES);
        SubprocessPlacement placement = schedulerHeld(jobId, 1, 3L << 30);
        // Shut down while the retry's command is built — past its pending-retry check, before its
        // start — when no attempt is running for shutdown to stop
        when(configService.getExecutablePathForType("ingest"))
                .thenReturn(executable.toString())
                .thenAnswer(i -> {
                    launcher.shutdownAll();
                    return executable.toString();
                });

        SubprocessResult cancelled = launch(jobId, placement).get(20, TimeUnit.SECONDS);
        assertTrue(cancelled.cancelled());
        assertFalse(cancelled.success());
        verify(progressTracker, never()).startTask(eq(retryId), any(), any());
        assertEquals(1, launches().size());
        // The row is the scheduler's to release
        verify(lifecycle, never()).releaseGpuForJob(any());
        assertTrue(lifecycle.hasJobGpuHold(jobId));
        // The retry's args file was written before its start was refused
        List<String> left = Stream.concat(argsFilesOf(jobId).stream(), argsFilesOf(retryId).stream()).toList();
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }

    @Test
    void shutdownDeletesTheArgsFileOfAnAttemptItStopsBeforeItsWatcherRuns() throws Exception {
        String jobId = "shutdown-args-" + UUID.randomUUID();
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);
        // The stopped attempt's watcher is held at its verdict, as if the JVM exited right after shutdown
        CountDownLatch watcherHeld = new CountDownLatch(1);
        CountDownLatch releaseWatcher = new CountDownLatch(1);
        doAnswer(i -> {
            watcherHeld.countDown();
            releaseWatcher.await(20, TimeUnit.SECONDS);
            return null;
        }).when(progressTracker).cancelTask(eq(jobId), any(), any(), any(), any());

        launch(jobId, SubprocessPlacement.cpu());
        verify(progressTracker, timeout(10_000)).startTask(eq(jobId), any(), any());
        try {
            launcher.shutdownAll();

            assertTrue(watcherHeld.await(10, TimeUnit.SECONDS), "the stopped attempt's watcher reached its verdict");
            List<String> left = argsFilesOf(jobId);
            assertTrue(left.isEmpty(), "args files left behind: " + left);
        } finally {
            releaseWatcher.countDown();
        }
    }

    @Test
    void nativeCommandCarriesThePlacementBeforeTheDispatchToken() throws Exception {
        installExecutable(COMPLETES, COMPLETES);
        SubprocessPlacement placement = schedulerHeld(JOB, 1, 3L << 30);

        assertTrue(launch(JOB, placement).get(20, TimeUnit.SECONDS).success());

        Launch launch = launchOf(JOB);
        List<String> command = launch.command();
        int dispatch = command.indexOf("--subprocess=ingest");
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
        SubprocessPlacement placement = schedulerHeld(JOB, 1, 3L << 30);
        launcher.applyPlacement(placement);

        assertTrue(launcher.launchIngest(JOB, tmp.resolve(JOB + ".pdf"), null, null, Map.of())
                .get(20, TimeUnit.SECONDS).success());

        assertLaunchedOn(placement, launchOf(JOB));
    }

    @Test
    void failureBehindABacklogOfOutputIsTheJobsVerdict() throws Exception {
        // Slow UI log forwarding: the FAILED report is still unread when the child exits
        doAnswer(i -> {
            Thread.sleep(2);
            return null;
        }).when(progressTracker).sendLog(eq(JOB), eq("STDOUT"), anyString());

        // A child the launcher did not wrap shares its protocol's pipe with its other output
        assertFailureBehindABacklogIsTheVerdict(Child.UNWRAPPED);
    }

    @Test
    void failureBehindABacklogOfStderrIsTheJobsVerdict() throws Exception {
        doAnswer(i -> {
            Thread.sleep(2);
            return null;
        }).when(progressTracker).sendLog(eq(JOB), eq("STDERR"), anyString(), anyString());

        // A wrapped child that writes its protocol to fd 1 has it on stderr, behind its other output
        assertFailureBehindABacklogIsTheVerdict(Child.LEGACY);
    }

    private void assertFailureBehindABacklogIsTheVerdict(Child child) throws Exception {
        String locked = "Index directory is locked";
        installExecutable(child, "i=0; while [ $i -lt 250 ]; do printf '%s\\n' '" + "x".repeat(200) + "'; i=$((i+1)); done; "
                + "protocol '" + failed(locked, "java.io.IOException") + "'; exit 1", COMPLETES);

        SubprocessResult failure = launch(JOB, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertFalse(failure.success());
        assertEquals(locked, failure.errorMessage());
        verify(progressTracker, timeout(5_000)).failTask(eq(JOB), any(), any(), eq(locked));
        verify(progressTracker, after(1000).times(1)).failTask(any(), any(), any(), anyString());
        // The run's log ends in the verdict's state
        assertEquals("FAILED", logEndStateOf(JOB));
        assertEquals(1, launches().size());
    }

    @Test
    void nativeOutputOfAChildOnTheChannelIsItsStderr() throws Exception {
        // libnd4j prints to fd 1 beneath the child's System.setOut redirect, and its text may lack a newline.
        // The launcher made fd 1 the stderr pipe; the protocol has a pipe of its own.
        installExecutable("printf '%s' '[native] no newline '; " + COMPLETES, "exit 3");

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        verify(progressTracker, timeout(5_000)).sendLog(JOB, "STDERR", "INFO", "[native] no newline ");
        verify(progressTracker, never()).sendLog(eq(JOB), eq("STDOUT"), anyString());
        assertEquals(1, launches().size());
    }

    @Test
    void completionBehindNativeOutputOnTheSameLineIsTheJobsVerdict() throws Exception {
        // A child the launcher did not wrap: native text shares the protocol's pipe, and may lack a newline
        installExecutable(Child.UNWRAPPED, "printf '%s' '[native] no newline '; " + COMPLETES, "exit 3");

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        // The native text is still the child's output
        verify(progressTracker, timeout(5_000)).sendLog(JOB, "STDOUT", "[native] no newline ");
        assertEquals(1, launches().size());
    }

    @Test
    void completionOnStderrBehindNativeOutputOnTheSameLineIsTheJobsVerdict() throws Exception {
        // A wrapped child that writes its protocol to fd 1, a build from before the channel, has it on stderr
        installExecutable(Child.LEGACY, "printf '%s' '[native] no newline '; " + COMPLETES, "exit 3");

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        verify(progressTracker, timeout(5_000)).sendLog(JOB, "STDERR", "INFO", "[native] no newline ");
        // The report is the job's verdict, not log text
        verify(progressTracker, never()).sendLog(eq(JOB), eq("STDERR"), anyString(), contains(SubprocessMessage.MESSAGE_PREFIX));
        assertEquals(1, launches().size());
    }

    @Test
    void stderrOfAChildOnTheChannelIsNeverItsVerdict() throws Exception {
        // Once the child has said its protocol goes to the channel, a protocol line on its stderr is text it
        // quoted, such as a log of a message
        String quoted = "quoted in a log";
        installExecutable("printf '%s\\n' '" + failed(quoted, "java.io.IOException") + "' >&2; sleep 0.3; " + COMPLETES,
                "exit 3");

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(20, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        verify(progressTracker, timeout(5_000)).sendLog(eq(JOB), eq("STDERR"), anyString(), contains(quoted));
        verify(progressTracker, never()).failTask(any(), any(), any(), anyString());
    }

    @Test
    void memoryThresholdStopIsRetried() throws Exception {
        // The child's memory watchdog stops it before the JVM runs out, and says why
        installExecutable("protocol '" + failed("Memory threshold exceeded after embedding - aborting to prevent OOM",
                "MemoryThreshold") + "'; exit 1", COMPLETES);

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(30, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        assertEquals(RETRY, outcome.taskId());
        verify(progressTracker, never()).failTask(any(), any(), any(), anyString());
        verify(progressTracker, never()).failTaskOutOfMemory(any(), any(), any(), any());
    }

    @Test
    void memoryKillThresholdStopIsRetried() throws Exception {
        installExecutable("protocol '" + failed("Memory kill threshold exceeded after embedding",
                "MemoryKillThreshold") + "'; exit 137", COMPLETES);

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(30, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        assertEquals(RETRY, outcome.taskId());
        verify(progressTracker, never()).failTask(any(), any(), any(), anyString());
        verify(progressTracker, never()).failTaskOutOfMemory(any(), any(), any(), any());
    }

    @Test
    void outOfMemoryNoticeOnFd1IsRetried() throws Exception {
        // -XX:+ExitOnOutOfMemoryError prints its notice to fd 1, the stderr pipe once the child is wrapped,
        // then exits 3
        installExecutable("printf '%s\\n' 'Terminating due to java.lang.OutOfMemoryError: Java heap space'; exit 3",
                COMPLETES);

        SubprocessResult outcome = launch(JOB, SubprocessPlacement.cpu()).get(30, TimeUnit.SECONDS);

        assertTrue(outcome.success(), () -> "outcome: " + outcome.errorMessage());
        assertEquals(RETRY, outcome.taskId());
        verify(progressTracker, never()).failTask(any(), any(), any(), anyString());
        verify(progressTracker, never()).failTaskOutOfMemory(any(), any(), any(), any());
        // The retry's checkpoint records what ended the first attempt
        String checkpoint = Files.readString(tmp.resolve("home/.kompile/checkpoints/ingest-" + JOB + ".checkpoint.json"));
        assertTrue(checkpoint.contains("Out of memory - JVM exited via -XX:+ExitOnOutOfMemoryError"), checkpoint);
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
        }).when(progressTracker).sendLog(eq(JOB), eq("STDOUT"), eq("hold"));

        CompletableFuture<SubprocessResult> result = launch(JOB, SubprocessPlacement.cpu());
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
            assertTrue(launcher.cancelIngest(JOB));
            // A watcher that judged the exit without draining the reader would have judged by now
            Thread.sleep(300);
        } finally {
            releaseReader.countDown();
        }

        assertTrue(result.get(20, TimeUnit.SECONDS).success());
        verify(progressTracker, timeout(5_000)).completeTask(eq(JOB), any(), any());
        verify(progressTracker, after(1000).never()).cancelTask(any(), any(), any(), any(), any());
    }

    @Test
    void unresponsiveAttemptGetsOneStuckVerdictBeforeItIsKilled() throws Exception {
        when(configService.getStaleThresholdSeconds()).thenReturn(0);
        installExecutable(RUNS_UNTIL_KILLED, COMPLETES);
        CompletableFuture<SubprocessResult> result = launch(JOB, SubprocessPlacement.cpu());
        verify(progressTracker, timeout(10_000)).startTask(eq(JOB), any(), any());
        // It has recorded its launch, so the kill doesn't end it unrecorded
        launchOf(JOB);
        // No heartbeat since it started
        Thread.sleep(50);

        launcher.checkStaleProcesses();

        String stuck = "Process became unresponsive (no heartbeat)";
        SubprocessResult verdict = result.get(20, TimeUnit.SECONDS);
        assertFalse(verdict.success());
        assertFalse(verdict.cancelled(), "the kill that follows the verdict is not a cancel");
        assertEquals(stuck, verdict.errorMessage());
        verify(progressTracker).failTask(eq(JOB), any(), any(), eq(stuck), eq(IngestProgressUpdate.FailureReason.PROCESS_STUCK));
        verify(progressTracker, after(1000).times(1)).failTask(any(), any(), any(), any(), any());
        verify(progressTracker, never()).failTask(any(), any(), any(), anyString());
        verify(progressTracker, never()).cancelTask(any(), any(), any(), any(), any());
        assertEquals("PROCESS_STUCK", logEndStateOf(JOB));
        assertEquals(1, launches().size());
    }

    @Test
    void launchAfterShutdownNeverStarts() throws Exception {
        String jobId = "after-shutdown-" + UUID.randomUUID();
        installExecutable(COMPLETES, COMPLETES);
        acquirable(jobId, freeDevice);
        launcher.shutdownAll();

        SubprocessResult stopped = launch(jobId, null).get(20, TimeUnit.SECONDS);

        assertTrue(stopped.cancelled());
        assertEquals("Cancelled before start", stopped.errorMessage());
        assertTrue(launches().isEmpty());
        verify(progressTracker, never()).startTask(any(), any(), any());
        verify(progressTracker, timeout(5_000)).cancelTask(eq(jobId), any(), any(), eq("Cancelled before start"), any());
        // The row it acquired on the way in is released once
        verify(lifecycle, times(1)).releaseGpuForJob(jobId);
        assertFalse(lifecycle.hasJobGpuHold(jobId));
        List<String> left = argsFilesOf(jobId);
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }

    @Test
    void resultWaitsForTheChildToExitAfterItsCompletionMessage() throws Exception {
        Path go = tmp.resolve("go");
        installExecutable(completesThenExitsAfter(go), COMPLETES);
        SubprocessPlacement placement = schedulerHeld(JOB, 1, 3L << 30);

        CompletableFuture<SubprocessResult> result = launch(JOB, placement);
        verify(progressTracker, timeout(10_000)).completeTask(eq(JOB), any(), any());
        // The scheduler frees the job's GPU row when this completes; the child still has the device
        assertThrows(TimeoutException.class, () -> result.get(1, TimeUnit.SECONDS));

        Files.createFile(go);
        assertTrue(result.get(20, TimeUnit.SECONDS).success());
    }

    @Test
    void launchFailureIsReportedOnce() throws Exception {
        String jobId = "launch-failure-" + UUID.randomUUID();
        acquirable(jobId, freeDevice);
        when(configService.getExecutablePathForType("ingest")).thenReturn(tmp.resolve("missing-executable").toString());

        CompletableFuture<SubprocessResult> result = launch(jobId, null);

        assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        verify(progressTracker, timeout(5_000)).failTask(eq(jobId), any(), any(),
                startsWith("Failed to launch ingest subprocess"));
        verify(progressTracker, after(1000).times(1)).failTask(any(), any(), any(), anyString());
        verify(progressTracker, never()).startTask(any(), any(), any());
        verify(lifecycle, times(1)).releaseGpuForJob(jobId);
        assertFalse(lifecycle.hasJobGpuHold(jobId));
        List<String> left = argsFilesOf(jobId);
        assertTrue(left.isEmpty(), "args files left behind: " + left);
        assertTrue(launches().isEmpty());
    }

    @Test
    void cancelWhileTheFirstAttemptIsBeingBuiltEndsTheJobOnce() throws Exception {
        String jobId = "cancel-before-start-" + UUID.randomUUID();
        installExecutable(COMPLETES, COMPLETES);
        acquirable(jobId, freeDevice);
        // Cancel while the first attempt's command is built: its args file is written, its child not started
        AtomicBoolean cancelAccepted = new AtomicBoolean();
        when(configService.getExecutablePathForType("ingest")).thenAnswer(i -> {
            cancelAccepted.set(launcher.cancelIngest(jobId));
            return executable.toString();
        });

        SubprocessResult cancelled = launch(jobId, null).get(20, TimeUnit.SECONDS);

        assertTrue(cancelAccepted.get());
        assertTrue(cancelled.cancelled());
        assertEquals("Cancelled by user", cancelled.errorMessage());
        assertTrue(launches().isEmpty());
        verify(progressTracker, never()).startTask(any(), any(), any());
        verify(progressTracker, after(1000).times(1)).cancelTask(any(), any(), any(), any(), any());
        verify(lifecycle, times(1)).releaseGpuForJob(jobId);
        assertFalse(lifecycle.hasJobGpuHold(jobId));
        List<String> left = argsFilesOf(jobId);
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }

    @Test
    void shutdownWhileARetryIsPendingEndsTheJobAsStopped() throws Exception {
        // Its own ids: the args-file check below must not see another test's files
        String jobId = "shutdown-retry-pending-" + UUID.randomUUID();
        String retryId = jobId + "-retry1";
        installExecutable(OOM_KILLED, COMPLETES);
        acquirable(jobId, freeDevice);
        // Shut down as the retry is scheduled, before its attempt starts
        doAnswer(i -> {
            launcher.shutdownAll();
            return null;
        }).when(progressTracker).sendLog(eq(jobId), eq("RETRY"), anyString());

        SubprocessResult stopped = launch(jobId, null).get(20, TimeUnit.SECONDS);

        String shutdown = "Ingest stopped: the application is shutting down";
        assertTrue(stopped.cancelled());
        assertEquals(shutdown, stopped.errorMessage());
        verify(progressTracker, timeout(5_000)).cancelTask(eq(jobId), any(), any(), eq(shutdown), any());
        // The retry's delay passes without a relaunch
        verify(progressTracker, after(2500).never()).startTask(eq(retryId), any(), any());
        assertEquals(1, launches().size());
        verify(lifecycle, times(1)).releaseGpuForJob(any());
        assertFalse(lifecycle.hasJobGpuHold(jobId));
        // Neither the stopped attempt's args file nor the unstarted retry's is left
        List<String> left = Stream.concat(argsFilesOf(jobId).stream(), argsFilesOf(retryId).stream()).toList();
        assertTrue(left.isEmpty(), "args files left behind: " + left);
    }

    /** One recorded child launch: its {@code SD_MAX_DEVICE_BYTES} and its command line. */
    private record Launch(String maxDeviceBytesEnv, List<String> command) {
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
    private void installExecutable(String firstAttempt, String retryAttempt) throws IOException {
        installExecutable(Child.CHANNEL, firstAttempt, retryAttempt);
    }

    /**
     * Installs the fake native ingest executable. Every attempt records its launch, then runs
     * {@code firstAttempt} — or {@code retryAttempt} for a retry, whose args file carries its
     * {@code -retryN} taskId. In both, {@code protocol} prints a protocol line where {@code child} writes them.
     */
    private void installExecutable(Child child, String firstAttempt, String retryAttempt) throws IOException {
        Files.writeString(executable, String.join("\n",
                "#!/bin/sh",
                "rec=$(mktemp '" + records + "/attempt-XXXXXX')",
                "{ printf '%s\\n' \"$" + SubprocessBackendFlags.MAX_DEVICE_BYTES_ENV + "\" \"$0\"; "
                        + "for a in \"$@\"; do printf '%s\\n' \"$a\"; done; } > \"$rec\"",
                "mv \"$rec\" \"$rec" + LAUNCH_RECORD + "\"",
                child.setup,
                "protocol() { printf '%s\\n' \"$1\" >&\"$fd\"; }",
                "for last; do :; done",
                "case \"$last\" in",
                "  *-retry[0-9]*) " + retryAttempt + " ;;",
                "  *) " + firstAttempt + " ;;",
                "esac",
                ""));
        assertTrue(executable.toFile().setExecutable(true));
        when(configService.getExecutablePathForType("ingest")).thenReturn(executable.toString());
    }

    /** An attempt that completes once {@code signal} exists. */
    private static String completesAfter(Path signal) {
        return "while [ ! -f '" + signal + "' ]; do sleep 0.05; done; " + COMPLETES;
    }

    /** An attempt that reports completion, then exits once {@code signal} exists. */
    private static String completesThenExitsAfter(Path signal) {
        return "protocol '" + COMPLETED_MESSAGE + "'; while [ ! -f '" + signal + "' ]; do sleep 0.05; done; exit 0";
    }

    /** A FAILED report as the child's protocol prints it. */
    private static String failed(String errorMessage, String errorType) {
        return SubprocessMessage.MESSAGE_PREFIX + "{\"type\":\"FAILED\",\"taskId\":\"t\",\"phase\":\"EMBEDDING\","
                + "\"errorMessage\":\"" + errorMessage + "\",\"errorType\":\"" + errorType + "\",\"stackTrace\":null}";
    }

    /** A GPU placement for a job whose row the scheduler holds, as the scheduler passes it. */
    private SubprocessPlacement schedulerHeld(String jobId, int cudaDevice, long capBytes) {
        GpuDevice device = GpuDevice.local(cudaDevice, cudaDevice, "gpu-" + cudaDevice, 8L << 30);
        holds.put(jobId, new JobGpuHold(jobId, JobResourceProfiles.INGEST.serviceType(), device,
                Instant.now(), "scheduler"));
        return SubprocessPlacement.gpu(device.cudaRuntimeIndex(), ModelLifecycleManager.clampToDevice(capBytes, device));
    }

    /** Lets the launcher acquire the job's own row on {@code device}. */
    private void acquirable(String jobId, GpuDevice device) {
        when(lifecycle.acquireGpuForJob(eq(jobId), anyString(), anyString(), eq(HoldLifetime.BOUNDED), anyLong(), isNull()))
                .thenAnswer(i -> {
                    holds.put(jobId, new JobGpuHold(jobId, JobResourceProfiles.INGEST.serviceType(), device,
                            Instant.now(), "launcher"));
                    return device;
                });
    }

    /** The placement of a job on the row the launcher acquires for it on {@link #freeDevice}. */
    private SubprocessPlacement onFreeDevice() {
        return SubprocessPlacement.gpu(freeDevice.cudaRuntimeIndex(),
                ModelLifecycleManager.clampToDevice(JobResourceProfiles.INGEST.peakGpuMemoryBytes(), freeDevice));
    }

    private CompletableFuture<SubprocessResult> launch(String jobId, SubprocessPlacement placement) {
        return launcher.launchIngest(jobId, tmp.resolve(jobId + ".pdf"), null, null, Map.of(), placement);
    }

    /** The command of a child on {@code placement}, up to its args file. */
    private List<String> nativeCommandOn(SubprocessPlacement placement) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(SubprocessBackendFlags.jvmFlags(placement, BackendPreference.INHERIT));
        command.add("--subprocess=ingest");
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
                launches.add(new Launch(lines.get(0), lines.subList(1, lines.size())));
            }
        }
        return launches;
    }

    /**
     * The launch of the attempt with this taskId — its args file is named after it. An attempt records
     * its launch as it starts, so this waits for the record.
     */
    private Launch launchOf(String attemptTaskId) throws Exception {
        Pattern argsFile = Pattern.compile("ingest-args-" + Pattern.quote(attemptTaskId) + "-[0-9]+\\.json");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<Launch> matching;
        while ((matching = launches().stream()
                .filter(launch -> argsFile.matcher(launch.argsFileName()).matches())
                .toList()).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, matching.size(), "launches of " + attemptTaskId);
        return matching.get(0);
    }

    /** The names of an attempt's args files left in the temp dir. Only names: the files carry credentials. */
    private static List<String> argsFilesOf(String attemptTaskId) throws IOException {
        Pattern argsFile = Pattern.compile("ingest-args-" + Pattern.quote(attemptTaskId) + "-[0-9]+\\.json");
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> argsFile.matcher(name).matches())
                    .toList();
        }
    }

    /** The state an attempt's subprocess log ended in, once its run has ended. */
    private String logEndStateOf(String attemptTaskId) throws Exception {
        Path meta = tmp.resolve("work/.kompile/logs/subprocesses/ingest/" + attemptTaskId + ".meta.json");
        ObjectMapper mapper = new ObjectMapper();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String state = null;
        while (System.nanoTime() < deadline) {
            try {
                state = mapper.readTree(meta.toFile()).path("state").asText(null);
                if (state != null && !"RUNNING".equals(state)) {
                    return state;
                }
            } catch (IOException notWrittenYet) {
                // Missing, or caught mid-write
            }
            Thread.sleep(20);
        }
        return state;
    }
}
