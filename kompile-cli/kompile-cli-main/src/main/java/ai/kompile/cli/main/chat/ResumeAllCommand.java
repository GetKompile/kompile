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

import ai.kompile.utils.StringUtils;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
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
 *   kompile resume-all                      # confirm all recent sessions or pick individual sessions
 *   kompile resume-all --yes                # resume recent sessions without prompting
 *   kompile resume-all --all                # resume every resumable session
 *   kompile resume-all --recent 5           # resume only the 5 most recent sessions
 *   kompile resume-all --active-within 30    # resume every session active in the last 30 minutes
 *   kompile resume-all --agent claude       # resume only claude sessions
 *   kompile resume-all --project /path      # resume sessions for a specific project
 *   kompile resume-all --dry-run            # show what would be resumed
 *   kompile resume-all --list               # list the sessions this invocation would resume
 *   kompile resume-all --set-recent 20      # persist the default recent-session limit
 *   kompile resume-all --set-terminal kitty # configure terminal emulator
 *   kompile resume-all --prune 30           # remove sessions older than 30 days
 * </pre>
 */
@CommandLine.Command(
        name = "resume-all",
        description = "Resume recently tracked agent sessions in new terminal windows " +
                "(defaults to the " + ResumeConfig.DEFAULT_RECENT_SESSIONS + " most recent; " +
                "use --all for every resumable session)",
        mixinStandardHelpOptions = true
)
public class ResumeAllCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Filter by agent name (e.g., claude, codex)")
    private String filterAgent;

    @CommandLine.Option(names = {"--project", "-p"}, description = "Filter by project directory")
    private String filterProject;

    @CommandLine.Option(names = {"--dry-run", "-n"}, description = "Show what would be resumed without launching", defaultValue = "false")
    private boolean dryRun;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List the resumable sessions this invocation would launch", defaultValue = "false")
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

    @CommandLine.Option(names = {"--recent", "-r"}, description = "Resume only the N most recent sessions " +
            "(default: the configured limit, " + ResumeConfig.DEFAULT_RECENT_SESSIONS + " unless changed via --set-recent)")
    private Integer recentCount;

    @CommandLine.Option(names = {"--all"}, description = "Resume every resumable session, ignoring the recent limit", defaultValue = "false")
    private boolean resumeAll;

    @CommandLine.Option(names = {"--active-within"}, paramLabel = "MINUTES",
            description = "Resume sessions active within the last N minutes; ignores the configured " +
                    "recent limit; combine with --recent to cap matching sessions")
    private Integer activeWithinMinutes;

    @CommandLine.Option(names = {"--set-recent"}, description = "Persist the default number of recent sessions " +
            "to ~/.kompile/config/resume.json and exit")
    private Integer setRecentCount;

    @CommandLine.Option(names = {"--unlock"}, description = "Force one stuck session (kompile or native session ID) " +
            "back to resumable and exit — workaround for bad/corrupt shutdowns")
    private String unlockSessionId;

    @CommandLine.Option(names = {"--unlock-all"}, description = "Force every stuck session (abandoned resume claims " +
            "and dead-PID rows) back to resumable and exit; live sessions are never touched", defaultValue = "false")
    private boolean unlockAll;

    @CommandLine.Option(names = {"--yes", "-y"},
            description = "Resume the matching sessions without confirmation (for unattended use)")
    private boolean assumeYes;

    private BiFunction<List<String>, String, String> sessionPrompt;
    private Instant activeCutoff;

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

    /**
     * Execute the same command from an interactive chat surface. Inline chat
     * options intentionally use the CLI's normal whitespace-separated syntax.
     */
    public static int executeInline(String args) {
        return executeInline(args, null);
    }

    /** Borrow the owning surface's input reader rather than competing for stdin. */
    public static int executeInline(String args, BiFunction<List<String>, String, String> prompt) {
        List<String> argv = tokenizeInlineArgs(args);
        ResumeAllCommand command = new ResumeAllCommand();
        command.sessionPrompt = prompt;
        return new CommandLine(command).execute(argv.toArray(String[]::new));
    }

    static List<String> tokenizeInlineArgs(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
            } else if (ch == '\\' && !singleQuoted) {
                escaping = true;
            } else if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (Character.isWhitespace(ch) && !singleQuoted && !doubleQuoted) {
                addInlineToken(result, current);
            } else {
                current.append(ch);
            }
        }
        if (escaping) current.append('\\');
        if (singleQuoted || doubleQuoted) {
            throw new IllegalArgumentException("Unterminated quote in resume-all options");
        }
        addInlineToken(result, current);
        return List.copyOf(result);
    }

    private static void addInlineToken(List<String> result, StringBuilder current) {
        if (current.length() == 0) return;
        result.add(current.toString());
        current.setLength(0);
    }

    @Override
    public Integer call() {
        if (recentCount != null && recentCount <= 0) {
            System.out.println(RED + "--recent must be a positive number" + RESET);
            return 1;
        }
        if (activeWithinMinutes != null && activeWithinMinutes <= 0) {
            System.out.println(RED + "--active-within must be a positive number" + RESET);
            return 1;
        }
        if (resumeAll && recentCount != null) {
            System.out.println(RED + "--all and --recent cannot be combined" + RESET);
            return 1;
        }
        // Handle terminal configuration first
        if (setTerminal != null) {
            return configureTerminal();
        }

        // Handle recent-limit configuration
        if (setRecentCount != null) {
            return configureRecentLimit();
        }

        // Handle prune
        if (pruneDays != null) {
            return pruneOldSessions();
        }

        // Handle status
        if (showStatus) {
            return showTerminalStatus();
        }

        // Handle lock repair (bad-shutdown workaround)
        if (unlockAll) {
            return unlockAllSessions();
        }
        if (unlockSessionId != null) {
            return unlockSession(unlockSessionId);
        }

        if (activeWithinMinutes != null) {
            activeCutoff = Instant.now().minus(Duration.ofMinutes(activeWithinMinutes));
        }

        // Load registry. Selection paths refresh status before applying filters;
        // refresh preserves or infers real activity timestamps.
        SessionRegistry registry = SessionRegistry.load();

        // Handle list
        if (listOnly) {
            return listSessions(registry);
        }

        // Main flow: resume sessions
        return resumeSessions(registry);
    }

    // ── Configure recent limit ────────────────────────────────────────────

    private int configureRecentLimit() {
        if (setRecentCount <= 0) {
            System.out.println(RED + "--set-recent must be a positive number" + RESET);
            return 1;
        }
        ResumeConfig config = ResumeConfig.load();
        config.setRecentSessions(setRecentCount);
        if (!config.save()) {
            System.out.println(RED + "Could not persist the recent-session limit" + RESET);
            return 1;
        }
        System.out.println(GREEN + "Default recent-session limit set to " + config.getRecentSessions()
                + DIM + " (~/.kompile/config/resume.json)" + RESET);
        return 0;
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

    // ── Lock repair ─────────────────────────────────────────────────────────

    private int unlockSession(String sessionId) {
        try {
            boolean cleared = SessionRegistry.load().clearResumeLock(sessionId);
            if (cleared) {
                System.out.println(GREEN + "Cleared stuck lock for session: " + sessionId + RESET);
                System.out.println(DIM + "  Resume with: kompile resume --session-id " + sessionId + RESET);
                return 0;
            }
            System.out.println(RED + "No tracked session matches: " + sessionId + RESET);
            return 1;
        } catch (Exception e) {
            System.out.println(RED + "Could not clear lock: " + e.getMessage() + RESET);
            return 1;
        }
    }

    private int unlockAllSessions() {
        try {
            int repaired = SessionRegistry.load().clearAllResumeLocks();
            System.out.println(repaired > 0
                    ? GREEN + "Cleared " + repaired + " stuck session"
                            + (repaired > 1 ? "s" : "") + RESET
                    : DIM + "No stuck sessions found" + RESET);
            return 0;
        } catch (Exception e) {
            System.out.println(RED + "Could not clear locks: " + e.getMessage() + RESET);
            return 1;
        }
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
        long resuming = registry.getAll().stream().filter(e -> "resuming".equals(e.getStatus())).count();

        System.out.println();
        System.out.println(BOLD + "Session Registry" + RESET);
        System.out.println("  Total:     " + total);
        System.out.println("  Running:   " + running);
        System.out.println("  Resuming:  " + resuming);
        System.out.println("  Resumable: " + resumable);
        return 0;
    }

    // ── List sessions ───────────────────────────────────────────────────────

    private int listSessions(SessionRegistry registry) {
        List<SessionEntry> entries = getFilteredEntries(registry.getResumable());

        // Match resumeSessions exactly: filter to exited/crash-detected sessions
        // first, then apply the recent limit. This makes --list a reliable preview.
        int limit = hasCountLimit() ? effectiveRecentLimit() : Integer.MAX_VALUE;
        boolean limited = hasCountLimit() && entries.size() > limit;
        if (limited) {
            entries = entries.subList(0, limit);
        }

        if (entries.isEmpty()) {
            System.out.println(DIM + "No resumable sessions" +
                    (filterAgent != null ? " for agent '" + filterAgent + "'" : "") +
                    (filterProject != null ? " in project '" + filterProject + "'" : "") +
                    RESET);
            return 0;
        }

        System.out.println(BOLD + "Resumable Sessions" + RESET +
                DIM + " (" + entries.size()
                        + (limited ? " most recent" : "") + ")" + RESET);
        System.out.println();
        System.out.printf("  %-12s %-10s %-12s %-18s %s%n",
                "SESSION", "AGENT", "STATUS", "STARTED", "PROJECT");
        System.out.println("  " + "─".repeat(80));

        for (SessionEntry entry : entries) {
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
                    StringUtils.truncate(entry.getKompileSessionId(), 12),
                    displayAgent(entry),
                    statusColor, status, RESET,
                    started,
                    projectShort);

            // Show conversation ID and title on second line if present
            boolean hasConvId = entry.getConversationId() != null && !entry.getConversationId().isEmpty();
            boolean hasTitle = entry.getTitle() != null && !entry.getTitle().isEmpty();
            if (hasConvId || hasTitle) {
                String detail = DIM + "  ";
                if (hasConvId) detail += "conv:" + StringUtils.truncate(entry.getConversationId(), 20);
                if (hasTitle) detail += (hasConvId ? "  " : "") + entry.getTitle();
                System.out.println(detail + RESET);
            }
        }

        System.out.println();
        System.out.println(DIM + "  " + entries.size() + " resumable" + RESET);
        return 0;
    }

    // ── Resume sessions ─────────────────────────────────────────────────────

    private int resumeSessions(SessionRegistry registry) {
        int limit = hasCountLimit() ? effectiveRecentLimit() : Integer.MAX_VALUE;
        String claimId = dryRun ? null : UUID.randomUUID().toString();
        List<SessionEntry> resumable = getFilteredEntries(registry.getResumable());
        if (resumable.size() > limit) {
            resumable = resumable.subList(0, limit);
        }

        if (resumable.isEmpty()) {
            System.out.println(DIM + "No resumable sessions found" +
                    (filterAgent != null ? " for agent '" + filterAgent + "'" : "") +
                    (filterProject != null ? " in project '" + filterProject + "'" : "") +
                    RESET);
            System.out.println(DIM + "  Start a session with: kompile chat" + RESET);
            return 0;
        }

        if (!dryRun) {
            if (!assumeYes) {
                BiFunction<List<String>, String, String> prompt = sessionPrompt;
                if (prompt == null) {
                    java.io.Console console = System.console();
                    if (console == null) {
                        sessionMenu(resumable).forEach(System.out::println);
                        System.out.println("No interactive console. Use --yes to resume all matching sessions, or --list to preview.");
                        return 1;
                    }
                    prompt = (lines, question) -> {
                        lines.forEach(console.writer()::println);
                        console.flush();
                        return console.readLine("%s", question);
                    };
                }
                try {
                    resumable = selectSessions(resumable, prompt);
                } catch (UserInterruptException | EndOfFileException ignored) {
                    resumable = List.of();
                }
                if (resumable.isEmpty()) {
                    System.out.println("Resume cancelled — no sessions launched.");
                    return 0;
                }
            }
            // Claim only the chosen snapshot, after the user has finished deciding.
            // Concurrent launches must never substitute unseen rows.
            Set<String> selected = resumable.stream().map(SessionRegistry::resumeIdentity)
                    .collect(Collectors.toSet());
            resumable = registry.claimResumable(
                    entry -> selected.contains(SessionRegistry.resumeIdentity(entry)) && matchesFilters(entry),
                    newestFirst(), selected.size(), claimId);
            if (resumable.isEmpty()) {
                System.out.println("The selected sessions are no longer resumable. No sessions launched.");
                return 0;
            }
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
        Set<String> launchedClaims = new HashSet<>();

        for (SessionEntry entry : resumable) {
            String sessionLabel = displayAgent(entry) + " @ " + shortenPath(entry.getProjectDirectory(), 40);
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
                launchedClaims.add(SessionRegistry.resumeIdentity(entry));
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
            } catch (RuntimeException e) {
                System.out.println("  " + RED + "Failed" + RESET + " " + sessionLabel
                        + ": " + e.getMessage());
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (claimId != null) {
            for (SessionEntry entry : resumable) {
                String resumeIdentity = SessionRegistry.resumeIdentity(entry);
                if (!launchedClaims.contains(resumeIdentity)) {
                    registry.releaseResumeClaim(resumeIdentity, claimId);
                }
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

    static List<SessionEntry> selectSessions(List<SessionEntry> entries,
            BiFunction<List<String>, String, String> prompt) {
        if (entries.isEmpty()) return List.of();
        List<String> message = List.of("Found " + entries.size() + " resumable sessions.");
        while (true) {
            String answer = prompt.apply(message, "Resume all these sessions? [y/N] (q cancels): ");
            if (answer == null || isCancel(answer)) return List.of();
            answer = answer.trim();
            if (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) return List.copyOf(entries);
            if (answer.isEmpty() || answer.equalsIgnoreCase("n") || answer.equalsIgnoreCase("no")) break;
            message = List.of("Please answer yes, no, or q to cancel.");
        }
        List<String> menu = sessionMenu(entries);
        while (true) {
            String answer = prompt.apply(menu, "Pick session numbers or UUIDs (comma-separated; Enter cancels): ");
            if (answer == null || answer.isBlank() || isCancel(answer)) return List.of();
            Set<Integer> selected = new HashSet<>();
            boolean valid = true;
            for (String token : answer.trim().split("[,\\s]+")) {
                int index = -1;
                for (int i = 0; i < entries.size(); i++) {
                    if (sessionUuid(entries.get(i)).equalsIgnoreCase(token)) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    try {
                        index = Integer.parseInt(token) - 1;
                    } catch (NumberFormatException ignored) { }
                }
                if (index < 0 || index >= entries.size()) {
                    valid = false;
                    break;
                }
                selected.add(index);
            }
            if (valid && !selected.isEmpty()) {
                List<SessionEntry> result = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    if (selected.contains(i)) result.add(entries.get(i));
                }
                return List.copyOf(result);
            }
            menu = new ArrayList<>(sessionMenu(entries));
            menu.add("Invalid selection. Choose listed numbers or full UUIDs; nothing has been launched.");
        }
    }

    private static boolean isCancel(String value) {
        return value.trim().equalsIgnoreCase("q") || value.trim().equalsIgnoreCase("cancel");
    }

    private static String sessionUuid(SessionEntry entry) {
        return entry.getKompileSessionId() != null && !entry.getKompileSessionId().isBlank()
                ? entry.getKompileSessionId() : entry.getConversationId();
    }

    private static List<String> sessionMenu(List<SessionEntry> entries) {
        List<String> lines = new ArrayList<>();
        lines.add("Sessions found (UUID | title | last active timestamp):");
        for (int i = 0; i < entries.size(); i++) {
            SessionEntry entry = entries.get(i);
            String title = entry.getTitle() == null || entry.getTitle().isBlank()
                    ? "(untitled)" : entry.getTitle();
            Instant timestamp = parseInstant(entry.getEndedAt());
            if (timestamp == null) timestamp = parseInstant(entry.getStartedAt());
            lines.add((i + 1) + ". " + sessionUuid(entry) + " | "
                    + title.replaceAll("[\\p{Cntrl}]", " ") + " | "
                    + (timestamp == null ? "unknown" : timestamp.toString()));
        }
        return lines;
    }

    // ── Command building ────────────────────────────────────────────────────

    /**
     * Build the resume command for a session entry.
     * Uses {@code kompile resume --session-id <id>} and lets ResumeCommand's
     * default {@code auto} target resolve standard versus provider transcripts.
     * <p>
     * Handles both native binary ("kompile") and JVM mode ("java -jar /path/to.jar")
     * by splitting the kompileBin string on spaces when it contains multiple tokens.
     */
    private List<String> buildResumeCommand(String kompileBin, SessionEntry entry) {
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

        // Prefer the Kompile wrapper ID: ResumeCommand uses its recorded source and
        // native-session metadata to choose the correct provider. Passing only a
        // native ID loses that association and can incorrectly fall back to Claude.
        String sessionId = entry.getKompileSessionId() != null
                && !entry.getKompileSessionId().isBlank()
                ? entry.getKompileSessionId()
                : entry.getConversationId();
        cmd.add("--session-id");
        cmd.add(sessionId);

        return cmd;
    }

    // ── Filtering ───────────────────────────────────────────────────────────

    private List<SessionEntry> getFilteredEntries(List<SessionEntry> entries) {
        return entries.stream()
                .filter(this::matchesFilters)
                .sorted(newestFirst())
                .collect(Collectors.toList());
    }

    private boolean matchesFilters(SessionEntry entry) {
        return (filterAgent == null || filterAgent.equalsIgnoreCase(displayAgent(entry)))
                && (filterProject == null
                || entry.getProjectDirectory().startsWith(filterProject))
                && (activeCutoff == null || wasActiveAtOrAfter(entry, activeCutoff));
    }

    /**
     * A session was active in the window when its recorded end falls inside it.
     * Legacy/crash rows without a usable end timestamp fall back to their start.
     */
    static boolean wasActiveAtOrAfter(SessionEntry entry, Instant cutoff) {
        if (entry == null || cutoff == null) return false;
        Instant lastActive = parseInstant(entry.getEndedAt());
        if (lastActive == null) {
            lastActive = parseInstant(entry.getStartedAt());
        }
        return lastActive != null && !lastActive.isBefore(cutoff);
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Comparator<SessionEntry> newestFirst() {
        return Comparator.comparing(ResumeAllCommand::startedAtSortKey).reversed();
    }

    private static Instant startedAtSortKey(SessionEntry entry) {
        try {
            return Instant.parse(entry.getStartedAt());
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private static String displayAgent(SessionEntry entry) {
        String agent = entry == null ? null : entry.getAgent();
        return agent == null || agent.isBlank() ? "kompile" : agent;
    }

    /**
     * The effective recent-session limit for this invocation: an explicit
     * --recent wins, otherwise the persisted ResumeConfig value (default 10).
     * --all bypasses the limit entirely in the callers.
     */
    private int effectiveRecentLimit() {
        if (recentCount != null) {
            return recentCount;
        }
        return ResumeConfig.load().getRecentSessions();
    }

    /**
     * An activity window replaces the configured default count. Callers may still
     * combine it with an explicit --recent cap; --all and --recent are rejected.
     */
    private boolean hasCountLimit() {
        return !resumeAll && (activeWithinMinutes == null || recentCount != null);
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
