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

package ai.kompile.cli.main.coordination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for inspecting and managing the agent coordination state.
 *
 * <pre>
 * kompile edit-coordinator status                     # dashboard
 * kompile edit-coordinator edits [--file path]        # list edit locks
 * kompile edit-coordinator agents                     # list active agents
 * kompile edit-coordinator processes                  # list active processes
 * kompile edit-coordinator release --lock-id id       # force-release a lock
 * kompile edit-coordinator release --file path        # force-release by file
 * kompile edit-coordinator clean                      # evict stale entries
 * </pre>
 */
@Command(
        name = "edit-coordinator",
        description = "Inspect and manage agent coordination state (edit locks, processes, agent activity)",
        subcommands = {
                EditCoordinatorCommand.StatusCmd.class,
                EditCoordinatorCommand.EditsCmd.class,
                EditCoordinatorCommand.AgentsCmd.class,
                EditCoordinatorCommand.ProcessesCmd.class,
                EditCoordinatorCommand.ReleaseCmd.class,
                EditCoordinatorCommand.CleanCmd.class
        },
        mixinStandardHelpOptions = true
)
public class EditCoordinatorCommand implements Callable<Integer> {

    @Option(names = {"--work-dir"}, description = "Working directory (default: current directory)")
    private String workDir;

    @Override
    public Integer call() {
        // Default: show status
        return new StatusCmd().callWithWorkDir(resolveWorkDir());
    }

    private Path resolveWorkDir() {
        return workDir != null ? Paths.get(workDir) : Paths.get(System.getProperty("user.dir"));
    }

    // ── status ────────────────────────────────────────────────────────────

    @Command(name = "status", description = "Show coordination dashboard (agents, edits, processes)",
            mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private EditCoordinatorCommand parent;

        @Override
        public Integer call() {
            Path wd = parent != null ? parent.resolveWorkDir()
                    : Paths.get(System.getProperty("user.dir"));
            return callWithWorkDir(wd);
        }

        int callWithWorkDir(Path wd) {
            CoordinationStateManager mgr = CoordinationStateManager.forCli(wd);
            try {
                System.out.println(mgr.statusDashboard());
            } finally {
                mgr.shutdown();
            }
            return 0;
        }
    }

    // ── edits ─────────────────────────────────────────────────────────────

    @Command(name = "edits", description = "List active file edit locks",
            mixinStandardHelpOptions = true)
    static class EditsCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private EditCoordinatorCommand parent;

        @Option(names = {"--file"}, description = "Filter by file path")
        private String file;

        @Option(names = {"--json"}, description = "Output as JSON")
        private boolean json;

        @Override
        public Integer call() {
            Path wd = parent != null ? parent.resolveWorkDir()
                    : Paths.get(System.getProperty("user.dir"));
            CoordinationStateManager mgr = CoordinationStateManager.forCli(wd);
            try {
                List<EditLockEntry> edits;
                if (file != null && !file.isEmpty()) {
                    edits = mgr.queryEditsForFile(file);
                } else {
                    edits = mgr.queryEdits();
                }

                if (json) {
                    ObjectMapper om = jsonMapper();
                    System.out.println(om.writerWithDefaultPrettyPrinter().writeValueAsString(edits));
                } else if (edits.isEmpty()) {
                    System.out.println("No active file edits.");
                } else {
                    System.out.printf("%-50s %-15s %-6s %s%n", "FILE", "AGENT", "TYPE", "AGE");
                    System.out.println("-".repeat(90));
                    for (EditLockEntry e : edits) {
                        System.out.printf("%-50s %-15s %-6s %s%n",
                                truncate(e.getFilePath(), 50),
                                e.getAgentName(),
                                e.getEditType(),
                                formatAge(e.getAcquiredAt()));
                    }
                    System.out.println("\n" + edits.size() + " active edit(s)");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } finally {
                mgr.shutdown();
            }
            return 0;
        }
    }

    // ── agents ────────────────────────────────────────────────────────────

    @Command(name = "agents", description = "List active agent sessions",
            mixinStandardHelpOptions = true)
    static class AgentsCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private EditCoordinatorCommand parent;

        @Option(names = {"--json"}, description = "Output as JSON")
        private boolean json;

        @Option(names = {"--include-stale"}, description = "Include expired/stale agents")
        private boolean includeStale;

