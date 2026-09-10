/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionRestartLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void restartSpawnsResumeForSameTranscriptAndWaitsOnCurrentPid() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Path> directory = new AtomicReference<>();
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("/opt/Kompile Current/bin/kompile"),
                (args, cwd) -> {
                    command.set(args);
                    directory.set(cwd);
                    return 4321L;
                },
                () -> 1234L);

        SessionRestartLauncher.LaunchResult result = launcher.restart("session-abc", tempDir);

        assertTrue(result.started());
        assertEquals(4321L, result.processId());
        assertNull(result.error());
        assertEquals(List.of(
                "/opt/Kompile Current/bin/kompile",
                SessionRestartLauncher.PARENT_PID_ARGUMENT + "1234",
                "resume", "--session-id", "session-abc"), command.get());
        assertEquals(tempDir.toAbsolutePath().normalize(), directory.get());
    }

    @Test
    void managedRestartReentersTheSameManagedAgentUi() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("kompile"),
                (args, cwd) -> {
                    command.set(args);
                    return 4321L;
                },
                () -> 1234L);

        SessionRestartLauncher.LaunchResult result =
                launcher.restartManaged("session-abc", tempDir, "codex");

        assertTrue(result.started());
        assertEquals(List.of(
                "kompile",
                SessionRestartLauncher.PARENT_PID_ARGUMENT + "1234",
                "chat", "--resume", "session-abc",
                "--mode", "passthrough",
                "--agent", "codex",
                "--internal-managed-resume"), command.get());
    }

    @Test
    void restartAllUsesEachSessionOwnerAndPreservesManagedMode() {
        List<List<String>> commands = new ArrayList<>();
        List<Long> terminated = new ArrayList<>();
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("kompile"),
                (args, cwd) -> {
                    commands.add(List.copyOf(args));
                    return 5000L + commands.size();
                },
                () -> 100L,
                entry -> true,
                entry -> {
                    terminated.add(entry.getPid());
                    return true;
                });

        SessionRestartLauncher.RestartAllResult result = launcher.restartAll(List.of(
                activeSession("standard-session", 100L, "local", "kompile"),
                activeSession("managed-session", 200L, "passthrough", "codex")));

        assertEquals(2, result.activeSessions());
        assertEquals(2, result.replacementsStarted());
        assertEquals(1, result.sessionsTerminated());
        assertTrue(result.currentSessionRestarted());
        assertTrue(result.failures().isEmpty());
        assertEquals(List.of(
                "kompile", SessionRestartLauncher.PARENT_PID_ARGUMENT + "100",
                "resume", "--session-id", "standard-session"), commands.get(0));
        assertEquals(List.of(
                "kompile", SessionRestartLauncher.PARENT_PID_ARGUMENT + "200",
                "chat", "--resume", "managed-session",
                "--mode", "passthrough", "--agent", "codex",
                "--internal-managed-resume"), commands.get(1));
        assertEquals(List.of(200L), terminated,
                "the invoking owner exits through normal REPL cleanup");
    }

    @Test
    void restartAllNeverStopsAnOwnerWhoseReplacementFailed() {
        List<Long> terminated = new ArrayList<>();
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("kompile"),
                (args, cwd) -> {
                    if (args.contains("failed-session")) {
                        throw new IOException("terminal unavailable");
                    }
                    return 6000L;
                },
                () -> 100L,
                entry -> true,
                entry -> {
                    terminated.add(entry.getPid());
                    return true;
                });

        SessionRestartLauncher.RestartAllResult result = launcher.restartAll(List.of(
                activeSession("current-session", 100L, "local", "kompile"),
                activeSession("restarted-session", 200L, "local", "kompile"),
                activeSession("failed-session", 300L, "local", "kompile")));

        assertEquals(3, result.activeSessions());
        assertEquals(2, result.replacementsStarted());
        assertEquals(List.of(200L), terminated);
        assertTrue(result.currentSessionRestarted());
        assertEquals(1, result.failures().size());
        assertEquals("failed-session", result.failures().get(0).sessionId());
        assertEquals("terminal unavailable", result.failures().get(0).error());
    }

    @Test
    void resetAllRejectsAPidReusedAfterSessionRegistration() {
        Instant registration = Instant.parse("2026-09-04T12:00:00Z");

        assertTrue(SessionRestartLauncher.processStartedNoLaterThanRegistration(
                registration.minusSeconds(30), registration.toString()));
        assertFalse(SessionRestartLauncher.processStartedNoLaterThanRegistration(
                registration.plusSeconds(30), registration.toString()),
                "a later process cannot own an older session registry row");
        assertFalse(SessionRestartLauncher.processStartedNoLaterThanRegistration(
                registration.minusSeconds(30), "legacy-unknown"),
                "reset-all must not terminate a process when ownership cannot be verified");
    }

    @Test
    void restartAllDoesNotBorrowTheInvokingDirectoryForIncompleteRows() {
        AtomicBoolean spawned = new AtomicBoolean(false);
        AtomicBoolean terminated = new AtomicBoolean(false);
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("kompile"),
                (args, cwd) -> {
                    spawned.set(true);
                    return 7000L;
                },
                () -> 100L,
                entry -> true,
                entry -> {
                    terminated.set(true);
                    return true;
                });
        SessionEntry incomplete = activeSession(
                "incomplete-session", 200L, "local", "kompile");
        incomplete.setProjectDirectory("");

        SessionRestartLauncher.RestartAllResult result =
                launcher.restartAll(List.of(incomplete));

        assertEquals(1, result.activeSessions());
        assertEquals(0, result.replacementsStarted());
        assertFalse(spawned.get());
        assertFalse(terminated.get());
        assertEquals("The session has no working directory to restore.",
                result.failures().get(0).error());
    }

    @Test
    void restartFailureLeavesCurrentSessionRunning() {
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("kompile"),
                (args, cwd) -> { throw new IOException("cannot spawn replacement"); },
                () -> 1234L);

        SessionRestartLauncher.LaunchResult result = launcher.restart("session-abc", tempDir);

        assertFalse(result.started());
        assertEquals(-1L, result.processId());
        assertEquals("cannot spawn replacement", result.error());
    }

    @Test
    void invalidSessionOrWorkingDirectoryNeverSpawns() {
        AtomicBoolean spawned = new AtomicBoolean(false);
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("kompile"),
                (args, cwd) -> {
                    spawned.set(true);
                    return 1L;
                },
                () -> 1234L);

        assertFalse(launcher.restart(null, tempDir).started());
        assertFalse(launcher.restart(" ", tempDir).started());
        assertFalse(launcher.restartManaged(null, tempDir, "codex").started());
        assertFalse(launcher.restartManaged("session-abc", tempDir, null).started());
        assertFalse(launcher.restart("session-abc", tempDir.resolve("missing")).started());
        assertFalse(spawned.get());
    }

    @Test
    void childStripsInternalParentArgumentAfterSuccessfulHandoff() throws Exception {
        AtomicLong waitedFor = new AtomicLong(-1L);
        AtomicReference<Duration> timeout = new AtomicReference<>();

        String[] stripped = SessionRestartLauncher.awaitRestartParentAndStrip(
                new String[]{
                        SessionRestartLauncher.PARENT_PID_ARGUMENT + "99",
                        "resume", "--session-id", "session-abc"
                },
                (pid, wait) -> {
                    waitedFor.set(pid);
                    timeout.set(wait);
                    return true;
                },
                100L);

        assertArrayEquals(new String[]{"resume", "--session-id", "session-abc"}, stripped);
        assertEquals(99L, waitedFor.get());
        assertEquals(SessionRestartLauncher.PARENT_EXIT_TIMEOUT, timeout.get());
    }

    @Test
    void childRefusesConcurrentResumeWhenParentDoesNotExit() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SessionRestartLauncher.awaitRestartParentAndStrip(
                        new String[]{SessionRestartLauncher.PARENT_PID_ARGUMENT + "99", "resume"},
                        (pid, timeout) -> false,
                        100L));

        assertTrue(error.getMessage().contains("refusing to resume the same session concurrently"));
    }

    @Test
    void childRejectsInvalidDuplicateOrSelfParentPid() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionRestartLauncher.awaitRestartParentAndStrip(
                        new String[]{SessionRestartLauncher.PARENT_PID_ARGUMENT + "bad"},
                        (pid, timeout) -> true,
                        100L));
        assertThrows(IllegalArgumentException.class,
                () -> SessionRestartLauncher.awaitRestartParentAndStrip(
                        new String[]{SessionRestartLauncher.PARENT_PID_ARGUMENT + "100"},
                        (pid, timeout) -> true,
                        100L));
        assertThrows(IllegalArgumentException.class,
                () -> SessionRestartLauncher.awaitRestartParentAndStrip(
                        new String[]{
                                SessionRestartLauncher.PARENT_PID_ARGUMENT + "98",
                                SessionRestartLauncher.PARENT_PID_ARGUMENT + "99"
                        },
                        (pid, timeout) -> true,
                        100L));
    }

    @Test
    void ordinaryStartupArgumentsAreUnchangedAndDoNotWait() throws Exception {
        AtomicBoolean waited = new AtomicBoolean(false);
        String[] original = {"chat", "--mode", "standard"};

        String[] result = SessionRestartLauncher.awaitRestartParentAndStrip(
                original,
                (pid, timeout) -> {
                    waited.set(true);
                    return true;
                },
                100L);

        assertArrayEquals(original, result);
        assertNotSame(original, result);
        assertFalse(waited.get());
    }

    @Test
    void javaJarInvocationPrefixPreservesJvmOptionsAndPathsWithSpaces() {
        List<String> prefix = SessionRestartLauncher.javaInvocationPrefix(
                "/opt/jdk/bin/java",
                new String[]{
                        "-Xmx2g", "-Dkompile.dist.home=/opt/Kompile Current",
                        "-jar", "/opt/Kompile Current/lib/kompile-cli.jar",
                        "chat", "--resume", "old-session"
                });

        assertEquals(List.of(
                "/opt/jdk/bin/java", "-Xmx2g",
                "-Dkompile.dist.home=/opt/Kompile Current",
                "-jar", "/opt/Kompile Current/lib/kompile-cli.jar"), prefix);
    }

    @Test
    void distributionJarUsesSiblingLauncher(@TempDir Path distribution) throws Exception {
        Path lib = Files.createDirectories(distribution.resolve("lib"));
        Path bin = Files.createDirectories(distribution.resolve("bin"));
        Path jar = Files.createFile(lib.resolve("kompile-cli.jar"));
        Path launcher = Files.createFile(bin.resolve("kompile"));
        assertTrue(launcher.toFile().setExecutable(true));

        assertEquals(launcher.toAbsolutePath().normalize(),
                SessionRestartLauncher.distributionLauncherForJar(jar));
    }

    private SessionEntry activeSession(String sessionId, long pid,
                                       String launchMode, String agent) {
        return SessionEntry.builder()
                .kompileSessionId(sessionId)
                .agent(agent)
                .projectDirectory(tempDir.toString())
                .launchMode(launchMode)
                .status("running")
                .pid(pid)
                .build();
    }
}
