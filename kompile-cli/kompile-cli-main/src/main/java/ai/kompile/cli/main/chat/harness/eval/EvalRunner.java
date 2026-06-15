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

package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgentFlagOverrides;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.*;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Orchestrates running an {@link EvalSuite} by executing each case against
 * an agent CLI and evaluating the outcome.
 *
 * <p>Execution flow per case:
 * <ol>
 *   <li>Run optional setup commands</li>
 *   <li>Spawn agent CLI with the case's prompt (non-interactive)</li>
 *   <li>Capture stdout/stderr with timeout enforcement</li>
 *   <li>Run escape detection on captured output</li>
 *   <li>Evaluate assertions via {@link OutcomeEvaluator}</li>
 *   <li>Record result with outcome</li>
 *   <li>Run optional teardown commands</li>
 * </ol>
 */
public class EvalRunner {

    private final TerminalRenderer renderer;
    private final OutcomeEvaluator outcomeEvaluator;
    private final EscapeDetector escapeDetector;
    private final Path workingDirectory;

    /** Optional judge LLM evaluator — set via enableJudge() */
    private JudgeLlmEvaluator judge;

    /** Listener called after each case completes. */
    public interface CaseListener {
        void onCaseComplete(EvalCaseResult result, int index, int total);
    }

    private CaseListener caseListener;

    public EvalRunner(Path workingDirectory) {
        this.renderer = new TerminalRenderer();
        this.outcomeEvaluator = new OutcomeEvaluator();
        this.escapeDetector = new EscapeDetector();
        this.workingDirectory = workingDirectory;
    }

    public void setCaseListener(CaseListener listener) {
        this.caseListener = listener;
    }

