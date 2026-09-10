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

import ai.kompile.cli.common.KompileHome;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Starts a fresh Kompile process in an independent terminal and resumes the
 * current transcript after the old process has completed normal session and
 * terminal cleanup.
 */
public final class SessionRestartLauncher {

    static final String PARENT_PID_ARGUMENT = "--internal-restart-parent-pid=";
    static final Duration PARENT_EXIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PROCESS_START_TOLERANCE = Duration.ofSeconds(5);
    private static final String MAIN_CLASS = "ai.kompile.cli.main.MainCommand";

    @FunctionalInterface
    interface ProcessSpawner {
        long spawn(List<String> command, Path workingDirectory) throws IOException;
    }

    @FunctionalInterface
    interface ParentAwaiter {
        boolean await(long parentPid, Duration timeout) throws InterruptedException;
    }

    @FunctionalInterface
    interface ProcessTerminator {
        boolean terminate(SessionEntry session);
    }

    public record LaunchResult(boolean started, long processId, String error) {
        static LaunchResult started(long processId) {
            return new LaunchResult(true, processId, null);
        }

        static LaunchResult failed(String error) {
            return new LaunchResult(false, -1L, error);
        }
    }

    public record RestartFailure(String sessionId, String error) {
    }

    public record RestartAllResult(int activeSessions,
                                   int replacementsStarted,
                                   int sessionsTerminated,
                                   boolean currentSessionRestarted,
                                   List<RestartFailure> failures) {
        public RestartAllResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    private final Supplier<List<String>> launchPrefixResolver;
    private final ProcessSpawner processSpawner;
    private final LongSupplier currentPid;
    private final Predicate<SessionEntry> processMatcher;
    private final ProcessTerminator processTerminator;

    SessionRestartLauncher(Supplier<List<String>> launchPrefixResolver,
                           ProcessSpawner processSpawner,
                           LongSupplier currentPid) {
        this(launchPrefixResolver, processSpawner, currentPid,
                SessionRestartLauncher::isRestartableSessionProcess,
                SessionRestartLauncher::terminateProcess);
    }

    SessionRestartLauncher(Supplier<List<String>> launchPrefixResolver,
                           ProcessSpawner processSpawner,
                           LongSupplier currentPid,
                           Predicate<SessionEntry> processMatcher,
                           ProcessTerminator processTerminator) {
        this.launchPrefixResolver = launchPrefixResolver;
        this.processSpawner = processSpawner;
        this.currentPid = currentPid;
        this.processMatcher = processMatcher;
        this.processTerminator = processTerminator;
    }

    public static LaunchResult restartCurrentSession(String sessionId, Path workingDirectory) {
        return systemLauncher().restart(sessionId, workingDirectory);
    }

    public static LaunchResult restartManagedSession(String sessionId, Path workingDirectory,
                                                     String agent) {
        return systemLauncher().restartManaged(sessionId, workingDirectory, agent);
    }

    public static RestartAllResult restartAllActiveSessions(String currentSessionId,
                                                            Path workingDirectory) {
        return restartAllActiveSessions(currentSessionId, workingDirectory, null, false);
    }

    public static RestartAllResult restartAllActiveManagedSessions(String currentSessionId,
                                                                   Path workingDirectory,
                                                                   String agent) {
        return restartAllActiveSessions(currentSessionId, workingDirectory, agent, true);
    }

    private static RestartAllResult restartAllActiveSessions(String currentSessionId,
                                                             Path workingDirectory,
                                                             String agent,
                                                             boolean managed) {
        SessionRestartLauncher launcher = systemLauncher();
        List<SessionEntry> sessions = activeSessionSnapshot(
                currentSessionId, workingDirectory, agent, managed, launcher.currentPid.getAsLong());
        return launcher.restartAll(sessions);
    }

    LaunchResult restart(String sessionId, Path workingDirectory) {
        if (sessionId == null || sessionId.isBlank()) {
            return LaunchResult.failed("The current session has no transcript ID to resume.");
        }
        return restart(sessionId, workingDirectory,
                List.of("resume", "--session-id", sessionId));
    }

    LaunchResult restartManaged(String sessionId, Path workingDirectory, String agent) {
        if (sessionId == null || sessionId.isBlank()) {
            return LaunchResult.failed("The current session has no transcript ID to resume.");
        }
        if (agent == null || agent.isBlank()) {
            return LaunchResult.failed("The managed session has no agent to restart.");
        }
        return restart(sessionId, workingDirectory, List.of(
                "chat", "--resume", sessionId,
                "--mode", "passthrough",
                "--agent", agent.trim(),
                "--internal-managed-resume"));
    }

    RestartAllResult restartAll(List<SessionEntry> sessions) {
        long ownPid = currentPid.getAsLong();
        Map<Long, SessionEntry> targetsByPid = new LinkedHashMap<>();
        if (sessions != null) {
            for (SessionEntry entry : sessions) {
                if (entry == null || entry.getPid() <= 0
                        || !"running".equals(entry.getStatus())) {
                    continue;
                }
                boolean matches = entry.getPid() == ownPid && entry.isProcessAlive();
                if (!matches) {
                    try {
                        matches = processMatcher.test(entry);
                    } catch (RuntimeException ignored) {
                        matches = false;
                    }
                }
                if (matches) {
                    targetsByPid.put(entry.getPid(), entry);
                }
            }
        }

        List<RestartFailure> failures = new ArrayList<>();
        Map<Long, LaunchResult> launches = new LinkedHashMap<>();
        for (Map.Entry<Long, SessionEntry> target : targetsByPid.entrySet()) {
            LaunchResult launch = restart(target.getValue(), target.getKey());
            if (launch.started()) {
                launches.put(target.getKey(), launch);
            } else {
                failures.add(new RestartFailure(
                        restartSessionId(target.getValue()), launch.error()));
            }
        }

        int terminated = 0;
        boolean currentRestarted = false;
        for (Map.Entry<Long, LaunchResult> launch : launches.entrySet()) {
            long ownerPid = launch.getKey();
            SessionEntry target = targetsByPid.get(ownerPid);
            if (ownerPid == ownPid) {
                currentRestarted = true;
                continue;
            }
            boolean stopped;
            try {
                stopped = processTerminator.terminate(target);
            } catch (RuntimeException ignored) {
                stopped = false;
            }
            if (stopped) {
                terminated++;
            } else {
                failures.add(new RestartFailure(restartSessionId(target),
                        "Replacement started, but the previous process " + ownerPid
                                + " could not be stopped."));
            }
        }

        return new RestartAllResult(
                targetsByPid.size(), launches.size(), terminated,
                currentRestarted, failures);
    }

    private LaunchResult restart(SessionEntry entry, long parentPid) {
        String sessionId = restartSessionId(entry);
        if (sessionId == null || sessionId.isBlank()) {
            return LaunchResult.failed("The current session has no transcript ID to resume.");
        }
        if (entry == null || entry.getProjectDirectory() == null
                || entry.getProjectDirectory().isBlank()) {
            return LaunchResult.failed("The session has no working directory to restore.");
        }
        Path workingDirectory;
        try {
            workingDirectory = Path.of(entry.getProjectDirectory());
        } catch (RuntimeException invalidPath) {
            return LaunchResult.failed("Invalid session working directory: "
                    + invalidPath.getMessage());
        }

        if (entry != null && "passthrough".equalsIgnoreCase(entry.getLaunchMode())) {
            String agent = entry.getAgent();
            if (agent == null || agent.isBlank()) {
                return LaunchResult.failed("The managed session has no agent to restart.");
            }
            return restart(sessionId, workingDirectory, parentPid, List.of(
                    "chat", "--resume", sessionId,
                    "--mode", "passthrough",
                    "--agent", agent.trim(),
                    "--internal-managed-resume"));
        }
        return restart(sessionId, workingDirectory, parentPid,
                List.of("resume", "--session-id", sessionId));
    }

    private static String restartSessionId(SessionEntry entry) {
        if (entry == null) return "";
        if (entry.getKompileSessionId() != null
                && !entry.getKompileSessionId().isBlank()) {
            return entry.getKompileSessionId();
        }
        return Objects.toString(entry.getConversationId(), "");
    }

    private LaunchResult restart(String sessionId, Path workingDirectory,
                                 List<String> sessionCommand) {
        return restart(sessionId, workingDirectory, currentPid.getAsLong(), sessionCommand);
    }

    private LaunchResult restart(String sessionId, Path workingDirectory,
                                 long parentPid, List<String> sessionCommand) {
        if (sessionId == null || sessionId.isBlank()) {
            return LaunchResult.failed("The current session has no transcript ID to resume.");
        }

        Path directory = workingDirectory == null
                ? Path.of(System.getProperty("user.dir"))
                : workingDirectory;
        directory = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            return LaunchResult.failed("Session working directory does not exist: " + directory);
        }

        try {
            List<String> prefix = List.copyOf(launchPrefixResolver.get());
            List<String> command = buildRestartCommand(
                    prefix, parentPid, sessionCommand);
            return LaunchResult.started(processSpawner.spawn(command, directory));
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return LaunchResult.failed(message);
        }
    }

