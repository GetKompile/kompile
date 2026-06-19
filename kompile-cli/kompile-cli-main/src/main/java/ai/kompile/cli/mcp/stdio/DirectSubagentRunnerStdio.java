/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.PersistentAgentProcess;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.skill.SkillsInjection;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spawns external agent processes (qwen, claude, codex, etc.) as subagents.
 * <p>
 * CRITICAL: This class runs inside {@code kompile mcp-stdio}, where System.out is
 * the MCP JSON-RPC response pipe. All human-readable output MUST go to System.err,
 * and subprocess I/O must NOT inherit the parent's stdin/stdout to avoid corrupting
 * the MCP protocol stream or deadlocking on shared pipes.
 */
public class DirectSubagentRunnerStdio {

    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String DIM = "\033[2m";
    private static final String RED = "\033[31m";

    /** Max chars to hold in memory for the summary. Full output streams to file. */
    private static final int MAX_MEMORY_CHARS = 128_000; // ~128KB for summary extraction

    private final Path workDir;
    private final RoleManager roleManager;

    public DirectSubagentRunnerStdio(Path workDir) {
        this.workDir = workDir;
        this.roleManager = null;
    }

    public DirectSubagentRunnerStdio(Path workDir, RoleManager roleManager) {
        this.workDir = workDir;
        this.roleManager = roleManager;
    }

    private volatile McpSessionTracker sessionTracker;
    private volatile SkillsInjection skillsInjection;
    private volatile SystemPromptManager systemPromptManager;
    private volatile Map<String, String> extraEnvironment = Map.of();
    private volatile String lastTaskId;

    /** Cancellation signal for the currently running subagent. */
    private final AtomicBoolean cancelSignal = new AtomicBoolean(false);
    /** The managed subprocess currently being executed, if any. */
    private volatile Process currentProcess;
    /** The thread blocked inside {@link #executeSubagentProcess}. */
    private volatile Thread waitingThread;

    // NOTE: there is intentionally NO shared "claudeProcess" field here.
    // Each call to runClaudeStreaming() creates its own PersistentAgentProcess,
    // uses it for exactly one turn, then disposes it.  This is the ONLY design
    // that allows true parallelism: a shared process serialises all callers
    // through its internal sendLock and contaminates every subtask with the
    // accumulated conversation context from prior subtasks.

    public void setSessionTracker(McpSessionTracker tracker) { this.sessionTracker = tracker; }
    public void setSkillsInjection(SkillsInjection injection) { this.skillsInjection = injection; }
    public void setSystemPromptManager(SystemPromptManager spm) { this.systemPromptManager = spm; }
    public void setExtraEnvironment(Map<String, String> env) { this.extraEnvironment = env != null ? env : Map.of(); }
    public String getLastTaskId() { return lastTaskId; }

