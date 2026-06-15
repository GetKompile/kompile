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

package ai.kompile.cli.main.chat;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Mass-resume all previously tracked agent sessions.
 * <p>
 * Reads the {@link SessionRegistry} and spawns each resumable session in its own
 * terminal window using {@link TerminalLauncher}. The kompile binary path is derived
 * from the current invocation (same resolution as {@code kompile init}).
 * <p>
 * Usage:
 * <pre>
 *   kompile resume-all                      # resume all sessions
 *   kompile resume-all --agent claude       # resume only claude sessions
 *   kompile resume-all --project /path      # resume sessions for a specific project
 *   kompile resume-all --dry-run            # show what would be resumed
 *   kompile resume-all --list               # list all tracked sessions
 *   kompile resume-all --set-terminal kitty # configure terminal emulator
 *   kompile resume-all --prune 30           # remove sessions older than 30 days
 * </pre>
 */
@CommandLine.Command(
        name = "resume-all",
        description = "Resume all previously tracked agent sessions in new terminal windows",
        mixinStandardHelpOptions = true
)
public class ResumeAllCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Filter by agent name (e.g., claude, codex)")
    private String filterAgent;

    @CommandLine.Option(names = {"--project", "-p"}, description = "Filter by project directory")
    private String filterProject;

    @CommandLine.Option(names = {"--dry-run", "-n"}, description = "Show what would be resumed without launching", defaultValue = "false")
    private boolean dryRun;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List all tracked sessions", defaultValue = "false")
    private boolean listOnly;

    @CommandLine.Option(names = {"--set-terminal"}, description = "Configure the terminal emulator to use (e.g., kitty, gnome-terminal, alacritty)")
    private String setTerminal;

    @CommandLine.Option(names = {"--set-terminal-args"}, description = "Configure extra terminal args (e.g., '-e' for terminals needing explicit exec flag)")
    private String setTerminalArgs;

    @CommandLine.Option(names = {"--terminal", "-t"}, description = "Override terminal for this invocation only")
    private String terminalOverride;

    @CommandLine.Option(names = {"--prune"}, description = "Remove sessions older than N days")
    private Integer pruneDays;

    @CommandLine.Option(names = {"--status"}, description = "Show terminal detection and config status", defaultValue = "false")
    private boolean showStatus;

    @CommandLine.Option(names = {"--recent", "-r"}, description = "Resume only the N most recent sessions")
    private Integer recentCount;

    // ANSI colors
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public Integer call() {
        // Handle terminal configuration first
        if (setTerminal != null) {
            return configureTerminal();
        }

        // Handle prune
        if (pruneDays != null) {
            return pruneOldSessions();
        }

        // Handle status
        if (showStatus) {
            return showTerminalStatus();
        }

        // Load registry
        SessionRegistry registry = SessionRegistry.load();

        // Handle list
        if (listOnly) {
            return listSessions(registry);
        }

        // Main flow: resume sessions
        return resumeSessions(registry);
    }

    // ── Configure terminal ──────────────────────────────────────────────────

    private int configureTerminal() {
        TerminalConfig config = TerminalConfig.load();
        config.setTerminalCommand(setTerminal);
        if (setTerminalArgs != null) {
            config.setTerminalArgs(setTerminalArgs);
        }
        config.save();
        System.out.println(GREEN + "Terminal configured: " + setTerminal + RESET);
        if (setTerminalArgs != null) {
            System.out.println(GREEN + "Terminal args: " + setTerminalArgs + RESET);
        }
        return 0;
    }

    // ── Prune ───────────────────────────────────────────────────────────────

    private int pruneOldSessions() {
        SessionRegistry registry = SessionRegistry.load();
        int removed = registry.pruneOlderThan(pruneDays);
        System.out.println(removed > 0
                ? GREEN + "Pruned " + removed + " sessions older than " + pruneDays + " days" + RESET
                : DIM + "No sessions older than " + pruneDays + " days" + RESET);
        return 0;
    }

    // ── Status ──────────────────────────────────────────────────────────────

    private int showTerminalStatus() {
        TerminalConfig config = TerminalConfig.load();
        TerminalLauncher launcher = new TerminalLauncher(config);

        System.out.println(BOLD + "Terminal Configuration" + RESET);
        System.out.println("  Config file:  " + DIM + "~/.kompile/config/terminal.json" + RESET);
        if (config.isConfigured()) {
            System.out.println("  Configured:   " + GREEN + config.getTerminalCommand() + RESET);
            if (config.getTerminalArgs() != null) {
                System.out.println("  Extra args:   " + config.getTerminalArgs());
            }
        } else {
            System.out.println("  Configured:   " + DIM + "(not set — using auto-detect)" + RESET);
        }
        System.out.println("  Detected:     " + CYAN + launcher.detectTerminal() + RESET);
        System.out.println("  Kompile bin:  " + CYAN + resolveKompileBinary() + RESET);

        SessionRegistry registry = SessionRegistry.load();
        registry.refreshStatuses();
        long total = registry.size();
        long resumable = registry.getResumable().size();
        long running = registry.getAll().stream().filter(e -> "running".equals(e.getStatus())).count();

        System.out.println();
        System.out.println(BOLD + "Session Registry" + RESET);
        System.out.println("  Total:     " + total);
        System.out.println("  Running:   " + running);
        System.out.println("  Resumable: " + resumable);
        return 0;
    }

    // ── List sessions ───────────────────────────────────────────────────────

    private int listSessions(SessionRegistry registry) {
        registry.refreshStatuses();
        List<SessionRegistry.SessionEntry> entries = getFilteredEntries(registry.getAll());

        if (entries.isEmpty()) {
            System.out.println(DIM + "No tracked sessions" +
                    (filterAgent != null ? " for agent '" + filterAgent + "'" : "") +
                    (filterProject != null ? " in project '" + filterProject + "'" : "") +
                    RESET);
            return 0;
        }

        System.out.println(BOLD + "Tracked Sessions" + RESET +
                DIM + " (" + entries.size() + " total)" + RESET);
        System.out.println();
        System.out.printf("  %-12s %-10s %-12s %-18s %s%n",
                "SESSION", "AGENT", "STATUS", "STARTED", "PROJECT");
        System.out.println("  " + "─".repeat(80));

        for (SessionRegistry.SessionEntry entry : entries) {
            String status = entry.getStatus();
            String statusColor = switch (status) {
                case "running" -> GREEN;
                case "exited" -> YELLOW;
                default -> DIM;
            };

            String started = "";
            try {
                started = TIME_FMT.format(Instant.parse(entry.getStartedAt()));
            } catch (Exception ignored) {}

            String projectShort = shortenPath(entry.getProjectDirectory(), 30);

            System.out.printf("  %-12s %-10s %s%-12s%s %-18s %s%n",
                    truncate(entry.getKompileSessionId(), 12),
                    entry.getAgent(),
                    statusColor, status, RESET,
                    started,
                    projectShort);

            // Show conversation ID and title on second line if present
            boolean hasConvId = entry.getConversationId() != null && !entry.getConversationId().isEmpty();
            boolean hasTitle = entry.getTitle() != null && !entry.getTitle().isEmpty();
            if (hasConvId || hasTitle) {
                String detail = DIM + "  ";
                if (hasConvId) detail += "conv:" + truncate(entry.getConversationId(), 20);
                if (hasTitle) detail += (hasConvId ? "  " : "") + entry.getTitle();
                System.out.println(detail + RESET);
            }
        }

        long resumable = entries.stream().filter(e -> "exited".equals(e.getStatus())
                && ((e.getConversationId() != null && !e.getConversationId().isEmpty())
                || (e.getKompileSessionId() != null && !e.getKompileSessionId().isEmpty()))).count();
        System.out.println();
        System.out.println(DIM + "  " + resumable + " resumable" + RESET);
        return 0;
    }

    // ── Resume sessions ─────────────────────────────────────────────────────

    private int resumeSessions(SessionRegistry registry) {
        List<SessionRegistry.SessionEntry> resumable = getFilteredEntries(registry.getResumable());

        if (recentCount != null && recentCount > 0) {
            resumable = resumable.stream()
                    .sorted(Comparator.comparing(SessionRegistry.SessionEntry::getStartedAt).reversed())
                    .limit(recentCount)
                    .collect(Collectors.toList());
        }

        if (resumable.isEmpty()) {
            System.out.println(DIM + "No resumable sessions found" +
                    (filterAgent != null ? " for agent '" + filterAgent + "'" : "") +
                    (filterProject != null ? " in project '" + filterProject + "'" : "") +
                    RESET);
            System.out.println(DIM + "  Start a session with: kompile chat" + RESET);
            return 0;
        }

        String kompileBin = resolveKompileBinary();
        TerminalConfig config = terminalOverride != null
                ? overrideTerminalConfig(terminalOverride)
                : TerminalConfig.load();
        TerminalLauncher launcher = new TerminalLauncher(config);

        System.out.println(BOLD + "Resuming " + resumable.size() + " session"
                + (resumable.size() > 1 ? "s" : "") + RESET);
        System.out.println(DIM + "  Terminal: " + launcher.detectTerminal() + RESET);
        System.out.println(DIM + "  Binary:   " + kompileBin + RESET);
        System.out.println();

        int launched = 0;
        int failed = 0;

        for (SessionRegistry.SessionEntry entry : resumable) {
            String sessionLabel = entry.getAgent() + " @ " + shortenPath(entry.getProjectDirectory(), 40);
            String title = entry.getTitle() != null && !entry.getTitle().isEmpty()
                    ? entry.getTitle()
                    : sessionLabel;

            // Build the resume command
            List<String> resumeCmd = buildResumeCommand(kompileBin, entry);

            if (dryRun) {
                System.out.println("  " + DIM + "[dry-run]" + RESET + " " + sessionLabel);
                System.out.println("    " + DIM + String.join(" ", resumeCmd) + RESET);
                launched++;
                continue;
            }

            try {
                Path projectDir = Path.of(entry.getProjectDirectory());
                if (!Files.isDirectory(projectDir)) {
                    System.out.println("  " + YELLOW + "Skipping " + sessionLabel
                            + " — project directory no longer exists" + RESET);
                    failed++;
                    continue;
                }

                launcher.launch(resumeCmd, projectDir, title);
                System.out.println("  " + GREEN + "Launched" + RESET + " " + sessionLabel);
                launched++;

                // Small delay between launches to avoid terminal race conditions
                if (launched < resumable.size()) {
                    Thread.sleep(500);
                }
            } catch (IOException e) {
                System.out.println("  " + RED + "Failed" + RESET + " " + sessionLabel
                        + ": " + e.getMessage());
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println();
        if (dryRun) {
            System.out.println(DIM + "Dry run — " + launched + " sessions would be resumed" + RESET);
        } else {
            System.out.println(launched > 0
                    ? GREEN + "Launched " + launched + " session" + (launched > 1 ? "s" : "") + RESET
                    : "");
            if (failed > 0) {
                System.out.println(YELLOW + failed + " session" + (failed > 1 ? "s" : "") + " failed" + RESET);
            }
        }
        return failed > 0 && launched == 0 ? 1 : 0;
    }

    // ── Command building ────────────────────────────────────────────────────

    /**
     * Build the resume command for a session entry.
     * Uses {@code kompile resume --session-id <id> --agent <agent>} which delegates
     * to the existing ResumeCommand infrastructure.
     * <p>
     * Handles both native binary ("kompile") and JVM mode ("java -jar /path/to.jar")
     * by splitting the kompileBin string on spaces when it contains multiple tokens.
     */
    private List<String> buildResumeCommand(String kompileBin, SessionRegistry.SessionEntry entry) {
        List<String> cmd = new ArrayList<>();

        // kompileBin may be "java -jar /path/to/kompile-cli-shaded.jar" — split it
        if (kompileBin.contains(" ")) {
            for (String token : kompileBin.split("\\s+")) {
                cmd.add(token);
            }
        } else {
            cmd.add(kompileBin);
        }
        cmd.add("resume");

        // Prefer the agent's native conversation ID for resume if available
        String sessionId = (entry.getConversationId() != null && !entry.getConversationId().isEmpty())
                ? entry.getConversationId()
                : entry.getKompileSessionId();
        cmd.add("--session-id");
        cmd.add(sessionId);

        cmd.add("--agent");
        cmd.add(entry.getAgent());

        return cmd;
    }

    // ── Filtering ───────────────────────────────────────────────────────────

    private List<SessionRegistry.SessionEntry> getFilteredEntries(List<SessionRegistry.SessionEntry> entries) {
        return entries.stream()
                .filter(e -> filterAgent == null || filterAgent.equalsIgnoreCase(e.getAgent()))
                .filter(e -> filterProject == null || e.getProjectDirectory().startsWith(filterProject))
                .sorted(Comparator.comparing(SessionRegistry.SessionEntry::getStartedAt).reversed())
                .collect(Collectors.toList());
    }

    // ── Kompile binary resolution ───────────────────────────────────────────

    /**
     * Resolve the kompile binary/command for spawning in new terminals.
     * <p>
     * Strategy:
     * 1. /proc/self/exe — if it resolves to a native "kompile" binary (not java/python)
     * 2. ProcessHandle — if the process command is a kompile binary
     * 3. Shaded JAR — look for kompile-cli-*-shaded.jar relative to current location
     * 4. sun.java.command — if we were launched via java -jar
     * 5. Well-known install: ~/.kompile/bin/kompile
     * 6. Project build output: kompile native binary in project root
     * 7. PATH lookup
     * <p>
     * For JVM mode, returns "java -jar /path/to/shaded.jar" as a single invocation
     * that the caller must handle (split into args or wrap in bash -c).
     */
    static String resolveKompileBinary() {
        // 1. /proc/self/exe — only useful for native images
        try {
            Path procSelf = Paths.get("/proc/self/exe");
            if (Files.exists(procSelf)) {
                Path resolved = procSelf.toRealPath();
                String name = resolved.getFileName().toString();
                // Only use if it's actually a kompile binary, not java/python
                if (name.equals("kompile") || name.startsWith("kompile-cli")) {
                    if (Files.isExecutable(resolved)) {
                        return resolved.toString();
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. ProcessHandle — check if we're running as a kompile native binary
        try {
            var cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) {
                Path candidate = Paths.get(cmd.get()).toAbsolutePath().normalize();
                String name = candidate.getFileName().toString();
                if (name.equals("kompile") || name.startsWith("kompile-cli")) {
                    return candidate.toString();
                }
            }
        } catch (Exception ignored) {}

        // 3. Shaded JAR — find it relative to where we're running from
        Path shadedJar = findShadedJar();
        if (shadedJar != null) {
            return "java -jar " + shadedJar.toAbsolutePath().normalize();
        }

        // 4. sun.java.command — if launched via "java -jar something.jar"
        String javaCommand = System.getProperty("sun.java.command");
        if (javaCommand != null && !javaCommand.isBlank()) {
            String firstToken = javaCommand.split("\\s+")[0];
            Path candidate = Paths.get(firstToken).toAbsolutePath().normalize();
            if (Files.exists(candidate) && candidate.toString().endsWith(".jar")) {
                return "java -jar " + candidate;
            }
        }

        // 5. Well-known install location
        Path homeBin = Paths.get(System.getProperty("user.home"), ".kompile", "bin", "kompile");
        if (Files.isExecutable(homeBin)) {
            return homeBin.toAbsolutePath().normalize().toString();
        }

        // 6. Project root native binary (development mode)
        try {
            // Walk up from CWD looking for a kompile native binary
            Path cwd = Paths.get("").toAbsolutePath();
            Path projectKompile = cwd.resolve("kompile");
            if (Files.isExecutable(projectKompile) && !Files.isDirectory(projectKompile)) {
                return projectKompile.toAbsolutePath().normalize().toString();
            }
            // Also check parent (in case CWD is a submodule)
            Path parentKompile = cwd.getParent() != null ? cwd.getParent().resolve("kompile") : null;
            if (parentKompile != null && Files.isExecutable(parentKompile) && !Files.isDirectory(parentKompile)) {
                return parentKompile.toAbsolutePath().normalize().toString();
            }
        } catch (Exception ignored) {}

        // 7. Check PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path candidate = Paths.get(dir, "kompile");
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }

        // Last resort — will likely fail, but at least gives a clear error in the terminal
        return "kompile";
    }

    /**
     * Search for the shaded CLI jar in typical locations.
     */
    private static Path findShadedJar() {
        // Check relative to CWD (project root or kompile-cli dir)
        String[] searchDirs = {
                "kompile-cli/target",
                "target",
                "../kompile-cli/target",
        };
        for (String dir : searchDirs) {
            try {
                Path dirPath = Paths.get(dir);
                if (Files.isDirectory(dirPath)) {
                    try (var stream = Files.list(dirPath)) {
                        var match = stream
                                .filter(p -> p.getFileName().toString().matches("kompile-cli-.*-shaded\\.jar"))
                                .findFirst();
                        if (match.isPresent()) {
                            return match.get();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private TerminalConfig overrideTerminalConfig(String terminal) {
        TerminalConfig config = new TerminalConfig();
        config.setTerminalCommand(terminal);
        return config;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private static String shortenPath(String path, int maxLen) {
        if (path == null || path.isEmpty()) return "";
        if (path.length() <= maxLen) return path;
        String home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        if (path.length() <= maxLen) return path;
        return "..." + path.substring(path.length() - maxLen + 3);
    }
}