    private static List<SessionEntry> activeSessionSnapshot(String currentSessionId,
                                                            Path workingDirectory,
                                                            String agent,
                                                            boolean managed,
                                                            long currentPid) {
        List<SessionEntry> sessions = new ArrayList<>();
        try {
            SessionRegistry registry = SessionRegistry.load();
            registry.refreshStatuses();
            sessions.addAll(registry.getAll());
        } catch (RuntimeException registryFailure) {
            System.err.println("Warning: Could not enumerate active sessions: "
                    + registryFailure.getMessage());
        }

        boolean currentTracked = sessions.stream().anyMatch(entry -> entry != null
                && entry.getPid() == currentPid
                && "running".equals(entry.getStatus())
                && Objects.equals(currentSessionId, entry.getKompileSessionId()));
        if (!currentTracked) {
            Path directory = workingDirectory == null
                    ? Path.of(System.getProperty("user.dir")) : workingDirectory;
            sessions.add(SessionEntry.builder()
                    .kompileSessionId(currentSessionId)
                    .agent(managed ? agent : "kompile")
                    .projectDirectory(directory.toAbsolutePath().normalize().toString())
                    .launchMode(managed ? "passthrough" : "standard")
                    .startedAt(Instant.now().toString())
                    .status("running")
                    .pid(currentPid)
                    .build());
        }
        return sessions;
    }

