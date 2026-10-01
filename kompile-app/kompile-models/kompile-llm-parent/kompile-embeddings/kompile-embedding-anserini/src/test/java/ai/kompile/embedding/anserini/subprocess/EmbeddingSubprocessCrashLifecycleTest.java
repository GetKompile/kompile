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

package ai.kompile.embedding.anserini.subprocess;

import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash handling of the embedding launcher against a real child process.
 *
 * <p>Five detectors report a child's death or hang: the output reader's EOF or IOException, the
 * health monitor, a failed stdin write, the sendRequest pre-check and the watchdog. One death used
 * to restart the lane once per detector and count once per detector against the restart limit; a
 * late report about an old child restarted its healthy replacement; a hung child was left running
 * next to its replacement.
 *
 * <p>A dying child's output closes before the kernel reports its exit, so its exit code used to
 * read as -1 and a DEVICE_ERROR exit was restarted as a stall. A child that was not restarted kept
 * its health monitor and its registry entry until the next {@code stop()}.
 *
 * <p>A DEVICE_ERROR exit (78, a poisoned CUDA context) used to end the lane at once, with no
 * restart. A new process gets a new CUDA context, so the restart policy now decides, as for any
 * crash. Once the launcher gives up on a child, whatever its exit code, the lane is marked down:
 * requests fail at once and say why, until a {@code start()} brings it back.
 *
 * <p>libnd4j prints to fd 1 beneath the child's {@code System.setOut} redirect. Native text without
 * a newline hid the response written after it: the launcher took the line for plain output, and
 * the request waited out its timeout. The launcher now gives the responses a pipe of their own
 * ({@link SubprocessProtocolChannel}) and reads fd 1 as stderr. A child that still writes its
 * responses to fd 1 has them read from stderr; once a child reports that it uses the channel, a
 * stderr line that quotes a response is only logged.
 *
 * <p>{@code Process.destroy()} and {@code destroyForcibly()} close this side of the child's pipes,
 * and {@code stop()} closed them itself, so what a child wrote just before its kill was lost: the
 * response to a pending request, its last error lines. The kills now only signal the child
 * ({@code SubprocessSignals}), and stop and crash handling wait, bounded, for the readers to take
 * that output. The tests hold a reader in the log callback while the child writes it, kill the
 * child, and release the reader only afterwards.
 *
 * <p>The child is {@link FakeEmbeddingChild}, started through a launch script in place of the
 * native binary, so no ND4J is loaded on either side. It records every start and load request in
 * a directory the test reads.
 */
@DisplayName("EmbeddingSubprocessLauncher crash lifecycle")
class EmbeddingSubprocessCrashLifecycleTest {

    private static final String DUMMY_KEY = "dummy-key-for-test";

    /** What a child prints on stderr once its responses go to the protocol channel. */
    private static final String CHANNEL_OPEN_NOTICE = "[kompile-subprocess] protocol channel open on fd ";

    @TempDir
    Path tempDir;

    private final AtomicInteger crashes = new AtomicInteger();
    private final List<EmbeddingSubprocessLauncher> launchers = new ArrayList<>();
    private Path fakeDir;
    private Path argFile;
    private Path script;
    private String savedUserHome;
    private String savedStagingUrl;
    private String savedStagingKey;
    private int savedRetryPollSeconds;

    /** Lets a reader held by {@link #holdingOn} go on. */
    private final CountDownLatch readerReleased = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        // Read before user.home moves: the factory's registry is created on first use.
        savedStagingUrl = AnseriniEncoderFactory.getStagingUrl();
        savedStagingKey = AnseriniEncoderFactory.getStagingApiKey();
        savedRetryPollSeconds = AnseriniEncoderFactory.getStagingRetryPollIntervalSeconds();