    /**
     * Enable judge LLM evaluation during eval runs.
     * When enabled, each case result will include judge correctness/completeness scores,
     * and the OutcomeEvaluator will use those scores for cases with no assertions.
     */
    public void enableJudge() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        HarnessConfig config = HarnessConfig.load(mapper);
        config.setJudgeEnabled(true);
        this.judge = new JudgeLlmEvaluator(mapper, config);
        if (!judge.isAvailable()) {
            System.err.println("  Warning: Judge LLM not available (" + judge.describeBackend()
                    + "). Configure with: kompile perf config --judge-provider <provider> --judge-api-key <key>");
            this.judge = null;
        } else {
            System.out.println("  Judge LLM enabled: " + judge.describeBackend());
        }
    }

    /**
     * Run all enabled cases in a suite sequentially.
     */
    public EvalRunResult run(EvalSuite suite, String suiteFile) {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setRunId(UUID.randomUUID().toString().substring(0, 8));
        runResult.setSuiteName(suite.getName());
        runResult.setSuiteFile(suiteFile);
        runResult.setStartTime(Instant.now());

        List<EvalCase> cases = suite.enabledCases();
        List<EvalCaseResult> caseResults = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            EvalCase evalCase = cases.get(i);
            EvalCaseResult result = runCase(evalCase, suite);
            caseResults.add(result);

            if (caseListener != null) {
                caseListener.onCaseComplete(result, i + 1, cases.size());
            }
        }

        runResult.setCaseResults(caseResults);
        runResult.setEndTime(Instant.now());
        runResult.computeAggregates(suite.getRequiredPassRate());
        return runResult;
    }

    /**
     * Run a single eval case against an agent.
     */
    EvalCaseResult runCase(EvalCase evalCase, EvalSuite suite) {
        String agent = suite.effectiveAgent(evalCase);
        String model = suite.effectiveModel(evalCase);
        String provider = suite.effectiveProvider(evalCase);
        String workDir = suite.effectiveWorkingDirectory(evalCase);
        Path caseWorkDir = workDir != null ? Path.of(workDir) : workingDirectory;

        // Run setup commands
        if (evalCase.getSetup() != null) {
            for (String cmd : evalCase.getSetup()) {
                try {
                    runShellCommand(cmd, caseWorkDir, 30_000);
                } catch (Exception e) {
                    return EvalCaseResult.error(evalCase, "Setup failed: " + e.getMessage());
                }
            }
        }

        try {
            // Build and execute agent — internal (kompile) or external CLI
            Instant execStart = Instant.now();
            long startTime = System.currentTimeMillis();
            AgentExecResult execResult;
            if (isInternalAgent(agent)) {
                execResult = executeInternal(evalCase.getPrompt(),
                        caseWorkDir, evalCase.getTimeoutMs(), evalCase.getEnv());
            } else {
                execResult = executeAgent(agent, evalCase.getPrompt(),
                        caseWorkDir, evalCase.getTimeoutMs(), evalCase.getEnv());
            }
            long elapsed = System.currentTimeMillis() - startTime;

            // Harvest tool calls from the agent's session files (best-effort)
            Map<String, Integer> toolCalls = SessionHarvester.harvestToolCalls(
                    agent != null ? agent : "claude", caseWorkDir, execStart);

            // Run escape detection
            String taskType = JudgeLlmEvaluator.detectTaskType(agent, execResult.output);
            TurnMetrics metrics = TurnMetrics.builder()
                    .agentOutput(execResult.output)
                    .agentName(agent != null ? agent : "unknown")
                    .model(model != null ? model : "unknown")
                    .provider(provider != null ? provider : "unknown")
                    .hitMaxSteps(false)
                    .taskPrompt(evalCase.getPrompt())
                    .build();

            EscapeDetector.EscapeResult escapeResult = escapeDetector.detect(metrics, taskType);

            // Run judge LLM evaluation if enabled
            float judgeCorrectness = 0;
            float judgeCompleteness = 0;
            String judgeReasoning = null;
            float compositeScore = 0;

            if (judge != null && !execResult.timedOut) {
                try {
                    JudgeDimensions dims = judge.evaluate(metrics, taskType,
                            agent != null ? agent : "unknown");
                    if (dims.isValid()) {
                        judgeCorrectness = dims.getCorrectness();
                        judgeCompleteness = dims.getCompleteness();
                        judgeReasoning = dims.getReasoning();
                        compositeScore = dims.weightedAverage();
                    } else if (dims.isError()) {
                        System.err.println("  Warning: Judge evaluation failed for case "
                                + evalCase.getId() + ": " + dims.getErrorDetail());
                    }
                } catch (Exception e) {
                    System.err.println("  Warning: Judge evaluation error for case "
                            + evalCase.getId() + ": " + e.getMessage());
                }
            }

            // Evaluate outcome with harvested tool calls and judge scores
            EvalCaseResult result = outcomeEvaluator.evaluate(
                    evalCase,
                    execResult.output,
                    escapeResult,
                    false, // hitMaxSteps — we enforce timeout externally
                    0,     // tool call errors — not available from external agent
                    toolCalls.isEmpty() ? null : toolCalls,
                    compositeScore, judgeCorrectness, judgeCompleteness, judgeReasoning
            );

            // Record tool call count in result
            int totalTools = toolCalls.values().stream().mapToInt(Integer::intValue).sum();
            result.setToolCallsTotal(totalTools);

            // Set execution metadata
            result.setAgent(agent);
            result.setModel(model);
            result.setProvider(provider);
            result.setExecutionTimeMs(elapsed);

            // If agent process failed (non-zero exit) and no escape was detected,
            // mark as failed
            if (execResult.exitCode != 0 && !result.isHadEscape()
                    && result.getOutcome() != TaskOutcome.FAILED) {
                result.setOutcome(TaskOutcome.FAILED);
                result.setOutcomeReason("Agent process exited with code " + execResult.exitCode);
            }

            // If timed out
            if (execResult.timedOut) {
                result.setOutcome(TaskOutcome.TIMED_OUT);
                result.setOutcomeReason("Agent timed out after " + evalCase.getTimeoutMs() + "ms");
            }

            return result;
        } catch (Exception e) {
            return EvalCaseResult.error(evalCase, e.getMessage());
        } finally {
            // Run teardown commands
            if (evalCase.getTeardown() != null) {
                for (String cmd : evalCase.getTeardown()) {
                    try {
                        runShellCommand(cmd, caseWorkDir, 30_000);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * Execute an agent CLI non-interactively and capture output.
     */
    AgentExecResult executeAgent(String agentName, String prompt,
                                         Path workDir, long timeoutMs,
                                         Map<String, String> env) throws Exception {
        if (agentName == null) {
            agentName = "claude";
        }

        String binary = findBinary(agentName);
        if (binary == null) {
            throw new IllegalStateException("Agent binary not found: " + agentName
                    + ". Ensure it is installed and on PATH.");
        }

        List<String> cmd = buildAgentCommand(binary, agentName, prompt, workDir);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        // Set environment variables
        if (env != null) {
            pb.environment().putAll(env);
        }

        // Redirect stdin from /dev/null
        String osName = System.getProperty("os.name").toLowerCase();
        File nullDevice = osName.contains("win") ? new File("NUL") : new File("/dev/null");
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice));

        Process process = pb.start();

        // Capture output with size limit
        StringBuilder output = new StringBuilder();
        int maxChars = 500_000; // 500KB output limit
        boolean timedOut = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> readFuture = executor.submit(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() < maxChars) {
                            output.append(line).append("\n");
                        }
                    }
                } catch (IOException ignored) {}
            });

            try {
                readFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
                process.destroyForcibly();
                readFuture.cancel(true);
            }
            executor.shutdownNow();
        }

        int exitCode = timedOut ? -1 : process.waitFor();

        return new AgentExecResult(output.toString(), exitCode, timedOut);
    }

    record AgentExecResult(String output, int exitCode, boolean timedOut) {}

    /**
     * Check if this agent name refers to the kompile-internal execution path.
     */
    private boolean isInternalAgent(String agentName) {
        if (agentName == null) return false;
        String name = agentName.toLowerCase();
        return name.equals("kompile") || name.equals("internal") || name.equals("local");
    }

    /**
     * Execute using the kompile-internal AgenticChatLoop directly (in-process).
     * This avoids spawning an external CLI and uses the configured LLM directly.
     */
    AgentExecResult executeInternal(String prompt, Path workDir,
                                            long timeoutMs, Map<String, String> env) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        try {
            ChatConfig chatConfig = ChatConfig.loadOrFromEnv();
            if (chatConfig == null || chatConfig.getApiKey() == null) {
                return new AgentExecResult(
                        "Error: No LLM configuration found. Run 'kompile chat --setup' to configure.",
                        1, false);
            }

            DirectLlmClient directClient = new DirectLlmClient(chatConfig, mapper);
            PermissionService permissionService = new PermissionService();
            permissionService.setAutoApproveAll(true); // eval runs are non-interactive
            AgentRegistry agentRegistry = new AgentRegistry();
            BackgroundProcessManager processManager = new BackgroundProcessManager(
                    "eval-" + UUID.randomUUID().toString().substring(0, 8));
            ToolRegistry toolRegistry = ToolRegistryFactory.create(
                    mapper, "", agentRegistry, permissionService,
                    renderer, processManager, chatConfig, null);

            AgenticChatLoop loop = new AgenticChatLoop(
                    null, mapper, toolRegistry, permissionService,
                    agentRegistry, workDir, directClient, processManager);

            String sessionId = "eval-" + UUID.randomUUID().toString().substring(0, 8);

            // Execute with timeout
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(() ->
                    loop.chat(prompt, sessionId, "coder", "kompile", false));

            try {
                String response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                executor.shutdownNow();
                return new AgentExecResult(
                        response != null ? response : "", 0, false);
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                return new AgentExecResult("", -1, true);
            }
        } catch (Exception e) {
            return new AgentExecResult(
                    "Internal execution error: " + e.getMessage(), 1, false);
        }
    }

    /**
     * Build the non-interactive command for each agent type.
     */
    private List<String> buildAgentCommand(String binary, String agentName, String prompt, Path workDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);

        String name = agentName.toLowerCase();
        if (name.contains("claude")) {
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agentName, true, workDir);
            cmd.add("-p");
            cmd.add(prompt);
        } else if (name.contains("codex")) {
            cmd.add("exec");
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agentName, true, workDir);
            cmd.add(prompt);
        } else if (name.contains("qwen")) {
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agentName, true, workDir);
            cmd.add("-p");
            cmd.add(prompt);
        } else if (name.contains("gemini")) {
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agentName, true, workDir);
            cmd.add("-p");
            cmd.add(prompt);
        } else if (name.contains("opencode")) {
            cmd.add("run");
            cmd.add(prompt);
        } else {
            cmd.add("-p");
            cmd.add(prompt);
        }

        return cmd;
    }

    private String findBinary(String agentName) {
        String name = agentName.toLowerCase();
        // Map agent names to expected binary names
        String binaryName;
        if (name.contains("claude")) binaryName = "claude";
        else if (name.contains("codex")) binaryName = "codex";
        else if (name.contains("qwen")) binaryName = "qwen";
        else if (name.contains("gemini")) binaryName = "gemini";
        else if (name.contains("opencode")) binaryName = "opencode";
        else binaryName = agentName;

        // Check PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, binaryName);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }

        // Try which/where
        try {
            String whichCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            Process p = new ProcessBuilder(whichCmd, binaryName)
                    .redirectErrorStream(true).start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !result.isEmpty()) {
                return result.split("\n")[0].trim();
            }
        } catch (Exception ignored) {}

        return null;
    }

    private void runShellCommand(String command, Path workDir, long timeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Setup command timed out: " + command);
        }
        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("Setup command failed (exit " + process.exitValue() + "): " + output);
        }
    }
}
