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
 * Observability command for the kompile daemon.
 * <p>
 * The daemon is auto-managed — it starts transparently when needed and
 * stops after idle timeout. This command lets you observe its state.
 * <p>
 * Usage:
 * <pre>
 *   kompile daemon              # Show status
 *   kompile daemon status       # Same as above
 *   kompile daemon stop         # Gracefully stop the daemon
 *   kompile daemon log          # Tail the daemon log
 * </pre>
 */
@CommandLine.Command(
        name = "daemon",
        description = "Observe the kompile daemon (auto-managed background process).%n"
                + "The daemon starts automatically when needed and stops after 30 min idle.%n%n"
                + "Subcommands:%n"
                + "  status   Show daemon status (default)%n"
                + "  stop     Gracefully stop the daemon%n"
                + "  log      Show recent daemon log output%n",
        mixinStandardHelpOptions = true,
        subcommands = {
                DaemonCommand.StatusCmd.class,
                DaemonCommand.StopCmd.class,
                DaemonCommand.LogCmd.class
        }
)
public class DaemonCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // Default action: show status
        return printStatus();
    }

    static int printStatus() {
        String report = DaemonClient.statusReport();
        System.out.println(report);
        return 0;
    }

    @CommandLine.Command(name = "status", description = "Show daemon status", mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            return printStatus();
        }
    }

    @CommandLine.Command(name = "stop", description = "Gracefully stop the daemon", mixinStandardHelpOptions = true)
    static class StopCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            Path pidFile = KompileHome.runtimeDirectory().toPath().resolve("kompile.pid");
            if (!Files.exists(pidFile)) {
                System.out.println("No daemon running (no PID file)");
                return 0;
            }

            try {
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                var handle = ProcessHandle.of(pid);
                if (handle.isPresent() && handle.get().isAlive()) {
                    handle.get().destroy();
                    System.out.println("Sent shutdown signal to daemon (PID " + pid + ")");

                    // Wait briefly for clean exit
                    for (int i = 0; i < 20; i++) {
                        if (!handle.get().isAlive()) {
                            System.out.println("Daemon stopped");
                            cleanup();
                            return 0;
                        }
                        Thread.sleep(250);
                    }

                    // Force kill if still alive
                    handle.get().destroyForcibly();
                    System.out.println("Force-killed daemon");
                    cleanup();
                    return 0;
                } else {
                    System.out.println("Daemon not running (stale PID file)");
                    cleanup();
                    return 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while stopping daemon");
                return 1;
            } catch (Exception e) {
                System.err.println("Error stopping daemon: " + e.getMessage());
                return 1;
            }
        }

        private void cleanup() {
            try {
                Files.deleteIfExists(KompileHome.daemonSocketFile().toPath());
                Files.deleteIfExists(KompileHome.runtimeDirectory().toPath().resolve("kompile.pid"));
                Files.deleteIfExists(KompileHome.daemonLockFile().toPath());
            } catch (IOException ignored) {}
        }
    }

    @CommandLine.Command(name = "log", description = "Show recent daemon log output", mixinStandardHelpOptions = true)
    static class LogCmd implements Callable<Integer> {
        @CommandLine.Option(names = {"-n", "--lines"}, description = "Number of lines to show (default: 50)",
                defaultValue = "50")
        private int lines;

        @Override
        public Integer call() {
            File logFile = new File(KompileHome.runtimeDirectory(), "daemon.log");
            if (!logFile.exists()) {
                System.out.println("No daemon log found at " + logFile.getAbsolutePath());
                return 0;
            }

            try {
                var allLines = Files.readAllLines(logFile.toPath());
                int start = Math.max(0, allLines.size() - lines);
                for (int i = start; i < allLines.size(); i++) {
                    System.out.println(allLines.get(i));
                }
                if (allLines.size() > lines) {
                    System.out.println("\n... (showing last " + lines + " of " + allLines.size() + " lines)");
                }
            } catch (IOException e) {
                System.err.println("Error reading log: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }
}