    /**
     * Cancel the currently running subagent, if any.
     * <p>
     * Sets the cancellation signal, destroys the managed subprocess (and its
     * descendants), and interrupts the waiting thread. This is safe to call
     * from any thread.
     */
    public void cancel() {
        cancelSignal.set(true);
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            killProcessTree(p);
        }
        Thread wt = waitingThread;
        if (wt != null) {
            wt.interrupt();
        }
    }

    /** Maximum subagent recursion depth before MCP tool injection is disabled. */
    private static final int MAX_SUBAGENT_DEPTH = 3;

    /**
     * Read the current subagent depth from the environment variable.
     * Returns 0 if not set (i.e., this is the top-level call).
     */
    private int getCurrentSubagentDepth() {
        String depthStr = System.getenv("KOMPILE_SUBAGENT_DEPTH");
        if (depthStr != null) {
            try {
                return Integer.parseInt(depthStr);
            } catch (NumberFormatException e) {
                // Ignore malformed values
            }
        }
        return 0;
    }

    public String runSubagent(AgentConfig agent, String prompt) throws Exception {
        String agentName = agent.getName();
        String effectivePrompt = prompt;

        // If a role is specified, prepend its system prompt to the user's prompt
        String roleName = agent.getRoleName();
        if (roleName != null && !roleName.isEmpty()) {
            RoleConfig role = resolveRole(roleName);
            if (role != null) {
                effectivePrompt = buildRolePrompt(role) + "\n\n---\n\n" + prompt;
                System.err.println(DIM + "  Role: " + roleName + RESET);
            } else {
                System.err.println(DIM + "  Warning: role '" + roleName + "' not found, using prompt as-is" + RESET);
            }
        }

        // Claude uses persistent streaming API — never spawns a process with -p
        if (agentName.toLowerCase().contains("claude")) {
            return runClaudeStreaming(agent, effectivePrompt);
        }

        String binary = resolveAgentBinary(agentName);

        if (binary == null) {
            return String.format(
                "Agent '%s' not found in PATH.\n\n" +
                "Please install the agent first:\n" +
                "  - Codex: Install from OpenAI\n" +
                "  - Qwen Code: npm install -g @anthropic-ai/qwen-code\n" +
                "  - Gemini CLI: npm install -g @anthropic-ai/gemini-code\n" +
                "  - OpenCode: go install github.com/opencode-ai/opencode@latest",
                agentName);
        }

        // All status output goes to stderr (stdout is the MCP JSON-RPC pipe)
        System.err.println(GREEN + "⟳ Spawning subagent: " + agentName + RESET);
        System.err.println(DIM + "  Binary: " + binary + RESET);
        System.err.println(DIM + "  Prompt: " + effectivePrompt.substring(0, Math.min(80, effectivePrompt.length())) + "..." + RESET);
        System.err.flush();

        // Check recursion depth to prevent infinite subagent spawning
        int currentDepth = getCurrentSubagentDepth();
        boolean injectMcpTools = currentDepth < MAX_SUBAGENT_DEPTH;
        if (!injectMcpTools) {
            System.err.println(DIM + "  Warning: Subagent depth limit reached (" + currentDepth +
                    "), skipping MCP tool injection to prevent recursion" + RESET);
        }

        // Codex exec mode cannot use MCP tools (calls are auto-cancelled).
        // It has its own shell exec tool which works fine in non-interactive mode.
        if (agentName.toLowerCase().contains("codex")) {
            injectMcpTools = false;
            System.err.println(DIM + "  Skipping MCP injection for codex (exec mode does not support MCP tools)" + RESET);
        }

        // Inject MCP tools and track the settings file for cleanup
        Path settingsFile = null;
        if (injectMcpTools) {
            try {
                settingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(workDir, agentName);
                if (settingsFile != null) {
                    System.err.println(DIM + "  Injected kompile MCP tools into " + settingsFile + RESET);
                    System.err.flush();
                }
            } catch (Exception e) {
                System.err.println(DIM + "  Warning: Could not configure MCP tools for subagent: "
                        + e.getMessage() + RESET);
                System.err.flush();
            }
        }

        try {
            return executeSubagentProcess(agentName, effectivePrompt, binary, prompt, currentDepth);
        } finally {
            // Always restore original settings after subagent exits
            if (settingsFile != null) {
                ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(settingsFile);
            }
        }
    }

    /**
     * Check if the subprocess output indicates a rate limit or token limit error.
     */
    private static boolean isRateLimited(String output, int exitCode) {
        if (exitCode == 0) return false; // successful completion is never rate limited
        if (output == null || output.isEmpty()) return false;

        String lower = output.toLowerCase();
        return lower.contains("rate limit") || lower.contains("rate_limit")
            || lower.contains("too many requests") || lower.contains("429")
            || lower.contains("token limit") || lower.contains("token_limit")
            || lower.contains("quota exceeded") || lower.contains("overloaded")
            || lower.contains("capacity") || lower.contains("resource_exhausted");
    }

    String executeSubagentProcess(String agentName, String effectivePrompt, String binary,
                                          String prompt, int currentDepth) throws Exception {
        List<String> cmd = buildAgentCommand(binary, agentName);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());

        // Increment and pass the subagent depth so child processes can enforce the same limit
        pb.environment().put("KOMPILE_SUBAGENT_DEPTH", String.valueOf(currentDepth + 1));

        // CRITICAL: Do NOT use inheritIO(). When running inside kompile mcp-stdio:
        //   - stdout is the MCP JSON-RPC pipe — subprocess output would corrupt it
        //   - stdin is the MCP JSON-RPC input — subprocess reads would consume messages
        // Instead: pipe stdin from this process, merge stderr into stdout, capture all output.
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectErrorStream(true); // merge stderr into stdout for unified capture

        cancelSignal.set(false);
        Process process = pb.start();
        currentProcess = process;
        waitingThread = Thread.currentThread();
        long startTime = System.currentTimeMillis();

        // Stream full output to a temp file while keeping a bounded buffer for summary.
        // This ensures nothing is lost even for very large outputs (multi-MB codex sessions).
        Path outputTempFile;
        try {
            Path resultsDir = workDir.resolve(".kompile").resolve("task-results");
            Files.createDirectories(resultsDir);
            outputTempFile = Files.createTempFile(resultsDir, "capture-" + agentName + "-", ".tmp");
        } catch (IOException e) {
            outputTempFile = Files.createTempFile("kompile-capture-", ".tmp");
        }
        final Path captureFile = outputTempFile;

        StringBuffer headBuffer = new StringBuffer();  // first N chars for summary
        StringBuffer tailBuffer = new StringBuffer();   // last N chars for summary
        final int TAIL_SIZE = 16_000;
        long[] totalCharsHolder = {0};

        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 java.io.BufferedWriter fileWriter = Files.newBufferedWriter(captureFile, StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    // Always write full output to file
                    fileWriter.write(buf, 0, n);

                    // Keep head buffer (first MAX_MEMORY_CHARS chars)
                    long total = totalCharsHolder[0];
                    if (total < MAX_MEMORY_CHARS) {
                        int toAppend = (int) Math.min(n, MAX_MEMORY_CHARS - total);
                        headBuffer.append(buf, 0, toAppend);
                    }

                    // Keep rolling tail buffer (last TAIL_SIZE chars)
                    tailBuffer.append(buf, 0, n);
                    if (tailBuffer.length() > TAIL_SIZE * 2) {
                        tailBuffer.delete(0, tailBuffer.length() - TAIL_SIZE);
                    }

                    totalCharsHolder[0] += n;
                }
                fileWriter.flush();
            } catch (IOException e) {
                // Process ended, stream closed
            }
        }, "subagent-output-" + agentName);
        outputReader.setDaemon(true);
        outputReader.start();

        // Write the prompt to the child's stdin and close it. This is the managed,
        // provider-agnostic way to deliver a one-shot task to a subagent.
        Thread stdinWriter = new Thread(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write(effectivePrompt.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                // Process may have exited before consuming all input
            }
        }, "subagent-stdin-" + agentName);
        stdinWriter.setDaemon(true);
        stdinWriter.start();

        int exitCode;
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelSignal.get() || Thread.interrupted()) {
                    cancelSignal.set(true);
                    killProcessTree(process);
                    break;
                }
            }
            if (process.isAlive()) {
                // Either cancelled or a spurious wakeup: give the process a moment to die.
                process.waitFor(5, TimeUnit.SECONDS);
            }
            if (cancelSignal.get()) {
                throw new InterruptedException("Subagent '" + agentName + "' was cancelled");
            }
            exitCode = process.isAlive() ? -1 : process.exitValue();
        } catch (InterruptedException e) {
            cancelSignal.set(true);
            killProcessTree(process);
            Thread.currentThread().interrupt();
            throw new InterruptedException("Subagent '" + agentName + "' was cancelled");
        } finally {
            waitingThread = null;
            currentProcess = null;
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            try { stdinWriter.join(2000); } catch (InterruptedException ignored) {}
        }

        outputReader.join(5000); // wait up to 5s for output thread to finish

        long elapsed = System.currentTimeMillis() - startTime;
        long totalChars = totalCharsHolder[0];
        String output = headBuffer.toString().trim();

        if (exitCode == 0) {
            System.err.println(DIM + "✓ Subagent '" + agentName + "' completed in " +
                String.format("%.1fs", elapsed / 1000.0) + RESET);
        } else {
            System.err.println(RED + "✗ Subagent '" + agentName + "' exited with code " +
                exitCode + " after " + String.format("%.1fs", elapsed / 1000.0) + RESET);
        }
        System.err.flush();

        // Check for rate limiting before returning output
        if (isRateLimited(output, exitCode)) {
            System.err.println(RED + "⚠ Subagent '" + agentName + "' hit rate limit" + RESET);
            System.err.flush();
            throw new RateLimitException(agentName, output);
        }

        // Return the captured output as the tool result (not "check output above")
        if (output.isEmpty() && totalChars == 0) {
            // Clean up empty capture file
            try { Files.deleteIfExists(captureFile); } catch (IOException ignored) {}
            return exitCode == 0
                ? String.format("Subagent '%s' completed successfully in %.1fs (no output captured).", agentName, elapsed / 1000.0)
                : String.format("Subagent '%s' exited with code %d after %.1fs (no output captured).", agentName, exitCode, elapsed / 1000.0);
        }

        String header = exitCode == 0
            ? String.format("Subagent '%s' completed in %.1fs", agentName, elapsed / 1000.0)
            : String.format("Subagent '%s' exited with code %d after %.1fs", agentName, exitCode, elapsed / 1000.0);

        // Rename capture file to final result file (full untruncated output)
        Path resultFile = renameResultFile(captureFile, agentName, header);
        System.err.println(DIM + "  Full output (" + totalChars + " chars) written to: " + resultFile + RESET);
        System.err.flush();

        // Build summary from head + tail buffers
        String tail = tailBuffer.toString();
        String summary = buildSummaryFromBuffers(output, tail, totalChars);

        StringBuilder result = new StringBuilder();
        result.append(header).append("\n\n");
        result.append("## Summary\n").append(summary).append("\n\n");
        result.append("**Full output (").append(totalChars).append(" chars) written to:** `")
              .append(resultFile.toAbsolutePath()).append("`\n");
        result.append("Use the `read` tool to access the full result if needed.");
        return result.toString();
    }

    /**
     * Destroy a process and its descendants. Tries a graceful destroy first,
     * then forcibly terminates anything still alive.
     */
    private void killProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            process.destroy();
            process.descendants().forEach(ProcessHandle::destroy);
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    private String resolveAgentBinary(String agentName) {
        // NOTE: claude is handled via streaming API in runClaudeStreaming(), never reaches here
        String binary = switch (agentName.toLowerCase()) {
            case "qwen", "qwen-code" -> "qwen";
            case "codex" -> "codex";
            case "gemini" -> "gemini";
            case "opencode", "open-code" -> "opencode";
            default -> agentName;
        };

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File candidate = new File(dir, binary);
                if (candidate.canExecute()) return candidate.getAbsolutePath();
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    File candidateExt = new File(dir, binary + ext);
                    if (candidateExt.canExecute()) return candidateExt.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * Build the provider-agnostic managed command for a subagent.
     * <p>
     * Only the resolved binary is returned. The prompt is delivered over stdin
     * after the process starts, so no provider prompt-mode flags (e.g. {@code -p},
     * {@code exec}, {@code run}), continuation flags, fork flags, or session flags
     * are added to the argument vector.
     */
    List<String> buildAgentCommand(String binary, String agentName) {
        return new ArrayList<>(List.of(binary));
    }

    /**
     * Run a claude subagent via the persistent stream-json protocol.
     * <p>
     * ISOLATION CONTRACT: A brand-new {@link PersistentAgentProcess} is created,
     * used for exactly ONE turn, and then closed.  This guarantees:
     * <ul>
     *   <li>True parallelism — concurrent callers (multi_task subtasks) each get
     *       their own OS process, unblocked by any shared sendLock.</li>
     *   <li>Isolated context — no conversation history leaks between subtasks.</li>
     *   <li>Deterministic lifecycle — the process dies as soon as the turn
     *       completes or fails, leaving no dangling processes.</li>
     * </ul>
     * There is intentionally NO "keep-alive" or process-reuse logic here.
     */
    private String runClaudeStreaming(AgentConfig agent, String prompt) throws Exception {
        long startTime = System.currentTimeMillis();
        System.err.println(GREEN + "⟳ Starting claude subagent (isolated stream-json process)" + RESET);
        System.err.println(DIM + "  Prompt: " + prompt.substring(0, Math.min(80, prompt.length())) + "..." + RESET);
        System.err.flush();

        PersistentAgentProcess proc = spawnFreshClaudeProcess(agent);
        try {
            String result = proc.sendMessage(prompt);

            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println(GREEN + "✓ Claude subagent completed in " +
                    String.format("%.1fs", elapsed / 1000.0) + " (session: " +
                    proc.getSessionId() + ")" + RESET);
            System.err.flush();

            return result.isEmpty() ? "(claude subagent returned empty response)" : result;
        } catch (PersistentAgentProcess.TimedOutException toe) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println(RED + "⏱ Claude subagent timed out after " +
                    String.format("%.1fs", elapsed / 1000.0) + ": " + toe.getMessage() + RESET);
            System.err.flush();
            // Re-throw so the caller (runSubtask / StdioMultiTaskTool) can record TIMED_OUT
            throw toe;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println(RED + "✗ Claude subagent failed after " +
                    String.format("%.1fs", elapsed / 1000.0) + ": " + e.getMessage() + RESET);
            System.err.flush();
            throw e;
        } finally {
            // Always dispose — never leak the subprocess
            proc.close();
        }
    }

    /**
     * Spawn a fresh, isolated {@link PersistentAgentProcess} for a single subtask.
     * <p>
     * Callers are responsible for closing the returned process via try-finally.
     * This method only creates and starts the process; it does not send any
     * messages.
     *
     * @param agent config containing optional system prompt / model override
     * @return a started (ready) process — caller must close it
     * @throws IOException          if the claude binary is missing or fails to start
     * @throws InterruptedException if the startup wait is interrupted
     */
    PersistentAgentProcess spawnFreshClaudeProcess(AgentConfig agent)
            throws IOException, InterruptedException {
        String binary = ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.resolveAgentBinary("claude");
        if (binary == null) {
            throw new IOException("claude not found on PATH");
        }

        String systemPrompt = agent.getSystemPrompt();

        PersistentAgentProcess proc = PersistentAgentProcess.builder(binary)
                .workDir(workDir)
                .systemPrompt(systemPrompt)
                .skipPermissions(true)
                .build();
        proc.start();
        System.err.println(DIM + "  Isolated claude process started (session: " +
                proc.getSessionId() + ")" + RESET);
        System.err.flush();
        return proc;
    }

    private RoleConfig resolveRole(String roleName) {
        if (roleManager != null) {
            return roleManager.getRole(roleName);
        }
        // Fallback: try to load roles directly from disk
        try {
            RoleManager fallbackManager = new RoleManager(workDir);
            return fallbackManager.getRole(roleName);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildRolePrompt(RoleConfig role) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Role: ").append(role.getDisplayName()).append("\n\n");
        sb.append(role.getSystemPrompt());
        if (role.getDescription() != null && !role.getDescription().isEmpty()) {
            sb.append("\n\n## Context\n");
            sb.append(role.getDescription());
        }
        return sb.toString();
    }

    // ── Optional extension hooks ──────────────────────────────────────────────

    /**
     * Install a session tracker for post-completion evaluation scoring.
     * The tracker type is kept as Object to avoid hard dependency; callers
     * that need the typed reference should cast.
     */
    public void setSessionTracker(Object sessionTracker) {
        // No-op by default — subclasses or enhanced runners may override
    }

    /**
     * Install a skills injection configuration so subagents can access
     * kompile skills (kompile-injected MCP prompts).
     */
    public void setSkillsInjection(Object skillsInjection) {
        // No-op by default — store if needed in subclasses
    }

    /**
     * Install a system prompt manager so subagents receive system prompt
     * injection via CLI flags or environment variables.
     */
    public void setSystemPromptManager(Object systemPromptManager) {
        // No-op by default — subclasses or enhanced runners may override
    }

    /** Max chars for summary sections. */
    private static final int SUMMARY_MAX_CHARS = 2000;

    /**
     * Rename the temp capture file to a permanent result file with header prepended.
     * The capture file already contains the full untruncated subprocess output.
     */
    Path renameResultFile(Path captureFile, String agentName, String header) {
        try {
            Path resultsDir = workDir.resolve(".kompile").resolve("task-results");
            Files.createDirectories(resultsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String sanitizedName = agentName.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path resultFile = resultsDir.resolve(sanitizedName + "-" + timestamp + ".md");

            // Prepend header to the capture file content
            String headerBlock = "# Task Result: " + agentName + "\n\n" + header + "\n\n---\n\n";
            Path tempResult = resultFile.resolveSibling(resultFile.getFileName() + ".tmp");
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(tempResult, StandardCharsets.UTF_8)) {
                writer.write(headerBlock);
                // Stream the capture file content (may be very large)
                try (java.io.BufferedReader reader = Files.newBufferedReader(captureFile, StandardCharsets.UTF_8)) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        writer.write(buf, 0, n);
                    }
                }
            }
            Files.move(tempResult, resultFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(captureFile);
            return resultFile;
        } catch (IOException e) {
            System.err.println(RED + "  Warning: Could not rename result file: " + e.getMessage() + RESET);
            // Fall back — the capture file itself has the full output
            return captureFile;
        }
    }

    /**
     * Build a concise summary from head and tail buffers.
     * The head buffer has the first ~128K chars, the tail buffer has the last ~16K chars.
     */
    String buildSummaryFromBuffers(String head, String tail, long totalChars) {
        if (totalChars <= SUMMARY_MAX_CHARS) {
            return head; // Small enough to return as-is
        }

        String[] headLines = head.split("\n");
        StringBuilder summary = new StringBuilder();

        // Take first ~20 lines
        int headLineCount = Math.min(20, headLines.length);
        int charCount = 0;
        for (int i = 0; i < headLineCount && charCount < SUMMARY_MAX_CHARS * 2 / 3; i++) {
            summary.append(headLines[i]).append("\n");
            charCount += headLines[i].length() + 1;
        }

        // Add truncation marker
        summary.append("\n... (").append(totalChars).append(" chars total) ...\n\n");

        // Take last ~10 lines from the tail buffer
        String[] tailLines = tail.split("\n");
        int tailStart = Math.max(0, tailLines.length - 10);
        for (int i = tailStart; i < tailLines.length; i++) {
            summary.append(tailLines[i]).append("\n");
        }

        return summary.toString().trim();
    }
}
