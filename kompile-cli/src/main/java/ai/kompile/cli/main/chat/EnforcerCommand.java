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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.enforcer.DiffPatternEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerDiffArchive;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerInitCommand;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerMonitorCommand;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerConversationWindow;
import ai.kompile.cli.main.chat.enforcer.EnforcerRealtimeMonitor;
import ai.kompile.cli.main.chat.enforcer.EnforcerResult;
import ai.kompile.cli.main.chat.enforcer.EnforcerRuntimePolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerService;
import ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.KeywordRealtimeMonitor;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Interactive chat mode that runs a designated external agent behind an enforcer judge.
 * Each user turn is sent to the subordinate agent, evaluated against user rules, and
 * corrected or stopped before the turn is accepted.
 */
@CommandLine.Command(
        name = "enforcer",
        description = "Enforcer chat mode: launch a designated agent and force rule compliance with a judge loop",
        mixinStandardHelpOptions = true,
        subcommands = { EnforcerMonitorCommand.class, EnforcerInitCommand.class }
)
public class EnforcerCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Designated subordinate agent", defaultValue = "claude")
    String agent;

    @CommandLine.Option(names = {"--working-dir", "-d"}, description = "Working directory for the agent", defaultValue = ".")
    String workingDir;

    @CommandLine.Option(names = {"--rules"}, description = "Inline enforcer rules")
    String rules;

    @CommandLine.Option(names = {"--rule-file"}, description = "Path to a file containing enforcer rules")
    String ruleFile;

    @CommandLine.Option(names = {"--max-corrections"}, description = "Maximum correction attempts after a violation", defaultValue = "2")
    int maxCorrections;

    @CommandLine.Option(names = {"--skip-permissions"}, description = "Skip subordinate agent permission prompts", defaultValue = "true")
    boolean skipPermissions;

    @CommandLine.Option(names = {"--inject-tools"}, description = "Inject kompile MCP tools into the subordinate agent", defaultValue = "true")
    boolean injectTools;

    @CommandLine.Option(names = {"--inject-skills"}, description = "Install configured skills into the subordinate agent", defaultValue = "true")
    boolean injectSkills;

    @CommandLine.Option(names = {"--url", "-u"}, description = "Kompile-app base URL for MCP tools", defaultValue = "")
    String kompileUrl;

    @CommandLine.Option(names = {"--mcp-port"}, description = "Port for embedded MCP server (0 = auto-detect)", defaultValue = "0")
    int mcpPort;

    @CommandLine.Option(names = {"--judge-mode"}, description = "Judge backend mode: auto, remote, local, auto-server")
    String judgeMode;

    @CommandLine.Option(names = {"--judge-provider"}, description = "Judge provider override, e.g. anthropic, openai, ollama")
    String judgeProvider;

    @CommandLine.Option(names = {"--judge-model"}, description = "Judge model override")
    String judgeModel;

    @CommandLine.Option(names = {"--judge-api-key"}, description = "Judge API key override")
    String judgeApiKey;

    @CommandLine.Option(names = {"--judge-base-url"}, description = "Judge API base URL override")
    String judgeBaseUrl;

    @CommandLine.Option(names = {"--print", "-P"}, description = "Single-shot prompt to enforce and print")
    String printPrompt;

    @CommandLine.Option(names = {"--keyword-mode"}, description = "Use keyword-based evaluator instead of LLM judge", defaultValue = "false")
    boolean keywordMode;

    @CommandLine.Option(names = {"--archive-diffs"}, description = "Archive file diffs per turn for rollback on violation", defaultValue = "true")
    boolean archiveDiffs;

    @CommandLine.Option(names = {"--purge-archive"}, description = "Purge all enforcer archives and exit", defaultValue = "false")
    boolean purgeArchive;

    @CommandLine.Option(names = {"--purge-older-than"}, description = "Purge archives older than N hours", defaultValue = "0")
    int purgeOlderThan;

    @CommandLine.Option(names = {"--diff-patterns"}, description = "Path to diff pattern rules file (banned code patterns checked against diffs)")
    String diffPatternsFile;

    @CommandLine.Option(names = {"--bootstrap-patterns"}, description = "Bootstrap diff patterns: describe rules in natural language, LLM generates pattern file")
    String bootstrapPatterns;

    @CommandLine.Option(names = {"--bootstrap-language"}, description = "Language for pattern bootstrap (e.g., java, typescript)", defaultValue = "java")
    String bootstrapLanguage;

    @CommandLine.Option(names = {"--bootstrap-output"}, description = "Output file for bootstrapped patterns", defaultValue = ".kompile/enforcer-diff-patterns.json")
    String bootstrapOutput;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Integer call() {
        Path wd = Path.of(workingDir).toAbsolutePath().normalize();

        // Handle purge commands early (no terminal needed)
        if (purgeArchive) {
            try {
                EnforcerDiffArchive.purgeAll(wd);
                System.out.println("All enforcer archives purged.");
                return 0;
            } catch (IOException e) {
                System.err.println("Failed to purge archives: " + e.getMessage());
                return 1;
            }
        }
        if (purgeOlderThan > 0) {
            try {
                int count = EnforcerDiffArchive.purgeOlderThan(wd, purgeOlderThan);
                System.out.println("Purged " + count + " archive(s) older than " + purgeOlderThan + " hours.");
                return 0;
            } catch (IOException e) {
                System.err.println("Failed to purge archives: " + e.getMessage());
                return 1;
            }
        }

        // Bootstrap diff patterns via LLM (one-shot, then exit)
        if (bootstrapPatterns != null && !bootstrapPatterns.isBlank()) {
            return bootstrapDiffPatterns(wd);
        }

        // Load per-project enforcer config (provides defaults for unset CLI options)
        EnforcerConfig projectConfig = EnforcerConfig.load(wd);
        if (projectConfig != null) {
            applyProjectConfig(projectConfig, wd);
        }

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            TerminalRenderer renderer = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(renderer);

            String resolvedRules = resolveRules(reader, wd);
            if (resolvedRules == null || resolvedRules.isBlank()) {
                System.err.println("Enforcer rules are required.");
                return 1;
            }

            HarnessConfig harnessConfig = loadHarnessConfig();
            EnforcerPolicy policy = new EnforcerPolicy(resolvedRules, maxCorrections, false);

            // Choose evaluator: keyword mode (no LLM) or full LLM judge
            EnforcerEvaluator evaluator;
            EnforcerJudge judge = null;
            if (keywordMode) {
                KeywordEnforcerEvaluator kwEval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);
                if (!kwEval.isAvailable()) {
                    System.err.println("No keyword rules parsed. Use BAN:/STOP: prefixes or JSON format.");
                    return 1;
                }
                evaluator = kwEval;
            } else {
                judge = new EnforcerJudge(harnessConfig, objectMapper);
                if (!judge.isAvailable()) {
                    System.err.println("No enforcer judge backend is available.");
                    System.err.println("Configure ~/.kompile/harness-config.json or pass --judge-provider/--judge-model.");
                    System.err.println("Tip: use --keyword-mode for a simple keyword-based evaluator with no LLM.");
                    return 1;
                }
                evaluator = judge;
            }

            EnforcerService service = new EnforcerService(evaluator);
            EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(wd, policy, harnessConfig, objectMapper);
            EnforcerConversationWindow conversationWindow =
                    new EnforcerConversationWindow(runtimePolicy.getContextFile(), objectMapper);

            SubprocessAgentRunner runner = new SubprocessAgentRunner(
                    agent, wd.toString(), skipPermissions, injectTools, kompileUrl, mcpPort,
                    null, renderer, ascii);
            runner.setExtraEnvironment(runtimePolicy.toEnvironment());
            runner.setInputProvider(prompt -> {
                try {
                    return reader.readLine(prompt);
                } catch (Exception e) {
                    return null;
                }
            });

            String sessionId = runtimePolicy.getSessionId();
            ChatHistory history = new ChatHistory(sessionId);
            ChatSessionMetrics metrics = new ChatSessionMetrics(sessionId);
            metrics.setAgentName(agent + " (enforced)");
            Instant started = Instant.now();

            // Initialize diff archive if enabled
            EnforcerDiffArchive diffArchive = null;
            if (archiveDiffs) {
                diffArchive = new EnforcerDiffArchive(sessionId, wd, objectMapper);
            }

            // Initialize diff pattern evaluator (checks banned code patterns in diffs)
            DiffPatternEvaluator diffPatternEvaluator = loadDiffPatternEvaluator(policy, wd);
            if (diffPatternEvaluator != null && diffPatternEvaluator.isAvailable()) {
                System.out.println(renderer.dim("[enforcer] diff pattern checker active: "
                        + diffPatternEvaluator.ruleCount() + " code patterns"));
            }

            history.open(resolvedRules, agent + " (enforcer)", false);

            runner.injectMcpTools();
            if (injectSkills) {
                runner.injectSkills();
            }

            // Register session with kompile-app server for web dashboard monitoring
            if (kompileUrl != null && !kompileUrl.isBlank()) {
                registerServerSession(sessionId, resolvedRules, evaluator.describe(), wd);
            }

            try {
                printWelcome(ascii, sessionId, evaluator.describe(), policy, diffArchive != null);

                if (printPrompt != null && !printPrompt.isBlank()) {
                    EnforcerResult result = runEnforcedTurn(service, judge, policy, runner, history,
                            metrics, conversationWindow, diffArchive, diffPatternEvaluator, printPrompt);
                    printResultSummary(renderer, result);
                    return result.getStatus() == EnforcerResult.Status.ERROR
                            || result.getStatus() == EnforcerResult.Status.UNAVAILABLE ? 1 : 0;
                }

                final EnforcerDiffArchive archive = diffArchive;
                while (true) {
                    String line;
                    try {
                        line = reader.readLine(buildPrompt());
                    } catch (UserInterruptException | EndOfFileException e) {
                        break;
                    }

                    if (line == null || line.isBlank()) {
                        continue;
                    }

                    String trimmed = line.trim();
                    if (trimmed.startsWith("/")) {
                        String action = handleSlashCommand(trimmed, reader, ascii, renderer,
                                policy, runner, sessionId, evaluator.describe(), archive);
                        if ("quit".equals(action)) {
                            break;
                        }
                        continue;
                    }

                    EnforcerResult result = runEnforcedTurn(service, judge, policy, runner, history,
                            metrics, conversationWindow, archive, diffPatternEvaluator, trimmed);
                    printResultSummary(renderer, result);
                }
            } finally {
                runner.setRealtimeMonitor(null);
                runner.cleanup();
                runtimePolicy.cleanup();
                history.close();
                renderer.resetTerminalTitle();
                System.out.println();
                System.out.println(renderer.dim("Enforcer session " + sessionId
                        + " ended after " + metrics.formatDuration(Duration.between(started, Instant.now()))
                        + ". Transcript: " + history.getTranscriptFile()));
                if (diffArchive != null) {
                    System.out.println(renderer.dim("Diff archive: " + diffArchive.getArchiveRoot()));
                }
                if (judge != null) {
                    judge.close();
                }
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error running enforcer mode: " + e.getMessage());
            return 1;
        }
    }

    private EnforcerResult runEnforcedTurn(EnforcerService service, EnforcerJudge judge,
                                           EnforcerPolicy policy,
                                           SubprocessAgentRunner runner, ChatHistory history,
                                           ChatSessionMetrics metrics,
                                           EnforcerConversationWindow conversationWindow,
                                           EnforcerDiffArchive diffArchive,
                                           DiffPatternEvaluator diffPatternEvaluator,
                                           String prompt) {
        System.out.println();
        if (conversationWindow != null) {
            conversationWindow.addUserMessage(prompt);
        }
        TerminalRenderer renderer = new TerminalRenderer();

        // Install realtime monitor: LLM-based (EnforcerJudge) or keyword-based
        SubprocessAgentRunner.RealtimeMonitor rtMonitor;
        if (judge != null) {
            rtMonitor = new EnforcerRealtimeMonitor(judge, policy, prompt, conversationWindow);
        } else if (keywordMode) {
            KeywordEnforcerEvaluator kwEval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);
            rtMonitor = new KeywordRealtimeMonitor(kwEval, policy, conversationWindow);
        } else {
            rtMonitor = null;
        }
        if (rtMonitor != null) {
            runner.setRealtimeMonitor(rtMonitor);
        }

        // Begin diff archive snapshot before the agent runs
        EnforcerDiffArchive.TurnSnapshot snapshot = null;
        if (diffArchive != null) {
            try {
                snapshot = diffArchive.beginTurn();
            } catch (IOException e) {
                System.err.println(renderer.dim("[enforcer] could not begin diff snapshot: " + e.getMessage()));
            }
        }

        int[] attemptCounter = {0};
        EnforcerResult result;
        try {
            result = service.enforce(prompt, policy,
                    conversationWindow != null ? conversationWindow::snapshot : null,
                    agentPrompt -> {
                        attemptCounter[0]++;
                        if (attemptCounter[0] > 1) {
                            System.out.println(renderer.yellow(
                                    "[enforcer] violation detected, sending correction (attempt "
                                            + attemptCounter[0] + ")"));
                        }
                        String output = runner.runMessage(agentPrompt, history, metrics);
                        if (conversationWindow != null) {
                            conversationWindow.finishAssistantMessage(output);
                        }
                        return output;
                    });
        } finally {
            if (rtMonitor != null) {
                runner.setRealtimeMonitor(null);
                if (rtMonitor instanceof AutoCloseable closeable) {
                    try { closeable.close(); } catch (Exception ignored) {}
                }
            }
        }

        // Complete diff archive snapshot
        boolean violated = !result.isAccepted();
        if (diffArchive != null && snapshot != null) {
            try {
                diffArchive.completeTurn(snapshot, violated);
                if (violated) {
                    System.out.println(renderer.yellow("[enforcer] changes archived for rollback: "
                            + snapshot.getTurnId()));
                }
            } catch (IOException e) {
                System.err.println(renderer.dim("[enforcer] could not complete diff snapshot: " + e.getMessage()));
            }

            // Run diff pattern evaluation on captured diff (no LLM — pure regex/keyword)
            if (diffPatternEvaluator != null && diffPatternEvaluator.isAvailable() && result.isAccepted()) {
                try {
                    String turnDiff = diffArchive.getTurnDiff(snapshot.getTurnId());
                    if (turnDiff != null && !turnDiff.isBlank()) {
                        DiffPatternEvaluator.DiffEvaluation diffEval = diffPatternEvaluator.evaluate(turnDiff);
                        if (!diffEval.passed()) {
                            // Diff pattern violation — override the result
                            violated = true;
                            // Update the turn metadata to reflect violation
                            diffArchive.completeTurn(snapshot, true);

                            System.out.println(renderer.yellow("[enforcer] code pattern violations in diff:"));
                            for (DiffPatternEvaluator.DiffViolation v : diffEval.violations()) {
                                System.out.println(renderer.yellow("  - " + v.filePath() + ":"
                                        + v.lineNumber() + " — " + v.rule().getDescription()));
                            }

                            // Auto-rollback the violating turn
                            EnforcerDiffArchive.RollbackResult rr = diffArchive.rollback(snapshot.getTurnId());
                            if (rr.success()) {
                                System.out.println(renderer.yellow("[enforcer] rolled back changes ("
                                        + rr.restoredFiles().size() + " files restored)"));
                            }

                            // Send correction to agent for retry
                            if (diffEval.correctionPrompt() != null) {
                                System.out.println(renderer.yellow("[enforcer] sending correction to agent..."));
                                String correctedOutput = runner.runMessage(
                                        diffEval.correctionPrompt(), history, metrics);
                                if (conversationWindow != null) {
                                    conversationWindow.finishAssistantMessage(correctedOutput);
                                }
                            }

                            // Build a blocked result
                            StringBuilder violationMsg = new StringBuilder();
                            for (DiffPatternEvaluator.DiffViolation v : diffEval.violations()) {
                                violationMsg.append(v.filePath()).append(":").append(v.lineNumber())
                                        .append(" — ").append(v.rule().getDescription()).append("; ");
                            }
                            result = EnforcerResult.blocked("",
                                    result.getAttempts(),
                                    "Code pattern violations: " + violationMsg,
                                    "diff-pattern-evaluator");
                        }
                    }
                } catch (IOException e) {
                    System.err.println(renderer.dim("[enforcer] diff pattern check failed: " + e.getMessage()));
                }
            }
        }

        if (!result.isAccepted()) {
            history.logSystem("Enforcer " + result.getStatus() + ": " + result.getMessage());
        }
        return result;
    }

    private String resolveRules(LineReader reader, Path wd) throws IOException {
        String resolved = EnforcerPolicy.resolveRules(rules, ruleFile, wd);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }

        System.out.println();
        System.out.println("Enter enforcer rules. Finish with a single '.' line.");
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line = reader.readLine("rules> ");
            if (line == null) {
                break;
            }
            if (".".equals(line.trim())) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private HarnessConfig loadHarnessConfig() {
        HarnessConfig config = HarnessConfig.load(objectMapper);
        if (judgeMode != null && !judgeMode.isBlank()) {
            config.setJudgeMode(judgeMode);
        }
        if (judgeProvider != null && !judgeProvider.isBlank()) {
            config.setJudgeProvider(judgeProvider);
        }
        if (judgeModel != null && !judgeModel.isBlank()) {
            config.setJudgeModel(judgeModel);
        }
        if (judgeApiKey != null && !judgeApiKey.isBlank()) {
            config.setJudgeApiKey(judgeApiKey);
        }
        if (judgeBaseUrl != null && !judgeBaseUrl.isBlank()) {
            config.setJudgeBaseUrl(judgeBaseUrl);
        }
        return config;
    }

    private void printWelcome(AsciiRenderer ascii, String sessionId, String backend,
                              EnforcerPolicy policy, boolean archiveEnabled) {
        String body = "Session:   " + sessionId + "\n"
                + "Agent:     " + agent + "\n"
                + "Watcher:   enforcer\n"
                + "Judge:     " + backend + "\n"
                + "Retries:   " + policy.getMaxCorrections() + "\n"
                + "Archive:   " + (archiveEnabled ? "enabled (rollback on violation)" : "disabled") + "\n\n"
                + "The subordinate agent is evaluated after each turn. "
                + "Violations trigger correction attempts; stop decisions block the turn.\n\n"
                + "Slash commands: /help, /rules, /agent, /status, /archive, /rollback, /quit";
        System.out.println(ascii.panel("Kompile Enforcer", body));
    }

    private String handleSlashCommand(String command, LineReader reader, AsciiRenderer ascii,
                                      TerminalRenderer renderer, EnforcerPolicy policy,
                                      SubprocessAgentRunner runner, String sessionId,
                                      String backend, EnforcerDiffArchive diffArchive) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.equals("/quit") || lower.equals("/exit")) {
            return "quit";
        }
        if (lower.equals("/help")) {
            String body = "/rules      Show active enforcer rules\n"
                    + "/agent      Switch subordinate agent for the next turn\n"
                    + "/status     Show current agent, judge, and correction limit\n"
                    + "/archive    List archived turns with violation status\n"
                    + "/rollback   Rollback all violated turns (restore original files)\n"
                    + "/rollback <turn-id>  Rollback a specific turn\n"
                    + "/diff <turn-id>      Show the diff for a specific turn\n"
                    + "/purge      Purge this session's archive\n"
                    + "/quit       Exit enforcer chat";
            System.out.println(ascii.panel("Enforcer Help", body));
            return "continue";
        }
        if (lower.equals("/rules")) {
            System.out.println(ascii.panel("Enforcer Rules", policy.getRules()));
            return "continue";
        }
        if (lower.equals("/status")) {
            String body = "Session: " + sessionId + "\n"
                    + "Agent:   " + runner.getAgentDisplayName() + "\n"
                    + "Watcher: enforcer\n"
                    + "Judge:   " + backend + "\n"
                    + "Retries: " + policy.getMaxCorrections() + "\n"
                    + "Archive: " + (diffArchive != null ? "enabled" : "disabled");
            if (diffArchive != null) {
                body += "\nPath:    " + diffArchive.getArchiveRoot();
            }
            System.out.println(ascii.panel("Enforcer Status", body));
            return "continue";
        }
        if (lower.equals("/archive")) {
            if (diffArchive == null) {
                System.out.println(renderer.dim("Diff archiving is disabled. Use --archive-diffs to enable."));
                return "continue";
            }
            try {
                List<EnforcerDiffArchive.TurnMetadata> turns = diffArchive.listTurns();
                if (turns.isEmpty()) {
                    System.out.println(renderer.dim("No turns archived yet."));
                } else {
                    StringBuilder body = new StringBuilder();
                    for (EnforcerDiffArchive.TurnMetadata t : turns) {
                        String marker = t.violated() ? renderer.red("[VIOLATED]") : renderer.green("[OK]");
                        body.append(String.format("  %s  %-10s  %s  files: %d\n",
                                marker, t.turnId(), t.timestamp(), t.changedFiles().size()));
                    }
                    System.out.println(ascii.panel("Enforcer Archive", body.toString()));
                }
            } catch (IOException e) {
                System.out.println(renderer.red("Error listing archive: " + e.getMessage()));
            }
            return "continue";
        }
        if (lower.equals("/rollback")) {
            if (diffArchive == null) {
                System.out.println(renderer.dim("Diff archiving is disabled."));
                return "continue";
            }
            try {
                EnforcerDiffArchive.RollbackResult rr = diffArchive.rollbackViolations();
                if (rr.success()) {
                    System.out.println(renderer.green("[enforcer] " + rr.message()));
                    for (String f : rr.restoredFiles()) {
                        System.out.println(renderer.dim("  restored: " + f));
                    }
                } else {
                    System.out.println(renderer.dim(rr.message()));
                }
            } catch (IOException e) {
                System.out.println(renderer.red("Rollback failed: " + e.getMessage()));
            }
            return "continue";
        }
        if (lower.startsWith("/rollback ")) {
            if (diffArchive == null) {
                System.out.println(renderer.dim("Diff archiving is disabled."));
                return "continue";
            }
            String turnId = command.substring("/rollback ".length()).trim();
            try {
                EnforcerDiffArchive.RollbackResult rr = diffArchive.rollback(turnId);
                if (rr.success()) {
                    System.out.println(renderer.green("[enforcer] " + rr.message()));
                    for (String f : rr.restoredFiles()) {
                        System.out.println(renderer.dim("  restored: " + f));
                    }
                } else {
                    System.out.println(renderer.yellow(rr.message()));
                }
            } catch (IOException e) {
                System.out.println(renderer.red("Rollback failed: " + e.getMessage()));
            }
            return "continue";
        }
        if (lower.startsWith("/diff ")) {
            if (diffArchive == null) {
                System.out.println(renderer.dim("Diff archiving is disabled."));
                return "continue";
            }
            String turnId = command.substring("/diff ".length()).trim();
            try {
                String diff = diffArchive.getTurnDiff(turnId);
                if (diff == null || diff.isBlank()) {
                    System.out.println(renderer.dim("No diff found for " + turnId));
                } else {
                    System.out.println(ascii.panel("Diff: " + turnId, diff));
                }
            } catch (IOException e) {
                System.out.println(renderer.red("Error reading diff: " + e.getMessage()));
            }
            return "continue";
        }
        if (lower.equals("/purge")) {
            if (diffArchive == null) {
                System.out.println(renderer.dim("Diff archiving is disabled."));
                return "continue";
            }
            try {
                diffArchive.purge();
                System.out.println(renderer.dim("Archive purged for this session."));
            } catch (IOException e) {
                System.out.println(renderer.red("Purge failed: " + e.getMessage()));
            }
            return "continue";
        }
        if (lower.equals("/agent") || lower.startsWith("/agent ")) {
            String next = command.length() > 6 ? command.substring(6).trim() : "";
            if (next.isBlank()) {
                next = reader.readLine("agent> ");
            }
            if (next != null && !next.isBlank()) {
                runner.setAgent(next.trim());
                this.agent = next.trim();
                System.out.println(renderer.dim("Subordinate agent set to " + next.trim()));
            }
            return "continue";
        }
        System.out.println(renderer.yellow("Unknown command: " + command));
        return "continue";
    }

    private void printResultSummary(TerminalRenderer renderer, EnforcerResult result) {
        if (result == null) {
            return;
        }
        switch (result.getStatus()) {
            case ACCEPTED -> {
                if (result.getAttempts().size() > 1) {
                    System.out.println(renderer.dim("[enforcer] accepted after "
                            + result.getAttempts().size() + " attempts"));
                }
            }
            case BLOCKED -> {
                System.out.println(renderer.yellow("[enforcer] blocked turn: " + result.getMessage()));
                var attempts = result.getAttempts();
                if (!attempts.isEmpty() && attempts.get(attempts.size() - 1).decision() != null) {
                    var decision = attempts.get(attempts.size() - 1).decision();
                    for (String violation : decision.getViolations()) {
                        System.out.println(renderer.yellow("  - " + violation));
                    }
                }
            }
            case UNAVAILABLE, ERROR -> System.out.println(renderer.red("[enforcer] " + result.getMessage()));
        }
    }

    private String buildPrompt() {
        return "\033[36mkompile \033[0m\033[2m[enforcer:" + agent + "]\033[0m\033[36m> \033[0m";
    }

    /**
     * Apply per-project enforcer config as defaults for CLI options that weren't explicitly set.
     * CLI flags always take precedence over config file values.
     */
    private void applyProjectConfig(EnforcerConfig config, Path wd) {
        // Agent (only if CLI default wasn't overridden)
        if ("claude".equals(agent) && config.getAgent() != null) {
            agent = config.getAgent();
        }

        // Evaluation mode
        if (!keywordMode && config.isKeywordMode()) {
            keywordMode = true;
        }

        // Max corrections (CLI default is 2, same as config default)
        if (maxCorrections == 2 && config.getMaxCorrections() != 2) {
            maxCorrections = config.getMaxCorrections();
        }

        // Archive diffs
        if (archiveDiffs && !config.isArchiveDiffs()) {
            archiveDiffs = false;
        }

        // Rules — config provides rules if CLI didn't specify any
        if ((rules == null || rules.isBlank()) && (ruleFile == null || ruleFile.isBlank())) {
            try {
                String configRules = config.buildRulesText(wd);
                if (!configRules.isBlank()) {
                    rules = configRules;
                }
            } catch (IOException e) {
                System.err.println("[enforcer] warning: could not load rules from config: " + e.getMessage());
            }
            // Also check ruleFile from config
            if (config.getRuleFile() != null && !config.getRuleFile().isBlank()
                    && (rules == null || rules.isBlank())) {
                ruleFile = config.getRuleFile();
            }
        }

        // Diff patterns file
        if (diffPatternsFile == null && config.getDiffPatternsFile() != null) {
            diffPatternsFile = config.getDiffPatternsFile();
        }

        // Judge settings
        if (judgeMode == null && config.getJudgeMode() != null) {
            judgeMode = config.getJudgeMode();
        }
        if (judgeProvider == null && config.getJudgeProvider() != null) {
            judgeProvider = config.getJudgeProvider();
        }
        if (judgeModel == null && config.getJudgeModel() != null) {
            judgeModel = config.getJudgeModel();
        }
        if (judgeApiKey == null && config.getJudgeApiKey() != null) {
            judgeApiKey = config.getJudgeApiKey();
        }
        if (judgeBaseUrl == null && config.getJudgeBaseUrl() != null) {
            judgeBaseUrl = config.getJudgeBaseUrl();
        }

        // MCP / connectivity
        if ((kompileUrl == null || kompileUrl.isBlank()) && config.getKompileUrl() != null) {
            kompileUrl = config.getKompileUrl();
        }
        if (mcpPort == 0 && config.getMcpPort() != 0) {
            mcpPort = config.getMcpPort();
        }

        // Tool injection
        if (injectTools && !config.isInjectTools()) {
            injectTools = false;
        }
        if (injectSkills && !config.isInjectSkills()) {
            injectSkills = false;
        }
        if (skipPermissions && !config.isSkipPermissions()) {
            skipPermissions = false;
        }
    }

    /**
     * Load the diff pattern evaluator from either:
     * 1. Explicit --diff-patterns file
     * 2. Diff-scoped rules embedded in the main policy rules
     * 3. Default file at .kompile/enforcer-diff-patterns.json
     */
    private DiffPatternEvaluator loadDiffPatternEvaluator(EnforcerPolicy policy, Path wd) {
        // Explicit file takes priority
        if (diffPatternsFile != null && !diffPatternsFile.isBlank()) {
            Path patternsPath = wd.resolve(diffPatternsFile);
            if (Files.exists(patternsPath)) {
                try {
                    return DiffPatternEvaluator.fromFile(patternsPath, objectMapper);
                } catch (IOException e) {
                    System.err.println("[enforcer] failed to load diff patterns from "
                            + diffPatternsFile + ": " + e.getMessage());
                }
            } else {
                System.err.println("[enforcer] diff patterns file not found: " + patternsPath);
            }
        }

        // Extract diff-scoped rules from the policy
        if (policy != null && policy.hasRules()) {
            KeywordEnforcerEvaluator kwEval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);
            DiffPatternEvaluator fromPolicy = DiffPatternEvaluator.fromKeywordRules(
                    kwEval.getRules(), policy.getRules());
            if (fromPolicy.isAvailable()) {
                return fromPolicy;
            }
        }

        // Check default location
        Path defaultPath = wd.resolve(".kompile").resolve("enforcer-diff-patterns.json");
        if (Files.exists(defaultPath)) {
            try {
                return DiffPatternEvaluator.fromFile(defaultPath, objectMapper);
            } catch (IOException e) {
                // Silent fallback
            }
        }

        return null;
    }

    /**
     * Bootstrap diff patterns by generating a prompt for an LLM, running the agent,
     * and saving the output as a pattern file.
     */
    private int bootstrapDiffPatterns(Path wd) {
        System.out.println("Bootstrapping diff patterns...");
        System.out.println("Language: " + bootstrapLanguage);
        System.out.println("Description: " + bootstrapPatterns);
        System.out.println();

        String prompt = DiffPatternEvaluator.buildBootstrapPrompt(bootstrapPatterns, bootstrapLanguage);

        // Use the configured agent to generate the patterns
        try {
            TerminalRenderer renderer = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(renderer);
            SubprocessAgentRunner runner = new SubprocessAgentRunner(
                    agent, wd.toString(), true, false, "", 0,
                    null, renderer, ascii);

            ChatHistory history = new ChatHistory("bootstrap-" + System.currentTimeMillis());
            ChatSessionMetrics metrics = new ChatSessionMetrics("bootstrap");
            history.open("pattern-bootstrap", agent, false);

            String output = runner.runMessage(prompt, history, metrics);
            runner.cleanup();
            history.close();

            if (output == null || output.isBlank()) {
                System.err.println("Agent returned empty output.");
                return 1;
            }

            // Extract JSON from the output (may be wrapped in markdown fences)
            String json = extractJson(output);
            if (json == null) {
                System.err.println("Could not extract JSON patterns from agent output.");
                System.err.println("Raw output:");
                System.err.println(output);
                return 1;
            }

            // Validate it parses as diff patterns
            DiffPatternEvaluator test = DiffPatternEvaluator.fromText(json, objectMapper);
            if (!test.isAvailable()) {
                System.err.println("Parsed 0 patterns from agent output. Raw JSON:");
                System.err.println(json);
                return 1;
            }

            // Write to output file
            Path outputPath = wd.resolve(bootstrapOutput);
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, json, StandardCharsets.UTF_8);

            System.out.println();
            System.out.println("Generated " + test.ruleCount() + " diff patterns → " + outputPath);
            System.out.println("Use with: kompile enforcer --diff-patterns=" + bootstrapOutput);
            System.out.println("Or embed BAN_DIFF: lines directly in your enforcer rules file.");
            return 0;

        } catch (Exception e) {
            System.err.println("Bootstrap failed: " + e.getMessage());
            return 1;
        }
    }

    private static String extractJson(String text) {
        // Try stripping markdown code fences
        String stripped = text.trim();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                stripped = stripped.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // Must start with [ or {
        if (stripped.startsWith("[") || stripped.startsWith("{")) {
            return stripped;
        }
        // Try finding the first [ in the text
        int bracket = text.indexOf('[');
        if (bracket >= 0) {
            int lastBracket = text.lastIndexOf(']');
            if (lastBracket > bracket) {
                return text.substring(bracket, lastBracket + 1);
            }
        }
        return null;
    }

    /**
     * Register this CLI enforcer session with the kompile-app server so the
     * web dashboard can monitor it. Best-effort — failures are logged but don't
     * block the session.
     */
    private void registerServerSession(String sessionId, String rules, String judgeDesc, Path wd) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("agentName", agent);
            body.put("rules", rules);
            body.put("maxCorrections", maxCorrections);
            body.put("judgeBackend", judgeDesc);
            body.put("workingDirectory", wd.toString());

            String url = kompileUrl.endsWith("/") ? kompileUrl : kompileUrl + "/";
            url += "api/enforcer/sessions/register?sessionId=" + sessionId;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.err.println("\033[2m[enforcer] session " + sessionId
                        + " registered with server for monitoring (judge=" + judgeDesc + ")\033[0m");
            }
        } catch (Exception e) {
            System.err.println("\033[2m[enforcer] could not register session with server: " + e.getMessage() + "\033[0m");
        }
    }
}