        @Override
        public Integer call() {
            Path wd = parent != null ? parent.resolveWorkDir()
                    : Paths.get(System.getProperty("user.dir"));
            CoordinationStateManager mgr = CoordinationStateManager.forCli(wd);
            try {
                List<AgentEntry> agents = mgr.queryAgents(includeStale);

                if (json) {
                    ObjectMapper om = jsonMapper();
                    System.out.println(om.writerWithDefaultPrettyPrinter().writeValueAsString(agents));
                } else if (agents.isEmpty()) {
                    System.out.println("No active agents.");
                } else {
                    System.out.printf("%-25s %-10s %-7s %-5s %-40s %s%n",
                            "SESSION", "AGENT", "TYPE", "DEPTH", "TASK", "RUNNING");
                    System.out.println("-".repeat(110));
                    for (AgentEntry a : agents) {
                        System.out.printf("%-25s %-10s %-7s %-5d %-40s %s%n",
                                truncate(a.getSessionId(), 25),
                                a.getAgentName(),
                                a.getAgentType(),
                                a.getDepth(),
                                truncate(a.getTask(), 40),
                                formatAge(a.getStartedAt()));
                    }
                    System.out.println("\n" + agents.size() + " agent(s)");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } finally {
                mgr.shutdown();
            }
            return 0;
        }
    }

    // ── processes ──────────────────────────────────────────────────────────

    @Command(name = "processes", description = "List active processes across all agents",
            mixinStandardHelpOptions = true)
    static class ProcessesCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private EditCoordinatorCommand parent;

        @Option(names = {"--json"}, description = "Output as JSON")
        private boolean json;

        @Override
        public Integer call() {
            Path wd = parent != null ? parent.resolveWorkDir()
                    : Paths.get(System.getProperty("user.dir"));
            CoordinationStateManager mgr = CoordinationStateManager.forCli(wd);
            try {
                List<ProcessCoordEntry> processes = mgr.queryProcesses();

                if (json) {
                    ObjectMapper om = jsonMapper();
                    System.out.println(om.writerWithDefaultPrettyPrinter().writeValueAsString(processes));
                } else if (processes.isEmpty()) {
                    System.out.println("No active processes.");
                } else {
                    System.out.printf("%-10s %-15s %-30s %-10s %s%n",
                            "PROC_ID", "AGENT", "COMMAND", "STATE", "DURATION");
                    System.out.println("-".repeat(90));
                    for (ProcessCoordEntry p : processes) {
                        System.out.printf("%-10s %-15s %-30s %-10s %s%n",
                                p.getProcessId(),
                                p.getAgentName(),
                                truncate(p.getCommand(), 30),
                                p.getState(),
                                formatAge(p.getStartedAt()));
                    }
                    System.out.println("\n" + processes.size() + " process(es)");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } finally {
                mgr.shutdown();
            }
            return 0;
        }
    }

    // ── release ───────────────────────────────────────────────────────────

    @Command(name = "release", description = "Force-release an edit lock by ID or file path",
            mixinStandardHelpOptions = true)
    static class ReleaseCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private EditCoordinatorCommand parent;

        @Option(names = {"--lock-id"}, description = "Lock ID to release")
        private String lockId;

        @Option(names = {"--file"}, description = "File path to release all locks on")
        private String file;

        @Override
        public Integer call() {
            if (lockId == null && file == null) {
                System.err.println("Either --lock-id or --file is required");
                return 1;
            }

            Path wd = parent != null ? parent.resolveWorkDir()
                    : Paths.get(System.getProperty("user.dir"));
            CoordinationStateManager mgr = CoordinationStateManager.forCli(wd);
            try {
                if (lockId != null) {
                    boolean released = mgr.releaseEditLock(lockId);
                    System.out.println(released ? "Lock " + lockId + " released."
                            : "Lock " + lockId + " not found.");
                } else {
                    boolean released = mgr.forceReleaseLockByFile(file);
                    System.out.println(released ? "All locks on " + file + " released."
                            : "No locks found for " + file + ".");
                }
            } finally {
                mgr.shutdown();
            }
            return 0;
        }
    }

    // ── clean ─────────────────────────────────────────────────────────────

    @Command(name = "clean", description = "Evict all stale (TTL-expired) coordination entries",
            mixinStandardHelpOptions = true)
    static class CleanCmd implements Callable<Integer> {

        @CommandLine.ParentCommand
        private EditCoordinatorCommand parent;

        @Override
        public Integer call() {
            Path wd = parent != null ? parent.resolveWorkDir()
                    : Paths.get(System.getProperty("user.dir"));
            CoordinationStateManager mgr = CoordinationStateManager.forCli(wd);
            try {
                int evicted = mgr.evictStale();
                System.out.println("Evicted " + evicted + " stale entries.");
            } finally {
                mgr.shutdown();
            }
            return 0;
        }
    }

    // ── Shared utilities ──────────────────────────────────────────────────

    private static ObjectMapper jsonMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return om;
    }

    private static String formatAge(Instant since) {
        Duration d = Duration.between(since, Instant.now());
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) return minutes + "m" + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h" + minutes + "m";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
