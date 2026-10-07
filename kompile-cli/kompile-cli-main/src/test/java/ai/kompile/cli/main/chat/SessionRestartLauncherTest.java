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

    @Test
    void currentTerminalRestartIsDeferredUntilCommandCleanupAndDoesNotWaitOnOwner() throws Exception {
        SessionRestartLauncher.RestartHandoff handoff = new SessionRestartLauncher.RestartHandoff();
        AtomicBoolean cleanedUp = new AtomicBoolean(false);
        AtomicBoolean spawned = new AtomicBoolean(false);
        SessionRestartLauncher launcher = new SessionRestartLauncher(
                () -> List.of("/opt/Kompile Current/bin/kompile"),
                (command, directory) -> handoff.queue(command, directory, 1234L),
                () -> 1234L);

        assertTrue(launcher.restart("session-abc", tempDir).started());
        assertFalse(spawned.get());
        cleanedUp.set(true); // execute() has now returned through its finally blocks
        int exitCode = handoff.finish(0, (command, directory) -> {
            spawned.set(true);
            assertTrue(cleanedUp.get());
            assertEquals(tempDir.toAbsolutePath().normalize(), directory);
            assertEquals(List.of("/opt/Kompile Current/bin/kompile", "resume",
                    "--session-id", "session-abc"), command.subList(0, command.size() - 1));
            assertTrue(command.get(command.size() - 1).startsWith(SessionRestartLauncher.SUPERVISOR_ARGUMENT));
            assertFalse(command.stream().anyMatch(arg -> arg.startsWith(SessionRestartLauncher.PARENT_PID_ARGUMENT)));
            return 37;
        });
        assertTrue(spawned.get());
        assertEquals(37, exitCode, "the foreground supervisor propagates the fresh CLI's exit code");
    }

    @Test
    void repeatedResetsReuseOneSupervisorAndPreserveManagedRouteAndChangedTranscript() throws Exception {
        SessionRestartLauncher.RestartHandoff supervisor = new SessionRestartLauncher.RestartHandoff();
        supervisor.queue(List.of("kompile", SessionRestartLauncher.PARENT_PID_ARGUMENT + "1234",
                "resume", "--session-id", "first"), tempDir, 1234L);
        List<List<String>> commands = new ArrayList<>();
        AtomicReference<Path> mailbox = new AtomicReference<>();
        assertEquals(9, supervisor.finish(0, (command, directory) -> {
            commands.add(command);
            SessionRestartLauncher.RestartHandoff child = new SessionRestartLauncher.RestartHandoff();
            String flag = command.get(command.size() - 1);
            mailbox.set(Path.of(flag.substring(SessionRestartLauncher.SUPERVISOR_ARGUMENT.length())));
            String[] args = child.prepare(command.subList(1, command.size()).toArray(String[]::new));
            assertFalse(List.of(args).stream().anyMatch(arg -> arg.startsWith(SessionRestartLauncher.SUPERVISOR_ARGUMENT)));
            if (commands.size() == 1) {
                child.queue(List.of("kompile", SessionRestartLauncher.PARENT_PID_ARGUMENT + "4321",
                        "chat", "--resume", "after-clear", "--mode", "passthrough",
                        "--agent", "codex", "--internal-managed-resume"), tempDir, 4321L);
                return child.finish(0, (nestedCommand, cwd) -> {
                    fail("a supervised child must exit, not spawn another supervisor");
                    return 1;
                });
            }
            assertEquals(List.of("chat", "--resume", "after-clear", "--mode", "passthrough",
                    "--agent", "codex", "--internal-managed-resume"), List.of(args));
            return 9;
        }));
        assertEquals(2, commands.size());
        assertEquals(commands.get(0).get(commands.get(0).size() - 1),
                commands.get(1).get(commands.get(1).size() - 1));
        assertFalse(Files.exists(mailbox.get()), "mailbox is removed on final exit");
    }

    @Test
    void failedCommandCleanupCancelsDeferredRestart() throws Exception {
        SessionRestartLauncher.RestartHandoff handoff = new SessionRestartLauncher.RestartHandoff();
        handoff.queue(List.of("kompile", "resume", "--session-id", "abc"), tempDir, 1234L);
        assertEquals(1, handoff.finish(1, (command, directory) -> {
            fail("failed cleanup must not launch the replacement");
            return 0;
        }));
        assertEquals(0, handoff.finish(0, (command, directory) -> {
            fail("the cancelled request must not leak into a later command");
            return 0;
        }));
    }

    @Test
    void duplicateRestartOrInvalidSupervisorMailboxIsRejected() {
        SessionRestartLauncher.RestartHandoff handoff = new SessionRestartLauncher.RestartHandoff();
        handoff.queue(List.of("kompile", "resume"), tempDir, 1234L);
        assertThrows(IllegalStateException.class,
                () -> handoff.queue(List.of("kompile", "resume"), tempDir, 1234L));
        assertThrows(IllegalArgumentException.class,
                () -> handoff.prepare(new String[]{SessionRestartLauncher.SUPERVISOR_ARGUMENT + "relative"}));
    }

    @Test
    void spawnFailureRemovesThePrivateMailbox() {
        SessionRestartLauncher.RestartHandoff handoff = new SessionRestartLauncher.RestartHandoff();
        handoff.queue(List.of("kompile", "resume"), tempDir, 1234L);
        AtomicReference<Path> mailbox = new AtomicReference<>();
        assertThrows(IOException.class, () -> handoff.finish(0, (command, directory) -> {
            mailbox.set(Path.of(command.get(command.size() - 1)
                    .substring(SessionRestartLauncher.SUPERVISOR_ARGUMENT.length())));
            throw new IOException("executable unavailable");
        }));
        assertFalse(Files.exists(mailbox.get()));
    }

    @Test
    void handoffCleanupRunsBeforeSpawningAndIsNotRepeated() throws Exception {
        SessionRestartLauncher.RestartHandoff handoff = new SessionRestartLauncher.RestartHandoff();
        List<String> events = new ArrayList<>();
        handoff.registerCleanup(() -> events.add("close initialized resources"));
        handoff.queue(List.of("kompile", "resume"), tempDir, 1234L);
        handoff.finish(0, (command, directory) -> {
            events.add("start fresh CLI");
            return 0;
        });
        handoff.finish(0, (command, directory) -> {
            fail("consumed restart must not launch again");
            return 0;
        });
        assertEquals(List.of("close initialized resources", "start fresh CLI"), events);
        assertFalse(handoff.isPending());
    }

    @Test
    void realFreshProcessesRetainInputAndCompleteHooksBeforeNextReset() throws Exception {
        List<String> command = RestartProcessProbe.command(0, tempDir);
        Path output = tempDir.resolve("probe-output.txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            process.getOutputStream().write("inherited input\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "restart probe must not hang");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertTrue(Files.readString(output).contains("received: inherited input"));
            assertEquals(List.of("1", "2", "3", "0"),
                    Files.readAllLines(tempDir.resolve("hooks.txt")),
                    "each replaced child fully shuts down before the next; one supervisor exits last");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void ptyResetKeepsForegroundInputAndCtrlCWithTheActiveChild() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/script"))
                && Files.isExecutable(Path.of("/bin/bash")), "POSIX PTY integration helper");
        String probe = RestartProcessProbe.command(0, tempDir).stream()
                .map(SessionRestartLauncherTest::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
        String interactiveShell = "/bin/bash -ic " + shellQuote(probe + "; printf '\\nshell returned\\n'");
        Path output = tempDir.resolve("pty-output.txt");
        Process process = new ProcessBuilder("/usr/bin/script", "-q", "-e", "-c", interactiveShell, "/dev/null")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            awaitProbeOutput(process, output, "ready for input");
            assertTrue(Files.readString(output).contains("console: true"), "fresh CLI retains the controlling TTY");
            process.getOutputStream().write(3); // terminal Ctrl-C, delivered to the foreground process group
            process.getOutputStream().flush();
            awaitProbeOutput(process, output, "child received interrupt");
            assertFalse(Files.readString(output).contains("shell returned"),
                    "Ctrl-C must not kill the waiting supervisor while the active CLI handles it");
            process.getOutputStream().write("inherited input\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "PTY handoff must not hang");
            String transcript = Files.readString(output);
            assertEquals(0, process.exitValue(), transcript);
            assertTrue(transcript.contains("received: inherited input"), transcript);
            assertTrue(transcript.contains("shell returned"), transcript);
            assertEquals(List.of("1", "2", "3", "0"), Files.readAllLines(tempDir.resolve("hooks.txt")));
        } finally {
            if (process.isAlive()) {
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void awaitProbeOutput(Process process, Path output, String marker) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(output) && Files.readString(output).contains(marker)) return;
            Thread.sleep(20);
        }
        fail("Probe did not reach " + marker + ": " + (Files.exists(output) ? Files.readString(output) : "no output"));
    }

    /** No chat/model bootstrap: exercises the actual multi-JVM handoff and inherited descriptors. */
    public static class RestartProcessProbe {
        static List<String> command(int generation, Path directory) {
            return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    RestartProcessProbe.class.getName(), Integer.toString(generation), directory.toString());
        }

        public static void main(String[] args) throws Exception {
            SessionRestartLauncher.RestartHandoff handoff = new SessionRestartLauncher.RestartHandoff();
            String[] cleanArgs = handoff.prepare(args);
            int generation = Integer.parseInt(cleanArgs[0]);
            Path directory = Path.of(cleanArgs[1]);
            Path hooks = directory.resolve("hooks.txt");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.writeString(hooks, generation + "\n", java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));
            if (generation > 1) {
                List<String> completed = Files.readAllLines(hooks);
                if (!completed.contains(Integer.toString(generation - 1))) {
                    throw new IllegalStateException("Previous child's hooks did not finish before resume");
                }
            }
            if (generation < 3) {
                handoff.queue(command(generation + 1, directory), directory, ProcessHandle.current().pid());
            } else {
                sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal ->
                        System.out.println("child received interrupt"));
                System.out.println("console: " + (System.console() != null));
                System.out.println("ready for input");
                System.out.println("received: " + new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in)).readLine());
            }
            int result = handoff.finish(0, SessionRestartLauncher::runForeground);
            System.exit(result);
        }
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
