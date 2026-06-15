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

package ai.kompile.cli.main.serve;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Starts the kompile daemon server — a shared process that multiplexes
 * MCP tool sessions and chat sessions over a single Unix domain socket.
 * <p>
 * This replaces N separate {@code kompile mcp-stdio} processes (one per Claude Code /
 * Codex / Gemini session) with a single daemon, saving ~120 MB of JVM overhead
 * plus ~100 MB of duplicated class loading per eliminated process.
 * <p>
 * Usage:
 * <pre>
 *   kompile serve                          # Start daemon (foreground)
 *   kompile serve --idle-timeout 30        # Auto-stop after 30 min idle
 *   kompile serve --max-sessions 10        # Limit concurrent connections
 *   kompile serve --detach                 # Fork to background (daemon mode)
 * </pre>
 * <p>
 * The daemon listens on {@code ~/.kompile/runtime/kompile.sock} and is discovered
 * automatically by {@code kompile mcp-stdio} (which bridges stdio ↔ socket) and
 * {@code kompile chat} (which connects as a thin client).
 */
@CommandLine.Command(
        name = "serve",
        hidden = true,  // Internal — daemon is auto-managed, not user-facing
        description = "Start the kompile daemon server (auto-managed, not normally run directly).%n"
                + "The daemon is started automatically when mcp-stdio or chat needs it,%n"
                + "and stops itself after 30 minutes of inactivity.%n%n"
                + "Use 'kompile daemon' to check status.",
        mixinStandardHelpOptions = true
)
public class ServeCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--idle-timeout"},
            description = "Shutdown after this many minutes with zero sessions (0 = never, default: 30)",
            defaultValue = "30")
    private int idleTimeoutMinutes;

    @CommandLine.Option(names = {"--max-sessions"},
            description = "Maximum concurrent connections (0 = unlimited, default: 0)",
            defaultValue = "0")
    private int maxSessions;

    @CommandLine.Option(names = {"--detach"},
            description = "Fork to background as a daemon process",
            defaultValue = "false")
    private boolean detach;

    @Override
    public Integer call() {
        // Ensure runtime directory exists
        File runtimeDir = KompileHome.runtimeDirectory();
        runtimeDir.mkdirs();

        // Acquire exclusive lock — prevents multiple daemons
        File lockFile = KompileHome.daemonLockFile();
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
             FileChannel lockChannel = raf.getChannel()) {

            FileLock lock = lockChannel.tryLock();
            if (lock == null) {
                System.err.println("Another kompile daemon is already running.");
                System.err.println("Socket: " + KompileHome.daemonSocketFile().getAbsolutePath());

                // Show existing daemon info
                if (DaemonClient.isDaemonAlive()) {
                    System.err.println("Status: alive");
                } else {
                    System.err.println("Status: stale lock — cleaning up");
                    Files.deleteIfExists(KompileHome.daemonSocketFile().toPath());
                    // Retry lock after cleanup
                    lock = lockChannel.tryLock();
                    if (lock == null) {
                        System.err.println("Still locked. Another process holds the lock file.");
                        return 1;
                    }
                    // Fall through to start
                }

                if (lock == null) return 1;
            }

            // Handle --detach: re-exec ourselves with setsid
            if (detach) {
                return forkToBackground();
            }

            // Write PID file
            Path pidFile = runtimeDir.toPath().resolve("kompile.pid");
            Files.writeString(pidFile, String.valueOf(ProcessHandle.current().pid()));

            // Initialize shared resources
            System.err.println("[daemon] Initializing shared resource pool...");
            SharedResourcePool pool = new SharedResourcePool();

            // Register in instance registry
            long pid = ProcessHandle.current().pid();
            InstanceInfo info = new InstanceInfo("kompile-daemon", "daemon", 0, pid,
                    KompileHome.daemonSocketFile().getAbsolutePath());
            InstanceRegistry.register(info);

            long idleMs = idleTimeoutMinutes > 0 ? idleTimeoutMinutes * 60_000L : 0;
            DaemonServer server = new DaemonServer(pool, maxSessions, idleMs);

            // Shutdown hook for clean exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("[daemon] Shutting down...");
                server.stop();
                pool.close();
                InstanceRegistry.unregister("kompile-daemon");
                try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
            }, "daemon-shutdown"));

            // Start — blocks until stop() or idle timeout
            server.start(() -> {
                String socket = KompileHome.daemonSocketFile().getAbsolutePath();
                System.err.println("[daemon] Ready. Socket: " + socket);
                System.err.println("[daemon] PID: " + pid);
                if (idleTimeoutMinutes > 0) {
                    System.err.println("[daemon] Idle timeout: " + idleTimeoutMinutes + " minutes");
                }
                if (maxSessions > 0) {
                    System.err.println("[daemon] Max sessions: " + maxSessions);
                }
                System.err.println("[daemon] Press Ctrl+C to stop.");
            });

            return 0;

        } catch (Exception e) {
            System.err.println("Failed to start daemon: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Fork to background using ProcessBuilder. Writes the child PID to stdout
     * so the caller can track it.
     */
    private int forkToBackground() {
        try {
            // Re-run ourselves without --detach
            String currentCommand = ProcessHandle.current().info().command().orElse("java");
            ProcessBuilder pb;

            // Detect if running as native image or JVM
            if (!currentCommand.toLowerCase().contains("java")) {
                // Native image
                pb = new ProcessBuilder(currentCommand, "serve",
                        "--idle-timeout", String.valueOf(idleTimeoutMinutes),
                        "--max-sessions", String.valueOf(maxSessions));
            } else {
                // JVM — need classpath
                String classPath = System.getProperty("java.class.path");
                pb = new ProcessBuilder(currentCommand, "-cp", classPath,
                        ai.kompile.cli.main.MainCommand.class.getName(), "serve",
                        "--idle-timeout", String.valueOf(idleTimeoutMinutes),
                        "--max-sessions", String.valueOf(maxSessions));
            }

            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    new File(KompileHome.runtimeDirectory(), "daemon.log")));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(
                    new File(KompileHome.runtimeDirectory(), "daemon.log")));

            Process child = pb.start();
            long childPid = child.pid();
            System.out.println("kompile daemon started (PID " + childPid + ")");
            System.out.println("Log: " + new File(KompileHome.runtimeDirectory(), "daemon.log").getAbsolutePath());
            System.out.println("Socket: " + KompileHome.daemonSocketFile().getAbsolutePath());
            return 0;

        } catch (IOException e) {
            System.err.println("Failed to fork daemon: " + e.getMessage());
            return 1;
        }
    }
}