        // Crash diagnostics go to user.home; the run log goes to the nearest .kompile above the
        // working directory.
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tempDir.resolve("home")).toString());
        Files.createDirectories(tempDir.resolve(".kompile"));
        fakeDir = Files.createDirectories(tempDir.resolve("fake"));

        // The test classpath is too long for one argv element (MAX_ARG_STRLEN), so the child JVM
        // reads it from an argument file. The script ignores the launcher's native flags.
        argFile = tempDir.resolve("fake-child.args");
        Files.write(argFile, List.of(
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-XX:-UsePerfData",
                "-cp",
                quoteForArgFile(System.getProperty("java.class.path")),
                FakeEmbeddingChild.class.getName()), StandardCharsets.UTF_8);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String exec = "exec '" + java + "' @'" + argFile + "'";
        // With FAKE_EMBED_UNWRAPPED, a child the launcher wrapped gets the layout of one it did not
        // wrap: the protocol pipe on fd 1, and no fd 3.
        String fdVar = SubprocessProtocolChannel.ENV_PROTOCOL_FD;
        script = tempDir.resolve("fake-embed.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "if [ -n \"$FAKE_EMBED_UNWRAPPED\" ] && [ -n \"$" + fdVar + "\" ]; then\n"
                + "  [ \"$" + fdVar + "\" = 3 ] || exit 64\n"
                + "  unset " + fdVar + "\n"
                + "  " + exec + " 1>&3 3>&-\n"
                + "fi\n"
                + exec + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            readerReleased.countDown();
            for (EmbeddingSubprocessLauncher launcher : launchers) {
                launcher.stop();
            }
            killLeftoverChildren();
        } finally {
            System.setProperty("user.home", savedUserHome);
            AnseriniEncoderFactory.configureStagingService(savedStagingUrl, savedStagingKey, savedRetryPollSeconds);
        }
    }

    @Test
    @DisplayName("claimCrash accepts only the current child, and only once")
    void claimCrashAcceptsOnlyTheCurrentChildOnce() {
        EmbeddingSubprocessLauncher launcher = fakeChildBuilder().build();
        Process current = new InertProcess();
        launcher.process = current;

        assertFalse(launcher.claimCrash(null));
        assertFalse(launcher.claimCrash(new InertProcess()), "a report about a child that was replaced");
        assertTrue(launcher.claimCrash(current));
        assertFalse(launcher.claimCrash(current), "a second report of the same death");
    }

    @Test
    @DisplayName("a replacement child can be claimed; its predecessor no longer can")
    void aReplacementCanBeClaimedAndItsPredecessorCannot() {
        EmbeddingSubprocessLauncher launcher = fakeChildBuilder().build();
        Process first = new InertProcess();
        Process second = new InertProcess();

        launcher.process = first;
        assertTrue(launcher.claimCrash(first));
        launcher.process = second;
        assertTrue(launcher.claimCrash(second));
        assertFalse(launcher.claimCrash(first));
    }

    @Test
    @DisplayName("two reports of one death restart once, kill the old child and reload with the caller's request")
    void oneDeathRestartsOnceAndReloadsTheSameRequest() throws Exception {
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        launcher.start();
        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of("k", "v")).get(30, TimeUnit.SECONDS).success());
        Process first = launcher.process;

        launcher.handleSubprocessCrash(first);
        // A second detector reporting the same death, after the replacement is up.
        launcher.handleSubprocessCrash(first);

        awaitEvents(events -> starts(events).size() >= 2 && loads(events).size() >= 2);
        Thread.sleep(500); // room for a late restart from the old child's reader
        List<String> events = events();

        assertEquals(2, starts(events).size(), events::toString);
        assertEquals(1, crashes.get(), "one death, one crash callback");
        assertEquals(1, launcher.getRestartAttempts(), "one death counts once against the restart limit");
        assertNotSame(first, launcher.process);
        assertTrue(launcher.process.isAlive());
        assertFalse(first.isAlive());
        assertTrue(starts(events).get(1).contains("prevAlive=false"), events::toString);
        assertEquals(List.of("load m 7 11 13 {k=v}", "load m 7 11 13 {k=v}"), loads(events),
                "the reload must repeat the caller's batch sizes and model config");
        assertTrue(launcher.isRunning());
    }

    @Test
    void aThrowingCrashObserverDoesNotStrandTheLane() throws Exception {
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().crashCallback(error -> {
            crashes.incrementAndGet();
            throw new IllegalStateException("observer failed (test)");
        }).build());
        launcher.start();
        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        Process first = launcher.process;

        assertDoesNotThrow(() -> launcher.handleSubprocessCrash(first));
        launcher.handleSubprocessCrash(first);
        assertEquals(1, crashes.get());
        assertNotSame(first, launcher.process);
        assertTrue(launcher.isRunning());
        assertTrue(launcher.isModelLoaded());
    }

    @Test
    void aThrowingRestartPolicyRetiresTheClaimedChild() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        RecordingRestartPolicy policy = new RecordingRestartPolicy() {
            @Override
            public EmbeddingSubprocessLauncher.RestartConfiguration shouldRestart(
                    String taskId, int exitCode, String reason, int attempt) {
                throw new IllegalStateException("policy failed (test)");
            }
        };
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        launcher.setSubprocessRegistry(registry);
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        Process first = launcher.process;

        assertDoesNotThrow(() -> launcher.handleSubprocessCrash(first));
        launcher.handleSubprocessCrash(first);
        assertTrue(launcher.isLaneUnavailable());
        assertFalse(launcher.isRunning());
        assertFalse(first.isAlive());
        assertTrue(launcher.healthMonitor.isShutdown());
        assertTrue(registry.get("embedding").isEmpty());
        assertEquals(1, policy.exhausted.get());
        assertEquals(0, policy.successes.get());
    }

    @Test
    void aRejectedReloadRetiresTheReplacementWithoutReportingSuccess() throws Exception {
        assertFailedReloadRetiresReplacement("reject-load");
    }

    @Test
    void aTimedOutReloadRetiresTheReplacementWithoutReportingSuccess() throws Exception {
        assertFailedReloadRetiresReplacement("ignore-load");
    }

    private void assertFailedReloadRetiresReplacement(String marker) throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        RecordingRestartPolicy policy = new RecordingRestartPolicy();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().loadModelTimeoutMs(3000).build());
        launcher.setSubprocessRegistry(registry);
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        Files.createFile(fakeDir.resolve(marker));
        Process first = launcher.process;

        launcher.handleSubprocessCrash(first);
        assertNotSame(first, launcher.process);
        assertTrue(launcher.isLaneUnavailable(), "a live but unloaded child must not suppress owner recovery");
        assertFalse(launcher.isRunning());
        assertFalse(launcher.isModelLoaded());
        assertFalse(launcher.process.isAlive(), "the failed replacement still holds its GPU reservation");
        assertTrue(launcher.healthMonitor.isShutdown());
        assertTrue(registry.get("embedding").isEmpty());
        assertEquals(0, policy.successes.get());
        assertEquals(1, policy.exhausted.get());
    }

    @Test
    void restartSuccessIsReportedOnlyAfterTheModelReloaded() throws Exception {
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        AtomicBoolean loadedAtSuccess = new AtomicBoolean();
        RecordingRestartPolicy policy = new RecordingRestartPolicy() {
            @Override
            public void onRestartSuccess(String taskId, int attemptNumber) {
                loadedAtSuccess.set(launcher.isModelLoaded());
                super.onRestartSuccess(taskId, attemptNumber);
            }
        };
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        launcher.handleSubprocessCrash(launcher.process);
        assertEquals(1, policy.successes.get());
        assertTrue(loadedAtSuccess.get(), "process start alone is not a successful model recovery");
    }

    private static class RecordingRestartPolicy implements EmbeddingSubprocessLauncher.RestartPolicyCallback {
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger exhausted = new AtomicInteger();

        @Override
        public EmbeddingSubprocessLauncher.RestartConfiguration shouldRestart(
                String taskId, int exitCode, String reason, int attempt) {
            return new EmbeddingSubprocessLauncher.RestartConfiguration(10, 0, 0, 0, "test restart");
        }

        @Override
        public void onRestartAttempt(String taskId, String fileName, int attempt, int maxAttempts,
                                     String reason, EmbeddingSubprocessLauncher.RestartConfiguration config) {}

        @Override
        public void onRestartSuccess(String taskId, int attempt) { successes.incrementAndGet(); }

        @Override
        public void onRestartExhausted(String taskId, int attempts, String reason) { exhausted.incrementAndGet(); }
    }

    @Test
    @DisplayName("a hung child is killed before its replacement starts")
    void aHungChildIsKilledBeforeItsReplacementStarts() throws Exception {
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().heartbeatTimeoutMs(300).build());
        launcher.start();
        awaitEvents(events -> starts(events).size() >= 1);
        Process first = launcher.process;
        Thread.sleep(400);

        // The fake child sends no heartbeats: to the health check it is alive but hung.
        assertTrue(first.isAlive());
        launcher.checkHealth();

        List<String> events = awaitEvents(e -> starts(e).size() >= 2);
        assertEquals(1, crashes.get());
        assertEquals(1, launcher.getRestartAttempts());
        assertFalse(first.isAlive(), "the hung child still holds its device memory");
        assertNotSame(first, launcher.process);
        assertTrue(launcher.process.isAlive());
        assertTrue(starts(events).get(1).contains("prevAlive=false"), events::toString);
    }

    @Test
    @DisplayName("a managed child gets the key this process resolved, through its environment")
    void managedChildGetsTheResolvedKey() throws Exception {
        AnseriniEncoderFactory.configureStagingService("http://staging.test", DUMMY_KEY);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .localModelOnly(false)
                .environment(staleKeyEnvironment())
                .build());

        launcher.start();

        assertEquals("match", keyStateOfFirstChild());
    }

    @Test
    @DisplayName("a managed child gets no key when this process has none, not an inherited one")
    void managedChildWithoutAKeyGetsNone() throws Exception {
        AnseriniEncoderFactory.configureStagingService("http://staging.test", null);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .localModelOnly(false)
                .environment(staleKeyEnvironment())
                .build());

        launcher.start();

        assertEquals("absent", keyStateOfFirstChild());
    }

    @Test
    @DisplayName("a local-only child gets no key")
    void localOnlyChildGetsNoKey() throws Exception {
        AnseriniEncoderFactory.configureStagingService("http://staging.test", DUMMY_KEY);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(staleKeyEnvironment())
                .build());

        launcher.start();

        assertEquals("absent", keyStateOfFirstChild());
    }

    @Test
    @DisplayName("a start that fails after the spawn kills the child")
    void aStartThatFailsAfterSpawnKillsTheChild() throws Exception {
        AtomicReference<Process> registered = new AtomicReference<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        launcher.setSubprocessRegistry(new SubprocessRegistry() {
            @Override
            public void register(String id, Process process, String type) {
                registered.set(process);
                throw new IllegalStateException("registry unavailable (test)");
            }
        });

        assertThrows(IllegalStateException.class, launcher::start);

        Process child = registered.get();
        assertNotNull(child);
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "nothing reads the abandoned child's pipes");
        assertFalse(launcher.isRunning());
        assertFalse(launcher.claimCrash(child), "no detector may restart the abandoned child");
    }

    @Test
    @DisplayName("a child claimed during start() is left to its crash handler, whose start() spawns a replacement")
    void aChildClaimedDuringStartIsNotMarkedRunning() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicBoolean claimedFirst = new AtomicBoolean();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        launcher.setSubprocessRegistry(new SubprocessRegistry() {
            @Override
            public void register(String id, Process process, String type) {
                // The watchdog can target a child as soon as start() publishes it.
                if (registrations.getAndIncrement() == 0) {
                    claimedFirst.set(launcher.claimCrash(process));
                }
            }
        });

        launcher.start();

        assertTrue(claimedFirst.get());
        assertFalse(launcher.isRunning(), "the crash handler that claimed the child owns the running flag");

        // That handler's restart must spawn a replacement, not return "already running".
        launcher.start();
        awaitEvents(events -> starts(events).size() >= 2);
        assertTrue(launcher.isRunning());
    }

    @Test
    @DisplayName("a DEVICE_ERROR exit seen first as the end of output is restarted, and the lane is marked down once restarts run out")
    void aDeviceErrorExitIsRestartedUntilRestartsRunOut() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(exitOnLoad(78))
                .build());
        launcher.setRestartConfig(1, 10, 1.0);
        launcher.setSubprocessRegistry(registry);
        launcher.start();

        // The child's CUDA context died with it; a new child gets a new one
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("Exit code: 78"), failure::toString);
        awaitEvents(events -> starts(events).size() >= 2);
        await(launcher::isRunning, "the restarted child");
        assertFalse(launcher.isLaneUnavailable());

        // The new child dies the same way, and the one restart allowed is spent
        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        await(launcher::isLaneUnavailable, "the lane to be marked down");
        Thread.sleep(500); // room for a restart that must not happen
        List<String> events = events();

        assertEquals(2, starts(events).size(), events::toString);
        assertEquals(2, crashes.get());
        assertFalse(launcher.isRunning());
        assertTrue(registry.get("embedding").isEmpty(), "the dead child's registry entry");
        assertTrue(launcher.healthMonitor.isShutdown(), "the dead child's health monitor");

        // A request to the dead lane fails at once, with the crash that ended it
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(5, TimeUnit.SECONDS));
        String message = refused.getCause().getMessage();
        assertTrue(message.contains("lane unavailable") && message.contains("Exit code: 78"), message);
        assertEquals(2, loads(events()).size(), "the refused request reached no child");

        // A start brings the lane back
        launcher.start();
        assertFalse(launcher.isLaneUnavailable());
        assertTrue(launcher.isRunning());
    }

    @Test
    @DisplayName("a restart that cannot start a child marks the lane down")
    void aRestartThatCannotStartAChildMarksTheLaneDown() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(exitOnLoad(78))
                .build());
        launcher.setSubprocessRegistry(new SubprocessRegistry() {
            @Override
            public void register(String id, Process process, String type) {
                // The first child registers; the restart's start() fails after its spawn
                if (registrations.getAndIncrement() > 0) {
                    throw new IllegalStateException("registry unavailable (test)");
                }
            }
        });
        launcher.start();

        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        await(launcher::isLaneUnavailable, "the lane to be marked down");

        assertEquals(2, registrations.get(), "the restart spawned a child");
        assertEquals(1, launcher.getRestartAttempts());
        assertFalse(launcher.isRunning());
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(5, TimeUnit.SECONDS));
        assertTrue(refused.getCause().getMessage().contains("lane unavailable"), refused::toString);
    }

    @Test
    @DisplayName("a crash that is not restarted retires the child's health monitor and registry entry, and marks the lane down")
    void aCrashThatIsNotRestartedRetiresTheChild() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        DecliningRestartPolicy policy = new DecliningRestartPolicy();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(exitOnLoad(1))
                .build());
        policy.watched = launcher;
        launcher.setSubprocessRegistry(registry);
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        assertTrue(registry.get("embedding").isPresent());

        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(policy.exhausted.await(30, TimeUnit.SECONDS), "the policy never heard that restarts are over");
        await(launcher::isLaneUnavailable, "the lane to be marked down");
        List<String> events = events();

        assertEquals(1, starts(events).size(), events::toString);
        assertEquals(1, crashes.get());
        // The model's policy pauses restarts when it hears they are over, before an owner that
        // finds the lane down goes looking for a new one
        assertEquals(Boolean.FALSE, policy.laneDownWhenExhausted,
                "the lane was marked down before the policy could pause restarts");
        assertFalse(launcher.isRunning());
        assertTrue(registry.get("embedding").isEmpty(), "the dead child's registry entry");
        assertTrue(launcher.healthMonitor.isShutdown(), "the dead child's health monitor");
    }

    @Test
    @DisplayName("an accepted stale restart leaves a callback-created healthy replacement alone")
    void anAcceptedStaleRestartLeavesItsReplacementAlone() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        RecordingRestartPolicy policy = new RecordingRestartPolicy();
        AtomicReference<EmbeddingSubprocessLauncher> self = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .crashCallback(e -> {
                    crashes.incrementAndGet();
                    try {
                        self.get().start();
                        if (!self.get().loadModel("replacement", 3, 5, 7, Map.of())
                                .get(30, TimeUnit.SECONDS).success()) {
                            throw new IllegalStateException("replacement load failed");
                        }
                        // Only an unwanted extra reload fails: the replacement is already healthy.
                        Files.createFile(fakeDir.resolve("reject-load"));
                    } catch (Exception failure) {
                        callbackFailure.set(failure);
                    }
                })
                .build());
        self.set(launcher);
        launcher.setSubprocessRegistry(registry);
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        Process first = launcher.process;

        launcher.handleSubprocessCrash(first);

        assertNull(callbackFailure.get(), "replacement creation failed in the crash callback");
        List<String> events = events();
        assertEquals(2, starts(events).size(), events::toString);
        assertEquals(2, loads(events).size(), "the stale handler reloaded a healthy replacement: " + events);
        Process replacement = launcher.process;
        assertNotSame(first, replacement);
        assertFalse(first.isAlive());
        assertTrue(replacement.isAlive(), "the stale handler killed a healthy replacement");
        assertTrue(launcher.isRunning());
        assertTrue(launcher.isModelLoaded());
        assertFalse(launcher.isLaneUnavailable());
        assertEquals(replacement.pid(), registry.get("embedding").orElseThrow().pid());
        assertFalse(launcher.healthMonitor.isShutdown());
        assertEquals(1, crashes.get());
        assertEquals(0, policy.successes.get(), "this handler did not recover the replacement");
        assertEquals(0, policy.exhausted.get(), "the replacement's lane is still healthy");
    }

    @Test
    @DisplayName("a crash handler that declines after a replacement started leaves the replacement alone and does not report restarts exhausted")
    void retiringACrashedChildLeavesItsReplacementAlone() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        DecliningRestartPolicy policy = new DecliningRestartPolicy();
        AtomicReference<EmbeddingSubprocessLauncher> self = new AtomicReference<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(exitOnLoad(1))
                // Another caller starts a replacement between the crash and the retire.
                .crashCallback(e -> {
                    crashes.incrementAndGet();
                    try {
                        self.get().start();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                })
                .build());
        self.set(launcher);
        launcher.setSubprocessRegistry(registry);
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        Process first = launcher.process;

        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(policy.asked.await(30, TimeUnit.SECONDS), "the crash handler never asked the policy");
        policy.awaitHandlerReturned();
        List<String> events = awaitEvents(e -> starts(e).size() >= 2);

        assertEquals(2, starts(events).size(), events::toString);
        assertEquals(1, crashes.get());
        Process replacement = launcher.process;
        assertNotSame(first, replacement);
        assertTrue(registry.get("embedding").isPresent(), "the replacement's registry entry");
        assertEquals(replacement.pid(), registry.get("embedding").orElseThrow().pid(),
                "the replacement's registry entry");
        assertFalse(launcher.healthMonitor.isShutdown(), "the replacement's health monitor");
        assertTrue(launcher.isRunning());
        assertFalse(launcher.isLaneUnavailable(), "the replacement serves the lane");
        // Restarts are not over for a lane its replacement serves, and the model's policy pauses
        // them when told so
        assertEquals(1, policy.exhausted.getCount(),
                "the policy heard that restarts are over for a lane its replacement serves");
    }

    @Test
    @DisplayName("a crash handler that declines after stop() leaves the next launcher's registry entry alone, and neither reports restarts exhausted nor marks the stopped lane down")
    void aCrashDeclinedAfterStopLeavesTheNextLaunchersEntryAlone() throws Exception {
        SubprocessRegistry registry = new SubprocessRegistry();
        DecliningRestartPolicy policy = new DecliningRestartPolicy(true);
        EmbeddingSubprocessLauncher old = track(fakeChildBuilder()
                .environment(exitOnLoad(1))
                .build());
        old.setSubprocessRegistry(registry);
        old.setRestartPolicyCallback(policy);
        old.start();
        assertThrows(ExecutionException.class,
                () -> old.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(policy.asked.await(30, TimeUnit.SECONDS), "the crash handler never asked the policy");

        // The model replaces a launcher while its crash handler is still deciding: it stops the old
        // launcher and starts a new one, which registers under the same id.
        old.stop();
        EmbeddingSubprocessLauncher next = track(fakeChildBuilder().build());
        next.setSubprocessRegistry(registry);
        next.start();
        policy.answer.countDown();
        policy.awaitHandlerReturned();

        assertTrue(registry.get("embedding").isPresent(), "the next launcher's registry entry");
        assertEquals(next.process.pid(), registry.get("embedding").orElseThrow().pid());
        // The model's policy pauses restarts when told they are over, and only a manual resume
        // lifts that: a lane stopped for a preemption would stay down after the preemption ends.
        assertEquals(1, policy.exhausted.getCount(),
                "the policy heard that restarts are over for a lane stop() took down");
        // Its child is still the one that crashed and nothing runs, but stop() took the lane
        // down on purpose: an owner must not read it as died and not restarted.
        assertFalse(old.isLaneUnavailable(), "the stopped lane was marked down");
    }

    @Test
    @DisplayName("a lane stopped while its crash handler reports restarts exhausted is not marked down")
    void aLaneStoppedDuringTheExhaustedNoticeIsNotMarkedDown() throws Exception {
        DecliningRestartPolicy policy = new DecliningRestartPolicy(false, true);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(exitOnLoad(1))
                .build());
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(policy.exhausted.await(30, TimeUnit.SECONDS), "the policy never heard that restarts are over");

        // The handler reports without the launcher's lock, and the model's policy publishes an event
        // there: an owner can stop the lane before the handler goes on to mark it down.
        launcher.stop();
        policy.noticeReleased.countDown();
        policy.awaitHandlerReturned();

        assertFalse(launcher.isLaneUnavailable(), "the stopped lane was marked down");
    }

    @Test
    @DisplayName("a late restarts-exhausted notice does not mark down a lane whose newer child is being restarted")
    void aLateExhaustedNoticeLeavesARestartingLaneUp() throws Exception {
        DecliningRestartPolicy policy = new DecliningRestartPolicy(false, true);
        // Longer than the checks below take: the newer child stays down, waiting out its backoff
        policy.laterBackoffMs = TimeUnit.SECONDS.toMillis(5);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(exitOnLoad(1))
                .build());
        launcher.setRestartPolicyCallback(policy);
        launcher.start();
        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(policy.exhausted.await(30, TimeUnit.SECONDS), "the policy never heard that restarts are over");

        // While the first child's handler reports, a caller starts a newer child. That one crashes
        // too, and its handler restarts it after the backoff.
        launcher.start();
        assertThrows(ExecutionException.class,
                () -> launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS));
        assertTrue(policy.restarting.await(30, TimeUnit.SECONDS), "the newer child's crash was not restarted");
        assertFalse(launcher.isRunning());

        policy.noticeReleased.countDown();
        policy.awaitHandlerReturned();

        assertFalse(launcher.isLaneUnavailable(),
                "the first child's handler marked down a lane that is being restarted");
    }

    @Test
    @DisplayName("a response behind native output on the same line of the child's fd 1 is delivered, and the native text is logged")
    void aResponseBehindNativeOutputOnTheSameLineIsDelivered() throws Exception {
        List<String> stdoutLines = new CopyOnWriteArrayList<>();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                // The protocol pipe is the child's fd 1, as for a child the launcher can't wrap
                .environment(Map.of("FAKE_EMBED_NATIVE_TEXT", "[native] no newline ", "FAKE_EMBED_UNWRAPPED", "1"))
                .logCallback(collect(stdoutLines, stderrLines))
                .build());
        launcher.start();

        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        assertEquals(List.of("[native] no newline "), stdoutLines);
    }

    @Test
    @DisplayName("native output goes to stderr, and the response arrives on a pipe of its own")
    void nativeOutputGoesToStderrAndTheResponseToItsOwnPipe() throws Exception {
        List<String> stdoutLines = new CopyOnWriteArrayList<>();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_NATIVE_TEXT", "[native] own line\n"))
                .logCallback(collect(stdoutLines, stderrLines))
                .build());
        launcher.start();

        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        await(() -> stderrLines.contains("[native] own line"), "the native text on stderr");
        assertTrue(stderrLines.stream().anyMatch(line -> line.startsWith(CHANNEL_OPEN_NOTICE)), stderrLines::toString);
        assertEquals(List.of(), stdoutLines, "nothing but responses reaches the protocol pipe");
    }

    @Test
    @DisplayName("a child that writes its responses to fd 1 has them read from stderr, behind native output on the same line")
    void aChildThatWritesItsResponsesToFd1IsReadFromStderr() throws Exception {
        List<String> stdoutLines = new CopyOnWriteArrayList<>();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_NATIVE_TEXT", "[native] no newline ", "FAKE_EMBED_LEGACY", "1"))
                .logCallback(collect(stdoutLines, stderrLines))
                .build());
        launcher.start();

        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        // stop() joins the stderr reader, so every line the child wrote has been read
        launcher.stop();
        assertTrue(stderrLines.contains("[native] no newline "), stderrLines::toString);
        assertTrue(stderrLines.stream().noneMatch(line -> line.contains(EmbeddingSubprocessMessage.MESSAGE_PREFIX)),
                () -> "the response was logged as stderr text: " + stderrLines);
        assertEquals(List.of(), stdoutLines);
    }

    @Test
    @DisplayName("a response that starts its line on stderr is read, and nothing in front of it is logged")
    void aResponseThatStartsItsStderrLineLogsNoText() throws Exception {
        List<String> stdoutLines = new CopyOnWriteArrayList<>();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_LEGACY", "1"))
                .logCallback(collect(stdoutLines, stderrLines))
                .build());
        launcher.start();

        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        launcher.stop();
        assertTrue(stderrLines.stream().noneMatch(String::isEmpty), () -> "an empty line was logged: " + stderrLines);
    }

    @Test
    @DisplayName("a stderr line that quotes a response is logged, not taken for the response, once the child uses the channel")
    void aResponseQuotedOnStderrIsNotDispatched() throws Exception {
        List<String> stdoutLines = new CopyOnWriteArrayList<>();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_QUOTE_ON_STDERR", "1"))
                .logCallback(collect(stdoutLines, stderrLines))
                .build());
        launcher.start();

        // The quote, a failed response to the same request, reaches the launcher first
        assertTrue(launcher.loadModel("m", 7, 11, 13, Map.of()).get(30, TimeUnit.SECONDS).success());
        await(() -> stderrLines.stream().anyMatch(line -> line.startsWith("sending " + EmbeddingSubprocessMessage.MESSAGE_PREFIX)),
                "the quoting line in the stderr log");
    }

    @Test
    @DisplayName("a detached load response is delivered after stop without restoring loaded state")
    void aDetachedLoadResponseCannotRestoreStateAfterStop() throws Exception {
        Files.createFile(fakeDir.resolve("ignore-load"));
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        launcher.start();
        CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> load =
                launcher.loadModel("old", 7, 11, 13, Map.of());
        awaitEvents(e -> loads(e).size() == 1);
        Map.Entry<String, CompletableFuture<EmbeddingSubprocessMessage>> detached = detachLoadResponse(launcher);

        launcher.stop();
        detached.getValue().complete(new EmbeddingSubprocessMessage.LoadModelResponse(
                detached.getKey(), true, "old", 768, "old-encoder", "FAKE", 1L, null));

        assertTrue(load.get(5, TimeUnit.SECONDS).success(), "deliver the child's valid response to its caller");
        assertFalse(launcher.isModelLoaded(), "a stopped child cannot restore readiness");
        assertNull(launcher.getCurrentModelId(), "a detached response must not publish stale metadata");
    }

    @Test
    @DisplayName("a detached load response cannot overwrite a replacement child's model or reload request")
    void aDetachedLoadResponseCannotOverwriteItsReplacement() throws Exception {
        Files.createFile(fakeDir.resolve("ignore-load"));
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder().build());
        launcher.start();
        CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> oldLoad =
                launcher.loadModel("old", 7, 11, 13, Map.of());
        awaitEvents(e -> loads(e).size() == 1);
        Map.Entry<String, CompletableFuture<EmbeddingSubprocessMessage>> detached = detachLoadResponse(launcher);

        Files.delete(fakeDir.resolve("ignore-load"));
        launcher.requestRestart("test: replace the child before the detached response arrives");
        awaitEvents(e -> starts(e).size() >= 2);
        await(launcher::isRunning, "the replacement child becoming ready");
        assertTrue(launcher.loadModel("replacement", 3, 5, 7, Map.of()).get(30, TimeUnit.SECONDS).success());
        detached.getValue().complete(new EmbeddingSubprocessMessage.LoadModelResponse(
                detached.getKey(), true, "old", 768, "old-encoder", "FAKE", 1L, null));
        assertTrue(oldLoad.get(5, TimeUnit.SECONDS).success());
        assertEquals("replacement", launcher.getCurrentModelId());
        assertEquals(384, launcher.getCurrentDimensions());
        assertTrue(launcher.isModelLoaded());

        launcher.requestRestart("test: verify the current child's reload request");
        List<String> events = awaitEvents(e -> loads(e).size() >= 3);
        assertEquals(List.of("load old 7 11 13 {}", "load replacement 3 5 7 {}",
                        "load replacement 3 5 7 {}"), loads(events));
    }

    /** The reader removes a future before completing it; stop cannot fail that detached future. */
    private static Map.Entry<String, CompletableFuture<EmbeddingSubprocessMessage>> detachLoadResponse(
            EmbeddingSubprocessLauncher launcher) {
        assertEquals(1, launcher.pendingRequests.size());
        Map.Entry<String, CompletableFuture<EmbeddingSubprocessMessage>> detached =
                launcher.pendingRequests.entrySet().iterator().next();
        assertSame(detached.getValue(), launcher.pendingRequests.remove(detached.getKey()));
        return detached;
    }

    @Test
    @DisplayName("stop() delivers the response a child wrote just before its kill")
    void stopDeliversTheResponseAChildWroteBeforeItsKill() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_HELD", "stdout"))
                .logCallback(holdingOn(held))
                .build());
        CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> load = loadIntoHeldChild(launcher, held);
        Process child = launcher.process;

        // The child reads no more requests, so stop() kills it once its graceful wait ends, then
        // retires the health monitor
        FutureTask<Void> stop = inBackground("stop", launcher::stop);
        await(launcher.healthMonitor::isShutdown, "stop() past its kill");
        releaseReaderAfterDeathOf(child);

        assertTrue(load.get(30, TimeUnit.SECONDS).success());
        stop.get(30, TimeUnit.SECONDS);
        assertFalse(launcher.isModelLoaded(), "draining the response must not restore stopped readiness");
    }

    @Test
    @DisplayName("a watchdog restart delivers the response the child wrote before its kill, and the replacement reloads that model")
    void aWatchdogRestartDeliversTheResponseTheChildWroteFirst() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_HELD", "stdout"))
                .logCallback(holdingOn(held))
                .build());
        CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> load = loadIntoHeldChild(launcher, held);
        Process first = launcher.process;

        launcher.requestRestart("test: over its memory limit");
        releaseReaderAfterDeathOf(first);

        assertTrue(load.get(30, TimeUnit.SECONDS).success());
        List<String> events = awaitEvents(e -> starts(e).size() >= 2 && loads(e).size() >= 2);
        assertEquals(1, crashes.get());
        assertEquals(List.of("load m 7 11 13 {k=v}", "load m 7 11 13 {k=v}"), loads(events),
                "the replacement reloads the model the response confirmed");
    }

    @Test
    @DisplayName("a hung child's last error lines reach the crash reason")
    void aHungChildsLastErrorLinesReachTheCrashReason() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        EmbeddingSubprocessLauncher launcher = track(fakeChildBuilder()
                .environment(Map.of("FAKE_EMBED_HELD", "stderr"))
                .heartbeatTimeoutMs(300)
                .logCallback(holdingOn(held))
                .build());
        CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> load = loadIntoHeldChild(launcher, held);
        Process first = launcher.process;
        Thread.sleep(400);

        // The fake child sends no heartbeats: the check finds it hung and kills it
        FutureTask<Void> check = inBackground("health-check", launcher::checkHealth);
        releaseReaderAfterDeathOf(first);
        check.get(30, TimeUnit.SECONDS);

        assertEquals(1, crashes.get());
        String reason = launcher.getLastCrashReason();
        assertTrue(reason.contains("unresponsive"), reason);
        assertTrue(reason.contains(FakeEmbeddingChild.LAST_WORDS), reason);
        assertThrows(ExecutionException.class, () -> load.get(30, TimeUnit.SECONDS));
    }

    private EmbeddingSubprocessLauncher.Builder fakeChildBuilder() {
        return EmbeddingSubprocessLauncher.builder()
                .launchMode(EmbeddingSubprocessLauncher.LaunchMode.NATIVE_EXECUTABLE)
                .nativeExecutablePath(script.toString())
                .classpath(List.of("unused-in-native-mode"))
                .workingDirectory(tempDir)
                .environment(Map.of("FAKE_EMBED_DIR", fakeDir.toString()))
                .localModelOnly(true)
                .loadModelTimeoutMs(30_000)
                .crashCallback(e -> crashes.incrementAndGet());
    }

    /**
     * A child that closes its protocol stream and exits when asked to load. Only a protocol pipe on
     * fd 1 is released by the close, so the child gets the layout of an unwrapped one.
     */
    private static Map<String, String> exitOnLoad(int exitCode) {
        return Map.of("FAKE_EMBED_EXIT_ON_LOAD", Integer.toString(exitCode), "FAKE_EMBED_UNWRAPPED", "1");
    }

    private static Consumer<EmbeddingSubprocessMessage.Log> collect(
            List<String> stdoutLines, List<String> stderrLines) {
        return log -> {
            if ("stdout".equals(log.source())) {
                stdoutLines.add(log.message());
            } else if ("stderr".equals(log.source())) {
                stderrLines.add(log.message());
            }
        };
    }

    /**
     * Holds the reader that delivers {@link FakeEmbeddingChild#HOLD_MARK} until the test releases it
     * ({@link #readerReleased}), so the child's last output waits in the pipe behind it.
     */
    private Consumer<EmbeddingSubprocessMessage.Log> holdingOn(CountDownLatch held) {
        return log -> {
            if (log.message() != null && log.message().contains(FakeEmbeddingChild.HOLD_MARK)) {
                held.countDown();
                try {
                    readerReleased.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private EmbeddingSubprocessLauncher track(EmbeddingSubprocessLauncher launcher) {
        launcher.setRestartConfig(3, 10, 1.0);
        launchers.add(launcher);
        return launcher;
    }

    /** An inherited key that is not the one this process resolved, and the key the child should see. */
    private static Map<String, String> staleKeyEnvironment() {
        return Map.of(
                "KOMPILE_STAGING_API_KEY", "stale-dummy",
                "FAKE_EMBED_EXPECTED_KEY", DUMMY_KEY);
    }

    private String keyStateOfFirstChild() throws Exception {
        String start = starts(awaitEvents(events -> !starts(events).isEmpty())).get(0);
        return start.substring(start.indexOf("key=") + "key=".length());
    }

    private List<String> events() throws IOException {
        Path events = fakeDir.resolve("events");
        return Files.exists(events) ? Files.readAllLines(events, StandardCharsets.UTF_8) : List.of();
    }

    private List<String> awaitEvents(Predicate<List<String>> done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        List<String> events = events();
        while (!done.test(events)) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for the fake child; events so far: " + events);
            }
            Thread.sleep(50);
            events = events();
        }
        return events;
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

    /**
     * Start a held child and send it a load request. Returns once the child has written its last
     * output behind the held reader: the response, or its last error line.
     */
    private CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> loadIntoHeldChild(
            EmbeddingSubprocessLauncher launcher, CountDownLatch held) throws Exception {
        launcher.start();
        CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> load =
                launcher.loadModel("m", 7, 11, 13, Map.of("k", "v"));
        assertTrue(held.await(30, TimeUnit.SECONDS), "the reader never reached the child's mark");
        Files.createFile(fakeDir.resolve("go"));
        await(() -> Files.exists(fakeDir.resolve("written")), "the child's last output");
        return load;
    }

    /**
     * Release the held reader once {@code child} is dead, and a little later still: time for
     * anything that races the reader to lose the child's last output first, such as a close of the
     * pipes or a request failed before the reader reached its response.
     */
    private void releaseReaderAfterDeathOf(Process child) throws InterruptedException {
        await(() -> !child.isAlive(), "the child's death");
        Thread.sleep(200);
        readerReleased.countDown();
    }

    /** Run {@code action} on a daemon thread; it may block until the test releases the reader. */
    private static FutureTask<Void> inBackground(String name, Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static List<String> starts(List<String> events) {
        return events.stream().filter(event -> event.startsWith("start ")).toList();
    }

    private static List<String> loads(List<String> events) {
        return events.stream().filter(event -> event.startsWith("load ")).toList();
    }

    /**
     * Kill any child the launchers no longer track: an abandoned start, a replaced child. Only a
     * process whose command line names this test's argument file is touched.
     */
    private void killLeftoverChildren() throws Exception {
        Path pids = fakeDir.resolve("pids");
        if (!Files.exists(pids)) {
            return;
        }
        for (String line : Files.readAllLines(pids, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            ProcessHandle handle = ProcessHandle.of(Long.parseLong(line.trim())).orElse(null);
            if (handle == null || !handle.isAlive()
                    || !handle.info().commandLine().map(c -> c.contains(argFile.toString())).orElse(false)) {
                continue;
            }
            handle.destroyForcibly();
            handle.onExit().get(10, TimeUnit.SECONDS);
        }
    }

    private static String quoteForArgFile(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Declines the first crash's restart, as the model's policy does for a paused lane or a spent
     * budget. A held policy gives that answer only once the test counts down {@link #answer}, and one
     * that holds its notice returns from {@link #onRestartExhausted} only once the test counts down
     * {@link #noticeReleased}. Later crashes are declined too, unless {@link #laterBackoffMs} is set:
     * they are then restarted after that backoff.
     */
    private static final class DecliningRestartPolicy implements EmbeddingSubprocessLauncher.RestartPolicyCallback {
        final CountDownLatch asked = new CountDownLatch(1);
        final CountDownLatch answer;
        final CountDownLatch exhausted = new CountDownLatch(1);
        final CountDownLatch noticeReleased;
        /** Counted down when a later crash is restarted, just before its backoff. */
        final CountDownLatch restarting = new CountDownLatch(1);
        /** The backoff before a later crash is restarted; negative to decline it. */
        volatile long laterBackoffMs = -1;
        private final AtomicInteger calls = new AtomicInteger();
        /** The launcher whose lane state {@link #onRestartExhausted} records, when set. */
        volatile EmbeddingSubprocessLauncher watched;
        /** Whether the lane was already marked down when the policy heard that restarts are over. */
        volatile Boolean laneDownWhenExhausted;
        /** The thread handling the first crash, once it asked the policy. */
        volatile Thread handler;

        DecliningRestartPolicy() {
            this(false, false);
        }

        DecliningRestartPolicy(boolean held) {
            this(held, false);
        }

        DecliningRestartPolicy(boolean held, boolean holdsNotice) {
            answer = new CountDownLatch(held ? 1 : 0);
            noticeReleased = new CountDownLatch(holdsNotice ? 1 : 0);
        }

        @Override
        public EmbeddingSubprocessLauncher.RestartConfiguration shouldRestart(
                String taskId, int exitCode, String crashReason, int attemptNumber) {
            if (calls.incrementAndGet() > 1) {
                long backoffMs = laterBackoffMs;
                return backoffMs < 0 ? null
                        : new EmbeddingSubprocessLauncher.RestartConfiguration(backoffMs, 0, 0, 0, "test restart");
            }
            handler = Thread.currentThread();
            asked.countDown();
            try {
                answer.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }

        @Override
        public void onRestartAttempt(String taskId, String fileName, int attemptNumber, int maxAttempts,
                                     String reason, EmbeddingSubprocessLauncher.RestartConfiguration config) {
            restarting.countDown();
        }

        @Override
        public void onRestartSuccess(String taskId, int attemptNumber) {
        }

        @Override
        public void onRestartExhausted(String taskId, int totalAttempts, String lastReason) {
            EmbeddingSubprocessLauncher launcher = watched;
            if (launcher != null) {
                laneDownWhenExhausted = launcher.isLaneUnavailable();
            }
            exhausted.countDown();
            try {
                noticeReleased.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Wait for the first crash's handler to finish: it still marks the lane after the policy
         * hears that restarts are over. The handler runs on a reader or health-monitor thread,
         * which ends once the handler returns.
         */
        void awaitHandlerReturned() throws InterruptedException {
            Thread thread = handler;
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(thread.isAlive(), "the crash handler never returned");
        }
    }

    /** A process that never runs: claimCrash compares children by identity only. */
    private static final class InertProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