    private static boolean terminateProcess(SessionEntry entry) {
        if (entry == null || entry.getPid() <= 0) return false;
        Optional<ProcessHandle> process = ProcessHandle.of(entry.getPid());
        if (process.isEmpty() || !process.get().isAlive()) return true;

        Instant processStartedAt = process.get().info().startInstant().orElse(null);
        if (!processStartedNoLaterThanRegistration(processStartedAt, entry.getStartedAt())) {
            return false;
        }
        boolean requested = process.get().destroy();
        return requested || !process.get().isAlive();
    }

    private static boolean isRestartableSessionProcess(SessionEntry entry) {
        if (entry == null || !entry.isProcessAlive()) return false;
        try {
            Instant processStartedAt = ProcessHandle.of(entry.getPid())
                    .flatMap(process -> process.info().startInstant())
                    .orElse(null);
            return processStartedAt != null
                    && processStartedNoLaterThanRegistration(
                    processStartedAt, entry.getStartedAt());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean processStartedNoLaterThanRegistration(Instant processStartedAt,
                                                         String registeredAt) {
        if (processStartedAt == null || registeredAt == null || registeredAt.isBlank()) {
            return false;
        }
        try {
            Instant registration = Instant.parse(registeredAt);
            return !processStartedAt.isAfter(registration.plus(PROCESS_START_TOLERANCE));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static List<String> buildRestartCommand(List<String> launchPrefix,
                                             String sessionId,
                                             long parentPid) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID is required.");
        }
        return buildRestartCommand(launchPrefix, parentPid,
                List.of("resume", "--session-id", sessionId));
    }

    static List<String> buildRestartCommand(List<String> launchPrefix,
                                             long parentPid,
                                             List<String> sessionCommand) {
        if (launchPrefix == null || launchPrefix.isEmpty()
                || launchPrefix.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Could not resolve the Kompile launch command.");
        }
        if (sessionCommand == null || sessionCommand.isEmpty()
                || sessionCommand.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Session restart command is required.");
        }
        if (parentPid <= 0) {
            throw new IllegalArgumentException("Parent process ID must be positive.");
        }

        List<String> command = new ArrayList<>(
                launchPrefix.size() + sessionCommand.size() + 1);
        command.addAll(launchPrefix);
        command.add(PARENT_PID_ARGUMENT + parentPid);
        command.addAll(sessionCommand);
        return List.copyOf(command);
    }

    /**
     * Called at the very beginning of {@code MainCommand.main}. The internal
     * handoff argument is removed before picocli sees the remaining command.
     */
    public static String[] awaitRestartParentAndStrip(String[] args) throws InterruptedException {
        return awaitRestartParentAndStrip(
                args, SessionRestartLauncher::awaitParentExit,
                ProcessHandle.current().pid());
    }

    static String[] awaitRestartParentAndStrip(String[] args,
                                               ParentAwaiter awaiter,
                                               long currentPid) throws InterruptedException {
        String[] source = args == null ? new String[0] : args;
        int handoffIndex = -1;
        long parentPid = -1L;
        for (int i = 0; i < source.length; i++) {
            String argument = source[i];
            if (argument == null || !argument.startsWith(PARENT_PID_ARGUMENT)) {
                continue;
            }
            if (handoffIndex >= 0) {
                throw new IllegalArgumentException("Duplicate internal restart parent argument.");
            }
            handoffIndex = i;
            String value = argument.substring(PARENT_PID_ARGUMENT.length());
            try {
                parentPid = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid restart parent process ID: " + value, e);
            }
        }

        if (handoffIndex < 0) {
            return source.clone();
        }
        if (parentPid <= 0 || parentPid == currentPid) {
            throw new IllegalArgumentException("Invalid restart parent process ID: " + parentPid);
        }
        if (!awaiter.await(parentPid, PARENT_EXIT_TIMEOUT)) {
            throw new IllegalStateException("Timed out waiting for the previous Kompile process "
                    + parentPid + " to exit; refusing to resume the same session concurrently.");
        }

        String[] stripped = new String[source.length - 1];
        System.arraycopy(source, 0, stripped, 0, handoffIndex);
        System.arraycopy(source, handoffIndex + 1, stripped, handoffIndex,
                source.length - handoffIndex - 1);
        return stripped;
    }

    static List<String> resolveLaunchPrefix() {
        Path configuredLauncher = configuredDistributionLauncher();
        if (configuredLauncher != null) {
            return List.of(configuredLauncher.toString());
        }

        ProcessHandle.Info info = ProcessHandle.current().info();
        String processCommand = info.command().orElse(null);
        if (processCommand != null && !processCommand.isBlank()) {
            Path executable = Path.of(processCommand).toAbsolutePath().normalize();
            if (!isJavaExecutable(executable)) {
                return List.of(executable.toString());
            }

            List<String> javaPrefix = javaInvocationPrefix(
                    executable.toString(), info.arguments().orElse(new String[0]));
            Path distributionLauncher = distributionLauncherForJavaPrefix(javaPrefix);
            if (distributionLauncher != null) {
                return List.of(distributionLauncher.toString());
            }
            if (!javaPrefix.isEmpty()) {
                return javaPrefix;
            }
        }

        Path codeSource = codeSourcePath();
        if (codeSource != null && Files.isRegularFile(codeSource)
                && codeSource.getFileName().toString().endsWith(".jar")) {
            Path distributionLauncher = distributionLauncherForJar(codeSource);
            if (distributionLauncher != null) {
                return List.of(distributionLauncher.toString());
            }
            return List.of(javaExecutable(), "-jar", codeSource.toString());
        }

        Path installedLauncher = executableLauncher(KompileHome.installDirectory().toPath());
        if (installedLauncher != null) {
            return List.of(installedLauncher.toString());
        }
        return List.of("kompile");
    }

    static List<String> javaInvocationPrefix(String javaCommand, String[] arguments) {
        if (javaCommand == null || javaCommand.isBlank()) {
            return List.of();
        }
        String[] args = arguments == null ? new String[0] : arguments;
        for (int i = 0; i < args.length; i++) {
            if ("-jar".equals(args[i]) && i + 1 < args.length) {
                List<String> prefix = new ArrayList<>(i + 3);
                prefix.add(javaCommand);
                prefix.addAll(Arrays.asList(args).subList(0, i + 2));
                return List.copyOf(prefix);
            }
            if (MAIN_CLASS.equals(args[i])) {
                List<String> prefix = new ArrayList<>(i + 2);
                prefix.add(javaCommand);
                prefix.addAll(Arrays.asList(args).subList(0, i + 1));
                return List.copyOf(prefix);
            }
        }
        return List.of();
    }

    private static SessionRestartLauncher systemLauncher() {
        return new SessionRestartLauncher(
                SessionRestartLauncher::resolveLaunchPrefix,
                SessionRestartLauncher::spawnInNewTerminal,
                () -> ProcessHandle.current().pid());
    }

    private static long spawnInNewTerminal(List<String> command, Path workingDirectory)
            throws IOException {
        // An inherited child loses foreground TTY ownership as soon as the
        // caller's shell observes this process exit. TerminalLauncher gives the
        // replacement an independent PTY while the parent-PID handoff still
        // prevents it from opening the transcript before cleanup completes.
        Process terminalProcess = new TerminalLauncher(TerminalConfig.load()).launch(
                command, workingDirectory, "Kompile — restarted session");
        if (terminalProcess == null) {
            throw new IOException("No terminal was available for the replacement session.");
        }
        return terminalProcess.pid();
    }

    private static boolean awaitParentExit(long parentPid, Duration timeout)
            throws InterruptedException {
        Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
        if (parent.isEmpty() || !parent.get().isAlive()) {
            return true;
        }
        try {
            parent.get().onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Could not observe previous Kompile process " + parentPid + " exiting.", e);
        }
    }

    private static Path configuredDistributionLauncher() {
        for (String root : List.of(
                System.getProperty("kompile.install.dir", ""),
                System.getenv().getOrDefault("KOMPILE_INSTALL_DIR", ""),
                System.getProperty("kompile.dist.home", ""),
                System.getenv().getOrDefault("KOMPILE_DIST_HOME", ""))) {
            if (root != null && !root.isBlank()) {
                Path launcher = executableLauncher(Path.of(root));
                if (launcher != null) {
                    return launcher;
                }
            }
        }
        return null;
    }

    private static Path distributionLauncherForJavaPrefix(List<String> javaPrefix) {
        for (int i = 0; i + 1 < javaPrefix.size(); i++) {
            if ("-jar".equals(javaPrefix.get(i))) {
                return distributionLauncherForJar(Path.of(javaPrefix.get(i + 1)));
            }
        }
        return null;
    }

    static Path distributionLauncherForJar(Path jar) {
        if (jar == null) return null;
        Path parent = jar.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getFileName() == null
                || !"lib".equals(parent.getFileName().toString())) {
            return null;
        }
        return executableLauncher(parent.getParent());
    }

    private static Path executableLauncher(Path distributionRoot) {
        if (distributionRoot == null) return null;
        Path bin = distributionRoot.toAbsolutePath().normalize().resolve("bin");
        for (String name : List.of("kompile", "kompile.exe", "kompile.cmd", "kompile.bat")) {
            Path candidate = bin.resolve(name);
            if (Files.isRegularFile(candidate) && (Files.isExecutable(candidate)
                    || name.endsWith(".cmd") || name.endsWith(".bat"))) {
                return candidate;
            }
        }
        return null;
    }

    private static Path codeSourcePath() {
        try {
            if (SessionRestartLauncher.class.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            URI location = SessionRestartLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isJavaExecutable(Path executable) {
        if (executable == null || executable.getFileName() == null) return false;
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return "java".equals(name) || "java.exe".equals(name);
    }

    private static String javaExecutable() {
        String executable = File.separatorChar == '\\' ? "java.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isExecutable(candidate) ? candidate.toString() : executable;
    }
}
